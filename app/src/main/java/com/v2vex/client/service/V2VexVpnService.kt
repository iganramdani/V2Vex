package com.v2vex.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.TrafficStats
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class V2VexVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.v2vex.client.START_VPN"
        const val ACTION_STOP = "com.v2vex.client.STOP_VPN"
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

        fun stop(context: Context) {
            val intent = Intent(context, V2VexVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayProcess: XrayProcessManager? = null
    private var telemetryJob: Job? = null

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
            ACTION_START, null -> {
                startTunnel()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        val app = application as V2VexApp
        val profile = app.repository.getSelectedProfile()
        if (profile == null) {
            LogBuffer.e("VPN", "Cannot start VPN: no server profile selected")
            VpnStateHolder.setDisconnected("No server profile selected")
            stopSelf()
            return
        }
        val settings = app.repository.settings.value

        VpnStateHolder.setConnecting(profile.id, profile.name, profile.shortBadge())
        startForeground(NOTIF_ID, buildNotification(profile.name, "Connecting...", settings))

        serviceScope.launch(Dispatchers.IO) {
            try {
                // 1. Generate Xray JSON configuration
                val configJson = XrayConfigGenerator.generate(profile, settings)
                LogBuffer.i("VPN", "Generated Xray config (${configJson.length} bytes) for '${profile.name}'")

                // 2. Start Xray-core process (if binary installed/bundled)
                val (_, coreStatus) = xrayProcess?.start(configJson, serviceScope)
                    ?: (false to "Unavailable")

                // 3. Establish Android TUN interface
                establishTunInterface(profile, settings)

                VpnStateHolder.setConnected(
                    profileId = profile.id,
                    profileName = profile.name,
                    badge = profile.shortBadge(),
                    coreMode = coreStatus
                )
                LogBuffer.i("VPN", "Tunnel ACTIVE · ${profile.name} · $coreStatus")

                startTelemetryLoop(profile, settings)
            } catch (t: Throwable) {
                LogBuffer.e("VPN", "Tunnel start failed: ${t.message}")
                stopTunnel(error = t.message ?: "Failed to start VPN")
            }
        }
    }

    private fun establishTunInterface(profile: ServerProfile, settings: AppSettings) {
        vpnInterface?.close()
        val builder = Builder()
            .setSession("V2Vex · ${profile.name}")
            .setMtu(settings.vpnMtu.coerceIn(1280, 9000))
            .addAddress("10.99.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(settings.dns.directDns.ifBlank { "1.1.1.1" })

        if (settings.enableIpv6) {
            runCatching {
                builder.addAddress("fd99:xray::1", 126)
                builder.addRoute("::", 0)
            }
        }

        // Attach local HTTP proxy on Android 10+ so HTTP/HTTPS apps seamlessly traverse Xray inbound
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

        vpnInterface = builder.establish()
            ?: error("VPN permission revoked or TUN interface could not be established")
    }

    private fun startTelemetryLoop(profile: ServerProfile, settings: AppSettings) {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch(Dispatchers.Default) {
            val baseRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            val baseTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var prevRx = baseRx
            var prevTx = baseTx
            var prevNs = SystemClock.elapsedRealtimeNanos()
            var notifCounter = 0

            while (isActive) {
                delay(500)
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val curRx = TrafficStats.getTotalRxBytes().coerceAtLeast(prevRx)
                val curTx = TrafficStats.getTotalTxBytes().coerceAtLeast(prevTx)
                val deltaNs = (nowNs - prevNs).coerceAtLeast(1L)

                val rxDelta = (curRx - prevRx).coerceAtLeast(0L)
                val txDelta = (curTx - prevTx).coerceAtLeast(0L)

                val dlBps = ((rxDelta * 8.0) / (deltaNs / 1_000_000_000.0)).toLong()
                val ulBps = ((txDelta * 8.0) / (deltaNs / 1_000_000_000.0)).toLong()

                prevRx = curRx
                prevTx = curTx
                prevNs = nowNs

                VpnStateHolder.updateTelemetry(
                    downloadBps = dlBps,
                    uploadBps = ulBps,
                    totalDownloadBytes = (curRx - baseRx).coerceAtLeast(0L),
                    totalUploadBytes = (curTx - baseTx).coerceAtLeast(0L)
                )

                // Update notification every 2 seconds to keep CPU & battery impact near zero
                notifCounter++
                if (notifCounter % 4 == 0) {
                    val unit = (application as V2VexApp).repository.settings.value.speedUnit
                    val subtitle = "↓ ${SpeedMath.formatSpeed(dlBps, unit)}   ↑ ${SpeedMath.formatSpeed(ulBps, unit)}"
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIF_ID, buildNotification(profile.name, subtitle, settings))
                }
            }
        }
    }

    private fun stopTunnel(error: String? = null) {
        telemetryJob?.cancel()
        telemetryJob = null
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

    private fun buildNotification(title: String, text: String, settings: AppSettings): Notification {
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
