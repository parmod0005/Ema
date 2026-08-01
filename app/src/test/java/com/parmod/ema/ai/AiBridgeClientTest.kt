package com.parmod.ema.ai

import com.parmod.ema.model.SignalAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AiBridgeClientTest {
    private val client = AiBridgeClient("https://bridge.example", "device-token")

    private fun decision(action: String = "BUY_CALL") = JSONObject().apply {
        put("schemaVersion", 1)
        put("decisionId", "d-1")
        put("snapshotId", "s-1")
        put("decidedAtMillis", 1_000L)
        put("validForMillis", 30_000L)
        put("action", action)
        put("confidence", 87)
        put("regime", "TRENDING_BULLISH")
        put("maximumSpotMovePct", 0.15)
        put("modelVersion", "gpt-test")
        put("promptVersion", "p1")
    }

    @Test fun parsesCallAliasAndOptionalFields() {
        val parsed = client.parseDecision(decision())
        assertEquals(SignalAction.BUY_CE, parsed.action)
        assertEquals(87, parsed.confidence)
        assertNull(parsed.instrumentKey)
    }

    @Test fun parsesPutAlias() {
        val parsed = client.parseDecision(decision("BUY_PUT"))
        assertEquals(SignalAction.BUY_PE, parsed.action)
    }

    @Test fun unknownActionFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            client.parseDecision(decision("BUY_FUTURE"))
        }
    }

    @Test fun invalidConfidenceFailsModelValidation() {
        val json = decision().put("confidence", 140)
        assertThrows(IllegalArgumentException::class.java) {
            client.parseDecision(json)
        }
    }
}
