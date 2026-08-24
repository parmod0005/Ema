package com.parmod.ema.training

/**
 * Pure-JVM audit for legacy VARDHANI/Upstox download manifests.
 *
 * A manifest proves that a file/key was downloaded, but it does NOT contain enough option
 * metadata to invent strike/CE-PE/lot-size information. In particular, BSE_FO text by itself
 * is never treated as proof that a contract belongs to SENSEX. Verified SENSEX catalogue
 * metadata requires an expired-options/sensex/.../contracts.json record (or equivalent real
 * contract metadata imported separately).
 */
object HistoricalArchiveManifestAudit {
    data class Summary(
        val rows: Int = 0,
        val niftyUnderlyingRows: Int = 0,
        val sensexUnderlyingRows: Int = 0,
        val niftyExpiredOptionRows: Int = 0,
        val sensexExpiredOptionRows: Int = 0,
        val nseFoRows: Int = 0,
        val bseFoRows: Int = 0,
        val niftyContractsJsonRows: Int = 0,
        val sensexContractsJsonRows: Int = 0,
    ) {
        val hasNiftyOptionEvidence: Boolean
            get() = niftyExpiredOptionRows > 0 || niftyContractsJsonRows > 0

        val hasSensexOptionEvidence: Boolean
            get() = sensexExpiredOptionRows > 0 || sensexContractsJsonRows > 0

        /** Only real SENSEX contracts.json metadata is enough to reconstruct catalogue rows. */
        val hasVerifiedSensexContractMetadata: Boolean
            get() = sensexContractsJsonRows > 0

        val hasVerifiedNiftyContractMetadata: Boolean
            get() = niftyContractsJsonRows > 0

        fun note(): String = buildString {
            append("manifest rows $rows")
            append(" · NIFTY underlying $niftyUnderlyingRows")
            append(" · SENSEX underlying $sensexUnderlyingRows")
            append(" · NIFTY expired-option $niftyExpiredOptionRows")
            append(" · SENSEX expired-option $sensexExpiredOptionRows")
            append(" · contracts.json NIFTY $niftyContractsJsonRows / SENSEX $sensexContractsJsonRows")
            append(" · NSE_FO rows $nseFoRows / BSE_FO rows $bseFoRows")
            if (!hasVerifiedSensexContractMetadata) {
                append(" · SENSEX option catalogue NOT reconstructable from manifest alone")
            }
        }
    }

    class Accumulator {
        private var summary = Summary()

        fun accept(rawLine: String) {
            val line = rawLine
                .replace("\\/", "/")
                .uppercase()
            if (line.isBlank()) return

            val niftyUnderlying = "UNDERLYING/NIFTY-50/" in line
            val sensexUnderlying = "UNDERLYING/SENSEX/" in line
            val niftyExpired = "EXPIRED-OPTIONS/NIFTY-50/" in line
            val sensexExpired = "EXPIRED-OPTIONS/SENSEX/" in line
            val nseFo = "NSE_FO%7C" in line || "NSE_FO|" in line
            val bseFo = "BSE_FO%7C" in line || "BSE_FO|" in line
            val contractsJson = "CONTRACTS.JSON" in line

            summary = summary.copy(
                rows = summary.rows + 1,
                niftyUnderlyingRows = summary.niftyUnderlyingRows + if (niftyUnderlying) 1 else 0,
                sensexUnderlyingRows = summary.sensexUnderlyingRows + if (sensexUnderlying) 1 else 0,
                niftyExpiredOptionRows = summary.niftyExpiredOptionRows + if (niftyExpired) 1 else 0,
                sensexExpiredOptionRows = summary.sensexExpiredOptionRows + if (sensexExpired) 1 else 0,
                nseFoRows = summary.nseFoRows + if (nseFo) 1 else 0,
                bseFoRows = summary.bseFoRows + if (bseFo) 1 else 0,
                niftyContractsJsonRows = summary.niftyContractsJsonRows + if (contractsJson && niftyExpired) 1 else 0,
                sensexContractsJsonRows = summary.sensexContractsJsonRows + if (contractsJson && sensexExpired) 1 else 0,
            )
        }

        fun snapshot(): Summary = summary
    }

    fun audit(lines: Sequence<String>): Summary {
        val accumulator = Accumulator()
        lines.forEach(accumulator::accept)
        return accumulator.snapshot()
    }
}
