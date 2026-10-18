package com.jingcun.cleaner.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 运行时权限适配器：自动检测设备能力并选择合适的授权路径。
 *
 * 设计目标：单 APK 兼容两类系统
 * - 标准系统（普通手机/平板）：走 MANAGE_EXTERNAL_STORAGE 授权页
 * - 车机多媒体系统（授权页被精简）：自动降级到 READ/WRITE_EXTERNAL_STORAGE 运行时权限
 *
 * 降级检测原理：用 PackageManager.resolveActivity() 检测目标授权 Activity 是否存在。
 * 车机精简了系统设置页时，resolveActivity 返回 null，触发降级路径。
 *
 * 参考：Android 官方文档 Environment.isExternalStorageManager() 判断全盘权限
 */
class PermissionAdapter(private val context: Context) {

    /** 存储权限模式 */
    enum class StorageMode {
        /** 标准：已获 MANAGE_EXTERNAL_STORAGE 全盘访问权限 */
        FULL,
        /** 降级：仅 READ/WRITE_EXTERNAL_STORAGE 运行时权限，可访问公共目录但非 Android/data/ */
        LEGACY,
        /** 未授权 */
        NONE,
    }

    /** 当前存储权限状态 */
    fun getStorageMode(): StorageMode {
        // Android 11+ 检查全盘权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return StorageMode.FULL
        }
        // 降级检查运行时读写权限
        val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        return if (read && write) StorageMode.LEGACY else StorageMode.NONE
    }

    /** 是否支持标准全盘授权页（车机精简后返回 false） */
    fun isFullStorageSettingsAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return intent.resolveActivity(context.packageManager) != null
    }

    /** 创建全盘存储授权 Intent（不可用时返回 null） */
    fun createFullStorageIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!isFullStorageSettingsAvailable()) return null
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 运行时存储权限是否已授予（降级模式用） */
    fun hasLegacyStoragePermission(): Boolean {
        val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /** 需要请求的运行时存储权限数组 */
    fun legacyStoragePermissions(): Array<String> = arrayOf(
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    /** 综合判断：是否可以开始全盘扫描 */
    fun canScanStorage(): Boolean = getStorageMode() != StorageMode.NONE
}
