package com.parmod.ema

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.data.UpstoxIntradayCandleClient
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxTickStream
import com.parmod.ema.engine.ExecutionEngineV2
import com.parmod.ema.engine.OptionSelector
import com.parmod.ema.engine.TickNativeDualEngine
import com.parmod.ema.engine.V76ScalperEngine
import com.parmod.ema.model.*
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
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var feedJob: Job? = null
    private var tickStream: UpstoxTickStream? = null
    private var underlyingKey = ""
    private var savedAccessToken = ""
    private var savedExpiry = ""
    private var vixLtp = 0.0

    private val tickCore = TickNativeDualEngine()
    private val v76Core = V76ScalperEngine()
    private val optionSelector = OptionSelector()
    private val executionEngine = ExecutionEngineV2()

    private var engine1Execution: ExecutionEngineV2.State? = null
    private var engine2Execution: ExecutionEngineV2.State? = null
    private var engine1LastExit = 0L
    private var engine2LastExit = 0L
    private var lastSignalPublishMillis = 0L

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
        feedJob = viewModelScope.launch {
            try {
                val client = UpstoxLiveClient(savedAccessToken)
                val snapshot = withContext(Dispatchers.IO) { client.fetchSnapshot(selectedIndex, savedExpiry) }
                underlyingKey = snapshot.underlyingKey

                val warm = withContext(Dispatchers.IO) {
                    runCatching { UpstoxIntradayCandleClient(savedAccessToken).getWarmupOneMinuteCandles(underlyingKey, 10) }
                        .getOrDefault(emptyList())
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
                                message = "${selectedIndex.name} live · 2 tick-native + V7.6 MTF · 3 PAPER engines",
                            )
                            runParallelAuto()
                        }
                        override fun onTick(tick: UpstoxTickStream.Tick) = applyTick(tick)
                        override fun onError(message: String) { _state.value = _state.value.copy(message = message.take(180)) }
                        override fun onClosed() { _state.value = _state.value.copy(isConnected = false, message = "Tick stream closed") }
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
        disconnectInternal()
        resetMarketStructure()
        _state.value = _state.value.copy(connectionMode = ConnectionMode.DEMO, isConnected = true, executionMode = ExecutionMode.PAPER, message = "Demo feed · 3 PAPER engines")
        feedJob = viewModelScope.launch {
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

    fun disconnect() { disconnectInternal(); _state.value = _state.value.copy(isConnected = false, message = "Disconnected") }
    private fun disconnectInternal() { feedJob?.cancel(); feedJob = null; tickStream?.disconnect(); tickStream = null }

    fun selectIndex(index: MarketIndex) {
        if (_state.value.index == index) return
        EngineId.entries.forEach { closeEnginePosition(it, "Market changed") }
        resetMarketStructure()
        _state.value = _state.value.copy(index = index, isConnected = false, optionChain = emptyList(), spotPrice = 0.0, message = "Switching to ${index.name}…")
        if (_state.value.connectionMode == ConnectionMode.UPSTOX && savedAccessToken.isNotBlank() && savedExpiry.isNotBlank()) connectSelectedIndex()
        else if (_state.value.connectionMode == ConnectionMode.DEMO) connectDemo()
    }

    fun setTradingMode(mode: TradingMode) { _state.value = _state.value.copy(tradingMode = mode, message = "$mode · selected engines · PAPER") }
    fun setAppMode(mode: AppMode) { _state.value = _state.value.copy(appMode = mode) }
    fun setStartingCapital(value: Double) { if (value > 0) _state.value = _state.value.copy(startingCapital = value) }
    fun setLiveTradingEnabled(enabled: Boolean) { _state.value = _state.value.copy(executionMode = ExecutionMode.PAPER, message = "This build remains PAPER only") }
    fun toggleEngine(engine: EngineId) {
        val set = _state.value.enabledEngines.toMutableSet()
        if (!set.add(engine)) set.remove(engine)
        _state.value = _state.value.copy(enabledEngines = set, message = "${set.size} engine(s) selected for AUTO paper trading")
    }
    fun setLots(index: MarketIndex, lots: Int) {
        val n = lots.coerceIn(1, 20)
        _state.value = if (index == MarketIndex.NIFTY) _state.value.copy(niftyLots = n) else _state.value.copy(sensexLots = n)
    }

    fun manualBuy(engine: EngineId, side: PositionSide) = openEnginePosition(engine, side, "Manual paper entry")
    fun exitEngine(engine: EngineId) = closeEnginePosition(engine, "Manual exit")

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
        _state.value = _state.value.copy(
            isConnected = true, optionChain = chain, lastTickMillis = tick.feedTimestamp,
            ticksReceived = current.ticksReceived + 1,
            message = "${current.index.name} · ${current.ticksReceived + 1} ticks · 3 PAPER engines",
        )
        updatePositions(chain); managePositions()
    }

    private fun onSpotTick(price: Double, timestamp: Long) {
        if (price <= 0.0) return
        tickCore.ingest(price, timestamp)
        ingestV76Minute(price, timestamp)
        _state.value = _state.value.copy(spotPrice = price, lastTickMillis = timestamp)
        if (timestamp - lastSignalPublishMillis >= 250L) {
            lastSignalPublishMillis = timestamp
            val result = tickCore.evaluate()
            _state.value = _state.value.copy(
                engine1 = _state.value.engine1.copy(signal = result.engine1, message = result.engine1.setup),
                engine2 = _state.value.engine2.copy(signal = result.engine2, message = result.engine2.setup),
            )
            runParallelAuto()
        }
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
        lastV76Evaluation = eval
        _state.value = _state.value.copy(engine3 = _state.value.engine3.copy(signal = eval.signal, message = eval.signal.setup))
        runParallelAuto()
    }

    private fun runParallelAuto() {
        val s = _state.value
        if (!s.isConnected || s.tradingMode != TradingMode.AUTO || s.appMode != AppMode.LIVE_MARKET || s.riskLocked) return
        val enabled = s.enabledEngines
        val now = System.currentTimeMillis()
        if (EngineId.ENGINE_1_TREND in enabled && s.engine1.position == null && now - engine1LastExit >= 120_000L) {
            when (s.engine1.signal.action) { SignalAction.BUY_CE -> openEnginePosition(EngineId.ENGINE_1_TREND, PositionSide.CE, s.engine1.signal.setup); SignalAction.BUY_PE -> openEnginePosition(EngineId.ENGINE_1_TREND, PositionSide.PE, s.engine1.signal.setup); else -> Unit }
        }
        val s2 = _state.value
        if (EngineId.ENGINE_2_AVWAP_LIQUIDITY in enabled && s2.engine2.position == null && now - engine2LastExit >= 120_000L && s2.engine2.signal.confidence >= 90) {
            when (s2.engine2.signal.action) { SignalAction.BUY_CE -> openEnginePosition(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.CE, s2.engine2.signal.setup); SignalAction.BUY_PE -> openEnginePosition(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.PE, s2.engine2.signal.setup); else -> Unit }
        }
        val s3 = _state.value
        if (EngineId.ENGINE_3_V76_SCALPER in enabled && s3.engine3.position == null) tryOpenV76()
    }

    private fun tryOpenV76() {
        val eval = lastV76Evaluation
        val side = when (eval.signal.action) { SignalAction.BUY_CE -> PositionSide.CE; SignalAction.BUY_PE -> PositionSide.PE; else -> return }
        if (eval.signalTimeMillis == 0L || eval.signalTimeMillis == lastV76SignalMillis) return
        val session = sessionV76()
        if (session.kill || session.trades >= V76ScalperEngine.MAX_TRADES_PER_DAY_PER_INDEX) return
        if (session.lastExitMillis > 0) {
            val elapsed = System.currentTimeMillis() - session.lastExitMillis
            val same = session.lastExitSide == side
            if (same && elapsed < V76ScalperEngine.SAME_DIRECTION_COOLDOWN_MINUTES * 60_000L) return
            if (!same && eval.score < V76ScalperEngine.REVERSAL_MIN_SCORE) return
        }
        lastV76SignalMillis = eval.signalTimeMillis
        openEnginePosition(EngineId.ENGINE_3_V76_SCALPER, side, eval.strategy ?: "PULLBACK")
    }

    private fun openEnginePosition(engine: EngineId, side: PositionSide, reason: String) {
        val s = _state.value
        if (!s.isConnected || s.riskLocked || engineState(engine).position != null) return
        val selection = if (engine == EngineId.ENGINE_3_V76_SCALPER) selectV76Option(s.optionChain, side, s.spotPrice) else optionSelector.select(s.optionChain, side.name)?.quote
        val q = selection ?: run { setEngineState(engine, engineState(engine).copy(message = "No liquid ${side.name} contract")); return }
        val lotSize = if (s.index == MarketIndex.NIFTY) 65 else 20
        val lots = s.selectedLots
        val qty = lotSize * lots

        if (engine == EngineId.ENGINE_3_V76_SCALPER) {
            val entry = paperBuy(q)
            if (entry !in V76ScalperEngine.MIN_OPTION_PREMIUM..V76ScalperEngine.MAX_OPTION_PREMIUM) return
            val strategy = if (reason.contains("BREAKOUT")) "BREAKOUT" else "PULLBACK"
            val stopPct = if (strategy == "BREAKOUT") V76ScalperEngine.FAST_STOP_PERCENT else V76ScalperEngine.PULLBACK_STOP_PERCENT
            val targetPct = if (strategy == "BREAKOUT") V76ScalperEngine.FAST_TARGET_PERCENT else V76ScalperEngine.PULLBACK_TARGET_PERCENT
            val maxHold = if (strategy == "BREAKOUT") V76ScalperEngine.FAST_MAX_HOLD_MINUTES else V76ScalperEngine.PULLBACK_MAX_HOLD_MINUTES
            val p = PaperPosition(
                side = side, strike = q.strike, quantity = qty, entryPrice = entry, currentPrice = q.ltp,
                highestPrice = entry, stopPrice = entry * (1 - stopPct / 100.0), targetPrice = entry * (1 + targetPct / 100.0),
                openedAtMillis = System.currentTimeMillis(), strategy = strategy, lotSize = lotSize, lots = lots, initialQuantity = qty,
                indexInvalidation = lastV76Evaluation.indexInvalidation, maxHoldMinutes = maxHold,
            )
            sessionV76().trades++
            setEngineState(engine, engineState(engine).copy(position = p, message = "V7.6 PAPER ${side.name} ${q.strike.toInt()} · $strategy"))
            return
        }

        val exec = executionEngine.open(q.ltp)
        val p = PaperPosition(side, q.strike, qty, q.ltp, q.ltp, exec.highestPrice, exec.stopPrice, exec.targetPrice, openedAtMillis = System.currentTimeMillis(), lotSize = lotSize, lots = lots, initialQuantity = qty)
        if (engine == EngineId.ENGINE_1_TREND) engine1Execution = exec else engine2Execution = exec
        setEngineState(engine, engineState(engine).copy(position = p, message = "PAPER ${side.name} ${q.strike.toInt()} · $reason"))
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
        manageEngine12(EngineId.ENGINE_1_TREND); manageEngine12(EngineId.ENGINE_2_AVWAP_LIQUIDITY); manageV76(); updateRiskLock()
    }

    private fun manageEngine12(engine: EngineId) {
        val state = engineState(engine); val position = state.position ?: return
        val execution = if (engine == EngineId.ENGINE_1_TREND) engine1Execution else engine2Execution
        val base = execution ?: executionEngine.open(position.entryPrice)
        val opposite = (position.side == PositionSide.CE && state.signal.action == SignalAction.BUY_PE) || (position.side == PositionSide.PE && state.signal.action == SignalAction.BUY_CE)
        val update = executionEngine.update(base, position.currentPrice, opposite)
        if (engine == EngineId.ENGINE_1_TREND) engine1Execution = update.state else engine2Execution = update.state
        setEngineState(engine, state.copy(position = position.copy(highestPrice = update.state.highestPrice, stopPrice = update.state.stopPrice, targetPrice = update.state.targetPrice, breakevenActive = update.state.breakevenActive, trailingActive = update.state.trailingActive)))
        update.exitReason?.let { closeEnginePosition(engine, it.name.replace('_', ' ')) }
    }

    private fun manageV76() {
        var state = _state.value.engine3
        var p = state.position ?: return
        val q = _state.value.optionChain.firstOrNull { it.strike == p.strike && it.type == p.side.name } ?: return
        val price = q.ltp
        if (price <= 0) return
        val highest = max(p.highestPrice, price)
        val peakGain = max(0.0, highest - p.entryPrice)
        val peakPct = if (p.entryPrice > 0) peakGain / p.entryPrice * 100 else 0.0
        val peakGross = peakGain * p.quantity
        var stop = p.stopPrice
        var trailing = p.trailingActive

        if (!p.target1Hit && (peakPct >= V76ScalperEngine.PROFIT_LOCK_TRIGGER_PERCENT || peakGross >= V76ScalperEngine.PROFIT_LOCK_TRIGGER_GROSS_INR) && peakGain > 0) {
            val minGain = p.entryPrice * V76ScalperEngine.PROFIT_LOCK_MIN_PERCENT / 100.0
            stop = max(stop, p.entryPrice + max(minGain, peakGain * V76ScalperEngine.PROFIT_LOCK_RETAIN_RATIO)); trailing = true
        }

        if (!p.target1Hit && price >= p.targetPrice) {
            var realized = p.realizedPartialPnl; var qty = p.quantity; var lots = p.lots; var bookedQty = p.target1ExitQuantity
            val fill = paperSell(q)
            if (lots >= 2) {
                var partialLots = max(1, (lots * V76ScalperEngine.TARGET1_PARTIAL_FRACTION).toInt())
                partialLots = min(partialLots, lots - 1)
                val partialQty = partialLots * p.lotSize
                realized += (fill - p.entryPrice) * partialQty - V76ScalperEngine.PAPER_EXTRA_PARTIAL_EXIT_COST
                qty -= partialQty; lots -= partialLots; bookedQty += partialQty
            }
            p = p.copy(quantity = qty, lots = lots, target1Hit = true, target1ExitQuantity = bookedQty, realizedPartialPnl = realized,
                maxHoldMinutes = if (p.strategy == "BREAKOUT") V76ScalperEngine.RUNNER_BREAKOUT_MAX_HOLD_MINUTES else V76ScalperEngine.RUNNER_PULLBACK_MAX_HOLD_MINUTES)
        }
        if (p.target1Hit && peakGain > 0) { stop = max(stop, p.entryPrice + peakGain * V76ScalperEngine.RUNNER_RETAIN_RATIO); trailing = true }
        p = p.copy(currentPrice = price, highestPrice = highest, stopPrice = stop, trailingActive = trailing)
        setEngineState(EngineId.ENGINE_3_V76_SCALPER, state.copy(position = p))

        val held = (System.currentTimeMillis() - p.openedAtMillis) / 60_000.0
        val local = Instant.now().atZone(ZoneId.of("Asia/Kolkata")); val minute = local.hour * 60 + local.minute
        val invalid = (p.side == PositionSide.CE && _state.value.spotPrice > 0 && _state.value.spotPrice < p.indexInvalidation) || (p.side == PositionSide.PE && _state.value.spotPrice > p.indexInvalidation && p.indexInvalidation > 0)
        val reason = when {
            price <= p.stopPrice -> if (p.target1Hit) "RUNNER TRAILING STOP" else if (p.trailingActive) "TRAILING STOP" else "PREMIUM STOP"
            invalid -> "INDEX INVALIDATION"
            held >= p.maxHoldMinutes -> if (p.target1Hit) "RUNNER TIME STOP" else "TIME STOP"
            minute >= V76ScalperEngine.FORCE_EXIT_MINUTE -> "SESSION EXIT"
            else -> null
        }
        if (reason != null) closeEnginePosition(EngineId.ENGINE_3_V76_SCALPER, reason)
    }

    private fun closeEnginePosition(engine: EngineId, reason: String) {
        val current = engineState(engine); val position = current.position ?: return
        var pnl = position.pnl
        if (engine == EngineId.ENGINE_3_V76_SCALPER) {
            val q = _state.value.optionChain.firstOrNull { it.strike == position.strike && it.type == position.side.name }
            val exit = q?.let(::paperSell) ?: position.currentPrice
            pnl = position.realizedPartialPnl + (exit - position.entryPrice) * position.quantity - V76ScalperEngine.PAPER_FIXED_COST_PER_TRADE
            val session = sessionV76(); session.pnl += pnl; session.consecutiveLosses = if (pnl < 0) session.consecutiveLosses + 1 else 0
            session.kill = session.pnl <= V76ScalperEngine.MAX_DAILY_LOSS_INR_PER_INDEX || session.consecutiveLosses >= V76ScalperEngine.MAX_CONSECUTIVE_LOSSES
            session.lastExitMillis = System.currentTimeMillis(); session.lastExitSide = position.side
        }
        val old = current.performance; val realized = old.realizedPnl + pnl; val peak = max(old.peakEquity, realized); val drawdown = (peak - realized).coerceAtLeast(0.0)
        val perf = old.copy(trades = old.trades + 1, wins = old.wins + if (pnl > 0) 1 else 0, losses = old.losses + if (pnl < 0) 1 else 0,
            realizedPnl = realized, grossProfit = old.grossProfit + pnl.coerceAtLeast(0.0), grossLoss = old.grossLoss + (-pnl).coerceAtLeast(0.0), peakEquity = peak, maxDrawdown = max(old.maxDrawdown, drawdown))
        setEngineState(engine, current.copy(position = null, performance = perf, message = "$reason · P&L ₹${"%.2f".format(pnl)}"))
        when (engine) { EngineId.ENGINE_1_TREND -> { engine1Execution = null; engine1LastExit = System.currentTimeMillis() }; EngineId.ENGINE_2_AVWAP_LIQUIDITY -> { engine2Execution = null; engine2LastExit = System.currentTimeMillis() }; else -> Unit }
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

    private fun resetMarketStructure() { tickCore.reset(); lastSignalPublishMillis = 0L; v76Working = null; v76Bars.clear(); lastV76SignalMillis = 0L; vixLtp = 0.0 }

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

    companion object { private const val INDIA_VIX_KEY = "NSE_INDEX|India VIX" }
}

private fun <T> Sequence<T>.takeLastCompat(count: Int): List<T> {
    val list = toList(); return if (list.size <= count) list else list.subList(list.size - count, list.size)
}
