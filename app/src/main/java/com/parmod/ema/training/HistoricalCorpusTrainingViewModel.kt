package com.parmod.ema.training

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.NumericalMetaBrain
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
        val isDownloading: Boolean = false,
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
        val downloadedSummary: LocalCorpusSummary = LocalCorpusSummary(),
        val importCompleted: Int = 0,
        val importTotal: Int = 0,
        val importMessage: String = "No local import running",
        val downloadStage: String = "IDLE",
        val downloadCompleted: Int = 0,
        val downloadTotal: Int = 0,
        val downloadMessage: String = "No historical download running",
        val downloadStrikeRadius: Int = HistoricalCorpusDownloadManager.DEFAULT_STRIKES_EACH_SIDE,
        val downloadCacheHits: Long = 0,
        val downloadNetworkRequests: Long = 0,
        val downloadErrors: Int = 0,
        val prelabelledCorpusReady: Boolean = false,
        val prelabelledTrainRows: Long = 0,
        val prelabelledValidationRows: Long = 0,
        val prelabelledTestRows: Long = 0,
        val checkpointAvailable: Boolean = false,
        val liveArchiveRecords: Int = 0,
        val liveArchiveDuplicates: Int = 0,
    ) {
        val progress: Float get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val importProgress: Float get() = if (importTotal <= 0) 0f else (importCompleted.toFloat() / importTotal.toFloat()).coerceIn(0f, 1f)
        val downloadProgress: Float get() = if (downloadTotal <= 0) 0f else (downloadCompleted.toFloat() / downloadTotal.toFloat()).coerceIn(0f, 1f)
        val windowLabel: String get() = PrelabelledTrainingWindowPlan.label(selectedMonths)
    }

    private val vault = LocalCredentialVault(application)
    private val upstoxCacheDirectory = File(application.filesDir, "upstox_backtest_cache/v1")
    private val localStore = StreamingLocalHistoricalCorpusStore(application)
    private val downloadedStore = DownloadedHistoricalCorpusStore(application)
    private val downloadManager = HistoricalCorpusDownloadManager(application, downloadedStore, upstoxCacheDirectory)
    private val prelabelledStore = AimlHistoricalOptionCorpusV1Store(application)
    private val checkpointStore = HistoricalTrainingCheckpointStore(application)
    private val liveArchiveStore = LiveArchiveTrainingStore(application)
    private val summaryPrefs = application.getSharedPreferences(PREFS, 0)
    private val initialPrelabelled = prelabelledStore.ready()

    init { LiveResearchArchive.initialize(application) }

    private val _state = MutableStateFlow(
        loadLastSummary(application).copy(
            localSummary = effectiveLocalSummary(),
            downloadedSummary = downloadedStore.summary(),
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
        _state.value = _state.value.copy(
            selectedMonths = months, result = null, installedCandidate = false, error = null,
            checkpointAvailable = checkpointFor(_state.value.selectedMarketScope, months),
            message = "${PrelabelledTrainingWindowPlan.label(months)} ${_state.value.selectedMarketScope.label} research window selected · chronological roles + ${PrelabelledTrainingWindowPlan.EMBARGO_MINUTES}m embargo",
        )
    }

    fun selectIndex(index: MarketIndex) = selectMarketScope(if (index == MarketIndex.NIFTY) HistoricalMarketScope.NIFTY else HistoricalMarketScope.SENSEX)

    fun selectMarketScope(scope: HistoricalMarketScope) {
        if (busy()) return
        _state.value = _state.value.copy(
            selectedMarketScope = scope,
            selectedIndex = scope.singleIndexOrNull() ?: _state.value.selectedIndex,
            result = null, installedCandidate = false, error = null,
            checkpointAvailable = checkpointFor(scope, _state.value.selectedMonths),
            message = if (scope == HistoricalMarketScope.BOTH) "BOTH selected · one shared Candidate and shared timestamps for NIFTY + SENSEX" else "${scope.label} historical AI corpus selected",
        )
    }

    fun selectSource(source: HistoricalCorpusSource) {
        if (busy()) return
        _state.value = _state.value.copy(
            selectedSource = source, result = null, installedCandidate = false, error = null,
            message = when (source) {
                HistoricalCorpusSource.UPSTOX -> "Upstox Plus expired-option corpus selected"
                HistoricalCorpusSource.LOCAL -> if (prelabelledStore.ready()) "Pre-labelled LOCAL corpus selected · timestamp roles re-derived" else "Local imported corpus selected · no Upstox token required"
                HistoricalCorpusSource.DOWNLOADED -> "DOWNLOADED selected · persistent phone NIFTY/SENSEX 1-minute expired-option corpus · no network required for training"
                HistoricalCorpusSource.LIVE_ARCHIVE -> "LIVE ARCHIVE selected · exact saved feature vectors + actual resolved outcomes · no broker token required"
                HistoricalCorpusSource.COMBINED -> "COMBINED selected · historical/local + LIVE ARCHIVE + Upstox refinement with source-level dedupe"
            },
        )
    }

    fun selectDownloadStrikeRadius(radius: Int) {
        if (busy() || radius !in HistoricalCorpusDownloadManager.ALLOWED_STRIKE_RADII) return
        _state.value = _state.value.copy(
            downloadStrikeRadius = radius,
            downloadMessage = "Download density selected · ${radius * 2 + 1} centre strikes/expiry × CE/PE",
            error = null,
        )
    }

    fun downloadHistoricalCorpus() {
        if (busy()) return
        val token = vault.read().upstoxAccessToken.trim()
        if (token.isBlank()) {
            _state.value = _state.value.copy(error = "Save a valid Upstox access token before downloading historical data")
            return
        }
        val scope = _state.value.selectedMarketScope
        val months = _state.value.selectedMonths
        val radius = _state.value.downloadStrikeRadius
        cancelRequested = false
        _state.value = _state.value.copy(
            isDownloading = true,
            downloadStage = "STARTING",
            downloadCompleted = 0,
            downloadTotal = 0,
            downloadMessage = "Starting / resuming ${scope.label} ${PrelabelledTrainingWindowPlan.label(months)} historical download…",
            downloadErrors = 0,
            error = null,
        )
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    downloadManager.download(
                        accessToken = token,
                        scope = scope,
                        months = months,
                        strikesEachSide = radius,
                        onProgress = { p ->
                            _state.value = _state.value.copy(
                                isDownloading = true,
                                downloadStage = p.stage,
                                downloadCompleted = p.completed,
                                downloadTotal = p.total,
                                downloadMessage = p.message,
                                downloadCacheHits = p.cacheHits,
                                downloadNetworkRequests = p.networkRequests,
                                error = null,
                            )
                        },
                        shouldCancel = { cancelRequested },
                    )
                }
                val bothReady = result.summary.bothMarketsPresent
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadStage = "COMPLETE",
                    downloadCompleted = result.contractsPlanned,
                    downloadTotal = result.contractsPlanned,
                    downloadMessage = "Download complete · ${result.contractsDownloaded} new request(s) · ${result.contractsSkipped} resume skip(s) · NIFTY ${result.summary.niftyContracts} · SENSEX ${result.summary.sensexContracts}",
                    downloadedSummary = result.summary,
                    selectedSource = HistoricalCorpusSource.DOWNLOADED,
                    downloadCacheHits = result.stats.cacheHits,
                    downloadNetworkRequests = result.stats.requests,
                    downloadErrors = result.errors.size,
                    result = null,
                    installedCandidate = false,
                    message = if (bothReady) "Downloaded NIFTY + SENSEX corpus ready · select BOTH and RUN / RESUME DOWNLOADED training" else "Downloaded corpus updated · ${if (result.summary.niftyContracts == 0) "NIFTY missing" else "NIFTY ready"} · ${if (result.summary.sensexContracts == 0) "SENSEX missing" else "SENSEX ready"}",
                    error = result.errors.lastOrNull()?.take(260),
                )
            } catch (error: Throwable) {
                val wasCancelled = cancelled(error)
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadStage = if (wasCancelled) "CANCELLED" else "ERROR",
                    downloadMessage = if (wasCancelled) "Historical download stopped safely · completed contracts retained · press DOWNLOAD / RESUME to continue" else "Historical download stopped safely · completed contracts retained",
                    downloadedSummary = downloadedStore.summary(),
                    error = if (wasCancelled) null else (error.message ?: error::class.java.simpleName).take(300),
                )
            } finally { cancelRequested = false; job = null }
        }
    }

    fun clearDownloadedCorpus() {
        if (busy()) return
        runCatching { downloadManager.clearDownloadedCorpus() }
            .onSuccess {
                _state.value = _state.value.copy(
                    downloadedSummary = LocalCorpusSummary(),
                    downloadCompleted = 0,
                    downloadTotal = 0,
                    downloadErrors = 0,
                    downloadMessage = "Downloaded NIFTY/SENSEX corpus cleared · imported/pre-labelled/live archive data unchanged",
                    selectedSource = if (_state.value.selectedSource == HistoricalCorpusSource.DOWNLOADED) HistoricalCorpusSource.UPSTOX else _state.value.selectedSource,
                    error = null,
                )
            }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear downloaded historical corpus: ${it.message}") }
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
                            isImporting = false, stage = if (ready) "IMPORT_COMPLETE" else "IMPORT_ERROR",
                            selectedSource = if (ready) HistoricalCorpusSource.LOCAL else _state.value.selectedSource,
                            selectedMarketScope = scope, selectedIndex = scope.singleIndexOrNull() ?: _state.value.selectedIndex,
                            selectedMonths = if (ready) 12 else _state.value.selectedMonths,
                            localSummary = imported.summary, importCompleted = uris.size, importTotal = uris.size,
                            importMessage = imported.message,
                            message = if (ready) "Pre-labelled corpus ready · ${scope.label} · 1M/3M/6M/12M/FULL roles derive from timestamps" else imported.message,
                            error = imported.summary.errors.lastOrNull(), prelabelledCorpusReady = ready,
                            prelabelledTrainRows = imported.trainRows, prelabelledValidationRows = imported.validationRows, prelabelledTestRows = imported.testRows,
                            checkpointAvailable = false,
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
                    isImporting = false, stage = "IMPORT_COMPLETE",
                    selectedSource = if (summary.trainable) HistoricalCorpusSource.LOCAL else _state.value.selectedSource,
                    selectedMarketScope = autoScope, localSummary = summary,
                    importCompleted = uris.size, importTotal = uris.size,
                    importMessage = "Import complete · ${summary.optionContracts} option contracts · ${summary.rowsAccepted} accepted rows",
                    message = if (summary.trainable) "Local raw-candle corpus ready · ${autoScope.label}" else "Import finished but no trainable CE/PE contracts were found",
                    error = null, prelabelledCorpusReady = prelabelledStore.ready(), checkpointAvailable = false,
                )
            } catch (error: Throwable) {
                val wasCancelled = cancelled(error)
                _state.value = _state.value.copy(
                    isImporting = false, stage = if (wasCancelled) "IMPORT_CANCELLED" else "IMPORT_ERROR",
                    importMessage = if (wasCancelled) "Import cancelled safely · active corpus retained" else "Import stopped safely · previous active corpus retained",
                    localSummary = effectiveLocalSummary(), error = if (wasCancelled) null else (error.message ?: error::class.java.simpleName).take(300),
                    prelabelledCorpusReady = prelabelledStore.ready(),
                )
            } finally { cancelRequested = false; job = null }
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
        val downloaded = downloadedStore.summary()

        if (source in setOf(HistoricalCorpusSource.UPSTOX, HistoricalCorpusSource.COMBINED) && token.isBlank()) {
            _state.value = _state.value.copy(error = "Save a valid Upstox access token first, or choose LOCAL / DOWNLOADED / LIVE ARCHIVE")
            return
        }
        if (source == HistoricalCorpusSource.LOCAL && !effectiveLocalSummary().trainable) {
            _state.value = _state.value.copy(error = "Import a trainable CE/PE local corpus first")
            return
        }
        if (source == HistoricalCorpusSource.DOWNLOADED && !downloaded.trainable) {
            _state.value = _state.value.copy(error = "Download NIFTY/SENSEX historical data first")
            return
        }
        if (source == HistoricalCorpusSource.COMBINED && !effectiveLocalSummary().trainable && !prelabelledReady) {
            _state.value = _state.value.copy(error = "COMBINED requires a trainable local/pre-labelled corpus in addition to LIVE ARCHIVE/Upstox")
            return
        }
        if (scope == HistoricalMarketScope.BOTH && source in setOf(HistoricalCorpusSource.LOCAL, HistoricalCorpusSource.COMBINED) && !effectiveLocalSummary().bothMarketsPresent) {
            _state.value = _state.value.copy(error = "BOTH local/combined training requires NIFTY and SENSEX in the local corpus")
            return
        }
        if (scope == HistoricalMarketScope.BOTH && source == HistoricalCorpusSource.DOWNLOADED && !downloaded.bothMarketsPresent) {
            _state.value = _state.value.copy(error = "BOTH DOWNLOADED training requires downloaded NIFTY and SENSEX data · use DOWNLOAD / RESUME with BOTH selected")
            return
        }
        if (months == PrelabelledTrainingWindowPlan.FULL && !prelabelledReady && source in setOf(HistoricalCorpusSource.LOCAL, HistoricalCorpusSource.COMBINED)) {
            _state.value = _state.value.copy(error = "FULL raw-local streaming is not enabled; use pre-labelled, DOWNLOADED, LIVE ARCHIVE, UPSTOX or a shorter raw-local window")
            return
        }

        cancelRequested = false
        _state.value = _state.value.copy(
            isRunning = true, stage = "STARTING", completed = 0, total = 0, result = null,
            installedCandidate = false, error = null,
            checkpointAvailable = checkpointFor(scope, months),
            message = "Starting ${source.label} ${scope.label} $label AI research · Production frozen${if (checkpointFor(scope, months)) " · compatible checkpoint will resume where supported" else ""}",
        )

        job = viewModelScope.launch {
            var client: UpstoxPlusHistoricalClient? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    when (source) {
                        HistoricalCorpusSource.LIVE_ARCHIVE -> runLiveArchive(scope, months, MetaBrainRuntime.productionSnapshotForResearch())
                        HistoricalCorpusSource.DOWNLOADED -> if (scope == HistoricalMarketScope.BOTH) runJointRawOrUpstox(source, months).first else runSingleRawOrUpstox(source, scope.singleIndexOrNull() ?: error("Invalid scope"), months).first
                        HistoricalCorpusSource.LOCAL -> if (prelabelledReady) {
                            if (scope == HistoricalMarketScope.BOTH) runJointPrelabelled(months) else runPrelabelled(scope.singleIndexOrNull() ?: error("Invalid scope"), months)
                        } else if (scope == HistoricalMarketScope.BOTH) runJointRawOrUpstox(HistoricalCorpusSource.LOCAL, months).first else runSingleRawOrUpstox(HistoricalCorpusSource.LOCAL, scope.singleIndexOrNull() ?: error("Invalid scope"), months).first
                        HistoricalCorpusSource.UPSTOX -> if (scope == HistoricalMarketScope.BOTH) runJointRawOrUpstox(source, months).also { client = it.second }.first else runSingleRawOrUpstox(source, scope.singleIndexOrNull() ?: error("Invalid scope"), months).also { client = it.second }.first
                        HistoricalCorpusSource.COMBINED -> {
                            if (prelabelledReady) {
                                val historical = if (scope == HistoricalMarketScope.BOTH) runJointPrelabelled(months) else runPrelabelled(scope.singleIndexOrNull() ?: error("Invalid scope"), months)
                                val archived = refineWithLiveArchive(historical, scope, months)
                                val base = archived.championState ?: historical.championState
                                if (base == null) archived.copy(note = archived.note + " · Upstox refinement skipped because no development-qualified base Candidate exists.")
                                else {
                                    val pair = loadSeries(HistoricalCorpusSource.UPSTOX, scope, months); client = pair.second
                                    val merged = HistoricalSeriesMerger.merge(pair.first)
                                    if (merged.isEmpty()) archived.copy(note = archived.note + " · No Upstox refinement series available.")
                                    else refineWithSeries(archived, base, scope, months, merged, pair.second)
                                }
                            } else {
                                val raw = if (scope == HistoricalMarketScope.BOTH) runJointRawOrUpstox(HistoricalCorpusSource.COMBINED, months).also { client = it.second }.first
                                else runSingleRawOrUpstox(HistoricalCorpusSource.COMBINED, scope.singleIndexOrNull() ?: error("Invalid scope"), months).also { client = it.second }.first
                                refineWithLiveArchive(raw, scope, months)
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
                    isRunning = false, stage = "COMPLETE", completed = _state.value.total.coerceAtLeast(_state.value.completed),
                    message = finalMessage, result = result, installedCandidate = installed,
                    error = result.errors.lastOrNull()?.take(220), cacheHits = stats.cacheHits, networkRequests = stats.requests,
                    localSummary = effectiveLocalSummary(), downloadedSummary = downloadedStore.summary(), prelabelledCorpusReady = prelabelledStore.ready(),
                    checkpointAvailable = checkpointFor(scope, months),
                )
                saveSummary(_state.value)
            } catch (error: Throwable) {
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats(); val wasCancelled = cancelled(error)
                _state.value = _state.value.copy(
                    isRunning = false, stage = if (wasCancelled) "CANCELLED" else "ERROR",
                    message = if (wasCancelled) "Training stopped safely · compatible checkpoint/corpus retained" else "Historical AI training stopped safely · corpus/checkpoint retained",
                    error = if (wasCancelled) null else (error.message ?: error::class.java.simpleName).take(300),
                    cacheHits = stats.cacheHits, networkRequests = stats.requests, localSummary = effectiveLocalSummary(), downloadedSummary = downloadedStore.summary(),
                    prelabelledCorpusReady = prelabelledStore.ready(), checkpointAvailable = checkpointFor(scope, months),
                )
            } finally { cancelRequested = false; job = null }
        }
    }

    private fun runPrelabelled(index: MarketIndex, months: Int): HistoricalCorpusTrainer.Result =
        AimlHistoricalOptionCorpusV1Trainer(prelabelledStore, MetaBrainRuntime.productionSnapshotForResearch()).run(
            index = index, monthsLabel = months.toLong(), onProgress = { publishProgress(it, null) }, shouldCancel = { cancelRequested }, checkpointStore = checkpointStore,
        )

    private fun runJointPrelabelled(months: Int): HistoricalCorpusTrainer.Result =
        JointPrelabelledHistoricalTrainer(prelabelledStore, MetaBrainRuntime.productionSnapshotForResearch()).run(
            monthsLabel = months.toLong(), onProgress = { publishProgress(it, null) }, shouldCancel = { cancelRequested }, checkpointStore = checkpointStore,
        )

    private fun runLiveArchive(scope: HistoricalMarketScope, months: Int, baseline: NumericalMetaBrain.ModelState): HistoricalCorpusTrainer.Result {
        _state.value = _state.value.copy(stage = "LIVE_ARCHIVE_INDEX", message = "Indexing preserved live observations/outcomes · exact-ID + canonical dedupe…")
        val markets = scope.singleIndexOrNull()?.let(::setOf) ?: MarketIndex.entries.toSet()
        val loaded = liveArchiveStore.load(months, markets, { cancelRequested }) { msg -> _state.value = _state.value.copy(stage = "LIVE_ARCHIVE_INDEX", message = msg) }
        _state.value = _state.value.copy(liveArchiveRecords = loaded.summary.records, liveArchiveDuplicates = loaded.summary.duplicatesRemoved)
        return LiveArchiveHistoricalTrainer(baseline).run(
            records = loaded.records, scope = scope, months = months,
            sourceLabel = "LIVE ARCHIVE ${PrelabelledTrainingWindowPlan.label(months)}",
            onProgress = { publishProgress(it, null) }, shouldCancel = { cancelRequested },
        ).let { r ->
            r.copy(note = r.note + " · Replay ${loaded.summary.records} unique · duplicates ${loaded.summary.duplicatesRemoved} · conflicts ${loaded.summary.conflictsRejected} · incompatible ${loaded.summary.incompatibleRejected} · migrated legacy vectors ${loaded.summary.legacyVectorsMigrated}.")
        }
    }

    private fun refineWithLiveArchive(base: HistoricalCorpusTrainer.Result, scope: HistoricalMarketScope, months: Int): HistoricalCorpusTrainer.Result {
        val champion = base.championState ?: return base.copy(note = base.note + " · LIVE ARCHIVE refinement skipped because no qualified base Candidate exists.")
        val archived = runLiveArchive(scope, months, champion)
        return if (archived.championState != null) archived.copy(note = base.note + " · LIVE ARCHIVE refinement PASS. " + archived.note)
        else base.copy(errors = (base.errors + archived.errors).distinct(), note = base.note + " · LIVE ARCHIVE refinement did not clear governance; base Candidate retained. " + archived.note)
    }

    private fun refineWithSeries(
        baseResult: HistoricalCorpusTrainer.Result,
        baseline: NumericalMetaBrain.ModelState,
        scope: HistoricalMarketScope,
        months: Int,
        series: List<HistoricalOptionSeries>,
        client: UpstoxPlusHistoricalClient?,
    ): HistoricalCorpusTrainer.Result {
        val refined = if (scope == HistoricalMarketScope.BOTH) JointHistoricalSeriesTrainer(baseline).run(
            series = series,
            config = HistoricalCorpusTrainer.Config(months = months.toLong()),
            sourceLabel = "COMBINED UPSTOX BOTH REFINEMENT",
            onProgress = { publishProgress(it, client) }, shouldCancel = { cancelRequested },
        ) else HistoricalSeriesTrainer(baseline).run(
            index = scope.singleIndexOrNull() ?: error("Invalid scope"), series = series,
            config = HistoricalCorpusTrainer.Config(months = months.toLong()),
            sourceLabel = "COMBINED UPSTOX REFINEMENT",
            onProgress = { publishProgress(it, client) }, shouldCancel = { cancelRequested },
        )
        return if (refined.championState != null) refined.copy(note = baseResult.note + " · Upstox refinement PASS. " + refined.note)
        else baseResult.copy(errors = (baseResult.errors + refined.errors).distinct(), note = baseResult.note + " · Upstox refinement did not clear governance; prior Candidate retained. " + refined.note)
    }

    private fun runSingleRawOrUpstox(source: HistoricalCorpusSource, index: MarketIndex, months: Int): Pair<HistoricalCorpusTrainer.Result, UpstoxPlusHistoricalClient?> {
        val pair = loadSeries(source, if (index == MarketIndex.NIFTY) HistoricalMarketScope.NIFTY else HistoricalMarketScope.SENSEX, months)
        val merged = HistoricalSeriesMerger.merge(pair.first); if (merged.isEmpty()) error("No trainable ${index.name} option series available from ${source.label}")
        return HistoricalSeriesTrainer(MetaBrainRuntime.productionSnapshotForResearch()).run(
            index = index, series = merged, config = HistoricalCorpusTrainer.Config(months = months.toLong()),
            sourceLabel = "${source.label} ${PrelabelledTrainingWindowPlan.label(months)}", onProgress = { publishProgress(it, pair.second) }, shouldCancel = { cancelRequested },
        ) to pair.second
    }

    private fun runJointRawOrUpstox(source: HistoricalCorpusSource, months: Int): Pair<HistoricalCorpusTrainer.Result, UpstoxPlusHistoricalClient?> {
        val pair = loadSeries(source, HistoricalMarketScope.BOTH, months); val merged = HistoricalSeriesMerger.merge(pair.first)
        if (merged.none { it.index == MarketIndex.NIFTY } || merged.none { it.index == MarketIndex.SENSEX }) error("BOTH historical research requires trainable NIFTY and SENSEX option series")
        return JointHistoricalSeriesTrainer(MetaBrainRuntime.productionSnapshotForResearch()).run(
            series = merged, config = HistoricalCorpusTrainer.Config(months = months.toLong()),
            sourceLabel = "${source.label} ${PrelabelledTrainingWindowPlan.label(months)}", onProgress = { publishProgress(it, pair.second) }, shouldCancel = { cancelRequested },
        ) to pair.second
    }

    private fun loadSeries(source: HistoricalCorpusSource, scope: HistoricalMarketScope, months: Int): Pair<List<HistoricalOptionSeries>, UpstoxPlusHistoricalClient?> {
        var client: UpstoxPlusHistoricalClient? = null; var series = emptyList<HistoricalOptionSeries>(); val errors = mutableListOf<String>()
        val markets = scope.singleIndexOrNull()?.let(::listOf) ?: MarketIndex.entries.toList()
        if (source in setOf(HistoricalCorpusSource.LOCAL, HistoricalCorpusSource.COMBINED)) {
            require(months != PrelabelledTrainingWindowPlan.FULL) { "FULL raw-local streaming is not enabled" }
            markets.forEach { index -> series += localStore.loadSeriesWindow(index, months); _state.value = _state.value.copy(stage = "LOCAL_READY", message = "Local ${scope.label} corpus · ${series.size} contracts streamed") }
        }
        if (source == HistoricalCorpusSource.DOWNLOADED) {
            markets.forEach { index ->
                series += downloadedStore.loadSeriesWindow(index, months)
                _state.value = _state.value.copy(stage = "DOWNLOADED_READY", message = "Downloaded ${scope.label} corpus · ${series.size} contracts loaded from phone")
            }
        }
        if (source in setOf(HistoricalCorpusSource.UPSTOX, HistoricalCorpusSource.COMBINED)) {
            val token = vault.read().upstoxAccessToken.trim(); client = UpstoxPlusHistoricalClient(accessToken = token, cacheDirectory = upstoxCacheDirectory)
            markets.forEach { index ->
                val loaded = UpstoxCorpusSeriesLoader(client).load(index = index, months = months.toLong(), onProgress = { publishProgress(it, client) }, shouldCancel = { cancelRequested })
                series += loaded.series; errors += loaded.errors
            }
        }
        if (errors.isNotEmpty()) _state.value = _state.value.copy(message = "${_state.value.message} · source warnings ${errors.size}")
        return series to client
    }

    private fun publishProgress(progress: HistoricalCorpusTrainer.Progress, client: UpstoxPlusHistoricalClient?) {
        val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
        _state.value = _state.value.copy(
            isRunning = true, stage = progress.stage, completed = progress.completed, total = progress.total,
            message = if (client == null) progress.message else "${progress.message} · cache ${stats.cacheHits} · network ${stats.requests}",
            cacheHits = stats.cacheHits, networkRequests = stats.requests, error = null,
        )
    }

    fun cancel() {
        if (job?.isActive == true) {
            cancelRequested = true
            _state.value = _state.value.copy(
                message = "Cancel requested · finishing current read safely…",
                importMessage = "Cancel requested…",
                downloadMessage = "Cancel requested · completed downloaded contracts will be retained…",
            )
        }
    }

    fun clearUpstoxCache() {
        if (busy()) return
        runCatching { upstoxCacheDirectory.deleteRecursively(); upstoxCacheDirectory.mkdirs() }
            .onSuccess { _state.value = _state.value.copy(cacheHits = 0, networkRequests = 0, downloadCacheHits = 0, downloadNetworkRequests = 0, message = "Upstox historical candle cache cleared", error = null) }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear Upstox cache: ${it.message}") }
    }
    fun clearHistoricalCache() = clearUpstoxCache()

    fun clearLocalCorpus() {
        if (busy()) return
        runCatching { prelabelledStore.clear(); localStore.clear(); checkpointStore.clearAll() }
            .onSuccess { _state.value = _state.value.copy(localSummary = LocalCorpusSummary(), selectedSource = HistoricalCorpusSource.UPSTOX, message = "Imported/pre-labelled corpus + incompatible training checkpoints cleared · downloaded/live archive data unchanged", error = null, prelabelledCorpusReady = false, prelabelledTrainRows = 0, prelabelledValidationRows = 0, prelabelledTestRows = 0, checkpointAvailable = false) }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear local corpus: ${it.message}") }
    }

    fun refreshLocalSummary() {
        if (busy()) return
        val ready = prelabelledStore.ready()
        _state.value = _state.value.copy(
            localSummary = effectiveLocalSummary(), downloadedSummary = downloadedStore.summary(), prelabelledCorpusReady = ready,
            prelabelledTrainRows = if (ready) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.TRAIN) else 0L,
            prelabelledValidationRows = if (ready) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION) else 0L,
            prelabelledTestRows = if (ready) prelabelledStore.rows(AimlHistoricalOptionCorpusV1Store.Split.TEST) else 0L,
            checkpointAvailable = checkpointFor(_state.value.selectedMarketScope, _state.value.selectedMonths),
        )
    }

    private fun checkpointFor(scope: HistoricalMarketScope, months: Int): Boolean = when (scope) {
        HistoricalMarketScope.BOTH -> checkpointStore.has("joint_both", months)
        HistoricalMarketScope.NIFTY -> checkpointStore.has("single_NIFTY", months)
        HistoricalMarketScope.SENSEX -> checkpointStore.has("single_SENSEX", months)
    }
    private fun effectiveLocalSummary(): LocalCorpusSummary = if (prelabelledStore.ready()) prelabelledStore.summary() else localStore.summary()
    private fun busy(): Boolean = job?.isActive == true || _state.value.isRunning || _state.value.isImporting || _state.value.isDownloading
    private fun cancelled(error: Throwable): Boolean = cancelRequested || error is kotlinx.coroutines.CancellationException || error.message?.contains("cancel", true) == true

    private fun saveSummary(state: UiState) {
        val r = state.result ?: return
        summaryPrefs.edit().putInt("months", state.selectedMonths).putString("index", state.selectedIndex.name)
            .putString("scope", state.selectedMarketScope.name).putString("source", state.selectedSource.name)
            .putInt("samples", r.corpusSamples).putBoolean("holdout_opened", r.lockedHoldoutOpened)
            .putBoolean("holdout_passed", r.lockedHoldoutPassed).putBoolean("installed", state.installedCandidate)
            .putString("message", state.message).apply()
    }

    override fun onCleared() { cancelRequested = true; super.onCleared() }

    companion object {
        private const val PREFS = "vardhani_historical_ai_training_summary"
        private fun loadLastSummary(application: Application): UiState {
            val p = application.getSharedPreferences(PREFS, 0); if (!p.contains("samples")) return UiState()
            val index = runCatching { MarketIndex.valueOf(p.getString("index", "NIFTY") ?: "NIFTY") }.getOrDefault(MarketIndex.NIFTY)
            val scope = runCatching { HistoricalMarketScope.valueOf(p.getString("scope", null) ?: if (index == MarketIndex.NIFTY) "NIFTY" else "SENSEX") }.getOrDefault(HistoricalMarketScope.BOTH)
            val source = runCatching { HistoricalCorpusSource.valueOf(p.getString("source", "UPSTOX") ?: "UPSTOX") }.getOrDefault(HistoricalCorpusSource.UPSTOX)
            val months = p.getInt("months", 1).takeIf { it in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS } ?: 1
            val samples = p.getInt("samples", 0); val passed = p.getBoolean("holdout_passed", false); val installed = p.getBoolean("installed", false)
            return UiState(selectedMonths = months, selectedIndex = index, selectedMarketScope = scope, selectedSource = source,
                message = "Last ${source.label} ${scope.label} ${PrelabelledTrainingWindowPlan.label(months)}: $samples samples · holdout ${if (passed) "PASS" else "not passed"}${if (installed) " · Candidate installed" else ""}", installedCandidate = installed)
        }
    }
}
