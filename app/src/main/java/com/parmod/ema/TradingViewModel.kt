package com.parmod.ema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxTickStream
import com.parmod.ema.engine.OptionSelector
import com.parmod.ema.engine.SignalEngineV2
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
    private val signalEngineV2 = SignalEngineV2()
    private val optionSelector = OptionSelector()
    private var underlyingKey = ""
    private var savedAccessToken = ""
    private var savedExpiry = ""

    fun connectLive(accessToken: String, expiryDate: String) {
        if (accessToken.isBlank() || expiryDate.isBlank()) {
            _state.value = _state.value.copy(message = "Paste a valid Upstox token and select expiry")
            return
        }
        savedAccessToken = accessToken.trim()
        savedExpiry = expiryDate.trim()
        connectSelectedIndex()
    }

    private fun connectSelectedIndex() {
        if (savedAccessToken.isBlank() || savedExpiry.isBlank()) return
        disconnectInternal()
        livePrices.clear()
        val selectedIndex = _state.value.index
        _state.value = _state.value.copy(
            connectionMode = ConnectionMode.UPSTOX,
            isConnected = false,
            executionMode = ExecutionMode.PAPER,
            optionChain = emptyList(),
            spotPrice = 0.0,
            message = "Loading ${selectedIndex.name} contracts and live feed…",
        )
        feedJob = viewModelScope.launch {
            try {
                val client = UpstoxLiveClient(savedAccessToken)
                val snapshot = withContext(Dispatchers.IO) { client.fetchSnapshot(selectedIndex, savedExpiry) }
                underlyingKey = snapshot.underlyingKey
                publishLiveSnapshot(snapshot)
                val keys = listOf(snapshot.underlyingKey) + snapshot.options.mapNotNull { it.instrumentKey.takeIf(String::isNotBlank) }
                tickStream = UpstoxTickStream(
                    authorizedUrlProvider = { client.authorizedSocketUrl() },
                    instrumentKeys = keys.distinct(),
                    listener = object : UpstoxTickStream.Listener {
                        override fun onOpen() {
                            _state.value = _state.value.copy(isConnected = true, message = "${selectedIndex.name} live ticks connected · ${keys.size} instruments")
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

    fun selectIndex(index: MarketIndex) {
        if (_state.value.index == index) return
        closePosition("Market changed")
        livePrices.clear()
        tick = 0
        _state.value = _state.value.copy(index = index, isConnected = false, optionChain = emptyList(), spotPrice = 0.0, message = "Switching automatically to ${index.name}…")
        if (_state.value.connectionMode == ConnectionMode.UPSTOX && savedAccessToken.isNotBlank() && savedExpiry.isNotBlank()) connectSelectedIndex()
        else if (_state.value.connectionMode == ConnectionMode.DEMO) connectDemo()
    }

    fun setTradingMode(mode: TradingMode) { autoTradeTakenForSignal = false; _state.value = _state.value.copy(tradingMode = mode, message = "$mode mode selected") }
    fun setAppMode(mode: AppMode) { _state.value = _state.value.copy(appMode = mode, message = if (mode == AppMode.BACKTEST) "Historical backtest mode" else "Live market mode") }
    fun setStartingCapital(value: Double) { if (value > 0) _state.value = _state.value.copy(startingCapital = value) }
    fun setLiveTradingEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(
            liveTradingEnabled = enabled,
            executionMode = if (enabled) ExecutionMode.LIVE else ExecutionMode.PAPER,
            message = if (enabled) "LIVE TRADING ARMED · broker order validation required" else "Live trading OFF · paper mode active",
        )
    }
    fun setExecutionMode(mode: ExecutionMode) = setLiveTradingEnabled(mode == ExecutionMode.LIVE)
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
            message = "${current.index.name} ticks ${current.ticksReceived + 1} · ${if (current.liveTradingEnabled) "LIVE ARMED" else "PAPER"}",
        )
        runAutoIfEligible()
    }

    private fun publishLiveSnapshot(snapshot: UpstoxLiveClient.Snapshot) {
        livePrices.addLast(snapshot.spot)
        val position = updatePosition(_state.value.position, snapshot.options)
        _state.value = _state.value.copy(spotPrice = snapshot.spot, optionChain = snapshot.options, position = position, pnl = position?.pnl ?: 0.0, signal = calculateLiveSignal(snapshot.spot, snapshot.options))
    }

    private fun calculateLiveSignal(spot: Double, chain: List<OptionQuote>): SignalSnapshot {
        val minimumTicks = 56
        if (spot <= 0 || livePrices.size < minimumTicks) return waitSignal("Collecting V2 ticks ${livePrices.size}/$minimumTicks")

        val prices = livePrices.toList()
        val bars = prices.zipWithNext().map { (open, close) ->
            SignalEngineV2.Bar(
                open = open,
                high = max(open, close),
                low = minOf(open, close),
                close = close,
                volume = 0,
            )
        }
        val evaluation = signalEngineV2.evaluate(bars)
        val calls = chain.filter { it.type == "CE" && abs(it.delta) in 0.35..0.70 }
        val puts = chain.filter { it.type == "PE" && abs(it.delta) in 0.35..0.70 }
        val callOi = calls.sumOf { it.changeInOpenInterest }
        val putOi = puts.sumOf { it.changeInOpenInterest }
        val oiConfirmed = when (evaluation.direction) {
            SignalEngineV2.Direction.BULLISH -> putOi >= callOi
            SignalEngineV2.Direction.BEARISH -> callOi >= putOi
            SignalEngineV2.Direction.NEUTRAL -> false
        }
        val confidence = (evaluation.score + if (oiConfirmed) 5 else 0).coerceAtMost(100)
        val risk = (evaluation.atr * 0.8).coerceAtLeast(spot * 0.001)
        val reasons = (evaluation.reasons + "OI ${if (oiConfirmed) "confirmed" else "not confirmed"}").take(4)

        return when {
            evaluation.direction == SignalEngineV2.Direction.BULLISH && confidence >= 80 -> SignalSnapshot(
                SignalAction.BUY_CE,
                confidence,
                TrendDirection.BULLISH,
                spot,
                spot - risk,
                spot + risk * 1.8,
                listOf("BUY CALL · Signal Engine v2") + reasons,
            )
            evaluation.direction == SignalEngineV2.Direction.BEARISH && confidence >= 80 -> SignalSnapshot(
                SignalAction.BUY_PE,
                confidence,
                TrendDirection.BEARISH,
                spot,
                spot + risk,
                spot - risk * 1.8,
                listOf("BUY PUT · Signal Engine v2") + reasons,
            )
            else -> SignalSnapshot(
                SignalAction.WAIT,
                confidence,
                TrendDirection.NEUTRAL,
                null,
                null,
                null,
                listOf("WAIT · Signal Engine v2 filters") + reasons,
            )
        }
    }

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
        runAutoIfEligible(); tick = (tick + 1) % 65
    }

    private fun buildDemoChain(spot: Double, atm: Int, step: Int): List<OptionQuote> = (-5..5).flatMap { offset ->
        val strike = atm + offset * step; val distance = spot - strike; val tv = max(18.0, 105.0 - abs(offset) * 12.0); val gamma = max(0.0002, 0.0022 - abs(offset) * 0.00025); val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
        listOf(OptionQuote(strike.toDouble(), "CE", max(0.0, distance) + tv, 90_000L, 1_500L - offset * 170L, ceDelta, gamma, offset == 0), OptionQuote(strike.toDouble(), "PE", max(0.0, -distance) + tv, 94_000L, -700L + offset * 190L, ceDelta - 1.0, gamma, offset == 0))
    }

    private fun openPaperPosition(side: PositionSide) {
        val current = _state.value
        if (!current.isConnected) { _state.value = current.copy(message = "Connect live data first"); return }
        if (current.position != null) { _state.value = current.copy(message = "Exit current position first"); return }

        val selection = optionSelector.select(current.optionChain, side.name)
        if (selection == null) {
            _state.value = current.copy(message = "No liquid ${side.name} contract matches delta/OI filters")
            return
        }
        val q = selection.quote
        val lot = if (current.index == MarketIndex.NIFTY) 65 else 20
        val mode = if (current.liveTradingEnabled) "LIVE" else "PAPER"
        val rationale = selection.reasons.take(2).joinToString(" · ")
        _state.value = current.copy(
            position = PaperPosition(side, q.strike, lot, q.ltp, q.ltp),
            pnl = 0.0,
            message = "$mode BUY ${q.strike.toInt()} ${side.name} × $lot · $rationale",
        )
    }

    private fun closePosition(reason: String) {
        val c = _state.value
        val realized = c.position?.pnl ?: 0.0
        _state.value = c.copy(position = null, pnl = 0.0, realizedPnl = c.realizedPnl + realized, message = "$reason · P&L ₹${"%.2f".format(realized)}")
        autoTradeTakenForSignal = false
    }

    private fun runAutoIfEligible() {
        val c = _state.value
        if (c.tradingMode != TradingMode.AUTO || c.appMode != AppMode.LIVE_MARKET) return
        if (c.position == null && !autoTradeTakenForSignal && c.signal.confidence >= 80) {
            when (c.signal.action) {
                SignalAction.BUY_CE -> openPaperPosition(PositionSide.CE)
                SignalAction.BUY_PE -> openPaperPosition(PositionSide.PE)
                SignalAction.WAIT -> Unit
            }
            autoTradeTakenForSignal = true
        }
        val p = _state.value.position ?: return
        val pct = if (p.entryPrice == 0.0) 0.0 else (p.currentPrice - p.entryPrice) / p.entryPrice
        val reversed = (p.side == PositionSide.CE && c.signal.action == SignalAction.BUY_PE) || (p.side == PositionSide.PE && c.signal.action == SignalAction.BUY_CE)
        if (pct <= -0.15 || pct >= 0.30 || reversed) closePosition("Auto exit")
    }

    override fun onCleared() { disconnectInternal(); super.onCleared() }
}
