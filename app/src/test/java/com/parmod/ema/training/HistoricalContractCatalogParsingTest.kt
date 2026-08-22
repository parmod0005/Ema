package com.parmod.ema.training

import com.parmod.ema.model.MarketIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HistoricalContractCatalogParsingTest {
    @Test
    fun valid_contract_metadata_is_preserved_exactly() {
        val contract = HistoricalContractCatalogStore.parseContractFields(
            instrumentKey = "NSE_FO|50774|02-01-2025",
            expiryText = "2025-01-02",
            strike = 24000.0,
            optionType = "CE",
            lotSize = 65,
            tradingSymbol = "NIFTY 24000 CE 02 JAN 25",
        )
        assertNotNull(contract)
        assertEquals("NSE_FO|50774|02-01-2025", contract!!.instrumentKey)
        assertEquals(LocalDate.of(2025, 1, 2), contract.expiry)
        assertEquals(24000.0, contract.strike, 0.0)
        assertEquals("CE", contract.optionType)
        assertEquals(65, contract.lotSize)
    }

    @Test
    fun invalid_or_incomplete_contract_metadata_is_rejected() {
        assertNull(
            HistoricalContractCatalogStore.parseContractFields(
                instrumentKey = "",
                expiryText = "2025-01-02",
                strike = 24000.0,
                optionType = "CE",
                lotSize = 65,
            ),
        )
        assertNull(
            HistoricalContractCatalogStore.parseContractFields(
                instrumentKey = "NSE_FO|1|02-01-2025",
                expiryText = "2025-01-02",
                strike = 24000.0,
                optionType = "XX",
                lotSize = 65,
            ),
        )
        assertNull(
            HistoricalContractCatalogStore.parseContractFields(
                instrumentKey = "NSE_FO|1|02-01-2025",
                expiryText = "bad-date",
                strike = 24000.0,
                optionType = "PE",
                lotSize = 65,
            ),
        )
    }

    @Test
    fun market_identity_comes_from_explicit_metadata_or_archive_path() {
        assertEquals(
            MarketIndex.NIFTY,
            HistoricalContractCatalogStore.inferMarketFields(tradingSymbol = "NIFTY 24000 CE 02 JAN 25"),
        )
        assertEquals(
            MarketIndex.SENSEX,
            HistoricalContractCatalogStore.inferMarketFields(underlyingSymbol = "SENSEX"),
        )
        assertEquals(
            MarketIndex.NIFTY,
            HistoricalContractCatalogStore.inferMarketFields(pathHint = "expired-options/nifty-50/2025-01-02/contracts.json"),
        )
        assertEquals(
            MarketIndex.SENSEX,
            HistoricalContractCatalogStore.inferMarketFields(pathHint = "expired-options/sensex/2025-01-02/contracts.json"),
        )
    }

    @Test
    fun exchange_segment_prefix_alone_does_not_claim_market_identity() {
        assertNull(HistoricalContractCatalogStore.inferMarketFields(tradingSymbol = "BSE_FO|12345|01-01-2025"))
        assertNull(HistoricalContractCatalogStore.inferMarketFields(tradingSymbol = "NSE_FO|12345|01-01-2025"))
    }
}
