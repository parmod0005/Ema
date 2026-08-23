package com.parmod.ema.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalArchiveManifestAuditTest {
    @Test
    fun sensex_underlying_history_does_not_claim_sensex_option_catalogue() {
        val summary = HistoricalArchiveManifestAudit.audit(
            sequenceOf(
                "{\"path\":\"underlying\\/sensex\\/minutes-1\\/2022-01-01--2022-01-31.json\",\"url\":\"https:\\/\\/api.upstox.com\\/v3\\/historical-candle\\/BSE_INDEX%7CSENSEX\"}",
                "{\"path\":\"underlying\\/sensex\\/minutes-1\\/2022-02-01--2022-02-28.json\"}",
            ),
        )

        assertEquals(2, summary.sensexUnderlyingRows)
        assertEquals(0, summary.sensexExpiredOptionRows)
        assertEquals(0, summary.sensexContractsJsonRows)
        assertFalse(summary.hasSensexOptionEvidence)
        assertFalse(summary.hasVerifiedSensexContractMetadata)
    }

    @Test
    fun nifty_contracts_and_candles_are_detected_without_inventing_metadata() {
        val summary = HistoricalArchiveManifestAudit.audit(
            sequenceOf(
                "{\"path\":\"expired-options\\/nifty-50\\/2024-10-03\\/contracts.json\"}",
                "{\"path\":\"expired-options\\/nifty-50\\/2024-10-03\\/candles\\/NSE_FO_58423_03-10-2024.json\",\"url\":\"https:\\/\\/api.upstox.com\\/v2\\/expired-instruments\\/historical-candle\\/NSE_FO%7C58423%7C03-10-2024\"}",
            ),
        )

        assertEquals(2, summary.niftyExpiredOptionRows)
        assertEquals(1, summary.niftyContractsJsonRows)
        assertEquals(1, summary.nseFoRows)
        assertTrue(summary.hasNiftyOptionEvidence)
        assertTrue(summary.hasVerifiedNiftyContractMetadata)
    }

    @Test
    fun bse_fo_text_alone_is_not_verified_sensex_contract_metadata() {
        val summary = HistoricalArchiveManifestAudit.audit(
            sequenceOf(
                "{\"path\":\"misc\\/candles\\/BSE_FO_12345_01-01-2025.json\",\"url\":\"https:\\/\\/api.upstox.com\\/historical\\/BSE_FO%7C12345%7C01-01-2025\"}",
            ),
        )

        assertEquals(1, summary.bseFoRows)
        assertEquals(0, summary.sensexExpiredOptionRows)
        assertEquals(0, summary.sensexContractsJsonRows)
        assertFalse(summary.hasVerifiedSensexContractMetadata)
    }

    @Test
    fun real_sensex_contracts_json_is_verified_catalogue_evidence() {
        val summary = HistoricalArchiveManifestAudit.audit(
            sequenceOf(
                "{\"path\":\"expired-options\\/sensex\\/2025-01-07\\/contracts.json\"}",
                "{\"path\":\"expired-options\\/sensex\\/2025-01-07\\/candles\\/BSE_FO_999_07-01-2025.json\",\"url\":\"https:\\/\\/api.upstox.com\\/historical\\/BSE_FO%7C999%7C07-01-2025\"}",
            ),
        )

        assertEquals(2, summary.sensexExpiredOptionRows)
        assertEquals(1, summary.sensexContractsJsonRows)
        assertEquals(1, summary.bseFoRows)
        assertTrue(summary.hasSensexOptionEvidence)
        assertTrue(summary.hasVerifiedSensexContractMetadata)
    }
}
