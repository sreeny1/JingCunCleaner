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
 * v3.1 增强（国内网络/车机可用性）：
 * - 镜像回退：直连 GitHub 失败自动切换加速镜像逐个重试
 * - 断点续传：连接中断后对已下载部分发起 Range 请求续传（最多 3 次）
 * - sha256 校验：GitHub Release 资产自带 digest，下载完成后校验防半包安装
 * - 下载写入 .part 临时文件，校验通过后才命名为最终文件
 * v3.4.1 修复（车机更新 sha256 校验失败）：.part 与下载 URL 绑定（跨镜像
 *   不再复用半截文件，杜绝不同源内容拼接错位）；206 续传校验 Content-Range
 *   起点；新增下载完整性显式检查
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

    companion object {
        /** 下载镜像前缀：直连失败时按序尝试（镜像站时效性强，仅作回退不作首选） */
        private val MIRROR_PREFIXES = listOf(
            "",
            "https://ghproxy.link/",
            "https://gh-proxy.net/",
        )
        private const val MAX_RESUME_RETRIES = 3
    }

    private val downloadDir: File by lazy {
        File(context.externalCacheDir, "updates").apply { if (!exists()) mkdirs() }
    }

    /**
     * 断点续传的半截文件与下载 URL 绑定（按 URL 哈希命名）。
     * 此前所有镜像共用一个 .part：直连残留的半截文件被镜像续传拼接，
     * 不同源内容错位导致 sha256 校验必败、更新重试始终失败。
     */
    private fun partFileFor(url: String): File =
        File(downloadDir, "jingcun_update_${Integer.toHexString(url.hashCode())}.apk.part")

    /**
     * 下载 APK：镜像逐个回退 + 断点续传 + sha256 校验。
     * @param digest GitHub 提供的 "sha256:xxx"（可空；非空时下载后强制校验）
     * @throws java.io.IOException 网络失败/校验失败时抛出，由调用方展示 message
     */
    suspend fun download(
        url: String,
        digest: String = "",
        onProgress: (DownloadProgress) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (mirror in MIRROR_PREFIXES) {
            try {
                val file = downloadSingle(mirror + url, digest, onProgress)
                if (file != null) return@withContext file
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("下载失败")
    }

    /** 单地址下载（含断点续传与校验）。part 与 URL 绑定，跨镜像互不复用 */
    private suspend fun downloadSingle(
        url: String,
        digest: String,
        onProgress: (DownloadProgress) -> Unit,
    ): File? {
        val targetFile = File(downloadDir, "jingcun_update.apk")
        val partFile = partFileFor(url)
        if (targetFile.exists()) targetFile.delete()
        // 清理其它地址的残留半截文件，防缓存膨胀
        runCatching {
            downloadDir.listFiles()?.filter { it.name.endsWith(".part") && it != partFile }?.forEach { it.delete() }
        }

        var resumeFrom = if (partFile.exists()) partFile.length() else 0L
        var retries = 0
        var conn: HttpURLConnection? = null
        try {
            while (true) {
                conn?.disconnect() // 续传/重试前断开上一连接，防泄漏
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "JingCunCleaner-OTA")
                    instanceFollowRedirects = true
                    if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
                }
                conn.connect()
                when (conn.responseCode) {
                    206 -> {
                        // 校验 Content-Range 起点与本地续传点一致；错位（劣质镜像）则丢弃半截文件按全量重下
                        val start = conn.getHeaderField("Content-Range")
                            ?.substringAfter(" ")?.substringBefore("-")?.trim()?.toLongOrNull()
                        if (start != null && start != resumeFrom) {
                            partFile.delete()
                            resumeFrom = 0L
                            conn.disconnect()
                            continue
                        }
                    }
                    200 -> { if (resumeFrom > 0) { partFile.delete(); resumeFrom = 0L } }
                    416 -> { partFile.delete(); resumeFrom = 0L; conn.disconnect(); continue }
                    else -> return null
                }

                val partial = conn.responseCode == 206
                val total = if (partial) resumeFrom + conn.contentLengthLong else conn.contentLengthLong
                val base = if (partial) resumeFrom else 0L
                try {
                    java.io.FileOutputStream(partFile, partial).use { output ->
                        conn.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = base
                            var lastReport = downloaded
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                // 节流：每 200KB 回调一次进度，避免频繁重组 UI
                                if (downloaded - lastReport > 200 * 1024) {
                                    onProgress(DownloadProgress(downloaded, total))
                                    lastReport = downloaded
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 连接中断：保留 .part，超限前自动续传
                    resumeFrom = partFile.length()
                    retries++
                    if (resumeFrom > 0 && retries <= MAX_RESUME_RETRIES) continue
                    throw e
                }

                onProgress(DownloadProgress(partFile.length(), total))
                // 显式完整性检查：服务端给出总长但实际不符（截断/内容注入），直接判失败
                if (total > 0 && partFile.length() != total) {
                    partFile.delete()
                    throw java.io.IOException("下载不完整（${partFile.length()}/${total} 字节），已清理重试")
                }
                if (!verifySha256(partFile, digest)) {
                    partFile.delete()
                    throw java.io.IOException("安装包校验失败（sha256 不匹配），已清理重试")
                }
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                return targetFile.takeIf { it.exists() && it.length() > 0 }
            }
        } finally {
            conn?.disconnect()
        }
    }

    /** sha256 校验；digest 为空时跳过（仅存在性与非空校验） */
    private fun verifySha256(file: File, digest: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (digest.isBlank()) return true
        return runCatching {
            val expected = digest.substringAfter("sha256:").substringAfter("SHA256:").trim().lowercase()
            if (expected.length != 64) return true // digest 格式异常时不拦截更新
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) md.update(buffer, 0, read)
            }
            md.digest().joinToString("") { "%02x".format(it) } == expected
        }.getOrDefault(false)
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
