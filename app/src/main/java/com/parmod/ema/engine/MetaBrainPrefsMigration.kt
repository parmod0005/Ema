package com.parmod.ema.engine

import android.content.Context

/** One-time compatibility copy so the adaptive v3 store inherits the existing v2 Production/Candidate state. */
object MetaBrainPrefsMigration {
    fun migrateV2ToV3IfNeeded(context: Context) {
        val oldPrefs = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        val newPrefs = context.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE)
        if (newPrefs.all.isNotEmpty() || oldPrefs.all.isEmpty()) return

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
        editor.apply()
    }

    private const val OLD_PREFS = "vardhani_meta_brain_v2"
    private const val NEW_PREFS = "vardhani_meta_brain_v3"
}
