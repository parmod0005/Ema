package com.parmod.ema

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.parmod.ema.model.TradingRecoveryRegistry

/**
 * Crash-safe launcher router. A previous LIVE position always goes to broker
 * reconciliation before the normal dashboard can be opened.
 */
class VardhaniEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val destination = if (TradingRecoveryRegistry.hasUnresolvedStartupLivePosition()) {
            LiveRecoveryActivity::class.java
        } else {
            VardhaniFullActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }
}
