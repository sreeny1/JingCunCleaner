package com.jingcun.cleaner.ui

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingcun.cleaner.apk.AppExtractor
import com.jingcun.cleaner.apk.ApkOrganizer
import com.jingcun.cleaner.data.AppSettings
import com.jingcun.cleaner.data.ApkOrganizeMode
import com.jingcun.cleaner.data.ScreenOrientation
import com.jingcun.cleaner.data.SettingsRepository
import com.jingcun.cleaner.data.ThemeMode
import com.jingcun.cleaner.util.PermissionAdapter
import com.jingcun.cleaner.update.UpdateChecker
import com.jingcun.cleaner.update.UpdateInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ==================== APK 归纳状态 ====================

enum class OrganizePhase { IDLE, SCANNING, READY, ORGANIZING, DONE }

data class OrganizeUiState(
    val phase: OrganizePhase = OrganizePhase.IDLE,
    val apks: List<ApkOrganizer.ApkInfo> = emptyList(),
    val progress: Float = 0f,
    val currentName: String = "",
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val message: String = "",
    val hasStoragePermission: Boolean = false,
)

// ==================== 应用提取状态 ====================

data class ExtractUiState(
    val apps: List<AppExtractor.InstalledApp> = emptyList(),
    val loading: Boolean = false,
    /** 正在提取的应用包名集合 */
    val extracting: Set<String> = emptySet(),
    /** 批量提取进度 */
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
    val batchCurrent: String = "",
    val batchRunning: Boolean = false,
    /** 批量提取完成消息 */
    val batchMessage: String = "",
    /** 提取结果：包名 → (是否成功, 消息) */
    val results: Map<String, Pair<Boolean, String>> = emptyMap(),
)

/** Shizuku（ADB 权限）状态 */
enum class ShizukuStatus { NOT_RUNNING, NEED_PERMISSION, GRANTED }

class ApkViewModel(app: Application) : AndroidViewModel(app) {
    private val apkOrganizer = ApkOrganizer(app)
    private val appExtractor = AppExtractor(app)
    private val settingsRepo = SettingsRepository(app)
    private val permissionAdapter = PermissionAdapter(app)
    private val updateChecker = UpdateChecker(app)
    private val updateInstaller = UpdateInstaller(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings()
    )

    // ---- 存储空间 ----
    private val _storageInfo = MutableStateFlow(StorageInfo())
    val storageInfo: StateFlow<StorageInfo> = _storageInfo.asStateFlow()

    data class StorageInfo(val total: Long = 0L, val free: Long = 0L) {
        val used: Long get() = (total - free).coerceAtLeast(0L)
        val usedFraction: Float get() = if (total > 0) used.toFloat() / total else 0f
    }

    // ---- APK 归纳 ----
    private val _organizeState = MutableStateFlow(OrganizeUiState())
    val organizeState: StateFlow<OrganizeUiState> = _organizeState.asStateFlow()
    private var organizeJob: Job? = null
    /** 进入页面是否自动扫描过（防切页重复扫描） */
    private var autoScanRequested = false

    /** 是否应在进入整理页时自动扫描（有存储权限且本次会话未扫过） */
    fun shouldAutoScan(): Boolean = !autoScanRequested && permissionAdapter.canScanStorage()

    // ---- 应用提取 ----
    private val _extractState = MutableStateFlow(ExtractUiState())
    val extractState: StateFlow<ExtractUiState> = _extractState.asStateFlow()
    private var extractJob: Job? = null

    // ---- Shizuku（ADB 权限）----
    private val _shizukuStatus = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val shizukuStatus: StateFlow<ShizukuStatus> = _shizukuStatus.asStateFlow()

    private val binderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener { refreshShizukuStatus() }
    private val binderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        _shizukuStatus.value = ShizukuStatus.NOT_RUNNING
    }
    private val requestPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizukuStatus() }

    init {
        // 全部初始化操作加防御性异常捕获，防止任一步骤失败导致 ViewModel 创建崩溃
        runCatching { refreshStorage() }
        runCatching { updateStoragePermission() }
        runCatching { refreshShizukuStatus() }
        // Shizuku 服务监听：服务启动/断开/授权结果变化时刷新状态
        runCatching {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            rikka.shizuku.Shizuku.addBinderDeadListener(binderDeadListener)
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(requestPermissionListener)
        }
        // 启动时自动检查 OTA 更新（静默，有新版本才提示）
        runCatching { checkForUpdate(silent = true) }
    }

    override fun onCleared() {
        runCatching {
            rikka.shizuku.Shizuku.removeBinderReceivedListener(binderReceivedListener)
            rikka.shizuku.Shizuku.removeBinderDeadListener(binderDeadListener)
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
        }
        // 释放 Shizuku 用户服务
        runCatching { appExtractor.release() }
        super.onCleared()
    }

    fun refreshShizukuStatus() {
        _shizukuStatus.value = when {
            !appExtractor.isShizukuAlive() -> ShizukuStatus.NOT_RUNNING
            appExtractor.isShizukuGranted() -> ShizukuStatus.GRANTED
            else -> ShizukuStatus.NEED_PERMISSION
        }
    }

    /** 请求 Shizuku 授权（弹系统授权框；设备无 Shizuku 时无操作，UI 提示引导） */
    fun requestShizukuPermission() {
        appExtractor.requestShizukuPermission()
        // 部分设备授权框关闭后 listener 不回调，延迟兜底刷新
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            refreshShizukuStatus()
        }
    }

    private fun refreshStorage() {
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            _storageInfo.value = StorageInfo(stat.totalBytes, stat.availableBytes)
        }
    }

    // ---- 存储权限（归纳功能需要） ----

    fun updateStoragePermission() {
        _organizeState.update { it.copy(hasStoragePermission = permissionAdapter.canScanStorage()) }
        refreshStorage()
    }

    /** 请求存储权限：自动选择标准/降级路径 */
    fun requestStoragePermission(): android.content.Intent? = permissionAdapter.createFullStorageIntent()

    /** 运行时存储权限数组（降级模式，车机无全盘授权页时） */
    fun legacyStoragePermissions(): Array<String> = permissionAdapter.legacyStoragePermissions()

    // ---- APK 归纳 ----

    /** 扫描所有存储卷中的 APK 文件 */
    fun scanApks() {
        val current = _organizeState.value
        if (current.phase == OrganizePhase.SCANNING || current.phase == OrganizePhase.ORGANIZING) return
        autoScanRequested = true
        _organizeState.value = OrganizeUiState(
            phase = OrganizePhase.SCANNING,
            hasStoragePermission = current.hasStoragePermission,
            currentName = "正在扫描 APK…",
        )
        organizeJob?.cancel()
        organizeJob = viewModelScope.launch {
            try {
                val list = apkOrganizer.scanAll { p ->
                    _organizeState.value = _organizeState.value.copy(
                        currentName = "已扫描 ${p.scanned} 个 · ${p.currentName}",
                    )
                }
                _organizeState.value = _organizeState.value.copy(
                    phase = if (list.isEmpty()) OrganizePhase.IDLE else OrganizePhase.READY,
                    apks = list,
                    totalCount = list.size,
                    message = if (list.isEmpty()) "未找到 APK 文件" else "",
                    currentName = "",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _organizeState.value = _organizeState.value.copy(
                    phase = OrganizePhase.IDLE,
                    message = "扫描失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /** 一键归纳：将扫描到的 APK 移动/复制到「APK归纳」目录并重命名 */
    fun organizeAllApks() {
        val s = _organizeState.value
        if (s.phase != OrganizePhase.READY || s.apks.isEmpty()) return
        _organizeState.value = s.copy(phase = OrganizePhase.ORGANIZING, progress = 0f, doneCount = 0)
        organizeJob?.cancel()
        organizeJob = viewModelScope.launch {
            try {
                val mode = settings.value.apkOrganizeMode
                val results = apkOrganizer.organizeAll(s.apks, mode) { done, total ->
                    _organizeState.value = _organizeState.value.copy(
                        progress = done.toFloat() / total.coerceAtLeast(1),
                        doneCount = done,
                        totalCount = total,
                    )
                }
                val ok = results.count { it.success }
                _organizeState.value = _organizeState.value.copy(
                    phase = OrganizePhase.DONE,
                    progress = 1f,
                    successCount = ok,
                    message = "成功 $ok / 共 ${results.size}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _organizeState.value = _organizeState.value.copy(
                    phase = OrganizePhase.DONE,
                    message = "归纳失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /** 取消当前归纳任务（回到空闲） */
    fun resetOrganize() {
        organizeJob?.cancel()
        _organizeState.value = OrganizeUiState(hasStoragePermission = _organizeState.value.hasStoragePermission)
    }

    // ---- 应用提取 ----

    /** 进入提取页是否自动加载过应用列表（防切页重复加载） */
    private var autoLoadAppsRequested = false

    /** 是否应在进入提取页时自动加载已安装应用列表 */
    fun shouldAutoLoadApps(): Boolean = !autoLoadAppsRequested

    /** 加载已安装应用列表（含系统应用） */
    fun loadInstalledApps() {
        if (_extractState.value.loading) return
        autoLoadAppsRequested = true
        _extractState.value = _extractState.value.copy(loading = true)
        viewModelScope.launch {
            val apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                appExtractor.listInstalledApps()
            }
            _extractState.value = _extractState.value.copy(apps = apps, loading = false)
        }
    }

    /** 当前应用是否为自身（提取自己无意义） */
    fun isSelfApp(app: AppExtractor.InstalledApp): Boolean = appExtractor.isSelf(app)

    /** 提取单个应用 */
    fun extractApp(app: AppExtractor.InstalledApp) {
        if (app.packageName in _extractState.value.extracting) return
        _extractState.update { it.copy(extracting = it.extracting + app.packageName) }
        viewModelScope.launch {
            try {
                val result = appExtractor.extract(app)
                _extractState.update {
                    it.copy(
                        extracting = it.extracting - app.packageName,
                        results = it.results + (app.packageName to (result.success to result.message)),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _extractState.update {
                    it.copy(
                        extracting = it.extracting - app.packageName,
                        results = it.results + (app.packageName to (false to "提取失败：${e.message ?: "未知错误"}")),
                    )
                }
            }
        }
    }

    /** 批量提取：默认提取所有用户应用 */
    fun extractAllUserApps() {
        if (_extractState.value.batchRunning) return
        val userApps = _extractState.value.apps.filter { !it.isSystemApp && !appExtractor.isSelf(it) }
        if (userApps.isEmpty()) return
        extractJob?.cancel()
        _extractState.value = _extractState.value.copy(
            batchRunning = true, batchTotal = userApps.size, batchDone = 0, batchCurrent = "",
        )
        extractJob = viewModelScope.launch {
            try {
                val results = appExtractor.extractAll(userApps) { done, total, app ->
                    _extractState.value = _extractState.value.copy(
                        batchDone = done, batchTotal = total, batchCurrent = app.appName,
                    )
                }
                val resultMap = results.associate { r -> r.app.packageName to (r.success to r.message) }
                val ok = results.count { it.success }
                _extractState.update {
                    it.copy(
                        batchRunning = false,
                        results = it.results + resultMap,
                        batchMessage = "批量提取完成：成功 $ok / 共 ${results.size}",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _extractState.update { it.copy(batchRunning = false) }
                throw e
            } catch (e: Throwable) {
                _extractState.update {
                    it.copy(
                        batchRunning = false,
                        batchMessage = "批量提取失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /** 取消批量提取 */
    fun cancelBatchExtract() {
        extractJob?.cancel()
        _extractState.update { it.copy(batchRunning = false) }
    }

    /** 清除某应用的提取结果提示 */
    fun clearExtractResult(packageName: String) {
        _extractState.update { it.copy(results = it.results - packageName) }
    }

    /** 目标目录路径（UI 展示用） */
    fun organizeTargetPath(): String = apkOrganizer.targetDir().absolutePath
    fun extractTargetPath(): String = appExtractor.extractDir.absolutePath

    // ---- 设置 ----
    fun setApkOrganizeMode(v: ApkOrganizeMode) = viewModelScope.launch { settingsRepo.setApkOrganizeMode(v) }
    fun setScreenOrientation(v: ScreenOrientation) = viewModelScope.launch { settingsRepo.setScreenOrientation(v) }
    fun setThemeMode(v: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(v) }

    // ---- OTA 在线更新 ----
    /** OTA 更新状态机：空闲/检查中/有新版本/下载中/已下载/出错 */
    enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, DOWNLOADED, ERROR }

    data class UpdateUiState(
        val phase: UpdatePhase = UpdatePhase.IDLE,
        val info: UpdateChecker.UpdateInfo? = null,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val message: String = "",
        /** 是否来自启动时静默检查（用于决定是否自动弹窗） */
        val silent: Boolean = false,
        /** 是否来自用户手动触发（用于决定是否提示"已是最新版本"） */
        val manual: Boolean = false,
        /** 更新弹窗是否已被用户关闭（关闭后设置页仍可下载，弹窗不再自动弹出） */
        val dialogDismissed: Boolean = false,
    ) {
        val percent: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
    }

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()
    private var updateJob: Job? = null
    private var downloadedApk: java.io.File? = null

    /** 当前版本号（用于设置页展示） */
    fun currentVersionName(): String = updateChecker.currentVersionName()

    /**
     * 检查更新。
     * @param silent true=启动时静默检查（仅当有新版本才提示）；false=用户手动触发（无论结果都给反馈）
     */
    fun checkForUpdate(silent: Boolean = false) {
        if (_updateState.value.phase == UpdatePhase.CHECKING ||
            _updateState.value.phase == UpdatePhase.DOWNLOADING) return
        _updateState.value = UpdateUiState(phase = UpdatePhase.CHECKING, silent = silent, manual = !silent)
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            try {
                val result = updateChecker.checkForUpdate()
                _updateState.value = when (result) {
                    is UpdateChecker.CheckResult.HasUpdate ->
                        UpdateUiState(phase = UpdatePhase.AVAILABLE, info = result.info, manual = !silent)
                    is UpdateChecker.CheckResult.NoUpdate ->
                        UpdateUiState(phase = UpdatePhase.IDLE, manual = !silent,
                            message = if (!silent) "已是最新版本" else "")
                    is UpdateChecker.CheckResult.Failed ->
                        UpdateUiState(phase = UpdatePhase.ERROR, manual = !silent,
                            message = result.reason)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _updateState.value = UpdateUiState(phase = UpdatePhase.ERROR, manual = !silent,
                    message = e.message ?: "检查失败")
            }
        }
    }

    /** 开始下载新版本 APK */
    fun downloadUpdate() {
        val info = _updateState.value.info ?: return
        if (_updateState.value.phase == UpdatePhase.DOWNLOADING) return
        _updateState.value = _updateState.value.copy(
            phase = UpdatePhase.DOWNLOADING,
            downloadedBytes = 0L,
            totalBytes = info.fileSize,
            message = "",
        )
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            try {
                val file = updateInstaller.download(info.downloadUrl) { p ->
                    _updateState.value = _updateState.value.copy(
                        downloadedBytes = p.downloaded, totalBytes = p.total
                    )
                }
                if (file != null) {
                    downloadedApk = file
                    _updateState.value = _updateState.value.copy(phase = UpdatePhase.DOWNLOADED)
                } else {
                    _updateState.value = _updateState.value.copy(
                        phase = UpdatePhase.ERROR, message = "下载失败，请检查网络后重试"
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateInstaller.cleanDownloaded()
                _updateState.value = UpdateUiState()
                throw e
            } catch (e: Throwable) {
                _updateState.value = _updateState.value.copy(
                    phase = UpdatePhase.ERROR, message = "下载失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    /** 引导式安装：调起系统安装器，由用户确认安装 */
    fun installUpdate() {
        val apk = downloadedApk ?: return
        when (updateInstaller.install(apk)) {
            UpdateInstaller.InstallResult.Started -> {
                // 调起成功后不重置状态，让用户在系统安装页完成操作
            }
            UpdateInstaller.InstallResult.NeedInstallPermission -> {
                _updateState.value = _updateState.value.copy(
                    phase = UpdatePhase.ERROR,
                    message = "需授权「安装未知应用」权限，请在系统设置中允许本应用安装 APK"
                )
                pendingInstallApk = apk
            }
            UpdateInstaller.InstallResult.NoInstaller -> {
                _updateState.value = _updateState.value.copy(
                    phase = UpdatePhase.ERROR, message = "无法调起系统安装器"
                )
            }
        }
    }

    /** 待安装的 APK（授权后重试安装用） */
    private var pendingInstallApk: java.io.File? = null

    /** 授权「安装未知应用」后重试安装 */
    fun retryInstallAfterPermission() {
        pendingInstallApk?.let { apk ->
            pendingInstallApk = null
            _updateState.value = _updateState.value.copy(phase = UpdatePhase.DOWNLOADED)
            installUpdate()
        }
    }

    /** 关闭更新弹窗（用户点击"稍后"或"取消"）：保留状态，设置页仍可操作 */
    fun dismissUpdate() {
        val current = _updateState.value
        when (current.phase) {
            UpdatePhase.DOWNLOADING -> {
                updateJob?.cancel()
                updateInstaller.cleanDownloaded()
                _updateState.value = current.copy(phase = UpdatePhase.AVAILABLE, dialogDismissed = true)
            }
            UpdatePhase.AVAILABLE -> {
                _updateState.value = current.copy(dialogDismissed = true)
            }
            UpdatePhase.DOWNLOADED -> {
                _updateState.value = current.copy(dialogDismissed = true)
            }
            else -> {
                _updateState.value = UpdateUiState()
            }
        }
    }

    /** 完全清除更新状态（用户主动重新检查时调用） */
    fun clearUpdateState() {
        updateJob?.cancel()
        updateInstaller.cleanDownloaded()
        downloadedApk = null
        _updateState.value = UpdateUiState()
    }

    /** 重新检查更新（用户在错误状态下点击重试） */
    fun retryCheckUpdate() {
        clearUpdateState()
        checkForUpdate(silent = false)
    }
}
