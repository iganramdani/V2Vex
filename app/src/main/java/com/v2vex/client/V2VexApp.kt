package com.v2vex.client

import android.app.Application
import com.v2vex.client.core.CoreManager
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.SpeedTestEngine
import com.v2vex.client.data.AppRepository

class V2VexApp : Application() {

    lateinit var repository: AppRepository
        private set

    lateinit var coreManager: CoreManager
        private set

    lateinit var speedTestEngine: SpeedTestEngine
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        coreManager = CoreManager(this).also { it.ensureBundledGeoAssets() }
        speedTestEngine = SpeedTestEngine()
        LogBuffer.i("App", "V2Vex initialized (99% Fast & Light)")
    }
}
