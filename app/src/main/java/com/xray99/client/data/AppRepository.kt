package com.xray99.client.data

import android.content.Context
import com.xray99.client.core.LogBuffer
import com.xray99.client.model.AppSettings
import com.xray99.client.model.ProxyProtocol
import com.xray99.client.model.SecurityType
import com.xray99.client.model.ServerProfile
import com.xray99.client.model.SpeedTestRecord
import com.xray99.client.model.Subscription
import com.xray99.client.model.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class FullBackupPayload(
    val version: Int = 1,
    val profiles: List<ServerProfile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val selectedProfileId: String? = null
)

class AppRepository(context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storageDir = File(context.filesDir, "store").apply { mkdirs() }
    private val profilesFile = File(storageDir, "profiles.json")
    private val subsFile = File(storageDir, "subscriptions.json")
    private val settingsFile = File(storageDir, "settings.json")
    private val historyFile = File(storageDir, "speed_history.json")
    private val metaFile = File(storageDir, "selected_id.txt")

    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    private val _speedHistory = MutableStateFlow<List<SpeedTestRecord>>(emptyList())
    val speedHistory: StateFlow<List<SpeedTestRecord>> = _speedHistory.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        _settings.value = readJson(settingsFile) ?: AppSettings()
        _subscriptions.value = readJson(subsFile) ?: emptyList()
        _speedHistory.value = readJson(historyFile) ?: emptyList()

        val loadedProfiles: List<ServerProfile>? = readJson(profilesFile)
        if (loadedProfiles == null) {
            // Seed starter templates on first launch so user can immediately explore & edit
            val starters = listOf(
                ServerProfile(
                    name = "SG-01 · VLESS REALITY Vision",
                    protocol = ProxyProtocol.VLESS,
                    address = "sg01.example.com",
                    port = 443,
                    uuidOrPassword = "00000000-0000-0000-0000-000000000001",
                    flow = "xtls-rprx-vision",
                    transport = TransportType.TCP,
                    security = SecurityType.REALITY,
                    sni = "www.cloudflare.com",
                    fingerprint = "chrome",
                    realityPublicKey = "REPLACE_WITH_REALITY_PUBLIC_KEY",
                    realityShortId = "a1b2c3d4"
                ),
                ServerProfile(
                    name = "JP-02 · VLESS XHTTP TLS",
                    protocol = ProxyProtocol.VLESS,
                    address = "jp02.example.com",
                    port = 443,
                    uuidOrPassword = "00000000-0000-0000-0000-000000000002",
                    transport = TransportType.XHTTP,
                    host = "jp02.example.com",
                    path = "/xhttp",
                    xhttpMode = "auto",
                    security = SecurityType.TLS,
                    sni = "jp02.example.com",
                    fingerprint = "firefox",
                    alpn = "h2,http/1.1"
                ),
                ServerProfile(
                    name = "US-03 · Trojan gRPC TLS",
                    protocol = ProxyProtocol.TROJAN,
                    address = "us03.example.com",
                    port = 443,
                    uuidOrPassword = "replace-with-trojan-password",
                    transport = TransportType.GRPC,
                    path = "xray99-grpc",
                    grpcMode = "multi",
                    security = SecurityType.TLS,
                    sni = "us03.example.com",
                    fingerprint = "safari"
                )
            )
            _profiles.value = starters
            _selectedProfileId.value = starters.first().id
            saveProfiles()
            saveSelectedId()
        } else {
            _profiles.value = loadedProfiles
            val savedId = if (metaFile.exists()) metaFile.readText().trim().ifBlank { null } else null
            _selectedProfileId.value = savedId?.takeIf { id -> loadedProfiles.any { it.id == id } }
                ?: loadedProfiles.firstOrNull()?.id
        }
    }

    fun getSelectedProfile(): ServerProfile? {
        val id = _selectedProfileId.value ?: return _profiles.value.firstOrNull()
        return _profiles.value.firstOrNull { it.id == id } ?: _profiles.value.firstOrNull()
    }

    fun selectProfile(id: String) {
        _selectedProfileId.value = id
        saveSelectedId()
    }

    fun upsertProfile(profile: ServerProfile) {
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(0, profile)
        _profiles.value = list
        if (_selectedProfileId.value == null) {
            _selectedProfileId.value = profile.id
            saveSelectedId()
        }
        saveProfiles()
    }

    fun addProfiles(newProfiles: List<ServerProfile>) {
        if (newProfiles.isEmpty()) return
        val list = _profiles.value.toMutableList()
        list.addAll(0, newProfiles)
        _profiles.value = list
        if (_selectedProfileId.value == null) {
            _selectedProfileId.value = newProfiles.first().id
            saveSelectedId()
        }
        saveProfiles()
    }

    fun replaceSubscriptionProfiles(subscriptionId: String, newProfiles: List<ServerProfile>) {
        val kept = _profiles.value.filter { it.subscriptionId != subscriptionId }
        _profiles.value = newProfiles + kept
        if (_selectedProfileId.value == null || _profiles.value.none { it.id == _selectedProfileId.value }) {
            _selectedProfileId.value = _profiles.value.firstOrNull()?.id
            saveSelectedId()
        }
        saveProfiles()
    }

    fun deleteProfile(id: String) {
        val updated = _profiles.value.filterNot { it.id == id }
        _profiles.value = updated
        if (_selectedProfileId.value == id) {
            _selectedProfileId.value = updated.firstOrNull()?.id
            saveSelectedId()
        }
        saveProfiles()
    }

    fun updateLatencies(results: Map<String, Int>) {
        _profiles.value = _profiles.value.map { p ->
            val ms = results[p.id]
            if (ms != null) p.copy(latencyMs = ms) else p
        }
        saveProfiles()
    }

    fun updateProfileSpeed(profileId: String?, downloadMbps: Double, uploadMbps: Double) {
        if (profileId == null) return
        _profiles.value = _profiles.value.map { p ->
            if (p.id == profileId) p.copy(lastDownloadMbps = downloadMbps, lastUploadMbps = uploadMbps)
            else p
        }
        saveProfiles()
    }

    fun upsertSubscription(sub: Subscription) {
        val list = _subscriptions.value.toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(0, sub)
        _subscriptions.value = list
        writeJson(subsFile, list)
    }

    fun deleteSubscription(subId: String, removeNodes: Boolean = true) {
        _subscriptions.value = _subscriptions.value.filterNot { it.id == subId }
        writeJson(subsFile, _subscriptions.value)
        if (removeNodes) {
            _profiles.value = _profiles.value.filterNot { it.subscriptionId == subId }
            saveProfiles()
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        writeJson(settingsFile, next)
    }

    fun addSpeedRecord(record: SpeedTestRecord) {
        val next = (listOf(record) + _speedHistory.value).take(50)
        _speedHistory.value = next
        writeJson(historyFile, next)
        updateProfileSpeed(record.profileId, record.downloadMbps, record.uploadMbps)
    }

    fun clearSpeedHistory() {
        _speedHistory.value = emptyList()
        writeJson(historyFile, emptyList<SpeedTestRecord>())
    }

    fun exportFullBackup(): String {
        val payload = FullBackupPayload(
            profiles = _profiles.value,
            subscriptions = _subscriptions.value,
            settings = _settings.value,
            selectedProfileId = _selectedProfileId.value
        )
        return json.encodeToString(payload)
    }

    fun importFullBackup(rawJson: String): Result<Int> = runCatching {
        val payload = json.decodeFromString<FullBackupPayload>(rawJson)
        _profiles.value = payload.profiles
        _subscriptions.value = payload.subscriptions
        _settings.value = payload.settings
        _selectedProfileId.value = payload.selectedProfileId ?: payload.profiles.firstOrNull()?.id
        saveProfiles()
        writeJson(subsFile, _subscriptions.value)
        writeJson(settingsFile, _settings.value)
        saveSelectedId()
        LogBuffer.i("Backup", "Restored ${payload.profiles.size} profiles & settings")
        payload.profiles.size
    }

    private fun saveProfiles() = writeJson(profilesFile, _profiles.value)
    private fun saveSelectedId() {
        runCatching { metaFile.writeText(_selectedProfileId.value.orEmpty()) }
    }

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    private inline fun <reified T> writeJson(file: File, value: T) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(value))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
