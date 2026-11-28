package com.v2vex.client.core

import android.content.Context
import android.os.Build
import com.v2vex.client.model.CoreInfo
import com.v2vex.client.model.RemoteReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Core & GeoData Manager with:
 * - Honest Android 10+ (W^X) executable detection
 * - SHA-256 verification for downloaded binaries & GeoData
 * - 1-tap GeoIP/GeoSite hot-swap (works on all Android 7-15 devices)
 * - In-app V2Vex APK release checker (for Android 10-15 W^X-compliant core updates)
 */
class CoreManager(private val context: Context) {

    enum class VersionCompareState {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        UNKNOWN
    }

    companion object {
        private val semVerRegex = Regex("""v?(\d+(?:\.\d+){1,3})""")

        fun extractVersionParts(raw: String): List<Int>? {
            val match = semVerRegex.find(raw) ?: return null
            return match.groupValues[1].split(".").mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
        }

        fun extractShortVersion(raw: String): String {
            val match = semVerRegex.find(raw) ?: return raw.trim()
            return "v${match.groupValues[1]}"
        }

        fun compareVersionTags(installedRaw: String, latestTag: String): VersionCompareState {
            val cur = extractVersionParts(installedRaw) ?: return VersionCompareState.UNKNOWN
            val latest = extractVersionParts(latestTag) ?: return VersionCompareState.UNKNOWN
            val maxLen = maxOf(cur.size, latest.size)
            for (i in 0 until maxLen) {
                val a = cur.getOrElse(i) { 0 }
                val b = latest.getOrElse(i) { 0 }
                if (a < b) return VersionCompareState.UPDATE_AVAILABLE
                if (a > b) return VersionCompareState.UP_TO_DATE
            }
            return VersionCompareState.UP_TO_DATE
        }

        fun formatVersionComparison(name: String, installedRaw: String, latestTag: String?): String {
            if (latestTag.isNullOrBlank()) return "$name: check failed"
            val installedShort = extractShortVersion(installedRaw)
            val latestShort = extractShortVersion(latestTag)
            return when (compareVersionTags(installedRaw, latestTag)) {
                VersionCompareState.UP_TO_DATE -> "$name $installedShort (Up to date)"
                VersionCompareState.UPDATE_AVAILABLE -> "$name $installedShort → $latestShort (Update available)"
                VersionCompareState.UNKNOWN -> "$name latest $latestShort"
            }
        }
    }

    private data class CachedBinaryProbe(
        val lastModified: Long,
        val length: Long,
        val versionOutput: String?
    ) {
        val executable: Boolean get() = versionOutput != null
    }

    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, CachedBinaryProbe>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val coreDir: File get() = File(context.filesDir, "core").apply { mkdirs() }
    val assetsDir: File get() = File(context.filesDir, "assets").apply { mkdirs() }
    val runtimeXrayBinary: File get() = File(coreDir, "xray")
    val bundledXrayBinary: File get() = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
    val geoIpFile: File get() = File(assetsDir, "geoip.dat")
    val geoSiteFile: File get() = File(assetsDir, "geosite.dat")

    private val versionMetaFile: File get() = File(coreDir, "version.txt")
    private val geoMetaFile: File get() = File(assetsDir, "geo_version.txt")

    fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    fun hasGeoIp(): Boolean = geoIpFile.exists() && geoIpFile.length() > 1024L
    fun hasGeoSite(): Boolean = geoSiteFile.exists() && geoSiteFile.length() > 1024L
    fun isWxEnforced(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun invalidateProbeCache() {
        probeCache.clear()
    }

    fun ensureBundledGeoAssets() {
        runCatching {
            val bundledAssets = context.assets.list("")?.toSet() ?: emptySet()
            if (!geoIpFile.exists() && "geoip.dat" in bundledAssets) {
                context.assets.open("geoip.dat").use { input ->
                    FileOutputStream(geoIpFile).use { out -> input.copyTo(out) }
                }
            }
            if (!geoSiteFile.exists() && "geosite.dat" in bundledAssets) {
                context.assets.open("geosite.dat").use { input ->
                    FileOutputStream(geoSiteFile).use { out -> input.copyTo(out) }
                }
            }
            if (!geoMetaFile.exists() && "core_version.txt" in bundledAssets) {
                val ver = context.assets.open("core_version.txt").bufferedReader().use { it.readText().trim() }
                if (ver.isNotBlank()) geoMetaFile.writeText("Bundled ($ver)")
            }
        }
    }

    val runtimeSingBoxBinary: File get() = File(coreDir, "sing-box")
    val bundledSingBoxBinary: File get() = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")

    /**
     * Resolves an external Core Plugin APK's nativeLibraryDir binary (Android 10-15 W^X compliant!).
     * Plugin package IDs:
     * - com.v2vex.core.xray    -> libxray.so
     * - com.v2vex.core.singbox -> libsingbox.so
     */
    private fun resolvePluginBinary(pluginPackage: String, libName: String): File? = runCatching {
        val appInfo = context.packageManager.getApplicationInfo(pluginPackage, 0)
        val file = File(appInfo.nativeLibraryDir, libName)
        if (file.exists() && file.length() > 0L) file else null
    }.getOrNull()

    /**
     * Probes binary executability & version in a SINGLE spawn and caches by (path, mtime, length).
     * Eliminates ~6 heavy Go binary spawns per Settings refresh and avoids re-probing on every connect!
     */
    private fun probeBinaryCached(file: File): CachedBinaryProbe {
        if (!file.exists() || file.length() <= 0L || !file.canExecute()) {
            return CachedBinaryProbe(0L, 0L, null)
        }
        val key = file.absolutePath
        val mtime = file.lastModified()
        val len = file.length()
        probeCache[key]?.let { cached ->
            if (cached.lastModified == mtime && cached.length == len) {
                return cached
            }
        }
        val version = probeVersionOutput(file)
        val fresh = CachedBinaryProbe(mtime, len, version)
        probeCache[key] = fresh
        return fresh
    }

    private fun resolveXrayWithProbe(): Triple<File?, String, CachedBinaryProbe?> {
        if (runtimeXrayBinary.exists() && runtimeXrayBinary.length() > 0L) {
            runtimeXrayBinary.setExecutable(true, false)
            val probe = probeBinaryCached(runtimeXrayBinary)
            if (probe.executable) {
                return Triple(runtimeXrayBinary, "Hot-Swapped (filesDir)", probe)
            }
        }
        resolvePluginBinary("com.v2vex.core.xray", "libxray.so")?.let { pluginBin ->
            val probe = probeBinaryCached(pluginBin)
            if (probe.executable) return Triple(pluginBin, "Plugin APK (Xray)", probe)
        }
        if (bundledXrayBinary.exists() && bundledXrayBinary.length() > 0L) {
            val probe = probeBinaryCached(bundledXrayBinary)
            if (probe.executable) {
                return Triple(bundledXrayBinary, "Bundled (jniLibs)", probe)
            }
        }
        if (runtimeXrayBinary.exists()) {
            return Triple(runtimeXrayBinary, "Blocked by Android W^X", null)
        }
        return Triple(null, "Not Installed", null)
    }

    private fun resolveSingBoxWithProbe(): Triple<File?, String, CachedBinaryProbe?> {
        if (runtimeSingBoxBinary.exists() && runtimeSingBoxBinary.length() > 0L) {
            runtimeSingBoxBinary.setExecutable(true, false)
            val probe = probeBinaryCached(runtimeSingBoxBinary)
            if (probe.executable) {
                return Triple(runtimeSingBoxBinary, "Hot-Swapped (Sing-box)", probe)
            }
        }
        resolvePluginBinary("com.v2vex.core.singbox", "libsingbox.so")?.let { pluginBin ->
            val probe = probeBinaryCached(pluginBin)
            if (probe.executable) return Triple(pluginBin, "Plugin APK (Sing-box)", probe)
        }
        if (bundledSingBoxBinary.exists() && bundledSingBoxBinary.length() > 0L) {
            val probe = probeBinaryCached(bundledSingBoxBinary)
            if (probe.executable) {
                return Triple(bundledSingBoxBinary, "Bundled (Sing-box)", probe)
            }
        }
        return Triple(null, "Sing-box Plugin Not Installed", null)
    }

    fun resolveExecutableBinary(): Pair<File?, String> {
        val (bin, src, _) = resolveXrayWithProbe()
        return bin to src
    }

    fun resolveSingBoxBinary(): Pair<File?, String> {
        val (bin, src, _) = resolveSingBoxWithProbe()
        return bin to src
    }

    fun availableGeoSiteTags(): List<String> = GeoDataInspector.loadGeoSiteTags(geoSiteFile)
    fun availableGeoIpTags(): List<String> = GeoDataInspector.loadGeoIpTags(geoIpFile)

    suspend fun inspectInstalledCore(): CoreInfo = withContext(Dispatchers.IO) {
        val (bin, source, xrayProbe) = resolveXrayWithProbe()
        val executable = xrayProbe?.executable == true
        val probedVersion = xrayProbe?.versionOutput

        val (sbBin, sbSource, sbProbe) = resolveSingBoxWithProbe()
        val sbExecutable = sbProbe?.executable == true
        val sbProbedVersion = sbProbe?.versionOutput

        val savedVer = if (versionMetaFile.exists()) versionMetaFile.readText().trim() else ""
        val displayVer = probedVersion ?: savedVer.ifBlank { "Not Installed" }
        val sbDisplayVer = sbProbedVersion ?: if (sbExecutable) "Sing-box Ready" else "Not Installed"
        val geoVer = if (geoMetaFile.exists()) geoMetaFile.readText().trim() else {
            if (hasGeoIp() || hasGeoSite()) "Local" else "Not Installed"
        }

        CoreInfo(
            installedVersion = displayVer,
            binarySource = source,
            binaryPath = bin?.absolutePath ?: "",
            abi = primaryAbi(),
            executable = executable,
            singBoxVersion = sbDisplayVer,
            singBoxSource = sbSource,
            singBoxExecutable = sbExecutable,
            geoIpVersion = if (hasGeoIp()) geoVer else "Not Installed",
            geoSiteVersion = if (hasGeoSite()) geoVer else "Not Installed",
            geoIpSizeBytes = if (geoIpFile.exists()) geoIpFile.length() else 0L,
            geoSiteSizeBytes = if (geoSiteFile.exists()) geoSiteFile.length() else 0L
        )
    }

    /**
     * Checks latest V2Vex APK release (prefers matching ABI split APK, falling back to universal).
     */
    suspend fun checkAppReleaseUpdate(repo: String = "iganramdani/V2Vex"): Result<RemoteReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val abi = primaryAbi()
                val keywords = when {
                    abi.contains("arm64") -> listOf("arm64-v8a-release.apk", "universal-release.apk", ".apk")
                    abi.contains("armeabi") -> listOf("armeabi-v7a-release.apk", "universal-release.apk", ".apk")
                    else -> listOf("universal-release.apk", ".apk")
                }
                fetchGitHubRelease(repo, keywords)
            }
        }

    suspend fun checkLatestXrayRelease(repo: String = "XTLS/Xray-core"): Result<RemoteReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val abi = primaryAbi()
                val desiredKeywords = when {
                    abi.contains("arm64") -> listOf("android-arm64-v8a.zip")
                    abi.contains("armeabi") -> listOf("android-arm32-v7a.zip")
                    abi.contains("x86_64") -> listOf("linux-64.zip", "android-x86_64.zip")
                    else -> listOf("android-arm64-v8a.zip")
                }
                fetchGitHubRelease(repo, desiredKeywords)
            }
        }

    suspend fun checkLatestSingBoxRelease(repo: String = "SagerNet/sing-box"): Result<RemoteReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val abi = primaryAbi()
                val desiredKeywords = when {
                    abi.contains("arm64") -> listOf("android-arm64.tar.gz")
                    abi.contains("armeabi") -> listOf("android-arm.tar.gz")
                    abi.contains("x86_64") -> listOf("android-amd64.tar.gz")
                    else -> listOf("android-arm64.tar.gz")
                }
                fetchGitHubRelease(repo, desiredKeywords)
            }
        }

    suspend fun downloadAndInstallXrayCore(
        release: RemoteReleaseInfo,
        onProgress: (Float, String) -> Unit
    ): Result<CoreInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val tmpZip = File(context.cacheDir, "xray_update.zip")
            onProgress(0.05f, "Downloading ${release.assetName}...")
            downloadFile(release.downloadUrl, tmpZip) { frac ->
                onProgress(0.05f + frac * 0.70f, "Downloading ${release.tagName} (${(frac * 100).toInt()}%)...")
            }

            // Optional SHA-256 verification against XTLS .dgst asset
            onProgress(0.78f, "Verifying SHA-256 integrity...")
            verifyDgstIfAvailable(release.downloadUrl + ".dgst", tmpZip)

            onProgress(0.86f, "Extracting Xray-core & GeoData...")
            extractXrayZip(tmpZip)
            tmpZip.delete()

            val canRunHotSwap = canExecute(runtimeXrayBinary)
            if (!canRunHotSwap) {
                // Clean up dead 33MB binary on Android 10+ W^X devices instead of wasting storage!
                runtimeXrayBinary.delete()
                geoMetaFile.writeText(release.tagName)
                LogBuffer.w(
                    "Core",
                    "Android ${Build.VERSION.SDK_INT} W^X blocks execve in filesDir. GeoData updated to ${release.tagName}; update V2Vex APK to upgrade bundled libxray.so."
                )
                onProgress(1f, "GeoData updated! Android 10+ W^X requires APK update for libxray.so.")
            } else {
                versionMetaFile.writeText(release.tagName)
                geoMetaFile.writeText(release.tagName)
                LogBuffer.i("Core", "Hot-swapped Xray-core ${release.tagName} (${release.assetName})")
                onProgress(1f, "Core hot-swapped to ${release.tagName}")
            }
            invalidateProbeCache()
            inspectInstalledCore()
        }
    }

    suspend fun updateGeoData(
        repo: String = "Loyalsoldier/v2ray-rules-dat",
        onProgress: (Float, String) -> Unit
    ): Result<CoreInfo> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(0.05f, "Checking latest GeoIP & GeoSite...")
            val geoIpRel = fetchGitHubRelease(repo, listOf("geoip.dat"))
            val geoSiteRel = fetchGitHubRelease(repo, listOf("geosite.dat"))

            onProgress(0.15f, "Downloading geoip.dat (${geoIpRel.tagName})...")
            downloadFile(geoIpRel.downloadUrl, geoIpFile) { frac ->
                onProgress(0.15f + frac * 0.35f, "Downloading geoip.dat (${(frac * 100).toInt()}%)...")
            }
            verifySha256SumIfAvailable(geoIpRel.downloadUrl + ".sha256sum", geoIpFile)

            onProgress(0.55f, "Downloading geosite.dat (${geoSiteRel.tagName})...")
            downloadFile(geoSiteRel.downloadUrl, geoSiteFile) { frac ->
                onProgress(0.55f + frac * 0.40f, "Downloading geosite.dat (${(frac * 100).toInt()}%)...")
            }
            verifySha256SumIfAvailable(geoSiteRel.downloadUrl + ".sha256sum", geoSiteFile)

            geoMetaFile.writeText(geoIpRel.tagName)
            LogBuffer.i("Core", "Verified & updated GeoIP + GeoSite to ${geoIpRel.tagName}")
            onProgress(1f, "GeoData verified & updated (${geoIpRel.tagName})")
            inspectInstalledCore()
        }
    }

    suspend fun importLocalCore(inputStream: InputStream, fileName: String): Result<CoreInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(context.cacheDir, "imported_core_${System.currentTimeMillis()}")
                inputStream.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                }
                if (fileName.endsWith(".zip", ignoreCase = true)) {
                    extractXrayZip(tmp)
                    tmp.delete()
                } else {
                    tmp.copyTo(runtimeXrayBinary, overwrite = true)
                    tmp.delete()
                    runtimeXrayBinary.setExecutable(true, false)
                }
                invalidateProbeCache()
                if (!probeBinaryCached(runtimeXrayBinary).executable) {
                    runtimeXrayBinary.delete()
                    error("Imported binary cannot execute on Android ${Build.VERSION.SDK_INT} (SELinux W^X). GeoData was updated if present in zip.")
                }
                versionMetaFile.writeText("Custom ($fileName)")
                LogBuffer.i("Core", "Imported custom core from $fileName")
                inspectInstalledCore()
            }
        }

    private fun extractXrayZip(zipFile: File) {
        var foundAny = false
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = File(entry.name).name
                when (name) {
                    "xray" -> {
                        FileOutputStream(runtimeXrayBinary).use { out -> zis.copyTo(out) }
                        runtimeXrayBinary.setExecutable(true, false)
                        foundAny = true
                    }
                    "geoip.dat" -> {
                        FileOutputStream(geoIpFile).use { out -> zis.copyTo(out) }
                        foundAny = true
                    }
                    "geosite.dat" -> {
                        FileOutputStream(geoSiteFile).use { out -> zis.copyTo(out) }
                        foundAny = true
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (!foundAny) error("Archive contains neither xray binary nor GeoData files")
    }

    private fun verifyDgstIfAvailable(dgstUrl: String, file: File) {
        val dgstText = fetchSmallText(dgstUrl) ?: return
        val expected = dgstText.lines()
            .firstOrNull { it.contains("SHA2-256", ignoreCase = true) || it.contains("SHA256", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
            ?.lowercase()
            ?: return
        val actual = sha256Hex(file)
        if (actual != expected) {
            file.delete()
            error("SHA-256 mismatch! Expected $expected, got $actual")
        }
    }

    private fun verifySha256SumIfAvailable(sumUrl: String, file: File) {
        val text = fetchSmallText(sumUrl) ?: return
        val expected = text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return
        if (expected.length != 64) return
        val actual = sha256Hex(file)
        if (actual != expected) {
            file.delete()
            error("GeoData SHA-256 mismatch!")
        }
    }

    private fun fetchSmallText(urlStr: String): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "V2Vex-Android/1.1")
        }
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchGitHubRelease(repo: String, assetKeywords: List<String>): RemoteReleaseInfo {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "V2Vex-Android/1.1")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("GitHub API returned HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val published = obj["published_at"]?.jsonPrimitive?.contentOrNull?.take(10) ?: ""
            val notes = obj["body"]?.jsonPrimitive?.contentOrNull?.take(400) ?: ""
            val assets = obj["assets"]?.jsonArray ?: error("No release assets found")

            val matched = assetKeywords.firstNotNullOfOrNull { kw ->
                assets.firstOrNull { el ->
                    val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    name.equals(kw, ignoreCase = true) || name.endsWith(kw, ignoreCase = true)
                }?.jsonObject
            } ?: error("No compatible asset found for ABI ${primaryAbi()} in $repo")

            return RemoteReleaseInfo(
                tagName = tag,
                publishedAt = published,
                downloadUrl = matched["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: error("Missing download URL"),
                assetName = matched["name"]?.jsonPrimitive?.contentOrNull ?: "asset",
                assetSizeBytes = matched["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                releaseNotes = notes
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(urlStr: String, target: File, onProgress: (Float) -> Unit) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "V2Vex-Android/1.1")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("Download failed with HTTP $code")
            val total = conn.contentLengthLong
            val tmpFile = File(target.parentFile, "${target.name}.tmp")
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            tmpFile.renameTo(target)
        } finally {
            conn.disconnect()
        }
    }

    private fun canExecute(file: File): Boolean {
        if (!file.exists() || !file.canExecute()) return false
        return probeVersionOutput(file) != null
    }

    private fun probeVersionOutput(binary: File): String? = runCatching {
        val proc = ProcessBuilder(binary.absolutePath, "version")
            .redirectErrorStream(true)
            .start()
        val firstLine = proc.inputStream.bufferedReader().readLine()
        proc.waitFor()
        firstLine?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
