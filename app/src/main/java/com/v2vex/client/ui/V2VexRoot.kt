package com.v2vex.client.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v2vex.client.V2VexApp
import com.v2vex.client.model.VpnConnectionState
import com.v2vex.client.ui.screens.DashboardScreen
import com.v2vex.client.ui.screens.LogsScreen
import com.v2vex.client.ui.screens.NodesScreen
import com.v2vex.client.ui.screens.SettingsScreen
import com.v2vex.client.ui.screens.SpeedLabScreen
import com.v2vex.client.ui.theme.V2VexTheme
import com.v2vex.client.ui.theme.XrayCyan

enum class RootTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Rounded.Home),
    NODES("Nodes", Icons.AutoMirrored.Rounded.List),
    SPEEDLAB("SpeedLab", Icons.Rounded.PlayArrow),
    SETTINGS("Core", Icons.Rounded.Settings)
}

@Composable
fun V2VexRoot(
    app: V2VexApp,
    coreRefreshNonce: Int,
    onRequestToggleVpn: () -> Unit,
    onRequestImportLocalCore: () -> Unit,
    vm: V2VexViewModel = viewModel()
) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    val settings by vm.settings.collectAsState()
    val selectedProfileId by vm.selectedProfileId.collectAsState()
    val speedHistory by vm.speedHistory.collectAsState()
    val vpnSnapshot by vm.vpnSnapshot.collectAsState()
    val coreInfo by vm.coreInfo.collectAsState()
    val coreUpdateBusy by vm.coreUpdateBusy.collectAsState()
    val coreUpdateProgress by vm.coreUpdateProgress.collectAsState()
    val coreUpdateStatus by vm.coreUpdateStatus.collectAsState()
    val appUpdateRelease by vm.appUpdateRelease.collectAsState()
    val isTestingBatch by vm.isTestingBatch.collectAsState()
    val speedProgress by vm.speedProgress.collectAsState()

    val selectedProfile = remember(profiles, selectedProfileId) {
        profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
    }

    val snackbarHost = remember { SnackbarHostState() }
    var currentTab by rememberSaveable { mutableStateOf(RootTab.DASHBOARD) }
    var showLogs by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(coreRefreshNonce) {
        vm.refreshCoreInfo()
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snackbarHost.showSnackbar(msg)
        }
    }

    V2VexTheme(themeMode = settings.themeMode) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!showLogs) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        RootTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = XrayCyan,
                                    selectedTextColor = XrayCyan,
                                    indicatorColor = XrayCyan.copy(alpha = 0.14f)
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showLogs) {
                    val header = "VPN State: ${vpnSnapshot.state} (${vpnSnapshot.coreMode})\n" +
                        "Core: ${coreInfo.installedVersion} | src=${coreInfo.binarySource} | exec=${coreInfo.executable}\n" +
                        "GeoIP=${coreInfo.geoIpSizeBytes}B | GeoSite=${coreInfo.geoSiteSizeBytes}B\n" +
                        "Active Node: ${selectedProfile?.name ?: "None"} (${selectedProfile?.shortBadge() ?: ""})"
                    LogsScreen(
                        xrayLogLevel = settings.logLevel,
                        extraReportHeader = header,
                        onChangeXrayLogLevel = { lvl ->
                            vm.updateSettings(hotReloadVpn = true) { it.copy(logLevel = lvl) }
                            vm.emitMessage("Xray log level -> $lvl")
                        },
                        onBack = { showLogs = false },
                        onMessage = { vm.emitMessage(it) }
                    )
                } else {
                    when (currentTab) {
                        RootTab.DASHBOARD -> DashboardScreen(
                            snapshot = vpnSnapshot,
                            selectedProfile = selectedProfile,
                            settings = settings,
                            coreInfo = coreInfo,
                            onToggleVpn = onRequestToggleVpn,
                            onOpenNodes = { currentTab = RootTab.NODES },
                            onOpenSpeedLab = { currentTab = RootTab.SPEEDLAB },
                            onOpenCoreSettings = { currentTab = RootTab.SETTINGS },
                            onOpenLogs = { showLogs = true },
                            onPingSelected = { vm.pingSelected() },
                            onToggleFragment = { vm.toggleFragment() },
                            onToggleMux = { vm.toggleMux() },
                            onCycleRouting = { vm.cycleRouting() }
                        )

                        RootTab.NODES -> NodesScreen(
                            profiles = profiles,
                            subscriptions = subscriptions,
                            selectedProfileId = selectedProfile?.id,
                            settings = settings,
                            isTestingBatch = isTestingBatch,
                            onSelectProfile = { vm.selectProfile(it) },
                            onUpsertProfile = { vm.upsertProfile(it) },
                            onDeleteProfile = { vm.deleteProfile(it) },
                            onImportRaw = { vm.importRaw(it) },
                            onPingAll = { vm.pingAll() },
                            onSelectFastest = { vm.selectFastest() },
                            onAddSubscription = { n, u -> vm.addSubscription(n, u) },
                            onRefreshSubscription = { vm.refreshSubscription(it) },
                            onDeleteSubscription = { vm.deleteSubscription(it) },
                            onShowMessage = { vm.emitMessage(it) }
                        )

                        RootTab.SPEEDLAB -> SpeedLabScreen(
                            progress = speedProgress,
                            history = speedHistory,
                            settings = settings.speedTest,
                            isVpnConnected = vpnSnapshot.state == VpnConnectionState.CONNECTED,
                            activeNodeName = selectedProfile?.name,
                            onStartTest = { vm.startSpeedTest() },
                            onStopTest = { vm.stopSpeedTest() },
                            onUpdateSpeedSettings = { next ->
                                vm.updateSettings(hotReloadVpn = false) { it.copy(speedTest = next) }
                            },
                            onClearHistory = { vm.clearSpeedHistory() }
                        )

                        RootTab.SETTINGS -> SettingsScreen(
                            settings = settings,
                            profiles = profiles,
                            coreInfo = coreInfo,
                            coreUpdateBusy = coreUpdateBusy,
                            coreUpdateProgress = coreUpdateProgress,
                            coreUpdateStatus = coreUpdateStatus,
                            appUpdateRelease = appUpdateRelease,
                            onUpdateSettings = { vm.updateSettings(hotReloadVpn = true, transform = it) },
                            onCheckAndUpdateCore = { vm.checkAndUpdateCore() },
                            onUpdateGeoData = { vm.updateGeoData() },
                            onImportLocalCore = onRequestImportLocalCore,
                            onOpenAppReleaseUrl = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            onExportBackup = { vm.exportFullBackup() },
                            onImportBackup = { vm.importFullBackup(it) },
                            onOpenLogs = { showLogs = true },
                            onShowMessage = { vm.emitMessage(it) }
                        )
                    }
                }
            }
        }
    }
}
