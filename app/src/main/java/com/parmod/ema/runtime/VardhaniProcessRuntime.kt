package com.parmod.ema.runtime

import com.parmod.ema.model.DashboardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-lifetime owner for the live paper runtime.
 *
 * The foreground service keeps the app process scheduled while minimized. Live
 * market coroutines use this scope instead of viewModelScope so destroying the
 * Activity/ViewModel owner does not cancel an active paper session. Dashboard
 * state is also process-shared so reopening the UI reattaches to the same
 * session instead of starting from a blank state.
 */
object VardhaniProcessRuntime {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dashboard: MutableStateFlow<DashboardState> = MutableStateFlow(DashboardState())
}
