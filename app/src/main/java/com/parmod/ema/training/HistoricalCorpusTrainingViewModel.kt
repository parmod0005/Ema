package com.parmod.ema.training

import android.app.Application
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
        val isRunning: Boolean = false,
        val stage: String = "IDLE",
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "Ready · 1M is recommended for the first phone run",
        val result: HistoricalCorpusTrainer.Result? = null,
        val installedCandidate: Boolean = false,
        val error: String? = null,
        val cacheHits: Long = 0,
        val networkRequests: Long = 0,
    ) {
        val progress: Float
            get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(loadLastSummary(application))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val vault = LocalCredentialVault(application)
    private val cacheDirectory = File(application.filesDir, "upstox_backtest_cache/v1")
    private val summaryPrefs = application.getSharedPreferences(PREFS, 0)
    private var job: Job? = null
    @Volatile private var cancelRequested = false

    fun selectMonths(months: Int) {
        if (job?.isActive == true || months !in setOf(1, 3, 6, 12)) return
        _state.value = _state.value.copy(
            selectedMonths = months,
            result = null,
            installedCandidate = false,
            error = null,
            message = if (months <= 3) "Ready · cached expired-option candles will be reused" else "Large mobile corpus · cached 1M/3M work will be reused",
        )
    }

    fun selectIndex(index: MarketIndex) {
        if (job?.isActive == true) return
        _state.value = _state.value.copy(
            selectedIndex = index,
            result = null,
            installedCandidate = false,
            error = null,
            message = "${index.name} historical AI corpus selected",
        )
    }

    fun runOrResume() {
        if (job?.isActive == true) return
        val token = vault.read().upstoxAccessToken.trim()
        if (token.isBlank()) {
            _state.value = _state.value.copy(error = "Save a valid Upstox access token in VARDHANI first")
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
            message = "Starting causal ${months}M ${index.name} option-premium corpus…",
        )

        job = viewModelScope.launch {
            var client: UpstoxPlusHistoricalClient? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    client = UpstoxPlusHistoricalClient(
                        accessToken = token,
                        cacheDirectory = cacheDirectory,
                    )
                    val trainer = HistoricalCorpusTrainer(
                        client = client!!,
                        productionBaseline = MetaBrainRuntime.productionSnapshotForResearch(),
                    )
                    trainer.run(
                        index = index,
                        config = HistoricalCorpusTrainer.Config(months = months.toLong()),
                        onProgress = { progress ->
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
                        },
                        shouldCancel = { cancelRequested },
                    )
                }

                val install = result.championState?.let { champion ->
                    MetaBrainRuntime.installHistoricalCandidate(
                        champion,
                        "Historical WF Champion · ${index.name} ${months}M",
                    )
                }
                val installed = install?.first == true
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                val finalMessage = when {
                    installed -> "Historical champion installed as Candidate only · now collect fresh live unseen labels"
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
                )
                saveSummary(_state.value)
            } catch (cancelled: Throwable) {
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                val wasCancel = cancelRequested || cancelled is kotlinx.coroutines.CancellationException || cancelled.message?.contains("cancel", true) == true
                _state.value = _state.value.copy(
                    isRunning = false,
                    stage = if (wasCancel) "CANCELLED" else "ERROR",
                    message = if (wasCancel) "Training stopped safely · completed downloads remain cached for resume" else "Historical AI training stopped safely · cache retained",
                    error = if (wasCancel) null else (cancelled.message ?: cancelled::class.java.simpleName).take(300),
                    cacheHits = stats.cacheHits,
                    networkRequests = stats.requests,
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
        _state.value = _state.value.copy(message = "Cancel requested · finishing current read safely…")
    }

    fun clearHistoricalCache() {
        if (job?.isActive == true) return
        runCatching {
            cacheDirectory.deleteRecursively()
            cacheDirectory.mkdirs()
        }.onSuccess {
            _state.value = _state.value.copy(
                cacheHits = 0,
                networkRequests = 0,
                message = "Historical candle cache cleared",
                error = null,
            )
        }.onFailure {
            _state.value = _state.value.copy(error = "Could not clear historical cache: ${it.message}")
        }
    }

    private fun saveSummary(state: UiState) {
        val r = state.result ?: return
        summaryPrefs.edit()
            .putInt("months", state.selectedMonths)
            .putString("index", state.selectedIndex.name)
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
            val samples = p.getInt("samples", 0)
            val passed = p.getBoolean("holdout_passed", false)
            val installed = p.getBoolean("installed", false)
            return UiState(
                selectedMonths = p.getInt("months", 1).coerceIn(1, 12),
                selectedIndex = index,
                message = "Last corpus: $samples samples · holdout ${if (passed) "PASS" else "not passed"}${if (installed) " · Candidate installed" else ""}",
                installedCandidate = installed,
            )
        }
    }
}
