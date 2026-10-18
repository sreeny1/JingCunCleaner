package com.jingcun.cleaner.apk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import com.jingcun.cleaner.data.ApkOrganizeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * APK 归纳引擎。
 *
 * 扫描主存储与外接存储（U 盘/SD 卡，车机常用）下所有 .apk 文件，
 * 读取每个 APK 的应用显示名 + 版本号，按「应用名+版本号.apk」
 * （如 微信8.12.1.apk）移动/复制到统一目录 /storage/emulated/0/APK归纳/。
 *
 * 关键点（参考 GitHub: pass-with-high-score/universal-installer）：
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
        /** 归纳后显示的文件名（应用名+版本号.apk），供 UI 预览 */
        val targetName: String,
    )

    data class OrganizeResult(
        val apkInfo: ApkInfo,
        val targetPath: String,
        val success: Boolean,
        val reason: String = "",
    )

    data class Progress(val scanned: Int, val total: Int, val currentName: String)

    companion object {
        /** 归纳目标目录名（主存储根目录） */
        const val TARGET_DIR_NAME = "APK归纳"
        /** 支持的安装包扩展名（含分裂包格式） */
        private val APK_EXTS = setOf("apk", "apks", "xapk", "apkm")
        /** 扫描时跳过的系统/私有目录名 */
        private val SKIP_DIRS = setOf("Android", "proc", "sys", "dev", "data", "lost+found")
    }

    /** 归纳目标目录：/storage/emulated/0/APK归纳 */
    private val targetDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), TARGET_DIR_NAME)
    }

    /**
     * 收集所有可扫描的存储卷根目录：内置存储 + U 盘/SD 卡。
     * 通过 getExternalFilesDirs 反推卷根（/storage/XXXX-XXXX 形式），兼容车机 U 盘。
     */
    fun storageVolumes(): List<File> {
        val volumes = LinkedHashSet<File>()
        runCatching {
            volumes.add(Environment.getExternalStorageDirectory())
            // /storage/emulated/0/Android/data/<pkg>/files → 反推各卷根
            context.getExternalFilesDirs(null).forEach { dir ->
                if (dir == null) return@forEach
                val path = dir.absolutePath
                val idx = path.indexOf("/Android/data/")
                if (idx > 0) {
                    val root = File(path.substring(0, idx))
                    if (root.exists() && root != Environment.getExternalStorageDirectory()) {
                        volumes.add(root)
                    }
                }
            }
        }
        return volumes.toList()
    }

    /**
     * 扫描所有存储卷中的 APK 文件（排除归纳目标目录与系统目录）。
     * @param onProgress 扫描进度回调（total=-1 表示总数未知）
     */
    suspend fun scanAll(onProgress: (Progress) -> Unit): List<ApkInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ApkInfo>()
        val visited = HashSet<String>()
        val targetPath = runCatching { targetDir.canonicalPath }.getOrDefault(targetDir.absolutePath)
        var scanned = 0
        for (volume in storageVolumes()) {
            val stack = ArrayDeque<File>()
            stack.addLast(volume)
            while (stack.isNotEmpty()) {
                // 响应协程取消
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                if (!visited.add(canonical)) continue
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (c in children) {
                    if (c.isDirectory) {
                        val path = runCatching { c.canonicalPath }.getOrDefault(c.absolutePath)
                        // 跳过归纳目标目录与系统/私有目录（防重复整理、防误入深层系统路径）
                        if (path == targetPath) continue
                        if (c.name in SKIP_DIRS) continue
                        stack.addLast(c)
                    } else if (c.extension.lowercase(Locale.ROOT) in APK_EXTS) {
                        scanned++
                        onProgress(Progress(scanned, -1, c.name))
                        parseApk(c)?.let { result.add(it) }
                    }
                }
            }
        }
        result
    }

    /** 解析单个 APK 元信息（应用名、版本号、图标备用信息） */
    private fun parseApk(file: File): ApkInfo? = runCatching {
        val pm = context.packageManager
        val pi = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA) ?: return null
        val appInfo = pi.applicationInfo ?: return null
        // 关键：未安装 APK 必须设置 sourceDir/publicSourceDir，否则 loadLabel 返回包名
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        val appName = runCatching { appInfo.loadLabel(pm).toString() }
            .getOrDefault(file.nameWithoutExtension)
        val versionName = pi.versionName ?: ""
        val versionCode = PackageInfoCompat.getLongVersionCode(pi)
        // versionName 为空时回退 versionCode，保证文件名可区分版本
        val verPart = versionName.ifBlank { "v$versionCode" }
        ApkInfo(
            sourceFile = file,
            appName = appName.ifBlank { file.nameWithoutExtension },
            packageName = pi.packageName ?: "",
            versionName = versionName,
            versionCode = versionCode,
            size = runCatching { file.length() }.getOrDefault(0L),
            targetName = "${sanitizeFileName("$appName$verPart")}.apk",
        )
    }.getOrNull()

    /**
     * 归纳一个 APK 到目标目录，命名为「应用名+版本号.apk」。
     *
     * 策略：
     * - 同卷直接 renameTo（秒完成，零拷贝）；跨卷复制后删除
     * - MOVE 删除原文件失败视为整体失败并清理 target，防重复归纳产生副本
     * - 复制失败清理半成品 target，防磁盘泄漏
     */
    suspend fun organize(apk: ApkInfo, mode: ApkOrganizeMode): OrganizeResult = withContext(Dispatchers.IO) {
        runCatching { targetDir.mkdirs() }
        var target = uniqueTarget(apk.targetName)
        // 先尝试同卷 rename（仅 MOVE 模式且源同卷时）
        var movedFast = false
        if (mode == ApkOrganizeMode.MOVE && isSameVolume(apk.sourceFile, target)) {
            movedFast = runCatching {
                apk.sourceFile.renameTo(target) && target.exists() && target.length() == apk.size
            }.getOrDefault(false)
        }
        var ok = movedFast
        if (!ok) {
            ok = runCatching {
                apk.sourceFile.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, bufferSize = 128 * 1024) }
                }
                true
            }.getOrElse {
                // 复制失败清理半成品 target，防磁盘泄漏
                runCatching { target.delete() }
                false
            }
            if (ok && mode == ApkOrganizeMode.MOVE) {
                val deleted = runCatching { apk.sourceFile.delete() }.getOrDefault(false)
                if (!deleted) {
                    // 删除原文件失败：回滚 target，避免重复归纳产生副本
                    runCatching { target.delete() }
                    ok = false
                }
            }
        }
        OrganizeResult(
            apkInfo = apk,
            targetPath = target.absolutePath,
            success = ok,
            reason = when {
                ok && mode == ApkOrganizeMode.MOVE -> "已移动"
                ok -> "已复制"
                else -> "归纳失败（权限不足或磁盘已满）"
            },
        )
    }

    /** 批量归纳 */
    suspend fun organizeAll(
        apks: List<ApkInfo>,
        mode: ApkOrganizeMode,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<OrganizeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OrganizeResult>()
        apks.forEachIndexed { index, apk ->
            results.add(organize(apk, mode))
            onProgress(index + 1, apks.size)
        }
        results
    }

    /** 归纳目标目录路径 */
    fun targetDir(): File = targetDir

    /** 目标目录下取唯一文件名：重名追加 (n) 序号，如 微信8.12.1(1).apk */
    private fun uniqueTarget(name: String): File {
        val base = name.removeSuffix(".apk")
        var target = File(targetDir, "$base.apk")
        var n = 1
        while (target.exists()) {
            target = File(targetDir, "$base($n).apk")
            n++
        }
        return target
    }

    /** 判断两个文件是否位于同一存储卷（同卷可 rename 快速移动） */
    private fun isSameVolume(a: File, b: File): Boolean {
        val volRoots = storageVolumes()
        if (volRoots.isEmpty()) return false
        fun volumeOf(f: File): String? = volRoots.firstOrNull { root ->
            runCatching { f.canonicalPath.startsWith(root.canonicalPath) }.getOrDefault(false)
        }?.canonicalPath
        return volumeOf(a) != null && volumeOf(a) == volumeOf(b)
    }

    /** 文件名非法字符清理 */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "未命名" }
}
