package com.v2vex.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.model.VpnConnectionState

/**
 * Tasker / MacroDroid / ADB Automation BroadcastReceiver:
 * - com.v2vex.client.AUTOMATION_CONNECT
 * - com.v2vex.client.AUTOMATION_DISCONNECT
 * - com.v2vex.client.AUTOMATION_TOGGLE
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        LogBuffer.i("AUTO", "Received automation intent: $action")
        when (action) {
            "com.v2vex.client.AUTOMATION_CONNECT" -> {
                if (VpnService.prepare(context) == null) {
                    V2VexVpnService.start(context)
                }
            }
            "com.v2vex.client.AUTOMATION_DISCONNECT" -> {
                V2VexVpnService.stop(context)
            }
            "com.v2vex.client.AUTOMATION_TOGGLE" -> {
                val state = VpnStateHolder.snapshot.value.state
                if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
                    V2VexVpnService.stop(context)
                } else if (VpnService.prepare(context) == null) {
                    V2VexVpnService.start(context)
                }
            }
        }
    }
}
