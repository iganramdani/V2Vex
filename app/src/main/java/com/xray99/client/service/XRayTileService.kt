package com.xray99.client.service

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xray99.client.MainActivity
import com.xray99.client.model.VpnConnectionState

class XRayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnStateHolder.snapshot.value.state
        if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
            XRayVpnService.stop(this)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                XRayVpnService.start(this)
            } else {
                val launch = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(launch)
            }
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val snap = VpnStateHolder.snapshot.value
        tile.state = when (snap.state) {
            VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = snap.activeProfileName?.take(16) ?: "XRay99"
        tile.updateTile()
    }
}
