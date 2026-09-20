package com.watchpicture.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.watchpicture.app.BuildConfig
import com.watchpicture.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        const val GITHUB_OWNER = "137458"
        const val GITHUB_REPO = "watchpicture"
        const val REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
        const val RELEASES_URL = "$REPO_URL/releases"
        const val API_LATEST_RELEASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        /**
         * 多段语义化版本号比较：v1 > v2 返回 1，v1 < v2 返回 -1，相等返回 0。
         */
        fun compareVersions(v1: String, v2: String): Int {
            val clean1 = v1.trim().removePrefix("v").removePrefix("V")
            val clean2 = v2.trim().removePrefix("v").removePrefix("V")
            val parts1 = clean1.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
            val parts2 = clean2.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(parts1.size, parts2.size)

            for (i in 0 until maxLen) {
                val num1 = parts1.getOrElse(i) { 0 }
                val num2 = parts2.getOrElse(i) { 0 }
                if (num1 != num2) {
                    return num1.compareTo(num2)
                }
            }
            return 0
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun checkForUpdate(): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_LATEST_RELEASE)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "WatchPicture-Android-App")
            connection.connectTimeout = 12000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    Exception("检查更新失败: HTTP $responseCode")
                )
            }

            val bodyText = connection.inputStream.bufferedReader().use { it.readText() }
            val release = json.decodeFromString<GithubRelease>(bodyText)

            val remoteVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
            val currentVersion = BuildConfig.VERSION_NAME.trim().removePrefix("v").removePrefix("V")

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val downloadUrl = apkAsset?.browserDownloadUrl
            val apkSize = apkAsset?.size ?: 0L

            val hasUpdate = compareVersions(remoteVersion, currentVersion) > 0

            Result.success(
                UpdateCheckResult(
                    currentVersion = "v$currentVersion",
                    latestVersion = "v$remoteVersion",
                    releaseTitle = release.name ?: release.tagName,
                    changelog = release.body.orEmpty(),
                    publishedAt = release.publishedAt?.take(10).orEmpty(),
                    releaseUrl = release.htmlUrl ?: RELEASES_URL,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize,
                    hasUpdate = hasUpdate
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var apkFile: File? = null
        try {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val targetFile = File(cacheDir, "watchpicture-update.apk")
            apkFile = targetFile
            if (targetFile.exists()) targetFile.delete()

            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "WatchPicture-Android-App")
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            val totalBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            } else {
                connection.contentLength.toLong().takeIf { it > 0 } ?: 0L
            }
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                        onProgress(progress, downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            Result.success(targetFile)
        } catch (e: CancellationException) {
            apkFile?.delete()
            throw e
        } catch (e: Exception) {
            apkFile?.delete()
            Result.failure(e)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed: ${e.message}", e)
            try {
                Toast.makeText(context, "无法拉起系统安装器，请在浏览器或文件管理中打开", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开浏览器链接", Toast.LENGTH_SHORT).show()
        }
    }
}
