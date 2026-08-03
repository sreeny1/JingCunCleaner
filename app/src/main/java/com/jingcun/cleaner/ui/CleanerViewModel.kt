package com.jingcun.cleaner.ui

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingcun.cleaner.apk.ApkOrganizer
import com.jingcun.cleaner.apk.AppStorageScanner
import com.jingcun.cleaner.data.AppSettings
import com.jingcun.cleaner.data.ApkOrganizeMode
import com.jingcun.cleaner.data.EmptyFolderScope
import com.jingcun.cleaner.data.ScreenOrientation
import com.jingcun.cleaner.data.SettingsRepository
import com.jingcun.cleaner.scanner.JunkCategory
import com.jingcun.cleaner.scanner.JunkItem
import com.jingcun.cleaner.scanner.ScanEngine
import com.jingcun.cleaner.trash.TrashManager
import java.io.File
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

enum class Phase { IDLE, SCANNING, DONE, CLEANING, CLEANED }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val progress: Float = 0f,
    val currentPath: String = "",
    val results: Map<JunkCategory, List<JunkItem>> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val freedBytes: Long = 0L,
    val trashedCount: Int = 0,
    val deletedCount: Int = 0,
    val totalBytes: Long = 0L,
    val totalItems: Int = 0,
    val cleanedCount: Int = 0,
    val storageTotal: Long = 0L,
    val storageFree: Long = 0L,
    val hasStoragePermission: Boolean = false,
)

class CleanerViewModel(app: Application) : AndroidViewModel(app) {
    private val trashManager = TrashManager(app)
    private val engine = ScanEngine(app, trashManager)
    private val apkOrganizer = ApkOrganizer(app)
    private val appStorageScanner = AppStorageScanner(app)
    private val settingsRepo = SettingsRepository(app)
    private val permissionAdapter = com.jingcun.cleaner.util.PermissionAdapter(app)
    private val updateChecker = UpdateChecker(app)
    private val updateInstaller = UpdateInstaller(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var cleanJob: Job? = null

    private val _trashItems = MutableStateFlow<List<TrashManager.TrashItem>>(emptyList())
    val trashItems: StateFlow<List<TrashManager.TrashItem>> = _trashItems.asStateFlow()

    private val _apkState = MutableStateFlow(ApkUiState())
    val apkState: StateFlow<ApkUiState> = _apkState.asStateFlow()

    init {
        // 全部初始化操作加防御性异常捕获，防止任一步骤失败导致 ViewModel 创建崩溃 → Activity 闪退
        runCatching { refreshStorage() }
        runCatching { updatePermission() }
        // 启动时清理过期回收站
        viewModelScope.launch {
            runCatching {
                val s = settings.value
                trashManager.cleanExpired(s.trashRetentionDays)
            }
        }
        // 启动时自动检查 OTA 更新（静默，有新版本才提示）
        runCatching { checkForUpdate(silent = true) }
    }

    fun updatePermission() {
        // 双通道：标准模式检查 isExternalStorageManager，降级模式检查运行时权限
        _state.update {
            it.copy(hasStoragePermission = permissionAdapter.canScanStorage())
        }
        refreshStorage()
    }

    /** 请求存储权限：自动选择标准/降级路径 */
    fun requestStoragePermission(): android.content.Intent? = permissionAdapter.createFullStorageIntent()

    /** 是否需要运行时弹窗授权（降级模式） */
    fun needsLegacyStoragePermission(): Boolean =
        permissionAdapter.createFullStorageIntent() == null && !permissionAdapter.hasLegacyStoragePermission()

    /** 运行时存储权限数组 */
    fun legacyStoragePermissions(): Array<String> = permissionAdapter.legacyStoragePermissions()

    /** 是否处于降级模式 */
    fun isLegacyStorageMode(): Boolean = permissionAdapter.isLegacyMode()

    private fun refreshStorage() {
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            _state.update { it.copy(storageTotal = stat.totalBytes, storageFree = stat.availableBytes) }
        }
    }

    fun startScan() {
        if (_state.value.phase == Phase.SCANNING || _state.value.phase == Phase.CLEANING) return
        scanJob?.cancel()
        _state.update {
            it.copy(
                phase = Phase.SCANNING, progress = 0f, currentPath = "正在扫描…",
                results = emptyMap(), selected = emptySet(),
                totalBytes = 0L, totalItems = 0,
            )
        }
        scanJob = viewModelScope.launch {
            try {
                val s = settings.value
                val result = engine.scan(s.emptyFolderScope) { p ->
                // 根据阶段文案映射进度区间，阶段内按已扫描项数渐变，避免卡在固定值
                val progress = when {
                    p.currentPath.startsWith("扫描应用缓存") ->
                        0.02f + (p.scanned.toFloat() / 500f).coerceAtMost(0.23f)
                    p.currentPath.startsWith("扫描应用公共缓存") ->
                        0.25f + (p.scanned.toFloat() / 1000f).coerceAtMost(0.25f)
                    p.currentPath.startsWith("扫描公共目录") ->
                        0.50f + (p.scanned.toFloat() / 3000f).coerceAtMost(0.30f)
                    p.currentPath.startsWith("扫描空文件夹") ->
                        0.80f + (p.scanned.toFloat() / 1000f).coerceAtMost(0.18f)
                    p.currentPath == "扫描完成" -> 1.0f
                    else -> 0.5f
                }.coerceIn(0f, 1f)
                _state.update { it.copy(currentPath = p.currentPath, progress = progress) }
            }
            val grouped = result.items.groupBy { it.category }
            _state.update {
                it.copy(
                    phase = Phase.DONE, progress = 1f, currentPath = "扫描完成",
                    results = grouped, totalBytes = result.totalBytes,
                    totalItems = result.items.size,
                    selected = result.items.map { j -> j.path }.toSet(),
                )
            }
            refreshStorage()
            // 扫描后直接清理（若开启）
            if (s.scanThenClean && result.items.isNotEmpty()) {
                clean()
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(phase = Phase.IDLE, currentPath = "扫描失败：${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** 重新扫描（与 startScan 相同，独立方法以便 UI 语义清晰） */
    fun rescan() = startScan()

    fun toggleSelect(path: String) {
        _state.update {
            val sel = if (path in it.selected) it.selected - path else it.selected + path
            it.copy(selected = sel)
        }
    }

    fun setCategorySelected(cat: JunkCategory, selected: Boolean) {
        _state.update { s ->
            val paths = s.results[cat]?.map { it.path } ?: emptyList()
            val sel = if (selected) s.selected + paths else s.selected - paths.toSet()
            s.copy(selected = sel)
        }
    }

    fun selectAll(selected: Boolean) {
        _state.update { s ->
            val sel = if (selected) s.results.values.flatten().map { it.path }.toSet() else emptySet()
            s.copy(selected = sel)
        }
    }

    fun clean() {
        val s = _state.value
        if (s.phase != Phase.DONE) return
        val toClean = s.results.values.flatten().filter { it.path in s.selected }
        if (toClean.isEmpty()) return
        cleanJob?.cancel()
        _state.update {
            it.copy(phase = Phase.CLEANING, progress = 0f, currentPath = "正在清理…", cleanedCount = 0)
        }
        cleanJob = viewModelScope.launch {
            try {
                val recycleEnabled = settings.value.recycleBinEnabled
                val result = engine.clean(toClean, recycleEnabled) { p ->
                    _state.update {
                        it.copy(progress = p.done.toFloat() / p.total.coerceAtLeast(1), cleanedCount = p.done)
                    }
                }
                _state.update {
                    it.copy(
                        phase = Phase.CLEANED,
                        freedBytes = result.freedBytes,
                        trashedCount = result.trashedCount,
                        deletedCount = result.deletedCount,
                        progress = 1f,
                        currentPath = "清理完成",
                    )
                }
                refreshStorage()
                refreshTrash()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(phase = Phase.IDLE, currentPath = "清理失败：${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun reset() {
        // 关键修复：重置时取消正在进行的扫描/清理，否则后台 job 完成会覆盖重置后的状态
        scanJob?.cancel()
        cleanJob?.cancel()
        _state.update {
            UiState(
                storageTotal = it.storageTotal,
                storageFree = it.storageFree,
                hasStoragePermission = it.hasStoragePermission,
            )
        }
    }

    // ---- 设置 ----
    fun setRecycleBinEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setRecycleBinEnabled(v) }
    fun setEmptyFolderScope(v: EmptyFolderScope) = viewModelScope.launch { settingsRepo.setEmptyFolderScope(v) }
    fun setScanThenClean(v: Boolean) = viewModelScope.launch { settingsRepo.setScanThenClean(v) }
    fun setTrashRetentionDays(v: Int) = viewModelScope.launch { settingsRepo.setTrashRetentionDays(v) }
    fun setApkOrganizeMode(v: ApkOrganizeMode) = viewModelScope.launch { settingsRepo.setApkOrganizeMode(v) }
    fun setScreenOrientation(v: ScreenOrientation) = viewModelScope.launch { settingsRepo.setScreenOrientation(v) }
    fun setThemeMode(v: com.jingcun.cleaner.data.ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(v) }

    // ---- 回收站 ----
    fun refreshTrash() = viewModelScope.launch { _trashItems.value = trashManager.list() }

    fun restoreTrashItem(item: TrashManager.TrashItem) = viewModelScope.launch {
        trashManager.restore(item)
        refreshTrash()
        refreshStorage()
    }

    fun restoreAllTrash() = viewModelScope.launch {
        trashManager.restoreAll()
        refreshTrash()
        refreshStorage()
    }

    fun deleteTrashItem(item: TrashManager.TrashItem) = viewModelScope.launch {
        trashManager.deletePermanently(item)
        refreshTrash()
        refreshStorage()
    }

    fun clearTrash() = viewModelScope.launch {
        trashManager.clearAll()
        refreshTrash()
        refreshStorage()
    }

    fun trashTotalSize(): Long = trashManager.totalSize()

    // ---- APK 整理 ----
    data class ApkUiState(
        val phase: ApkPhase = ApkPhase.IDLE,
        val apks: List<ApkOrganizer.ApkInfo> = emptyList(),
        val progress: Float = 0f,
        val currentName: String = "",
        val organizedCount: Int = 0,
        val totalCount: Int = 0,
        val message: String = "",
    )
    enum class ApkPhase { IDLE, SCANNING, READY, ORGANIZING, DONE }

    fun scanApks() {
        if (_apkState.value.phase == ApkPhase.SCANNING || _apkState.value.phase == ApkPhase.ORGANIZING) return
        _apkState.value = ApkUiState(phase = ApkPhase.SCANNING, currentName = "正在扫描 APK…")
        viewModelScope.launch {
            try {
                val list = apkOrganizer.scanAll { p ->
                    _apkState.value = _apkState.value.copy(
                        // total=-1（扫描前不知道总数）时显示已扫描绝对值而非百分比
                        progress = if (p.total > 0) p.scanned.toFloat() / p.total else 0f,
                        currentName = "已扫描 ${p.scanned} 个 · ${p.currentName}",
                    )
                }
                _apkState.value = ApkUiState(phase = ApkPhase.READY, apks = list, totalCount = list.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _apkState.value = ApkUiState(phase = ApkPhase.IDLE, message = "扫描失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun organizeAllApks() {
        val s = _apkState.value
        if (s.phase != ApkPhase.READY || s.apks.isEmpty()) return
        _apkState.value = s.copy(phase = ApkPhase.ORGANIZING, progress = 0f, organizedCount = 0)
        viewModelScope.launch {
            try {
                val mode = settings.value.apkOrganizeMode
                val results = apkOrganizer.organizeAll(s.apks, mode) { done, total ->
                    _apkState.value = _apkState.value.copy(
                        progress = done.toFloat() / total.coerceAtLeast(1),
                        organizedCount = done,
                        totalCount = total,
                    )
                }
                val ok = results.count { it.success }
                _apkState.value = _apkState.value.copy(
                    phase = ApkPhase.DONE,
                    message = "整理完成（${mode.title}）：成功 $ok / 共 ${results.size}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _apkState.value = _apkState.value.copy(
                    phase = ApkPhase.DONE,
                    message = "整理失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    fun resetApk() {
        _apkState.value = ApkUiState()
    }

    // ---- 已安装应用大小排行 ----
    data class AppStorageUiState(
        val phase: AppStoragePhase = AppStoragePhase.IDLE,
        val apps: List<AppStorageScanner.AppStorageInfo> = emptyList(),
        val progress: Float = 0f,
        val currentName: String = "",
        val totalSize: Long = 0L,
        val isLegacy: Boolean = false,
    )
    enum class AppStoragePhase { IDLE, SCANNING, DONE }

    private val _appStorageState = MutableStateFlow(AppStorageUiState())
    val appStorageState: StateFlow<AppStorageUiState> = _appStorageState.asStateFlow()

    /** 应用大小扫描是否需要授权（标准模式才需要，降级模式直接可扫） */
    fun appStorageNeedsAuth(): Boolean = !appStorageScanner.isLegacyMode() &&
        permissionAdapter.createUsageAccessIntent() != null

    /** 创建使用情况访问授权 Intent（不可用时返回 null，UI 据此判断是否降级） */
    fun createUsageAccessIntent(): android.content.Intent? = permissionAdapter.createUsageAccessIntent()

    fun scanInstalledApps() {
        if (_appStorageState.value.phase == AppStoragePhase.SCANNING) return
        _appStorageState.value = AppStorageUiState(
            phase = AppStoragePhase.SCANNING,
            currentName = "正在扫描已安装应用…",
            isLegacy = appStorageScanner.isLegacyMode(),
        )
        viewModelScope.launch {
            try {
                val isLegacy = appStorageScanner.isLegacyMode()
                val list = appStorageScanner.scanAll { p ->
                    _appStorageState.value = _appStorageState.value.copy(
                        progress = if (p.total > 0) p.scanned.toFloat() / p.total else 0f,
                        currentName = p.currentName,
                    )
                }
                _appStorageState.value = AppStorageUiState(
                    phase = AppStoragePhase.DONE,
                    apps = list,
                    totalSize = list.sumOf { it.totalBytes },
                    // 关键修复：继承 SCANNING 阶段的 isLegacy，list 为空时不会误判为 false
                    isLegacy = isLegacy,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _appStorageState.value = AppStorageUiState(
                    phase = AppStoragePhase.IDLE,
                    currentName = "扫描失败：${e.message ?: e.javaClass.simpleName}",
                    isLegacy = appStorageScanner.isLegacyMode(),
                )
            }
        }
    }

    fun resetAppStorage() {
        _appStorageState.value = AppStorageUiState(
            isLegacy = appStorageScanner.isLegacyMode(),
        )
    }

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
    private var downloadedApk: File? = null

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
                // 适配新的 CheckResult 区分"无更新"与"请求失败"
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
                // 用户取消下载，清理半成品文件
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
        // 适配新的 InstallResult：区分"未授权安装未知应用"以引导授权
        when (updateInstaller.install(apk)) {
            UpdateInstaller.InstallResult.Started -> {
                // 调起成功后不重置状态，让用户在系统安装页完成操作；返回 App 时仍能看到提示
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
    private var pendingInstallApk: File? = null

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
                // 下载中取消：停止下载并清理半成品文件
                updateJob?.cancel()
                updateInstaller.cleanDownloaded()
                _updateState.value = current.copy(phase = UpdatePhase.AVAILABLE, dialogDismissed = true)
            }
            UpdatePhase.AVAILABLE -> {
                // 有新版本但暂不更新：标记弹窗已关闭，设置页仍显示下载按钮
                _updateState.value = current.copy(dialogDismissed = true)
            }
            UpdatePhase.DOWNLOADED -> {
                // 已下载但暂不安装：标记弹窗已关闭，设置页仍显示安装按钮
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

/** 计算当前选中项的总大小 */
fun selectedSizeOf(state: UiState): Long =
    state.results.values.flatten()
        .filter { it.path in state.selected }
        .sumOf { it.size }
