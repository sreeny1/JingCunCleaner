package com.jingcun.cleaner.apk

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import com.jingcun.cleaner.data.ApkOrganizeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APK 整理引擎。
 *
 * 扫描公共存储下所有 .apk 文件，读取每个 APK 的应用显示名 + 版本号，
 * 复制到 /storage/emulated/0/apk整理/ 目录，命名为「应用名版本号.apk」。
 *
 * 参考：pass-with-high-score/universal-installer 的 ApkMetadataReader ——
 * 解析未安装 APK 必须先设置 applicationInfo.sourceDir / publicSourceDir，
 * 否则 loadLabel 会返回包名而非应用名。
 */
class ApkOrganizer(private val context: Context) {

    data class ApkInfo(
        val sourceFile: File,
        val appName: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val size: Long,
    )

    data class OrganizeResult(
        val apkInfo: ApkInfo,
        val targetPath: String,
        val success: Boolean,
        val reason: String = "",
    )

    data class Progress(val scanned: Int, val total: Int, val currentName: String)

    private val targetDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), "apk整理").apply { if (!exists()) mkdirs() }
    }

    /** 扫描所有 APK 文件（排除目标整理目录，避免重复整理） */
    suspend fun scanAll(onProgress: (Progress) -> Unit): List<ApkInfo> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        val result = mutableListOf<ApkInfo>()
        val stack = ArrayDeque<File>()
        // visited 防 symlink 循环导致栈溢出
        val visited = HashSet<String>()
        stack.addLast(root)
        var scanned = 0
        // 支持的安装包扩展名（含分裂包格式）
        val apkExts = setOf("apk", "apks", "xapk", "apkm")
        while (stack.isNotEmpty()) {
            // 响应协程取消
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visited.add(canonical)) continue
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    // 跳过目标整理目录与 Android 系统目录
                    val name = c.name
                    if (c.absolutePath == targetDir.absolutePath) continue
                    if (name == "Android") continue
                    stack.addLast(c)
                } else if (c.extension.lowercase() in apkExts) {
                    scanned++
                    onProgress(Progress(scanned, -1, c.name))
                    parseApk(c)?.let { result.add(it) }
                }
            }
        }
        result
    }

    /** 解析单个 APK 元信息 */
    private fun parseApk(file: File): ApkInfo? = runCatching {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val pi = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val appInfo = pi.applicationInfo ?: return null
        // 关键：未安装 APK 必须设置 sourceDir，否则 loadLabel 返回包名
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        val appName = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(file.nameWithoutExtension)
        val packageName = pi.packageName ?: ""
        val versionName = pi.versionName ?: ""
        val versionCode = PackageInfoCompat.getLongVersionCode(pi)
        ApkInfo(
            sourceFile = file,
            appName = appName.ifBlank { file.nameWithoutExtension },
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            size = runCatching { file.length() }.getOrDefault(0L),
        )
    }.getOrNull()

    /**
     * 整理一个 APK 到目标目录。
     * @param mode COPY=复制保留原文件；MOVE=移动（复制成功后删除原文件）
     *
     * 关键修复（v2.3）：
     * - 复制失败时清理半成品 target，防磁盘泄漏与下次冲突检测异常
     * - MOVE 模式删除原文件失败时视为整体失败并清理 target，避免重复整理产生副本
     * - 64KB 缓冲区提升大 APK 复制效率
     * - versionName 为空时回退 versionCode，保证文件名可区分版本
     */
    suspend fun organize(apk: ApkInfo, mode: ApkOrganizeMode): OrganizeResult = withContext(Dispatchers.IO) {
        // versionName 为空时回退 versionCode，保证文件名可区分版本
        val verPart = apk.versionName.ifBlank { "v${apk.versionCode}" }
        val safeName = sanitizeFileName("${apk.appName}${verPart}")
        var target = File(targetDir, "$safeName.apk")
        // 同名冲突加序号
        var n = 1
        while (target.exists()) {
            target = File(targetDir, "${safeName}_$n.apk")
            n++
        }
        val ok = runCatching {
            target.parentFile?.mkdirs()
            apk.sourceFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            }
            true
        }.getOrElse {
            // 关键修复：复制失败清理半成品 target，防磁盘泄漏
            runCatching { target.delete() }
            false
        }
        // 移动模式：复制成功后删除原文件
        var moved = false
        if (ok && mode == ApkOrganizeMode.MOVE) {
            moved = runCatching { apk.sourceFile.delete() }.getOrDefault(false)
            // 关键修复：MOVE 删除原文件失败视为整体失败，清理 target 避免重复整理产生副本
            if (!moved) {
                runCatching { target.delete() }
            }
        }
        // COPY 模式 success=ok；MOVE 模式 success=ok&&moved
        val finalSuccess = ok && (mode == ApkOrganizeMode.COPY || moved)
        OrganizeResult(
            apkInfo = apk,
            targetPath = target.absolutePath,
            success = finalSuccess,
            reason = when {
                !ok -> "复制失败"
                mode == ApkOrganizeMode.MOVE && !moved -> "复制成功但删除原文件失败，已回滚"
                else -> if (mode == ApkOrganizeMode.MOVE) "已移动" else "已复制"
            },
        )
    }

    /** 批量整理 */
    suspend fun organizeAll(
        apks: List<ApkInfo>,
        mode: ApkOrganizeMode,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<OrganizeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OrganizeResult>()
        val total = apks.size
        apks.forEachIndexed { index, apk ->
            results.add(organize(apk, mode))
            onProgress(index + 1, total)
        }
        results
    }

    /** 整理目标目录路径 */
    fun targetDir(): File = targetDir

    /** 文件名非法字符清理 */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "未命名" }
    }
}
