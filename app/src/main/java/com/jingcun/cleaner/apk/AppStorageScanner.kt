package com.jingcun.cleaner.apk

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.jingcun.cleaner.util.PermissionAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 已安装应用存储大小扫描器（双通道自适应）。
 *
 * 标准通道：StorageStatsManager.queryStatsForUid() 获取 app/data/cache 完整大小
 * 降级通道：File(ApplicationInfo.sourceDir).length() 仅获取 APK 安装包大小
 *
 * 自动选择逻辑：通过 PermissionAdapter 检测使用情况访问授权页是否存在 + 权限是否已授予。
 * 车机多媒体系统（如比亚迪 DiLink）授权页被精简 → 自动降级到 sourceDir 方案。
 *
 * 参考实现：
 * - Hamza417/Inure (PackageUtils.kt) — queryStatsForUid 基础用法
 * - mozilla-firefox/firefox (StorageStatsMetrics.kt) — dataBytes 包含 cacheBytes 的修正
 * - iyStudy/DeleteApp (MainActivity.kt) — AppOpsManager 权限检测
 */
class AppStorageScanner(private val context: Context) {

    data class AppStorageInfo(
        val packageName: String,
        val appName: String,
        val appBytes: Long,       // APK + 原生库大小
        val dataBytes: Long,      // 纯数据大小（已减去缓存），降级模式下为 0
        val cacheBytes: Long,     // 缓存大小，降级模式下为 0
        val totalBytes: Long,     // 总占用
        val isSystemApp: Boolean,
        val isLegacy: Boolean,    // 是否为降级模式获取的数据
    )

    data class Progress(val scanned: Int, val total: Int, val currentName: String)

    private val permissionAdapter = PermissionAdapter(context)

    /** 是否使用降级模式（无 PACKAGE_USAGE_STATS 权限或授权页不可用） */
    fun isLegacyMode(): Boolean {
        // 授权页不存在（车机精简）→ 必然降级
        if (!permissionAdapter.isUsageAccessSettingsAvailable()) return true
        // 授权页存在但未授权 → 降级
        return !hasUsageStatsPermission()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * 扫描所有已安装应用的存储大小，按总占用降序排列。
     * 自动选择标准/降级通道。
     */
    suspend fun scanAll(onProgress: (Progress) -> Unit): List<AppStorageInfo> = withContext(Dispatchers.IO) {
        val legacy = isLegacyMode()
        val pm = context.packageManager
        val selfPkg = context.packageName
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val total = apps.size
        val result = mutableListOf<AppStorageInfo>()

        if (!legacy) {
            // 标准通道：StorageStatsManager 获取完整大小
            val statsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            apps.forEachIndexed { index, appInfo ->
                val name = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(appInfo.packageName)
                if (index % 5 == 0) onProgress(Progress(index, total, name))

                val info = runCatching {
                    val stats = statsManager.queryStatsForUid(appInfo.storageUuid, appInfo.uid)
                    val appBytes = stats.appBytes
                    val cacheBytes = stats.cacheBytes
                    // dataBytes 包含 cacheBytes，纯数据需相减（参考 Mozilla Firefox 实现）
                    val dataBytes = (stats.dataBytes - cacheBytes).coerceAtLeast(0L)
                    AppStorageInfo(
                        packageName = appInfo.packageName,
                        appName = name,
                        appBytes = appBytes,
                        dataBytes = dataBytes,
                        cacheBytes = cacheBytes,
                        totalBytes = appBytes + dataBytes + cacheBytes,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        isLegacy = false,
                    )
                }.getOrNull()

                if (info != null && info.packageName != selfPkg) result.add(info)
            }
        } else {
            // 降级通道：仅读取 APK 文件大小（无需 PACKAGE_USAGE_STATS）
            apps.forEachIndexed { index, appInfo ->
                val name = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(appInfo.packageName)
                if (index % 5 == 0) onProgress(Progress(index, total, name))

                val info = runCatching {
                    val sourceDir = appInfo.sourceDir
                    val apkSize = File(sourceDir).length()
                    // 尝试累加 split APKs 大小
                    val splitSizes = runCatching {
                        appInfo.splitSourceDirs?.sumOf { File(it).length() } ?: 0L
                    }.getOrDefault(0L)
                    val totalApkSize = apkSize + splitSizes
                    AppStorageInfo(
                        packageName = appInfo.packageName,
                        appName = name,
                        appBytes = totalApkSize,
                        dataBytes = 0L,
                        cacheBytes = 0L,
                        totalBytes = totalApkSize,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        isLegacy = true,
                    )
                }.getOrNull()

                if (info != null && info.packageName != selfPkg) result.add(info)
            }
        }

        onProgress(Progress(total, total, "完成"))
        result.sortedByDescending { it.totalBytes }
    }
}
