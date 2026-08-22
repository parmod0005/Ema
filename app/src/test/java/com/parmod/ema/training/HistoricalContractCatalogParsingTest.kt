package com.parmod.ema.training

import com.parmod.ema.model.MarketIndex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HistoricalContractCatalogParsingTest {
    @Test
    fun valid_contract_metadata_is_preserved_exactly() {
        val json = JSONObject()
            .put("instrument_key", "NSE_FO|50774|02-01-2025")
            .put("expiry", "2025-01-02")
            .put("strike_price", 24000.0)
            .put("instrument_type", "CE")
            .put("lot_size", 65)
            .put("trading_symbol", "NIFTY 24000 CE 02 JAN 25")

        val contract = HistoricalContractCatalogStore.parseContract(json)
        assertNotNull(contract)
        assertEquals("NSE_FO|50774|02-01-2025", contract!!.instrumentKey)
        assertEquals(LocalDate.of(2025, 1, 2), contract.expiry)
        assertEquals(24000.0, contract.strike, 0.0)
        assertEquals("CE", contract.optionType)
        assertEquals(65, contract.lotSize)
    }

    @Test
    fun invalid_or_incomplete_contract_metadata_is_rejected() {
        val missingKey = JSONObject()
            .put("expiry", "2025-01-02")
            .put("strike_price", 24000.0)
            .put("instrument_type", "CE")
            .put("lot_size", 65)
        assertNull(HistoricalContractCatalogStore.parseContract(missingKey))

        val invalidType = JSONObject()
            .put("instrument_key", "NSE_FO|1|02-01-2025")
            .put("expiry", "2025-01-02")
            .put("strike_price", 24000.0)
            .put("instrument_type", "XX")
            .put("lot_size", 65)
        assertNull(HistoricalContractCatalogStore.parseContract(invalidType))
    }

    @Test
    fun market_identity_comes_from_explicit_metadata_or_archive_path() {
        val nifty = JSONObject().put("trading_symbol", "NIFTY 24000 CE 02 JAN 25")
        val sensex = JSONObject().put("underlying_symbol", "SENSEX")
        val pathOnly = JSONObject()

        assertEquals(MarketIndex.NIFTY, HistoricalContractCatalogStore.inferMarket(nifty))
        assertEquals(MarketIndex.SENSEX, HistoricalContractCatalogStore.inferMarket(sensex))
        assertEquals(MarketIndex.NIFTY, HistoricalContractCatalogStore.inferMarket(pathOnly, "expired-options/nifty-50/2025-01-02/contracts.json"))
        assertEquals(MarketIndex.SENSEX, HistoricalContractCatalogStore.inferMarket(pathOnly, "expired-options/sensex/2025-01-02/contracts.json"))
    }

    @Test
    fun exchange_segment_prefix_alone_does_not_claim_market_identity() {
        val bse = JSONObject().put("instrument_key", "BSE_FO|12345|01-01-2025")
        val nse = JSONObject().put("instrument_key", "NSE_FO|12345|01-01-2025")
        assertNull(HistoricalContractCatalogStore.inferMarket(bse))
        assertNull(HistoricalContractCatalogStore.inferMarket(nse))
    }
}
