package com.v2vex.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2vex.client.V2VexApp
import com.v2vex.client.core.LatencyTester
import com.v2vex.client.core.SubscriptionManager
import com.v2vex.client.core.UriParser
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.CoreInfo
import com.v2vex.client.model.RemoteReleaseInfo
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.SpeedTestProgress
import com.v2vex.client.model.Subscription
import com.v2vex.client.model.VpnConnectionState
import com.v2vex.client.service.V2VexVpnService
import com.v2vex.client.service.VpnStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rotation-safe ViewModel (zero DI bloat) managing SpeedLab, Batch RTT, Real Proxy URLTest,
 * Core/GeoData updates, and seamless VPN hot-reload.
 */
class V2VexViewModel(application: Application) : AndroidViewModel(application) {

    private val app: V2VexApp get() = getApplication()

    val profiles = app.repository.profiles
    val subscriptions = app.repository.subscriptions
    val settings = app.repository.settings
    val selectedProfileId = app.repository.selectedProfileId
    val speedHistory = app.repository.speedHistory
    val vpnSnapshot = VpnStateHolder.snapshot

    private val _coreInfo = MutableStateFlow(CoreInfo())
    val coreInfo: StateFlow<CoreInfo> = _coreInfo.asStateFlow()

    private val _coreUpdateBusy = MutableStateFlow(false)
    val coreUpdateBusy: StateFlow<Boolean> = _coreUpdateBusy.asStateFlow()

    private val _coreUpdateProgress = MutableStateFlow(0f)
    val coreUpdateProgress: StateFlow<Float> = _coreUpdateProgress.asStateFlow()

    private val _coreUpdateStatus = MutableStateFlow("")
    val coreUpdateStatus: StateFlow<String> = _coreUpdateStatus.asStateFlow()

    private val _appUpdateRelease = MutableStateFlow<RemoteReleaseInfo?>(null)
    val appUpdateRelease: StateFlow<RemoteReleaseInfo?> = _appUpdateRelease.asStateFlow()

    private val _isTestingBatch = MutableStateFlow(false)
    val isTestingBatch: StateFlow<Boolean> = _isTestingBatch.asStateFlow()

    private val _isGeneratingWarp = MutableStateFlow(false)
    val isGeneratingWarp: StateFlow<Boolean> = _isGeneratingWarp.asStateFlow()

    private val _speedProgress = MutableStateFlow(SpeedTestProgress())
    val speedProgress: StateFlow<SpeedTestProgress> = _speedProgress.asStateFlow()

    private val _geoSiteTags = MutableStateFlow<List<String>>(emptyList())
    val geoSiteTags: StateFlow<List<String>> = _geoSiteTags.asStateFlow()

    private val _geoIpTags = MutableStateFlow<List<String>>(emptyList())
    val geoIpTags: StateFlow<List<String>> = _geoIpTags.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var speedJob: Job? = null
    private var settingsReloadJob: Job? = null

    init {
        refreshCoreInfo()
    }

    fun emitMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    fun refreshCoreInfo() {
        viewModelScope.launch {
            _coreInfo.value = app.coreManager.inspectInstalledCore()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.coreManager.ensureBundledGeoAssets()
                _geoSiteTags.value = app.coreManager.availableGeoSiteTags()
                _geoIpTags.value = app.coreManager.availableGeoIpTags()
            }
        }
    }

    fun selectProfile(id: String) {
        app.repository.selectProfile(id)
        if (VpnStateHolder.snapshot.value.state == VpnConnectionState.CONNECTED) {
            V2VexVpnService.reloadIfRunning(app)
            emitMessage("Switched active tunnel node")
        }
    }

    fun upsertProfile(profile: ServerProfile) {
        app.repository.upsertProfile(profile)
        if (profile.id == selectedProfileId.value) {
            V2VexVpnService.reloadIfRunning(app)
        }
    }

    fun deleteProfile(id: String) = app.repository.deleteProfile(id)

    fun importRaw(raw: String): Int {
        val parsed = UriParser.parseBatch(raw)
        app.repository.addProfiles(parsed)
        return parsed.size
    }

    fun updateSettings(hotReloadVpn: Boolean = false, transform: (AppSettings) -> AppSettings) {
        val before = app.repository.settings.value
        app.repository.updateSettings(transform)
        val after = app.repository.settings.value
        if (hotReloadVpn && tunnelSettingsChanged(before, after)) {
            settingsReloadJob?.cancel()
            settingsReloadJob = viewModelScope.launch {
                kotlinx.coroutines.delay(450)
                V2VexVpnService.reloadIfRunning(app)
            }
        }
    }

    private fun tunnelSettingsChanged(a: AppSettings, b: AppSettings): Boolean {
        val aNorm = a.copy(
            themeMode = b.themeMode,
            speedUnit = b.speedUnit,
            speedTest = b.speedTest,
            customCoreRepo = b.customCoreRepo,
            customGeoRepo = b.customGeoRepo,
            batteryBannerDismissed = b.batteryBannerDismissed
        )
        return aNorm != b
    }

    fun toggleBlockQuic() {
        updateSettings(hotReloadVpn = true) {
            it.copy(blockQuic = !it.blockQuic)
        }
        val on = settings.value.blockQuic
        emitMessage("QUIC (UDP:443): ${if (on) "BLOCKED" else "ALLOWED"}")
    }

    fun toggleFragment() {
        updateSettings(hotReloadVpn = true) {
            it.copy(fragment = it.fragment.copy(enabled = !it.fragment.enabled))
        }
        val on = settings.value.fragment.enabled
        emitMessage("DPI Fragment: ${if (on) "ON" else "OFF"}")
    }

    fun toggleMux() {
        updateSettings(hotReloadVpn = true) {
            it.copy(mux = it.mux.copy(enabled = !it.mux.enabled))
        }
        val on = settings.value.mux.enabled
        emitMessage("Mux.Cool: ${if (on) "ON" else "OFF"}")
    }

    fun cycleRouting() {
        val presets = RoutingPreset.entries
        val next = presets[(presets.indexOf(settings.value.routingPreset) + 1) % presets.size]
        updateSettings(hotReloadVpn = true) { it.copy(routingPreset = next) }
        emitMessage("Routing: ${next.label}")
    }

    fun addQuickDomainRule(domain: String, target: com.v2vex.client.model.RuleTarget) {
        val rule = com.v2vex.client.model.CustomRoutingRule(
            name = "${target.label} $domain",
            matchType = com.v2vex.client.model.RuleMatchType.DOMAIN,
            values = "domain:$domain",
            target = target
        )
        updateSettings(hotReloadVpn = true) {
            it.copy(customRules = listOf(rule) + it.customRules)
        }
        emitMessage("Added rule: domain:$domain -> ${target.label}")
    }

    fun generateFreeWarp() {
        if (_isGeneratingWarp.value) return
        viewModelScope.launch {
            _isGeneratingWarp.value = true
            emitMessage("Registering free Cloudflare WARP Curve25519 identity...")
            com.v2vex.client.core.WarpGenerator.generateFreeWarpProfile().fold(
                onSuccess = { profile ->
                    app.repository.upsertProfile(profile)
                    emitMessage("Created '${profile.name}' (ready for direct or Multi-Hop Chain!)")
                },
                onFailure = { err ->
                    emitMessage("WARP registration failed: ${err.message}")
                }
            )
            _isGeneratingWarp.value = false
        }
    }

    /**
     * If VPN is connected, runs Real Proxy URLTest through the active tunnel!
     * Otherwise runs TCP RTT to the selected server.
     */
    fun pingSelected() {
        val target = app.repository.getSelectedProfile() ?: return
        viewModelScope.launch {
            val isConnected = VpnStateHolder.snapshot.value.state == VpnConnectionState.CONNECTED
            val ms = if (isConnected) {
                LatencyTester.testRealProxyDelay(settings.value.httpPort, settings.value.speedTest.pingUrl)
            } else {
                LatencyTester.testSingle(target)
            }
            app.repository.updateLatencies(mapOf(target.id to ms), persist = true)
            val mode = if (isConnected) "Real Proxy Delay" else "TCP RTT"
            emitMessage(if (ms > 0) "${target.name} ($mode): ${ms}ms" else "${target.name} ($mode): Unreachable")
        }
    }

    fun pingAll() {
        val list = profiles.value
        if (list.isEmpty() || _isTestingBatch.value) return
        viewModelScope.launch {
            _isTestingBatch.value = true
            val results = LatencyTester.testBatch(list) { id, ms ->
                // Update in-memory only during batch ticks (zero disk write races!)
                app.repository.updateLatencies(mapOf(id to ms), persist = false)
            }
            // Single atomic disk persist once batch finishes
            app.repository.updateLatencies(results, persist = true)
            _isTestingBatch.value = false
            val reachable = results.values.count { it > 0 }
            emitMessage("RTT scan done: $reachable/${list.size} reachable")
        }
    }

    fun selectFastest() {
        val list = profiles.value
        if (list.isEmpty() || _isTestingBatch.value) return
        viewModelScope.launch {
            _isTestingBatch.value = true
            val results = LatencyTester.testBatch(list) { id, ms ->
                app.repository.updateLatencies(mapOf(id to ms), persist = false)
            }
            app.repository.updateLatencies(results, persist = true)
            _isTestingBatch.value = false
            val best = results.entries.filter { it.value > 0 }.minByOrNull { it.value }
            if (best != null) {
                selectProfile(best.key)
                val name = list.firstOrNull { it.id == best.key }?.name ?: "Node"
                emitMessage("Fastest node: $name (${best.value}ms)")
            } else {
                emitMessage("No reachable nodes found")
            }
        }
    }

    fun addSubscription(name: String, url: String) {
        val sub = Subscription(name = name, url = url)
        app.repository.upsertSubscription(sub)
        refreshSubscription(sub)
    }

    fun refreshSubscription(sub: Subscription) {
        viewModelScope.launch {
            emitMessage("Syncing '${sub.name}'...")
            SubscriptionManager.fetchSubscription(sub).fold(
                onSuccess = { res ->
                    app.repository.upsertSubscription(res.updatedSubscription)
                    app.repository.replaceSubscriptionProfiles(sub.id, res.profiles)
                    emitMessage("Synced '${sub.name}': ${res.profiles.size} node(s)")
                },
                onFailure = { emitMessage("Sync failed: ${it.message}") }
            )
        }
    }

    fun deleteSubscription(subId: String) {
        app.repository.deleteSubscription(subId, removeNodes = true)
        emitMessage("Subscription removed")
    }

    fun startSpeedTest() {
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            val selected = app.repository.getSelectedProfile()
            val useProxy = VpnStateHolder.snapshot.value.state == VpnConnectionState.CONNECTED && _coreInfo.value.executable
            runCatching {
                val record = app.speedTestEngine.runBenchmark(
                    settings = settings.value.speedTest,
                    useProxy = useProxy,
                    httpProxyPort = settings.value.httpPort,
                    profileId = selected?.id,
                    profileName = selected?.name ?: "Direct Network",
                    onProgress = { _speedProgress.value = it }
                )
                app.repository.addSpeedRecord(record)
            }
        }
    }

    fun stopSpeedTest() {
        speedJob?.cancel()
        speedJob = null
        _speedProgress.value = SpeedTestProgress(statusDetail = "Benchmark cancelled")
    }

    fun clearSpeedHistory() {
        app.repository.clearSpeedHistory()
        emitMessage("SpeedLab history cleared")
    }

    fun checkAndUpdateCore() {
        viewModelScope.launch {
            _coreUpdateBusy.value = true
            _coreUpdateProgress.value = 0.1f
            _coreUpdateStatus.value = "Checking Xray-core, Sing-box & V2Vex APK..."

            val xrayRes = app.coreManager.checkLatestXrayRelease(settings.value.customCoreRepo)
            _coreUpdateProgress.value = 0.4f
            val sbRes = app.coreManager.checkLatestSingBoxRelease()
            _coreUpdateProgress.value = 0.7f
            val apkRes = app.coreManager.checkAppReleaseUpdate()
            apkRes.onSuccess { rel -> _appUpdateRelease.value = rel }

            val xrayLatest = xrayRes.getOrNull()?.tagName ?: "check failed"
            val sbLatest = sbRes.getOrNull()?.tagName ?: "check failed"
            val apkLatest = apkRes.getOrNull()?.tagName ?: ""

            if (app.coreManager.isWxEnforced()) {
                // On Android 10-15 (W^X), jniLibs (libxray.so & libsingbox.so) are updated via V2Vex APK release!
                _coreUpdateProgress.value = 1f
                val summary = "Latest: Xray $xrayLatest · Sing-box $sbLatest" +
                    if (apkLatest.isNotBlank()) " · App $apkLatest" else ""
                _coreUpdateStatus.value = summary
                com.v2vex.client.core.LogBuffer.i("Core", "Dual-Core check -> $summary")
                emitMessage(summary)
                _coreInfo.value = app.coreManager.inspectInstalledCore()
            } else {
                // Pre-Android 10: allow hot-swapping Xray binary directly
                val xrayRel = xrayRes.getOrNull()
                if (xrayRel != null) {
                    app.coreManager.downloadAndInstallXrayCore(xrayRel) { frac, msg ->
                        _coreUpdateProgress.value = frac
                        _coreUpdateStatus.value = msg
                    }.fold(
                        onSuccess = { info ->
                            _coreInfo.value = info
                            val msg = "Xray $xrayLatest updated · Sing-box latest: $sbLatest"
                            _coreUpdateStatus.value = msg
                            emitMessage(msg)
                        },
                        onFailure = { err ->
                            _coreUpdateStatus.value = "Update failed: ${err.message}"
                            emitMessage("Core update failed: ${err.message}")
                        }
                    )
                } else {
                    _coreUpdateStatus.value = "Check failed: ${xrayRes.exceptionOrNull()?.message}"
                    emitMessage("GitHub check failed")
                }
            }
            _coreUpdateBusy.value = false
        }
    }

    fun updateGeoData() {
        viewModelScope.launch {
            _coreUpdateBusy.value = true
            _coreUpdateProgress.value = 0.05f
            _coreUpdateStatus.value = "Updating GeoIP & GeoSite..."
            app.coreManager.updateGeoData(settings.value.customGeoRepo) { frac, msg ->
                _coreUpdateProgress.value = frac
                _coreUpdateStatus.value = msg
            }.fold(
                onSuccess = { info ->
                    _coreInfo.value = info
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        _geoSiteTags.value = app.coreManager.availableGeoSiteTags()
                        _geoIpTags.value = app.coreManager.availableGeoIpTags()
                    }
                    emitMessage("GeoIP & GeoSite verified & updated (${_geoSiteTags.value.size} geosite tags)")
                },
                onFailure = { err ->
                    _coreUpdateStatus.value = "Geo update failed: ${err.message}"
                    emitMessage("Geo update failed: ${err.message}")
                }
            )
            _coreUpdateBusy.value = false
        }
    }

    fun exportFullBackup(): String = app.repository.exportFullBackup()
    fun importFullBackup(raw: String): Result<Int> = app.repository.importFullBackup(raw)
}
