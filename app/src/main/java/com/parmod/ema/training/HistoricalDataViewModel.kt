package com.parmod.ema.training

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.model.MarketIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** UI state for persistent NIFTY/SENSEX historical-data acquisition only. */
class HistoricalDataViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val selectedScope: HistoricalMarketScope = HistoricalMarketScope.BOTH,
        val selectedMonths: Int = 6,
        val strikeRadius: Int = HistoricalCorpusDownloadManager.DEFAULT_STRIKES_EACH_SIDE,
        val isRunning: Boolean = false,
        val isImportingCatalogue: Boolean = false,
        val stage: String = "IDLE",
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "Ready · BOTH NIFTY + SENSEX · 6M verified historical download selected",
        val summary: LocalCorpusSummary = LocalCorpusSummary(),
        val catalogue: HistoricalContractCatalogStore.Summary = HistoricalContractCatalogStore.Summary(),
        val niftyUnderlyingRows: Long = 0L,
        val sensexUnderlyingRows: Long = 0L,
        val storage: DownloadedHistoricalCorpusStore.StorageStatus = DownloadedHistoricalCorpusStore.StorageStatus(0L, 0L),
        val cacheHits: Long = 0L,
        val networkRequests: Long = 0L,
        val errors: Int = 0,
        val availableFrom: LocalDate? = null,
        val availableTo: LocalDate? = null,
        val sourceCoverageLimited: Boolean = false,
        val strikeReferenceFallbacks: Int = 0,
        val allAvailableWorkComplete: Boolean = false,
        val error: String? = null,
    ) {
        val progress: Float get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val windowLabel: String get() = PrelabelledTrainingWindowPlan.label(selectedMonths)
    }

    private val app = application
    private val vault = LocalCredentialVault(application)
    private val manager = HistoricalCorpusDownloadManager(application)
    private val _state = MutableStateFlow(snapshotState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var cancelRequested = false

    fun selectScope(scope: HistoricalMarketScope) {
        if (busy()) return
        _state.value = _state.value.copy(selectedScope = scope, message = "${scope.label} historical download selected", error = null)
    }

    fun selectMonths(months: Int) {
        if (busy() || months !in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS) return
        _state.value = _state.value.copy(
            selectedMonths = months,
            message = when (months) {
                PrelabelledTrainingWindowPlan.FULL -> "FULL selected · persistent old contract catalogue + fresh Upstox Plus discovery · actual verified coverage will be shown"
                12 -> "12M selected · old verified catalogue is reused beyond today's fresh expiry-discovery window"
                else -> "${PrelabelledTrainingWindowPlan.label(months)} historical download selected"
            },
            error = null,
        )
    }

    fun selectStrikeRadius(radius: Int) {
        if (busy() || radius !in HistoricalCorpusDownloadManager.ALLOWED_STRIKE_RADII) return
        _state.value = _state.value.copy(
            strikeRadius = radius,
            message = "${radius * 2 + 1} causal spot-centred strikes/expiry selected · CE + PE",
            error = null,
        )
    }

    fun importOldCatalogue(uri: Uri) {
        if (busy()) return
        _state.value = _state.value.copy(
            isImportingCatalogue = true,
            stage = "CATALOGUE_IMPORT",
            message = "Importing real expired-option contract metadata from prior Upstox archive…",
            error = null,
        )
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val name = runCatching {
                        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull().orEmpty()
                    val input = app.contentResolver.openInputStream(uri) ?: error("Could not open selected archive")
                    input.use { manager.importCatalogue(it, name) }
                }
                val catalog = manager.catalogueSummary()
                _state.value = _state.value.copy(
                    isImportingCatalogue = false,
                    stage = "CATALOGUE_READY",
                    catalogue = catalog,
                    message = "Catalogue import complete · NIFTY expiries ${catalog.niftyExpiries} · SENSEX ${catalog.sensexExpiries} · contracts ${catalog.contracts} · newly added ${result.contractsAdded}",
                    errors = result.errors.size,
                    error = result.errors.lastOrNull(),
                )
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    isImportingCatalogue = false,
                    stage = "CATALOGUE_ERROR",
                    message = "Catalogue import stopped safely · existing catalogue retained",
                    error = (error.message ?: error::class.java.simpleName).take(300),
                )
            } finally {
                job = null
            }
        }
    }

    fun downloadOrResume() {
        if (busy()) return
        val token = vault.read().upstoxAccessToken.trim()
        if (token.isBlank()) {
            _state.value = _state.value.copy(error = "Save a valid Upstox access token in VARDHANI credentials first")
            return
        }
        val selected = _state.value
        cancelRequested = false
        _state.value = selected.copy(
            isRunning = true,
            stage = "STARTING",
            completed = 0,
            total = 0,
            errors = 0,
            message = "Starting / resuming ${selected.selectedScope.label} ${selected.windowLabel} verified historical download…",
            error = null,
        )
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    manager.download(
                        accessToken = token,
                        scope = selected.selectedScope,
                        months = selected.selectedMonths,
                        strikesEachSide = selected.strikeRadius,
                        onProgress = { p ->
                            _state.value = _state.value.copy(
                                isRunning = true,
                                stage = p.stage,
                                completed = p.completed,
                                total = p.total,
                                message = p.message,
                                cacheHits = p.cacheHits,
                                networkRequests = p.networkRequests,
                                storage = manager.storageStatus(),
                                error = null,
                            )
                        },
                        shouldCancel = { cancelRequested },
                    )
                }
                val partial = !result.allAvailableWorkComplete
                _state.value = _state.value.copy(
                    isRunning = false,
                    stage = if (partial) "PARTIAL" else "COMPLETE",
                    completed = result.contractsDownloaded + result.contractsSkipped,
                    total = result.contractsPlanned,
                    summary = result.summary,
                    catalogue = result.catalogue,
                    niftyUnderlyingRows = manager.underlyingRows(MarketIndex.NIFTY),
                    sensexUnderlyingRows = manager.underlyingRows(MarketIndex.SENSEX),
                    storage = result.storage,
                    cacheHits = result.stats.cacheHits,
                    networkRequests = result.stats.requests,
                    errors = result.errors.size,
                    availableFrom = result.availableFrom,
                    availableTo = result.availableTo,
                    sourceCoverageLimited = result.sourceCoverageLimited,
                    strikeReferenceFallbacks = result.strikeReferenceFallbacks,
                    allAvailableWorkComplete = result.allAvailableWorkComplete,
                    message = buildString {
                        append(if (partial) "Partial download retained safely" else "Requested verified catalogue work complete")
                        append(" · new ").append(result.contractsDownloaded)
                        append(" · resume skips ").append(result.contractsSkipped)
                        append(" · option rows +").append(result.rowsAdded)
                        append(" · index rows +").append(result.underlyingRowsAdded)
                        if (result.sourceCoverageLimited) append(" · requested window exceeds verified catalogue coverage")
                    },
                    error = result.errors.lastOrNull()?.take(300),
                )
            } catch (error: Throwable) {
                val cancelled = cancelRequested || error is kotlinx.coroutines.CancellationException || error.message?.contains("cancel", true) == true
                _state.value = _state.value.copy(
                    isRunning = false,
                    stage = if (cancelled) "CANCELLED" else "ERROR",
                    summary = manager.summary(),
                    catalogue = manager.catalogueSummary(),
                    niftyUnderlyingRows = manager.underlyingRows(MarketIndex.NIFTY),
                    sensexUnderlyingRows = manager.underlyingRows(MarketIndex.SENSEX),
                    storage = manager.storageStatus(),
                    message = if (cancelled) "Download stopped safely · verified data retained · press DOWNLOAD / RESUME to continue" else "Download stopped safely · verified data retained",
                    error = if (cancelled) null else (error.message ?: error::class.java.simpleName).take(300),
                )
            } finally {
                cancelRequested = false
                job = null
            }
        }
    }

    fun cancel() {
        if (job?.isActive != true) return
        cancelRequested = true
        _state.value = _state.value.copy(message = "Stop requested · current request will finish safely before stopping…")
    }

    fun refresh() {
        if (busy()) return
        _state.value = snapshotState(_state.value).copy(message = "Downloaded historical corpus + contract catalogue refreshed", error = null)
    }

    fun clearDownloaded() {
        if (busy()) return
        runCatching { manager.clearDownloadedCorpus() }
            .onSuccess {
                _state.value = snapshotState(_state.value).copy(
                    stage = "IDLE",
                    completed = 0,
                    total = 0,
                    errors = 0,
                    availableFrom = null,
                    availableTo = null,
                    sourceCoverageLimited = false,
                    strikeReferenceFallbacks = 0,
                    allAvailableWorkComplete = false,
                    message = "Downloaded candles/index context cleared · historical contract catalogue retained for re-download",
                    error = null,
                )
            }
            .onFailure { _state.value = _state.value.copy(error = "Could not clear downloaded corpus: ${it.message}") }
    }

    private fun snapshotState(base: UiState = UiState()): UiState = base.copy(
        summary = manager.summary(),
        catalogue = manager.catalogueSummary(),
        niftyUnderlyingRows = manager.underlyingRows(MarketIndex.NIFTY),
        sensexUnderlyingRows = manager.underlyingRows(MarketIndex.SENSEX),
        storage = manager.storageStatus(),
    )

    private fun busy(): Boolean = job?.isActive == true || _state.value.isRunning || _state.value.isImportingCatalogue

    override fun onCleared() {
        cancelRequested = true
        super.onCleared()
    }
}
