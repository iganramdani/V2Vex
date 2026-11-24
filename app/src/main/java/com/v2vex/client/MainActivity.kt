package com.v2vex.client

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import com.v2vex.client.core.UriParser
import com.v2vex.client.model.VpnConnectionState
import com.v2vex.client.service.V2VexVpnService
import com.v2vex.client.service.VpnStateHolder
import com.v2vex.client.ui.V2VexRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val coreRefreshNonce = mutableIntStateOf(0)
    private val openLogsShortcutNonce = mutableIntStateOf(0)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            V2VexVpnService.start(this)
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional on Android 13+ */ }

    private val corePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = application as V2VexApp
        val fileName = resolveFileName(uri) ?: "xray_custom.zip"
        lifecycleScope.launch {
            val stream = contentResolver.openInputStream(uri)
            if (stream == null) {
                Toast.makeText(this@MainActivity, "Unable to read selected file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            app.coreManager.importLocalCore(stream, fileName).fold(
                onSuccess = {
                    coreRefreshNonce.intValue++
                    Toast.makeText(this@MainActivity, "Imported core: $fileName", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    Toast.makeText(this@MainActivity, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIncomingIntent(intent)

        val app = application as V2VexApp
        setContent {
            V2VexRoot(
                app = app,
                coreRefreshNonce = coreRefreshNonce.intValue,
                openLogsShortcutNonce = openLogsShortcutNonce.intValue,
                onRequestToggleVpn = { toggleVpn() },
                onRequestImportLocalCore = { corePickerLauncher.launch(arrayOf("*/*")) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun toggleVpn() {
        val state = VpnStateHolder.snapshot.value.state
        if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
            V2VexVpnService.stop(this)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                V2VexVpnService.start(this)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            "com.v2vex.client.SHORTCUT_TOGGLE_VPN" -> {
                toggleVpn()
                return
            }
            "com.v2vex.client.SHORTCUT_OPEN_LOGS" -> {
                openLogsShortcutNonce.intValue++
                return
            }
        }
        val data = intent?.dataString ?: return
        val parsed = UriParser.parseSingle(data) ?: return
        val app = application as V2VexApp
        app.repository.upsertProfile(parsed)
        app.repository.selectProfile(parsed.id)
        Toast.makeText(this, "Imported node: ${parsed.name}", Toast.LENGTH_SHORT).show()
    }

    private fun resolveFileName(uri: android.net.Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }
}
