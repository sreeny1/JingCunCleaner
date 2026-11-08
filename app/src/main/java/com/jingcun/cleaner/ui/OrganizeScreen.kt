package com.jingcun.cleaner.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingcun.cleaner.apk.ApkOrganizer
import com.jingcun.cleaner.util.formatBytes

/**
 * APK 归纳页（主页）。
 *
 * 扫描内置存储 + U 盘/SD 卡中所有 APK → 按应用名+版本号展示 →
 * 一键归纳到 /storage/emulated/0/APK归纳/ 目录。
 * 进入页面且已有存储权限时自动扫描一次。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrganizeScreen() {
    val vm: ApkViewModel = viewModel()
    val state by vm.organizeState.collectAsState()
    val context = LocalContext.current

    var showGrantOptions by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.updateStoragePermission()
        // 返回后仍未授权：可能是车机诱饵页（比亚迪等），引导用户换一种授权入口
        if (!vm.organizeState.value.hasStoragePermission) showGrantOptions = true
    }

    // 运行时权限弹窗（降级模式用，车机无全盘授权页时走这里）
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.updateStoragePermission() }

    val requestPermission: () -> Unit = {
        val fullIntent = vm.requestStoragePermission()
        if (fullIntent != null) {
            runCatching { permissionLauncher.launch(fullIntent) }
                .onFailure { runtimePermissionLauncher.launch(vm.legacyStoragePermissions()) }
        } else {
            runtimePermissionLauncher.launch(vm.legacyStoragePermissions())
        }
    }

    // 「查看归纳目录」：检测文件管理器是否可处理目录查看，无则隐藏入口（比亚迪等无文件管理器的车机）
    val canOpenFolder = remember(vm.organizeTargetPath()) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(vm.organizeTargetPath()), "resource/folder")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
    }

    val openFolder: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(vm.organizeTargetPath()), "resource/folder")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    // 已有存储权限时，进入页面自动扫描一次（优先用扫描缓存秒出列表）
    LaunchedEffect(Unit) {
        if (vm.shouldAutoScan()) vm.scanApks()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("APK 归纳", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.phase) {
                OrganizePhase.IDLE -> {
                    if (!state.hasStoragePermission) {
                        item { PermissionCard(onGrant = requestPermission) }
                    }
                    item {
                        HeroCard(
                            title = if (state.message.startsWith("扫描失败") || state.message == "未找到 APK 文件")
                                state.message else "归纳设备内的所有 APK",
                            desc = "自动扫描内置存储与 U 盘/SD 卡中的 APK 文件，" +
                                "统一移动到「${ApkOrganizer.TARGET_DIR_NAME}」目录，" +
                                "并按「应用名+版本号」重命名（如 微信8.12.1.apk）",
                            buttonText = "扫描 APK 文件",
                            icon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp)) },
                            enabled = state.hasStoragePermission,
                            onClick = vm::scanApks,
                        )
                    }
                }
                OrganizePhase.SCANNING -> item { ScanningCard(state) }
                OrganizePhase.READY -> {
                    // 存储卷筛选：全部 / 本机存储 / USB 存储设备 / SD 卡（车机多磁盘场景）
                    if (state.apks.size > 1) {
                        item(key = "volumes") { VolumeFilterRow(state, onSelect = vm::setVolumeFilter) }
                    }
                    item { SummaryCard(state, onSelectAll = { vm.selectAll(it) }) }
                    item {
                        BigButton(
                            text = "一键归纳（已选 ${selectedCountInFilter(state)}/${filteredApks(state).size}）",
                            icon = Icons.Filled.PlayArrow,
                            enabled = selectedCountInFilter(state) > 0,
                            onClick = vm::organizeAllApks,
                        )
                    }
                    items(
                        filteredApks(state),
                        key = { it.sourceFile.absolutePath },
                        contentType = { "apk" },
                    ) { apk ->
                        ApkItemCard(
                            apk = apk,
                            checked = apk.sourceFile.absolutePath in state.selectedPaths,
                            onToggle = { vm.toggleSelect(apk.sourceFile.absolutePath) },
                        )
                    }
                }
                OrganizePhase.ORGANIZING -> item { OrganizingCard(state) }
                OrganizePhase.DONE -> {
                    item {
                        DoneCard(
                            message = state.message,
                            successCount = state.successCount,
                            total = state.totalCount,
                            targetPath = vm.organizeTargetPath(),
                            canOpenFolder = canOpenFolder,
                            onOpenFolder = openFolder,
                            onAgain = { vm.resetOrganize(); vm.scanApks(force = true) },
                        )
                    }
                }
            }
        }
    }

    // 授权选项链对话框：标准授权页无效（如比亚迪诱饵页）时，提供其他入口
    if (showGrantOptions) {
        val options = vm.storageGrantOptions()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGrantOptions = false },
            title = { Text("换一种方式授权", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "刚才的授权页没有生效（部分车机会拦截系统设置页）。试试下面其他方式：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    options.forEach { option ->
                        TextButton(
                            onClick = {
                                showGrantOptions = false
                                runCatching { permissionLauncher.launch(option.intent) }
                                    .onFailure { runtimePermissionLauncher.launch(vm.legacyStoragePermissions()) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Text(option.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    option.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGrantOptions = false }) { Text("关闭") }
            },
        )
    }
}

/** 当前卷筛选下的 APK 列表（selectedVolume=null 返回全部） */
private fun filteredApks(state: OrganizeUiState): List<ApkOrganizer.ApkInfo> =
    if (state.selectedVolume == null) state.apks
    else state.apks.filter { it.volumeName == state.selectedVolume }

/** 当前卷筛选下已勾选的文件数 */
private fun selectedCountInFilter(state: OrganizeUiState): Int =
    filteredApks(state).count { it.sourceFile.absolutePath in state.selectedPaths }

/** 顶部主操作卡 */
@Composable
private fun HeroCard(
    title: String,
    desc: String,
    buttonText: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, modifier = Modifier.size(88.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                        )
                    ),
                ) { icon() }
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            BigButton(text = buttonText, icon = Icons.Filled.Search, enabled = enabled, onClick = onClick)
        }
    }
}

/** 全宽主按钮（车机触控友好：52dp 高） */
@Composable
private fun BigButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

/** 存储卷筛选行：全部 / 本机存储 / USB 存储设备 / SD 卡（含各自文件数） */
@Composable
private fun VolumeFilterRow(state: OrganizeUiState, onSelect: (String?) -> Unit) {
    // 以原始 volumeName 为键（空串也可能出现），显示名仅在 label 兜底「未知位置」
    val volumes = state.apks
        .groupingBy { it.volumeName }
        .eachCount()
        .entries
        .toList()
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            FilterChip(
                selected = state.selectedVolume == null,
                onClick = { onSelect(null) },
                label = { Text("全部 · ${state.apks.size}") },
            )
        }
        items(volumes) { entry ->
            FilterChip(
                selected = state.selectedVolume == entry.key,
                onClick = { onSelect(entry.key) },
                label = { Text("${entry.key.ifEmpty { "未知位置" }} · ${entry.value}") },
                leadingIcon = {
                    Icon(
                        when (entry.key) {
                            "本机存储" -> Icons.Filled.Inventory2
                            else -> Icons.Filled.Usb
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

/** 存储权限提示卡 */
@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    "需要存储访问权限",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "扫描与归纳 APK 需要存储访问权限。所有操作均在本地完成，数据不会被上传。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("授予权限") }
        }
    }
}

/** 扫描中进度卡（M3 Expressive 加载动画） */
@Composable
private fun ScanningCard(state: OrganizeUiState) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("正在扫描 APK…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            AnimatedContent(
                targetState = state.currentName.ifEmpty { "准备中…" },
                label = "scanName",
            ) { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 扫描结果摘要卡（计数随卷筛选联动，数字变化带动画） */
@Composable
private fun SummaryCard(state: OrganizeUiState, onSelectAll: (Boolean) -> Unit) {
    val filtered = filteredApks(state)
    val selectedCount = selectedCountInFilter(state)
    val selectedSize = filtered
        .filter { it.sourceFile.absolutePath in state.selectedPaths }
        .sumOf { it.size }
    val allSelected = filtered.isNotEmpty() && selectedCount == filtered.size
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = selectedCount to filtered.size,
                    label = "summaryCount",
                ) { (sel, total) ->
                    Text(
                        "共 $total 个 APK · 已选 $sel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    "已选共 ${formatBytes(selectedSize)} / 全部 ${formatBytes(filtered.sumOf { it.size })}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            TextButton(onClick = { onSelectAll(!allSelected) }) {
                Text(if (allSelected) "全不选" else "全选", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 单个 APK 条目：勾选预览 + 归纳后目标文件名 */
@Composable
private fun ApkItemCard(
    apk: ApkOrganizer.ApkInfo,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    apk.targetName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!apk.recognized) {
                        // 完整性徽标：红色=疑似损坏（下载中断），黄色=非标准包/元信息异常
                        val isError = apk.integrity == ApkOrganizer.ApkIntegrity.INCOMPLETE
                        val label = when (apk.integrity) {
                            ApkOrganizer.ApkIntegrity.INCOMPLETE -> "不完整·可能无法安装"
                            ApkOrganizer.ApkIntegrity.NON_APK -> "非标准安装包"
                            else -> "元信息无法识别"
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isError) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        buildString {
                            if (apk.volumeName.isNotBlank()) append("${apk.volumeName} · ")
                            append(
                                apk.sourceFile.absolutePath
                                    .substringAfter("/storage/emulated/0/")
                                    .ifEmpty { apk.sourceFile.absolutePath },
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                formatBytes(apk.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 归纳中进度卡（弹性动画跟随进度） */
@Composable
private fun OrganizingCard(state: OrganizeUiState) {
    val animProgress by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "org",
    )
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("正在归纳…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { animProgress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = state.doneCount to state.totalCount,
                label = "orgCount",
            ) { (done, total) ->
                Text(
                    "$done / $total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 归纳完成卡 */
@Composable
private fun DoneCard(
    message: String,
    successCount: Int,
    total: Int,
    targetPath: String,
    canOpenFolder: Boolean,
    onOpenFolder: () -> Unit,
    onAgain: () -> Unit,
) {
    val allSuccess = successCount == total
    // 完成卡弹入动画（弹性缩放 + 淡入）
    val shown = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown.value = true }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (allSuccess) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = shown.value,
            enter = scaleIn(
                initialScale = 0.9f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            ) + fadeIn(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Icon(
                if (allSuccess) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (allSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (allSuccess) "归纳完成" else "归纳完成（部分失败）",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "成功 $successCount / 共 $total",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "文件已保存到 $targetPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            if (canOpenFolder) {
                OutlinedButton(
                    onClick = onOpenFolder,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("查看归纳目录", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = onAgain,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("再扫描一次", fontWeight = FontWeight.Bold)
            }
            }
        }
    }
}
