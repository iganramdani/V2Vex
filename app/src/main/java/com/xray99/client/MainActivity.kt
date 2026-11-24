package com.xray99.client

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
import com.xray99.client.core.UriParser
import com.xray99.client.model.VpnConnectionState
import com.xray99.client.service.VpnStateHolder
import com.xray99.client.service.XRayVpnService
import com.xray99.client.ui.XRay99Root
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val coreRefreshNonce = mutableIntStateOf(0)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            XRayVpnService.start(this)
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
        val app = application as XRay99App
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

        handleDeepLink(intent)

        val app = application as XRay99App
        setContent {
            XRay99Root(
                app = app,
                coreRefreshNonce = coreRefreshNonce.intValue,
                onRequestToggleVpn = { toggleVpn() },
                onRequestImportLocalCore = { corePickerLauncher.launch(arrayOf("*/*")) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun toggleVpn() {
        val state = VpnStateHolder.snapshot.value.state
        if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
            XRayVpnService.stop(this)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                XRayVpnService.start(this)
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        val parsed = UriParser.parseSingle(data) ?: return
        val app = application as XRay99App
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
