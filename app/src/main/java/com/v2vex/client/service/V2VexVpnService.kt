package com.v2vex.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.v2vex.client.MainActivity
import com.v2vex.client.R
import com.v2vex.client.V2VexApp
import com.v2vex.client.core.LatencyTester
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.SingBoxConfigGenerator
import com.v2vex.client.core.SpeedLabTrafficCounter
import com.v2vex.client.core.SpeedMath
import com.v2vex.client.core.XrayConfigGenerator
import com.v2vex.client.core.XrayProcessManager
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.CoreEnginePreference
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.SplitTunnelMode
import com.v2vex.client.model.VpnConnectionState
import hev.htproxy.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * V2Vex v3.0 Flagship L3 VpnService:
 * - Native C hev-socks5-tunnel + Xray-core / Sing-box Modular Dual-Core
 * - Zero-Loopback Speed Meter: strictly TProxyGetStats() + SpeedLabTrafficCounter (0% loopback error!)
 * - Screen-State Battery Saver: pauses SystemUI notification wakeups when screen is off
 * - Seamless Wi-Fi <-> Cellular 0-Drop Network Handover via NetworkCallback + setUnderlyingNetworks
 */
class V2VexVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.v2vex.client.START_VPN"
        const val ACTION_STOP = "com.v2vex.client.STOP_VPN"
        const val ACTION_RELOAD = "com.v2vex.client.RELOAD_VPN"
        private const val CHANNEL_ID = "v2vex_vpn_channel"
        private const val NOTIF_ID = 9901

        fun start(context: Context) {
            val intent = Intent(context, V2VexVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reloadIfRunning(context: Context) {
            if (VpnStateHolder.snapshot.value.state == VpnConnectionState.CONNECTED) {
                val intent = Intent(context, V2VexVpnService::class.java).setAction(ACTION_RELOAD)
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, V2VexVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayProcess: XrayProcessManager? = null
    private var telemetryJob: Job? = null
    private var reloadJob: Job? = null
    private var tproxyActive = false
    private var networkCallbackRegistered = false
    @Volatile private var lastGoodConfigJson: String? = null
    @Volatile private var lastGoodUseSingBox: Boolean = false
    @Volatile private var lastGoodCoreStatus: String = ""
    @Volatile private var runtimeFailoverAttempted: Boolean = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val caps = cm.getNetworkCapabilities(network) ?: return
            // Ignore our own VPN transport
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val transportName = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
            LogBuffer.i("NET", "Underlying network handover -> $transportName ($network)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = application as V2VexApp
        xrayProcess = XrayProcessManager(this, app.coreManager)
        LogBuffer.d("VPN", "V2VexVpnService v3.3 ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadCoreOnly()
                return START_STICKY
            }
            ACTION_START, null -> {
                startTunnel()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        val app = application as V2VexApp
        app.repository.ensureLoaded()
        val profile = app.repository.getSelectedProfile()
        if (profile == null) {
            LogBuffer.e("VPN", "Cannot start VPN: no server profile selected")
            VpnStateHolder.setDisconnected("No server profile selected")
            stopSelf()
            return
        }
        // Issue #8: Auto-reset debug log level back to warning after 30 minutes
        val initialSettings = app.repository.settings.value
        val settings = if (
            initialSettings.logLevel == "debug" &&
            initialSettings.debugLogEnabledAtMs > 0L &&
            System.currentTimeMillis() - initialSettings.debugLogEnabledAtMs > 30L * 60L * 1000L
        ) {
            app.repository.updateSettings { it.copy(logLevel = "warning", debugLogEnabledAtMs = 0L) }
            LogBuffer.i("VPN", "Auto-reset debug log level -> warning after 30 minutes")
            app.repository.settings.value
        } else {
            initialSettings
        }

        VpnStateHolder.setConnecting(profile.id, profile.name, profile.shortBadge())
        startForeground(
            NOTIF_ID,
            buildNotification("V2Vex · Connecting...", "${profile.name} · Initializing L3 TUN")
        )

        serviceScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                try {
                    runtimeFailoverAttempted = false
                    app.coreManager.ensureBundledGeoAssets()
                    val launchRes = launchEngineWithDualCoreFallback(app, profile, settings)
                    if (!launchRes.ok) {
                        stopTunnel(launchRes.status)
                        return@withLock
                    }

                    lastGoodConfigJson = launchRes.configJson
                    lastGoodUseSingBox = launchRes.useSingBox
                    lastGoodCoreStatus = launchRes.status

                    val pfd = establishTunInterface(profile, settings)
                    val tproxyCfg = writeTProxyConfig(settings)
                    val startedTun = TProxyService.TProxyStartService(tproxyCfg.absolutePath, pfd.fd)
                    if (!startedTun) {
                        LogBuffer.e("TUN", "TProxyStartService returned false! fd=${pfd.fd}")
                        stopTunnel("Failed to start L3 hev-socks5-tunnel engine")
                        return@withLock
                    }
                    tproxyActive = true
                    registerNetworkHandoverCallback()

                    LogBuffer.i(
                        "TUN",
                        "L3 TUN ACTIVE | fd=${pfd.fd} | MTU=${settings.vpnMtu} | SOCKS=127.0.0.1:${settings.socksPort}"
                    )

                    VpnStateHolder.setConnected(
                        profileId = profile.id,
                        profileName = profile.name,
                        badge = profile.shortBadge(),
                        coreMode = "${launchRes.status} · L3 TUN"
                    )
                    startTelemetryLoop(profile)
                } catch (t: Throwable) {
                    LogBuffer.e("VPN", "Tunnel start failed: ${t.javaClass.simpleName}: ${t.message}")
                    stopTunnel(error = t.message ?: "Failed to start VPN")
                }
            }
        }
    }

    private data class EngineLaunchResult(
        val ok: Boolean,
        val status: String,
        val configJson: String,
        val useSingBox: Boolean
    )

    private fun handleUnexpectedEngineExit(exitCode: Int, reason: String?) {
        serviceScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val msg = if (!reason.isNullOrBlank()) "Core exited ($exitCode): $reason" else "Core exited ($exitCode)"
                val app = application as V2VexApp
                val profile = app.repository.getSelectedProfile()
                val settings = app.repository.settings.value

                // Issue #4 & #12: Mutex-guarded runtime crash failover in AUTO (Dual-Core) mode
                if (
                    tproxyActive &&
                    profile != null &&
                    settings.coreEngine == CoreEnginePreference.AUTO &&
                    profile.protocol != ProxyProtocol.RAW_JSON &&
                    !runtimeFailoverAttempted
                ) {
                    val altSingBox = !lastGoodUseSingBox
                    val altName = if (altSingBox) "Sing-box" else "Xray-core"
                    if (altSingBox && SingBoxConfigGenerator.requiresUnsupportedSafeSniSpoof(profile)) {
                        LogBuffer.w(
                            "VPN",
                            "Refused runtime Sing-box failover: Safe SNI Spoofing (verifyPeerCertByName) requires Xray-core to preserve TLS verification."
                        )
                    } else {
                        runtimeFailoverAttempted = true
                        LogBuffer.w("VPN", "Runtime crash ($msg) — failing over in-place to $altName!")
                        val fbCfg = buildEngineConfig(app, profile, settings, altSingBox, includeFailoverPool = false)
                        val fbRes = xrayProcess?.start(fbCfg, serviceScope, altSingBox, ::handleUnexpectedEngineExit)
                            ?: (false to "Core manager unavailable")
                        if (fbRes.first) {
                            lastGoodConfigJson = fbCfg
                            lastGoodUseSingBox = altSingBox
                            lastGoodCoreStatus = "${fbRes.second} · Runtime-Failover"
                            VpnStateHolder.setConnected(
                                profileId = profile.id,
                                profileName = profile.name,
                                badge = profile.shortBadge(),
                                coreMode = "${lastGoodCoreStatus} · L3 TUN"
                            )
                            LogBuffer.i("VPN", "Runtime failover to $altName succeeded — TUN preserved!")
                            return@withLock
                        }
                    }
                }
                stopTunnel(msg)
            }
        }
    }

    /**
     * Intelligent Dual-Core Launcher (Issues #2, #3, #4):
     * 1. Enforces Safe SNI Spoofing TLS security (never silently downgrades verifyPeerCertByName to insecure=true on Sing-box).
     * 2. Picks optimal engine (Sing-box for Hysteria2/TUIC; Xray for VLESS/VMess/Trojan/WG and SS with Xray-only features).
     * 3. If Xray fails with Auto-Failover pool active, retries primary node alone.
     * 4. In AUTO mode, verifies post-connect HTTP 204 health (when autoHealthFallback=true) and fails over to the alternate engine if primary fails startup OR handshake.
     */
    private suspend fun launchEngineWithDualCoreFallback(
        app: V2VexApp,
        profile: ServerProfile,
        settings: AppSettings
    ): EngineLaunchResult {
        val unsafeForSingBox = SingBoxConfigGenerator.requiresUnsupportedSafeSniSpoof(profile)
        if (settings.coreEngine == CoreEnginePreference.SING_BOX && unsafeForSingBox) {
            val msg = "Safe SNI Spoofing (verifyPeerCertByName) requires Xray-core when allowInsecure=false. Switch Core Engine to Xray or Auto."
            LogBuffer.e("VPN", msg)
            return EngineLaunchResult(false, msg, "", true)
        }

        val primarySingBox = shouldUseSingBox(profile, settings) && !unsafeForSingBox
        val onCrash: (Int, String?) -> Unit = ::handleUnexpectedEngineExit

        // Attempt 1: Primary engine with full settings
        var cfg = buildEngineConfig(app, profile, settings, primarySingBox, includeFailoverPool = true)
        LogBuffer.i(
            "VPN",
            "Config ready (${cfg.length}B) | engine=${if (primarySingBox) "Sing-box" else "Xray"} | mode=${settings.coreEngine} | node='${profile.name}' (${profile.shortBadge()}) | routing=${settings.routingPreset} | blockQuic=${settings.blockQuic} | dialStrat=${settings.outboundDomainStrategy} | frag=${settings.fragment.enabled} | mux=${settings.mux.enabled}"
        )
        var (ok, status) = xrayProcess?.start(cfg, serviceScope, primarySingBox, onCrash)
            ?: (false to "Core manager unavailable")

        if (ok) {
            // Issue #4: Lightweight post-connect health probe in AUTO mode
            if (shouldRunHealthFallback(profile, settings, primarySingBox, unsafeForSingBox)) {
                val probeMs = runProxyHealthProbe(settings)
                if (probeMs > 0) {
                    LogBuffer.i("VPN", "Post-connect health probe OK (${probeMs}ms)")
                    return EngineLaunchResult(true, status, cfg, primarySingBox)
                }
                val fallbackSingBox = !primarySingBox
                val fallbackName = if (fallbackSingBox) "Sing-box" else "Xray-core"
                LogBuffer.w("VPN", "Auto Dual-Core: primary engine failed HTTP 204 health probe — testing $fallbackName...")
                val fbCfg = buildEngineConfig(app, profile, settings, fallbackSingBox, includeFailoverPool = false)
                val fbRes = xrayProcess?.start(fbCfg, serviceScope, fallbackSingBox, onCrash)
                    ?: (false to "Core manager unavailable")
                if (fbRes.first) {
                    val fbProbeMs = runProxyHealthProbe(settings)
                    if (fbProbeMs > 0) {
                        LogBuffer.i("VPN", "Auto Dual-Core: $fallbackName passed health probe (${fbProbeMs}ms)!")
                        return EngineLaunchResult(true, "${fbRes.second} · Health-Fallback", fbCfg, fallbackSingBox)
                    }
                    LogBuffer.w("VPN", "Auto Dual-Core: $fallbackName also failed health probe — restoring primary engine")
                }
                // Restore primary engine so user still has tunnel up if probe URL itself is blocked
                val restored = xrayProcess?.start(cfg, serviceScope, primarySingBox, onCrash)
                    ?: (false to "Restore failed")
                if (restored.first) {
                    return EngineLaunchResult(true, status, cfg, primarySingBox)
                }
            }
            return EngineLaunchResult(true, status, cfg, primarySingBox)
        }
        if (status == "Superseded" || status == "Superseded by newer start") {
            return EngineLaunchResult(false, status, cfg, primarySingBox)
        }

        // Attempt 2: If Xray failed while Auto-Failover pool was enabled, retry Xray with primary node ONLY
        if (!primarySingBox && settings.enableAutoFailover) {
            LogBuffer.w("VPN", "Xray failed with Auto-Failover pool ($status) — retrying primary node '${profile.name}' alone")
            cfg = buildEngineConfig(app, profile, settings, useSingBox = false, includeFailoverPool = false)
            val retry = xrayProcess?.start(cfg, serviceScope, useSingBox = false, onCrash)
                ?: (false to "Core manager unavailable")
            ok = retry.first
            status = retry.second
            if (ok) return EngineLaunchResult(true, "$status (Primary-Only)", cfg, false)
        }

        // Attempt 3: In AUTO (Dual-Core) mode, seamlessly fail over to the secondary engine (respecting TLS security!)
        if (settings.coreEngine == CoreEnginePreference.AUTO && profile.protocol != ProxyProtocol.RAW_JSON) {
            val fallbackSingBox = !primarySingBox
            if (fallbackSingBox && unsafeForSingBox) {
                LogBuffer.w(
                    "VPN",
                    "Refused Auto-Fallback to Sing-box: Safe SNI Spoofing (verifyPeerCertByName) requires Xray-core to keep TLS verification enabled (allowInsecure=false)."
                )
                return EngineLaunchResult(false, "$status (Sing-box fallback blocked to protect TLS verification)", cfg, primarySingBox)
            }
            val fallbackName = if (fallbackSingBox) "Sing-box" else "Xray-core"
            LogBuffer.w("VPN", "Auto Dual-Core: primary engine failed ($status) — switching automatically to $fallbackName!")
            val fbCfg = buildEngineConfig(app, profile, settings, fallbackSingBox, includeFailoverPool = false)
            val fbRes = xrayProcess?.start(fbCfg, serviceScope, fallbackSingBox, onCrash)
                ?: (false to "Core manager unavailable")
            if (fbRes.first) {
                return EngineLaunchResult(true, "${fbRes.second} · Auto-Fallback", fbCfg, fallbackSingBox)
            }
            return EngineLaunchResult(false, "$status | Fallback $fallbackName: ${fbRes.second}", fbCfg, fallbackSingBox)
        }

        return EngineLaunchResult(false, status, cfg, primarySingBox)
    }

    private fun shouldRunHealthFallback(
        profile: ServerProfile,
        settings: AppSettings,
        primarySingBox: Boolean,
        unsafeForSingBox: Boolean
    ): Boolean {
        if (!settings.autoHealthFallback) return false
        if (settings.coreEngine != CoreEnginePreference.AUTO) return false
        if (profile.protocol == ProxyProtocol.RAW_JSON) return false
        // Hysteria2/TUIC are Sing-box-only; Xray cannot run them
        if (profile.protocol == ProxyProtocol.HYSTERIA2 || profile.protocol == ProxyProtocol.TUIC) return false
        // Do not probe fallback to Sing-box if Safe SNI Spoofing forbids Sing-box
        if (!primarySingBox && unsafeForSingBox) return false
        return true
    }

    private suspend fun runProxyHealthProbe(settings: AppSettings): Int {
        val useAuth = settings.allowLan && settings.lanProxyUsername.isNotBlank() && settings.lanProxyPassword.isNotBlank()
        return LatencyTester.testRealProxyDelay(
            httpProxyPort = settings.httpPort,
            probeUrl = settings.speedTest.pingUrl.ifBlank { "https://www.gstatic.com/generate_204" },
            timeoutMs = 3500,
            proxyUser = if (useAuth) settings.lanProxyUsername else "",
            proxyPassword = if (useAuth) settings.lanProxyPassword else ""
        )
    }

    private fun hasXrayOnlyFeaturesEnabled(settings: AppSettings): Boolean {
        val hasGeoRules = settings.routingPreset == com.v2vex.client.model.RoutingPreset.ADBLOCK_BYPASS_LAN ||
            settings.customRules.any { rule ->
                rule.enabled && (rule.values.contains("geosite:", ignoreCase = true) || rule.values.contains("geoip:", ignoreCase = true))
            }
        return settings.fragment.enabled ||
            settings.mux.enabled ||
            settings.enableAutoFailover ||
            settings.dns.enableFakeDns ||
            hasGeoRules
    }

    private fun shouldUseSingBox(profile: ServerProfile, settings: AppSettings): Boolean = when (settings.coreEngine) {
        CoreEnginePreference.SING_BOX -> true
        CoreEnginePreference.XRAY -> false
        CoreEnginePreference.AUTO -> when (profile.protocol) {
            ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC -> true
            // Issue #3: Keep Shadowsocks on Xray in AUTO mode when Xray-only features are enabled
            ProxyProtocol.SHADOWSOCKS -> !hasXrayOnlyFeaturesEnabled(settings)
            else -> false
        }
    }

    private fun buildEngineConfig(
        app: V2VexApp,
        profile: ServerProfile,
        settings: AppSettings,
        useSingBox: Boolean,
        includeFailoverPool: Boolean = true
    ): String {
        val chainProfile = app.repository.getProfileById(settings.chainProfileId)
        if (useSingBox) {
            return SingBoxConfigGenerator.generate(profile, settings, chainProfile = chainProfile)
        }
        val failovers = if (includeFailoverPool && settings.enableAutoFailover) {
            val selectedIds = settings.failoverNodeIds.toSet()
            val all = app.repository.profiles.value
            if (selectedIds.isNotEmpty()) {
                all.filter { it.id in selectedIds && it.id != profile.id }
            } else {
                // Only pick verified reachable nodes (latencyMs > 0) — never blindly probe broken nodes!
                all.filter { it.id != profile.id && (it.latencyMs ?: -1) > 0 }
                    .sortedBy { it.latencyMs }
                    .take(3)
            }
        } else emptyList()

        val validGeoIp = app.coreManager.availableGeoIpTags().toSet()
        val validGeoSite = app.coreManager.availableGeoSiteTags().toSet()

        return XrayConfigGenerator.generate(
            profile = profile,
            settings = settings,
            hasGeoIp = app.coreManager.hasGeoIp(),
            hasGeoSite = app.coreManager.hasGeoSite(),
            chainProfile = chainProfile,
            failoverProfiles = failovers,
            validGeoIpTags = validGeoIp,
            validGeoSiteTags = validGeoSite
        )
    }

    private fun reloadCoreOnly() {
        // Coalesce rapid setting taps into a single clean reload — eliminates exit-143/141 races!
        reloadJob?.cancel()
        reloadJob = serviceScope.launch(Dispatchers.IO) {
            delay(280)
            lifecycleMutex.withLock {
                if (!tproxyActive) return@withLock
                val app = application as V2VexApp
                val profile = app.repository.getSelectedProfile() ?: return@withLock
                val settings = app.repository.settings.value
                try {
                    runtimeFailoverAttempted = false
                    val launchRes = launchEngineWithDualCoreFallback(app, profile, settings)
                    if (launchRes.ok) {
                        lastGoodConfigJson = launchRes.configJson
                        lastGoodUseSingBox = launchRes.useSingBox
                        lastGoodCoreStatus = launchRes.status
                        VpnStateHolder.setConnected(
                            profileId = profile.id,
                            profileName = profile.name,
                            badge = profile.shortBadge(),
                            coreMode = "${launchRes.status} · L3 TUN"
                        )
                        LogBuffer.i(
                            "VPN",
                            "Hot-reloaded '${profile.name}' (engine=${if (launchRes.useSingBox) "Sing-box" else "Xray"}, blockQuic=${settings.blockQuic}, dial=${settings.outboundDomainStrategy}, log=${settings.logLevel})"
                        )
                    } else if (launchRes.status != "Superseded" && launchRes.status != "Superseded by newer start") {
                        LogBuffer.w("VPN", "Hot-reload rejected (${launchRes.status}) — rolling back to last healthy config")
                        val fallbackJson = lastGoodConfigJson
                        if (!fallbackJson.isNullOrBlank()) {
                            val (rolledBack, rbStatus) = xrayProcess?.start(
                                configJson = fallbackJson,
                                scope = serviceScope,
                                useSingBox = lastGoodUseSingBox,
                                onUnexpectedExit = ::handleUnexpectedEngineExit
                            ) ?: (false to "Rollback failed")
                            if (rolledBack) {
                                LogBuffer.i("VPN", "Rollback succeeded ($rbStatus) — tunnel preserved!")
                            } else {
                                stopTunnel(launchRes.status)
                            }
                        } else {
                            stopTunnel(launchRes.status)
                        }
                    }
                } catch (t: Throwable) {
                    LogBuffer.e("VPN", "Hot-reload failed: ${t.message}")
                }
            }
        }
    }

    private fun writeTProxyConfig(settings: AppSettings): File {
        val mtu = settings.vpnMtu.coerceIn(1280, 9000)
        val ipv6Line = if (settings.enableIpv6) "  ipv6: 'fd99:2026::1'\n" else ""
        val useAuth = settings.allowLan && settings.lanProxyUsername.isNotBlank() && settings.lanProxyPassword.isNotBlank()
        val authLines = if (useAuth) {
            val u = settings.lanProxyUsername.replace("'", "''")
            val p = settings.lanProxyPassword.replace("'", "''")
            "  username: '$u'\n  password: '$p'\n"
        } else ""
        val yaml = """
            tunnel:
              mtu: $mtu
              ipv4: 10.99.0.1
            ${ipv6Line}socks5:
              port: ${settings.socksPort}
              address: 127.0.0.1
              udp: 'udp'
            ${authLines}misc:
              task-stack-size: 81920
              connect-timeout: 5000
              read-write-timeout: 60000
              log-level: warn
        """.trimIndent()
        return File(filesDir, "tproxy.yml").apply { writeText(yaml) }
    }

    private fun establishTunInterface(profile: ServerProfile, settings: AppSettings): ParcelFileDescriptor {
        stopTProxy()
        vpnInterface?.close()

        val builder = Builder()
            .setSession("V2Vex · ${profile.name}")
            .setMtu(settings.vpnMtu.coerceIn(1280, 9000))
            .addAddress("10.99.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(settings.dns.directDns.ifBlank { "1.1.1.1" })

        if (settings.enableIpv6) {
            runCatching {
                builder.addAddress("fd99:2026::1", 126)
                builder.addRoute("::", 0)
            }
        }

        when (settings.splitTunnelMode) {
            SplitTunnelMode.ALL_APPS -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            SplitTunnelMode.BYPASS_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                settings.splitTunnelPackages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            SplitTunnelMode.PROXY_ONLY_SELECTED -> {
                val allowed = settings.splitTunnelPackages - packageName
                if (allowed.isEmpty()) {
                    runCatching { builder.addDisallowedApplication(packageName) }
                } else {
                    allowed.forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                }
            }
        }

        val pfd = builder.establish()
            ?: error("VPN permission revoked or TUN interface could not be established")
        vpnInterface = pfd
        return pfd
    }

    /**
     * Pure L3 TUN Speed Meter (verified against heiher/hev-socks5-tunnel src/hev-jni.c & hev-socks5-tunnel.c):
     * - TProxyGetStats() returns [0]=txPackets, [1]=txBytes (hev_tunnel_read = app upload),
     *   [2]=rxPackets, [3]=rxBytes (hev_tunnel_write = app download), eliminating Android eBPF 127.0.0.1 loopback inflation.
     * - Combined with in-app SpeedLabTrafficCounter for direct/proxied SpeedLab measurements.
     * - Screen-State Power Saver: when screen is off (!pm.isInteractive), skips SystemUI notification updates
     *   and relaxes polling to 2500ms for near-0% idle battery drain.
     */
    private fun startTelemetryLoop(profile: ServerProfile) {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch(Dispatchers.Default) {
            val pm = getSystemService(PowerManager::class.java)
            val nm = getSystemService(NotificationManager::class.java)

            var prevTunTx = 0L
            var prevTunRx = 0L
            var prevLabTx = SpeedLabTrafficCounter.txBytes.get()
            var prevLabRx = SpeedLabTrafficCounter.rxBytes.get()
            var prevNs = SystemClock.elapsedRealtimeNanos()

            var smoothDlBps = 0.0
            var smoothUlBps = 0.0
            var totalSessionDl = 0L
            var totalSessionUl = 0L
            var tickCount = 0
            var lastNotifTitle = ""

            while (isActive) {
                val screenOn = pm?.isInteractive != false
                delay(if (screenOn) 500L else 2500L)

                val nowNs = SystemClock.elapsedRealtimeNanos()
                val deltaSec = (nowNs - prevNs).coerceAtLeast(1L) / 1_000_000_000.0
                prevNs = nowNs

                val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                val curTunTx = stats?.getOrNull(1)?.coerceAtLeast(0L) ?: prevTunTx
                val curTunRx = stats?.getOrNull(3)?.coerceAtLeast(0L) ?: prevTunRx
                val tunTxDelta = (curTunTx - prevTunTx).coerceAtLeast(0L)
                val tunRxDelta = (curTunRx - prevTunRx).coerceAtLeast(0L)
                prevTunTx = curTunTx
                prevTunRx = curTunRx

                val curLabTx = SpeedLabTrafficCounter.txBytes.get()
                val curLabRx = SpeedLabTrafficCounter.rxBytes.get()
                val labTxDelta = (curLabTx - prevLabTx).coerceAtLeast(0L)
                val labRxDelta = (curLabRx - prevLabRx).coerceAtLeast(0L)
                prevLabTx = curLabTx
                prevLabRx = curLabRx

                val rxDelta = tunRxDelta + labRxDelta
                val txDelta = tunTxDelta + labTxDelta

                totalSessionDl += rxDelta
                totalSessionUl += txDelta

                val rawDlBps = (rxDelta * 8.0) / deltaSec
                val rawUlBps = (txDelta * 8.0) / deltaSec

                smoothDlBps = if (rawDlBps == 0.0 && smoothDlBps < 4_000.0) 0.0 else (0.60 * rawDlBps + 0.40 * smoothDlBps)
                smoothUlBps = if (rawUlBps == 0.0 && smoothUlBps < 4_000.0) 0.0 else (0.60 * rawUlBps + 0.40 * smoothUlBps)

                val dlBpsLong = smoothDlBps.toLong()
                val ulBpsLong = smoothUlBps.toLong()

                VpnStateHolder.updateTelemetry(
                    downloadBps = dlBpsLong,
                    uploadBps = ulBpsLong,
                    totalDownloadBytes = totalSessionDl,
                    totalUploadBytes = totalSessionUl
                )

                tickCount++
                if (screenOn && tickCount % 2 == 0) {
                    val unit = (application as V2VexApp).repository.settings.value.speedUnit
                    val activeName = VpnStateHolder.snapshot.value.activeProfileName ?: profile.name
                    val title = "↓ ${SpeedMath.formatSpeed(dlBpsLong, unit)}  ↑ ${SpeedMath.formatSpeed(ulBpsLong, unit)}"
                    if (title != lastNotifTitle || tickCount % 10 == 0) {
                        lastNotifTitle = title
                        val subtitle = "$activeName · ↓ ${SpeedMath.formatBytes(totalSessionDl)} ↑ ${SpeedMath.formatBytes(totalSessionUl)}"
                        nm?.notify(NOTIF_ID, buildNotification(title, subtitle))
                    }
                }

                if (tickCount % 60 == 0) {
                    LogBuffer.d(
                        "TELEM",
                        "Pure L3 TUN: rx=${SpeedMath.formatBytes(totalSessionDl)} tx=${SpeedMath.formatBytes(totalSessionUl)} | screenOn=$screenOn | coreAlive=${xrayProcess?.isRunning}"
                    )
                }
            }
        }
    }

    private fun registerNetworkHandoverCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkHandoverCallback() {
        if (!networkCallbackRegistered) return
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    private fun stopTProxy() {
        if (tproxyActive) {
            runCatching { TProxyService.TProxyStopService() }
            tproxyActive = false
        }
    }

    private fun stopTunnel(error: String? = null) {
        reloadJob?.cancel()
        reloadJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        unregisterNetworkHandoverCallback()
        stopTProxy()
        xrayProcess?.stop()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        VpnStateHolder.setDisconnected(error)
        LogBuffer.i("VPN", "Tunnel stopped${if (error != null) " ($error)" else ""}")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel("VPN permission revoked by system")
        super.onRevoke()
    }

    override fun onDestroy() {
        reloadJob?.cancel()
        telemetryJob?.cancel()
        unregisterNetworkHandoverCallback()
        stopTProxy()
        xrayProcess?.stop()
        runCatching { vpnInterface?.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, V2VexVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
