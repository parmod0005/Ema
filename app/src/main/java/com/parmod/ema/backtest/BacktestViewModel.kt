package com.parmod.ema.backtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.model.MarketIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BacktestViewModel : ViewModel() {
    data class UiState(
        val isRunning: Boolean = false,
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "Ready to fetch three months of Upstox Plus data",
        val result: ThreeMonthBacktestPipeline.Result? = null,
        val error: String? = null,
    ) {
        val progress: Float
            get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    fun run(accessToken: String, index: MarketIndex) {
        if (accessToken.isBlank()) {
            _state.value = _state.value.copy(error = "Paste and verify a valid Upstox token first")
            return
        }
        if (job?.isActive == true) return

        _state.value = UiState(isRunning = true, message = "Discovering expired expiries…")
        job = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ThreeMonthBacktestPipeline(UpstoxPlusHistoricalClient(accessToken.trim())).run(
                        index = index,
                        onProgress = { progress ->
                            _state.value = _state.value.copy(
                                isRunning = true,
                                completed = progress.completed,
                                total = progress.total,
                                message = progress.message,
                                error = null,
                            )
                        },
                    )
                }
            }.onSuccess { result ->
                _state.value = UiState(
                    isRunning = false,
                    completed = result.contractsTested,
                    total = result.contractsTested,
                    message = "Backtest complete: ${result.report.trades} trades across ${result.expiries} expiries",
                    result = result,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isRunning = false,
                    message = "Backtest failed",
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(isRunning = false, message = "Backtest cancelled")
    }

    fun clearResult() {
        if (job?.isActive == true) return
        _state.value = UiState()
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
