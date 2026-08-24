package com.parmod.ema

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.parmod.ema.data.UpstoxIntradayCandleClient
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxOptionDiscoveryClient
import com.parmod.ema.data.UpstoxOrderClient
import com.parmod.ema.data.UpstoxTickStream
import com.parmod.ema.engine.AdaptiveExitEngine
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.MultiTimeframeConfirmation
import com.parmod.ema.engine.OptionSelector
import com.parmod.ema.engine.TickNativeDualEngine
import com.parmod.ema.engine.V76ExecutionQualityEngine
import com.parmod.ema.engine.V76ScalperEngine
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.EngineState
import com.parmod.ema.model.EngineTimeframeConfig
import com.parmod.ema.model.ExecutionMode
import com.parmod.ema.model.FullDashboardState
import com.parmod.ema.model.FullMarketState
import com.parmod.ema.model.LiveArmMode
import com.parmod.ema.model.LiveExecutionGuard
import com.parmod.ema.model.LiveGateDecision
import com.parmod.ema.model.LiveGateInput
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.MarketSelection
import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PaperPosition
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TradeLogEntry
import com.parmod.ema.model.TradeStatus
import com.parmod.ema.model.TradingMode
import com.parmod.ema.model.TrendDirection
import com.parmod.ema.runtime.ProcessTradingScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Full VARDHANI runtime matching the original product scope.
 *
 * PAPER remains the default. LIVE is opt-in and disarmed after every material mode,
 * market or connection change. NIFTY and SENSEX use independent signal, exit, trade,
 * feed and risk sessions and can run concurrently when BOTH is selected.
 */
class VardhaniFullViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(FullDashboardState())
    val state: StateFlow<FullDashboardState> = _state.asStateFlow()

    private var accessToken: String = ""
    private val sessions: MutableMap<MarketIndex, Session> = MarketIndex.entries
        .associateWith { Session(it) }
        .toMutableMap()

    init {
        MetaBrainRuntime.initialize(application.applicationContext)
    }

    private data class WorkingMinute(
        val minute: Long,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var volume: Long,
    )

    private data class StoredSpotTick(val price: Double, val timestamp: Long)

    private data class StoredOptionTick(
        val instrumentKey: String,
        val ltp: Double?,
        val oi: Long?,
        val volume: Long?,
        val bid: Double?,
        val ask: Double?,
        val timestamp: Long,
        val ltq: Long?,
        val totalBuyQty: Long?,
        val totalSellQty: Long?,
        val depth: List<UpstoxTickStream.DepthLevel>,
    )

    private inner class Session(val index: MarketIndex) {
        val tickCore = TickNativeDualEngine()
        val v76Core = V76ScalperEngine()
        val mtf = MultiTimeframeConfirmation()
        val selector = OptionSelector()
        val adaptiveExit = AdaptiveExitEngine()
        var job: Job? = null
        var stream: UpstoxTickStream? = null
        var underlyingKey: String = ""
        var expiry: String = ""
        var vixLtp: Double = 0.0
        var workingMinute: WorkingMinute? = null
        val oneMinute = ArrayDeque<MultiTimeframeConfirmation.TimedBar>()
        val v76Bars = ArrayDeque<V76ScalperEngine.Bar>()
        val spotHistory = ArrayDeque<StoredSpotTick>()
        val optionHistory = linkedMapOf<String, ArrayDeque<StoredOptionTick>>()
        var lastSignalPublishMillis: Long = 0L
        var lastV76SignalMillis: Long = 0L
        var lastV76Evaluation = V76ScalperEngine.Evaluation(
            SignalSnapshot(SignalAction.WAIT, 0, TrendDirection.NEUTRAL, null, null, null, listOf("V7.6 warm-up")),
        )
        val lastExitMillis = mutableMapOf<EngineId, Long>()
        val lastExitSide = mutableMapOf<EngineId, PositionSide?>()
        val lastAutoAttemptKey = mutableMapOf<EngineId, String>()
        val lastAutoAttemptMillis = mutableMapOf<EngineId, Long>()
        val pendingEntry = mutableSetOf<EngineId>()
        val pendingExit = mutableSetOf<EngineId>()
        var v76ConsecutiveLosses: Int = 0
    }

    fun connectUpstox(token: String) {
        val clean = token.trim()
        if (clean.isBlank()) {
            _state.update { it.copy(message = "Upstox access token is required") }
            return
        }
        if (hasAnyPendingBrokerOperation()) {
            _state.update { it.copy(message = "Connection change blocked · broker operation pending") }
            return
        }
        accessToken = clean
        selectedIndexes().forEach { connectIndex(it, null) }
    }

    fun connectDemo() {
        if (hasOpenLivePosition() || hasAnyPendingBrokerOperation()) {
            _state.update { it.copy(message = "DEMO blocked · close/reconcile live broker positions first") }
            return
        }
        accessToken = ""
        disconnectAllInternal()
        selectedIndexes().forEach(::startDemo)
        _state.update {
            it.copy(
                executionMode = ExecutionMode.PAPER,
                liveArmMode = LiveArmMode.DISARMED,
                message = "DEMO · ${it.marketSelection.name} · PAPER",
            )
        }
    }

    fun disconnectAll() {
        if (hasOpenLivePosition() || hasAnyPendingBrokerOperation()) {
            _state.update { it.copy(message = "DISCONNECT BLOCKED · live broker position/order still active") }
            return
        }
        disconnectAllInternal()
        _state.update { current ->
            current.copy(
                liveArmMode = LiveArmMode.DISARMED,
                markets = current.markets.mapValues { (_, market) -> market.copy(isConnected = false, message = "Disconnected") },
                message = "Disconnected · live arm cleared",
            )
        }
        MetaBrainRuntime.forceSave()
    }

    private fun disconnectAllInternal() {
        sessions.values.forEach(::disconnectSession)
    }

    fun setMarketSelection(selection: MarketSelection) {
        val current = _state.value
        if (current.marketSelection == selection) return
        val removed = current.marketSelection.indexes - selection.indexes
        if (removed.any(::hasOpenPosition) || removed.any { sessions.getValue(it).pendingEntry.isNotEmpty() || sessions.getValue(it).pendingExit.isNotEmpty() }) {
            _state.update { it.copy(message = "MARKET CHANGE BLOCKED · close/reconcile ${removed.joinToString()} positions first") }
            return
        }
        _state.update {
            it.copy(
                marketSelection = selection,
                liveArmMode = LiveArmMode.DISARMED,
                message = "Market selection ${selection.name} · LIVE arm cleared",
            )
        }
        removed.forEach { disconnectSession(sessions.getValue(it)) }
        if (accessToken.isNotBlank()) (selection.indexes - current.marketSelection.indexes).forEach { connectIndex(it, null) }
    }

    fun setExpiry(index: MarketIndex, expiry: String) {
        if (expiry.isBlank()) return
        if (hasOpenPosition(index) || sessions.getValue(index).pendingEntry.isNotEmpty() || sessions.getValue(index).pendingExit.isNotEmpty()) {
            updateMarket(index) { it.copy(message = "EXPIRY CHANGE BLOCKED · close/reconcile positions first") }
            return
        }
        if (accessToken.isBlank()) {
            updateMarket(index) { it.copy(message = "Connect Upstox before changing expiry") }
            return
        }
        connectIndex(index, expiry)
    }

    fun setTradingMode(mode: TradingMode) {
        _state.update {
            it.copy(
                tradingMode = mode,
                liveArmMode = if (it.executionMode == ExecutionMode.LIVE) LiveArmMode.DISARMED else it.liveArmMode,
                message = "$mode selected${if (it.executionMode == ExecutionMode.LIVE) " · LIVE must be re-armed" else ""}",
            )
        }
    }

    fun setExecutionMode(mode: ExecutionMode) {
        val current = _state.value
        if (mode == ExecutionMode.LIVE && selectedIndexes().any { !current.market(it).isConnected }) {
            _state.update { it.copy(message = "LIVE BLOCKED · connect every selected market first") }
            return
        }
        _state.update {
            it.copy(
                executionMode = mode,
                liveArmMode = LiveArmMode.DISARMED,
                message = when {
                    mode == ExecutionMode.PAPER && hasOpenLivePosition() -> "PAPER selected for new entries · existing LIVE positions remain broker-managed until exit"
                    mode == ExecutionMode.PAPER -> "PAPER execution active"
                    else -> "LIVE selected · DISARMED until explicit arm",
                },
            )
        }
    }

    fun armLive(mode: LiveArmMode) {
        val s = _state.value
        if (s.executionMode != ExecutionMode.LIVE) {
            _state.update { it.copy(message = "Select LIVE before arming") }
            return
        }
        if (mode == LiveArmMode.DISARMED) {
            _state.update { it.copy(liveArmMode = LiveArmMode.DISARMED, message = "LIVE disarmed") }
            return
        }
        if (accessToken.isBlank() || selectedIndexes().any { !s.market(it).isConnected }) {
            _state.update { it.copy(liveArmMode = LiveArmMode.DISARMED, message = "LIVE ARM BLOCKED · Upstox not fully connected") }
            return
        }
        if (s.emergencyKill || selectedIndexes().any { s.market(it).riskLocked }) {
            _state.update { it.copy(liveArmMode = LiveArmMode.DISARMED, message = "LIVE ARM BLOCKED · safety lock active") }
            return
        }
        _state.update {
            it.copy(
                liveArmMode = mode,
                message = "LIVE ${mode.name.replace('_', ' ')} · every BUY remains risk-gated",
            )
        }
    }

    fun setEmergencyKill(enabled: Boolean) {
        _state.update {
            it.copy(
                emergencyKill = enabled,
                liveArmMode = if (enabled) LiveArmMode.DISARMED else it.liveArmMode,
                message = if (enabled) "EMERGENCY KILL ACTIVE · flatten/close requested" else "Emergency kill reset · LIVE remains disarmed",
            )
        }
        if (enabled) {
            sessions.values.forEach { session ->
                EngineId.entries.forEach { engine -> requestClose(session, engine, "EMERGENCY KILL") }
            }
        }
    }

    fun toggleEngine(engine: EngineId) {
        _state.update { current ->
            val enabled = current.enabledEngines.toMutableSet()
            if (!enabled.add(engine)) enabled.remove(engine)
            current.copy(enabledEngines = enabled, message = "${enabled.size} engine(s) enabled")
        }
    }

    fun setLots(index: MarketIndex, lots: Int) {
        _state.update { current ->
            val n = lots.coerceIn(1, current.riskConfig.maxLotsPerOrder)
            if (index == MarketIndex.NIFTY) current.copy(niftyLots = n) else current.copy(sensexLots = n)
        }
    }

    fun setDailyTradeLimit(value: Int) {
        _state.update { current ->
            val risk = current.riskConfig.copy(maxTradesPerIndex = value.coerceIn(1, 100))
            current.copy(riskConfig = risk, message = "Daily trade limit ${risk.maxTradesPerIndex} per index")
        }
    }

    fun setDailyLossLimit(value: Double) {
        if (value <= 0.0) return
        _state.update { current ->
            current.copy(
                riskConfig = current.riskConfig.copy(dailyLossLimitInr = value),
                message = "Daily loss lock ₹${"%.0f".format(value)} per index",
            )
        }
        selectedIndexes().forEach(::updateRiskLock)
    }

    fun setMaxRiskPerTrade(value: Double) {
        if (value <= 0.0) return
        _state.update { current ->
            current.copy(
                riskConfig = current.riskConfig.copy(maxRiskPerTradeInr = value),
                message = "Max LIVE risk ₹${"%.0f".format(value)} per trade",
            )
        }
    }

    fun setMinimumAutoLiveConfidence(value: Int) {
        _state.update { current ->
            val v = value.coerceIn(1, 100)
            current.copy(
                riskConfig = current.riskConfig.copy(minimumAutoLiveConfidence = v),
                liveArmMode = LiveArmMode.DISARMED,
                message = "AUTO LIVE minimum confidence $v · LIVE arm cleared",
            )
        }
    }

    fun setTimeframes(engine: EngineId, config: EngineTimeframeConfig) {
        if (engine == EngineId.ENGINE_3_V76_SCALPER && config != EngineTimeframeConfig.E3_DEFAULT) {
            _state.update { it.copy(message = "E3 V7.6 timing locked to exact 1m / 3m / 5m") }
            return
        }
        _state.update {
            it.copy(
                engineTimeframes = it.engineTimeframes + (engine to config),
                liveArmMode = if (it.executionMode == ExecutionMode.LIVE) LiveArmMode.DISARMED else it.liveArmMode,
                message = "${engine.name}: ${config.trigger.label}/${config.setup.label}/${config.bias.label}",
            )
        }
    }

    fun manualBuy(index: MarketIndex, engine: EngineId, side: PositionSide) {
        if (index !in selectedIndexes()) return
        requestOpen(sessions.getValue(index), engine, side, "Manual ${_state.value.executionMode.name} entry", automatic = false)
    }

    fun manualExit(index: MarketIndex, engine: EngineId) {
        requestClose(sessions.getValue(index), engine, "Manual exit")
    }

    private fun selectedIndexes(): Set<MarketIndex> = _state.value.marketSelection.indexes

    private fun connectIndex(index: MarketIndex, requestedExpiry: String?) {
        if (hasOpenPosition(index)) {
            updateMarket(index) { it.copy(message = "RECONNECT BLOCKED · position is open") }
            return
        }
        val session = sessions.getValue(index)
        disconnectSession(session)
        resetSessionCore(session)
        MetaBrainRuntime.resetSession(index)
        updateMarket(index) {
            it.copy(
                isConnected = false,
                message = "Discovering ${index.name} contracts…",
                optionChain = emptyList(),
                spotPrice = 0.0,
            )
        }
        session.job = ProcessTradingScope.scope.launch {
            try {
                val discovery = withContext(Dispatchers.IO) { UpstoxOptionDiscoveryClient(accessToken).discover(index) }
                val expiry = requestedExpiry?.takeIf { it in discovery.expiries } ?: discovery.nearestExpiry
                session.expiry = expiry
                updateMarket(index) {
                    it.copy(
                        expiry = expiry,
                        availableExpiries = discovery.expiries,
                        message = "Loading ${index.name} $expiry…",
                    )
                }

                val client = UpstoxLiveClient(accessToken)
                val snapshot = withContext(Dispatchers.IO) { client.fetchSnapshot(index, expiry) }
                session.underlyingKey = snapshot.underlyingKey
                val warm = withContext(Dispatchers.IO) {
                    runCatching {
                        UpstoxIntradayCandleClient(accessToken).getWarmupOneMinuteCandles(snapshot.underlyingKey, 10)
                    }.getOrElse { emptyList() }
                }
                warmSession(session, warm)
                publishSnapshot(session, snapshot)

                val keys = (
                    listOf(snapshot.underlyingKey, INDIA_VIX_KEY) +
                        snapshot.options.mapNotNull { it.instrumentKey.takeIf(String::isNotBlank) }
                    ).distinct()
                session.stream = UpstoxTickStream(
                    authorizedUrlProvider = { client.authorizedSocketUrl() },
                    instrumentKeys = keys,
                    listener = object : UpstoxTickStream.Listener {
                        override fun onOpen() {
                            updateMarket(index) {
                                it.copy(
                                    isConnected = true,
                                    message = "$index LIVE DATA · ${session.expiry} · ${_state.value.executionMode.name}",
                                )
                            }
                        }

                        override fun onTick(tick: UpstoxTickStream.Tick) = applyTick(session, tick)

                        override fun onError(message: String) {
                            updateMarket(index) { it.copy(message = message.take(180)) }
                        }

                        override fun onClosed() {
                            updateMarket(index) { it.copy(isConnected = false, message = "Feed closed/reconnecting") }
                        }
                    },
                ).also { withContext(Dispatchers.IO) { it.connect() } }
            } catch (error: Exception) {
                updateMarket(index) {
                    it.copy(
                        isConnected = false,
                        message = "${index.name} connect failed: ${error.message?.take(140)}",
                    )
                }
            }
        }
    }

    private fun startDemo(index: MarketIndex) {
        val session = sessions.getValue(index)
        resetSessionCore(session)
        updateMarket(index) {
            it.copy(
                isConnected = true,
                expiry = "DEMO",
                availableExpiries = listOf("DEMO"),
                message = "DEMO PAPER",
            )
        }
        session.job = ProcessTradingScope.scope.launch {
            var n = 0
            while (true) {
                val base = if (index == MarketIndex.NIFTY) 24_500.0 else 80_000.0
                val wave = when ((n / 80) % 4) {
                    0 -> n % 80 * 1.4
                    1 -> 112.0 - (n % 80) * 0.7
                    2 -> 56.0 - (n % 80) * 1.2
                    else -> -40.0 + (n % 80) * 0.5
                }
                val spot = base + wave + Random.nextDouble(-2.0, 2.0)
                val chain = buildDemoChain(index, spot)
                val now = System.currentTimeMillis()
                updateMarket(index) {
                    it.copy(optionChain = chain, lastTickMillis = now, ticksReceived = it.ticksReceived + 1)
                }
                onSpotTick(session, spot, now, 1L)
                updatePositions(session)
                managePositions(session)
                n++
                delay(100L)
            }
        }
    }

    private fun disconnectSession(session: Session) {
        session.job?.cancel()
        session.job = null
        session.stream?.disconnect()
        session.stream = null
        updateMarket(session.index) { it.copy(isConnected = false) }
    }

    private fun resetSessionCore(session: Session) {
        session.tickCore.reset()
        session.adaptiveExit.reset()
        session.workingMinute = null
        session.oneMinute.clear()
        session.v76Bars.clear()
        session.spotHistory.clear()
        session.optionHistory.clear()
        session.vixLtp = 0.0
        session.lastSignalPublishMillis = 0L
        session.lastV76SignalMillis = 0L
        session.pendingEntry.clear()
        session.pendingExit.clear()
        session.lastAutoAttemptKey.clear()
        session.lastAutoAttemptMillis.clear()
        session.v76ConsecutiveLosses = 0
    }

    private fun warmSession(session: Session, candles: List<UpstoxIntradayCandleClient.Candle>) {
        val currentMinute = System.currentTimeMillis() / 60_000L
        candles.asSequence()
            .filter { it.time.toInstant().toEpochMilli() / 60_000L < currentMinute }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .takeLastFull(MAX_ONE_MINUTE_BARS)
            .forEach { c ->
                val ts = c.time.toInstant().toEpochMilli()
                session.oneMinute.addLast(
                    MultiTimeframeConfirmation.TimedBar(ts, c.open, c.high, c.low, c.close, c.volume),
                )
                session.v76Bars.addLast(V76ScalperEngine.Bar(ts, c.open, c.high, c.low, c.close, c.volume))
            }
    }

    private fun publishSnapshot(session: Session, snapshot: UpstoxLiveClient.Snapshot) {
        val now = System.currentTimeMillis()
        updateMarket(session.index) {
            it.copy(
                spotPrice = snapshot.spot,
                optionChain = snapshot.options,
                lastTickMillis = now,
            )
        }
        storeSpot(session, snapshot.spot, now)
        session.tickCore.ingest(snapshot.spot, now)
        updatePositions(session)
        evaluateSignals(session, now)
        evaluateV76(session)
    }

    private fun applyTick(session: Session, tick: UpstoxTickStream.Tick) {
        val index = session.index
        val current = _state.value.market(index)
        val tickTimestamp = tick.ltt?.takeIf { it > 0L } ?: tick.feedTimestamp
        when {
            tick.instrumentKey == INDIA_VIX_KEY && tick.ltp != null -> session.vixLtp = tick.ltp
            tick.instrumentKey == session.underlyingKey && tick.ltp != null -> {
                onSpotTick(session, tick.ltp, tickTimestamp, tick.ltq ?: 0L)
            }
            else -> {
                storeOption(session, tick)
                if (tick.ltp != null && tick.ltp > 0.0) {
                    MetaBrainRuntime.observeOptionPremium(index, tick.instrumentKey, tick.ltp, tickTimestamp)
                }
                val nextChain = current.optionChain.map { q ->
                    if (q.instrumentKey != tick.instrumentKey) q else q.copy(
                        ltp = tick.ltp ?: q.ltp,
                        openInterest = tick.oi ?: q.openInterest,
                        delta = tick.delta ?: q.delta,
                        gamma = tick.gamma ?: q.gamma,
                        lastTickMillis = tickTimestamp,
                        bid = tick.bid ?: q.bid,
                        ask = tick.ask ?: q.ask,
                        volume = tick.volume ?: q.volume,
                    )
                }
                updateMarket(index) { it.copy(optionChain = nextChain) }
            }
        }
        val depthLevels = if (tick.depth.isNotEmpty()) tick.depth.size else current.marketDepthLevels
        val depthMode = if (tick.requestMode.isNotBlank()) tick.requestMode.uppercase() else current.marketDepthMode
        updateMarket(index) {
            it.copy(
                isConnected = true,
                lastTickMillis = tickTimestamp,
                ticksReceived = it.ticksReceived + 1,
                marketDepthMode = depthMode,
                marketDepthLevels = depthLevels,
            )
        }
        updatePositions(session)
        managePositions(session)
    }

    private fun onSpotTick(session: Session, price: Double, timestamp: Long, volume: Long) {
        if (price <= 0.0 || timestamp <= 0L) return
        storeSpot(session, price, timestamp)
        MetaBrainRuntime.observeSpot(session.index, price, timestamp)
        session.tickCore.ingest(price, timestamp)
        ingestMinute(session, price, timestamp, volume)
        updateMarket(session.index) { it.copy(spotPrice = price, lastTickMillis = timestamp) }
        if (timestamp - session.lastSignalPublishMillis >= SIGNAL_REFRESH_MS) {
            session.lastSignalPublishMillis = timestamp
            evaluateSignals(session, timestamp)
        }
    }

    private fun ingestMinute(session: Session, price: Double, timestamp: Long, volume: Long) {
        val minute = timestamp / 60_000L
        val working = session.workingMinute
        if (working == null) {
            session.workingMinute = WorkingMinute(minute, price, price, price, price, volume)
            return
        }
        if (working.minute == minute) {
            working.high = max(working.high, price)
            working.low = min(working.low, price)
            working.close = price
            working.volume += volume.coerceAtLeast(0L)
            return
        }
        val ts = working.minute * 60_000L
        session.oneMinute.addLast(
            MultiTimeframeConfirmation.TimedBar(ts, working.open, working.high, working.low, working.close, working.volume),
        )
        session.v76Bars.addLast(
            V76ScalperEngine.Bar(ts, working.open, working.high, working.low, working.close, working.volume),
        )
        while (session.oneMinute.size > MAX_ONE_MINUTE_BARS) session.oneMinute.removeFirst()
        while (session.v76Bars.size > MAX_ONE_MINUTE_BARS) session.v76Bars.removeFirst()
        session.workingMinute = WorkingMinute(minute, price, price, price, price, volume)
        evaluateV76(session)
    }

    private fun evaluateSignals(session: Session, timestamp: Long) {
        val market = _state.value.market(session.index)
        if (market.spotPrice <= 0.0) return
        val tickResult = session.tickCore.evaluate(session.index)
        val e1 = gateWithTimeframes(session, EngineId.ENGINE_1_TREND, tickResult.engine1)
        val e2Timed = gateWithTimeframes(session, EngineId.ENGINE_2_AVWAP_LIQUIDITY, tickResult.engine2)
        val e2 = confirmEngine2(session, e2Timed, timestamp)
        updateMarket(session.index) { current ->
            current.copy(
                engine1 = current.engine1.copy(signal = e1, message = e1.setup),
                engine2 = current.engine2.copy(signal = e2, message = e2.setup),
            )
        }
        runAuto(session)
    }

    private fun gateWithTimeframes(session: Session, engine: EngineId, raw: SignalSnapshot): SignalSnapshot {
        val config = _state.value.engineTimeframes[engine] ?: return raw
        val mtf = session.mtf.evaluate(session.oneMinute.toList(), config)
        if (!mtf.ready) {
            return raw.copy(
                action = SignalAction.WAIT,
                confidence = min(raw.confidence, mtf.score),
                entry = null,
                stopLoss = null,
                target = null,
                reasons = (raw.reasons + mtf.reasons).takeLast(10),
                setup = "${raw.setup} · MTF WARM-UP",
            )
        }
        val rawDirection = when (raw.action) {
            SignalAction.BUY_CE -> TrendDirection.BULLISH
            SignalAction.BUY_PE -> TrendDirection.BEARISH
            SignalAction.WAIT -> raw.trend
        }
        val aligned = raw.action == SignalAction.WAIT || mtf.confirms(rawDirection)
        val blendedConfidence = ((raw.confidence * 0.70) + (mtf.score * 0.30)).toInt().coerceIn(0, 100)
        return if (aligned) {
            raw.copy(
                confidence = blendedConfidence,
                reasons = (raw.reasons + mtf.reasons).takeLast(10),
                setup = "${raw.setup} · MTF ${config.trigger.label}/${config.setup.label}/${config.bias.label}",
            )
        } else {
            raw.copy(
                action = SignalAction.WAIT,
                confidence = min(raw.confidence, mtf.score),
                entry = null,
                stopLoss = null,
                target = null,
                reasons = (raw.reasons + mtf.reasons + "Blocked: configured timeframe direction mismatch").takeLast(10),
                setup = "${raw.setup} · MTF BLOCK",
            )
        }
    }

    private fun confirmEngine2(session: Session, raw: SignalSnapshot, timestamp: Long): SignalSnapshot {
        val side = when {
            raw.action == SignalAction.BUY_CE -> PositionSide.CE
            raw.action == SignalAction.BUY_PE -> PositionSide.PE
            raw.confidence >= ENGINE2_SPOT_CANDIDATE_SCORE && raw.trend == TrendDirection.BULLISH -> PositionSide.CE
            raw.confidence >= ENGINE2_SPOT_CANDIDATE_SCORE && raw.trend == TrendDirection.BEARISH -> PositionSide.PE
            else -> return raw
        }
        val market = _state.value.market(session.index)
        if (market.optionChain.isEmpty()) return raw
        val quality = isolatedQuality(session) {
            V76ExecutionQualityEngine.evaluate(side, market.optionChain, market.spotPrice)
        }
        val combined = ((raw.confidence * 45 + quality.score * 55) / 100).coerceIn(0, 100)
        val reasons = (raw.reasons + quality.reasons).distinct().takeLast(10)
        if (!quality.canEnter || raw.action == SignalAction.WAIT) {
            return raw.copy(
                action = SignalAction.WAIT,
                confidence = combined,
                entry = null,
                stopLoss = null,
                target = null,
                reasons = reasons,
                setup = "${raw.setup} · ${quality.label}",
            )
        }
        return MetaBrainRuntime.decorate(
            index = session.index,
            engine = EngineId.ENGINE_2_AVWAP_LIQUIDITY,
            raw = raw.copy(
                confidence = max(ENGINE2_CONFIRMED_MIN_SCORE, combined),
                reasons = reasons,
                setup = "${raw.setup} · ${quality.label}",
            ),
            spot = market.spotPrice,
            timestamp = timestamp,
            directionScore = quality.directionScore.toDouble(),
            entryQualityScore = quality.entryQualityScore.toDouble(),
            orderFlow = quality.orderFlowProxy,
            relativeActivity = quality.relativeActivity,
            oiImpulse = quality.optionOiImpulse,
            optionFlow = quality.optionFlowProxy,
            acceleration = quality.acceleration,
            extensionAtr = quality.extensionAtr,
            depthImbalance = quality.depthImbalance,
            micropricePressure = quality.micropricePressure,
            totalBookPressure = quality.totalBookPressure,
            wallPressure = quality.wallPressure,
            depthLevels = quality.depthLevels.toDouble(),
        )
    }

    private fun evaluateV76(session: Session) {
        val market = _state.value.market(session.index)
        if (market.spotPrice <= 0.0 || session.v76Bars.isEmpty()) return
        val evaluation = isolatedQuality(session) {
            session.v76Core.evaluate(
                session.v76Bars.toList(),
                market.optionChain,
                market.spotPrice,
                session.vixLtp,
            )
        }
        session.lastV76Evaluation = evaluation
        updateMarket(session.index) {
            it.copy(engine3 = it.engine3.copy(signal = evaluation.signal, message = evaluation.signal.setup))
        }
        runAuto(session)
    }

    private fun runAuto(session: Session) {
        val global = _state.value
        val market = global.market(session.index)
        if (!market.isConnected || global.tradingMode != TradingMode.AUTO || global.emergencyKill || market.riskLocked) return
        if (global.executionMode == ExecutionMode.LIVE && global.liveArmMode != LiveArmMode.AUTO_ARMED) return

        global.enabledEngines.forEach { engine ->
            val engineState = market.engine(engine)
            if (engineState.position != null || engine in session.pendingEntry || engine in session.pendingExit) return@forEach
            val signal = engineState.signal
            val side = when (signal.action) {
                SignalAction.BUY_CE -> PositionSide.CE
                SignalAction.BUY_PE -> PositionSide.PE
                SignalAction.WAIT -> return@forEach
            }

            if (engine == EngineId.ENGINE_3_V76_SCALPER && session.v76ConsecutiveLosses >= V76ScalperEngine.MAX_CONSECUTIVE_LOSSES) {
                updateEngine(session.index, engine) { it.copy(message = "V7.6 KILL · ${session.v76ConsecutiveLosses} consecutive losses") }
                return@forEach
            }

            val lastExit = session.lastExitMillis[engine] ?: 0L
            val elapsed = System.currentTimeMillis() - lastExit
            val lastSide = session.lastExitSide[engine]
            if (engine == EngineId.ENGINE_3_V76_SCALPER) {
                if (lastExit > 0L && lastSide == side && elapsed < V76ScalperEngine.SAME_DIRECTION_COOLDOWN_MINUTES * 60_000L) return@forEach
                if (lastExit > 0L && lastSide != null && lastSide != side && session.lastV76Evaluation.score < V76ScalperEngine.REVERSAL_MIN_SCORE) return@forEach
                val eval = session.lastV76Evaluation
                if (eval.signalTimeMillis == 0L || eval.signalTimeMillis == session.lastV76SignalMillis) return@forEach
            } else if (lastExit > 0L && elapsed < DEFAULT_E1_E2_COOLDOWN_MS) {
                return@forEach
            }

            val key = "${side.name}|${signal.setup}|${signal.confidence / 5}|${System.currentTimeMillis() / 60_000L}"
            val previousKey = session.lastAutoAttemptKey[engine]
            val previousAt = session.lastAutoAttemptMillis[engine] ?: 0L
            if (key == previousKey && System.currentTimeMillis() - previousAt < AUTO_DUPLICATE_SUPPRESSION_MS) return@forEach
            session.lastAutoAttemptKey[engine] = key
            session.lastAutoAttemptMillis[engine] = System.currentTimeMillis()
            if (engine == EngineId.ENGINE_3_V76_SCALPER) session.lastV76SignalMillis = session.lastV76Evaluation.signalTimeMillis
            requestOpen(session, engine, side, signal.setup, automatic = true)
        }
    }

    private fun requestOpen(
        session: Session,
        engine: EngineId,
        side: PositionSide,
        reason: String,
        automatic: Boolean,
    ) {
        if (!session.pendingEntry.add(engine)) return
        ProcessTradingScope.scope.launch {
            try {
                openPosition(session, engine, side, reason, automatic)
            } catch (error: Exception) {
                updateEngine(session.index, engine) {
                    it.copy(message = "ENTRY ERROR · ${error.message?.take(140)}")
                }
            } finally {
                session.pendingEntry.remove(engine)
            }
        }
    }

    private suspend fun openPosition(
        session: Session,
        engine: EngineId,
        side: PositionSide,
        reason: String,
        automatic: Boolean,
    ) {
        val global = _state.value
        val market = global.market(session.index)
        if (!market.isConnected || market.riskLocked || global.emergencyKill || market.engine(engine).position != null) return
        val tradesToday = todayTradeCount(market)
        if (tradesToday >= global.riskConfig.maxTradesPerIndex) {
            updateEngine(session.index, engine) {
                it.copy(message = "DAILY TRADE LIMIT · $tradesToday/${global.riskConfig.maxTradesPerIndex}")
            }
            return
        }

        val quote = if (engine == EngineId.ENGINE_3_V76_SCALPER) {
            selectV76Option(market.optionChain, side, market.spotPrice)
        } else {
            session.selector.select(market.optionChain, side.name)?.quote
        }
        if (quote == null) {
            updateEngine(session.index, engine) { it.copy(message = "No liquid ${side.name} contract") }
            return
        }

        val lots = global.lotsFor(session.index)
        val lotSize = when {
            quote.lotSize > 0 -> quote.lotSize
            market.expiry == "DEMO" -> if (session.index == MarketIndex.NIFTY) 65 else 20
            else -> 0
        }
        if (lotSize <= 0) {
            updateEngine(session.index, engine) { it.copy(message = "ENTRY BLOCKED · broker lot size unavailable") }
            return
        }
        val quantity = lots * lotSize
        val signal = market.engine(engine).signal
        val estimatedEntry = paperBuy(quote)
        if (estimatedEntry <= 0.0) return
        val strategy = if (engine == EngineId.ENGINE_3_V76_SCALPER) {
            session.lastV76Evaluation.strategy ?: "PULLBACK"
        } else {
            market.engine(engine).name
        }
        val riskPreview = AdaptiveExitEngine().open(engine, side, estimatedEntry, System.currentTimeMillis(), strategy)
        val plannedRisk = (estimatedEntry - riskPreview.stopPrice).coerceAtLeast(0.0) * quantity + AdaptiveExitEngine.PAPER_ROUND_TRIP_COST_INR

        val executionMode = global.executionMode
        var brokerReference = ""
        var actualQuantity = quantity
        val entryPrice = if (executionMode == ExecutionMode.PAPER) {
            estimatedEntry
        } else {
            val gate = liveGate(global, market, quote, quantity, signal.confidence, automatic, plannedRisk)
            if (!gate.allowed) {
                updateEngine(session.index, engine) { it.copy(message = "LIVE BLOCKED · ${gate.reason}") }
                return
            }
            val client = UpstoxOrderClient(accessToken)
            val placement = withContext(Dispatchers.IO) {
                client.placeMarketOrder(
                    instrumentKey = quote.instrumentKey,
                    quantity = quantity,
                    transactionType = UpstoxOrderClient.TransactionType.BUY,
                    tag = liveTag(session.index, engine, "IN"),
                )
            }
            val execution = withContext(Dispatchers.IO) { client.awaitExecution(placement, quantity) }
            brokerReference = execution.brokerReference
            if (execution.zeroFill) error("Broker BUY filled 0/$quantity; ${execution.states.joinToString { it.state }}")
            if (!execution.fullyFilled) {
                val residual = flattenUnexpectedEntry(
                    client = client,
                    index = session.index,
                    engine = engine,
                    instrumentKey = quote.instrumentKey,
                    quantity = execution.filledQuantity,
                )
                if (residual <= 0) {
                    error("Broker BUY partial ${execution.filledQuantity}/$quantity was flattened safely")
                }
                actualQuantity = residual
                _state.update {
                    it.copy(
                        emergencyKill = true,
                        liveArmMode = LiveArmMode.DISARMED,
                        message = "PARTIAL ENTRY RESIDUAL $residual · emergency kill active",
                    )
                }
            } else if (_state.value.emergencyKill) {
                val residual = flattenUnexpectedEntry(
                    client = client,
                    index = session.index,
                    engine = engine,
                    instrumentKey = quote.instrumentKey,
                    quantity = execution.filledQuantity,
                )
                if (residual <= 0) error("Entry filled after emergency kill and was flattened")
                actualQuantity = residual
            }
            execution.averagePrice.takeIf { it > 0.0 } ?: estimatedEntry
        }

        if (entryPrice <= 0.0 || actualQuantity <= 0) return
        val now = System.currentTimeMillis()
        val plan = session.adaptiveExit.open(engine, side, entryPrice, now, strategy)
        val invalidation = if (engine == EngineId.ENGINE_3_V76_SCALPER) {
            session.lastV76Evaluation.indexInvalidation
        } else {
            signal.stopLoss ?: 0.0
        }
        val effectiveLots = max(1, ceil(actualQuantity.toDouble() / lotSize).toInt())
        val position = PaperPosition(
            side = side,
            strike = quote.strike,
            quantity = actualQuantity,
            entryPrice = entryPrice,
            currentPrice = quote.ltp,
            highestPrice = entryPrice,
            stopPrice = plan.stopPrice,
            targetPrice = plan.target1Price,
            openedAtMillis = now,
            strategy = strategy,
            lotSize = lotSize,
            lots = effectiveLots,
            initialQuantity = actualQuantity,
            indexInvalidation = invalidation,
            maxHoldMinutes = plan.maxHoldMinutes,
            instrumentKey = quote.instrumentKey,
            executionMode = executionMode,
            brokerEntryOrderId = brokerReference,
        )
        updateEngine(session.index, engine) {
            it.copy(
                position = position,
                message = "${executionMode.name} ${side.name} ${quote.strike.toInt()} · ${actualQuantity} qty · ADAPTIVE EXIT",
            )
        }
        recordOpen(session.index, engine, position, reason)
    }

    private suspend fun flattenUnexpectedEntry(
        client: UpstoxOrderClient,
        index: MarketIndex,
        engine: EngineId,
        instrumentKey: String,
        quantity: Int,
    ): Int {
        if (quantity <= 0) return 0
        val placement = withContext(Dispatchers.IO) {
            client.placeMarketOrder(
                instrumentKey = instrumentKey,
                quantity = quantity,
                transactionType = UpstoxOrderClient.TransactionType.SELL,
                tag = liveTag(index, engine, "FLAT"),
            )
        }
        val execution = withContext(Dispatchers.IO) { client.awaitExecution(placement, quantity) }
        return (quantity - execution.filledQuantity).coerceAtLeast(0)
    }

    private fun managePositions(session: Session) {
        EngineId.entries.forEach { engine ->
            val market = _state.value.market(session.index)
            val engineState = market.engine(engine)
            var position = engineState.position ?: return@forEach
            val quote = market.optionChain.firstOrNull {
                it.instrumentKey == position.instrumentKey ||
                    (it.strike == position.strike && it.type == position.side.name)
            } ?: return@forEach
            val currentPrice = quote.ltp
            if (currentPrice <= 0.0) return@forEach
            val opposite = (position.side == PositionSide.CE && engineState.signal.action == SignalAction.BUY_PE) ||
                (position.side == PositionSide.PE && engineState.signal.action == SignalAction.BUY_CE)
            val invalid = (position.side == PositionSide.CE && position.indexInvalidation > 0.0 && market.spotPrice < position.indexInvalidation) ||
                (position.side == PositionSide.PE && position.indexInvalidation > 0.0 && market.spotPrice > position.indexInvalidation)
            val quality = isolatedQuality(session) {
                V76ExecutionQualityEngine.evaluate(position.side, market.optionChain, market.spotPrice)
            }
            val update = session.adaptiveExit.update(
                engine = engine,
                side = position.side,
                entryPrice = position.entryPrice,
                currentPrice = currentPrice,
                timestamp = System.currentTimeMillis(),
                currentStopPrice = position.stopPrice,
                previousHighestPrice = position.highestPrice,
                target1Hit = position.target1Hit,
                quantity = position.quantity,
                strategy = position.strategy,
                oppositeSignal = opposite,
                indexInvalidated = invalid,
                quality = quality,
            )
            position = position.copy(
                currentPrice = currentPrice,
                highestPrice = update.highestPrice,
                stopPrice = update.stopPrice,
                targetPrice = update.target1Price,
                breakevenActive = update.breakevenActive,
                trailingActive = update.trailingActive,
                maxHoldMinutes = if (position.target1Hit) update.runnerMaxHoldMinutes else update.maxHoldMinutes,
            )
            updateEngine(session.index, engine) {
                it.copy(
                    position = position,
                    message = "ADAPTIVE EXIT · ${update.diagnostic}${if (position.target1Hit) " · RUNNER" else ""}",
                )
            }

            if (update.partialTrigger && !position.target1Hit && position.quantity >= position.lotSize * 2 && engine !in session.pendingExit) {
                requestPartial(session, engine)
                return@forEach
            }
            update.exitReason?.let { requestClose(session, engine, it.name.replace('_', ' ')) }
        }
        updateRiskLock(session.index)
    }

    private fun requestPartial(session: Session, engine: EngineId) {
        if (!session.pendingExit.add(engine)) return
        ProcessTradingScope.scope.launch {
            try {
                val market = _state.value.market(session.index)
                val current = market.engine(engine)
                val p = current.position ?: return@launch
                if (p.target1Hit || p.quantity < p.lotSize * 2) return@launch
                val desiredLots = max(1, (p.lots * AdaptiveExitEngine.TARGET1_PARTIAL_FRACTION).toInt())
                val desiredQty = min(p.quantity - p.lotSize, desiredLots * p.lotSize)
                if (desiredQty <= 0) return@launch
                val quote = market.optionChain.firstOrNull { it.instrumentKey == p.instrumentKey } ?: return@launch
                val execution = executeSell(p, quote, desiredQty, liveTag(session.index, engine, "T1"))
                val soldQty = execution.filledQuantity
                if (soldQty <= 0) {
                    updateEngine(session.index, engine) { it.copy(message = "T1 EXIT NOT FILLED") }
                    return@launch
                }
                val fillPrice = execution.averagePrice.takeIf { it > 0.0 } ?: paperSell(quote)
                val realized = p.realizedPartialPnl + (fillPrice - p.entryPrice) * soldQty - AdaptiveExitEngine.PAPER_EXTRA_EXIT_ORDER_COST_INR
                val remainingQty = p.quantity - soldQty
                val nextLots = max(1, ceil(remainingQty.toDouble() / p.lotSize).toInt())
                val next = p.copy(
                    quantity = remainingQty,
                    lots = nextLots,
                    target1Hit = true,
                    target1ExitQuantity = p.target1ExitQuantity + soldQty,
                    realizedPartialPnl = realized,
                )
                updateEngine(session.index, engine) {
                    it.copy(
                        position = next,
                        message = if (execution.fullyFilled) "T1 PARTIAL · $soldQty qty · RUNNER ACTIVE" else "T1 PARTIAL FILL · $soldQty/$desiredQty · RUNNER ACTIVE",
                    )
                }
            } catch (error: Exception) {
                updateEngine(session.index, engine) { it.copy(message = "T1 EXIT ERROR · ${error.message?.take(120)}") }
            } finally {
                session.pendingExit.remove(engine)
            }
        }
    }

    private fun requestClose(session: Session, engine: EngineId, reason: String) {
        if (!session.pendingExit.add(engine)) return
        ProcessTradingScope.scope.launch {
            try {
                closePosition(session, engine, reason)
            } catch (error: Exception) {
                updateEngine(session.index, engine) { it.copy(message = "EXIT ERROR · ${error.message?.take(140)}") }
            } finally {
                session.pendingExit.remove(engine)
            }
        }
    }

    private suspend fun closePosition(session: Session, engine: EngineId, reason: String) {
        val market = _state.value.market(session.index)
        val current = market.engine(engine)
        val p = current.position ?: return
        val quote = market.optionChain.firstOrNull { it.instrumentKey == p.instrumentKey }
            ?: market.optionChain.firstOrNull { it.strike == p.strike && it.type == p.side.name }
        if (quote == null && p.executionMode == ExecutionMode.LIVE) error("Live exit quote unavailable")

        val execution = if (p.executionMode == ExecutionMode.LIVE) {
            executeSell(p, quote!!, p.quantity, liveTag(session.index, engine, "OUT"))
        } else {
            UpstoxOrderClient.Execution(
                orderIds = emptyList(),
                requestedQuantity = p.quantity,
                filledQuantity = p.quantity,
                pendingQuantity = 0,
                averagePrice = quote?.let(::paperSell) ?: p.currentPrice,
                states = emptyList(),
            )
        }
        val soldQty = execution.filledQuantity.coerceIn(0, p.quantity)
        if (soldQty <= 0) {
            updateEngine(session.index, engine) { it.copy(message = "$reason · EXIT NOT FILLED") }
            return
        }
        val exitPrice = execution.averagePrice.takeIf { it > 0.0 } ?: quote?.let(::paperSell) ?: p.currentPrice
        val realizedFromThisExit = (exitPrice - p.entryPrice) * soldQty
        val remainingQty = p.quantity - soldQty
        if (remainingQty > 0) {
            val residual = p.copy(
                quantity = remainingQty,
                lots = max(1, ceil(remainingQty.toDouble() / p.lotSize).toInt()),
                realizedPartialPnl = p.realizedPartialPnl + realizedFromThisExit,
            )
            updateEngine(session.index, engine) {
                it.copy(
                    position = residual,
                    message = "$reason · PARTIAL BROKER EXIT $soldQty/${p.quantity} · residual $remainingQty",
                )
            }
            if (p.executionMode == ExecutionMode.LIVE) {
                _state.update {
                    it.copy(
                        emergencyKill = true,
                        liveArmMode = LiveArmMode.DISARMED,
                        message = "LIVE residual position ${session.index} $remainingQty qty · emergency kill active",
                    )
                }
            }
            return
        }

        val pnl = p.realizedPartialPnl + realizedFromThisExit - AdaptiveExitEngine.PAPER_ROUND_TRIP_COST_INR
        session.adaptiveExit.close(engine)
        val old = current.performance
        val realized = old.realizedPnl + pnl
        val peak = max(old.peakEquity, realized)
        val dd = (peak - realized).coerceAtLeast(0.0)
        val perf = old.copy(
            trades = old.trades + 1,
            wins = old.wins + if (pnl > 0.0) 1 else 0,
            losses = old.losses + if (pnl < 0.0) 1 else 0,
            realizedPnl = realized,
            grossProfit = old.grossProfit + pnl.coerceAtLeast(0.0),
            grossLoss = old.grossLoss + (-pnl).coerceAtLeast(0.0),
            peakEquity = peak,
            maxDrawdown = max(old.maxDrawdown, dd),
        )
        updateEngine(session.index, engine) {
            it.copy(
                position = null,
                performance = perf,
                message = "$reason · ${p.executionMode.name} P&L ₹${"%.2f".format(pnl)}",
            )
        }
        recordClose(
            index = session.index,
            engine = engine,
            p = p,
            exitPrice = exitPrice,
            pnl = pnl,
            reason = reason,
            exitOrderId = execution.brokerReference,
        )
        session.lastExitMillis[engine] = System.currentTimeMillis()
        session.lastExitSide[engine] = p.side
        if (engine == EngineId.ENGINE_3_V76_SCALPER) {
            session.v76ConsecutiveLosses = if (pnl < 0.0) session.v76ConsecutiveLosses + 1 else 0
        }
        updateRiskLock(session.index)
    }

    private suspend fun executeSell(
        p: PaperPosition,
        quote: OptionQuote,
        quantity: Int,
        tag: String,
    ): UpstoxOrderClient.Execution {
        if (p.executionMode == ExecutionMode.PAPER) {
            return UpstoxOrderClient.Execution(
                orderIds = emptyList(),
                requestedQuantity = quantity,
                filledQuantity = quantity,
                pendingQuantity = 0,
                averagePrice = paperSell(quote),
                states = emptyList(),
            )
        }
        val client = UpstoxOrderClient(accessToken)
        val placement = withContext(Dispatchers.IO) {
            client.placeMarketOrder(
                instrumentKey = p.instrumentKey,
                quantity = quantity,
                transactionType = UpstoxOrderClient.TransactionType.SELL,
                tag = tag,
            )
        }
        return withContext(Dispatchers.IO) { client.awaitExecution(placement, quantity) }
    }

    private fun liveGate(
        global: FullDashboardState,
        market: FullMarketState,
        quote: OptionQuote,
        quantity: Int,
        confidence: Int,
        automatic: Boolean,
        plannedRiskInr: Double,
    ): LiveGateDecision {
        val spread = if (quote.ask > 0.0 && quote.bid > 0.0) {
            (quote.ask - quote.bid) / max((quote.ask + quote.bid) / 2.0, 0.01) * 100.0
        } else {
            Double.POSITIVE_INFINITY
        }
        val now = System.currentTimeMillis()
        return LiveExecutionGuard.evaluate(
            LiveGateInput(
                executionMode = global.executionMode,
                armMode = global.liveArmMode,
                automatic = automatic,
                connected = market.isConnected,
                upstoxTokenPresent = accessToken.isNotBlank(),
                instrumentKeyPresent = quote.instrumentKey.isNotBlank() && quote.lotSize > 0,
                quantity = quantity,
                plannedRiskInr = plannedRiskInr,
                riskLocked = market.riskLocked,
                emergencyKill = global.emergencyKill,
                marketOpen = isMarketOpen(),
                entriesAllowed = isEntryWindowOpen(),
                tickAgeMillis = if (market.lastTickMillis > 0L) (now - market.lastTickMillis).coerceAtLeast(0L) else Long.MAX_VALUE,
                confidence = confidence,
                spreadPercent = spread,
                tradesToday = todayTradeCount(market),
                risk = global.riskConfig,
            ),
        )
    }

    private fun updateRiskLock(index: MarketIndex) {
        val global = _state.value
        val market = global.market(index)
        val locked = market.realizedPnl <= -global.riskConfig.dailyLossLimitInr
        updateMarket(index) {
            it.copy(
                riskLocked = locked,
                riskReason = if (locked) {
                    "Daily realized loss reached ₹${"%.0f".format(global.riskConfig.dailyLossLimitInr)}"
                } else {
                    "Risk gates clear"
                },
            )
        }
        if (locked && global.executionMode == ExecutionMode.LIVE) {
            _state.update { it.copy(liveArmMode = LiveArmMode.DISARMED, message = "$index risk lock · LIVE disarmed") }
        }
    }

    private fun todayTradeCount(market: FullMarketState): Int {
        val today = LocalDate.now(INDIA_ZONE)
        return market.tradeLog.count { trade ->
            Instant.ofEpochMilli(trade.entryTimeMillis).atZone(INDIA_ZONE).toLocalDate() == today
        }
    }

    private fun updatePositions(session: Session) {
        val market = _state.value.market(session.index)
        var next = market
        EngineId.entries.forEach { engine ->
            val engineState = next.engine(engine)
            val p = engineState.position ?: return@forEach
            val quote = next.optionChain.firstOrNull { it.instrumentKey == p.instrumentKey }
                ?: return@forEach
            next = next.withEngine(engine, engineState.copy(position = p.copy(currentPrice = quote.ltp)))
        }
        if (next != market) replaceMarket(session.index, next)
    }

    private fun recordOpen(index: MarketIndex, engine: EngineId, p: PaperPosition, setup: String) {
        updateMarket(index) { market ->
            val engineState = market.engine(engine)
            val entry = TradeLogEntry(
                id = p.openedAtMillis,
                engineId = engine,
                engineName = engineState.name,
                index = index,
                side = p.side,
                strike = p.strike,
                quantity = p.quantity,
                lots = p.lots,
                entryPrice = p.entryPrice,
                entrySpot = market.spotPrice,
                entryTimeMillis = p.openedAtMillis,
                setup = setup,
                executionMode = p.executionMode,
                brokerEntryOrderId = p.brokerEntryOrderId,
            )
            market.copy(tradeLog = (market.tradeLog + entry).takeLast(MAX_TRADE_LOG))
        }
    }

    private fun recordClose(
        index: MarketIndex,
        engine: EngineId,
        p: PaperPosition,
        exitPrice: Double,
        pnl: Double,
        reason: String,
        exitOrderId: String,
    ) {
        updateMarket(index) { market ->
            val idx = market.tradeLog.indexOfLast {
                it.engineId == engine && it.status == TradeStatus.OPEN && it.entryTimeMillis == p.openedAtMillis
            }
            if (idx < 0) return@updateMarket market
            val log = market.tradeLog.toMutableList()
            log[idx] = log[idx].copy(
                status = TradeStatus.CLOSED,
                exitPrice = exitPrice,
                exitSpot = market.spotPrice,
                exitTimeMillis = System.currentTimeMillis(),
                pnl = pnl,
                exitReason = reason,
                brokerExitOrderId = exitOrderId,
            )
            market.copy(tradeLog = log.takeLast(MAX_TRADE_LOG))
        }
    }

    private fun storeSpot(session: Session, price: Double, timestamp: Long) {
        session.spotHistory.addLast(StoredSpotTick(price, timestamp))
        while (session.spotHistory.size > QUALITY_SPOT_HISTORY) session.spotHistory.removeFirst()
    }

    private fun storeOption(session: Session, tick: UpstoxTickStream.Tick) {
        val q = session.optionHistory.getOrPut(tick.instrumentKey) { ArrayDeque() }
        q.addLast(
            StoredOptionTick(
                instrumentKey = tick.instrumentKey,
                ltp = tick.ltp,
                oi = tick.oi,
                volume = tick.volume,
                bid = tick.bid,
                ask = tick.ask,
                timestamp = tick.ltt?.takeIf { it > 0L } ?: tick.feedTimestamp,
                ltq = tick.ltq,
                totalBuyQty = tick.totalBuyQty,
                totalSellQty = tick.totalSellQty,
                depth = tick.depth,
            ),
        )
        while (q.size > QUALITY_OPTION_HISTORY) q.removeFirst()
        while (session.optionHistory.size > MAX_TRACKED_OPTIONS) {
            session.optionHistory.remove(session.optionHistory.keys.first())
        }
    }

    /**
     * V76ExecutionQualityEngine is a legacy singleton. For true BOTH mode we replay only
     * one market's retained ticks while holding the singleton monitor, so NIFTY and SENSEX
     * never evaluate against each other's D30/order-flow history.
     */
    private fun <T> isolatedQuality(session: Session, block: () -> T): T = synchronized(V76ExecutionQualityEngine) {
        V76ExecutionQualityEngine.reset()
        session.spotHistory.forEach {
            V76ExecutionQualityEngine.ingestSpot(it.price, it.timestamp)
        }
        session.optionHistory.values.forEach { history ->
            history.forEach { tick ->
                V76ExecutionQualityEngine.ingestOption(
                    instrumentKey = tick.instrumentKey,
                    ltp = tick.ltp,
                    oi = tick.oi,
                    volume = tick.volume,
                    bid = tick.bid,
                    ask = tick.ask,
                    timestamp = tick.timestamp,
                    ltq = tick.ltq,
                    totalBuyQty = tick.totalBuyQty,
                    totalSellQty = tick.totalSellQty,
                    depth = tick.depth.map {
                        V76ExecutionQualityEngine.DepthLevel(
                            it.bidPrice,
                            it.bidQty,
                            it.askPrice,
                            it.askQty,
                        )
                    },
                )
            }
        }
        block()
    }

    private fun selectV76Option(
        chain: List<OptionQuote>,
        side: PositionSide,
        spot: Double,
    ): OptionQuote? {
        val step = if (spot >= 50_000.0) 100.0 else 50.0
        return chain.asSequence()
            .filter { it.type == side.name && abs(it.strike - spot) <= step * 2.0 }
            .filter { paperBuy(it) in V76ScalperEngine.MIN_OPTION_PREMIUM..V76ScalperEngine.MAX_OPTION_PREMIUM }
            .filter { q ->
                q.bid <= 0.0 || q.ask <= 0.0 ||
                    ((q.ask - q.bid) / max((q.ask + q.bid) / 2.0, 0.01) * 100.0) <= 4.5
            }
            .minByOrNull { q ->
                val targetDelta = if (side == PositionSide.CE) {
                    V76ScalperEngine.TARGET_ABS_DELTA
                } else {
                    -V76ScalperEngine.TARGET_ABS_DELTA
                }
                val deltaPenalty = if (q.delta != 0.0) abs(q.delta - targetDelta) else 0.20
                val strikePenalty = abs(q.strike - spot) / step
                val liquidityBonus = min(kotlin.math.log10(max(q.volume, 1L).toDouble()) / 10.0, 0.6)
                val spread = if (q.ask > 0.0 && q.bid > 0.0) {
                    (q.ask - q.bid) / max((q.ask + q.bid) / 2.0, 0.01) * 100.0
                } else {
                    0.0
                }
                deltaPenalty * 4.0 + strikePenalty * 0.35 + spread * 0.15 - liquidityBonus
            }
    }

    private fun paperBuy(q: OptionQuote): Double = if (q.ask > 0.0) q.ask else q.ltp
    private fun paperSell(q: OptionQuote): Double = if (q.bid > 0.0) q.bid else q.ltp

    private fun isMarketOpen(now: LocalTime = LocalTime.now(INDIA_ZONE)): Boolean = now >= MARKET_OPEN && now <= MARKET_CLOSE
    private fun isEntryWindowOpen(now: LocalTime = LocalTime.now(INDIA_ZONE)): Boolean = now >= ENTRY_START && now <= ENTRY_END

    private fun liveTag(index: MarketIndex, engine: EngineId, leg: String): String {
        val code = when (engine) {
            EngineId.ENGINE_1_TREND -> "E1"
            EngineId.ENGINE_2_AVWAP_LIQUIDITY -> "E2"
            EngineId.ENGINE_3_V76_SCALPER -> "E3"
        }
        return "VRD-${index.name.take(3)}-$code-$leg-${System.currentTimeMillis().toString().takeLast(8)}".take(40)
    }

    private fun buildDemoChain(index: MarketIndex, spot: Double): List<OptionQuote> {
        val step = if (index == MarketIndex.NIFTY) 50 else 100
        val lotSize = if (index == MarketIndex.NIFTY) 65 else 20
        val atm = (spot / step).toInt() * step
        return (-5..5).flatMap { offset ->
            val strike = atm + offset * step
            val distance = spot - strike
            val timeValue = max(18.0, 110.0 - abs(offset) * 12.0)
            val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
            listOf(
                OptionQuote(
                    strike.toDouble(), "CE", max(0.0, distance) + timeValue,
                    90_000L, 1_500L, ceDelta, 0.002, offset == 0,
                    "DEMO_${index.name}_CE_$strike", bid = max(0.0, distance) + timeValue - 0.5,
                    ask = max(0.0, distance) + timeValue + 0.5, volume = 50_000, lotSize = lotSize,
                ),
                OptionQuote(
                    strike.toDouble(), "PE", max(0.0, -distance) + timeValue,
                    94_000L, 1_200L, ceDelta - 1.0, 0.002, offset == 0,
                    "DEMO_${index.name}_PE_$strike", bid = max(0.0, -distance) + timeValue - 0.5,
                    ask = max(0.0, -distance) + timeValue + 0.5, volume = 50_000, lotSize = lotSize,
                ),
            )
        }
    }

    private fun hasOpenPosition(index: MarketIndex): Boolean = _state.value.market(index).engines.any { it.position != null }

    private fun hasOpenLivePosition(index: MarketIndex? = null): Boolean {
        val markets = if (index == null) _state.value.markets.values else listOf(_state.value.market(index))
        return markets.any { market -> market.engines.any { it.position?.executionMode == ExecutionMode.LIVE } }
    }

    private fun hasAnyPendingBrokerOperation(): Boolean = sessions.values.any { it.pendingEntry.isNotEmpty() || it.pendingExit.isNotEmpty() }

    private fun updateEngine(
        index: MarketIndex,
        engine: EngineId,
        transform: (EngineState) -> EngineState,
    ) {
        updateMarket(index) { market -> market.withEngine(engine, transform(market.engine(engine))) }
    }

    private fun updateMarket(
        index: MarketIndex,
        transform: (FullMarketState) -> FullMarketState,
    ) {
        _state.update { current -> current.withMarket(index, transform(current.market(index))) }
    }

    private fun replaceMarket(index: MarketIndex, market: FullMarketState) {
        _state.update { it.withMarket(index, market) }
    }

    override fun onCleared() {
        // The process scope intentionally survives Activity rotation; disconnect/kill controls
        // remain authoritative so a UI recreation cannot silently strand a broker position.
        MetaBrainRuntime.forceSave()
        super.onCleared()
    }

    companion object {
        private val INDIA_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
        private val MARKET_OPEN: LocalTime = LocalTime.of(9, 15)
        private val MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)
        private val ENTRY_START: LocalTime = LocalTime.of(9, 25)
        private val ENTRY_END: LocalTime = LocalTime.of(15, 10)
        private const val INDIA_VIX_KEY = "NSE_INDEX|India VIX"
        private const val SIGNAL_REFRESH_MS = 250L
        private const val DEFAULT_E1_E2_COOLDOWN_MS = 120_000L
        private const val AUTO_DUPLICATE_SUPPRESSION_MS = 60_000L
        private const val MAX_ONE_MINUTE_BARS = 5_000
        private const val MAX_TRADE_LOG = 500
        private const val QUALITY_SPOT_HISTORY = 1_500
        private const val QUALITY_OPTION_HISTORY = 120
        private const val MAX_TRACKED_OPTIONS = 80
        private const val ENGINE2_SPOT_CANDIDATE_SCORE = 78
        private const val ENGINE2_CONFIRMED_MIN_SCORE = 82
    }
}

private fun <T> Sequence<T>.takeLastFull(count: Int): List<T> {
    val list = toList()
    return if (list.size <= count) list else list.subList(list.size - count, list.size)
}
