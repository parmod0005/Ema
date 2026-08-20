package com.parmod.ema

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.parmod.ema.engine.MetaBrainRuntime

class VardhaniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MetaBrainRuntime.initialize(this)
        publishAiLabShortcut()
    }

    private fun publishAiLabShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
        val intent = Intent(this, MetaBrainLabActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shortcut = ShortcutInfo.Builder(this, "vardhani_ai_lab")
            .setShortLabel("AI LAB")
            .setLongLabel("VARDHANI AI LAB")
            .setIcon(Icon.createWithResource(this, R.drawable.vardhani_logo))
            .setIntent(intent)
            .build()
        shortcutManager.dynamicShortcuts = listOf(shortcut)
    }
}
