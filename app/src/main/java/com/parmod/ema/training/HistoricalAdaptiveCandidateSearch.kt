package com.parmod.ema.training

import com.parmod.ema.engine.AdaptiveCandidateSearch
import com.parmod.ema.engine.NumericalMetaBrain

/**
 * Bounded historical-only Candidate evolution.
 *
 * It reuses the same deterministic LR/L2/TAKE/REJECT neighbourhood generator as
 * live adaptive search, but is driven only by development / walk-forward evidence.
 * Locked holdout results are never fed back into this search.
 */
object HistoricalAdaptiveCandidateSearch {
    const val MAX_ADAPTIVE_GENERATIONS = 6
    const val CANDIDATES_PER_GENERATION = 12

    data class Batch(
        val generation: Int,
        val parent: NumericalMetaBrain.HyperParameters,
        val candidates: List<NumericalMetaBrain.HyperParameters>,
    )

    fun nextBatch(
        parent: NumericalMetaBrain.HyperParameters,
        generation: Int,
        seenSignatures: MutableSet<String>,
    ): Batch {
        val safeGeneration = generation.coerceAtLeast(1)
        val candidates = ArrayList<NumericalMetaBrain.HyperParameters>(CANDIDATES_PER_GENERATION)
        var mutationCursor = (safeGeneration - 1) * CANDIDATES_PER_GENERATION
        while (candidates.size < CANDIDATES_PER_GENERATION) {
            val generated = AdaptiveCandidateSearch.next(
                parent = parent,
                generation = safeGeneration,
                startMutationIndex = mutationCursor,
                seenSignatures = seenSignatures,
            )
            mutationCursor = generated.mutationIndex + 1
            val signature = AdaptiveCandidateSearch.signature(generated.hyperParameters)
            if (seenSignatures.add(signature)) {
                candidates += generated.hyperParameters
            } else {
                mutationCursor++
            }
        }
        return Batch(safeGeneration, parent, candidates)
    }
}
