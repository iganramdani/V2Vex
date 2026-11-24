package com.v2vex.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.v2vex.client.V2VexApp
import com.v2vex.client.core.LatencyTester
import com.v2vex.client.core.SubscriptionManager
import com.v2vex.client.core.UriParser
import com.v2vex.client.model.CoreInfo
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.SpeedTestProgress
import com.v2vex.client.model.Subscription
import com.v2vex.client.model.VpnConnectionState
import com.v2vex.client.service.VpnStateHolder
import com.v2vex.client.ui.screens.DashboardScreen
import com.v2vex.client.ui.screens.LogsScreen
import com.v2vex.client.ui.screens.NodesScreen
import com.v2vex.client.ui.screens.SettingsScreen
import com.v2vex.client.ui.screens.SpeedLabScreen
import com.v2vex.client.ui.theme.V2VexTheme
import com.v2vex.client.ui.theme.XrayCyan
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class RootTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Rounded.Bolt),
    NODES("Nodes", Icons.Rounded.Dns),
    SPEEDLAB("SpeedLab", Icons.Rounded.Speed),
    SETTINGS("Core", Icons.Rounded.Tune)
}

@Composable
fun V2VexRoot(
    app: V2VexApp,
    coreRefreshNonce: Int,
    onRequestToggleVpn: () -> Unit,
    onRequestImportLocalCore: () -> Unit
) {
    val profiles by app.repository.profiles.collectAsState()
    val subscriptions by app.repository.subscriptions.collectAsState()
    val settings by app.repository.settings.collectAsState()
    val selectedProfileId by app.repository.selectedProfileId.collectAsState()
    val speedHistory by app.repository.speedHistory.collectAsState()
    val vpnSnapshot by VpnStateHolder.snapshot.collectAsState()

    val selectedProfile = remember(profiles, selectedProfileId) {
        profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
    }

    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var currentTab by remember { mutableStateOf(RootTab.DASHBOARD) }
    var showLogs by remember { mutableStateOf(false) }
    var isTestingBatch by remember { mutableStateOf(false) }

    // Core manager state
    var coreInfo by remember { mutableStateOf(CoreInfo()) }
    var coreUpdateBusy by remember { mutableStateOf(false) }
    var coreUpdateProgress by remember { mutableFloatStateOf(0f) }
    var coreUpdateStatus by remember { mutableStateOf("") }

    // SpeedLab state
    var speedProgress by remember { mutableStateOf(SpeedTestProgress()) }
    var speedJob by remember { mutableStateOf<Job?>(null) }

    fun toast(msg: String) {
        scope.launch { snackbarHost.showSnackbar(msg) }
    }

    LaunchedEffect(coreRefreshNonce) {
        coreInfo = app.coreManager.inspectInstalledCore()
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
                    LogsScreen(
                        onBack = { showLogs = false },
                        onCopied = { toast("Logs copied to clipboard") }
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
                            onPingSelected = {
                                val target = selectedProfile ?: return@DashboardScreen
                                scope.launch {
                                    val ms = LatencyTester.testSingle(target)
                                    app.repository.updateLatencies(mapOf(target.id to ms))
                                    toast(if (ms > 0) "${target.name}: ${ms}ms" else "${target.name}: Unreachable")
                                }
                            },
                            onToggleFragment = {
                                app.repository.updateSettings {
                                    it.copy(fragment = it.fragment.copy(enabled = !it.fragment.enabled))
                                }
                            },
                            onToggleMux = {
                                app.repository.updateSettings {
                                    it.copy(mux = it.mux.copy(enabled = !it.mux.enabled))
                                }
                            },
                            onCycleRouting = {
                                val presets = RoutingPreset.entries
                                val next = presets[(presets.indexOf(settings.routingPreset) + 1) % presets.size]
                                app.repository.updateSettings { it.copy(routingPreset = next) }
                                toast("Routing: ${next.label}")
                            }
                        )

                        RootTab.NODES -> NodesScreen(
                            profiles = profiles,
                            subscriptions = subscriptions,
                            selectedProfileId = selectedProfile?.id,
                            settings = settings,
                            isTestingBatch = isTestingBatch,
                            onSelectProfile = { app.repository.selectProfile(it) },
                            onUpsertProfile = { app.repository.upsertProfile(it) },
                            onDeleteProfile = { app.repository.deleteProfile(it) },
                            onImportRaw = { raw ->
                                val parsed = UriParser.parseBatch(raw)
                                app.repository.addProfiles(parsed)
                                parsed.size
                            },
                            onPingAll = {
                                if (profiles.isEmpty()) return@NodesScreen
                                scope.launch {
                                    isTestingBatch = true
                                    val results = LatencyTester.testBatch(profiles) { id, ms ->
                                        app.repository.updateLatencies(mapOf(id to ms))
                                    }
                                    isTestingBatch = false
                                    val reachable = results.values.count { it > 0 }
                                    toast("RTT test complete: $reachable/${profiles.size} reachable")
                                }
                            },
                            onSelectFastest = {
                                scope.launch {
                                    isTestingBatch = true
                                    val results = LatencyTester.testBatch(profiles) { id, ms ->
                                        app.repository.updateLatencies(mapOf(id to ms))
                                    }
                                    isTestingBatch = false
                                    val best = results.entries
                                        .filter { it.value > 0 }
                                        .minByOrNull { it.value }
                                    if (best != null) {
                                        app.repository.selectProfile(best.key)
                                        val name = profiles.firstOrNull { it.id == best.key }?.name ?: "Node"
                                        toast("Auto-selected fastest: $name (${best.value}ms)")
                                    } else {
                                        toast("No reachable nodes found")
                                    }
                                }
                            },
                            onAddSubscription = { name, url ->
                                val sub = Subscription(name = name, url = url)
                                app.repository.upsertSubscription(sub)
                                scope.launch {
                                    toast("Syncing subscription '$name'...")
                                    SubscriptionManager.fetchSubscription(sub).fold(
                                        onSuccess = { res ->
                                            app.repository.upsertSubscription(res.updatedSubscription)
                                            app.repository.replaceSubscriptionProfiles(sub.id, res.profiles)
                                            toast("Synced '${sub.name}': ${res.profiles.size} node(s)")
                                        },
                                        onFailure = { toast("Sync failed: ${it.message}") }
                                    )
                                }
                            },
                            onRefreshSubscription = { sub ->
                                scope.launch {
                                    toast("Refreshing '${sub.name}'...")
                                    SubscriptionManager.fetchSubscription(sub).fold(
                                        onSuccess = { res ->
                                            app.repository.upsertSubscription(res.updatedSubscription)
                                            app.repository.replaceSubscriptionProfiles(sub.id, res.profiles)
                                            toast("Updated '${sub.name}': ${res.profiles.size} node(s)")
                                        },
                                        onFailure = { toast("Refresh failed: ${it.message}") }
                                    )
                                }
                            },
                            onDeleteSubscription = { subId ->
                                app.repository.deleteSubscription(subId, removeNodes = true)
                                toast("Subscription removed")
                            },
                            onShowMessage = { toast(it) }
                        )

                        RootTab.SPEEDLAB -> SpeedLabScreen(
                            progress = speedProgress,
                            history = speedHistory,
                            settings = settings.speedTest,
                            isVpnConnected = vpnSnapshot.state == VpnConnectionState.CONNECTED,
                            activeNodeName = selectedProfile?.name,
                            onStartTest = {
                                speedJob?.cancel()
                                speedJob = scope.launch {
                                    val useProxy = vpnSnapshot.state == VpnConnectionState.CONNECTED && coreInfo.executable
                                    runCatching {
                                        val record = app.speedTestEngine.runBenchmark(
                                            settings = settings.speedTest,
                                            useProxy = useProxy,
                                            httpProxyPort = settings.httpPort,
                                            profileId = selectedProfile?.id,
                                            profileName = selectedProfile?.name ?: "Direct Network",
                                            onProgress = { speedProgress = it }
                                        )
                                        app.repository.addSpeedRecord(record)
                                    }
                                }
                            },
                            onStopTest = {
                                speedJob?.cancel()
                                speedJob = null
                                speedProgress = SpeedTestProgress(statusDetail = "Benchmark cancelled")
                            },
                            onUpdateSpeedSettings = { next ->
                                app.repository.updateSettings { it.copy(speedTest = next) }
                            },
                            onClearHistory = {
                                app.repository.clearSpeedHistory()
                                toast("SpeedLab history cleared")
                            }
                        )

                        RootTab.SETTINGS -> SettingsScreen(
                            settings = settings,
                            coreInfo = coreInfo,
                            coreUpdateBusy = coreUpdateBusy,
                            coreUpdateProgress = coreUpdateProgress,
                            coreUpdateStatus = coreUpdateStatus,
                            onUpdateSettings = { app.repository.updateSettings(it) },
                            onCheckAndUpdateCore = {
                                scope.launch {
                                    coreUpdateBusy = true
                                    coreUpdateProgress = 0.05f
                                    coreUpdateStatus = "Checking ${settings.customCoreRepo}..."
                                    app.coreManager.checkLatestXrayRelease(settings.customCoreRepo).fold(
                                        onSuccess = { release ->
                                            app.coreManager.downloadAndInstallXrayCore(release) { frac, msg ->
                                                coreUpdateProgress = frac
                                                coreUpdateStatus = msg
                                            }.fold(
                                                onSuccess = { info ->
                                                    coreInfo = info
                                                    toast("Xray-core updated to ${release.tagName}")
                                                },
                                                onFailure = { err ->
                                                    coreUpdateStatus = "Update failed: ${err.message}"
                                                    toast("Core update failed: ${err.message}")
                                                }
                                            )
                                        },
                                        onFailure = { err ->
                                            coreUpdateStatus = "Check failed: ${err.message}"
                                            toast("GitHub check failed: ${err.message}")
                                        }
                                    )
                                    coreUpdateBusy = false
                                }
                            },
                            onUpdateGeoData = {
                                scope.launch {
                                    coreUpdateBusy = true
                                    coreUpdateProgress = 0.05f
                                    coreUpdateStatus = "Updating GeoIP & GeoSite..."
                                    app.coreManager.updateGeoData(settings.customGeoRepo) { frac, msg ->
                                        coreUpdateProgress = frac
                                        coreUpdateStatus = msg
                                    }.fold(
                                        onSuccess = { info ->
                                            coreInfo = info
                                            toast("GeoIP & GeoSite updated")
                                        },
                                        onFailure = { err ->
                                            coreUpdateStatus = "Geo update failed: ${err.message}"
                                            toast("Geo update failed: ${err.message}")
                                        }
                                    )
                                    coreUpdateBusy = false
                                }
                            },
                            onImportLocalCore = onRequestImportLocalCore,
                            onExportBackup = { app.repository.exportFullBackup() },
                            onImportBackup = { app.repository.importFullBackup(it) },
                            onOpenLogs = { showLogs = true },
                            onShowMessage = { toast(it) }
                        )
                    }
                }
            }
        }
    }
}
