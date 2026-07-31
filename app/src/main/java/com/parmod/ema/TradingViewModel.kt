package com.parmod.ema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxTickStream
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
    private var tickStream: UpstoxTickStream? = null
    private var tick = 0
    private var autoTradeTakenForSignal = false
    private val livePrices = ArrayDeque<Double>()
    private var underlyingKey = ""

    fun connectLive(accessToken: String, expiryDate: String) {
        if (accessToken.isBlank() || expiryDate.isBlank()) {
            _state.value = _state.value.copy(message = "Enter Upstox token and expiry date")
            return
        }
        disconnectInternal()
        livePrices.clear()
        _state.value = _state.value.copy(connectionMode = ConnectionMode.UPSTOX, isConnected = false, executionMode = ExecutionMode.PAPER, message = "Bootstrapping Upstox option instruments…")
        feedJob = viewModelScope.launch {
            try {
                val client = UpstoxLiveClient(accessToken.trim())
                val snapshot = withContext(Dispatchers.IO) { client.fetchSnapshot(_state.value.index, expiryDate.trim()) }
                underlyingKey = snapshot.underlyingKey
                publishLiveSnapshot(snapshot)
                val keys = listOf(snapshot.underlyingKey) + snapshot.options.mapNotNull { it.instrumentKey.takeIf(String::isNotBlank) }
                tickStream = UpstoxTickStream(
                    authorizedUrlProvider = { client.authorizedSocketUrl() },
                    instrumentKeys = keys.distinct(),
                    listener = object : UpstoxTickStream.Listener {
                        override fun onOpen() {
                            _state.value = _state.value.copy(isConnected = true, message = "UPSTOX V3 TICK STREAM · ${keys.size} instruments · paper only")
                        }
                        override fun onTick(tick: UpstoxTickStream.Tick) { applyTick(tick) }
                        override fun onError(message: String) { _state.value = _state.value.copy(message = message) }
                        override fun onClosed() { _state.value = _state.value.copy(isConnected = false, message = "Tick stream closed") }
                    },
                ).also { withContext(Dispatchers.IO) { it.connect() } }
            } catch (error: Exception) {
                _state.value = _state.value.copy(isConnected = false, message = error.message?.take(180) ?: "Upstox connection error")
            }
        }
    }

    fun connectDemo() {
        disconnectInternal()
        livePrices.clear()
        _state.value = _state.value.copy(connectionMode = ConnectionMode.DEMO, isConnected = true, executionMode = ExecutionMode.PAPER, message = "Demo feed connected")
        feedJob = viewModelScope.launch { while (true) { publishDemoTick(); delay(500) } }
    }

    fun disconnect() { disconnectInternal(); _state.value = _state.value.copy(isConnected = false, message = "Disconnected") }
    private fun disconnectInternal() { feedJob?.cancel(); feedJob = null; tickStream?.disconnect(); tickStream = null }
    fun selectIndex(index: MarketIndex) { closePosition("Index changed"); disconnectInternal(); livePrices.clear(); tick = 0; _state.value = _state.value.copy(index = index, isConnected = false, message = "Reconnect for ${index.name}") }
    fun setTradingMode(mode: TradingMode) { autoTradeTakenForSignal = false; _state.value = _state.value.copy(tradingMode = mode, message = "$mode mode selected") }
    fun setExecutionMode(mode: ExecutionMode) { _state.value = if (mode == ExecutionMode.LIVE) _state.value.copy(executionMode = ExecutionMode.PAPER, message = "Live orders disabled; tick data and paper trading remain live") else _state.value.copy(executionMode = mode) }
    fun buyCe() = openPaperPosition(PositionSide.CE)
    fun buyPe() = openPaperPosition(PositionSide.PE)
    fun exitPosition() = closePosition("Position exited")

    private fun applyTick(tick: UpstoxTickStream.Tick) {
        val current = _state.value
        var spot = current.spotPrice
        var chain = current.optionChain
        if (tick.instrumentKey == underlyingKey && tick.ltp != null) {
            spot = tick.ltp
            livePrices.addLast(spot)
            while (livePrices.size > 600) livePrices.removeFirst()
        } else {
            chain = chain.map { quote ->
                if (quote.instrumentKey != tick.instrumentKey) quote else quote.copy(
                    ltp = tick.ltp ?: quote.ltp,
                    openInterest = tick.oi ?: quote.openInterest,
                    delta = tick.delta ?: quote.delta,
                    gamma = tick.gamma ?: quote.gamma,
                    lastTickMillis = tick.feedTimestamp,
                )
            }
        }
        val position = updatePosition(current.position, chain)
        _state.value = current.copy(
            isConnected = true,
            spotPrice = spot,
            optionChain = chain,
            signal = calculateLiveSignal(spot, chain),
            position = position,
            pnl = position?.pnl ?: 0.0,
            lastTickMillis = tick.feedTimestamp,
            ticksReceived = current.ticksReceived + 1,
            message = "UPSTOX V3 TICKS ${current.ticksReceived + 1} · paper orders only",
        )
        runAutoPaperIfEligible()
    }

    private fun publishLiveSnapshot(snapshot: UpstoxLiveClient.Snapshot) {
        livePrices.addLast(snapshot.spot)
        val position = updatePosition(_state.value.position, snapshot.options)
        _state.value = _state.value.copy(spotPrice = snapshot.spot, optionChain = snapshot.options, position = position, pnl = position?.pnl ?: 0.0, signal = calculateLiveSignal(snapshot.spot, snapshot.options))
    }

    private fun calculateLiveSignal(spot: Double, chain: List<OptionQuote>): SignalSnapshot {
        if (spot <= 0 || livePrices.size < 20) return waitSignal("Collecting ticks ${livePrices.size}/20")
        val prices = livePrices.toList()
        val fast = ema(prices, 8)
        val slow = ema(prices, 20)
        val previousFast = ema(prices.dropLast(minOf(3, prices.size - 1)), 8)
        val slope = fast - previousFast
        val separation = abs(fast - slow) / spot
        val calls = chain.filter { it.type == "CE" && abs(it.delta) in 0.35..0.70 }
        val puts = chain.filter { it.type == "PE" && abs(it.delta) in 0.35..0.70 }
        val callOi = calls.sumOf { it.changeInOpenInterest }
        val putOi = puts.sumOf { it.changeInOpenInterest }
        val bullish = fast > slow && slope > 0 && separation > 0.00005
        val bearish = fast < slow && slope < 0 && separation > 0.00005
        val oiConfirm = (bullish && putOi >= callOi) || (bearish && callOi >= putOi)
        val confidence = (65 + minOf(17, (abs(slope) / spot * 120_000).toInt()) + if (oiConfirm) 10 else 0).coerceAtMost(92)
        return when {
            bullish && confidence >= 75 -> SignalSnapshot(SignalAction.BUY_CE, confidence, TrendDirection.BULLISH, spot, spot * 0.9985, spot * 1.003, listOf("Tick EMA fast above slow", "Positive tick momentum", "Live option OI ${if (oiConfirm) "confirms" else "is weak"}"))
            bearish && confidence >= 75 -> SignalSnapshot(SignalAction.BUY_PE, confidence, TrendDirection.BEARISH, spot, spot * 1.0015, spot * 0.997, listOf("Tick EMA fast below slow", "Negative tick momentum", "Live option OI ${if (oiConfirm) "confirms" else "is weak"}"))
            else -> waitSignal("Tick anti-chop filter active")
        }
    }

    private fun ema(values: List<Double>, period: Int): Double { if (values.isEmpty()) return 0.0; val k = 2.0 / (period + 1.0); var r = values.first(); values.drop(1).forEach { r = it * k + r * (1 - k) }; return r }
    private fun waitSignal(reason: String) = SignalSnapshot(SignalAction.WAIT, 45, TrendDirection.NEUTRAL, null, null, null, listOf(reason, "Waiting for confirmed expansion"))
    private fun updatePosition(position: PaperPosition?, chain: List<OptionQuote>): PaperPosition? = position?.let { p -> chain.firstOrNull { it.strike == p.strike && it.type == p.side.name }?.let { p.copy(currentPrice = it.ltp) } ?: p }

    private fun publishDemoTick() {
        val current = _state.value
        val base = if (current.index == MarketIndex.NIFTY) 24_550.0 else 80_200.0
        val step = if (current.index == MarketIndex.NIFTY) 50 else 100
        val spot = base + (if (tick < 30) tick * 2.0 else 60.0 - (tick - 30) * 1.8) + Random.nextDouble(-1.5, 1.5)
        val atm = (spot / step).toInt() * step
        val chain = buildDemoChain(spot, atm, step)
        livePrices.addLast(spot); while (livePrices.size > 600) livePrices.removeFirst()
        val position = updatePosition(current.position, chain)
        _state.value = current.copy(spotPrice = spot, optionChain = chain, signal = calculateLiveSignal(spot, chain), position = position, pnl = position?.pnl ?: 0.0, ticksReceived = current.ticksReceived + 1, lastTickMillis = System.currentTimeMillis())
        runAutoPaperIfEligible(); tick = (tick + 1) % 65
    }

    private fun buildDemoChain(spot: Double, atm: Int, step: Int): List<OptionQuote> = (-5..5).flatMap { offset ->
        val strike = atm + offset * step; val distance = spot - strike; val tv = max(18.0, 105.0 - abs(offset) * 12.0); val gamma = max(0.0002, 0.0022 - abs(offset) * 0.00025); val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
        listOf(OptionQuote(strike.toDouble(), "CE", max(0.0, distance) + tv, 90_000L, 1_500L - offset * 170L, ceDelta, gamma, offset == 0), OptionQuote(strike.toDouble(), "PE", max(0.0, -distance) + tv, 94_000L, -700L + offset * 190L, ceDelta - 1.0, gamma, offset == 0))
    }

    private fun openPaperPosition(side: PositionSide) {
        val current = _state.value
        if (!current.isConnected) { _state.value = current.copy(message = "Connect Upstox tick data first"); return }
        if (current.position != null) { _state.value = current.copy(message = "Exit current position first"); return }
        val q = current.optionChain.firstOrNull { it.isAtm && it.type == side.name } ?: return
        val lot = if (current.index == MarketIndex.NIFTY) 65 else 20
        _state.value = current.copy(position = PaperPosition(side, q.strike, lot, q.ltp, q.ltp), pnl = 0.0, message = "TICK-DATA PAPER BUY ${q.strike.toInt()} ${side.name} × $lot")
    }

    private fun closePosition(reason: String) { val c = _state.value; val realized = c.position?.pnl ?: 0.0; _state.value = c.copy(position = null, pnl = 0.0, message = "$reason · P&L ₹${"%.2f".format(realized)}"); autoTradeTakenForSignal = false }
    private fun runAutoPaperIfEligible() {
        val c = _state.value
        if (c.tradingMode != TradingMode.AUTO || c.executionMode != ExecutionMode.PAPER) return
        if (c.position == null && !autoTradeTakenForSignal && c.signal.confidence >= 80) { when (c.signal.action) { SignalAction.BUY_CE -> openPaperPosition(PositionSide.CE); SignalAction.BUY_PE -> openPaperPosition(PositionSide.PE); SignalAction.WAIT -> Unit }; autoTradeTakenForSignal = true }
        val p = _state.value.position ?: return
        val pct = if (p.entryPrice == 0.0) 0.0 else (p.currentPrice - p.entryPrice) / p.entryPrice
        val reversed = (p.side == PositionSide.CE && c.signal.action == SignalAction.BUY_PE) || (p.side == PositionSide.PE && c.signal.action == SignalAction.BUY_CE)
        if (pct <= -0.15 || pct >= 0.30 || reversed) closePosition("Auto exit")
    }

    override fun onCleared() { disconnectInternal(); super.onCleared() }
}
