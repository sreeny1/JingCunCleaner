package com.jingcun.cleaner.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/** 设备硬件与系统信息（用于关于页） */
object DeviceInfo {

    data class Info(
        val androidVersion: String,
        val sdkLevel: Int,
        val deviceModel: String,
        val manufacturer: String,
        val cpuAbi: String,
        val cpuCores: Int,
        val totalRam: Long,
        val availableRam: Long,
        val storageTotal: Long,
        val storageFree: Long,
        val installedAppCount: Int,
    )

    fun collect(context: Context): Info {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val appCount = runCatching {
            context.packageManager.getInstalledPackages(0).size
        }.getOrDefault(0)

        return Info(
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkLevel = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            cpuAbi = cpuAbi,
            cpuCores = cpuCores,
            totalRam = mi.totalMem,
            availableRam = mi.availMem,
            storageTotal = stat.totalBytes,
            storageFree = stat.availableBytes,
            installedAppCount = appCount,
        )
    }
}
