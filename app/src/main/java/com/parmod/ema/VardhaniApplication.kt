package com.parmod.ema

import android.app.Application
import com.parmod.ema.engine.MetaBrainRuntime

class VardhaniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MetaBrainRuntime.initialize(this)
    }
}
