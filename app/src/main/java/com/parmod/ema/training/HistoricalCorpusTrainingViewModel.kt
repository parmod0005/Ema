package com.parmod.ema.training

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.model.MarketIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HistoricalCorpusTrainingViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val selectedMonths: Int = 1,
        val selectedIndex: MarketIndex = MarketIndex.NIFTY,
        val selectedMarketScope: HistoricalMarketScope = HistoricalMarketScope.BOTH,
        val selectedSource: HistoricalCorpusSource = HistoricalCorpusSource.UPSTOX,
        val isRunning: Boolean = false,
        val isImporting: Boolean = false,
        val stage: String = "IDLE",
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "Ready · BOTH NIFTY + SENSEX historical research selected",
        val result: HistoricalCorpusTrainer.Result? = null,
        val installedCandidate: Boolean = false,
        val error: String? = null,
        val cacheHits: Long = 0,
        val networkRequests: Long = 0,
        val localSummary: LocalCorpusSummary = LocalCorpusSummary(),
        val importCompleted: Int = 0,
        val importTotal: Int = 0,
        val importMessage: String = "No local import running",
        val prelabelledCorpusReady: Boolean = false,
        val prelabelledTrainRows: Long = 0,
        val prelabelledValidationRows: Long = 0,
        val prelabelledTestRows: Long = 0,
    ) {
        val progress: Float get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val importProgress: Float get() = if (importTotal <= 0) 0f else (importCompleted.toFloat() / importTotal.toFloat()).coerceIn(0f, 1f)
        val windowLabel: String get() = PrelabelledTrainingWindowPlan.label(selectedMonths)
    }

    private val vault = LocalCredentialVault(application)
    private val upstoxCacheDirectory = File(application.filesDir, "upstox_backtest_cache/v1")
    private val localStore = StreamingLocalHistoricalCorpusStore(application)
    private val prelabelledStore = AimlHistoricalOptionCorpusV1Store(application)
    private val summaryPrefs = application.getSharedPreferences(PREFS, 0)
    private val initialPrelabelled = prelabelledStore.ready()
    private val _state = MutableStateFlow(
        loadLastSummary(application).copy(
            localSummary = effectiveLocalSummary(),
            prelabelledCorpusReady = initialPrelabelled,
            prelabelledTrainRows = if (initialPrelabelled) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.TRAIN) else 0L,
            prelabelledValidationRows = if (initialPrelabelled) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION) else 0L,
            prelabelledTestRows = if (initialPrelabelled) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.TEST) else 0L,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var cancelRequested = false

    fun selectMonths(months: Int) {
        if (busy() || months !in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS) return
        val label = PrelabelledTrainingWindowPlan.label(months)
        _state.value = _state.value.copy(
            selectedMonths = months,
            result = null,
            installedCandidate = false,
            error = null,
            message = if (prelabelledStore.ready()) {
                "$label selected · fresh chronological TRAIN/calibration/scoring/locked-TEST roles will be derived from timestamps"
            } else {
                "$label ${_state.value.selectedMarketScope.label} research window selected · caches reused"
            },
        )
    }

    fun selectIndex(index: MarketIndex) =
        selectMarketScope(if (index == MarketIndex.NIFTY) HistoricalMarketScope.NIFTY else HistoricalMarketScope.SENSEX)

    fun selectMarketScope(scope: HistoricalMarketScope) {
        if (busy()) return
        _state.value = _state.value.copy(
            selectedMarketScope = scope,
            selectedIndex = scope.singleIndexOrNull() ?: _state.value.selectedIndex,
            result = null,
            installedCandidate = false,
            error = null,
            message = if (scope == HistoricalMarketScope.BOTH) {
                "BOTH selected · one shared Candidate and one shared chronological window for NIFTY + SENSEX"
            } else "${scope.label} historical AI corpus selected",
        )
    }

    fun selectSource(source: HistoricalCorpusSource) {
        if (busy()) return
        _state.value = _state.value.copy(
            selectedSource = source,
            result = null,
            installedCandidate = false,
            error = null,
            message = when (source) {
                HistoricalCorpusSource.UPSTOX -> "Upstox Plus expired-option corpus selected"
                HistoricalCorpusSource.LOCAL -> if (prelabelledStore.ready()) "Pre-labelled LOCAL corpus selected · timestamp roles will be re-derived" else "Local imported corpus selected · no Upstox token required"
                HistoricalCorpusSource.COMBINED -> if (prelabelledStore.ready()) "COMBINED selected · chronological pre-labelled champion + Upstox refinement" else "Combined corpus selected · local + Upstox deduplicated"
            },
        )
    }

    fun importLocalCorpus(uris: List<Uri>) {
        if (busy() || uris.isEmpty()) return
        cancelRequested = false
        _state.value = _state.value.copy(isImporting = true, stage = "IMPORT", importCompleted = 0, importTotal = uris.size, importMessage = "Detecting corpus format · memory-safe streaming enabled…", error = null)
        job = viewModelScope.launch {
            try {
                if (prelabelledStore.likelyPrelabelledCorpus(uris)) {
                    val imported = withContext(Dispatchers.IO) {
                        prelabelledStore.importUris(
                            uris = uris,
                            onProgress = { p -> _state.value = _state.value.copy(isImporting = true, stage = "IMPORT_PRELABELLED", importCompleted = p.completedFiles, importTotal = p.totalFiles, importMessage = p.message, error = null) },
                            shouldCancel = { cancelRequested },
                        )
                    }
                    if (imported.recognized) {
                        val ready = prelabelledStore.ready()
                        val scope = when {
                            ready && imported.summary.bothMarketsPresent -> HistoricalMarketScope.BOTH
                            ready && imported.summary.niftyContracts > 0 && imported.summary.sensexContracts == 0 -> HistoricalMarketScope.NIFTY
                            ready && imported.summary.sensexContracts > 0 && imported.summary.niftyContracts == 0 -> HistoricalMarketScope.SENSEX
                            else -> _state.value.selectedMarketScope
                        }
                        _state.value = _state.value.copy(
                            isImporting = false,
                            stage = if (ready) "IMPORT_COMPLETE" else "IMPORT_ERROR",
                            selectedSource = if (ready) HistoricalCorpusSource.LOCAL else _state.value.selectedSource,
                            selectedMarketScope = scope,
                            selectedIndex = scope.singleIndexOrNull() ?: _state.value.selectedIndex,
                            selectedMonths = if (ready) 12 else _state.value.selectedMonths,
                            localSummary = imported.summary,
                            importCompleted = uris.size,
                            importTotal = uris.size,
                            importMessage = imported.message,
                            message = if (ready) "Pre-labelled corpus ready · ${scope.label} · choose 1M/3M/6M/12M/FULL; roles derive from timestamps" else imported.message,
                            error = imported.summary.errors.lastOrNull(),
                            prelabelledCorpusReady = ready,
                            prelabelledTrainRows = imported.trainRows,
                            prelabelledValidationRows = imported.validationRows,
                            prelabelledTestRows = imported.testRows,
                        )
                        return@launch
                    }
                }

                val summary = withContext(Dispatchers.IO) {
                    localStore.importUris(
                        uris = uris,
                        onProgress = { p -> _state.value = _state.value.copy(isImporting = true, stage = "IMPORT_RAW", importCompleted = p.completedFiles, importTotal = p.totalFiles, importMessage = p.message, error = null) },
                        shouldCancel = { cancelRequested },
                    )
                }
                val autoScope = if (summary.bothMarketsPresent) HistoricalMarketScope.BOTH else _state.value.selectedMarketScope
                _state.value = _state.value.copy(
                    isImporting = false,
                    stage = "IMPORT_COMPLETE",
                    selectedSource = if (summary.trainable) HistoricalCorpusSource.LOCAL else _state.value.selectedSource,
                    selectedMarketScope = autoScope,
                    localSummary = summary,
                    importCompleted = uris.size,
                    importTotal = uris.size,
                    importMessage = "Import complete · ${summary.optionContracts} option contracts · ${summary.rowsAccepted} accepted rows",
                    message = if (summary.trainable) "Local raw-candle corpus ready · ${autoScope.label}" else "Import finished but no trainable CE/PE contracts were found",
                    error = null,
                    prelabelledCorpusReady = prelabelledStore.ready(),
                )
            } catch (error: Throwable) {
                val cancelled = cancelled(error)
                _state.value = _state.value.copy(
                    isImporting = false,
                    stage = if (cancelled) "IMPORT_CANCELLED" else "IMPORT_ERROR",
                    importMessage = if (cancelled) "Import cancelled safely · active corpus retained" else "Import stopped safely · previous active corpus retained",
                    localSummary = effectiveLocalSummary(),
                    error = if (cancelled) null else (error.message ?: error::class.java.simpleName).take(300),
                    prelabelledCorpusReady = prelabelledStore.ready(),
                )
            } finally {
                cancelRequested = false
                job = null
            }
        }
    }

    fun runOrResume() {
        if (busy()) return
        val source = _state.value.selectedSource
        val scope = _state.value.selectedMarketScope
        val months = _state.value.selectedMonths
        val label = PrelabelledTrainingWindowPlan.label(months)
        val token = vault.read().upstoxAccessToken.trim()
        val prelabelledReady = prelabelledStore.ready()

        if (source != HistoricalCorpusSource.LOCAL && token.isBlank()) {
            _state.value = _state.value.copy(error = "Save a valid Upstox access token first, or choose LOCAL corpus")
            return
        }
        if (source != HistoricalCorpusSource.UPSTOX && !effectiveLocalSummary().trainable) {
            _state.value = _state.value.copy(error = "Import a trainable CE/PE local corpus first, or choose UPSTOX source")
            return
        }
        if (scope == HistoricalMarketScope.BOTH && source != HistoricalCorpusSource.UPSTOX && !effectiveLocalSummary().bothMarketsPresent) {
            _state.value = _state.value.copy(error = "BOTH historical training requires NIFTY and SENSEX in the local corpus")
            return
        }
        if (months == PrelabelledTrainingWindowPlan.FULL && !prelabelledReady && source != HistoricalCorpusSource.UPSTOX) {
            _state.value = _state.value.copy(error = "FULL raw-local streaming is not enabled yet; choose 1M/3M/6M/12M or use the pre-labelled/UPSTOX source")
            return
        }

        cancelRequested = false
        _state.value = _state.value.copy(
            isRunning = true,
            stage = "STARTING",
            completed = 0,
            total = 0,
            result = null,
            installedCandidate = false,
            error = null,
            message = "Starting ${source.label} ${scope.label} $label historical AI research · Production frozen…",
        )

        job = viewModelScope.launch {
            var client: UpstoxPlusHistoricalClient? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    when {
                        scope == HistoricalMarketScope.BOTH && prelabelledReady && source == HistoricalCorpusSource.LOCAL -> runJointPrelabelled(months)
                        scope == HistoricalMarketScope.BOTH && prelabelledReady && source == HistoricalCorpusSource.COMBINED -> {
                            val historical = runJointPrelabelled(months)
                            val champion = historical.championState
                            if (champion == null) historical.copy(note = historical.note + " · Upstox refinement skipped because joint pre-labelled governance did not pass.")
                            else {
                                val pair = loadSeries(HistoricalCorpusSource.UPSTOX, scope, months)
                                client = pair.second
                                val merged = HistoricalSeriesMerger.merge(pair.first)
                                if (merged.isEmpty()) historical.copy(note = historical.note + " · No Upstox BOTH-market refinement series available.")
                                else {
                                    val refined = JointHistoricalSeriesTrainer(champion).run(
                                        series = merged,
                                        config = HistoricalCorpusTrainer.Config(months = months.toLong()),
                                        sourceLabel = "COMBINED UPSTOX BOTH REFINEMENT",
                                        onProgress = { publishProgress(it, client) },
                                        shouldCancel = { cancelRequested },
                                    )
                                    if (refined.championState != null) refined.copy(note = historical.note + " · Upstox BOTH refinement also passed. " + refined.note)
                                    else historical.copy(errors = historical.errors + refined.errors, note = historical.note + " · Upstox BOTH refinement did not clear governance; pre-labelled champion retained.")
                                }
                            }
                        }
                        scope == HistoricalMarketScope.BOTH -> runJointRawOrUpstox(source, months).also { client = it.second }.first
                        else -> {
                            val index = scope.singleIndexOrNull() ?: error("Invalid single-market scope")
                            when {
                                prelabelledReady && source == HistoricalCorpusSource.LOCAL -> runPrelabelled(index, months)
                                prelabelledReady && source == HistoricalCorpusSource.COMBINED -> {
                                    val historical = runPrelabelled(index, months)
                                    val champion = historical.championState
                                    if (champion == null) historical.copy(note = historical.note + " · Upstox refinement skipped because pre-labelled governance did not pass.")
                                    else {
                                        val pair = loadSeries(HistoricalCorpusSource.UPSTOX, scope, months)
                                        client = pair.second
                                        val merged = HistoricalSeriesMerger.merge(pair.first)
                                        if (merged.isEmpty()) historical.copy(note = historical.note + " · No Upstox refinement series available.")
                                        else {
                                            val refined = HistoricalSeriesTrainer(champion).run(
                                                index = index,
                                                series = merged,
                                                config = HistoricalCorpusTrainer.Config(months = months.toLong()),
                                                sourceLabel = "COMBINED UPSTOX REFINEMENT",
                                                onProgress = { publishProgress(it, client) },
                                                shouldCancel = { cancelRequested },
                                            )
                                            if (refined.championState != null) refined.copy(note = historical.note + " · Upstox refinement also passed. " + refined.note)
                                            else historical.copy(errors = historical.errors + refined.errors, note = historical.note + " · Upstox refinement did not clear governance; pre-labelled champion retained.")
                                        }
                                    }
                                }
                                else -> runSingleRawOrUpstox(source, index, months).also { client = it.second }.first
                            }
                        }
                    }
                }

                val origin = "${source.label} Historical Champion · ${scope.label} · $label"
                val install = result.championState?.let { MetaBrainRuntime.installHistoricalCandidate(it, origin) }
                val installed = install?.first == true
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                val finalMessage = when {
                    installed -> "Historical governance PASS · ${scope.label} $label champion installed as Candidate only · fresh NIFTY + SENSEX live validation required"
                    !result.lockedHoldoutOpened -> "Development governance not reached · locked holdout remained closed"
                    !result.lockedHoldoutPassed -> "Locked holdout/governance did not pass · Production unchanged"
                    else -> "Historical research complete · Production unchanged"
                }
                _state.value = _state.value.copy(
                    isRunning = false,
                    stage = "COMPLETE",
                    completed = _state.value.total.coerceAtLeast(_state.value.completed),
                    message = finalMessage,
                    result = result,
                    installedCandidate = installed,
                    error = result.errors.lastOrNull()?.take(220),
                    cacheHits = stats.cacheHits,
                    networkRequests = stats.requests,
                    localSummary = effectiveLocalSummary(),
                    prelabelledCorpusReady = prelabelledStore.ready(),
                )
                saveSummary(_state.value)
            } catch (error: Throwable) {
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                val wasCancelled = cancelled(error)
                _state.value = _state.value.copy(
                    isRunning = false,
                    stage = if (wasCancelled) "CANCELLED" else "ERROR",
                    message = if (wasCancelled) "Training stopped safely · corpus retained" else "Historical AI training stopped safely · corpus retained",
                    error = if (wasCancelled) null else (error.message ?: error::class.java.simpleName).take(300),
                    cacheHits = stats.cacheHits,
                    networkRequests = stats.requests,
                    localSummary = effectiveLocalSummary(),
                    prelabelledCorpusReady = prelabelledStore.ready(),
                )
            } finally {
                cancelRequested = false
                job = null
            }
        }
    }

    private fun runPrelabelled(index: MarketIndex, months: Int): HistoricalCorpusTrainer.Result =
        AimlHistoricalOptionCorpusV1Trainer(prelabelledStore, MetaBrainRuntime.productionSnapshotForResearch()).run(
            index = index,
            monthsLabel = months.toLong(),
            onProgress = { publishProgress(it, null) },
            shouldCancel = { cancelRequested },
        )

    private fun runJointPrelabelled(months: Int): HistoricalCorpusTrainer.Result =
        JointPrelabelledHistoricalTrainer(prelabelledStore, MetaBrainRuntime.productionSnapshotForResearch()).run(
            monthsLabel = months.toLong(),
            onProgress = { publishProgress(it, null) },
            shouldCancel = { cancelRequested },
        )

    private fun runSingleRawOrUpstox(source: HistoricalCorpusSource, index: MarketIndex, months: Int): Pair<HistoricalCorpusTrainer.Result, UpstoxPlusHistoricalClient?> {
        val pair = loadSeries(source, if (index == MarketIndex.NIFTY) HistoricalMarketScope.NIFTY else HistoricalMarketScope.SENSEX, months)
        val merged = HistoricalSeriesMerger.merge(pair.first)
        if (merged.isEmpty()) error("No trainable ${index.name} option series available from ${source.label}")
        val trained = HistoricalSeriesTrainer(MetaBrainRuntime.productionSnapshotForResearch()).run(
            index = index,
            series = merged,
            config = HistoricalCorpusTrainer.Config(months = months.toLong()),
            sourceLabel = "${source.label} ${PrelabelledTrainingWindowPlan.label(months)}",
            onProgress = { publishProgress(it, pair.second) },
            shouldCancel = { cancelRequested },
        )
        return trained to pair.second
    }

    private fun runJointRawOrUpstox(source: HistoricalCorpusSource, months: Int): Pair<HistoricalCorpusTrainer.Result, UpstoxPlusHistoricalClient?> {
        val pair = loadSeries(source, HistoricalMarketScope.BOTH, months)
        val merged = HistoricalSeriesMerger.merge(pair.first)
        if (merged.none { it.index == MarketIndex.NIFTY } || merged.none { it.index == MarketIndex.SENSEX }) error("BOTH historical research requires trainable NIFTY and SENSEX option series")
        val trained = JointHistoricalSeriesTrainer(MetaBrainRuntime.productionSnapshotForResearch()).run(
            series = merged,
            config = HistoricalCorpusTrainer.Config(months = months.toLong()),
            sourceLabel = "${source.label} ${PrelabelledTrainingWindowPlan.label(months)}",
            onProgress = { publishProgress(it, pair.second) },
            shouldCancel = { cancelRequested },
        )
        return trained to pair.second
    }

    private fun loadSeries(source: HistoricalCorpusSource, scope: HistoricalMarketScope, months: Int): Pair<List<HistoricalOptionSeries>, UpstoxPlusHistoricalClient?> {
        var client: UpstoxPlusHistoricalClient? = null
        var series = emptyList<HistoricalOptionSeries>()
        val errors = mutableListOf<String>()
        val markets = scope.singleIndexOrNull()?.let(::listOf) ?: MarketIndex.entries.toList()
        if (source == HistoricalCorpusSource.LOCAL || source == HistoricalCorpusSource.COMBINED) {
            require(months != PrelabelledTrainingWindowPlan.FULL) { "FULL raw-local streaming is not enabled" }
            markets.forEach { index ->
                series += localStore.loadSeriesWindow(index, months)
                _state.value = _state.value.copy(stage = "LOCAL_READY", message = "Local ${scope.label} corpus · ${series.size} contracts streamed")
            }
        }
        if (source == HistoricalCorpusSource.UPSTOX || source == HistoricalCorpusSource.COMBINED) {
            val token = vault.read().upstoxAccessToken.trim()
            client = UpstoxPlusHistoricalClient(accessToken = token, cacheDirectory = upstoxCacheDirectory)
            markets.forEach { index ->
                val loaded = UpstoxCorpusSeriesLoader(client).load(
                    index = index,
                    months = months.toLong(),
                    onProgress = { publishProgress(it, client) },
                    shouldCancel = { cancelRequested },
                )
                series += loaded.series
                errors += loaded.errors
            }
        }
        if (errors.isNotEmpty()) _state.value = _state.value.copy(message = "${_state.value.message} · source warnings ${errors.size}")
        return series to client
    }

    private fun publishProgress(progress: HistoricalCorpusTrainer.Progress, client: UpstoxPlusHistoricalClient?) {
        val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
        _state.value = _state.value.copy(
            isRunning = true,
            stage = progress.stage,
            completed = progress.completed,
            total = progress.total,
            message = if (client == null) progress.message else "${progress.message} · cache ${stats.cacheHits} · network ${stats.requests}",
            cacheHits = stats.cacheHits,
            networkRequests = stats.requests,
            error = null,
        )
    }

    fun cancel() {
        if (job?.isActive != true) return
        cancelRequested = true
        _state.value = _state.value.copy(message = "Cancel requested · finishing current read safely…", importMessage = "Cancel requested…")
    }

    fun clearUpstoxCache() {
        if (busy()) return
        runCatching { upstoxCacheDirectory.deleteRecursively(); upstoxCacheDirectory.mkdirs() }
            .onSuccess { _state.value = _state.value.copy(cacheHits = 0, networkRequests = 0, message = "Upstox historical candle cache cleared", error = null) }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear Upstox cache: ${it.message}") }
    }

    fun clearHistoricalCache() = clearUpstoxCache()

    fun clearLocalCorpus() {
        if (busy()) return
        runCatching { prelabelledStore.clear(); localStore.clear() }
            .onSuccess {
                _state.value = _state.value.copy(
                    localSummary = LocalCorpusSummary(), selectedSource = HistoricalCorpusSource.UPSTOX,
                    message = "Local historical corpus cleared · Production/Candidate unchanged", error = null,
                    prelabelledCorpusReady = false, prelabelledTrainRows = 0, prelabelledValidationRows = 0, prelabelledTestRows = 0,
                )
            }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear local corpus: ${it.message}") }
    }

    fun refreshLocalSummary() {
        if (busy()) return
        val ready = prelabelledStore.ready()
        _state.value = _state.value.copy(
            localSummary = effectiveLocalSummary(),
            prelabelledCorpusReady = ready,
            prelabelledTrainRows = if (ready) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.TRAIN) else 0L,
            prelabelledValidationRows = if (ready) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION) else 0L,
            prelabelledTestRows = if (ready) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.TEST) else 0L,
        )
    }

    private fun effectiveLocalSummary(): LocalCorpusSummary = if (prelabelledStore.ready()) prelabelledStore.summary() else localStore.summary()
    private fun busy(): Boolean = job?.isActive == true || _state.value.isRunning || _state.value.isImporting
    private fun cancelled(error: Throwable): Boolean = cancelRequested || error is kotlinx.coroutines.CancellationException || error.message?.contains("cancel", true) == true

    private fun saveSummary(state: UiState) {
        val r = state.result ?: return
        summaryPrefs.edit()
            .putInt("months", state.selectedMonths)
            .putString("index", state.selectedIndex.name)
            .putString("scope", state.selectedMarketScope.name)
            .putString("source", state.selectedSource.name)
            .putInt("samples", r.corpusSamples)
            .putBoolean("holdout_opened", r.lockedHoldoutOpened)
            .putBoolean("holdout_passed", r.lockedHoldoutPassed)
            .putBoolean("installed", state.installedCandidate)
            .putString("message", state.message)
            .apply()
    }

    override fun onCleared() {
        cancelRequested = true
        super.onCleared()
    }

    companion object {
        private const val PREFS = "vardhani_historical_ai_training_summary"

        private fun loadLastSummary(application: Application): UiState {
            val p = application.getSharedPreferences(PREFS, 0)
            if (!p.contains("samples")) return UiState()
            val index = runCatching { MarketIndex.valueOf(p.getString("index", "NIFTY") ?: "NIFTY") }.getOrDefault(MarketIndex.NIFTY)
            val scope = runCatching { HistoricalMarketScope.valueOf(p.getString("scope", null) ?: if (index == MarketIndex.NIFTY) "NIFTY" else "SENSEX") }.getOrDefault(HistoricalMarketScope.BOTH)
            val source = runCatching { HistoricalCorpusSource.valueOf(p.getString("source", "UPSTOX") ?: "UPSTOX") }.getOrDefault(HistoricalCorpusSource.UPSTOX)
            val months = p.getInt("months", 1).takeIf { it in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS } ?: 1
            val samples = p.getInt("samples", 0)
            val passed = p.getBoolean("holdout_passed", false)
            val installed = p.getBoolean("installed", false)
            return UiState(
                selectedMonths = months,
                selectedIndex = index,
                selectedMarketScope = scope,
                selectedSource = source,
                message = "Last ${source.label} ${scope.label} ${PrelabelledTrainingWindowPlan.label(months)}: $samples samples · holdout ${if (passed) "PASS" else "not passed"}${if (installed) " · Candidate installed" else ""}",
                installedCandidate = installed,
            )
        }
    }
}
