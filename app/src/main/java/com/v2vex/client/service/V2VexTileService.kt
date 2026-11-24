package com.v2vex.client.service

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.v2vex.client.MainActivity
import com.v2vex.client.model.VpnConnectionState

class V2VexTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnStateHolder.snapshot.value.state
        if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
            V2VexVpnService.stop(this)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                V2VexVpnService.start(this)
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
        tile.label = snap.activeProfileName?.take(16) ?: "V2Vex"
        tile.updateTile()
    }
}
