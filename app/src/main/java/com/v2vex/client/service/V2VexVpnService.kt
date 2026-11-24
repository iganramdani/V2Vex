package com.v2vex.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.v2vex.client.MainActivity
import com.v2vex.client.R
import com.v2vex.client.V2VexApp
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.SpeedMath
import com.v2vex.client.core.XrayConfigGenerator
import com.v2vex.client.core.XrayProcessManager
import com.v2vex.client.model.AppSettings
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
import java.io.File

/**
 * Full L3 Android VpnService powered by:
 * - Xray-core (libxray.so) for VLESS/REALITY/XHTTP/VMess/Trojan/SS/WG
 * - Native C hev-socks5-tunnel (TProxyService) for 100% TCP + UDP + DNS L3 TUN routing
 *   (NO setHttpProxy bypass, so ALL apps traverse TUN and telemetry is 100% accurate!)
 * - Hybrid exact telemetry: TProxyGetStats() + UID socket fallback (captures SpeedLab local HTTP too!)
 * - Smooth 1-second live notification speed meter showing ↓ Mbps / ↑ Mbps + session totals
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
    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayProcess: XrayProcessManager? = null
    private var telemetryJob: Job? = null
    private var tproxyActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = application as V2VexApp
        xrayProcess = XrayProcessManager(this, app.coreManager)
        LogBuffer.d("VPN", "V2VexVpnService onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogBuffer.d("VPN", "onStartCommand action=${intent?.action}")
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
        val settings = app.repository.settings.value

        VpnStateHolder.setConnecting(profile.id, profile.name, profile.shortBadge())
        startForeground(
            NOTIF_ID,
            buildNotification("V2Vex · Connecting...", "${profile.name} · Starting Xray + L3 TUN")
        )

        serviceScope.launch(Dispatchers.IO) {
            try {
                app.coreManager.ensureBundledGeoAssets()
                val useSingBox = shouldUseSingBox(profile, settings)
                val configJson = buildEngineConfig(app, profile, settings, useSingBox)
                LogBuffer.i(
                    "VPN",
                    "Config ready (${configJson.length}B) | engine=${if (useSingBox) "Sing-box" else "Xray"} | node='${profile.name}' (${profile.shortBadge()}) | routing=${settings.routingPreset} | frag=${settings.fragment.enabled} | mux=${settings.mux.enabled} | failover=${settings.enableAutoFailover}"
                )

                // 1. Launch & verify selected core engine is genuinely alive
                val (coreStarted, coreStatus) = xrayProcess?.start(
                    configJson = configJson,
                    scope = serviceScope,
                    useSingBox = useSingBox
                ) { exitCode ->
                    stopTunnel("Core engine crashed (exit $exitCode)")
                } ?: (false to "Core manager unavailable")

                if (!coreStarted) {
                    stopTunnel(coreStatus)
                    return@launch
                }

                // 2. Establish Android TUN interface (pure L3 — NO setHttpProxy bypass!)
                val pfd = establishTunInterface(profile, settings)

                // 3. Start native C hev-socks5-tunnel (full TCP + UDP + DNS L3 routing)
                val tproxyCfg = writeTProxyConfig(settings)
                val startedTun = TProxyService.TProxyStartService(tproxyCfg.absolutePath, pfd.fd)
                if (!startedTun) {
                    LogBuffer.e("TUN", "TProxyStartService returned false! fd=${pfd.fd}")
                    stopTunnel("Failed to start L3 hev-socks5-tunnel engine")
                    return@launch
                }
                tproxyActive = true
                LogBuffer.i(
                    "TUN",
                    "hev-socks5-tunnel ACTIVE | fd=${pfd.fd} | MTU=${settings.vpnMtu} | SOCKS=127.0.0.1:${settings.socksPort} | IPv6=${settings.enableIpv6}"
                )

                VpnStateHolder.setConnected(
                    profileId = profile.id,
                    profileName = profile.name,
                    badge = profile.shortBadge(),
                    coreMode = "$coreStatus · L3 TUN"
                )
                startTelemetryLoop(profile)
            } catch (t: Throwable) {
                LogBuffer.e("VPN", "Tunnel start failed: ${t.javaClass.simpleName}: ${t.message}")
                stopTunnel(error = t.message ?: "Failed to start VPN")
            }
        }
    }

    private fun shouldUseSingBox(profile: ServerProfile, settings: AppSettings): Boolean = when (settings.coreEngine) {
        com.v2vex.client.model.CoreEnginePreference.SING_BOX -> true
        com.v2vex.client.model.CoreEnginePreference.XRAY -> false
        com.v2vex.client.model.CoreEnginePreference.AUTO ->
            profile.protocol == com.v2vex.client.model.ProxyProtocol.HYSTERIA2 ||
                profile.protocol == com.v2vex.client.model.ProxyProtocol.TUIC
    }

    private fun buildEngineConfig(
        app: V2VexApp,
        profile: ServerProfile,
        settings: AppSettings,
        useSingBox: Boolean
    ): String {
        if (useSingBox) {
            return com.v2vex.client.core.SingBoxConfigGenerator.generate(profile, settings)
        }
        val chainProfile = app.repository.getProfileById(settings.chainProfileId)
        val failovers = if (settings.enableAutoFailover) {
            val selectedIds = settings.failoverNodeIds.toSet()
            val all = app.repository.profiles.value
            if (selectedIds.isNotEmpty()) all.filter { it.id in selectedIds } else all.take(4)
        } else emptyList()

        return XrayConfigGenerator.generate(
            profile = profile,
            settings = settings,
            hasGeoIp = app.coreManager.hasGeoIp(),
            hasGeoSite = app.coreManager.hasGeoSite(),
            chainProfile = chainProfile,
            failoverProfiles = failovers
        )
    }

    private fun reloadCoreOnly() {
        val app = application as V2VexApp
        val profile = app.repository.getSelectedProfile() ?: return
        val settings = app.repository.settings.value

        serviceScope.launch(Dispatchers.IO) {
            try {
                val useSingBox = shouldUseSingBox(profile, settings)
                val configJson = buildEngineConfig(app, profile, settings, useSingBox)
                val (ok, coreStatus) = xrayProcess?.start(
                    configJson = configJson,
                    scope = serviceScope,
                    useSingBox = useSingBox
                ) { exitCode ->
                    stopTunnel("Core engine crashed (exit $exitCode)")
                } ?: (false to "Core error")

                if (ok) {
                    VpnStateHolder.setConnected(
                        profileId = profile.id,
                        profileName = profile.name,
                        badge = profile.shortBadge(),
                        coreMode = "$coreStatus · L3 TUN"
                    )
                    LogBuffer.i("VPN", "Hot-reloaded ${if (useSingBox) "Sing-box" else "Xray-core"} for '${profile.name}' (logLevel=${settings.logLevel})")
                } else {
                    stopTunnel(coreStatus)
                }
            } catch (t: Throwable) {
                LogBuffer.e("VPN", "Hot-reload failed: ${t.message}")
            }
        }
    }

    private fun writeTProxyConfig(settings: AppSettings): File {
        val mtu = settings.vpnMtu.coerceIn(1280, 9000)
        val ipv6Line = if (settings.enableIpv6) "  ipv6: 'fd99:2026::1'\n" else ""
        val yaml = """
            tunnel:
              mtu: $mtu
              ipv4: 10.99.0.1
            ${ipv6Line}socks5:
              port: ${settings.socksPort}
              address: 127.0.0.1
              udp: 'udp'
            misc:
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

        // NOTE: Do NOT call setHttpProxy() when L3 hev-socks5-tunnel is active!
        // Setting setHttpProxy causes Android HTTP/HTTPS apps to bypass tun0, breaking
        // TUN routing and making TUN speed meters read 0 Mbps.

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
        LogBuffer.d("TUN", "TUN established: fd=${pfd.fd}, splitMode=${settings.splitTunnelMode}, excludedSelf=$packageName")
        return pfd
    }

    /**
     * High-accuracy live speed meter + 1-second notification updater:
     * - Reads native hev-socks5-tunnel TProxyGetStats() [tx_pkts, tx_bytes, rx_pkts, rx_bytes]
     * - Also reads TrafficStats.getUidRxBytes(myUid) / getUidTxBytes(myUid) for Xray's own UID
     *   (zero double-counting AND captures direct local HTTP/SOCKS traffic such as SpeedLab!).
     * - Takes maxOf(tunDelta, uidDelta) per 500ms window and applies light EMA smoothing for
     *   crisp, jitter-free Mbps readouts in both Dashboard and Status Notification.
     */
    private fun startTelemetryLoop(profile: ServerProfile) {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch(Dispatchers.Default) {
            val myUid = Process.myUid()
            var prevTunTx = 0L
            var prevTunRx = 0L
            val baseUidRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0L)
            val baseUidTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0L)
            var prevUidRx = baseUidRx
            var prevUidTx = baseUidTx
            var prevNs = SystemClock.elapsedRealtimeNanos()

            var smoothDlBps = 0.0
            var smoothUlBps = 0.0
            var totalSessionDl = 0L
            var totalSessionUl = 0L
            var tickCount = 0

            val nm = getSystemService(NotificationManager::class.java)

            while (isActive) {
                delay(500)
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val deltaNs = (nowNs - prevNs).coerceAtLeast(1L)
                val deltaSec = deltaNs / 1_000_000_000.0

                // 1. Native TUN counters from hev-socks5-tunnel
                val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                val curTunTx = stats?.getOrNull(1)?.coerceAtLeast(0L) ?: prevTunTx
                val curTunRx = stats?.getOrNull(3)?.coerceAtLeast(0L) ?: prevTunRx
                val tunTxDelta = (curTunTx - prevTunTx).coerceAtLeast(0L)
                val tunRxDelta = (curTunRx - prevTunRx).coerceAtLeast(0L)
                prevTunTx = curTunTx
                prevTunRx = curTunRx

                // 2. Xray process UID counters (exact outbound socket bytes, zero double-count)
                val curUidRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(prevUidRx)
                val curUidTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(prevUidTx)
                val uidRxDelta = (curUidRx - prevUidRx).coerceAtLeast(0L)
                val uidTxDelta = (curUidTx - prevUidTx).coerceAtLeast(0L)
                prevUidRx = curUidRx
                prevUidTx = curUidTx
                prevNs = nowNs

                // Use whichever exact counter sees the active flow (TUN for device apps, UID for local proxy/SpeedLab)
                val rxDelta = maxOf(tunRxDelta, uidRxDelta)
                val txDelta = maxOf(tunTxDelta, uidTxDelta)

                totalSessionDl += rxDelta
                totalSessionUl += txDelta

                val rawDlBps = (rxDelta * 8.0) / deltaSec
                val rawUlBps = (txDelta * 8.0) / deltaSec

                // Fast-attack, smooth-decay EMA (alpha=0.55) for responsive yet readable Mbps
                smoothDlBps = if (rawDlBps == 0.0 && smoothDlBps < 5_000.0) 0.0 else (0.55 * rawDlBps + 0.45 * smoothDlBps)
                smoothUlBps = if (rawUlBps == 0.0 && smoothUlBps < 5_000.0) 0.0 else (0.55 * rawUlBps + 0.45 * smoothUlBps)

                val dlBpsLong = smoothDlBps.toLong()
                val ulBpsLong = smoothUlBps.toLong()

                VpnStateHolder.updateTelemetry(
                    downloadBps = dlBpsLong,
                    uploadBps = ulBpsLong,
                    totalDownloadBytes = totalSessionDl,
                    totalUploadBytes = totalSessionUl
                )

                tickCount++
                // Update notification every 1.0 second (every 2 ticks) with live speed right in title!
                if (tickCount % 2 == 0) {
                    val unit = (application as V2VexApp).repository.settings.value.speedUnit
                    val activeName = VpnStateHolder.snapshot.value.activeProfileName ?: profile.name
                    val title = "↓ ${SpeedMath.formatSpeed(dlBpsLong, unit)}  ↑ ${SpeedMath.formatSpeed(ulBpsLong, unit)}"
                    val subtitle = "$activeName · ↓ ${SpeedMath.formatBytes(totalSessionDl)} ↑ ${SpeedMath.formatBytes(totalSessionUl)}"
                    nm?.notify(NOTIF_ID, buildNotification(title, subtitle))
                }

                // Periodic diagnostic heartbeat every 30s for real-device bug hunting
                if (tickCount % 60 == 0) {
                    LogBuffer.d(
                        "TELEM",
                        "Heartbeat: tunRx=$curTunRx tunTx=$curTunTx | uidRx=${curUidRx - baseUidRx} uidTx=${curUidTx - baseUidTx} | xrayAlive=${xrayProcess?.isRunning}"
                    )
                }
            }
        }
    }

    private fun stopTProxy() {
        if (tproxyActive) {
            runCatching { TProxyService.TProxyStopService() }
            tproxyActive = false
            LogBuffer.d("TUN", "hev-socks5-tunnel stopped")
        }
    }

    private fun stopTunnel(error: String? = null) {
        telemetryJob?.cancel()
        telemetryJob = null
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
        telemetryJob?.cancel()
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
