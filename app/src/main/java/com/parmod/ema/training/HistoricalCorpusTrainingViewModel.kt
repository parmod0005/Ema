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
import java.time.LocalDate

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
    ) {
        val progress: Float
            get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val importProgress: Float
            get() = if (importTotal <= 0) 0f else (importCompleted.toFloat() / importTotal.toFloat()).coerceIn(0f, 1f)
    }

    private val vault = LocalCredentialVault(application)
    private val upstoxCacheDirectory = File(application.filesDir, "upstox_backtest_cache/v1")
    private val localStore = LocalHistoricalCorpusStore(application)
    private val summaryPrefs = application.getSharedPreferences(PREFS, 0)
    private val _state = MutableStateFlow(loadLastSummary(application).copy(localSummary = localStore.summary()))
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
            message = "${months}M research window selected · cached and imported data will be reused",
        )
    }

    fun selectIndex(index: MarketIndex) {
        if (busy()) return
        _state.value = _state.value.copy(
            selectedIndex = index,
            result = null,
            installedCandidate = false,
            error = null,
            message = "${index.name} historical AI corpus selected",
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
                HistoricalCorpusSource.LOCAL -> "Local imported corpus selected · no Upstox token required"
                HistoricalCorpusSource.COMBINED -> "Combined corpus selected · local + Upstox data will be deduplicated"
            },
        )
    }

    fun importLocalCorpus(uris: List<Uri>) {
        if (busy() || uris.isEmpty()) return
        cancelRequested = false
        _state.value = _state.value.copy(
            isImporting = true,
            stage = "IMPORT",
            importCompleted = 0,
            importTotal = uris.size,
            importMessage = "Starting local corpus import…",
            error = null,
        )
        job = viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    localStore.importUris(
                        uris = uris,
                        onProgress = { p ->
                            _state.value = _state.value.copy(
                                isImporting = true,
                                stage = "IMPORT",
                                importCompleted = p.completedFiles,
                                importTotal = p.totalFiles,
                                importMessage = p.message,
                                error = null,
                            )
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
                    importMessage = "Import complete · ${summary.optionContracts} option contracts · ${summary.rowsAccepted} accepted candles",
                    message = if (summary.trainable) "Local corpus ready for validation/training" else "Import finished but no trainable CE/PE option contracts were found",
                    error = summary.errors.lastOrNull(),
                )
            } catch (error: Throwable) {
                val cancelled = cancelRequested || error is kotlinx.coroutines.CancellationException || error.message?.contains("cancel", true) == true
                _state.value = _state.value.copy(
                    isImporting = false,
                    stage = if (cancelled) "IMPORT_CANCELLED" else "IMPORT_ERROR",
                    importMessage = if (cancelled) "Import cancelled safely · completed normalized contracts were retained" else "Import stopped safely",
                    localSummary = localStore.summary(),
                    error = if (cancelled) null else (error.message ?: error::class.java.simpleName).take(300),
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
        if (source != HistoricalCorpusSource.LOCAL && token.isBlank()) {
            _state.value = _state.value.copy(error = "Save a valid Upstox access token first, or choose LOCAL corpus")
            return
        }
        if (source != HistoricalCorpusSource.UPSTOX && !_state.value.localSummary.trainable) {
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
            message = "Starting ${source.label} causal ${months}M ${index.name} option-premium research…",
        )

        job = viewModelScope.launch {
            var client: UpstoxPlusHistoricalClient? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    var series = emptyList<HistoricalOptionSeries>()
                    var sourceErrors = emptyList<String>()
                    if (source == HistoricalCorpusSource.LOCAL || source == HistoricalCorpusSource.COMBINED) {
                        val local = trimToMostRecentMonths(localStore.loadSeries(index), months)
                        series = series + local
                        _state.value = _state.value.copy(
                            stage = "LOCAL_READY",
                            message = "Local corpus · ${local.size} contracts selected for ${months}M window",
                        )
                    }
                    if (source == HistoricalCorpusSource.UPSTOX || source == HistoricalCorpusSource.COMBINED) {
                        client = UpstoxPlusHistoricalClient(
                            accessToken = token,
                            cacheDirectory = upstoxCacheDirectory,
                        )
                        val loaded = UpstoxCorpusSeriesLoader(client!!).load(
                            index = index,
                            months = months.toLong(),
                            onProgress = { progress -> publishProgress(progress, client) },
                            shouldCancel = { cancelRequested },
                        )
                        series = series + loaded.series
                        sourceErrors = sourceErrors + loaded.errors
                    }
                    val merged = HistoricalSeriesMerger.merge(series)
                    if (merged.isEmpty()) error("No trainable ${index.name} option series available from ${source.label}")
                    val trainer = HistoricalSeriesTrainer(
                        productionBaseline = MetaBrainRuntime.productionSnapshotForResearch(),
                    )
                    val trained = trainer.run(
                        index = index,
                        series = merged,
                        config = HistoricalCorpusTrainer.Config(months = months.toLong()),
                        sourceLabel = source.label,
                        onProgress = { progress -> publishProgress(progress, client) },
                        shouldCancel = { cancelRequested },
                    )
                    if (sourceErrors.isEmpty()) trained else trained.copy(errors = trained.errors + sourceErrors)
                }

                val install = result.championState?.let { champion ->
                    MetaBrainRuntime.installHistoricalCandidate(
                        champion,
                        "${source.label} Historical WF Champion · ${index.name} ${months}M",
                    )
                }
                val installed = install?.first == true
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                val finalMessage = when {
                    installed -> "${source.label} champion installed as Candidate only · now collect fresh live unseen labels"
                    result.lockedHoldoutOpened && !result.lockedHoldoutPassed -> "Locked holdout failed · Production and current Candidate were not replaced"
                    !result.lockedHoldoutOpened -> "Walk-forward robustness not reached · locked holdout remained closed"
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
                    localSummary = localStore.summary(),
                )
                saveSummary(_state.value)
            } catch (error: Throwable) {
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                val cancelled = cancelRequested || error is kotlinx.coroutines.CancellationException || error.message?.contains("cancel", true) == true
                _state.value = _state.value.copy(
                    isRunning = false,
                    stage = if (cancelled) "CANCELLED" else "ERROR",
                    message = if (cancelled) "Training stopped safely · downloaded/imported corpus retained for resume" else "Historical AI training stopped safely · corpus retained",
                    error = if (cancelled) null else (error.message ?: error::class.java.simpleName).take(300),
                    cacheHits = stats.cacheHits,
                    networkRequests = stats.requests,
                    localSummary = localStore.summary(),
                )
            } finally {
                cancelRequested = false
                job = null
            }
        }
    }

    private fun publishProgress(progress: HistoricalCorpusTrainer.Progress, client: UpstoxPlusHistoricalClient?) {
        val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
        _state.value = _state.value.copy(
            isRunning = true,
            stage = progress.stage,
            completed = progress.completed,
            total = progress.total,
            message = "${progress.message} · cache ${stats.cacheHits} · network ${stats.requests}",
            cacheHits = stats.cacheHits,
            networkRequests = stats.requests,
            error = null,
        )
    }

    private fun trimToMostRecentMonths(series: List<HistoricalOptionSeries>, months: Int): List<HistoricalOptionSeries> {
        val maxDate = series.flatMap { it.candles }.maxOfOrNull { it.time.toLocalDate() } ?: return emptyList()
        val cutoff = maxDate.minusMonths(months.toLong())
        return series.mapNotNull { s ->
            val candles = s.candles.filter { !it.time.toLocalDate().isBefore(cutoff) && !it.time.toLocalDate().isAfter(maxDate) }
            s.copy(candles = candles).takeIf { candles.isNotEmpty() }
        }
    }

    fun cancel() {
        if (job?.isActive != true) return
        cancelRequested = true
        _state.value = _state.value.copy(message = "Cancel requested · finishing current read safely…", importMessage = "Cancel requested…")
    }

    fun clearUpstoxCache() {
        if (busy()) return
        runCatching { upstoxCacheDirectory.deleteRecursively(); upstoxCacheDirectory.mkdirs() }
            .onSuccess {
                _state.value = _state.value.copy(cacheHits = 0, networkRequests = 0, message = "Upstox historical candle cache cleared", error = null)
            }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear Upstox cache: ${it.message}") }
    }

    fun clearHistoricalCache() = clearUpstoxCache()

    fun clearLocalCorpus() {
        if (busy()) return
        runCatching { localStore.clear() }
            .onSuccess {
                _state.value = _state.value.copy(
                    localSummary = LocalCorpusSummary(),
                    selectedSource = HistoricalCorpusSource.UPSTOX,
                    message = "Local imported corpus cleared · Production/Candidate models were not changed",
                    error = null,
                )
            }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear local corpus: ${it.message}") }
    }

    fun refreshLocalSummary() {
        if (busy()) return
        _state.value = _state.value.copy(localSummary = localStore.summary())
    }

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
