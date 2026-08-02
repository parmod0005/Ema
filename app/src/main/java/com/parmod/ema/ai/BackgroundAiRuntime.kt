package com.parmod.ema.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped AI request runner used by the live paper session.
 *
 * Requests are not children of an Activity/ViewModel coroutine. Therefore a
 * screen lock, configuration change, or temporary UI suspension does not cancel
 * an in-flight OpenAI request. The foreground service keeps the process alive.
 * This component never places broker orders.
 */
object BackgroundAiRuntime {
    data class Result(
        val decision: AiTradeDecision,
        val latencyMillis: Long,
        val snapshotSpot: Double,
    )

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicBoolean(false)

    fun isActive(): Boolean = active.get()

    fun submit(
        snapshotSpot: Double,
        request: () -> Pair<AiTradeDecision, Long>,
        onSuccess: (Result) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        if (!active.compareAndSet(false, true)) return false
        scope.launch {
            try {
                val response = request()
                onSuccess(Result(response.first, response.second, snapshotSpot))
            } catch (error: Throwable) {
                onFailure(error)
            } finally {
                active.set(false)
            }
        }
        return true
    }

    fun reset() {
        active.set(false)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
