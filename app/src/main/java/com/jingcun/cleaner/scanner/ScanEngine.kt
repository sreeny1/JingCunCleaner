package com.jingcun.cleaner.scanner

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.pm.PackageInfoCompat
import com.jingcun.cleaner.data.EmptyFolderScope
import com.jingcun.cleaner.trash.TrashManager
import java.io.File
import java.util.ArrayDeque

/**
 * 垃圾文件扫描与清理引擎。
 *
 * 范围：外部存储（/storage/emulated/0）可访问区域 + 本应用自身缓存目录。
 * 需要 MANAGE_EXTERNAL_STORAGE 权限以遍历公共目录。
 *
 * 参考设计：d4rken-org/sdmaid-se 的 DataArea 抽象与 Sieve 规则引擎，
 * 此处简化为按分类的明确规则集合，保留「保护名单 + 自底向上空目录判定」核心思路。
 */
class ScanEngine(
    private val context: Context,
    private val trashManager: TrashManager,
) {

    data class Progress(val scanned: Int, val currentPath: String)
    data class Result(val items: List<JunkItem>, val totalBytes: Long)
    data class CleanProgress(val done: Int, val total: Int)
    data class CleanResult(val freedBytes: Long, val trashedCount: Int, val deletedCount: Int)

    private val tempExtensions = setOf("tmp", "temp", "log", "bak", "old")

    private val thumbnailNames = setOf(
        ".thumbnails", ".thumbs", "thumbnails", "thumbs", "albumthumbs", ".thumbdata"
    )

    /** 外部存储根下的标准媒体/系统目录，即使为空也不删除（系统会重建或依赖） */
    private val protectedRootNames = setOf(
        "dcim", "pictures", "music", "movies", "download", "documents",
        "recordings", "alarms", "ringtones", "notifications", "podcasts",
        "audiobooks", "android", "lost.dir", ".androidsecure"
    )

    /** 本应用自身生成的目录，扫描时完全跳过，避免误删回收站和 APK 整理成果 */
    private val appGeneratedDirs = setOf(".jingcuntrash", "apk整理")

    /** 系统残留垃圾文件名（精确匹配，参考 SD Maid SE MacFilesFilter / AnalyticsFilter） */
    private val junkFileNames = setOf(
        ".ds_store", "desktop.ini", "thumbs.db", ".installation", ".imei.txt",
        "bt_activity_pkt.txt.last", ".mmsyscache", ".tlocalcookieid",
        ".wps_preloaded_2.txt", ".oukdtft", "ppy_cross"
    )

    /** 系统残留垃圾文件名前缀（macOS AppleDouble 等，参考 SD Maid SE MacFilesFilter） */
    private val junkFilePrefixes = setOf("._", "sdm_write_test-")

    /** 系统残留垃圾目录名，扫描其内容作为垃圾（参考 SD Maid SE LostDirFilter / MacFilesFilter） */
    private val junkDirNames = setOf(
        "lost.dir", ".trashes", ".trash", ".spotlight", ".spotlight-v100",
        ".fseventsd", ".temporaryitems", ".trash-1000"
    )

    /** 广告 SDK 缓存目录名前缀（参考 SD Maid SE AdvertisementFilter） */
    private val adCachePrefixes = setOf(
        "applifiervideocache", "burstlyvideocache", "unityadsvideocache",
        "applifierimagecache", "burstlyimagecache", "unityadsimagecache",
        "__chartboost", ".chartboost", "adhub", ".mobvista", ".goadsdk",
        ".goproduct", ".mologiq", ".adcenix"
    )

    /** 扫描入口 */
    suspend fun scan(
        emptyFolderScope: EmptyFolderScope = EmptyFolderScope.ROOT_ONLY,
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val items = mutableListOf<JunkItem>()
        var scanned = 0

        // 分 4 阶段递增进度，每阶段完成后推进 25%，避免卡在固定值
        fun report(path: String) {
            scanned++
            if (scanned % 40 == 0) onProgress(Progress(scanned, path))
        }

        // 阶段 1：本应用自身缓存（0~25%）
        onProgress(Progress(0, "扫描应用缓存…"))
        listOfNotNull(context.cacheDir, context.externalCacheDir, context.codeCacheDir).forEach { dir ->
            collectFiles(dir, JunkCategory.APP_CACHE, items) { report(it) }
        }

        val pm = context.packageManager
        val installed = getInstalledPackages(pm)
        val roots = getExternalRoots()

        // 阶段 2：各应用公共缓存目录（25~50%）
        onProgress(Progress(scanned, "扫描应用公共缓存…"))
        for (root in roots) {
            scanAppCaches(File(root, "Android/data"), items) { report(it) }
            scanAppCaches(File(root, "Android/media"), items) { report(it) }
        }

        // 阶段 3：公共区域（50~75%）
        onProgress(Progress(scanned, "扫描公共目录…"))
        for (root in roots) {
            walkPublic(root, installed, pm, items) { report(it) }
        }

        // 阶段 4：空文件夹（75~100%）
        onProgress(Progress(scanned, "扫描空文件夹…"))
        for (root in roots) {
            scanEmptyFolders(root, emptyFolderScope, items) { report(it) }
        }

        onProgress(Progress(scanned, "扫描完成"))
        Result(items, items.sumOf { it.size })
    }

    /** 清理选中的项目：回收站开启则移入回收站，否则直接删除。返回释放字节数与统计 */
    suspend fun clean(
        items: List<JunkItem>,
        recycleBinEnabled: Boolean,
        onProgress: (CleanProgress) -> Unit,
    ): CleanResult = withContext(Dispatchers.IO) {
        var freed = 0L
        var trashed = 0
        var deleted = 0
        val total = items.size
        var done = 0
        // 先处理文件，再按深度倒序处理目录（先删子目录再删父目录）
        val files = items.filter { !it.isDirectory }
        val dirs = items.filter { it.isDirectory }.sortedByDescending { it.path.length }
        for (item in files) {
            val f = File(item.path)
            val size = if (f.isFile) runCatching { f.length() }.getOrDefault(0L) else 0L
            val ok = if (recycleBinEnabled) {
                trashManager.moveToTrash(item.path) != null
            } else {
                runCatching { f.delete() }.getOrDefault(false)
            }
            if (ok) {
                freed += size
                if (recycleBinEnabled) trashed++ else deleted++
            }
            done++
            // 每项都回调进度，避免末尾批次卡在高百分比
            onProgress(CleanProgress(done, total))
        }
        for (item in dirs) {
            val f = File(item.path)
            val ok = if (recycleBinEnabled) {
                trashManager.moveToTrash(item.path) != null
            } else {
                deleteRecursivelySafe(f)
            }
            if (ok) {
                if (recycleBinEnabled) trashed++ else deleted++
            }
            done++
            onProgress(CleanProgress(done, total))
        }
        onProgress(CleanProgress(total, total))
        CleanResult(freed, trashed, deleted)
    }

    // ------------------------------------------------------------------

    private fun collectFiles(root: File, cat: JunkCategory, out: MutableList<JunkItem>, onFile: (String) -> Unit) {
        // visited 防 symlink 循环导致栈溢出
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            val dir = stack.pop()
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visited.add(canonical)) continue
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    stack.push(c)
                } else {
                    val size = runCatching { c.length() }.getOrDefault(0L)
                    out.add(JunkItem(c.absolutePath, size, cat, false))
                    onFile(c.absolutePath)
                }
            }
        }
    }

    private fun scanAppCaches(areaRoot: File, out: MutableList<JunkItem>, onFile: (String) -> Unit) {
        val pkgs = runCatching { areaRoot.listFiles() }.getOrNull() ?: return
        for (pkgDir in pkgs) {
            if (!pkgDir.isDirectory) continue
            for (cacheName in listOf("cache", "code_cache")) {
                val cacheDir = File(pkgDir, cacheName)
                if (cacheDir.isDirectory) collectFiles(cacheDir, JunkCategory.APP_CACHE, out, onFile)
            }
        }
    }

    private fun walkPublic(
        root: File,
        installed: Map<String, PackageInfo>,
        pm: PackageManager,
        out: MutableList<JunkItem>,
        onFile: (String) -> Unit,
    ) {
        // visited 防 symlink 循环导致栈溢出
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            val dir = stack.pop()
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visited.add(canonical)) continue
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    val name = c.name.lowercase()
                    // 跳过本应用自身生成的目录（回收站 + APK 整理），避免误删
                    if (appGeneratedDirs.contains(name)) continue
                    if (name == "android") continue // Android 文件夹单独处理缓存
                    // 跳过版本控制目录，避免破坏工程文件
                    if (name == ".git" || name == ".svn" || name == ".idea") continue
                    if (thumbnailNames.contains(name)) {
                        collectFiles(c, JunkCategory.THUMBNAIL, out, onFile)
                    } else if (junkDirNames.contains(name)) {
                        // 系统残留垃圾目录（LOST.DIR、.Trashes 等），内容标记为系统垃圾
                        collectFiles(c, JunkCategory.SYSTEM_JUNK, out, onFile)
                    } else if (adCachePrefixes.any { name.startsWith(it) }) {
                        // 广告 SDK 缓存目录
                        collectFiles(c, JunkCategory.SYSTEM_JUNK, out, onFile)
                    } else {
                        stack.push(c)
                    }
                } else {
                    onFile(c.absolutePath)
                    val lowerName = c.name.lowercase()
                    val ext = c.extension.lowercase()
                    when {
                        ext == "apk" || ext == "apks" -> {
                            if (isApkResidual(c, installed, pm)) {
                                val size = runCatching { c.length() }.getOrDefault(0L)
                                out.add(JunkItem(c.absolutePath, size, JunkCategory.APK_RESIDUAL, false))
                            }
                        }
                        tempExtensions.contains(ext) -> {
                            val size = runCatching { c.length() }.getOrDefault(0L)
                            out.add(JunkItem(c.absolutePath, size, JunkCategory.TEMP_LOG, false))
                        }
                        // 系统残留文件（.DS_Store、desktop.ini、thumbs.db 等）
                        junkFileNames.contains(lowerName) -> {
                            val size = runCatching { c.length() }.getOrDefault(0L)
                            out.add(JunkItem(c.absolutePath, size, JunkCategory.SYSTEM_JUNK, false))
                        }
                        // macOS AppleDouble 文件（._ 前缀）等
                        junkFilePrefixes.any { lowerName.startsWith(it) } -> {
                            val size = runCatching { c.length() }.getOrDefault(0L)
                            out.add(JunkItem(c.absolutePath, size, JunkCategory.SYSTEM_JUNK, false))
                        }
                    }
                }
            }
        }
    }

    private fun isApkResidual(
        apk: File,
        installed: Map<String, PackageInfo>,
        pm: PackageManager,
    ): Boolean {
        val info = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull() ?: return false
        // 关键修复：畸形 APK 的 applicationInfo 可能为 null，PackageInfoCompat.getLongVersionCode 内部读
        // applicationInfo.longVersionCode 会 NPE，导致整个扫描崩溃。必须判空。
        val ai = info.applicationInfo ?: return false
        val pkgName = info.packageName ?: return false
        // 未安装的安装包不算残留，保留
        val installedInfo = installed[pkgName] ?: return false
        val installedVc = PackageInfoCompat.getLongVersionCode(installedInfo)
        val apkVc = PackageInfoCompat.getLongVersionCode(info)
        // 已安装版本 >= 安装包版本 → 视为冗余残留
        return installedVc >= apkVc
    }

    /** 自底向上判定空文件夹：仅标记「空子树的顶端」目录。scope 控制仅根目录一层或全部递归 */
    private fun scanEmptyFolders(
        root: File,
        scope: EmptyFolderScope,
        out: MutableList<JunkItem>,
        onFile: (String) -> Unit,
    ) {
        if (scope == EmptyFolderScope.ROOT_ONLY) {
            // 仅扫描根目录下一层的空文件夹
            val children = runCatching { root.listFiles() }.getOrNull() ?: return
            for (c in children) {
                if (!c.isDirectory) continue
                // 跳过本应用自身生成的目录
                if (appGeneratedDirs.contains(c.name.lowercase())) continue
                onFile(c.absolutePath)
                if (isProtectedRoot(c, root)) continue
                // 关键修复：listFiles 返回 null 时不视为空目录（无法访问 ≠ 空），跳过避免误删
                val sub = runCatching { c.listFiles() }.getOrNull() ?: continue
                if (sub.isEmpty()) out.add(JunkItem(c.absolutePath, 0L, JunkCategory.EMPTY_FOLDER, true))
            }
            return
        }
        // 全部：递归扫描
        val allDirs = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            val dir = stack.pop()
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visited.add(canonical)) continue
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    // 跳过本应用自身生成的目录，不递归进入
                    if (appGeneratedDirs.contains(c.name.lowercase())) continue
                    allDirs.add(c)
                    stack.push(c)
                    onFile(c.absolutePath)
                }
            }
        }

        // hasFile[dir] = 该目录（含子树）是否包含文件
        val hasFile = HashMap<String, Boolean>()
        // 按路径长度倒序 → 叶子目录先处理
        allDirs.sortByDescending { it.absolutePath.length }
        for (dir in allDirs) {
            // 关键修复：listFiles 返回 null 时视为含文件（保守，避免误删无法访问的目录）
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            var containsFile = false
            for (c in children) {
                if (c.isDirectory) {
                    val childHas = hasFile[c.absolutePath] ?: true // 未知则保守视为含文件
                    if (childHas) { containsFile = true; break }
                } else {
                    containsFile = true; break
                }
            }
            hasFile[dir.absolutePath] = containsFile
        }

        // 标记空子树顶端
        for (dir in allDirs) {
            if (hasFile[dir.absolutePath] != false) continue
            val parent = dir.parentFile
            val parentAlsoEmpty = parent != null && hasFile[parent.absolutePath] == false
            if (parentAlsoEmpty) continue // 父目录才是顶端
            if (isProtectedRoot(dir, root)) continue
            out.add(JunkItem(dir.absolutePath, 0L, JunkCategory.EMPTY_FOLDER, true))
        }
    }

    private fun isProtectedRoot(dir: File, root: File): Boolean {
        val parent = dir.parentFile ?: return false
        if (parent.absolutePath == root.absolutePath) {
            if (dir.name.lowercase() in protectedRootNames) return true
        }
        return false
    }

    private fun getInstalledPackages(pm: PackageManager): Map<String, PackageInfo> {
        return runCatching { pm.getInstalledPackages(0).associateBy { it.packageName } }.getOrDefault(emptyMap())
    }

    private fun getExternalRoots(): List<File> {
        val roots = mutableListOf<File>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.isDirectory) roots.add(primary)
        return roots
    }

    private fun deleteRecursivelySafe(f: File): Boolean {
        if (!f.exists()) return false
        if (f.isDirectory) {
            val children = runCatching { f.listFiles() }.getOrNull() ?: emptyArray()
            for (c in children) deleteRecursivelySafe(c)
        }
        return runCatching { f.delete() }.getOrDefault(false)
    }
}
