package com.parmod.ema

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.parmod.ema.engine.MetaBrainPrefsMigration
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.training.DualMarketLiveTrainingCoordinator
import com.parmod.ema.training.LiveResearchArchive

class VardhaniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MetaBrainPrefsMigration.migrateV2ToV3IfNeeded(this)
        LiveResearchArchive.initialize(this)
        MetaBrainRuntime.initialize(this)
        DualMarketLiveTrainingCoordinator.initialize(this)
        publishResearchShortcuts()
    }

    private fun publishResearchShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
        val aiIntent = Intent(this, MetaBrainLabActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val historyIntent = Intent(this, HistoricalDataActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val backupIntent = Intent(this, ResearchArchiveActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ai = ShortcutInfo.Builder(this, "vardhani_ai_lab")
            .setShortLabel("AI LAB")
            .setLongLabel("VARDHANI AI LAB")
            .setIcon(Icon.createWithResource(this, R.drawable.vardhani_logo))
            .setIntent(aiIntent)
            .build()
        val history = ShortcutInfo.Builder(this, "vardhani_historical_data")
            .setShortLabel("HISTORICAL DATA")
            .setLongLabel("VARDHANI HISTORICAL DATA")
            .setIcon(Icon.createWithResource(this, R.drawable.vardhani_logo))
            .setIntent(historyIntent)
            .build()
        val backup = ShortcutInfo.Builder(this, "vardhani_research_backup")
            .setShortLabel("RESEARCH BACKUP")
            .setLongLabel("VARDHANI RESEARCH BACKUP")
            .setIcon(Icon.createWithResource(this, R.drawable.vardhani_logo))
            .setIntent(backupIntent)
            .build()
        shortcutManager.dynamicShortcuts = listOf(ai, history, backup)
    }
}
