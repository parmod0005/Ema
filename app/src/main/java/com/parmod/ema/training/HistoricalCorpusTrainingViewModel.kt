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
        val selectedSource: HistoricalCorpusSource = HistoricalCorpusSource.UPSTOX,
        val isRunning: Boolean = false,
        val isImporting: Boolean = false,
        val stage: String = "IDLE",
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "Ready · choose Upstox, Local or Combined corpus",
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
        val importProgress: Float get() = if (importTotal <= 0) 0f else completedFraction(importCompleted, importTotal)
        private fun completedFraction(done: Int, all: Int): Float = if (all <= 0) 0f else (done.toFloat() / all.toFloat()).coerceIn(0f, 1f)
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
        if (busy() || months !in setOf(1, 3, 6, 12)) return
        _state.value = _state.value.copy(
            selectedMonths = months,
            result = null,
            installedCandidate = false,
            error = null,
            message = if (prelabelledStore.ready()) {
                "Pre-labelled corpus preserves its original train/validation/test split; ${months}M is a display/research label only for that corpus"
            } else {
                "${months}M research window selected · cached and imported data will be reused"
            },
        )
    }

    fun selectIndex(index: MarketIndex) {
        if (busy()) return
        _state.value = _state.value.copy(selectedIndex = index, result = null, installedCandidate = false, error = null, message = "${index.name} historical AI corpus selected")
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
                HistoricalCorpusSource.LOCAL -> if (prelabelledStore.ready()) "Pre-labelled LOCAL corpus selected · original train/validation/test split will be preserved" else "Local imported corpus selected · no Upstox token required"
                HistoricalCorpusSource.COMBINED -> if (prelabelledStore.ready()) "COMBINED selected · pre-labelled champion first, then recent Upstox refinement if available" else "Combined corpus selected · local + Upstox data will be deduplicated"
            },
        )
    }

    fun importLocalCorpus(uris: List<Uri>) {
        if (busy() || uris.isEmpty()) return
        cancelRequested = false
        _state.value = _state.value.copy(isImporting = true, stage = "IMPORT", importCompleted = 0, importTotal = uris.size, importMessage = "Detecting corpus format · memory-safe streaming enabled…", error = null)
        job = viewModelScope.launch {
            try {
                val specialized = prelabelledStore.likelyPrelabelledCorpus(uris)
                if (specialized) {
                    val imported = withContext(Dispatchers.IO) {
                        prelabelledStore.importUris(
                            uris = uris,
                            onProgress = { p ->
                                _state.value = _state.value.copy(isImporting = true, stage = "IMPORT_PRELABELLED", importCompleted = p.completedFiles, importTotal = p.totalFiles, importMessage = p.message, error = null)
                            },
                            shouldCancel = { cancelRequested },
                        )
                    }
                    if (imported.recognized) {
                        val ready = prelabelledStore.ready()
                        _state.value = _state.value.copy(
                            isImporting = false,
                            stage = if (ready) "IMPORT_COMPLETE" else "IMPORT_ERROR",
                            selectedSource = if (ready) HistoricalCorpusSource.LOCAL else _state.value.selectedSource,
                            selectedIndex = if (ready && imported.summary.niftyContracts > 0 && imported.summary.sensexContracts == 0) MarketIndex.NIFTY else _state.value.selectedIndex,
                            selectedMonths = if (ready) 12 else _state.value.selectedMonths,
                            localSummary = imported.summary,
                            importCompleted = uris.size,
                            importTotal = uris.size,
                            importMessage = imported.message,
                            message = if (ready) "Pre-labelled historical corpus ready · original train/validation/test split locked for research" else imported.message,
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
                        onProgress = { p ->
                            _state.value = _state.value.copy(isImporting = true, stage = "IMPORT_RAW", importCompleted = p.completedFiles, importTotal = p.totalFiles, importMessage = p.message, error = null)
                        },
                        shouldCancel = { cancelRequested },
                    )
                }
                _state.value = _state.value.copy(
                    isImporting = false,
                    stage = "IMPORT_COMPLETE",
                    selectedSource = if (summary.trainable) HistoricalCorpusSource.LOCAL else _state.value.selectedSource,
                    localSummary = summary,
                    importCompleted = uris.size,
                    importTotal = uris.size,
                    importMessage = "Import complete · ${summary.optionContracts} option contracts · ${summary.rowsAccepted} accepted rows",
                    message = if (summary.trainable) "Local raw-candle corpus ready for validation/training" else "Import finished but no trainable CE/PE option contracts were found",
                    error = null,
                    prelabelledCorpusReady = prelabelledStore.ready(),
                )
            } catch (error: Throwable) {
                val cancelled = cancelRequested || error is kotlinx.coroutines.CancellationException || error.message?.contains("cancel", true) == true
                _state.value = _state.value.copy(
                    isImporting = false,
                    stage = if (cancelled) "IMPORT_CANCELLED" else "IMPORT_ERROR",
                    importMessage = if (cancelled) "Import cancelled safely · completed active corpus retained" else "Import stopped safely · previous active corpus retained",
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

        val months = _state.value.selectedMonths
        val index = _state.value.selectedIndex
        cancelRequested = false
        _state.value = _state.value.copy(
            isRunning = true,
            stage = "STARTING",
            completed = 0,
            total = 0,
            result = null,
            installedCandidate = false,
            error = null,
            message = when {
                prelabelledReady && source == HistoricalCorpusSource.LOCAL -> "Starting pre-labelled ${index.name} train → validation → locked-test research…"
                prelabelledReady && source == HistoricalCorpusSource.COMBINED -> "Starting pre-labelled champion search before recent Upstox refinement…"
                else -> "Starting ${source.label} causal ${months}M ${index.name} option-premium research…"
            },
        )

        job = viewModelScope.launch {
            var client: UpstoxPlusHistoricalClient? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    when {
                        prelabelledReady && source == HistoricalCorpusSource.LOCAL -> runPrelabelled(index, months)
                        prelabelledReady && source == HistoricalCorpusSource.COMBINED -> {
                            val historical = runPrelabelled(index, months)
                            val champion = historical.championState
                            if (champion == null) {
                                historical.copy(note = historical.note + " · COMBINED refinement skipped because the pre-labelled candidate did not pass strict historical governance.")
                            } else {
                                client = UpstoxPlusHistoricalClient(accessToken = token, cacheDirectory = upstoxCacheDirectory)
                                val loaded = UpstoxCorpusSeriesLoader(client!!).load(index = index, months = months.toLong(), onProgress = { p -> publishProgress(p, client) }, shouldCancel = { cancelRequested })
                                val merged = HistoricalSeriesMerger.merge(loaded.series)
                                if (merged.isEmpty()) {
                                    historical.copy(errors = historical.errors + loaded.errors, note = historical.note + " · No recent Upstox refinement series were available; pre-labelled champion retained as Candidate result.")
                                } else {
                                    val refined = HistoricalSeriesTrainer(productionBaseline = champion).run(
                                        index = index,
                                        series = merged,
                                        config = HistoricalCorpusTrainer.Config(months = months.toLong()),
                                        sourceLabel = "COMBINED RECENT UPSTOX REFINEMENT",
                                        onProgress = { p -> publishProgress(p, client) },
                                        shouldCancel = { cancelRequested },
                                    )
                                    if (refined.championState != null) {
                                        refined.copy(errors = refined.errors + loaded.errors, note = historical.note + " · Then recent Upstox causal refinement passed strict historical governance. " + refined.note)
                                    } else {
                                        historical.copy(errors = historical.errors + loaded.errors + refined.errors, note = historical.note + " · Recent Upstox refinement did not clear strict governance, so the original pre-labelled champion was retained.")
                                    }
                                }
                            }
                        }
                        else -> runRawOrUpstox(source, index, months).also { client = it.second }.first
                    }
                }

                val origin = when {
                    prelabelledReady && source == HistoricalCorpusSource.LOCAL -> "Pre-labelled Historical Champion · ${index.name} · original train/validation/test"
                    prelabelledReady && source == HistoricalCorpusSource.COMBINED -> "Combined Historical Champion · pre-labelled + recent Upstox"
                    else -> "${source.label} Historical WF Champion · ${index.name} ${months}M"
                }
                val install = result.championState?.let { champion -> MetaBrainRuntime.installHistoricalCandidate(champion, origin) }
                val installed = install?.first == true
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                val governance = HistoricalCandidateGovernance.evaluate(
                    candidate = result.holdoutCandidate,
                    production = result.holdoutProduction,
                    coverage = result.coverage,
                    corpusSamples = result.corpusSamples,
                    holdoutOpened = result.lockedHoldoutOpened,
                )
                val finalMessage = when {
                    installed -> "Historical governance PASS · champion installed as Candidate only · now collect fresh live unseen labels"
                    governance.status == HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA -> "Historical governance INSUFFICIENT DATA · ${governance.reasons.firstOrNull().orEmpty()}"
                    governance.status == HistoricalCandidateGovernance.Status.FAIL -> "Historical governance FAIL · ${governance.reasons.firstOrNull().orEmpty()}"
                    !result.lockedHoldoutOpened -> "Validation robustness not reached · locked holdout remained closed"
                    else -> "Historical training complete"
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
                val cancelled = cancelRequested || error is kotlinx.coroutines.CancellationException || error.message?.contains("cancel", true) == true
                _state.value = _state.value.copy(
                    isRunning = false,
                    stage = if (cancelled) "CANCELLED" else "ERROR",
                    message = if (cancelled) "Training stopped safely · imported/downloaded corpus retained" else "Historical AI training stopped safely · corpus retained",
                    error = if (cancelled) null else (error.message ?: error::class.java.simpleName).take(300),
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
        AimlHistoricalOptionCorpusV1Trainer(
            store = prelabelledStore,
            productionBaseline = MetaBrainRuntime.productionSnapshotForResearch(),
        ).run(index = index, monthsLabel = months.toLong(), onProgress = { p -> publishProgress(p, null) }, shouldCancel = { cancelRequested })

    private fun runRawOrUpstox(
        source: HistoricalCorpusSource,
        index: MarketIndex,
        months: Int,
    ): Pair<HistoricalCorpusTrainer.Result, UpstoxPlusHistoricalClient?> {
        var client: UpstoxPlusHistoricalClient? = null
        var series = emptyList<HistoricalOptionSeries>()
        var sourceErrors = emptyList<String>()
        if (source == HistoricalCorpusSource.LOCAL || source == HistoricalCorpusSource.COMBINED) {
            val local = localStore.loadSeriesWindow(index, months)
            series = series + local
            _state.value = _state.value.copy(stage = "LOCAL_READY", message = "Local raw-candle corpus · ${local.size} contracts streamed for ${months}M window")
        }
        if (source == HistoricalCorpusSource.UPSTOX || source == HistoricalCorpusSource.COMBINED) {
            val token = vault.read().upstoxAccessToken.trim()
            client = UpstoxPlusHistoricalClient(accessToken = token, cacheDirectory = upstoxCacheDirectory)
            val loaded = UpstoxCorpusSeriesLoader(client).load(index = index, months = months.toLong(), onProgress = { p -> publishProgress(p, client) }, shouldCancel = { cancelRequested })
            series = series + loaded.series
            sourceErrors = sourceErrors + loaded.errors
        }
        val merged = HistoricalSeriesMerger.merge(series)
        if (merged.isEmpty()) error("No trainable ${index.name} option series available from ${source.label}")
        val trained = HistoricalSeriesTrainer(productionBaseline = MetaBrainRuntime.productionSnapshotForResearch()).run(
            index = index,
            series = merged,
            config = HistoricalCorpusTrainer.Config(months = months.toLong()),
            sourceLabel = source.label,
            onProgress = { p -> publishProgress(p, client) },
            shouldCancel = { cancelRequested },
        )
        return (if (sourceErrors.isEmpty()) trained else trained.copy(errors = trained.errors + sourceErrors)) to client
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
        runCatching {
            prelabelledStore.clear()
            localStore.clear()
        }.onSuccess {
            _state.value = _state.value.copy(
                localSummary = LocalCorpusSummary(),
                selectedSource = HistoricalCorpusSource.UPSTOX,
                message = "All local historical corpus caches cleared · Production/Candidate models were not changed",
                error = null,
                prelabelledCorpusReady = false,
                prelabelledTrainRows = 0,
                prelabelledValidationRows = 0,
                prelabelledTestRows = 0,
            )
        }.onFailure { _state.value = _state.value.copy(error = "Could not clear local corpus: ${it.message}") }
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

    private fun saveSummary(state: UiState) {
        val r = state.result ?: return
        summaryPrefs.edit()
            .putInt("months", state.selectedMonths)
            .putString("index", state.selectedIndex.name)
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
            val source = runCatching { HistoricalCorpusSource.valueOf(p.getString("source", "UPSTOX") ?: "UPSTOX") }.getOrDefault(HistoricalCorpusSource.UPSTOX)
            val samples = p.getInt("samples", 0)
            val passed = p.getBoolean("holdout_passed", false)
            val installed = p.getBoolean("installed", false)
            return UiState(
                selectedMonths = p.getInt("months", 1).takeIf { it in setOf(1, 3, 6, 12) } ?: 1,
                selectedIndex = index,
                selectedSource = source,
                message = "Last ${source.label} corpus: $samples samples · holdout ${if (passed) "PASS" else "not passed"}${if (installed) " · Candidate installed" else ""}",
                installedCandidate = installed,
            )
        }
    }
}
