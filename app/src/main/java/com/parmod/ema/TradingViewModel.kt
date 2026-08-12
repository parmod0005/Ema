package com.parmod.ema

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxTickStream
import com.parmod.ema.engine.AvwapLiquidityEngine
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
import kotlin.math.max
import kotlin.random.Random

class TradingViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var feedJob: Job? = null
    private var tickStream: UpstoxTickStream? = null
    private var underlyingKey = ""
    private var savedAccessToken = ""
    private var savedExpiry = ""

    private val engine1Core = SignalEngineV2()
    private val engine2Core = AvwapLiquidityEngine()
    private val optionSelector = OptionSelector()
    private val executionEngine = ExecutionEngineV2()

    private var engine1Execution: ExecutionEngineV2.State? = null
    private var engine2Execution: ExecutionEngineV2.State? = null
    private var engine1LastExit = 0L
    private var engine2LastExit = 0L
    private var lastEvaluatedMinute = -1L

    private data class WorkingBar(
        val minute: Long,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var ticks: Long = 0,
        var buyTicks: Long = 0,
        var sellTicks: Long = 0,
        var previousTick: Double = open,
    )

    private var workingBar: WorkingBar? = null
    private val completedBars = ArrayDeque<AvwapLiquidityEngine.Bar>()

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
            message = "Loading ${selectedIndex.name} contracts and single live feed…",
        )
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
                        override fun onOpen() {
                            _state.value = _state.value.copy(isConnected = true, message = "${selectedIndex.name} live · dual paper engines · ${keys.size} instruments")
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

    fun connectDemo() {
        disconnectInternal()
        resetMarketStructure()
        _state.value = _state.value.copy(connectionMode = ConnectionMode.DEMO, isConnected = true, executionMode = ExecutionMode.PAPER, message = "Demo feed · dual engines")
        feedJob = viewModelScope.launch {
            var n = 0
            while (true) {
                val base = if (_state.value.index == MarketIndex.NIFTY) 24_500.0 else 80_000.0
                val wave = when ((n / 80) % 4) {
                    0 -> n % 80 * 1.4
                    1 -> 112.0 - (n % 80) * 0.7
                    2 -> 56.0 - (n % 80) * 1.2
                    else -> -40.0 + (n % 80) * 0.5
                }
                val spot = base + wave + Random.nextDouble(-2.0, 2.0)
                val chain = buildDemoChain(spot)
                val now = System.currentTimeMillis()
                onSpotTick(spot, now)
                _state.value = _state.value.copy(optionChain = chain, lastTickMillis = now, ticksReceived = _state.value.ticksReceived + 1)
                updatePositions(chain)
                managePositions()
                n++
                delay(500)
            }
        }
    }

    fun disconnect() {
        disconnectInternal()
        _state.value = _state.value.copy(isConnected = false, message = "Disconnected")
    }

    private fun disconnectInternal() {
        feedJob?.cancel()
        feedJob = null
        tickStream?.disconnect()
        tickStream = null
    }

    fun selectIndex(index: MarketIndex) {
        if (_state.value.index == index) return
        closeEnginePosition(EngineId.ENGINE_1_TREND, "Market changed")
        closeEnginePosition(EngineId.ENGINE_2_AVWAP_LIQUIDITY, "Market changed")
        resetMarketStructure()
        _state.value = _state.value.copy(index = index, isConnected = false, optionChain = emptyList(), spotPrice = 0.0, message = "Switching to ${index.name}…")
        if (_state.value.connectionMode == ConnectionMode.UPSTOX && savedAccessToken.isNotBlank() && savedExpiry.isNotBlank()) connectSelectedIndex()
        else if (_state.value.connectionMode == ConnectionMode.DEMO) connectDemo()
    }

    fun setTradingMode(mode: TradingMode) { _state.value = _state.value.copy(tradingMode = mode, message = "$mode · both engines remain PAPER only") }
    fun setAppMode(mode: AppMode) { _state.value = _state.value.copy(appMode = mode) }
    fun setStartingCapital(value: Double) { if (value > 0) _state.value = _state.value.copy(startingCapital = value) }
    fun setLiveTradingEnabled(enabled: Boolean) { _state.value = _state.value.copy(executionMode = ExecutionMode.PAPER, message = "Live broker orders are removed · PAPER only") }

    fun manualBuy(engine: EngineId, side: PositionSide) = openEnginePosition(engine, side, "Manual paper entry")
    fun exitEngine(engine: EngineId) = closeEnginePosition(engine, "Manual exit")

    private fun publishLiveSnapshot(snapshot: UpstoxLiveClient.Snapshot) {
        val now = System.currentTimeMillis()
        onSpotTick(snapshot.spot, now)
        _state.value = _state.value.copy(spotPrice = snapshot.spot, optionChain = snapshot.options, lastTickMillis = now)
        updatePositions(snapshot.options)
    }

    private fun applyTick(tick: UpstoxTickStream.Tick) {
        val current = _state.value
        var chain = current.optionChain
        if (tick.instrumentKey == underlyingKey && tick.ltp != null) {
            onSpotTick(tick.ltp, tick.feedTimestamp)
        } else {
            chain = chain.map { q ->
                if (q.instrumentKey != tick.instrumentKey) q else q.copy(
                    ltp = tick.ltp ?: q.ltp,
                    openInterest = tick.oi ?: q.openInterest,
                    delta = tick.delta ?: q.delta,
                    gamma = tick.gamma ?: q.gamma,
                    lastTickMillis = tick.feedTimestamp,
                )
            }
        }
        _state.value = _state.value.copy(
            isConnected = true,
            optionChain = chain,
            lastTickMillis = tick.feedTimestamp,
            ticksReceived = current.ticksReceived + 1,
            message = "${current.index.name} · ${current.ticksReceived + 1} ticks · 2 PAPER engines",
        )
        updatePositions(chain)
        managePositions()
    }

    private fun onSpotTick(price: Double, timestamp: Long) {
        if (price <= 0.0) return
        val minute = timestamp / 60_000L
        val bar = workingBar
        if (bar == null) {
            workingBar = WorkingBar(minute, price, price, price, price, ticks = 1, previousTick = price)
        } else if (bar.minute == minute) {
            if (price > bar.previousTick) bar.buyTicks++ else if (price < bar.previousTick) bar.sellTicks++
            bar.high = max(bar.high, price)
            bar.low = minOf(bar.low, price)
            bar.close = price
            bar.previousTick = price
            bar.ticks++
        } else {
            completedBars.addLast(bar.toEngine2Bar())
            while (completedBars.size > 180) completedBars.removeFirst()
            evaluateCompletedMinute(bar.minute)
            workingBar = WorkingBar(minute, price, price, price, price, ticks = 1, previousTick = price)
        }
        _state.value = _state.value.copy(spotPrice = price, lastTickMillis = timestamp)
    }

    private fun WorkingBar.toEngine2Bar() = AvwapLiquidityEngine.Bar(open, high, low, close, ticks, buyTicks, sellTicks, minute * 60_000L)

    private fun evaluateCompletedMinute(minute: Long) {
        if (minute == lastEvaluatedMinute || completedBars.isEmpty()) return
        lastEvaluatedMinute = minute
        val bars = completedBars.toList()

        val e1Bars = bars.map { SignalEngineV2.Bar(it.open, it.high, it.low, it.close, it.tickVolume) }
        val e1Eval = engine1Core.evaluate(e1Bars)
        val e1Signal = when (e1Eval.direction) {
            SignalEngineV2.Direction.BULLISH -> SignalSnapshot(SignalAction.BUY_CE, e1Eval.score, TrendDirection.BULLISH, bars.last().close, bars.last().close - e1Eval.atr, bars.last().close + e1Eval.atr * 1.8, e1Eval.reasons, "TREND + BREAKOUT + ANTI-CHOP")
            SignalEngineV2.Direction.BEARISH -> SignalSnapshot(SignalAction.BUY_PE, e1Eval.score, TrendDirection.BEARISH, bars.last().close, bars.last().close + e1Eval.atr, bars.last().close - e1Eval.atr * 1.8, e1Eval.reasons, "TREND + BREAKOUT + ANTI-CHOP")
            SignalEngineV2.Direction.NEUTRAL -> SignalSnapshot(SignalAction.WAIT, e1Eval.score, TrendDirection.NEUTRAL, null, null, null, e1Eval.reasons, "ANTI-CHOP WAIT")
        }

        val e2Eval = engine2Core.evaluate(bars)
        _state.value = _state.value.copy(
            engine1 = _state.value.engine1.copy(signal = e1Signal, message = e1Signal.setup),
            engine2 = _state.value.engine2.copy(signal = e2Eval.signal, message = e2Eval.signal.setup),
        )
        runParallelAuto()
    }

    private fun runParallelAuto() {
        val s = _state.value
        if (s.tradingMode != TradingMode.AUTO || s.appMode != AppMode.LIVE_MARKET || s.riskLocked) return
        val now = System.currentTimeMillis()
        if (s.engine1.position == null && now - engine1LastExit >= 120_000L) {
            when (s.engine1.signal.action) {
                SignalAction.BUY_CE -> openEnginePosition(EngineId.ENGINE_1_TREND, PositionSide.CE, s.engine1.signal.setup)
                SignalAction.BUY_PE -> openEnginePosition(EngineId.ENGINE_1_TREND, PositionSide.PE, s.engine1.signal.setup)
                SignalAction.WAIT -> Unit
            }
        }
        val after1 = _state.value
        if (after1.engine2.position == null && now - engine2LastExit >= 120_000L && after1.engine2.signal.confidence >= 90) {
            when (after1.engine2.signal.action) {
                SignalAction.BUY_CE -> openEnginePosition(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.CE, after1.engine2.signal.setup)
                SignalAction.BUY_PE -> openEnginePosition(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.PE, after1.engine2.signal.setup)
                SignalAction.WAIT -> Unit
            }
        }
    }

    private fun openEnginePosition(engine: EngineId, side: PositionSide, reason: String) {
        val s = _state.value
        if (!s.isConnected || s.riskLocked) return
        val existing = if (engine == EngineId.ENGINE_1_TREND) s.engine1.position else s.engine2.position
        if (existing != null) return
        val selection = optionSelector.select(s.optionChain, side.name) ?: run {
            val updated = engineState(engine).copy(message = "No liquid ${side.name} contract")
            setEngineState(engine, updated)
            return
        }
        val q = selection.quote
        val qty = if (s.index == MarketIndex.NIFTY) 65 else 20
        val exec = executionEngine.open(q.ltp)
        val position = PaperPosition(
            side = side,
            strike = q.strike,
            quantity = qty,
            entryPrice = q.ltp,
            currentPrice = q.ltp,
            highestPrice = exec.highestPrice,
            stopPrice = exec.stopPrice,
            targetPrice = exec.targetPrice,
            openedAtMillis = System.currentTimeMillis(),
        )
        if (engine == EngineId.ENGINE_1_TREND) engine1Execution = exec else engine2Execution = exec
        setEngineState(engine, engineState(engine).copy(position = position, message = "PAPER ${side.name} ${q.strike.toInt()} · $reason"))
    }

    private fun updatePositions(chain: List<OptionQuote>) {
        var e1 = _state.value.engine1
        var e2 = _state.value.engine2
        e1.position?.let { p -> chain.firstOrNull { it.strike == p.strike && it.type == p.side.name }?.let { e1 = e1.copy(position = p.copy(currentPrice = it.ltp)) } }
        e2.position?.let { p -> chain.firstOrNull { it.strike == p.strike && it.type == p.side.name }?.let { e2 = e2.copy(position = p.copy(currentPrice = it.ltp)) } }
        _state.value = _state.value.copy(engine1 = e1, engine2 = e2)
    }

    private fun managePositions() {
        manageEngine(EngineId.ENGINE_1_TREND)
        manageEngine(EngineId.ENGINE_2_AVWAP_LIQUIDITY)
        updateRiskLock()
    }

    private fun manageEngine(engine: EngineId) {
        val state = engineState(engine)
        val position = state.position ?: return
        val execution = if (engine == EngineId.ENGINE_1_TREND) engine1Execution else engine2Execution
        val base = execution ?: executionEngine.open(position.entryPrice)
        val signal = state.signal
        val opposite = (position.side == PositionSide.CE && signal.action == SignalAction.BUY_PE) ||
            (position.side == PositionSide.PE && signal.action == SignalAction.BUY_CE)
        val update = executionEngine.update(base, position.currentPrice, opposite)
        if (engine == EngineId.ENGINE_1_TREND) engine1Execution = update.state else engine2Execution = update.state
        val managed = position.copy(
            highestPrice = update.state.highestPrice,
            stopPrice = update.state.stopPrice,
            targetPrice = update.state.targetPrice,
            breakevenActive = update.state.breakevenActive,
            trailingActive = update.state.trailingActive,
        )
        setEngineState(engine, state.copy(position = managed))
        update.exitReason?.let { closeEnginePosition(engine, it.name.replace('_', ' ')) }
    }

    private fun closeEnginePosition(engine: EngineId, reason: String) {
        val current = engineState(engine)
        val position = current.position ?: return
        val pnl = position.pnl
        val old = current.performance
        val realized = old.realizedPnl + pnl
        val peak = max(old.peakEquity, realized)
        val drawdown = (peak - realized).coerceAtLeast(0.0)
        val perf = old.copy(
            trades = old.trades + 1,
            wins = old.wins + if (pnl > 0) 1 else 0,
            losses = old.losses + if (pnl < 0) 1 else 0,
            realizedPnl = realized,
            grossProfit = old.grossProfit + pnl.coerceAtLeast(0.0),
            grossLoss = old.grossLoss + (-pnl).coerceAtLeast(0.0),
            peakEquity = peak,
            maxDrawdown = max(old.maxDrawdown, drawdown),
        )
        setEngineState(engine, current.copy(position = null, performance = perf, message = "$reason · P&L ₹${"%.2f".format(pnl)}"))
        if (engine == EngineId.ENGINE_1_TREND) {
            engine1Execution = null
            engine1LastExit = System.currentTimeMillis()
        } else {
            engine2Execution = null
            engine2LastExit = System.currentTimeMillis()
        }
        updateRiskLock()
    }

    private fun updateRiskLock() {
        val s = _state.value
        val lossLimit = s.startingCapital * 0.02
        val combined = s.engine1.performance.realizedPnl + s.engine2.performance.realizedPnl
        val locked = combined <= -lossLimit
        _state.value = s.copy(riskLocked = locked, riskReason = if (locked) "Combined session loss reached 2%" else "Risk gates clear")
    }

    private fun engineState(engine: EngineId) = if (engine == EngineId.ENGINE_1_TREND) _state.value.engine1 else _state.value.engine2
    private fun setEngineState(engine: EngineId, value: EngineState) {
        _state.value = if (engine == EngineId.ENGINE_1_TREND) _state.value.copy(engine1 = value) else _state.value.copy(engine2 = value)
    }

    private fun resetMarketStructure() {
        workingBar = null
        completedBars.clear()
        lastEvaluatedMinute = -1L
    }

    private fun buildDemoChain(spot: Double): List<OptionQuote> {
        val step = if (_state.value.index == MarketIndex.NIFTY) 50 else 100
        val atm = (spot / step).toInt() * step
        return (-5..5).flatMap { offset ->
            val strike = atm + offset * step
            val distance = spot - strike
            val timeValue = max(18.0, 110.0 - kotlin.math.abs(offset) * 12.0)
            val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
            listOf(
                OptionQuote(strike.toDouble(), "CE", max(0.0, distance) + timeValue, 90_000L, 1_500L, ceDelta, 0.002, offset == 0, "CE$strike"),
                OptionQuote(strike.toDouble(), "PE", max(0.0, -distance) + timeValue, 94_000L, 1_200L, ceDelta - 1.0, 0.002, offset == 0, "PE$strike"),
            )
        }
    }

    override fun onCleared() {
        // Do not intentionally tear down an active personal paper session merely because
        // Android recreated the Activity. Explicit Disconnect remains the stop control.
        super.onCleared()
    }
}
