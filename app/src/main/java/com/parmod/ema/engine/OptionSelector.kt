package com.parmod.ema.engine

import com.parmod.ema.model.OptionQuote
import kotlin.math.abs

/** Selects the most tradable contract instead of blindly taking the first ATM quote. */
class OptionSelector {
    data class Config(
        val targetAbsDelta: Double = 0.50,
        val minimumAbsDelta: Double = 0.35,
        val maximumAbsDelta: Double = 0.70,
        val minimumOpenInterest: Long = 10_000,
        val minimumLtp: Double = 5.0,
        val maximumLtp: Double = 2_500.0,
    )

    data class Selection(
        val quote: OptionQuote,
        val score: Double,
        val reasons: List<String>,
    )

    fun select(
        chain: List<OptionQuote>,
        optionType: String,
        config: Config = Config(),
    ): Selection? {
        val candidates = chain.asSequence()
            .filter { it.type == optionType }
            .filter { abs(it.delta) in config.minimumAbsDelta..config.maximumAbsDelta }
            .filter { it.openInterest >= config.minimumOpenInterest }
            .filter { it.ltp in config.minimumLtp..config.maximumLtp }
            .map { quote ->
                val deltaQuality = 1.0 - (abs(abs(quote.delta) - config.targetAbsDelta) / 0.20).coerceIn(0.0, 1.0)
                val oiQuality = (quote.openInterest / 100_000.0).coerceIn(0.0, 1.0)
                val oiChangeQuality = (quote.changeInOpenInterest / 25_000.0).coerceIn(-1.0, 1.0)
                val atmBonus = if (quote.isAtm) 0.12 else 0.0
                val gammaQuality = (quote.gamma / 0.003).coerceIn(0.0, 1.0)
                val score = deltaQuality * 0.45 + oiQuality * 0.25 + oiChangeQuality * 0.10 + gammaQuality * 0.08 + atmBonus
                Selection(
                    quote = quote,
                    score = score,
                    reasons = listOf(
                        "Delta ${"%.2f".format(quote.delta)}",
                        "OI ${quote.openInterest}",
                        "ΔOI ${quote.changeInOpenInterest}",
                        if (quote.isAtm) "ATM" else "Near ATM",
                    ),
                )
            }
            .sortedByDescending { it.score }
            .toList()

        return candidates.firstOrNull()
    }
}
