package com.rfsat.pocketgnss

import android.app.Application
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.SettingsStore

class GnssApp : Application() {
    lateinit var engine: GnssEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = GnssEngine(this, SettingsStore(this))
    }
}
