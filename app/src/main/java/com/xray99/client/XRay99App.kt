package com.xray99.client

import android.app.Application
import com.xray99.client.core.CoreManager
import com.xray99.client.core.LogBuffer
import com.xray99.client.core.SpeedTestEngine
import com.xray99.client.data.AppRepository

class XRay99App : Application() {

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
        LogBuffer.i("App", "XRay99 initialized (99% Fast & Light)")
    }
}
