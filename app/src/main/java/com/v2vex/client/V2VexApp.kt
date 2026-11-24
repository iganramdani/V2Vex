package com.v2vex.client

import android.app.Application
import com.v2vex.client.core.CoreManager
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.SpeedTestEngine
import com.v2vex.client.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class V2VexApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var repository: AppRepository
        private set

    lateinit var coreManager: CoreManager
        private set

    lateinit var speedTestEngine: SpeedTestEngine
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        coreManager = CoreManager(this)
        speedTestEngine = SpeedTestEngine()

        // Zero main-thread disk I/O: warm repository & GeoData asynchronously
        repository.loadAsync()
        appScope.launch {
            coreManager.ensureBundledGeoAssets()
        }
        LogBuffer.i("App", "V2Vex v1.1.0 initialized (99% Fast & Light)")
    }
}
