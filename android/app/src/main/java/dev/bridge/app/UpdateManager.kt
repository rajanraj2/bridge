package dev.bridge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val version: String,
    val releaseName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkDownloadUrl: String?
)

object UpdateManager {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/rajanraj2/bridge/releases/latest"

    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val url = URL(LATEST_RELEASE_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Bridge-Android-App")
            conn.setRequestProperty("Accept", "application/vnd.github+json")

            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode} while checking for updates"))
            }

            val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val tagName = json.optString("tag_name", "")
            val remoteVersion = tagName.removePrefix("v").trim()
            val releaseName = json.optString("name", "Bridge $tagName")
            val releaseNotes = json.optString("body", "")
            val releaseUrl = json.optString("html_url", "https://github.com/rajanraj2/bridge/releases")

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        val dl = asset.optString("browser_download_url", "")
                        apkUrl = if (dl.isNotBlank()) dl else null
                        break
                    }
                }
            }

            if (isNewerVersion(remoteVersion, currentVersion)) {
                val info = UpdateInfo(
                    tagName = tagName,
                    version = remoteVersion,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    releaseUrl = releaseUrl,
                    apkDownloadUrl = apkUrl ?: releaseUrl
                )
                Result.success(info)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun openDownload(context: Context, downloadUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            println("UpdateManager: failed to open download URL: ${e.message}")
        }
    }
}
