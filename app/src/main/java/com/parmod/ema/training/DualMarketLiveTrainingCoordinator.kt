package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.data.UpstoxIntradayCandleClient
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxOptionDiscoveryClient
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.NumericalMetaBrain
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
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Process-lifetime read-only dual-market AI research coordinator. */
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

    private data class TrainingContract(
        val instrumentKey: String,
        val premium: Double,
        val lotSize: Int,
        val strike: Double,
        val volume: Long,
        val oi: Long,
    )

    private class MarketRuntime(
        val index: MarketIndex,
        val expiry: String,
        val underlyingKey: String,
        val lotSizes: Map<String, Int>,
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
        val premiumMinuteCloses = mutableMapOf<String, LinkedHashMap<Long, Double>>()
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
        LiveResearchArchive.initialize(appContext)
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
        return Report(initialized, activeTokenFingerprint != 0, connected, message, lastBootstrapMillis, reports)
    }

    private suspend fun watchLoop() {
        while (true) {
            try {
                val token = runCatching { LocalCredentialVault(appContext).read().upstoxAccessToken.trim() }.getOrDefault("")
                val today = LocalDate.now(zone)
                val fingerprint = if (token.isBlank()) 0 else token.hashCode()
                when {
                    token.isBlank() -> if (activeTokenFingerprint != 0) stopFeed("Upstox token unavailable · dual-market research paused")
                    fingerprint != activeTokenFingerprint || activeDate != today || runtimes.size != MarketIndex.entries.size -> bootstrap(token, today, "token/day bootstrap")
                    needsUniverseRefresh() && System.currentTimeMillis() - lastBootstrapMillis >= MIN_REBOOT_INTERVAL_MS -> bootstrap(token, today, "ATM universe refresh")
                }
            } catch (t: Throwable) {
                synchronized(this) { message = "Dual-market AI research retrying: ${t.message?.take(140) ?: t::class.java.simpleName}" }
            }
            delay(WATCH_INTERVAL_MS)
        }
    }

    private suspend fun bootstrap(token: String, today: LocalDate, reason: String) {
        synchronized(this) {
            message = "Bootstrapping simultaneous NIFTY + SENSEX premium research · $reason"
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
                val expiryContracts = discovery.contractsByExpiry[expiry].orEmpty()
                val lotSizes = expiryContracts.associate { it.instrumentKey to it.lotSize }
                val snapshot = client.fetchSnapshot(index, expiry)
                val warm = candleClient.getWarmupOneMinuteCandles(snapshot.underlyingKey, 10)
                val nowMinute = System.currentTimeMillis() / 60_000L
                val bars = warm.asSequence()
                    .filter { it.time.toInstant().toEpochMilli() / 60_000L < nowMinute }
                    .map { MarketV76ResearchCore.Bar(it.time.toInstant().toEpochMilli(), it.open, it.high, it.low, it.close, it.volume) }
                    .toList()
                MarketRuntime(index, expiry, snapshot.underlyingKey, lotSizes, snapshot.spot, snapshot.options, bars)
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
                        message = "LIVE AI RESEARCH · NIFTY + SENSEX · CAUSAL V2 · ARCHIVE ON · D30 requested"
                    }
                }
                override fun onTick(tick: DualMarketResearchTickStream.Tick) = applyTick(tick)
                override fun onError(message: String) {
                    synchronized(this@DualMarketLiveTrainingCoordinator) { this@DualMarketLiveTrainingCoordinator.message = message.take(180) }
                }
                override fun onClosed() {
                    synchronized(this@DualMarketLiveTrainingCoordinator) {
                        connected = false
                        message = "Dual-market AI research feed closed"
                    }
                }
            },
        )

        MarketIndex.entries.forEach(MetaBrainRuntime::resetSession)
        synchronized(this) {
            runtimes.clear(); runtimes.putAll(built)
            underlyingToMarket.clear(); underlyingToMarket.putAll(underlyings)
            optionToMarket.clear(); optionToMarket.putAll(options)
            activeTokenFingerprint = token.hashCode()
            activeDate = today
            lastBootstrapMillis = System.currentTimeMillis()
            connected = false
            message = "Connecting dual-market D30 premium research feed…"
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
            LiveResearchArchive.captureTick(
                underlyingMarket, tick.instrumentKey, "UNDERLYING", timestamp, price,
                tick.oi, tick.volume, tick.bid, tick.ask, tick.totalBuyQty, tick.totalSellQty, emptyList(),
            )
            onUnderlyingTick(runtimes[underlyingMarket] ?: return, price, timestamp)
            return
        }
        val optionMarket = optionToMarket[tick.instrumentKey] ?: return
        val runtime = runtimes[optionMarket] ?: return
        val premium = tick.ltp
        if (premium != null && premium > 0.0) {
            ingestPremiumMinute(runtime, tick.instrumentKey, premium, timestamp)
            MetaBrainRuntime.observeOptionPremium(optionMarket, tick.instrumentKey, premium, timestamp)
        }
        val oldQuote = runtime.chain.firstOrNull { it.instrumentKey == tick.instrumentKey }
        runtime.chain = runtime.chain.map { q ->
            if (q.instrumentKey != tick.instrumentKey) q else q.copy(
                ltp = premium ?: q.ltp,
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
            ltp = premium,
            oi = tick.oi,
            volume = tick.volume,
            bid = tick.bid,
            ask = tick.ask,
            timestamp = timestamp,
            totalBuyQty = tick.totalBuyQty,
            totalSellQty = tick.totalSellQty,
            depth = tick.depth.map { MarketMicrostructureResearch.DepthLevel(it.bidPrice, it.bidQty, it.askPrice, it.askQty) },
        )
        val step = if (runtime.index == MarketIndex.NIFTY) 50.0 else 100.0
        val nearAtm = oldQuote != null && abs(oldQuote.strike - runtime.spot) <= step * 1.5
        LiveResearchArchive.captureTick(
            index = optionMarket,
            instrumentKey = tick.instrumentKey,
            kind = "OPTION",
            timestamp = timestamp,
            ltp = premium,
            oi = tick.oi,
            volume = tick.volume,
            bid = tick.bid,
            ask = tick.ask,
            totalBuyQty = tick.totalBuyQty,
            totalSellQty = tick.totalSellQty,
            depth = if (nearAtm) tick.depth.map { LiveResearchArchive.DepthRow(it.bidPrice, it.bidQty, it.askPrice, it.askQty) } else emptyList(),
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
        val training = trainingContract(runtime, side, timestamp)
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
            causalExtras = training?.let { liveCausal(runtime, it, timestamp) } ?: NumericalMetaBrain.CausalExtras(),
            registerForTraining = training != null,
            trainingInstrumentKey = training?.instrumentKey,
            trainingPremium = training?.premium,
            trainingLotSize = training?.lotSize ?: 0,
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
                w.high = maxOf(w.high, price); w.low = minOf(w.low, price); w.close = price; w.ticks++
            }
            else -> {
                val bar = MarketV76ResearchCore.Bar(w.minute * 60_000L, w.open, w.high, w.low, w.close, w.ticks)
                runtime.bars.addLast(bar)
                while (runtime.bars.size > 5000) runtime.bars.removeFirst()
                LiveResearchArchive.captureMinuteBar(runtime.index, bar.timestamp, bar.open, bar.high, bar.low, bar.close, bar.volume)
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
        val training = trainingContract(runtime, side, evaluation.signalTimeMillis)
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
            causalExtras = training?.let { liveCausal(runtime, it, evaluation.signalTimeMillis) } ?: NumericalMetaBrain.CausalExtras(),
            registerForTraining = training != null,
            trainingInstrumentKey = training?.instrumentKey,
            trainingPremium = training?.premium,
            trainingLotSize = training?.lotSize ?: 0,
        )
        runtime.lastSignalMillis = maxOf(runtime.lastSignalMillis, evaluation.signalTimeMillis)
    }

    private fun ingestPremiumMinute(runtime: MarketRuntime, instrumentKey: String, premium: Double, timestamp: Long) {
        val minute = timestamp / 60_000L
        val map = runtime.premiumMinuteCloses.getOrPut(instrumentKey) { linkedMapOf() }
        map[minute] = premium
        while (map.size > 90) map.remove(map.keys.first())
    }

    private fun liveCausal(runtime: MarketRuntime, contract: TrainingContract, timestamp: Long): NumericalMetaBrain.CausalExtras {
        val premiumCloses = runtime.premiumMinuteCloses[contract.instrumentKey]?.values?.toList().orEmpty()
        val spotCloses = buildList {
            runtime.bars.takeLast(79).forEach { add(it.close) }
            runtime.working?.close?.let(::add)
        }
        val step = if (runtime.index == MarketIndex.NIFTY) 50.0 else 100.0
        val moneyness = if (step > 0.0) (contract.strike - runtime.spot) / step else 0.0
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val expiry = runCatching { LocalDate.parse(runtime.expiry) }.getOrNull()
        val dte = expiry?.let { ChronoUnit.DAYS.between(date, it).coerceAtLeast(0L).toDouble() } ?: 0.0
        return CausalFeatureEngineering.fromLiveCloses(
            premiumCloses = premiumCloses,
            spotCloses = spotCloses,
            currentOpen = contract.premium,
            currentHigh = contract.premium,
            currentLow = contract.premium,
            currentClose = contract.premium,
            currentVolume = contract.volume.toDouble(),
            currentOi = contract.oi.toDouble(),
            moneynessSteps = moneyness,
            daysToExpiry = dte,
        )
    }

    private fun trainingContract(runtime: MarketRuntime, side: PositionSide, timestamp: Long): TrainingContract? {
        val quote = runtime.chain.asSequence()
            .filter { it.type == side.name && it.instrumentKey.isNotBlank() && it.ltp > 0.0 && it.lastTickMillis > 0L }
            .filter { abs(timestamp - it.lastTickMillis) <= MAX_TRAINING_QUOTE_AGE_MS }
            .minByOrNull { abs(it.strike - runtime.spot) }
            ?: return null
        val lot = runtime.lotSizes[quote.instrumentKey] ?: return null
        if (lot <= 0) return null
        return TrainingContract(quote.instrumentKey, quote.ltp, lot, quote.strike, quote.volume, quote.openInterest)
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
        stream?.disconnect(); stream = null; connected = false; activeTokenFingerprint = 0; activeDate = null; message = reason
        MarketIndex.entries.forEach(MetaBrainRuntime::resetSession)
    }

    private const val WATCH_INTERVAL_MS = 30_000L
    private const val MIN_REBOOT_INTERVAL_MS = 5 * 60_000L
    private const val TICK_EVALUATION_INTERVAL_MS = 250L
    private const val MAX_TRAINING_QUOTE_AGE_MS = 5_000L
}
