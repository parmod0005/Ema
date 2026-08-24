package com.parmod.ema

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.parmod.ema.data.UpstoxIntradayCandleClient
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxTickStream
import com.parmod.ema.engine.AdaptiveExitEngine
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.OptionSelector
import com.parmod.ema.engine.TickNativeDualEngine
import com.parmod.ema.engine.V76ExecutionQualityEngine
import com.parmod.ema.engine.V76ScalperEngine
import com.parmod.ema.model.*
import com.parmod.ema.runtime.ProcessTradingScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class TradingViewModel(application: Application) : AndroidViewModel(application) {
    private val localState = MutableStateFlow(DashboardState())
    private val _state: MutableStateFlow<DashboardState>
        get() = runtimeOwner?.localState ?: localState
    val state: StateFlow<DashboardState>
        get() = _state.asStateFlow()

    private var feedJob: Job? = null
    private var tickStream: UpstoxTickStream? = null
    private var underlyingKey = ""
    private var savedAccessToken = ""
    private var savedExpiry = ""
    private var vixLtp = 0.0

    private val tickCore = TickNativeDualEngine()
    private val v76Core = V76ScalperEngine()
    private val optionSelector = OptionSelector()
    private val adaptiveExit = AdaptiveExitEngine()

    private var engine1LastExit = 0L
    private var engine2LastExit = 0L
    private var lastSignalPublishMillis = 0L

    init {
        MetaBrainRuntime.initialize(application.applicationContext)
        synchronized(TradingViewModel::class.java) {
            if (runtimeOwner == null) runtimeOwner = this
        }
    }

    private fun owner(): TradingViewModel = runtimeOwner ?: this

    private data class WorkingMinute(
        val minute: Long,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var ticks: Long = 1,
    )
    private var v76Working: WorkingMinute? = null
    private val v76Bars = ArrayDeque<V76ScalperEngine.Bar>()
    private var lastV76SignalMillis = 0L
    private var lastV76Evaluation = V76ScalperEngine.Evaluation(
        SignalSnapshot(SignalAction.WAIT, 0, TrendDirection.NEUTRAL, null, null, null, listOf("V7.6 warm-up")),
    )

    private data class V76Session(
        var date: LocalDate = LocalDate.MIN,
        var trades: Int = 0,
        var pnl: Double = 0.0,
        var consecutiveLosses: Int = 0,
        var kill: Boolean = false,
        var lastExitMillis: Long = 0L,
        var lastExitSide: PositionSide? = null,
    )
    private val v76Sessions = mutableMapOf(MarketIndex.NIFTY to V76Session(), MarketIndex.SENSEX to V76Session())

    fun connectLive(accessToken: String, expiryDate: String) {
        val active = owner(); if (active !== this) { active.connectLive(accessToken, expiryDate); return }
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
        resetMarketStructure()
        val selectedIndex = _state.value.index
        _state.value = _state.value.copy(
            connectionMode = ConnectionMode.UPSTOX,
            isConnected = false,
            executionMode = ExecutionMode.PAPER,
            optionChain = emptyList(),
            spotPrice = 0.0,
            message = "Loading ${selectedIndex.name} · 3 independent PAPER engines…",
        )
        feedJob = ProcessTradingScope.scope.launch {
            try {
                val client = UpstoxLiveClient(savedAccessToken)
                val snapshot = withContext(Dispatchers.IO) { client.fetchSnapshot(selectedIndex, savedExpiry) }
                underlyingKey = snapshot.underlyingKey

                val warmResult = withContext(Dispatchers.IO) {
                    runCatching { UpstoxIntradayCandleClient(savedAccessToken).getWarmupOneMinuteCandles(underlyingKey, 10) }
                }
                val warm = warmResult.getOrElse { error ->
                    _state.value = _state.value.copy(
                        message = "DATA INTEGRITY · V7.6 warm-up failed: ${error.message?.take(120) ?: "unknown error"}",
                    )
                    emptyList()
                }
                warmV76(warm)
                publishLiveSnapshot(snapshot)
                evaluateV76()

                val keys = (listOf(snapshot.underlyingKey, INDIA_VIX_KEY) + snapshot.options.mapNotNull { it.instrumentKey.takeIf(String::isNotBlank) }).distinct()
                tickStream = UpstoxTickStream(
                    authorizedUrlProvider = { client.authorizedSocketUrl() },
                    instrumentKeys = keys,
                    listener = object : UpstoxTickStream.Listener {
                        override fun onOpen() {
                            _state.value = _state.value.copy(
                                isConnected = true,
                                message = "${selectedIndex.name} live · META SHADOW learning · 3 PAPER engines",
                            )
                            runParallelAuto()
                        }
                        override fun onTick(tick: UpstoxTickStream.Tick) = applyTick(tick)
                        override fun onError(message: String) { _state.value = _state.value.copy(message = message.take(180)) }
                        override fun onClosed() { _state.value = _state.value.copy(isConnected = false, message = "Tick stream reconnecting…") }
                    },
                ).also { withContext(Dispatchers.IO) { it.connect() } }
            } catch (error: Exception) {
                _state.value = _state.value.copy(isConnected = false, message = error.message?.take(180) ?: "Upstox connection error")
            }
        }
    }

    private fun warmV76(candles: List<UpstoxIntradayCandleClient.Candle>) {
        val currentMinute = System.currentTimeMillis() / 60_000L
        candles.asSequence()
            .filter { it.time.toInstant().toEpochMilli() / 60_000L < currentMinute }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .takeLastCompat(5000)
            .forEach { c ->
                v76Bars.addLast(V76ScalperEngine.Bar(c.time.toInstant().toEpochMilli(), c.open, c.high, c.low, c.close, c.volume))
            }
    }

    fun connectDemo() {
        val active = owner(); if (active !== this) { active.connectDemo(); return }
        disconnectInternal()
        resetMarketStructure()
        _state.value = _state.value.copy(connectionMode = ConnectionMode.DEMO, isConnected = true, executionMode = ExecutionMode.PAPER, message = "Demo feed · META SHADOW learning · 3 PAPER engines")
        feedJob = ProcessTradingScope.scope.launch {
            var n = 0
            while (true) {
                val base = if (_state.value.index == MarketIndex.NIFTY) 24_500.0 else 80_000.0
                val wave = when ((n / 80) % 4) { 0 -> n % 80 * 1.4; 1 -> 112.0 - (n % 80) * 0.7; 2 -> 56.0 - (n % 80) * 1.2; else -> -40.0 + (n % 80) * 0.5 }
                val spot = base + wave + Random.nextDouble(-2.0, 2.0)
                val chain = buildDemoChain(spot)
                val now = System.currentTimeMillis()
                onSpotTick(spot, now)
                _state.value = _state.value.copy(optionChain = chain, lastTickMillis = now, ticksReceived = _state.value.ticksReceived + 1)
                updatePositions(chain)
                managePositions()
                n++
                delay(100)
            }
        }
    }

    fun disconnect() {
        val active = owner(); if (active !== this) { active.disconnect(); return }
        disconnectInternal(); MetaBrainRuntime.forceSave(); _state.value = _state.value.copy(isConnected = false, message = "Disconnected · AI state saved")
    }
    private fun disconnectInternal() { feedJob?.cancel(); feedJob = null; tickStream?.disconnect(); tickStream = null }

    fun selectIndex(index: MarketIndex) {
        val active = owner(); if (active !== this) { active.selectIndex(index); return }
        if (_state.value.index == index) return
        EngineId.entries.forEach { closeEnginePosition(it, "Market changed") }
        resetMarketStructure()
        _state.value = _state.value.copy(index = index, isConnected = false, optionChain = emptyList(), spotPrice = 0.0, message = "Switching to ${index.name}…")
        if (_state.value.connectionMode == ConnectionMode.UPSTOX && savedAccessToken.isNotBlank() && savedExpiry.isNotBlank()) connectSelectedIndex()
        else if (_state.value.connectionMode == ConnectionMode.DEMO) connectDemo()
    }

    fun setTradingMode(mode: TradingMode) {
        val active = owner(); if (active !== this) { active.setTradingMode(mode); return }
        _state.value = _state.value.copy(tradingMode = mode, message = "$mode · selected engines · PAPER")
    }
    fun setAppMode(mode: AppMode) {
        val active = owner(); if (active !== this) { active.setAppMode(mode); return }
        _state.value = _state.value.copy(appMode = mode)
    }
    fun setStartingCapital(value: Double) {
        val active = owner(); if (active !== this) { active.setStartingCapital(value); return }
        if (value > 0) _state.value = _state.value.copy(startingCapital = value)
    }
    fun setLiveTradingEnabled(enabled: Boolean) {
        val active = owner(); if (active !== this) { active.setLiveTradingEnabled(enabled); return }
        _state.value = _state.value.copy(executionMode = ExecutionMode.PAPER, message = "This build remains PAPER only")
    }
    fun toggleEngine(engine: EngineId) {
        val active = owner(); if (active !== this) { active.toggleEngine(engine); return }
        val set = _state.value.enabledEngines.toMutableSet()
        if (!set.add(engine)) set.remove(engine)
        _state.value = _state.value.copy(enabledEngines = set, message = "${set.size} engine(s) selected for AUTO paper trading")
    }
    fun setLots(index: MarketIndex, lots: Int) {
        val active = owner(); if (active !== this) { active.setLots(index, lots); return }
        val n = lots.coerceIn(1, 20)
        _state.value = if (index == MarketIndex.NIFTY) _state.value.copy(niftyLots = n) else _state.value.copy(sensexLots = n)
    }
    fun setDailyTradeLimit(limit: Int) {
        val active = owner(); if (active !== this) { active.setDailyTradeLimit(limit); return }
        val n = limit.coerceIn(1, 50)
        _state.value = _state.value.copy(dailyTradeLimit = n, message = "Daily trade limit set to $n per index · all engines combined")
    }

    fun manualBuy(engine: EngineId, side: PositionSide) {
        val active = owner(); if (active !== this) { active.manualBuy(engine, side); return }
        openEnginePosition(engine, side, "Manual paper entry")
    }
    fun exitEngine(engine: EngineId) {
        val active = owner(); if (active !== this) { active.exitEngine(engine); return }
        closeEnginePosition(engine, "Manual exit")
    }

    private fun publishLiveSnapshot(snapshot: UpstoxLiveClient.Snapshot) {
        val now = System.currentTimeMillis(); onSpotTick(snapshot.spot, now)
        _state.value = _state.value.copy(spotPrice = snapshot.spot, optionChain = snapshot.options, lastTickMillis = now)
        updatePositions(snapshot.options)
    }

    private fun applyTick(tick: UpstoxTickStream.Tick) {
        val current = _state.value
        var chain = current.optionChain
        when {
            tick.instrumentKey == INDIA_VIX_KEY && tick.ltp != null -> vixLtp = tick.ltp
            tick.instrumentKey == underlyingKey && tick.ltp != null -> onSpotTick(tick.ltp, tick.feedTimestamp)
            else -> chain = chain.map { q ->
                if (q.instrumentKey != tick.instrumentKey) q else q.copy(
                    ltp = tick.ltp ?: q.ltp,
                    openInterest = tick.oi ?: q.openInterest,
                    delta = tick.delta ?: q.delta,
                    gamma = tick.gamma ?: q.gamma,
                    lastTickMillis = tick.feedTimestamp,
                    bid = tick.bid ?: q.bid,
                    ask = tick.ask ?: q.ask,
                    volume = tick.volume ?: q.volume,
                )
            }
        }
        val depthLevels = if (tick.depth.isNotEmpty()) tick.depth.size else current.marketDepthLevels
        val depthMode = if (tick.requestMode.isNotBlank()) tick.requestMode.uppercase() else current.marketDepthMode
        _state.value = _state.value.copy(
            isConnected = true, optionChain = chain, lastTickMillis = tick.feedTimestamp,
            ticksReceived = current.ticksReceived + 1,
            marketDepthMode = depthMode,
            marketDepthLevels = depthLevels,
            message = "${current.index.name} · ${current.ticksReceived + 1} ticks · $depthMode D$depthLevels · META ${if (MetaBrainRuntime.report().gateEnabled) "GATE" else "SHADOW"}",
        )
        updatePositions(chain); managePositions()
    }

    private fun onSpotTick(price: Double, timestamp: Long) {
        if (price <= 0.0) return
        MetaBrainRuntime.observeSpot(price, timestamp)
        tickCore.ingest(price, timestamp)
        ingestV76Minute(price, timestamp)
        _state.value = _state.value.copy(spotPrice = price, lastTickMillis = timestamp)
        if (timestamp - lastSignalPublishMillis >= 250L) {
            lastSignalPublishMillis = timestamp
            val result = tickCore.evaluate()
            val e1Meta = decorateWithMeta(EngineId.ENGINE_1_TREND, result.engine1, timestamp)
            val e2Confirmed = confirmEngine2(result.engine2)
            val e2Meta = decorateWithMeta(EngineId.ENGINE_2_AVWAP_LIQUIDITY, e2Confirmed, timestamp)
            _state.value = _state.value.copy(
                engine1 = _state.value.engine1.copy(signal = e1Meta, message = e1Meta.setup),
                engine2 = _state.value.engine2.copy(signal = e2Meta, message = e2Meta.setup),
            )
            runParallelAuto()
        }
    }

    private fun decorateWithMeta(engine: EngineId, raw: SignalSnapshot, timestamp: Long): SignalSnapshot {
        val side = when {
            raw.action == SignalAction.BUY_CE -> PositionSide.CE
            raw.action == SignalAction.BUY_PE -> PositionSide.PE
            raw.trend == TrendDirection.BULLISH -> PositionSide.CE
            raw.trend == TrendDirection.BEARISH -> PositionSide.PE
            else -> return raw
        }
        val spot = _state.value.spotPrice
        if (spot <= 0.0) return raw
        val quality = if (_state.value.connectionMode == ConnectionMode.DEMO) null else V76ExecutionQualityEngine.evaluate(side, _state.value.optionChain, spot)
        return MetaBrainRuntime.decorate(
            engine = engine,
            raw = raw,
            spot = spot,
            timestamp = timestamp,
            directionScore = quality?.directionScore?.toDouble() ?: raw.confidence * 0.60,
            entryQualityScore = quality?.entryQualityScore?.toDouble() ?: raw.confidence * 0.40,
            orderFlow = quality?.orderFlowProxy ?: 0.0,
            relativeActivity = quality?.relativeActivity ?: 1.0,
            oiImpulse = quality?.optionOiImpulse ?: 0.0,
            optionFlow = quality?.optionFlowProxy ?: 0.0,
            acceleration = quality?.acceleration ?: 0.0,
            extensionAtr = quality?.extensionAtr ?: 0.0,
            depthImbalance = quality?.depthImbalance ?: 0.0,
            micropricePressure = quality?.micropricePressure ?: 0.0,
            totalBookPressure = quality?.totalBookPressure ?: 0.0,
            wallPressure = quality?.wallPressure ?: 0.0,
            depthLevels = quality?.depthLevels?.toDouble() ?: 0.0,
        )
    }

    private fun confirmEngine2(raw: SignalSnapshot): SignalSnapshot {
        val side = when {
            raw.action == SignalAction.BUY_CE -> PositionSide.CE
            raw.action == SignalAction.BUY_PE -> PositionSide.PE
            raw.confidence >= ENGINE2_SPOT_CANDIDATE_SCORE && raw.trend == TrendDirection.BULLISH -> PositionSide.CE
            raw.confidence >= ENGINE2_SPOT_CANDIDATE_SCORE && raw.trend == TrendDirection.BEARISH -> PositionSide.PE
            else -> return raw
        }
        if (_state.value.connectionMode == ConnectionMode.DEMO) return raw

        val quality = V76ExecutionQualityEngine.evaluate(side, _state.value.optionChain, _state.value.spotPrice)
        val trend = if (side == PositionSide.CE) TrendDirection.BULLISH else TrendDirection.BEARISH
        val combinedScore = ((raw.confidence * 45 + quality.score * 55) / 100).coerceIn(0, 100)
        val reasons = (raw.reasons + quality.reasons).distinct()
        val spot = _state.value.spotPrice
        val risk = max(spot * 0.00070, 1.0)

        if (quality.canEnter && raw.confidence >= ENGINE2_SPOT_CANDIDATE_SCORE) {
            val action = if (side == PositionSide.CE) SignalAction.BUY_CE else SignalAction.BUY_PE
            return SignalSnapshot(
                action = action,
                confidence = max(ENGINE2_CONFIRMED_MIN_SCORE, combinedScore),
                trend = trend,
                entry = spot,
                stopLoss = if (side == PositionSide.CE) spot - risk else spot + risk,
                target = if (side == PositionSide.CE) spot + risk * 2.0 else spot - risk * 2.0,
                reasons = reasons,
                setup = "E2 EARLY CONFIRMED · AVWAP + OPTION FLOW + D30",
            )
        }

        val setup = when (quality.decision) {
            V76ExecutionQualityEngine.Decision.WAIT_PULLBACK -> "E2 EXTENDED · WAIT FOR PULLBACK"
            V76ExecutionQualityEngine.Decision.EXHAUSTION_RISK -> "E2 REVERSAL / ABSORPTION RISK"
            else -> "E2 WAIT · OPTION / D30 CONFIRMATION"
        }
        return SignalSnapshot(
            action = SignalAction.WAIT,
            confidence = combinedScore,
            trend = trend,
            entry = null,
            stopLoss = null,
            target = null,
            reasons = reasons,
            setup = setup,
        )
    }

    private fun ingestV76Minute(price: Double, timestamp: Long) {
        val minute = timestamp / 60_000L
        val w = v76Working
        if (w == null) v76Working = WorkingMinute(minute, price, price, price, price)
        else if (w.minute == minute) {
            w.high = max(w.high, price); w.low = min(w.low, price); w.close = price; w.ticks++
        } else {
            v76Bars.addLast(V76ScalperEngine.Bar(w.minute * 60_000L, w.open, w.high, w.low, w.close, w.ticks))
            while (v76Bars.size > 5000) v76Bars.removeFirst()
            v76Working = WorkingMinute(minute, price, price, price, price)
            evaluateV76()
        }
    }

    private fun evaluateV76() {
        if (v76Bars.isEmpty()) return
        val eval = v76Core.evaluate(v76Bars.toList(), _state.value.optionChain, _state.value.spotPrice, vixLtp)
        val decorated = decorateWithMeta(EngineId.ENGINE_3_V76_SCALPER, eval.signal, System.currentTimeMillis())
        lastV76Evaluation = eval.copy(signal = decorated)
        _state.value = _state.value.copy(engine3 = _state.value.engine3.copy(signal = decorated, message = decorated.setup))
        runParallelAuto()
    }

    private fun runParallelAuto() {
        val snapshot = _state.value
        if (!snapshot.isConnected || snapshot.tradingMode != TradingMode.AUTO || snapshot.appMode != AppMode.LIVE_MARKET || snapshot.riskLocked) return
        val enabled = snapshot.enabledEngines
        val now = System.currentTimeMillis()

        val e1Signal = snapshot.engine1.signal
        if (EngineId.ENGINE_1_TREND in enabled && snapshot.engine1.position == null && now - engine1LastExit >= 120_000L) {
            when (e1Signal.action) {
                SignalAction.BUY_CE -> openEnginePosition(EngineId.ENGINE_1_TREND, PositionSide.CE, e1Signal.setup, expectedSignal = e1Signal)
                SignalAction.BUY_PE -> openEnginePosition(EngineId.ENGINE_1_TREND, PositionSide.PE, e1Signal.setup, expectedSignal = e1Signal)
                else -> Unit
            }
        }

        val s2 = _state.value
        val e2Signal = s2.engine2.signal
        if (EngineId.ENGINE_2_AVWAP_LIQUIDITY in enabled && s2.engine2.position == null && now - engine2LastExit >= 120_000L) {
            when (e2Signal.action) {
                SignalAction.BUY_CE -> openEnginePosition(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.CE, e2Signal.setup, expectedSignal = e2Signal)
                SignalAction.BUY_PE -> openEnginePosition(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.PE, e2Signal.setup, expectedSignal = e2Signal)
                else -> Unit
            }
        }

        val s3 = _state.value
        if (EngineId.ENGINE_3_V76_SCALPER in enabled && s3.engine3.position == null) tryOpenV76()
    }

    private fun tryOpenV76() {
        val eval = lastV76Evaluation
        val side = when (eval.signal.action) { SignalAction.BUY_CE -> PositionSide.CE; SignalAction.BUY_PE -> PositionSide.PE; else -> return }
        if (eval.signalTimeMillis == 0L || eval.signalTimeMillis == lastV76SignalMillis) return
        val session = sessionV76()
        val s = _state.value
        if (session.kill || todayTradeCount(s.index) >= s.dailyTradeLimit) return
        if (session.lastExitMillis > 0) {
            val elapsed = System.currentTimeMillis() - session.lastExitMillis
            val same = session.lastExitSide == side
            if (same && elapsed < V76ScalperEngine.SAME_DIRECTION_COOLDOWN_MINUTES * 60_000L) return
            if (!same && eval.score < V76ScalperEngine.REVERSAL_MIN_SCORE) return
        }
        lastV76SignalMillis = eval.signalTimeMillis
        openEnginePosition(EngineId.ENGINE_3_V76_SCALPER, side, eval.strategy ?: "PULLBACK")
    }

    private fun openEnginePosition(
        engine: EngineId,
        side: PositionSide,
        reason: String,
        expectedSignal: SignalSnapshot? = null,
    ) {
        val s = _state.value
        if (!s.isConnected || s.riskLocked || engineState(engine).position != null) return
        val usedToday = todayTradeCount(s.index)
        if (usedToday >= s.dailyTradeLimit) {
            setEngineState(engine, engineState(engine).copy(message = "DAILY TRADE LIMIT · $usedToday/${s.dailyTradeLimit} used for ${s.index.name}"))
            return
        }

        if (expectedSignal != null) {
            val liveSignal = engineState(engine).signal
            val sideMatches = (side == PositionSide.CE && expectedSignal.action == SignalAction.BUY_CE) ||
                (side == PositionSide.PE && expectedSignal.action == SignalAction.BUY_PE)
            if (!sideMatches || liveSignal != expectedSignal || liveSignal.action == SignalAction.WAIT) {
                setEngineState(engine, engineState(engine).copy(message = "ENTRY CANCELLED · signal changed before fill"))
                return
            }
        }

        val selection = if (engine == EngineId.ENGINE_3_V76_SCALPER) selectV76Option(s.optionChain, side, s.spotPrice) else optionSelector.select(s.optionChain, side.name)?.quote
        val q = selection ?: run { setEngineState(engine, engineState(engine).copy(message = "No liquid ${side.name} contract")); return }
        val lotSize = if (s.index == MarketIndex.NIFTY) 65 else 20
        val lots = s.selectedLots
        val qty = lotSize * lots
        val entry = if (engine == EngineId.ENGINE_3_V76_SCALPER) paperBuy(q) else if (q.ask > 0.0) q.ask else q.ltp
        if (entry <= 0.0) return
        if (engine == EngineId.ENGINE_3_V76_SCALPER && entry !in V76ScalperEngine.MIN_OPTION_PREMIUM..V76ScalperEngine.MAX_OPTION_PREMIUM) return

        val strategy = when {
            engine != EngineId.ENGINE_3_V76_SCALPER -> engine.name
            reason.contains("BREAKOUT", ignoreCase = true) -> "BREAKOUT"
            else -> "PULLBACK"
        }
        val now = System.currentTimeMillis()
        val plan = adaptiveExit.open(engine, side, entry, now, strategy)
        val invalidation = if (engine == EngineId.ENGINE_3_V76_SCALPER) lastV76Evaluation.indexInvalidation else expectedSignal?.stopLoss ?: 0.0
        val p = PaperPosition(
            side = side,
            strike = q.strike,
            quantity = qty,
            entryPrice = entry,
            currentPrice = q.ltp,
            highestPrice = entry,
            stopPrice = plan.stopPrice,
            targetPrice = plan.target1Price,
            openedAtMillis = now,
            strategy = strategy,
            lotSize = lotSize,
            lots = lots,
            initialQuantity = qty,
            indexInvalidation = invalidation,
            maxHoldMinutes = plan.maxHoldMinutes,
        )
        if (engine == EngineId.ENGINE_3_V76_SCALPER) sessionV76().trades++
        val label = if (engine == EngineId.ENGINE_3_V76_SCALPER) "V7.6 PAPER" else "PAPER"
        setEngineState(engine, engineState(engine).copy(position = p, message = "$label ${side.name} ${q.strike.toInt()} · ADAPTIVE EXIT · $strategy"))
        recordOpenTrade(engine, p, reason)
    }

    private fun todayTradeCount(index: MarketIndex): Int {
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        return _state.value.tradeLog.count { entry ->
            entry.index == index && Instant.ofEpochMilli(entry.entryTimeMillis).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate() == today
        }
    }

    private fun recordOpenTrade(engine: EngineId, p: PaperPosition, setup: String) {
        val s = _state.value
        val e = engineState(engine)
        val entry = TradeLogEntry(
            id = p.openedAtMillis,
            engineId = engine,
            engineName = e.name,
            index = s.index,
            side = p.side,
            strike = p.strike,
            quantity = p.quantity,
            lots = p.lots,
            entryPrice = p.entryPrice,
            entrySpot = s.spotPrice,
            entryTimeMillis = p.openedAtMillis,
            setup = setup,
        )
        _state.value = s.copy(tradeLog = (s.tradeLog + entry).takeLast(MAX_TRADE_LOG))
    }

    private fun recordCloseTrade(engine: EngineId, p: PaperPosition, exitPrice: Double, pnl: Double, reason: String) {
        val s = _state.value
        val idx = s.tradeLog.indexOfLast { it.engineId == engine && it.status == TradeStatus.OPEN && it.entryTimeMillis == p.openedAtMillis }
        if (idx < 0) return
        val updated = s.tradeLog.toMutableList()
        updated[idx] = updated[idx].copy(
            status = TradeStatus.CLOSED,
            exitPrice = exitPrice,
            exitSpot = s.spotPrice,
            exitTimeMillis = System.currentTimeMillis(),
            pnl = pnl,
            exitReason = reason,
        )
        _state.value = s.copy(tradeLog = updated.takeLast(MAX_TRADE_LOG))
    }

    private fun selectV76Option(chain: List<OptionQuote>, side: PositionSide, spot: Double): OptionQuote? {
        val step = if (_state.value.index == MarketIndex.NIFTY) 50.0 else 100.0
        return chain.asSequence().filter { it.type == side.name && abs(it.strike - spot) <= step * 2 }
            .filter { paperBuy(it) in V76ScalperEngine.MIN_OPTION_PREMIUM..V76ScalperEngine.MAX_OPTION_PREMIUM }
            .filter { q -> q.bid <= 0 || q.ask <= 0 || ((q.ask - q.bid) / max((q.ask + q.bid) / 2.0, 0.01) * 100.0) <= 4.5 }
            .minByOrNull { q ->
                val targetDelta = if (side == PositionSide.CE) V76ScalperEngine.TARGET_ABS_DELTA else -V76ScalperEngine.TARGET_ABS_DELTA
                val deltaPenalty = if (q.delta != 0.0) abs(q.delta - targetDelta) else 0.20
                val strikePenalty = abs(q.strike - spot) / step
                val liquidityBonus = min(kotlin.math.log10(max(q.volume, 1L).toDouble()) / 10.0, 0.6)
                val spread = if (q.ask > 0 && q.bid > 0) (q.ask - q.bid) / max((q.ask + q.bid) / 2.0, 0.01) * 100.0 else 0.0
                deltaPenalty * 4.0 + strikePenalty * 0.35 + spread * 0.15 - liquidityBonus
            }
    }

    private fun paperBuy(q: OptionQuote) = if (q.ask > 0) q.ask else q.ltp * (1 + V76ScalperEngine.PAPER_SLIPPAGE_BPS / 10_000.0)
    private fun paperSell(q: OptionQuote) = if (q.bid > 0) q.bid else q.ltp * (1 - V76ScalperEngine.PAPER_SLIPPAGE_BPS / 10_000.0)

    private fun updatePositions(chain: List<OptionQuote>) {
        var e1 = _state.value.engine1; var e2 = _state.value.engine2; var e3 = _state.value.engine3
        fun update(e: EngineState): EngineState { val p = e.position ?: return e; val q = chain.firstOrNull { it.strike == p.strike && it.type == p.side.name } ?: return e; return e.copy(position = p.copy(currentPrice = q.ltp)) }
        e1 = update(e1); e2 = update(e2); e3 = update(e3)
        _state.value = _state.value.copy(engine1 = e1, engine2 = e2, engine3 = e3)
    }

    private fun managePositions() {
        manageAdaptive(EngineId.ENGINE_1_TREND)
        manageAdaptive(EngineId.ENGINE_2_AVWAP_LIQUIDITY)
        manageAdaptive(EngineId.ENGINE_3_V76_SCALPER)
        updateRiskLock()
    }

    private fun manageAdaptive(engine: EngineId) {
        var state = engineState(engine)
        var p = state.position ?: return
        val q = _state.value.optionChain.firstOrNull { it.strike == p.strike && it.type == p.side.name } ?: return
        val price = q.ltp
        if (price <= 0.0) return
        val now = System.currentTimeMillis()
        val opposite = (p.side == PositionSide.CE && state.signal.action == SignalAction.BUY_PE) ||
            (p.side == PositionSide.PE && state.signal.action == SignalAction.BUY_CE)
        val invalid = (p.side == PositionSide.CE && p.indexInvalidation > 0.0 && _state.value.spotPrice < p.indexInvalidation) ||
            (p.side == PositionSide.PE && p.indexInvalidation > 0.0 && _state.value.spotPrice > p.indexInvalidation)
        val quality = if (_state.value.connectionMode == ConnectionMode.DEMO) null else V76ExecutionQualityEngine.evaluate(p.side, _state.value.optionChain, _state.value.spotPrice)
        val update = adaptiveExit.update(
            engine = engine,
            side = p.side,
            entryPrice = p.entryPrice,
            currentPrice = price,
            timestamp = now,
            currentStopPrice = p.stopPrice,
            previousHighestPrice = p.highestPrice,
            target1Hit = p.target1Hit,
            quantity = p.quantity,
            strategy = p.strategy,
            oppositeSignal = opposite,
            indexInvalidated = invalid,
            quality = quality,
        )

        if (update.partialTrigger && !p.target1Hit) {
            var realized = p.realizedPartialPnl
            var qty = p.quantity
            var lots = p.lots
            var bookedQty = p.target1ExitQuantity
            if (lots >= 2) {
                var partialLots = max(1, (lots * AdaptiveExitEngine.TARGET1_PARTIAL_FRACTION).toInt())
                partialLots = min(partialLots, lots - 1)
                val partialQty = partialLots * p.lotSize
                val fill = paperSell(q)
                realized += (fill - p.entryPrice) * partialQty - AdaptiveExitEngine.PAPER_EXTRA_EXIT_ORDER_COST_INR
                qty -= partialQty
                lots -= partialLots
                bookedQty += partialQty
            }
            p = p.copy(
                quantity = qty,
                lots = lots,
                target1Hit = true,
                target1ExitQuantity = bookedQty,
                realizedPartialPnl = realized,
                maxHoldMinutes = update.runnerMaxHoldMinutes,
            )
        }

        p = p.copy(
            currentPrice = price,
            highestPrice = update.highestPrice,
            stopPrice = update.stopPrice,
            targetPrice = update.target1Price,
            breakevenActive = update.breakevenActive,
            trailingActive = update.trailingActive,
            maxHoldMinutes = if (p.target1Hit) update.runnerMaxHoldMinutes else update.maxHoldMinutes,
        )
        val adaptiveMessage = "ADAPTIVE EXIT · ${update.diagnostic}${if (p.target1Hit) " · RUNNER" else ""}"
        setEngineState(engine, state.copy(position = p, message = adaptiveMessage))
        update.exitReason?.let { closeEnginePosition(engine, it.name.replace('_', ' ')) }
    }

    private fun closeEnginePosition(engine: EngineId, reason: String) {
        val current = engineState(engine); val position = current.position ?: return
        val q = _state.value.optionChain.firstOrNull { it.strike == position.strike && it.type == position.side.name }
        val exitPrice = q?.let(::paperSell) ?: position.currentPrice
        val pnl = position.realizedPartialPnl + (exitPrice - position.entryPrice) * position.quantity - AdaptiveExitEngine.PAPER_ROUND_TRIP_COST_INR
        adaptiveExit.close(engine)
        if (engine == EngineId.ENGINE_3_V76_SCALPER) {
            val session = sessionV76(); session.pnl += pnl; session.consecutiveLosses = if (pnl < 0) session.consecutiveLosses + 1 else 0
            session.kill = session.pnl <= V76ScalperEngine.MAX_DAILY_LOSS_INR_PER_INDEX || session.consecutiveLosses >= V76ScalperEngine.MAX_CONSECUTIVE_LOSSES
            session.lastExitMillis = System.currentTimeMillis(); session.lastExitSide = position.side
        }
        val old = current.performance; val realized = old.realizedPnl + pnl; val peak = max(old.peakEquity, realized); val drawdown = (peak - realized).coerceAtLeast(0.0)
        val perf = old.copy(trades = old.trades + 1, wins = old.wins + if (pnl > 0) 1 else 0, losses = old.losses + if (pnl < 0) 1 else 0,
            realizedPnl = realized, grossProfit = old.grossProfit + pnl.coerceAtLeast(0.0), grossLoss = old.grossLoss + (-pnl).coerceAtLeast(0.0), peakEquity = peak, maxDrawdown = max(old.maxDrawdown, drawdown))
        setEngineState(engine, current.copy(position = null, performance = perf, message = "$reason · P&L ₹${"%.2f".format(pnl)}"))
        recordCloseTrade(engine, position, exitPrice, pnl, reason)
        when (engine) {
            EngineId.ENGINE_1_TREND -> engine1LastExit = System.currentTimeMillis()
            EngineId.ENGINE_2_AVWAP_LIQUIDITY -> engine2LastExit = System.currentTimeMillis()
            else -> Unit
        }
        updateRiskLock()
    }

    private fun sessionV76(): V76Session {
        val s = v76Sessions.getValue(_state.value.index); val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        if (s.date != today) { s.date = today; s.trades = 0; s.pnl = 0.0; s.consecutiveLosses = 0; s.kill = false; s.lastExitMillis = 0L; s.lastExitSide = null }
        return s
    }

    private fun updateRiskLock() {
        val s = _state.value; val combined = s.combinedRealizedPnl; val locked = combined <= -s.startingCapital * 0.02
        _state.value = s.copy(riskLocked = locked, riskReason = if (locked) "Combined session loss reached 2%" else "Risk gates clear")
    }

    private fun engineState(engine: EngineId) = when (engine) { EngineId.ENGINE_1_TREND -> _state.value.engine1; EngineId.ENGINE_2_AVWAP_LIQUIDITY -> _state.value.engine2; EngineId.ENGINE_3_V76_SCALPER -> _state.value.engine3 }
    private fun setEngineState(engine: EngineId, value: EngineState) { _state.value = when (engine) { EngineId.ENGINE_1_TREND -> _state.value.copy(engine1 = value); EngineId.ENGINE_2_AVWAP_LIQUIDITY -> _state.value.copy(engine2 = value); EngineId.ENGINE_3_V76_SCALPER -> _state.value.copy(engine3 = value) } }

    private fun resetMarketStructure() { tickCore.reset(); V76ExecutionQualityEngine.reset(); adaptiveExit.reset(); lastSignalPublishMillis = 0L; v76Working = null; v76Bars.clear(); lastV76SignalMillis = 0L; vixLtp = 0.0 }

    private fun buildDemoChain(spot: Double): List<OptionQuote> {
        val step = if (_state.value.index == MarketIndex.NIFTY) 50 else 100; val atm = (spot / step).toInt() * step
        return (-5..5).flatMap { offset ->
            val strike = atm + offset * step; val distance = spot - strike; val timeValue = max(18.0, 110.0 - abs(offset) * 12.0); val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
            listOf(
                OptionQuote(strike.toDouble(), "CE", max(0.0, distance) + timeValue, 90_000L, 1_500L, ceDelta, 0.002, offset == 0, "CE$strike", bid = max(0.0, distance) + timeValue - 0.5, ask = max(0.0, distance) + timeValue + 0.5, volume = 50_000),
                OptionQuote(strike.toDouble(), "PE", max(0.0, -distance) + timeValue, 94_000L, 1_200L, ceDelta - 1.0, 0.002, offset == 0, "PE$strike", bid = max(0.0, -distance) + timeValue - 0.5, ask = max(0.0, -distance) + timeValue + 0.5, volume = 50_000),
            )
        }
    }

    companion object {
        @Volatile private var runtimeOwner: TradingViewModel? = null
        private const val INDIA_VIX_KEY = "NSE_INDEX|India VIX"
        private const val MAX_TRADE_LOG = 200
        private const val ENGINE2_SPOT_CANDIDATE_SCORE = 78
        private const val ENGINE2_CONFIRMED_MIN_SCORE = 82
    }
}

private fun <T> Sequence<T>.takeLastCompat(count: Int): List<T> {
    val list = toList(); return if (list.size <= count) list else list.subList(list.size - count, list.size)
}
