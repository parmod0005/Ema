package com.parmod.ema.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for the live paper-trading runtime.
 *
 * Unlike viewModelScope this is not cancelled when the Activity/ViewModel is
 * stopped or recreated. The foreground service keeps the process scheduled;
 * explicit disconnect remains responsible for stopping the market job/socket.
 */
object ProcessTradingScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
