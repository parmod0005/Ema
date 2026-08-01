package com.parmod.ema.ai

import com.parmod.ema.model.SignalAction
import kotlin.math.abs

/**
 * Deterministic safety gate between AI analysis and VARDHANI execution.
 * The AI proposes; this router rejects stale, risky, conflicting, or unavailable decisions.
 */
class AiDecisionRouter {
    data class NativeDecision(
        val action: SignalAction,
        val confidence: Int,
    )

    data class Context(
        val mode: SignalEngineMode,
        val aiRunMode: AiRunMode,
        val nowMillis: Long,
        val currentSpot: Double,
        val snapshotSpot: Double,
        val dataAgeMillis: Long,
        val bridgeHealth: AiBridgeHealth,
        val dailyLossLocked: Boolean,
        val hasOpenPosition: Boolean,
        val native: NativeDecision,
        val ai: AiTradeDecision?,
        val minimumAiConfidence: Int = 80,
        val minimumNativeConfidence: Int = 80,
        val maximumDataAgeMillis: Long = 3_000,
    )

    enum class Authority { NATIVE, AI, AGREEMENT, NONE }

    data class Result(
        val action: SignalAction,
        val authority: Authority,
        val executable: Boolean,
        val reasons: List<String>,
        val aiDecision: AiTradeDecision? = null,
    )

    fun route(context: Context): Result {
        if (context.dailyLossLocked) {
            return wait("Daily loss lock active")
        }
        if (context.dataAgeMillis > context.maximumDataAgeMillis) {
            return wait("Market data is stale")
        }

        return when (context.mode) {
            SignalEngineMode.NATIVE -> nativeOnly(context)
            SignalEngineMode.AI_BRAIN -> aiOnly(context)
            SignalEngineMode.HYBRID -> hybrid(context)
        }
    }

    private fun nativeOnly(context: Context): Result {
        val qualified = context.native.action != SignalAction.WAIT &&
            context.native.confidence >= context.minimumNativeConfidence
        return Result(
            action = if (qualified) context.native.action else SignalAction.WAIT,
            authority = if (qualified) Authority.NATIVE else Authority.NONE,
            executable = qualified && !context.hasOpenPosition,
            reasons = listOf(if (qualified) "Native signal qualified" else "Native signal below threshold"),
        )
    }

    private fun aiOnly(context: Context): Result {
        val validation = validateAi(context)
        if (validation != null) return validation
        val ai = requireNotNull(context.ai)
        val executable = context.aiRunMode != AiRunMode.SHADOW && !context.hasOpenPosition
        return Result(
            action = ai.action,
            authority = Authority.AI,
            executable = executable,
            reasons = listOf(if (context.aiRunMode == AiRunMode.SHADOW) "AI shadow signal only" else "AI signal approved") + ai.reasons.take(3),
            aiDecision = ai,
        )
    }

    private fun hybrid(context: Context): Result {
        val aiInvalid = validateAi(context)
        if (aiInvalid != null) {
            // In hybrid mode, a healthy high-confidence native engine may safely fall back.
            val nativeQualified = context.native.action != SignalAction.WAIT &&
                context.native.confidence >= context.minimumNativeConfidence
            return if (nativeQualified) {
                Result(
                    action = context.native.action,
                    authority = Authority.NATIVE,
                    executable = !context.hasOpenPosition,
                    reasons = listOf("AI unavailable or invalid; native fallback") + aiInvalid.reasons,
                )
            } else aiInvalid
        }

        val ai = requireNotNull(context.ai)
        val nativeQualified = context.native.action != SignalAction.WAIT &&
            context.native.confidence >= context.minimumNativeConfidence
        val agree = nativeQualified && context.native.action == ai.action
        if (!agree) {
            return wait("AI/native conflict or native confirmation missing", ai)
        }

        val executable = context.aiRunMode != AiRunMode.SHADOW && !context.hasOpenPosition
        return Result(
            action = ai.action,
            authority = Authority.AGREEMENT,
            executable = executable,
            reasons = listOf(
                if (context.aiRunMode == AiRunMode.SHADOW) "AI/native agreement recorded in shadow mode"
                else "AI/native agreement approved",
            ) + ai.reasons.take(3),
            aiDecision = ai,
        )
    }

    private fun validateAi(context: Context): Result? {
        val ai = context.ai ?: return wait("No AI decision available")
        if (!context.bridgeHealth.configured || !context.bridgeHealth.reachable) {
            return wait("AI bridge unavailable", ai)
        }
        if (ai.snapshotId.isBlank() || ai.decisionId.isBlank()) {
            return wait("AI decision identifiers missing", ai)
        }
        if (ai.isExpired(context.nowMillis)) {
            return wait("AI decision expired", ai)
        }
        if (ai.action == SignalAction.WAIT) {
            return wait("AI decision is WAIT", ai)
        }
        if (ai.confidence < context.minimumAiConfidence) {
            return wait("AI confidence below threshold", ai)
        }
        if (ai.riskFlags.isNotEmpty()) {
            return wait("AI risk flags present: ${ai.riskFlags.take(2).joinToString()}", ai)
        }
        if (context.snapshotSpot <= 0.0 || context.currentSpot <= 0.0) {
            return wait("Invalid spot price", ai)
        }
        val movePct = abs(context.currentSpot - context.snapshotSpot) / context.snapshotSpot * 100.0
        if (movePct > ai.maximumSpotMovePct) {
            return wait("Price moved ${"%.2f".format(movePct)}% since AI snapshot", ai)
        }
        return null
    }

    private fun wait(reason: String, ai: AiTradeDecision? = null) = Result(
        action = SignalAction.WAIT,
        authority = Authority.NONE,
        executable = false,
        reasons = listOf(reason),
        aiDecision = ai,
    )
}
