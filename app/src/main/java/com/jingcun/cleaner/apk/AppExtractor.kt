package com.jingcun.cleaner.apk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Environment
import android.os.IBinder
import androidx.core.content.pm.PackageInfoCompat
import com.jingcun.cleaner.shizuku.ExtractService
import com.jingcun.cleaner.shizuku.IExtractService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 应用提取引擎：列出设备所有已安装应用（含系统应用），提取 APK 到本地。
 *
 * 提取权限降级链（逐级尝试）：
 * 1. 直接读取 APK 文件 —— 系统应用（/system /vendor /product 等）全局可读；
 *    部分定制 ROM（常见于车机）对 /data/app 也放行
 * 2. Shizuku（ADB 权限）—— 用户通过无线调试/PC adb 激活 Shizuku 后，
 *    以 shell(uid 2000) 身份复制 APK（GitHub 开源方案 RikkaApps/Shizuku）
 * 3. Root（su）—— 已刷机设备直接以 root 身份复制
 * 4. 全部失败 —— 提示用户暂时无法提取，但应用列表始终可见
 *
 * 提取目标目录：/storage/emulated/0/APK归纳/提取/，命名「应用名+版本号.apk」。
 */
class AppExtractor(private val context: Context) {

    /** 已安装应用信息 */
    data class InstalledApp(
        val appName: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val sourceDir: String,
        val sizeBytes: Long,
        val isSystemApp: Boolean,
        val icon: Drawable?,
        /** 提取后的目标文件名（应用名+版本号.apk） */
        val targetName: String,
    )

    /** 单个提取结果 */
    data class ExtractResult(
        val app: InstalledApp,
        val targetPath: String,
        val success: Boolean,
        /** 成功时为提取方式描述；失败时为失败原因 */
        val message: String,
    )

    companion object {
        const val EXTRACT_DIR_NAME = "提取"
        /** Shizuku 权限请求码 */
        const val SHIZUKU_REQUEST_CODE = 7001

        /** 判断 sourceDir 是否属于系统分区（普通应用可直接读取） */
        private val SYSTEM_PATH_PREFIXES = listOf(
            "/system/", "/vendor/", "/product/", "/odm/", "/oem/", "/apex/", "/system_ext/",
        )

        /** shell 命令单引号转义 */
        private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    /** 提取目录：/storage/emulated/0/APK归纳/提取 */
    val extractDir: File
        get() = File(
            File(Environment.getExternalStorageDirectory(), ApkOrganizer.TARGET_DIR_NAME),
            EXTRACT_DIR_NAME,
        )

    /**
     * 列出所有已安装应用（含系统应用），按 应用名 排序。
     * 仅读取基本信息，不做重 IO；列表始终可见（无论是否有提取权限）。
     */
    fun listInstalledApps(): List<InstalledApp> = runCatching {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val packages: List<PackageInfo> = pm.getInstalledPackages(flags)
        packages.mapNotNull { pi ->
            runCatching {
                val ai: ApplicationInfo = pi.applicationInfo ?: return@mapNotNull null
                val appName = runCatching { ai.loadLabel(pm).toString() }
                    .getOrDefault(pi.packageName ?: "")
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val sourceDir = ai.sourceDir ?: return@mapNotNull null
                val versionName = pi.versionName ?: ""
                val versionCode = PackageInfoCompat.getLongVersionCode(pi)
                val verPart = versionName.ifBlank { "v$versionCode" }
                InstalledApp(
                    appName = appName,
                    packageName = pi.packageName ?: "",
                    versionName = versionName,
                    versionCode = versionCode,
                    sourceDir = sourceDir,
                    sizeBytes = runCatching { File(sourceDir).length() }.getOrDefault(0L),
                    isSystemApp = isSystem,
                    icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
                    targetName = "${sanitizeFileName("$appName$verPart")}.apk",
                )
            }.getOrNull()
        }.sortedBy { it.appName.lowercase() }
    }.getOrDefault(emptyList())

    /** 当前应用是否为自身（提取自己无意义，列表中过滤） */
    fun isSelf(app: InstalledApp): Boolean = app.packageName == context.packageName

    /** 源 APK 是否可被当前进程直接读取（系统应用或宽松 ROM） */
    fun canReadDirectly(sourceDir: String): Boolean = runCatching {
        File(sourceDir).inputStream().use { it.read() >= 0 }
    }.getOrDefault(false)

    /** 提取单个应用 */
    suspend fun extract(app: InstalledApp): ExtractResult = withContext(Dispatchers.IO) {
        val dir = extractDir
        runCatching { dir.mkdirs() }
        val target = uniqueTarget(dir, app.targetName)

        // 1) 直接读取（系统应用 / 宽松 ROM / 已有全盘权限）
        if (isSystemPath(app.sourceDir) || canReadDirectly(app.sourceDir)) {
            val ok = copyDirect(app.sourceDir, target)
            if (ok) return@withContext ExtractResult(app, target.absolutePath, true, "直接提取")
        }

        // 2) Shizuku（ADB 权限，UserService 方式：官方推荐，shell 身份进程内复制）
        if (isShizukuGranted()) {
            val ok = extractViaShizuku(app.sourceDir, target.absolutePath)
            if (ok) return@withContext ExtractResult(app, target.absolutePath, true, "通过 ADB 权限提取")
        }

        // 3) Root
        if (hasRoot()) {
            val cmd = "cat ${shQuote(app.sourceDir)} > ${shQuote(target.absolutePath)}"
            val ok = runRootShell(cmd) && validateTarget(target)
            if (ok) return@withContext ExtractResult(app, target.absolutePath, true, "通过 Root 权限提取")
        }

        // 4) 全部失败：清理半成品并提示
        runCatching { target.delete() }
        ExtractResult(
            app = app,
            targetPath = target.absolutePath,
            success = false,
            message = when {
                !isShizukuAlive() && !hasRoot() ->
                    "无法读取该应用安装包：系统限制访问，且设备未开启 ADB 权限（Shizuku）也无 Root 权限，暂时无法提取"
                !isShizukuAlive() ->
                    "无法读取该应用安装包：Shizuku 未运行且 Root 不可用，暂时无法提取"
                else ->
                    "无法读取该应用安装包：请授予 ADB 权限（Shizuku）或使用 Root 后重试"
            },
        )
    }

    /** 批量提取（返回逐项结果，失败项不中断后续） */
    suspend fun extractAll(
        apps: List<InstalledApp>,
        onProgress: (done: Int, total: Int, app: InstalledApp) -> Unit,
    ): List<ExtractResult> {
        val results = mutableListOf<ExtractResult>()
        apps.forEachIndexed { index, app ->
            results.add(extract(app))
            onProgress(index + 1, apps.size, app)
        }
        return results
    }

    // ---------- Shizuku（ADB 权限，UserService） ----------

    /** Shizuku 用户服务引用（shell/root 身份进程内运行的复制服务） */
    private var shizukuService: IExtractService? = null
    private var connectLatch = CountDownLatch(0)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shizukuService = IExtractService.Stub.asInterface(binder)
            connectLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
        }
    }

    private fun userServiceArgs() = rikka.shizuku.Shizuku.UserServiceArgs(
        ComponentName(context, ExtractService::class.java)
    ).processNameSuffix("extract").version(1)

    /** 确保 Shizuku 用户服务已绑定（异步绑定，最多等待 8 秒） */
    @Synchronized
    private fun ensureShizukuService(): IExtractService? {
        shizukuService?.let { return it }
        if (!isShizukuGranted()) return null
        return runCatching {
            connectLatch = CountDownLatch(1)
            rikka.shizuku.Shizuku.bindUserService(userServiceArgs(), serviceConnection)
            connectLatch.await(8, TimeUnit.SECONDS)
            shizukuService
        }.getOrNull()
    }

    /** 通过 Shizuku 用户服务以 shell 身份复制文件 */
    private fun extractViaShizuku(src: String, dst: String): Boolean {
        val service = ensureShizukuService() ?: return false
        return runCatching {
            service.copyFile(src, dst) && validateTarget(File(dst))
        }.getOrDefault(false)
    }

    /** Shizuku 服务是否在运行（用户已激活或启动了 Shizuku 应用） */
    fun isShizukuAlive(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder()
    }.getOrDefault(false)

    /** Shizuku 是否已授权本应用 */
    fun isShizukuGranted(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 发起 Shizuku 授权请求（弹系统授权框，需 Shizuku 正在运行） */
    fun requestShizukuPermission() {
        runCatching {
            if (rikka.shizuku.Shizuku.pingBinder()) {
                rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        }
    }

    /** 释放 Shizuku 用户服务（ViewModel 销毁时调用） */
    fun release() {
        runCatching {
            rikka.shizuku.Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true)
        }
        shizukuService = null
    }

    // ---------- Root ----------

    /** 设备是否有可用 root（su 命令可执行） */
    fun hasRoot(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        process.outputStream.use { it.write("exit\n".toByteArray()) }
        process.waitFor() == 0
    }.getOrDefault(false)

    /** 通过 root 执行命令 */
    private fun runRootShell(cmd: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        process.outputStream.use { it.write("$cmd\nexit\n".toByteArray()) }
        process.waitFor() == 0
    }.getOrDefault(false)

    // ---------- 工具 ----------

    /** 验证提取产物有效（存在且非空） */
    private fun validateTarget(target: File): Boolean =
        runCatching { target.exists() && target.length() > 0 }.getOrDefault(false)

    /** 系统分区路径判断 */
    private fun isSystemPath(sourceDir: String): Boolean =
        SYSTEM_PATH_PREFIXES.any { sourceDir.startsWith(it) }

    /** 普通流复制 */
    private fun copyDirect(src: String, target: File): Boolean = runCatching {
        File(src).inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, bufferSize = 128 * 1024) }
        }
        true
    }.getOrElse {
        runCatching { target.delete() }
        false
    }

    /** 提取目录下取唯一文件名：重名追加 (n) 序号 */
    private fun uniqueTarget(dir: File, name: String): File {
        val base = name.removeSuffix(".apk")
        var target = File(dir, "$base.apk")
        var n = 1
        while (target.exists()) {
            target = File(dir, "$base($n).apk")
            n++
        }
        return target
    }

    /** 文件名非法字符清理 */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "未命名" }
}
