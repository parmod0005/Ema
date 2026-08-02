package com.parmod.ema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.ai.*
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxTickStream
import com.parmod.ema.engine.ExecutionEngineV2
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
    private var aiJob: Job? = null
    private var tickStream: UpstoxTickStream? = null
    private var tick = 0
    private var autoTradeTakenForSignal = false
    private val livePrices = ArrayDeque<Double>()
    private val signalEngineV2 = SignalEngineV2()
    private val optionSelector = OptionSelector()
    private val executionEngineV2 = ExecutionEngineV2()
    private val aiRouter = AiDecisionRouter()
    private val aiBuffer = AiSnapshotBuffer()
    private var executionState: ExecutionEngineV2.State? = null
    private var underlyingKey = ""
    private var savedAccessToken = ""
    private var savedExpiry = ""
    private var aiClient: AiBridgeClient? = null
    private var directOpenAiClient: DirectOpenAiClient? = null
    private var lastAiRequestMillis = 0L
    private var lastAiSnapshotSpot = 0.0

    fun setAiConnectionMode(mode: AiConnectionMode) {
        aiJob?.cancel()
        val configured = when (mode) {
            AiConnectionMode.BRIDGE_SERVER -> aiClient != null
            AiConnectionMode.DIRECT_OPENAI -> directOpenAiClient != null
        }
        val provider = if (mode == AiConnectionMode.BRIDGE_SERVER) "Bridge Server" else "Direct OpenAI"
        _state.value = _state.value.copy(
            aiConnectionMode = mode,
            aiDecision = null,
            aiBridgeHealth = AiBridgeHealth(
                configured = configured,
                reachable = false,
                message = if (configured) "$provider configured · waiting for market snapshot" else "$provider not configured",
            ),
            aiFinalReason = "$provider selected",
            message = "$provider selected · Shadow/Paper only",
        )
        applyAiRouting()
    }

    fun configureAiBridge(baseUrl: String, deviceToken: String) {
        aiJob?.cancel()
        aiClient = if (baseUrl.isBlank() || deviceToken.isBlank()) null else AiBridgeClient(baseUrl.trim(), deviceToken.trim())
        _state.value = _state.value.copy(aiConnectionMode = AiConnectionMode.BRIDGE_SERVER, aiDecision = null)
        if (aiClient == null) {
            _state.value = _state.value.copy(
                aiBridgeHealth = AiBridgeHealth(message = "AI bridge not configured"),
                aiFinalReason = "AI bridge not configured",
            )
            return
        }
        _state.value = _state.value.copy(aiBridgeHealth = AiBridgeHealth(configured = true, message = "Checking AI bridge…"))
        viewModelScope.launch {
            val health = withContext(Dispatchers.IO) { aiClient?.health() ?: AiBridgeHealth() }
            _state.value = _state.value.copy(aiBridgeHealth = health, message = health.message)
        }
    }

    fun configureDirectOpenAi(apiKey: String, model: String) {
        aiJob?.cancel()
        val cleanModel = model.trim().ifBlank { "gpt-5" }
        directOpenAiClient = if (apiKey.isBlank()) null else DirectOpenAiClient(apiKey.trim(), cleanModel)
        _state.value = _state.value.copy(
            aiConnectionMode = AiConnectionMode.DIRECT_OPENAI,
            directOpenAiModel = cleanModel,
            aiDecision = null,
            aiBridgeHealth = if (directOpenAiClient == null) {
                AiBridgeHealth(message = "Direct OpenAI not configured")
            } else {
                AiBridgeHealth(configured = true, message = "Direct OpenAI configured · waiting for snapshot")
            },
            aiFinalReason = if (directOpenAiClient == null) "Direct OpenAI not configured" else "Direct OpenAI selected · local safety routing active",
            message = if (directOpenAiClient == null) "OpenAI key removed" else "Direct OpenAI configured · Shadow/Paper only",
        )
        applyAiRouting()
    }

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
        aiBuffer.clear()
        val selectedIndex = _state.value.index
        _state.value = _state.value.copy(connectionMode = ConnectionMode.UPSTOX, isConnected = false, executionMode = ExecutionMode.PAPER, optionChain = emptyList(), spotPrice = 0.0, message = "Loading ${selectedIndex.name} contracts and live feed…")
        feedJob = viewModelScope.launch {
            try {
                val client = UpstoxLiveClient(savedAccessToken)
                val snapshot = withContext(Dispatchers.IO) { client.fetchSnapshot(selectedIndex, savedExpiry) }
                underlyingKey = snapshot.underlyingKey
                publishLiveSnapshot(snapshot)
                val keys = (listOf(snapshot.underlyingKey) + snapshot.options.mapNotNull { it.instrumentKey.takeIf(String::isNotBlank) }).distinct()
                tickStream = UpstoxTickStream(
                    authorizedUrlProvider = { client.authorizedSocketUrl() },
                    instrumentKeys = keys,
                    listener = object : UpstoxTickStream.Listener {
                        override fun onOpen() { _state.value = _state.value.copy(isConnected = true, message = "${selectedIndex.name} live ticks connected · ${keys.size} instruments") }
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
        disconnectInternal(); livePrices.clear(); aiBuffer.clear()
        _state.value = _state.value.copy(connectionMode = ConnectionMode.DEMO, isConnected = true, executionMode = ExecutionMode.PAPER, message = "Demo feed connected")
        feedJob = viewModelScope.launch { while (true) { publishDemoTick(); delay(500) } }
    }

    fun disconnect() { disconnectInternal(); _state.value = _state.value.copy(isConnected = false, message = "Disconnected") }
    private fun disconnectInternal() { feedJob?.cancel(); feedJob = null; tickStream?.disconnect(); tickStream = null; aiJob?.cancel(); aiJob = null }

    fun selectIndex(index: MarketIndex) {
        if (_state.value.index == index) return
        closePosition("Market changed"); livePrices.clear(); aiBuffer.clear(); tick = 0
        _state.value = _state.value.copy(index = index, isConnected = false, optionChain = emptyList(), spotPrice = 0.0, aiDecision = null, message = "Switching automatically to ${index.name}…")
        if (_state.value.connectionMode == ConnectionMode.UPSTOX && savedAccessToken.isNotBlank() && savedExpiry.isNotBlank()) connectSelectedIndex() else if (_state.value.connectionMode == ConnectionMode.DEMO) connectDemo()
    }

    fun setTradingMode(mode: TradingMode) { autoTradeTakenForSignal = false; _state.value = _state.value.copy(tradingMode = mode, message = "$mode mode selected") }
    fun setAppMode(mode: AppMode) { _state.value = _state.value.copy(appMode = mode, message = if (mode == AppMode.BACKTEST) "Historical backtest mode" else "Live market mode") }
    fun setSignalEngineMode(mode: SignalEngineMode) {
        autoTradeTakenForSignal = false
        val provider = if (_state.value.aiConnectionMode == AiConnectionMode.DIRECT_OPENAI) "Direct OpenAI" else "AI bridge"
        val reason = when (mode) {
            SignalEngineMode.NATIVE -> "Native Signal Engine V2 selected"
            SignalEngineMode.AI_BRAIN -> "AI Brain selected · $provider decisions required"
            SignalEngineMode.HYBRID -> "Hybrid selected · AI/native safety routing"
        }
        _state.value = _state.value.copy(signalEngineMode = mode, aiFinalReason = reason, message = reason)
        applyAiRouting()
    }
    fun setAiRunMode(mode: AiRunMode) {
        val safeMode = if (mode == AiRunMode.LIVE_CANDIDATE) AiRunMode.SHADOW else mode
        _state.value = _state.value.copy(aiRunMode = safeMode, message = if (safeMode == AiRunMode.SHADOW) "AI shadow mode · analysis only" else "AI paper mode · local risk gates active")
        applyAiRouting()
    }
    fun setStartingCapital(value: Double) { if (value > 0) _state.value = _state.value.copy(startingCapital = value) }
    fun setLiveTradingEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(liveTradingEnabled = false, executionMode = ExecutionMode.PAPER, message = if (enabled) "Live broker orders remain locked · paper mode active" else "Live trading OFF · paper mode active")
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
            livePrices.addLast(spot); while (livePrices.size > 600) livePrices.removeFirst()
            aiBuffer.add(tick.feedTimestamp, spot)
        } else {
            chain = chain.map { q -> if (q.instrumentKey != tick.instrumentKey) q else q.copy(ltp = tick.ltp ?: q.ltp, openInterest = tick.oi ?: q.openInterest, delta = tick.delta ?: q.delta, gamma = tick.gamma ?: q.gamma, lastTickMillis = tick.feedTimestamp) }
        }
        val native = calculateLiveSignal(spot, chain)
        val position = updatePosition(current.position, chain)
        _state.value = current.copy(isConnected = true, spotPrice = spot, optionChain = chain, signal = native, position = position, pnl = position?.pnl ?: 0.0, lastTickMillis = tick.feedTimestamp, ticksReceived = current.ticksReceived + 1, message = "${current.index.name} ticks ${current.ticksReceived + 1} · PAPER")
        applyAiRouting(native)
        manageOpenPosition(); runAutoIfEligible(); maybeRequestAi()
    }

    private fun publishLiveSnapshot(snapshot: UpstoxLiveClient.Snapshot) {
        val now = System.currentTimeMillis(); livePrices.addLast(snapshot.spot); aiBuffer.add(now, snapshot.spot)
        val native = calculateLiveSignal(snapshot.spot, snapshot.options)
        val position = updatePosition(_state.value.position, snapshot.options)
        _state.value = _state.value.copy(spotPrice = snapshot.spot, optionChain = snapshot.options, position = position, pnl = position?.pnl ?: 0.0, signal = native, lastTickMillis = now)
        applyAiRouting(native); manageOpenPosition()
    }

    private fun maybeRequestAi() {
        val c = _state.value
        val providerConfigured = when (c.aiConnectionMode) {
            AiConnectionMode.BRIDGE_SERVER -> aiClient != null
            AiConnectionMode.DIRECT_OPENAI -> directOpenAiClient != null
        }
        if (!providerConfigured || c.signalEngineMode == SignalEngineMode.NATIVE || !c.isConnected || !aiBuffer.isReady() || savedExpiry.isBlank()) return
        val now = System.currentTimeMillis()
        if (aiJob?.isActive == true || now - lastAiRequestMillis < 15_000L) return
        val snapshot = aiBuffer.build(c, savedExpiry, now)
        lastAiRequestMillis = now; lastAiSnapshotSpot = snapshot.spot
        aiJob = viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    when (c.aiConnectionMode) {
                        AiConnectionMode.BRIDGE_SERVER -> aiClient?.analyze(snapshot)?.let { it.decision to it.latencyMillis }
                            ?: error("AI bridge not configured")
                        AiConnectionMode.DIRECT_OPENAI -> directOpenAiClient?.analyze(snapshot)?.let { it.decision to it.latencyMillis }
                            ?: error("Direct OpenAI not configured")
                    }
                }
                val providerName = if (c.aiConnectionMode == AiConnectionMode.BRIDGE_SERVER) "AI bridge" else "Direct OpenAI"
                val health = AiBridgeHealth(configured = true, reachable = true, lastLatencyMillis = response.second, lastSuccessMillis = System.currentTimeMillis(), message = "$providerName online")
                _state.value = _state.value.copy(aiBridgeHealth = health, aiDecision = response.first)
                applyAiRouting(snapshotSpot = snapshot.spot)
                runAutoIfEligible()
            } catch (error: Exception) {
                val old = _state.value.aiBridgeHealth
                val providerName = if (c.aiConnectionMode == AiConnectionMode.BRIDGE_SERVER) "AI bridge" else "Direct OpenAI"
                _state.value = _state.value.copy(aiBridgeHealth = old.copy(configured = true, reachable = false, consecutiveFailures = old.consecutiveFailures + 1, message = error.message?.take(160) ?: "$providerName error"))
                applyAiRouting(snapshotSpot = snapshot.spot)
            }
        }
    }

    private fun applyAiRouting(nativeSignal: SignalSnapshot = calculateLiveSignal(_state.value.spotPrice, _state.value.optionChain), snapshotSpot: Double = lastAiSnapshotSpot.takeIf { it > 0 } ?: _state.value.spotPrice) {
        val c = _state.value
        if (c.signalEngineMode == SignalEngineMode.NATIVE) {
            _state.value = c.copy(signal = nativeSignal, aiFinalReason = "Native engine controls final signal")
            return
        }
        val now = System.currentTimeMillis()
        val result = aiRouter.route(AiDecisionRouter.Context(
            mode = c.signalEngineMode,
            aiRunMode = c.aiRunMode,
            nowMillis = now,
            currentSpot = c.spotPrice,
            snapshotSpot = snapshotSpot,
            dataAgeMillis = (now - c.lastTickMillis).coerceAtLeast(0L),
            bridgeHealth = c.aiBridgeHealth,
            dailyLossLocked = false,
            hasOpenPosition = c.position != null,
            native = AiDecisionRouter.NativeDecision(nativeSignal.action, nativeSignal.confidence),
            ai = c.aiDecision,
        ))
        val triggerOk = c.aiDecision?.let { triggerSatisfied(it, c.spotPrice) } ?: true
        val finalResult = if (!triggerOk && result.action != SignalAction.WAIT) result.copy(action = SignalAction.WAIT, executable = false, reasons = listOf("AI conditional trigger not reached")) else result
        val ai = finalResult.aiDecision
        val trend = when (finalResult.action) { SignalAction.BUY_CE -> TrendDirection.BULLISH; SignalAction.BUY_PE -> TrendDirection.BEARISH; SignalAction.WAIT -> TrendDirection.NEUTRAL }
        val finalSignal = SignalSnapshot(finalResult.action, ai?.confidence ?: nativeSignal.confidence, trend, ai?.entryMin ?: nativeSignal.entry, ai?.stopLoss ?: nativeSignal.stopLoss, ai?.target ?: nativeSignal.target, finalResult.reasons)
        _state.value = c.copy(signal = finalSignal, aiFinalReason = finalResult.reasons.joinToString(" · ").take(240))
    }

    private fun triggerSatisfied(ai: AiTradeDecision, spot: Double): Boolean {
        val t = ai.trigger ?: return true
        return (t.spotAbove == null || spot > t.spotAbove) && (t.spotBelow == null || spot < t.spotBelow)
    }

    private fun calculateLiveSignal(spot: Double, chain: List<OptionQuote>): SignalSnapshot {
        val minimumTicks = 56
        if (spot <= 0 || livePrices.size < minimumTicks) return waitSignal("Collecting V2 ticks ${livePrices.size}/$minimumTicks")
        val bars = livePrices.toList().zipWithNext().map { (open, close) -> SignalEngineV2.Bar(open, max(open, close), minOf(open, close), close, 0) }
        val evaluation = signalEngineV2.evaluate(bars)
        val calls = chain.filter { it.type == "CE" && abs(it.delta) in 0.35..0.70 }
        val puts = chain.filter { it.type == "PE" && abs(it.delta) in 0.35..0.70 }
        val callOi = calls.sumOf { it.changeInOpenInterest }; val putOi = puts.sumOf { it.changeInOpenInterest }
        val oiConfirmed = when (evaluation.direction) { SignalEngineV2.Direction.BULLISH -> putOi >= callOi; SignalEngineV2.Direction.BEARISH -> callOi >= putOi; else -> false }
        val confidence = (evaluation.score + if (oiConfirmed) 5 else 0).coerceAtMost(100)
        val risk = (evaluation.atr * 0.8).coerceAtLeast(spot * 0.001)
        val reasons = (evaluation.reasons + "OI ${if (oiConfirmed) "confirmed" else "not confirmed"}").take(4)
        return when {
            evaluation.direction == SignalEngineV2.Direction.BULLISH && confidence >= 80 -> SignalSnapshot(SignalAction.BUY_CE, confidence, TrendDirection.BULLISH, spot, spot - risk, spot + risk * 1.8, listOf("BUY CALL · Signal Engine v2") + reasons)
            evaluation.direction == SignalEngineV2.Direction.BEARISH && confidence >= 80 -> SignalSnapshot(SignalAction.BUY_PE, confidence, TrendDirection.BEARISH, spot, spot + risk, spot - risk * 1.8, listOf("BUY PUT · Signal Engine v2") + reasons)
            else -> SignalSnapshot(SignalAction.WAIT, confidence, TrendDirection.NEUTRAL, null, null, null, listOf("WAIT · Signal Engine v2 filters") + reasons)
        }
    }

    private fun waitSignal(reason: String) = SignalSnapshot(SignalAction.WAIT, 45, TrendDirection.NEUTRAL, null, null, null, listOf(reason, "Waiting for confirmed expansion"))
    private fun updatePosition(position: PaperPosition?, chain: List<OptionQuote>): PaperPosition? = position?.let { p -> chain.firstOrNull { it.strike == p.strike && it.type == p.side.name }?.let { p.copy(currentPrice = it.ltp) } ?: p }

    private fun publishDemoTick() {
        val current = _state.value; val base = if (current.index == MarketIndex.NIFTY) 24_550.0 else 80_200.0; val step = if (current.index == MarketIndex.NIFTY) 50 else 100
        val spot = base + (if (tick < 30) tick * 2.0 else 60.0 - (tick - 30) * 1.8) + Random.nextDouble(-1.5, 1.5); val atm = (spot / step).toInt() * step; val chain = buildDemoChain(spot, atm, step)
        livePrices.addLast(spot); while (livePrices.size > 600) livePrices.removeFirst(); val now = System.currentTimeMillis(); aiBuffer.add(now, spot)
        val native = calculateLiveSignal(spot, chain); val position = updatePosition(current.position, chain)
        _state.value = current.copy(spotPrice = spot, optionChain = chain, signal = native, position = position, pnl = position?.pnl ?: 0.0, ticksReceived = current.ticksReceived + 1, lastTickMillis = now)
        applyAiRouting(native); manageOpenPosition(); runAutoIfEligible(); maybeRequestAi(); tick = (tick + 1) % 65
    }

    private fun buildDemoChain(spot: Double, atm: Int, step: Int): List<OptionQuote> = (-5..5).flatMap { offset ->
        val strike = atm + offset * step; val distance = spot - strike; val tv = max(18.0, 105.0 - abs(offset) * 12.0); val gamma = max(0.0002, 0.0022 - abs(offset) * 0.00025); val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
        listOf(OptionQuote(strike.toDouble(), "CE", max(0.0, distance) + tv, 90_000L, 1_500L - offset * 170L, ceDelta, gamma, offset == 0), OptionQuote(strike.toDouble(), "PE", max(0.0, -distance) + tv, 94_000L, -700L + offset * 190L, ceDelta - 1.0, gamma, offset == 0))
    }

    private fun openPaperPosition(side: PositionSide) {
        val current = _state.value
        if (!current.isConnected) { _state.value = current.copy(message = "Connect live data first"); return }
        if (current.position != null) { _state.value = current.copy(message = "Exit current position first"); return }
        val selection = optionSelector.select(current.optionChain, side.name) ?: run { _state.value = current.copy(message = "No liquid ${side.name} contract matches delta/OI filters"); return }
        val q = selection.quote; val lot = if (current.index == MarketIndex.NIFTY) 65 else 20; val rationale = selection.reasons.take(2).joinToString(" · "); val exec = executionEngineV2.open(q.ltp)
        executionState = exec
        _state.value = current.copy(position = PaperPosition(side, q.strike, lot, q.ltp, q.ltp, exec.highestPrice, exec.stopPrice, exec.targetPrice, exec.breakevenActive, exec.trailingActive), pnl = 0.0, message = "PAPER BUY ${q.strike.toInt()} ${side.name} × $lot · SL ${"%.2f".format(exec.stopPrice)} · TG ${"%.2f".format(exec.targetPrice)} · $rationale")
    }

    private fun manageOpenPosition() {
        val current = _state.value; val position = current.position ?: return; val state = executionState ?: executionEngineV2.open(position.entryPrice)
        val opposite = (position.side == PositionSide.CE && current.signal.action == SignalAction.BUY_PE) || (position.side == PositionSide.PE && current.signal.action == SignalAction.BUY_CE)
        val update = executionEngineV2.update(state, position.currentPrice, opposite); executionState = update.state
        val managed = position.copy(highestPrice = update.state.highestPrice, stopPrice = update.state.stopPrice, targetPrice = update.state.targetPrice, breakevenActive = update.state.breakevenActive, trailingActive = update.state.trailingActive)
        _state.value = current.copy(position = managed, pnl = managed.pnl)
        update.exitReason?.let { reason -> closePosition(when (reason) { ExecutionEngineV2.ExitReason.STOP_LOSS -> if (update.state.trailingActive) "Trailing stop exit" else if (update.state.breakevenActive) "Breakeven stop exit" else "Stop-loss exit"; ExecutionEngineV2.ExitReason.TARGET -> "Target exit"; ExecutionEngineV2.ExitReason.OPPOSITE_SIGNAL -> "Opposite-signal exit" }) }
    }

    private fun closePosition(reason: String) {
        val c = _state.value; val realized = c.position?.pnl ?: 0.0; executionState = null
        _state.value = c.copy(position = null, pnl = 0.0, realizedPnl = c.realizedPnl + realized, message = "$reason · P&L ₹${"%.2f".format(realized)}"); autoTradeTakenForSignal = false
    }

    private fun runAutoIfEligible() {
        val c = _state.value
        if (c.tradingMode != TradingMode.AUTO || c.appMode != AppMode.LIVE_MARKET || c.liveTradingEnabled) return
        if (c.signalEngineMode != SignalEngineMode.NATIVE && c.aiRunMode == AiRunMode.SHADOW) return
        if (c.position == null && !autoTradeTakenForSignal && c.signal.confidence >= 80) {
            when (c.signal.action) { SignalAction.BUY_CE -> openPaperPosition(PositionSide.CE); SignalAction.BUY_PE -> openPaperPosition(PositionSide.PE); SignalAction.WAIT -> Unit }
            autoTradeTakenForSignal = _state.value.position != null
        }
    }

    override fun onCleared() { disconnectInternal(); super.onCleared() }
}
