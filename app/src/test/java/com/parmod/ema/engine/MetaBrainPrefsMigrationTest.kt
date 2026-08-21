package com.parmod.ema.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaBrainPrefsMigrationTest {
    @Test
    fun modern_serialized_legacy_weights_are_padded_to_current_schema() {
        val legacy = DoubleArray(NumericalMetaBrain.LEGACY_FEATURE_COUNT) { it / 10.0 }
        val raw = listOf(
            "0.1", "71", "4", "SHADOW", "0.015", "0.0005", "0.66", "0.42",
            legacy.joinToString(","),
        ).joinToString("|")
        val migrated = MetaBrainPrefsMigration.padSerializedWeights(raw)!!
        val weights = migrated.split("|")[8].split(',').map(String::toDouble)
        assertEquals(NumericalMetaBrain.FEATURE_COUNT, weights.size)
        legacy.indices.forEach { assertEquals(legacy[it], weights[it], 1e-12) }
        assertTrue(weights.drop(legacy.size).all { it == 0.0 })
    }
}
