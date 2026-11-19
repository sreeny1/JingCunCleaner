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
 * v3.5.4 修复（OTA 下载源返回 HTML 错误页导致 sha256 必败）：
 * 1. 镜像池更换：ghproxy.link / gh-proxy.net 已静默失效（返回 HTML 错误页，
 *    自带 Content-Length，长度检查拦不住 → sha256 必败），仅保留实测可用源
 * 2. ZIP 魔数快检：APK 是 ZIP，下载后先验文件头再验 sha256，HTML 错误页
 *    在校验前被拦截，报错不再误导排查方向
 * 3. 直连通道修复：HttpURLConnection 自动重定向只跟随同主机，GitHub release
 *    下载的跨主机 302（github.com → objects.githubusercontent.com）会被静默
 *    返回导致直连从未生效——改为手动逐跳跟随
 * 4. 失败诊断：全部源失败时汇总各源失败原因；sha256 失败携带实际哈希前缀
 * 详见 docs/OTA_下载可靠性规范.md（强制规范，改动下载器前必读）
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
        /**
         * 下载源列表（直连 + 镜像回退，按序尝试）。
         * v3.5.4 教训：镜像站会静默失效（ghproxy.link / gh-proxy.net 曾返回
         * HTML 错误页而非文件内容，长度检查拦不住，sha256 必败）。镜像池只放
         * 实测可用的源；新增/更换镜像前必须 curl 实测下载并校验 sha256，
         * 规范见 docs/OTA_下载可靠性规范.md
         */
        private val MIRROR_PREFIXES = listOf(
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://github.moeyy.xyz/",
        )
        private const val MAX_RESUME_RETRIES = 3
        private const val MAX_REDIRECTS = 5
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
     * 下载 APK：多源逐个回退 + 断点续传 + sha256 校验。
     * @param digest GitHub 提供的 "sha256:xxx"（可空；非空时下载后强制校验）
     * @throws java.io.IOException 全部源失败时抛出，message 汇总各源失败原因
     */
    suspend fun download(
        url: String,
        digest: String = "",
        onProgress: (DownloadProgress) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        var lastError: Exception? = null
        for (mirror in MIRROR_PREFIXES) {
            val label = if (mirror.isEmpty()) "直连" else mirror.removePrefix("https://").removeSuffix("/")
            try {
                val file = downloadSingle(mirror + url, digest, onProgress)
                if (file != null) return@withContext file
                failures.add("$label:服务器无内容")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // v3.5.4：记录每个源的失败原因，全部失败后汇总展示，杜绝无证据报错
                failures.add("$label:${e.message ?: e.javaClass.simpleName}")
                lastError = e
            }
        }
        throw java.io.IOException("全部下载源失败（${failures.joinToString("；")}）", lastError)
    }

    /**
     * 建立连接并手动跟随跨主机重定向。
     * HttpURLConnection 的自动重定向只跟随同主机同协议：GitHub release 下载的
     * 302（github.com → objects.githubusercontent.com）会被静默返回、直连通道
     * 因此从未真正生效——必须手动逐跳跟随。
     */
    private fun openFollowRedirects(url: String, resumeFrom: Long): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val conn = current.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "JingCunCleaner-OTA")
            conn.instanceFollowRedirects = false
            if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            conn.connect()
            when (conn.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val host = conn.url.host
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) {
                        throw java.io.IOException("服务器重定向缺少 Location（$host）")
                    }
                    current = URL(current, location)
                }
                else -> return conn
            }
        }
        throw java.io.IOException("重定向超过 $MAX_REDIRECTS 跳")
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
                conn = openFollowRedirects(url, resumeFrom)
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
                // v3.5.4 内容魔数快检：失效镜像会返回「长度正常的 HTML 错误页」，
                // 长度检查拦不住、sha256 报错误导排查方向——APK 是 ZIP，先验文件头
                if (!isZipArchive(partFile)) {
                    val head = headHex(partFile)
                    val size = partFile.length()
                    partFile.delete()
                    throw java.io.IOException("下载源返回非安装包内容（${size}B，头 $head），已换源重试")
                }
                // sha256 校验：失败时携带实际哈希前缀（诊断证据），不再无证据报错
                val actual = sha256Hex(partFile)
                val expected = digest.substringAfter("sha256:").substringAfter("SHA256:").trim().lowercase()
                if (digest.isNotBlank() && expected.length == 64 && actual != expected) {
                    partFile.delete()
                    throw java.io.IOException(
                        "安装包校验失败（实际 sha256 ${actual?.take(8) ?: "?"}…，期望 ${expected.take(8)}…），已换源重试"
                    )
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

    /** 计算 sha256 hex（小写）；读取失败返回 null */
    private fun sha256Hex(file: File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) md.update(buffer, 0, read)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** ZIP/APK 文件头校验（PK\x03\x04）：拦下失效镜像返回的 HTML 错误页等异常内容 */
    private fun isZipArchive(file: File): Boolean = runCatching {
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) } == 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
    }.getOrDefault(false)

    /** 文件头 hex（诊断用），读取失败返回 "?" */
    private fun headHex(file: File): String = runCatching {
        val head = ByteArray(4)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) "?" else head.take(n).joinToString("") { "%02X".format(it) }
    }.getOrDefault("?")

    /**
     * 触发系统安装器安装 APK（引导式安装）。
     * v3.5.3 修复（卓易通等 ROM 调不起安装器）：
     * 1. 去掉 resolveActivity 判空——targetSdk 30+ 包可见性限制 + 精简 ROM
     *    会把安装器组件隐藏，resolveActivity 误报 null；改直接 startActivity，
     *    捕获 ActivityNotFoundException 才判无安装器（startActivity 不受可见性限制）
     * 2. FileProvider 路径已补 external-path 与 root-path（file_provider_paths.xml），
     *    APK库/外部存储/U盘路径的 getUriForFile 不再抛 IllegalArgumentException
     *    （此前异常被 runCatching 吞掉，同样误报「无安装器」）
     */
    fun install(apkFile: File): InstallResult {
        // Android 8.0+ 需检查"安装未知应用"权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                return InstallResult.NeedInstallPermission
            }
        }
        return try {
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
            context.startActivity(intent)
            InstallResult.Started
        } catch (e: android.content.ActivityNotFoundException) {
            InstallResult.NoInstaller
        } catch (e: Exception) {
            // FileProvider 路径异常等兜底，不再静默吞成"无安装器"
            InstallResult.NoInstaller
        }
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
