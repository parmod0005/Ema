package com.parmod.ema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class TradingViewModel : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
    private var feedJob: Job? = null
    private var tick = 0
    private var autoTradeTakenForSignal = false
    private val livePrices = ArrayDeque<Double>()

    fun connectLive(accessToken: String, expiryDate: String) {
        if (accessToken.isBlank() || expiryDate.isBlank()) {
            _state.value = _state.value.copy(message = "Enter Upstox token and expiry date")
            return
        }
        feedJob?.cancel()
        val client = UpstoxLiveClient(accessToken.trim())
        livePrices.clear()
        _state.value = _state.value.copy(
            connectionMode = ConnectionMode.UPSTOX,
            isConnected = false,
            executionMode = ExecutionMode.PAPER,
            message = "Connecting to Upstox live market data…",
        )
        feedJob = viewModelScope.launch {
            while (true) {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        client.fetchSnapshot(_state.value.index, expiryDate.trim())
                    }
                    publishLiveSnapshot(snapshot)
                    delay(2_000)
                } catch (error: Exception) {
                    _state.value = _state.value.copy(
                        isConnected = false,
                        message = error.message?.take(180) ?: "Upstox live-data error",
                    )
                    delay(5_000)
                }
            }
        }
    }

    fun connectDemo() {
        feedJob?.cancel()
        livePrices.clear()
        _state.value = _state.value.copy(connectionMode = ConnectionMode.DEMO, isConnected = true, executionMode = ExecutionMode.PAPER, message = "Demo feed connected")
        feedJob = viewModelScope.launch { while (true) { publishDemoTick(); delay(1_000) } }
    }

    fun disconnect() { feedJob?.cancel(); feedJob = null; _state.value = _state.value.copy(isConnected = false, message = "Disconnected") }
    fun selectIndex(index: MarketIndex) { closePosition("Index changed"); livePrices.clear(); tick = 0; _state.value = _state.value.copy(index = index) }
    fun setTradingMode(mode: TradingMode) { autoTradeTakenForSignal = false; _state.value = _state.value.copy(tradingMode = mode, message = "$mode mode selected") }
    fun setExecutionMode(mode: ExecutionMode) {
        if (mode == ExecutionMode.LIVE) {
            _state.value = _state.value.copy(executionMode = ExecutionMode.PAPER, message = "Live orders are disabled; market data and paper trading remain live")
        } else _state.value = _state.value.copy(executionMode = mode)
    }
    fun buyCe() = openPaperPosition(PositionSide.CE)
    fun buyPe() = openPaperPosition(PositionSide.PE)
    fun exitPosition() = closePosition("Position exited")

    private fun publishLiveSnapshot(snapshot: UpstoxLiveClient.Snapshot) {
        livePrices.addLast(snapshot.spot)
        while (livePrices.size > 120) livePrices.removeFirst()
        val signal = calculateLiveSignal(snapshot.spot, snapshot.options)
        val current = _state.value
        val updatedPosition = updatePosition(current.position, snapshot.options)
        _state.value = current.copy(
            isConnected = true,
            spotPrice = snapshot.spot,
            optionChain = snapshot.options,
            signal = signal,
            position = updatedPosition,
            pnl = updatedPosition?.pnl ?: 0.0,
            message = "UPSTOX LIVE · ${snapshot.options.size} contracts · paper orders only",
        )
        runAutoPaperIfEligible()
    }

    private fun calculateLiveSignal(spot: Double, chain: List<OptionQuote>): SignalSnapshot {
        if (livePrices.size < 20) return waitSignal("Collecting live ticks ${livePrices.size}/20")
        val prices = livePrices.toList()
        val fast = ema(prices, 8)
        val slow = ema(prices, 20)
        val previousFast = ema(prices.dropLast(3), 8)
        val slope = fast - previousFast
        val separation = abs(fast - slow) / spot
        val atmCalls = chain.filter { it.type == "CE" && abs(it.delta) in 0.35..0.70 }
        val atmPuts = chain.filter { it.type == "PE" && abs(it.delta) in 0.35..0.70 }
        val callOiChange = atmCalls.sumOf { it.changeInOpenInterest }
        val putOiChange = atmPuts.sumOf { it.changeInOpenInterest }
        val bullish = fast > slow && slope > 0 && separation > 0.00008
        val bearish = fast < slow && slope < 0 && separation > 0.00008
        val oiSupportsBull = putOiChange >= callOiChange
        val oiSupportsBear = callOiChange >= putOiChange
        val confidence = (65 + minOf(15, (abs(slope) / spot * 100_000).toInt()) + if ((bullish && oiSupportsBull) || (bearish && oiSupportsBear)) 10 else 0).coerceAtMost(92)
        return when {
            bullish && confidence >= 75 -> SignalSnapshot(SignalAction.BUY_CE, confidence, TrendDirection.BULLISH, spot, spot * 0.9985, spot * 1.003, listOf("Live EMA fast above slow", "Positive live momentum", "Option-chain OI confirmation: ${if (oiSupportsBull) "yes" else "weak"}"))
            bearish && confidence >= 75 -> SignalSnapshot(SignalAction.BUY_PE, confidence, TrendDirection.BEARISH, spot, spot * 1.0015, spot * 0.997, listOf("Live EMA fast below slow", "Negative live momentum", "Option-chain OI confirmation: ${if (oiSupportsBear) "yes" else "weak"}"))
            else -> waitSignal("Live anti-chop filter active")
        }
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var result = values.first()
        values.drop(1).forEach { result = it * k + result * (1 - k) }
        return result
    }

    private fun waitSignal(reason: String) = SignalSnapshot(SignalAction.WAIT, 45, TrendDirection.NEUTRAL, null, null, null, listOf(reason, "Waiting for confirmed expansion"))
    private fun updatePosition(position: PaperPosition?, chain: List<OptionQuote>): PaperPosition? = position?.let { p -> chain.firstOrNull { it.strike == p.strike && it.type == p.side.name }?.let { p.copy(currentPrice = it.ltp) } ?: p }

    private fun publishDemoTick() {
        val current = _state.value
        val base = if (current.index == MarketIndex.NIFTY) 24_550.0 else 80_200.0
        val step = if (current.index == MarketIndex.NIFTY) 50 else 100
        val wave = if (tick < 30) tick * 2.0 else 60.0 - (tick - 30) * 1.8
        val spot = base + wave + Random.nextDouble(-1.5, 1.5)
        val atm = (spot / step).toInt() * step
        val chain = buildDemoChain(spot, atm, step)
        livePrices.addLast(spot); while (livePrices.size > 120) livePrices.removeFirst()
        publishLiveSnapshot(UpstoxLiveClient.Snapshot(spot, chain))
        tick = (tick + 1) % 65
    }

    private fun buildDemoChain(spot: Double, atm: Int, step: Int): List<OptionQuote> = (-5..5).flatMap { offset ->
        val strike = atm + offset * step
        val distance = spot - strike
        val timeValue = max(18.0, 105.0 - abs(offset) * 12.0)
        val gamma = max(0.0002, 0.0022 - abs(offset) * 0.00025)
        val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
        listOf(
            OptionQuote(strike.toDouble(), "CE", max(0.0, distance) + timeValue, 90_000L, 1_500L - offset * 170L, ceDelta, gamma, offset == 0),
            OptionQuote(strike.toDouble(), "PE", max(0.0, -distance) + timeValue, 94_000L, -700L + offset * 190L, ceDelta - 1.0, gamma, offset == 0),
        )
    }

    private fun openPaperPosition(side: PositionSide) {
        val current = _state.value
        if (!current.isConnected) { _state.value = current.copy(message = "Connect Upstox live data first"); return }
        if (current.position != null) { _state.value = current.copy(message = "Exit current position first"); return }
        val quote = current.optionChain.firstOrNull { it.isAtm && it.type == side.name } ?: return
        val lot = if (current.index == MarketIndex.NIFTY) 65 else 20
        _state.value = current.copy(position = PaperPosition(side, quote.strike, lot, quote.ltp, quote.ltp), pnl = 0.0, message = "LIVE-DATA PAPER BUY ${quote.strike.toInt()} ${side.name} × $lot")
    }

    private fun closePosition(reason: String) {
        val current = _state.value
        val realized = current.position?.pnl ?: 0.0
        _state.value = current.copy(position = null, pnl = 0.0, message = "$reason · P&L ₹${"%.2f".format(realized)}")
        autoTradeTakenForSignal = false
    }

    private fun runAutoPaperIfEligible() {
        val current = _state.value
        if (current.tradingMode != TradingMode.AUTO || current.executionMode != ExecutionMode.PAPER) return
        if (current.position == null && !autoTradeTakenForSignal && current.signal.confidence >= 80) {
            when (current.signal.action) { SignalAction.BUY_CE -> openPaperPosition(PositionSide.CE); SignalAction.BUY_PE -> openPaperPosition(PositionSide.PE); SignalAction.WAIT -> Unit }
            autoTradeTakenForSignal = true
        }
        val position = _state.value.position ?: return
        val pct = if (position.entryPrice == 0.0) 0.0 else (position.currentPrice - position.entryPrice) / position.entryPrice
        val reversed = (position.side == PositionSide.CE && current.signal.action == SignalAction.BUY_PE) || (position.side == PositionSide.PE && current.signal.action == SignalAction.BUY_CE)
        if (pct <= -0.15 || pct >= 0.30 || reversed) closePosition("Auto exit")
    }
}
