package com.parmod.ema.backtest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.model.MarketIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class BacktestRange(val months: Long, val label: String) {
    ONE_MONTH(1, "1M"),
    THREE_MONTHS(3, "3M"),
    SIX_MONTHS(6, "6M"),
    ONE_YEAR(12, "1Y"),
}

class BacktestViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val range: BacktestRange = BacktestRange.ONE_MONTH,
        val isRunning: Boolean = false,
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "Safe mobile mode: start with one month",
        val result: ThreeMonthBacktestPipeline.Result? = null,
        val error: String? = null,
        val cacheHits: Long = 0,
        val networkRequests: Long = 0,
    ) {
        val progress: Float
            get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val selectedMonths: Int
            get() = range.months.toInt()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    private val candleCache = File(application.filesDir, "upstox_backtest_cache/v1")

    fun selectRange(range: BacktestRange) {
        if (job?.isActive == true) return
        _state.value = UiState(
            range = range,
            message = if (range.months <= 3) {
                "Ready to fetch or resume ${range.months} month${if (range.months == 1L) "" else "s"}"
            } else {
                "Large mobile run selected · complete 1M/3M first so cached data can be reused"
            },
        )
    }

    fun selectMonths(months: Int) {
        val range = BacktestRange.entries.firstOrNull { it.months.toInt() == months } ?: return
        selectRange(range)
    }

    fun run(accessToken: String, index: MarketIndex) {
        if (accessToken.isBlank()) {
            _state.value = _state.value.copy(error = "Paste and verify a valid Upstox token first")
            return
        }
        if (job?.isActive == true) return

        val selectedRange = _state.value.range
        _state.value = UiState(
            range = selectedRange,
            isRunning = true,
            message = "Discovering expiries · completed candle files will be reused…",
        )
        job = viewModelScope.launch {
            var client: UpstoxPlusHistoricalClient? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    client = UpstoxPlusHistoricalClient(
                        accessToken = accessToken.trim(),
                        cacheDirectory = candleCache,
                    )
                    ThreeMonthBacktestPipeline(client!!).run(
                        index = index,
                        months = selectedRange.months,
                        onProgress = { progress ->
                            val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                            _state.value = _state.value.copy(
                                isRunning = true,
                                completed = progress.completed,
                                total = progress.total,
                                message = "${progress.message} · cache ${stats.cacheHits} · network ${stats.requests}",
                                error = null,
                                cacheHits = stats.cacheHits,
                                networkRequests = stats.requests,
                            )
                        },
                    )
                }
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                _state.value = UiState(
                    range = selectedRange,
                    isRunning = false,
                    completed = result.contractsTested,
                    total = result.contractsTested,
                    message = "Backtest complete · ${stats.cacheHits} cached datasets · ${stats.requests} network requests",
                    result = result,
                    cacheHits = stats.cacheHits,
                    networkRequests = stats.requests,
                )
            } catch (memory: OutOfMemoryError) {
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                _state.value = _state.value.copy(
                    isRunning = false,
                    message = "Backtest stopped safely · cached downloads retained",
                    error = "Phone memory limit reached. Restart with 1M; cached data will be reused.",
                    cacheHits = stats.cacheHits,
                    networkRequests = stats.requests,
                )
            } catch (error: Throwable) {
                val stats = client?.requestStats() ?: UpstoxPlusHistoricalClient.RequestStats()
                _state.value = _state.value.copy(
                    isRunning = false,
                    message = "Backtest stopped safely · cached contracts remain available for resume",
                    error = (error.message ?: error::class.java.simpleName).take(300),
                    cacheHits = stats.cacheHits,
                    networkRequests = stats.requests,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(
            isRunning = false,
            message = "Backtest cancelled · completed candle downloads were retained",
        )
    }

    fun clearResult() {
        if (job?.isActive == true) return
        val range = _state.value.range
        _state.value = UiState(range = range)
    }

    fun clearCache() {
        if (job?.isActive == true) return
        runCatching { candleCache.deleteRecursively(); candleCache.mkdirs() }
            .onSuccess {
                _state.value = _state.value.copy(
                    cacheHits = 0,
                    networkRequests = 0,
                    message = "Historical candle cache cleared",
                    error = null,
                )
            }
            .onFailure {
                _state.value = _state.value.copy(error = "Could not clear cache: ${it.message}")
            }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
