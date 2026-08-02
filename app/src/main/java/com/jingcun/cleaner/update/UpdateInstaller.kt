package com.jingcun.cleaner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA 下载与引导式安装器。
 *
 * 关键修复（v2.3）：
 * - try/finally 确保 conn.disconnect()，防连接泄漏
 * - 下载循环检查 ensureActive()，响应协程取消
 * - 复制缓冲区 64KB 提升大文件效率
 * - 删除 Android 7.0 以下死代码分支（minSdk=31）
 * - install() 前检查 canRequestPackageInstalls()，未授权时返回特殊状态引导授权
 * - contentLength 为 -1 时进度显示已下载字节数
 */
class UpdateInstaller(private val context: Context) {

    data class DownloadProgress(val downloaded: Long, val total: Long) {
        val percent: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }

    /** 安装结果：区分"未授权安装未知应用"以引导用户授权 */
    sealed class InstallResult {
        data object Started : InstallResult()                      // 已调起系统安装器
        data object NoInstaller : InstallResult()                  // 系统无安装器
        data object NeedInstallPermission : InstallResult()        // 需用户授权"安装未知应用"
    }

    private val downloadDir: File by lazy {
        File(context.externalCacheDir, "updates").apply { if (!exists()) mkdirs() }
    }

    /**
     * 下载 APK 文件。
     * 关键修复：循环中检查 ensureActive() 响应取消；finally disconnect 防泄漏。
     */
    suspend fun download(
        url: String,
        onProgress: (DownloadProgress) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val targetFile = File(downloadDir, "jingcun_update.apk")
            if (targetFile.exists()) targetFile.delete()

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "JingCunCleaner-OTA")
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode != 200) return@withContext null

            val total = conn.contentLengthLong
            targetFile.outputStream().use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024) // 64KB 缓冲提升大文件效率
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        // 响应协程取消：用户点"取消"后立即停止下载
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        // 节流：每 200ms 回调一次进度，避免频繁重组 UI
                        if (downloaded - lastReport > 200 * 1024 || downloaded == total) {
                            onProgress(DownloadProgress(downloaded, total))
                            lastReport = downloaded
                        }
                    }
                }
            }
            targetFile.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 触发系统安装器安装 APK（引导式安装）。
     * 关键修复：检查 canRequestPackageInstalls()，未授权返回 NeedInstallPermission 引导授权。
     */
    fun install(apkFile: File): InstallResult {
        // Android 8.0+ 需检查"安装未知应用"权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                return InstallResult.NeedInstallPermission
            }
        }
        return runCatching {
            // minSdk=31，直接用 FileProvider，删除 Android 7.0 以下死代码
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return InstallResult.NoInstaller
            }
            context.startActivity(intent)
            InstallResult.Started
        }.getOrDefault(InstallResult.NoInstaller)
    }

    /** 跳转到"安装未知应用"授权页 */
    fun createInstallPermissionIntent(): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else null
    }.getOrNull()

    /** 清理已下载的旧 APK 文件 */
    fun cleanDownloaded() {
        runCatching { downloadDir.listFiles()?.forEach { it.delete() } }
    }
}
