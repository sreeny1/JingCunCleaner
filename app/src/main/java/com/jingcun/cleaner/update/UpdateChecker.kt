package com.jingcun.cleaner.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA 更新检查器：基于 GitHub Releases API。
 *
 * 工作流程：
 * 1. 请求 https://api.github.com/repos/{owner}/{repo}/releases/latest
 * 2. 解析 tag_name（如 v2.2）对比当前 versionCode
 * 3. 返回 UpdateInfo（版本号、更新说明、APK 下载地址、文件大小）
 *
 * 参考：
 * - 字节跳动/DCloud update-checker 的版本对比逻辑
 * - koreader/android-update-engine 的 GitHub Release 检查
 */
class UpdateChecker(private val context: Context) {

    data class UpdateInfo(
        val versionName: String,      // 新版本号（如 2.2）
        val versionCode: Long,        // 新版本 versionCode
        val releaseNotes: String,     // 更新说明
        val downloadUrl: String,      // APK 下载地址
        val fileSize: Long,           // APK 文件大小（字节）
        val htmlUrl: String,          // Release 页面地址
    )

    /** GitHub 仓库信息（OTA 检查地址：https://api.github.com/repos/{owner}/{repo}/releases/latest） */
    @Volatile
    var repoOwner: String = "sreeny1"
    @Volatile
    var repoName: String = "JingCunCleaner"

    /** 当前已安装版本 */
    fun currentVersionCode(): Long = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.longVersionCode
    } catch (e: Exception) { 0L }

    fun currentVersionName(): String = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName ?: ""
    } catch (e: Exception) { "" }

    /**
     * 检查 GitHub 最新 Release 是否有新版本。
     * @return UpdateInfo 若有新版本；null 表示已是最新或请求失败
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "JingCunCleaner-OTA")
            }
            conn.connect()
            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parseRelease(body)
        }.getOrNull()
    }

    private fun parseRelease(json: String): UpdateInfo? = runCatching {
        val obj = JSONObject(json)
        // tag_name 格式如 "v2.2" 或 "2.2"
        val tag = obj.optString("tag_name").removePrefix("v").removePrefix("V").trim()
        val newVersionName = tag.ifBlank { obj.optString("name", "未知版本") }
        // 把版本号字符串（如 2.2 / 2.10.1）解析为可比较的长整型
        val newVerNum = versionToLong(newVersionName)
        if (newVerNum <= 0) return null
        // 用 versionName 比对，避免依赖 build.gradle 中单调递增的 versionCode
        val currentVerNum = versionToLong(currentVersionName())
        if (newVerNum <= currentVerNum) return null

        val notes = obj.optString("body", "暂无更新说明").ifBlank { "暂无更新说明" }
        val htmlUrl = obj.optString("html_url", "")
        // 从 assets 中找 .apk 文件
        val assets = obj.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                apkSize = asset.optLong("size", 0L)
                break
            }
        }
        if (apkUrl == null) return null
        UpdateInfo(
            versionName = newVersionName,
            versionCode = newVerNum,
            releaseNotes = notes,
            downloadUrl = apkUrl,
            fileSize = apkSize,
            htmlUrl = htmlUrl,
        )
    }.getOrNull()

    /**
     * 将点分版本号字符串转为可比较的长整型。
     * 例：2.1 → 20001，2.2 → 20002，2.10.1 → 2010001
     * 每段限定 0~9999，最多支持 4 段。
     */
    private fun versionToLong(version: String): Long {
        val parts = version.split(".", "_", "-")
            .mapNotNull { it.toIntOrNull() }
            .take(4)
        if (parts.isEmpty()) return 0L
        var result = 0L
        for (part in parts) {
            result = result * 10000 + part.coerceIn(0, 9999)
        }
        return result
    }
}
