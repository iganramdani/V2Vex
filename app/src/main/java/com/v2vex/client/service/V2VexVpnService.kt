package com.v2vex.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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
 * - Native C hev-socks5-tunnel (TProxyService) for full TCP + UDP + DNS TUN routing
 * - Exact TUN-level RX/TX telemetry via TProxyGetStats() (zero double-counting!)
 * - Seamless ACTION_RELOAD hot-reload when toggling Fragment/Mux/Routing or switching nodes
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
            if (VpnStateHolder.snapshot.value.state == com.v2vex.client.model.VpnConnectionState.CONNECTED) {
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
        val settings = app.repository.settings.value

        VpnStateHolder.setConnecting(profile.id, profile.name, profile.shortBadge())
        startForeground(NOTIF_ID, buildNotification(profile.name, "Starting Xray + L3 TUN..."))

        serviceScope.launch(Dispatchers.IO) {
            try {
                app.coreManager.ensureBundledGeoAssets()
                val chainProfile = app.repository.getProfileById(settings.chainProfileId)
                val configJson = XrayConfigGenerator.generate(
                    profile = profile,
                    settings = settings,
                    hasGeoIp = app.coreManager.hasGeoIp(),
                    hasGeoSite = app.coreManager.hasGeoSite(),
                    chainProfile = chainProfile
                )
                LogBuffer.i("VPN", "Generated Xray config (${configJson.length}B) for '${profile.name}'")

                // 1. Launch & verify Xray-core is genuinely alive
                val (coreStarted, coreStatus) = xrayProcess?.start(configJson, serviceScope) { exitCode ->
                    stopTunnel("Xray-core crashed (exit $exitCode)")
                } ?: (false to "Core manager unavailable")

                if (!coreStarted) {
                    stopTunnel(coreStatus)
                    return@launch
                }

                // 2. Establish Android TUN interface
                val pfd = establishTunInterface(profile, settings)

                // 3. Start native C hev-socks5-tunnel (full TCP + UDP + DNS L3 routing!)
                val tproxyCfg = writeTProxyConfig(settings)
                val startedTun = TProxyService.TProxyStartService(tproxyCfg.absolutePath, pfd.fd)
                if (!startedTun) {
                    stopTunnel("Failed to start L3 hev-socks5-tunnel engine")
                    return@launch
                }
                tproxyActive = true
                LogBuffer.i("TUN", "hev-socks5-tunnel ACTIVE (fd=${pfd.fd}, MTU=${settings.vpnMtu}, SOCKS=127.0.0.1:${settings.socksPort})")

                VpnStateHolder.setConnected(
                    profileId = profile.id,
                    profileName = profile.name,
                    badge = profile.shortBadge(),
                    coreMode = "$coreStatus · L3 TUN"
                )
                startTelemetryLoop(profile)
            } catch (t: Throwable) {
                LogBuffer.e("VPN", "Tunnel start failed: ${t.message}")
                stopTunnel(error = t.message ?: "Failed to start VPN")
            }
        }
    }

    /**
     * Hot-reloads Xray-core config cleanly without dropping the Android TUN interface!
     */
    private fun reloadCoreOnly() {
        val app = application as V2VexApp
        val profile = app.repository.getSelectedProfile() ?: return
        val settings = app.repository.settings.value

        serviceScope.launch(Dispatchers.IO) {
            try {
                val chainProfile = app.repository.getProfileById(settings.chainProfileId)
                val configJson = XrayConfigGenerator.generate(
                    profile = profile,
                    settings = settings,
                    hasGeoIp = app.coreManager.hasGeoIp(),
                    hasGeoSite = app.coreManager.hasGeoSite(),
                    chainProfile = chainProfile
                )
                val (ok, coreStatus) = xrayProcess?.start(configJson, serviceScope) { exitCode ->
                    stopTunnel("Xray-core crashed (exit $exitCode)")
                } ?: (false to "Core error")

                if (ok) {
                    VpnStateHolder.setConnected(
                        profileId = profile.id,
                        profileName = profile.name,
                        badge = profile.shortBadge(),
                        coreMode = "$coreStatus · L3 TUN"
                    )
                    LogBuffer.i("VPN", "Hot-reloaded Xray-core for '${profile.name}'")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", settings.httpPort))
            }
        }

        // Split Tunneling (always disallow self to prevent VPN loop)
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
     * Exact TUN-level telemetry directly from hev-socks5-tunnel TProxyGetStats() — ZERO double-counting!
     * TProxyGetStats() returns [tx_packets, tx_bytes, rx_packets, rx_bytes].
     */
    private fun startTelemetryLoop(profile: ServerProfile) {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch(Dispatchers.Default) {
            var prevTxBytes = 0L
            var prevRxBytes = 0L
            var prevNs = SystemClock.elapsedRealtimeNanos()
            var notifCounter = 0

            while (isActive) {
                delay(500)
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val deltaNs = (nowNs - prevNs).coerceAtLeast(1L)

                val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                val curTx = stats?.getOrNull(1)?.coerceAtLeast(0L) ?: prevTxBytes
                val curRx = stats?.getOrNull(3)?.coerceAtLeast(0L) ?: prevRxBytes

                val txDelta = (curTx - prevTxBytes).coerceAtLeast(0L)
                val rxDelta = (curRx - prevRxBytes).coerceAtLeast(0L)

                val dlBps = ((rxDelta * 8.0) / (deltaNs / 1_000_000_000.0)).toLong()
                val ulBps = ((txDelta * 8.0) / (deltaNs / 1_000_000_000.0)).toLong()

                prevTxBytes = curTx
                prevRxBytes = curRx
                prevNs = nowNs

                VpnStateHolder.updateTelemetry(
                    downloadBps = dlBps,
                    uploadBps = ulBps,
                    totalDownloadBytes = curRx,
                    totalUploadBytes = curTx
                )

                notifCounter++
                if (notifCounter % 4 == 0) {
                    val unit = (application as V2VexApp).repository.settings.value.speedUnit
                    val subtitle = "↓ ${SpeedMath.formatSpeed(dlBps, unit)}   ↑ ${SpeedMath.formatSpeed(ulBps, unit)}"
                    val activeName = VpnStateHolder.snapshot.value.activeProfileName ?: profile.name
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIF_ID, buildNotification(activeName, subtitle))
                }
            }
        }
    }

    private fun stopTProxy() {
        if (tproxyActive) {
            runCatching { TProxyService.TProxyStopService() }
            tproxyActive = false
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
            .setContentTitle("V2Vex · $title")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
