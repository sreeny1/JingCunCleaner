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
 * v3.1 比亚迪 DiLink 适配（依据社区固件审计证据）：
 * 比亚迪车机的设置页是自定义 VehicleSettings，会为数十个标准 android.settings.* action
 * 注册"诱饵"Activity——startActivity 成功打开但只显示"多媒体系统不支持此操作"，
 * resolveActivity() 对诱饵返回非 null，无法据此判断页面真实存在。
 * 因此授权不再只发一个 Intent，而是提供「授权选项链」：
 * 标准全盘授权页 → 应用详情页（比亚迪固件审计确认 InstalledAppDetailsActivity 可达，
 * 从"权限→文件和媒体"中开启）→ 运行时权限。上一项无效时用户可换下一项。
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

    /** 一个可选的授权入口（授权选项链中的一环） */
    data class GrantOption(
        val id: String,
        val title: String,
        val desc: String,
        val intent: Intent,
    )

    companion object {
        /** 比亚迪/DiLink 设备检测：Build 信息中含 BYD/DiLink 标识 */
        fun isBydDevice(): Boolean {
            val marks = listOf(
                Build.MANUFACTURER, Build.BRAND, Build.PRODUCT,
                Build.DISPLAY, Build.FINGERPRINT, Build.MODEL,
            ).joinToString(" ").lowercase()
            return "byd" in marks || "dilink" in marks
        }

        /** 从 Build 信息解析 DiLink 版本号（如 "5.1"），解析失败返回 null */
        fun diLinkVersion(): String? {
            val source = (Build.DISPLAY + " " + Build.FINGERPRINT)
            val m = Regex("DiLink\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(source)
            return m?.groupValues?.get(1)
        }
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

    /**
     * 全盘存储授权选项链（按优先级排列）。
     * 注意：比亚迪诱饵页会导致 resolveActivity 误判，故仅做基础可用性过滤，
     * 由 UI 在"返回后仍未授权"时引导用户尝试下一项。
     */
    fun grantOptions(): List<GrantOption> {
        val options = mutableListOf<GrantOption>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 1. 标准"所有文件访问权限"授权页（定向到本应用）
            options += GrantOption(
                id = "all_files",
                title = "所有文件访问权限",
                desc = "标准授权页。部分车机会显示「不支持此操作」，此时请尝试下一项",
                intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            // 2. 全部应用的全盘授权列表页（部分 ROM 保留）
            options += GrantOption(
                id = "all_files_list",
                title = "全盘授权列表",
                desc = "在列表中找到「APK归纳」并允许",
                intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            // 3. 应用详情页（比亚迪实测可达；从 权限→文件和媒体 开启）
            options += GrantOption(
                id = "app_details",
                title = "应用详情页",
                desc = "车机推荐。进入后点「权限」→「文件和媒体」→ 允许管理所有文件",
                intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        return options
    }

    /** 首选授权 Intent（grantOptions 第一项；Android 11 以下为 null 走运行时权限） */
    fun createFullStorageIntent(): Intent? = grantOptions().firstOrNull()?.intent

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
