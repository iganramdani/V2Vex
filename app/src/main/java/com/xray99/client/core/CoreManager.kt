package com.xray99.client.core

import android.content.Context
import android.os.Build
import com.xray99.client.model.CoreInfo
import com.xray99.client.model.RemoteReleaseInfo
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
import java.util.zip.ZipInputStream

/**
 * Hot-swappable Xray-core & GeoData updater.
 *
 * Resolution priority:
 * 1. User-updated / imported binary in filesDir/core/xray (if executable on device)
 * 2. Bundled native binary in applicationInfo.nativeLibraryDir/libxray.so (Android 10+ W^X safe)
 *
 * GeoData (geoip.dat & geosite.dat) always live in filesDir/assets/ so they can be
 * updated at runtime on any Android version without rebuilding the APK.
 */
class CoreManager(private val context: Context) {

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

    /**
     * Resolves the best runnable Xray binary on this device.
     */
    fun resolveExecutableBinary(): Pair<File?, String> {
        if (runtimeXrayBinary.exists() && runtimeXrayBinary.length() > 0L) {
            runtimeXrayBinary.setExecutable(true, false)
            if (canExecute(runtimeXrayBinary)) {
                return runtimeXrayBinary to "Hot-Swapped (filesDir)"
            }
        }
        if (bundledXrayBinary.exists() && bundledXrayBinary.length() > 0L) {
            if (canExecute(bundledXrayBinary)) {
                return bundledXrayBinary to "Bundled (jniLibs)"
            }
        }
        // Return runtime binary even if W^X restricts direct execve so UI can report accurately
        if (runtimeXrayBinary.exists()) {
            return runtimeXrayBinary to "Downloaded (W^X Restricted)"
        }
        return null to "Not Installed"
    }

    suspend fun inspectInstalledCore(): CoreInfo = withContext(Dispatchers.IO) {
        val (bin, source) = resolveExecutableBinary()
        val probedVersion = if (bin != null && canExecute(bin)) {
            probeVersionOutput(bin)
        } else null

        val savedVer = if (versionMetaFile.exists()) versionMetaFile.readText().trim() else ""
        val displayVer = probedVersion ?: savedVer.ifBlank { "Not Installed" }
        val geoVer = if (geoMetaFile.exists()) geoMetaFile.readText().trim() else {
            if (geoIpFile.exists() || geoSiteFile.exists()) "Local" else "Not Installed"
        }

        CoreInfo(
            installedVersion = displayVer,
            binarySource = source,
            binaryPath = bin?.absolutePath ?: "",
            abi = primaryAbi(),
            executable = bin != null && canExecute(bin),
            geoIpVersion = if (geoIpFile.exists()) geoVer else "Not Installed",
            geoSiteVersion = if (geoSiteFile.exists()) geoVer else "Not Installed",
            geoIpSizeBytes = if (geoIpFile.exists()) geoIpFile.length() else 0L,
            geoSiteSizeBytes = if (geoSiteFile.exists()) geoSiteFile.length() else 0L
        )
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

    suspend fun downloadAndInstallXrayCore(
        release: RemoteReleaseInfo,
        onProgress: (Float, String) -> Unit
    ): Result<CoreInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val tmpZip = File(context.cacheDir, "xray_update.zip")
            onProgress(0.05f, "Downloading ${release.assetName}...")
            downloadFile(release.downloadUrl, tmpZip) { frac ->
                onProgress(0.05f + frac * 0.75f, "Downloading ${release.tagName} (${(frac * 100).toInt()}%)...")
            }

            onProgress(0.85f, "Extracting Xray-core & GeoData...")
            extractXrayZip(tmpZip)
            tmpZip.delete()

            versionMetaFile.writeText(release.tagName)
            geoMetaFile.writeText(release.tagName)
            LogBuffer.i("Core", "Installed Xray-core ${release.tagName} (${release.assetName})")

            onProgress(1f, "Core updated to ${release.tagName}")
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

            onProgress(0.55f, "Downloading geosite.dat (${geoSiteRel.tagName})...")
            downloadFile(geoSiteRel.downloadUrl, geoSiteFile) { frac ->
                onProgress(0.55f + frac * 0.40f, "Downloading geosite.dat (${(frac * 100).toInt()}%)...")
            }

            geoMetaFile.writeText(geoIpRel.tagName)
            LogBuffer.i("Core", "Updated GeoIP & GeoSite to ${geoIpRel.tagName}")
            onProgress(1f, "GeoData updated (${geoIpRel.tagName})")
            inspectInstalledCore()
        }
    }

    /**
     * Imports a user-selected Xray-core .zip archive OR raw xray binary from storage.
     */
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
                versionMetaFile.writeText("Custom ($fileName)")
                LogBuffer.i("Core", "Imported custom core from $fileName")
                inspectInstalledCore()
            }
        }

    private fun extractXrayZip(zipFile: File) {
        var foundBinary = false
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = File(entry.name).name
                when (name) {
                    "xray" -> {
                        FileOutputStream(runtimeXrayBinary).use { out -> zis.copyTo(out) }
                        runtimeXrayBinary.setExecutable(true, false)
                        foundBinary = true
                    }
                    "geoip.dat" -> {
                        FileOutputStream(geoIpFile).use { out -> zis.copyTo(out) }
                    }
                    "geosite.dat" -> {
                        FileOutputStream(geoSiteFile).use { out -> zis.copyTo(out) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (!foundBinary) {
            error("Archive does not contain an 'xray' executable binary")
        }
    }

    private fun fetchGitHubRelease(repo: String, assetKeywords: List<String>): RemoteReleaseInfo {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "XRay99-Android/1.0")
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
            setRequestProperty("User-Agent", "XRay99-Android/1.0")
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
