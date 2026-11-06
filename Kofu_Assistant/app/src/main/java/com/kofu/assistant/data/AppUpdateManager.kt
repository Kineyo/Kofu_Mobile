package com.kofu.assistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val body: String,
    val apkDownloadUrl: String?,
    val releasePageUrl: String,
    val isNewer: Boolean
)

sealed class UpdateCheckResult {
    data object Idle : UpdateCheckResult()
    data object Loading : UpdateCheckResult()
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val CURRENT_VERSION_NAME = "2.1.0"
    const val CURRENT_VERSION_TAG = "2.1"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/Kineyo/Kofu_Mobile/releases"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatestRelease(currentVersion: String = CURRENT_VERSION_NAME): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Kofu-Mobile-App")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: No se pudo consultar GitHub Releases"))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Respuesta vacía de GitHub"))
                val releasesArray = JSONArray(body)
                if (releasesArray.length() == 0) {
                    return@withContext Result.failure(Exception("No se encontraron releases publicadas aún"))
                }

                val latestObj = releasesArray.getJSONObject(0)
                val tagName = latestObj.optString("tag_name", "").trim()
                val title = latestObj.optString("name", "Release $tagName")
                val releaseBody = latestObj.optString("body", "")
                val htmlUrl = latestObj.optString("html_url", "https://github.com/Kineyo/Kofu_Mobile/releases")

                val assetsArray = latestObj.optJSONArray("assets") ?: JSONArray()
                var apkUrl: String? = null
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }

                val isNewer = isTagNewer(tagName, currentVersion)
                Result.success(
                    ReleaseInfo(
                        tagName = tagName,
                        title = title,
                        body = releaseBody,
                        apkDownloadUrl = apkUrl ?: htmlUrl,
                        releasePageUrl = htmlUrl,
                        isNewer = isNewer
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando actualizaciones", e)
            Result.failure(e)
        }
    }

    fun isTagNewer(latestTag: String, currentVersion: String): Boolean {
        val cleanLatest = latestTag.removePrefix("v").replace(Regex("[^0-9.]"), "").trim()
        val cleanCurrent = currentVersion.removePrefix("v").replace(Regex("[^0-9.]"), "").trim()

        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
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
            Log.e(TAG, "Error abriendo URL de actualización: $downloadUrl", e)
        }
    }
}
