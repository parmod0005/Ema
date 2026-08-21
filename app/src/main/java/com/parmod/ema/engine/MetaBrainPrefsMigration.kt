package com.parmod.ema.engine

import android.content.Context

/** Compatibility migration for persistent VARDHANI numerical model state. */
object MetaBrainPrefsMigration {
    fun migrateV2ToV3IfNeeded(context: Context) {
        val oldPrefs = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        val newPrefs = context.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE)
        if (newPrefs.all.isEmpty() && oldPrefs.all.isNotEmpty()) {
            val editor = newPrefs.edit()
            oldPrefs.all.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
        migrateFeatureVectorIfNeeded(newPrefs)
    }

    /**
     * Model serialization stores weights in the final pipe-delimited field. Feature v2
     * only appends dimensions, so old weights retain identical meaning and new causal
     * dimensions safely start at zero. This preserves Production/Candidate/rollback
     * state across APK upgrades instead of silently dropping it.
     */
    private fun migrateFeatureVectorIfNeeded(prefs: android.content.SharedPreferences) {
        val editor = prefs.edit()
        var changed = false
        MODEL_KEYS.forEach { key ->
            val raw = prefs.getString(key, null) ?: return@forEach
            val migrated = padSerializedWeights(raw) ?: return@forEach
            if (migrated != raw) {
                editor.putString(key, migrated)
                changed = true
            }
        }
        if (changed) editor.commit()
        prefs.edit().putInt(KEY_FEATURE_SCHEMA, NumericalMetaBrain.FEATURE_SCHEMA_VERSION).apply()
    }

    internal fun padSerializedWeights(raw: String): String? {
        val parts = raw.split("|").toMutableList()
        val weightIndex = when {
            parts.size >= 9 -> 8
            parts.size == 5 -> 4
            else -> return null
        }
        val weights = parts[weightIndex].split(',').mapNotNull { it.toDoubleOrNull() }.toMutableList()
        if (weights.size == NumericalMetaBrain.FEATURE_COUNT) return raw
        if (weights.size != NumericalMetaBrain.LEGACY_FEATURE_COUNT || weights.size > NumericalMetaBrain.FEATURE_COUNT) return null
        while (weights.size < NumericalMetaBrain.FEATURE_COUNT) weights += 0.0
        parts[weightIndex] = weights.joinToString(",")
        return parts.joinToString("|")
    }

    private const val OLD_PREFS = "vardhani_meta_brain_v2"
    private const val NEW_PREFS = "vardhani_meta_brain_v3"
    private const val KEY_FEATURE_SCHEMA = "feature_schema"
    private val MODEL_KEYS = listOf("production", "candidate", "candidate_seed", "rollback")
}
