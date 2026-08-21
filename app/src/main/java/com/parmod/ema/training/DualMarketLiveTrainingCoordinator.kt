package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.data.UpstoxIntradayCandleClient
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxOptionDiscoveryClient
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import com.parmod.ema.runtime.ProcessTradingScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Process-lifetime read-only AI research coordinator.
 *
 * NIFTY and SENSEX are bootstrapped, subscribed and evaluated concurrently even when
 * the visible trading dashboard is showing only one market. Every market owns its
 * tick core, D30 microstructure buffers, option chain and 1m/V7.6 state. Only the
 * shared Numerical Meta Brain is common, with MarketIndex encoded in every feature.
 */
object DualMarketLiveTrainingCoordinator {
    data class MarketReport(
        val index: MarketIndex,
        val ready: Boolean = false,
        val expiry: String = "",
        val spot: Double = 0.0,
        val ticks: Long = 0L,
        val oneMinuteBars: Int = 0,
        val optionContracts: Int = 0,
        val d30DepthLevels: Int = 0,
        val lastTickMillis: Long = 0L,
        val lastSignalMillis: Long = 0L,
        val error: String = "",
    )

    data class Report(
        val initialized: Boolean = false,
        val running: Boolean = false,
        val connected: Boolean = false,
        val message: String = "Waiting for Upstox token",
        val lastBootstrapMillis: Long = 0L,
        val markets: Map<MarketIndex, MarketReport> = MarketIndex.entries.associateWith { MarketReport(it) },
    )

    private data class WorkingMinute(
        val minute: Long,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var ticks: Long = 1L,
    )

    private class MarketRuntime(
        val index: MarketIndex,
        val expiry: String,
        val underlyingKey: String,
        initialSpot: Double,
        initialChain: List<OptionQuote>,
        warmBars: List<MarketV76ResearchCore.Bar>,
    ) {
        var spot: Double = initialSpot
        var chain: List<OptionQuote> = initialChain
        val tickCore = MarketTickResearchCore()
        val micro = MarketMicrostructureResearch()
        val v76Core = MarketV76ResearchCore()
        val bars = ArrayDeque<MarketV76ResearchCore.Bar>().apply { warmBars.takeLast(5000).forEach(::addLast) }
        var working: WorkingMinute? = null
        var ticks: Long = 0L
        var lastTickMillis: Long = 0L
        var lastSignalMillis: Long = 0L
        var lastTickEvaluationMillis: Long = 0L
        var lastV76SignalMillis: Long = 0L
        var maxDepthLevels: Int = 0
        var error: String = ""
    }

    private lateinit var appContext: Context
    private var initialized = false
    private var watcherJob: Job? = null
    private var stream: DualMarketResearchTickStream? = null
    private var activeTokenFingerprint: Int = 0
    private var activeDate: LocalDate? = null
    private var lastBootstrapMillis = 0L
    private var connected = false
    private var message = "Waiting for Upstox token"
    private val runtimes = linkedMapOf<MarketIndex, MarketRuntime>()
    private val underlyingToMarket = mutableMapOf<String, MarketIndex>()
    private val optionToMarket = mutableMapOf<String, MarketIndex>()
    private var vixLtp = 0.0
    private const val INDIA_VIX_KEY = "NSE_INDEX|India VIX"
    private val zone = ZoneId.of("Asia/Kolkata")

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        watcherJob = ProcessTradingScope.scope.launch { watchLoop() }
    }

    @Synchronized
    fun report(): Report {
        val reports = MarketIndex.entries.associateWith { index ->
            val r = runtimes[index]
            if (r == null) MarketReport(index, error = if (initialized) "Waiting for bootstrap" else "Not initialized")
            else MarketReport(
                index = index,
                ready = r.spot > 0.0 && r.chain.isNotEmpty(),
                expiry = r.expiry,
                spot = r.spot,
                ticks = r.ticks,
                oneMinuteBars = r.bars.size,
                optionContracts = r.chain.size,
                d30DepthLevels = r.maxDepthLevels,
                lastTickMillis = r.lastTickMillis,
                lastSignalMillis = r.lastSignalMillis,
                error = r.error,
            )
        }
        return Report(
            initialized = initialized,
            running = activeTokenFingerprint != 0,
            connected = connected,
            message = message,
            lastBootstrapMillis = lastBootstrapMillis,
            markets = reports,
        )
    }

    private suspend fun watchLoop() {
        while (true) {
            try {
                val token = runCatching { LocalCredentialVault(appContext).read().upstoxAccessToken.trim() }.getOrDefault("")
                val today = LocalDate.now(zone)
                val fingerprint = if (token.isBlank()) 0 else token.hashCode()
                when {
                    token.isBlank() -> {
                        if (activeTokenFingerprint != 0) stopFeed("Upstox token unavailable · dual-market research paused")
                    }
                    fingerprint != activeTokenFingerprint || activeDate != today || runtimes.size != MarketIndex.entries.size -> {
                        bootstrap(token, today, "token/day bootstrap")
                    }
                    needsUniverseRefresh() && System.currentTimeMillis() - lastBootstrapMillis >= MIN_REBOOT_INTERVAL_MS -> {
                        bootstrap(token, today, "ATM universe refresh")
                    }
                }
            } catch (t: Throwable) {
                synchronized(this) { message = "Dual-market AI research retrying: ${t.message?.take(140) ?: t::class.java.simpleName}" }
            }
            delay(WATCH_INTERVAL_MS)
        }
    }

    private suspend fun bootstrap(token: String, today: LocalDate, reason: String) {
        synchronized(this) {
            message = "Bootstrapping simultaneous NIFTY + SENSEX AI research · $reason"
            connected = false
        }
        stream?.disconnect()
        stream = null

        val client = UpstoxLiveClient(token)
        val candleClient = UpstoxIntradayCandleClient(token)
        val built = linkedMapOf<MarketIndex, MarketRuntime>()
        val underlyings = mutableMapOf<String, MarketIndex>()
        val options = mutableMapOf<String, MarketIndex>()

        for (index in MarketIndex.entries) {
            val runtime = withContext(Dispatchers.IO) {
                val discovery = UpstoxOptionDiscoveryClient(token).discover(index, today)
                val expiry = discovery.nearestExpiry
                val snapshot = client.fetchSnapshot(index, expiry)
                val warm = candleClient.getWarmupOneMinuteCandles(snapshot.underlyingKey, 10)
                val nowMinute = System.currentTimeMillis() / 60_000L
                val bars = warm.asSequence()
                    .filter { it.time.toInstant().toEpochMilli() / 60_000L < nowMinute }
                    .map {
                        MarketV76ResearchCore.Bar(
                            timestamp = it.time.toInstant().toEpochMilli(),
                            open = it.open,
                            high = it.high,
                            low = it.low,
                            close = it.close,
                            volume = it.volume,
                        )
                    }
                    .toList()
                MarketRuntime(index, expiry, snapshot.underlyingKey, snapshot.spot, snapshot.options, bars)
            }
            runtime.micro.ingestSpot(runtime.spot, System.currentTimeMillis())
            built[index] = runtime
            underlyings[runtime.underlyingKey] = index
            runtime.chain.forEach { quote -> if (quote.instrumentKey.isNotBlank()) options[quote.instrumentKey] = index }
        }

        val instrumentKeys = (underlyings.keys + options.keys + INDIA_VIX_KEY).distinct()
        val newStream = DualMarketResearchTickStream(
            authorizedUrlProvider = { client.authorizedSocketUrl() },
            instrumentKeys = instrumentKeys,
            listener = object : DualMarketResearchTickStream.Listener {
                override fun onOpen() {
                    synchronized(this@DualMarketLiveTrainingCoordinator) {
                        connected = true
                        message = "LIVE AI RESEARCH · NIFTY + SENSEX simultaneous · D30 requested"
                    }
                }

                override fun onTick(tick: DualMarketResearchTickStream.Tick) = applyTick(tick)

                override fun onError(message: String) {
                    synchronized(this@DualMarketLiveTrainingCoordinator) {
                        this@DualMarketLiveTrainingCoordinator.message = message.take(180)
                    }
                }

                override fun onClosed() {
                    synchronized(this@DualMarketLiveTrainingCoordinator) {
                        connected = false
                        message = "Dual-market AI research feed closed"
                    }
                }
            },
        )

        synchronized(this) {
            runtimes.clear()
            runtimes.putAll(built)
            underlyingToMarket.clear()
            underlyingToMarket.putAll(underlyings)
            optionToMarket.clear()
            optionToMarket.putAll(options)
            activeTokenFingerprint = token.hashCode()
            activeDate = today
            lastBootstrapMillis = System.currentTimeMillis()
            connected = false
            message = "Connecting dual-market D30 AI research feed…"
            stream = newStream
        }
        withContext(Dispatchers.IO) { newStream.connect() }
    }

    @Synchronized
    private fun applyTick(tick: DualMarketResearchTickStream.Tick) {
        val timestamp = tick.ltt?.takeIf { it > 0L } ?: tick.feedTimestamp
        if (timestamp <= 0L) return
        if (tick.instrumentKey == INDIA_VIX_KEY) {
            tick.ltp?.takeIf { it > 0.0 }?.let { vixLtp = it }
            return
        }
        val underlyingMarket = underlyingToMarket[tick.instrumentKey]
        if (underlyingMarket != null) {
            val price = tick.ltp ?: return
            onUnderlyingTick(runtimes[underlyingMarket] ?: return, price, timestamp)
            return
        }
        val optionMarket = optionToMarket[tick.instrumentKey] ?: return
        val runtime = runtimes[optionMarket] ?: return
        runtime.chain = runtime.chain.map { q ->
            if (q.instrumentKey != tick.instrumentKey) q else q.copy(
                ltp = tick.ltp ?: q.ltp,
                openInterest = tick.oi ?: q.openInterest,
                delta = tick.delta ?: q.delta,
                gamma = tick.gamma ?: q.gamma,
                lastTickMillis = timestamp,
                bid = tick.bid ?: q.bid,
                ask = tick.ask ?: q.ask,
                volume = tick.volume ?: q.volume,
            )
        }
        runtime.maxDepthLevels = maxOf(runtime.maxDepthLevels, tick.depth.size)
        runtime.micro.ingestOption(
            instrumentKey = tick.instrumentKey,
            ltp = tick.ltp,
            oi = tick.oi,
            volume = tick.volume,
            bid = tick.bid,
            ask = tick.ask,
            timestamp = timestamp,
            totalBuyQty = tick.totalBuyQty,
            totalSellQty = tick.totalSellQty,
            depth = tick.depth.map {
                MarketMicrostructureResearch.DepthLevel(it.bidPrice, it.bidQty, it.askPrice, it.askQty)
            },
        )
    }

    private fun onUnderlyingTick(runtime: MarketRuntime, price: Double, timestamp: Long) {
        if (price <= 0.0) return
        runtime.spot = price
        runtime.ticks++
        runtime.lastTickMillis = maxOf(runtime.lastTickMillis, timestamp)
        runtime.tickCore.ingest(price, timestamp)
        runtime.micro.ingestSpot(price, timestamp)
        MetaBrainRuntime.observeSpot(runtime.index, price, timestamp)
        ingestMinute(runtime, price, timestamp)
        if (timestamp - runtime.lastTickEvaluationMillis >= TICK_EVALUATION_INTERVAL_MS) {
            runtime.lastTickEvaluationMillis = timestamp
            val raw = runtime.tickCore.evaluate()
            decorateTickEngine(runtime, EngineId.ENGINE_1_TREND, raw.engine1, timestamp)
            decorateTickEngine(runtime, EngineId.ENGINE_2_AVWAP_LIQUIDITY, raw.engine2, timestamp)
        }
    }

    private fun decorateTickEngine(runtime: MarketRuntime, engine: EngineId, raw: SignalSnapshot, timestamp: Long) {
        if (raw.trend == TrendDirection.NEUTRAL || runtime.spot <= 0.0) return
        val side = if (raw.trend == TrendDirection.BULLISH) PositionSide.CE else PositionSide.PE
        val quality = runtime.micro.evaluate(side, runtime.chain, runtime.spot)
        val diagnostic = runtime.tickCore.diagnosticFeatures(raw)
        val direction = if (quality.directionScore > 0) quality.directionScore.toDouble() else (raw.confidence * 0.60).coerceIn(0.0, 60.0)
        val entry = if (quality.entryQualityScore > 0) quality.entryQualityScore.toDouble() else (raw.confidence * 0.40).coerceIn(0.0, 40.0)
        val enriched = raw.copy(
            confidence = ((raw.confidence * 0.55) + (quality.score * 0.45)).toInt().coerceIn(0, 100),
            reasons = (raw.reasons + "DUAL-MARKET D30 research ${quality.score}/100").takeLast(10),
        )
        MetaBrainRuntime.decorate(
            index = runtime.index,
            engine = engine,
            raw = enriched,
            spot = runtime.spot,
            timestamp = timestamp,
            directionScore = direction,
            entryQualityScore = entry,
            orderFlow = if (quality.orderFlow != 0.0) quality.orderFlow else diagnostic.first,
            relativeActivity = quality.relativeActivity,
            oiImpulse = quality.oiImpulse,
            optionFlow = quality.optionFlow,
            acceleration = quality.acceleration,
            extensionAtr = if (quality.extensionAtr > 0.0) quality.extensionAtr else diagnostic.second,
            depthImbalance = quality.depthImbalance,
            micropricePressure = quality.micropricePressure,
            totalBookPressure = quality.totalBookPressure,
            wallPressure = quality.wallPressure,
            depthLevels = quality.depthLevels.toDouble(),
        )
        runtime.lastSignalMillis = maxOf(runtime.lastSignalMillis, timestamp)
    }

    private fun ingestMinute(runtime: MarketRuntime, price: Double, timestamp: Long) {
        val minute = timestamp / 60_000L
        val w = runtime.working
        when {
            w == null -> runtime.working = WorkingMinute(minute, price, price, price, price)
            minute < w.minute -> return
            minute == w.minute -> {
                w.high = maxOf(w.high, price)
                w.low = minOf(w.low, price)
                w.close = price
                w.ticks++
            }
            else -> {
                runtime.bars.addLast(MarketV76ResearchCore.Bar(w.minute * 60_000L, w.open, w.high, w.low, w.close, w.ticks))
                while (runtime.bars.size > 5000) runtime.bars.removeFirst()
                runtime.working = WorkingMinute(minute, price, price, price, price)
                evaluateV76(runtime)
            }
        }
    }

    private fun evaluateV76(runtime: MarketRuntime) {
        if (runtime.bars.size < MarketV76ResearchCore.MIN_READY_1M_CANDLES) return
        val evaluation = runtime.v76Core.evaluate(runtime.bars.toList(), runtime.chain, runtime.spot, vixLtp)
        if (evaluation.signalTimeMillis <= 0L || evaluation.signalTimeMillis <= runtime.lastV76SignalMillis) return
        runtime.lastV76SignalMillis = evaluation.signalTimeMillis
        val raw = evaluation.signal
        if (raw.trend == TrendDirection.NEUTRAL) return
        val side = if (raw.trend == TrendDirection.BULLISH) PositionSide.CE else PositionSide.PE
        val quality = runtime.micro.evaluate(side, runtime.chain, runtime.spot)
        val enriched = raw.copy(
            confidence = ((raw.confidence * 0.55) + (quality.score * 0.45)).toInt().coerceIn(0, 100),
            reasons = (raw.reasons + "Market-scoped D30 ${quality.score}/100 · depth ${quality.depthLevels}").takeLast(10),
        )
        MetaBrainRuntime.decorate(
            index = runtime.index,
            engine = EngineId.ENGINE_3_V76_SCALPER,
            raw = enriched,
            spot = runtime.spot,
            timestamp = evaluation.signalTimeMillis,
            directionScore = quality.directionScore.toDouble().coerceAtLeast(raw.confidence * 0.50).coerceAtMost(60.0),
            entryQualityScore = quality.entryQualityScore.toDouble().coerceAtLeast(raw.confidence * 0.25).coerceAtMost(40.0),
            orderFlow = quality.orderFlow,
            relativeActivity = quality.relativeActivity,
            oiImpulse = quality.oiImpulse,
            optionFlow = quality.optionFlow,
            acceleration = quality.acceleration,
            extensionAtr = quality.extensionAtr,
            depthImbalance = quality.depthImbalance,
            micropricePressure = quality.micropricePressure,
            totalBookPressure = quality.totalBookPressure,
            wallPressure = quality.wallPressure,
            depthLevels = quality.depthLevels.toDouble(),
        )
        runtime.lastSignalMillis = maxOf(runtime.lastSignalMillis, evaluation.signalTimeMillis)
    }

    @Synchronized
    private fun needsUniverseRefresh(): Boolean = runtimes.values.any { runtime ->
        val strikes = runtime.chain.map { it.strike }.distinct().sorted()
        if (strikes.size < 5 || runtime.spot <= 0.0) false
        else {
            val step = if (runtime.index == MarketIndex.NIFTY) 50.0 else 100.0
            runtime.spot <= strikes.first() + step * 1.5 || runtime.spot >= strikes.last() - step * 1.5
        }
    }

    @Synchronized
    private fun stopFeed(reason: String) {
        stream?.disconnect()
        stream = null
        connected = false
        activeTokenFingerprint = 0
        activeDate = null
        message = reason
    }

    private const val WATCH_INTERVAL_MS = 30_000L
    private const val MIN_REBOOT_INTERVAL_MS = 5 * 60_000L
    private const val TICK_EVALUATION_INTERVAL_MS = 250L
}
