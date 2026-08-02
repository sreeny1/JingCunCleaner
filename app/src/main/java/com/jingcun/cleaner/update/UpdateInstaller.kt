package com.jingcun.cleaner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA 下载与引导式安装器。
 *
 * 引导式安装流程（无需 root/系统签名）：
 * 1. 下载 APK 到 externalCacheDir（应用专属目录，无需权限）
 * 2. 通过 FileProvider 生成 content:// URI
 * 3. Intent ACTION_VIEW + type application/vnd.android.package-archive
 * 4. 系统弹出安装确认页，用户点击安装
 *
 * 参考：
 * - Android 官方 PackageInstaller 引导安装文档
 * - mishudo/PI-TestKt 的 FileProvider 安装示例
 */
class UpdateInstaller(private val context: Context) {

    data class DownloadProgress(val downloaded: Long, val total: Long) {
        val percent: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }

    private val downloadDir: File by lazy {
        File(context.externalCacheDir, "updates").apply { if (!exists()) mkdirs() }
    }

    /**
     * 下载 APK 文件。
     * @return 下载完成的 File，失败返回 null
     */
    suspend fun download(
        url: String,
        onProgress: (DownloadProgress) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val targetFile = File(downloadDir, "jingcun_update.apk")
            // 删除旧文件避免脏数据
            if (targetFile.exists()) targetFile.delete()

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "JingCunCleaner-OTA")
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode != 200) return@runCatching null

            val total = conn.contentLengthLong
            targetFile.outputStream().use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(DownloadProgress(downloaded, total))
                    }
                }
            }
            conn.disconnect()
            targetFile.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull()
    }

    /**
     * 触发系统安装器安装 APK（引导式安装，用户需手动确认）。
     * 支持 Android 7.0+ 的 FileProvider 方案。
     */
    fun install(apkFile: File): Boolean = runCatching {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // FileProvider 的 content:// URI 必须授予读写权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        // 校验系统存在能处理该 Intent 的 Activity
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 清理已下载的旧 APK 文件 */
    fun cleanDownloaded() {
        runCatching { downloadDir.listFiles()?.forEach { it.delete() } }
    }
}
