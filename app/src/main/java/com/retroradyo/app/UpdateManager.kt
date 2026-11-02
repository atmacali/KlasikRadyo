package com.retroradyo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK, downloads it and hands it to the system installer. */
object UpdateManager {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/atmacali/KlasikRadyo/releases/latest"
    private const val UPDATE_DIR = "updates"
    private const val UPDATE_FILE = "RetroRadyo-update.apk"
    private const val TIMEOUT_MS = 15_000
    private val TRUSTED_HOSTS = listOf("github.com", "githubusercontent.com")
    private val TAG_PATTERN = Regex("""build-(\d+)""")

    class Release(val versionCode: Long, val tag: String, val apkUrl: String, val size: Long)

    fun installedVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Returns null when the repository has no usable release yet. */
    fun fetchLatest(): Release? {
        val connection = open(LATEST_RELEASE_URL).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("GitHub yanıtı: ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val code = TAG_PATTERN.find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val url = asset.optString("browser_download_url")
                if (asset.optString("name").endsWith(".apk", ignoreCase = true) && isTrusted(url)) {
                    return Release(code, tag, url, asset.optLong("size", -1L))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, UPDATE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, UPDATE_FILE)
        val partial = File(dir, "$UPDATE_FILE.part")
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("İndirme hatası: ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            var downloaded = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (total > 0 && downloaded != total) throw java.io.IOException("İndirme eksik kaldı")
            if (!partial.renameTo(target)) throw java.io.IOException("Dosya kaydedilemedi")
            verifyApk(context, target)
            return target
        } catch (e: Exception) {
            partial.delete()
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    // The system installer still enforces the signing key; this only rejects wrong or stale packages early.
    private fun verifyApk(context: Context, file: File) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw java.io.IOException("APK okunamadı")
        if (info.packageName != context.packageName) throw java.io.IOException("Paket adı uyuşmuyor")
        if (PackageInfoCompat.getLongVersionCode(info) <= installedVersionCode(context)) {
            throw java.io.IOException("İndirilen sürüm daha yeni değil")
        }
    }

    fun installIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun isTrusted(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host ?: return false
        return parsed.scheme == "https" && TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RetroRadyo-Updater")
        }
}
