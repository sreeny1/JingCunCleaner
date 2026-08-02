package com.jingcun.cleaner.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA 更新检查器：基于 GitHub Releases API。
 *
 * 关键修复（v2.3）：
 * - try/finally 确保 conn.disconnect()，防连接泄漏
 * - versionToLong 补齐段数到 4 段，修复 2.2 vs 2.2.0 误判
 * - apkUrl 用 isNullOrEmpty() 判空（optString 缺失返回 ""）
 * - 区分 403 限速/404 仓库不存在，UI 给准确提示
 * - 网络预检查，无网络立即返回不等待 10s 超时
 * - 限制响应体 1MB，防恶意超大 JSON 导致 OOM
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

    /** 检查结果封装：区分"无新版本"和"请求失败" */
    sealed class CheckResult {
        data object NoUpdate : CheckResult()                    // 已是最新
        data class HasUpdate(val info: UpdateInfo) : CheckResult() // 有新版本
        data class Failed(val reason: String) : CheckResult()   // 请求失败（网络/限速/仓库不存在）
    }

    /** GitHub 仓库信息 */
    @Volatile
    var repoOwner: String = "sreeny1"
    @Volatile
    var repoName: String = "JingCunCleaner"

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
     * 返回 CheckResult 区分"无更新"与"请求失败"，UI 据此给准确提示。
     */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        // 网络预检查：无网络立即返回，避免用户等待 10s 超时
        if (!isNetworkAvailable()) {
            return@withContext CheckResult.Failed("无网络连接")
        }

        var conn: HttpURLConnection? = null
        try {
            val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "JingCunCleaner-OTA")
            }
            conn.connect()
            val code = conn.responseCode
            when {
                code == 200 -> {
                    // 限制读取 1MB，防恶意超大响应导致 OOM
                    val body = conn.inputStream.bufferedReader().use { reader ->
                        val sb = StringBuilder()
                        val buffer = CharArray(8192)
                        var read: Int
                        var total = 0
                        while (reader.read(buffer).also { read = it } != -1) {
                            sb.append(buffer, 0, read)
                            total += read
                            if (total > 1 * 1024 * 1024) break
                        }
                        sb.toString()
                    }
                    parseRelease(body) ?: CheckResult.NoUpdate
                }
                code == 403 -> CheckResult.Failed("GitHub API 限速，请稍后重试")
                code == 404 -> CheckResult.Failed("仓库或 Release 不存在")
                else -> CheckResult.Failed("服务器返回 $code")
            }
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "网络请求异常")
        } finally {
            // 关键修复：finally 确保 disconnect，防连接泄漏
            conn?.disconnect()
        }
    }

    private fun parseRelease(json: String): CheckResult? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return CheckResult.Failed("响应解析失败")
        val tag = obj.optString("tag_name").removePrefix("v").removePrefix("V").trim()
        val newVersionName = tag.ifBlank { obj.optString("name", "未知版本") }
        val newVerNum = versionToLong(newVersionName)
        if (newVerNum <= 0) return CheckResult.Failed("版本号格式异常: $newVersionName")
        val currentVerNum = versionToLong(currentVersionName())
        if (newVerNum <= currentVerNum) return CheckResult.NoUpdate

        val notes = obj.optString("body", "暂无更新说明").ifBlank { "暂无更新说明" }
        val htmlUrl = obj.optString("html_url", "")
        val assets = obj.optJSONArray("assets") ?: return CheckResult.Failed("Release 无 APK 资产")
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url", "")
                apkSize = asset.optLong("size", 0L)
                break
            }
        }
        // 关键修复：optString 缺失返回 ""，用 isNullOrEmpty 判空
        if (apkUrl.isEmpty()) return CheckResult.Failed("Release 未找到 APK 文件")
        return CheckResult.HasUpdate(
            UpdateInfo(
                versionName = newVersionName,
                versionCode = newVerNum,
                releaseNotes = notes,
                downloadUrl = apkUrl,
                fileSize = apkSize,
                htmlUrl = htmlUrl,
            )
        )
    }

    private fun isNetworkAvailable(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * 将点分版本号字符串转为可比较的长整型。
     * 关键修复：补齐到固定 4 段，否则 2.2(20002) 与 2.2.0(200020000) 比较错误，导致重复提示更新。
     * 例：2.2 → 20002000000000000，2.2.0 → 20002000000000000（相等，正确）
     *     2.10.1 → 20100010000000000（大于 2.2，正确）
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
        // 补齐到 4 段，使 2.2 与 2.2.0 相等
        repeat(4 - parts.size) { result *= 10000 }
        return result
    }
}
