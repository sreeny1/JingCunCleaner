package com.jingcun.cleaner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingcun.cleaner.apk.ApkOrganizer
import com.jingcun.cleaner.apk.AppStorageScanner
import com.jingcun.cleaner.util.formatBytes

/**
 * APK 整理页面。
 *
 * 流程：扫描设备内所有 .apk → 展示应用名/版本号/大小/包名 → 一键整理到
 * /storage/emulated/0/apk整理/ 目录，命名为「应用名版本号.apk」。
 *
 * 借鉴 GitHub: pass-with-high-score/universal-installer 的 APK 元信息解析思路
 * （loadLabel 前必须设置 sourceDir/publicSourceDir，已在 ApkOrganizer 中实现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkOrganizerScreen() {
    val vm: CleanerViewModel = viewModel()
    val apkState by vm.apkState.collectAsState()
    val appStorageState by vm.appStorageState.collectAsState()
    val context = LocalContext.current

    val usageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.resetAppStorage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("APK 整理", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    val enabled = apkState.phase != CleanerViewModel.ApkPhase.SCANNING &&
                        apkState.phase != CleanerViewModel.ApkPhase.ORGANIZING
                    if (apkState.phase == CleanerViewModel.ApkPhase.READY ||
                        apkState.phase == CleanerViewModel.ApkPhase.DONE) {
                        OutlinedButton(onClick = { vm.resetApk(); vm.scanApks() }, enabled = enabled) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重新扫描")
                        }
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
            item { ApkIntroCard() }

            when (apkState.phase) {
                CleanerViewModel.ApkPhase.IDLE -> {
                    item {
                        ApkActionCard(
                            title = "扫描 APK 文件",
                            desc = "扫描设备内所有 .apk 文件并读取应用名与版本号",
                            buttonText = "开始扫描",
                            enabled = true,
                            onClick = vm::scanApks,
                        )
                    }
                }

                CleanerViewModel.ApkPhase.SCANNING -> {
                    item { ApkScanningCard(apkState) }
                }

                CleanerViewModel.ApkPhase.READY -> {
                    item { ApkSummaryCard(apkState) }
                    item {
                        ApkActionCard(
                            title = "一键整理",
                            desc = "将所有 APK 复制到 /storage/emulated/0/apk整理/ 并重命名为「应用名版本号.apk」",
                            buttonText = "开始整理",
                            enabled = apkState.apks.isNotEmpty(),
                            onClick = vm::organizeAllApks,
                        )
                    }
                    items(apkState.apks, key = { it.sourceFile.absolutePath }) { apk ->
                        ApkItemCard(apk)
                    }
                }

                CleanerViewModel.ApkPhase.ORGANIZING -> {
                    item { ApkOrganizingCard(apkState) }
                }

                CleanerViewModel.ApkPhase.DONE -> {
                    item { ApkDoneCard(apkState, onAgain = { vm.resetApk(); vm.scanApks() }) }
                }
            }

            // 已安装应用大小排行（始终显示在页面底部）
            item { Spacer(Modifier.height(8.dp)) }
            item {
                val needsAuth = vm.appStorageNeedsAuth()
                InstalledAppStorageSection(
                    state = appStorageState,
                    needsAuth = needsAuth,
                    onScan = { vm.scanInstalledApps() },
                    onRequestPermission = {
                        vm.createUsageAccessIntent()?.let { intent ->
                            runCatching { usageAccessLauncher.launch(intent) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ApkIntroCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 渐变圆形图标容器
            Surface(shape = CircleShape, modifier = Modifier.size(52.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                        )
                    )
                ) {
                    Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("自动整理设备内 APK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "扫描所有 .apk 文件，按「应用名+版本号」命名后复制到 apk整理 目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApkActionCard(
    title: String,
    desc: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
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
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(buttonText, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ApkScanningCard(state: CleanerViewModel.ApkUiState) {
    val animProgress by animateFloatAsState(state.progress, animationSpec = tween(300), label = "apk-scan")
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
            Spacer(Modifier.height(14.dp))
            Text("正在扫描 APK…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.currentName.ifEmpty { "准备中…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ApkSummaryCard(state: CleanerViewModel.ApkUiState) {
    val totalSize = state.apks.sumOf { it.size }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("发现 ${state.apks.size} 个 APK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("共 ${formatBytes(totalSize)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ApkItemCard(apk: ApkOrganizer.ApkInfo) {
    val displayName = "${apk.appName}${apk.versionName}".ifBlank { apk.sourceFile.nameWithoutExtension }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "v${apk.versionName} (${apk.versionCode}) · ${apk.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    apk.sourceFile.absolutePath.substringAfter("/storage/emulated/0/").ifEmpty { apk.sourceFile.absolutePath },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(formatBytes(apk.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ApkOrganizingCard(state: CleanerViewModel.ApkUiState) {
    val animProgress by animateFloatAsState(state.progress, animationSpec = tween(300), label = "apk-org")
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在整理 APK…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { animProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Text("${state.organizedCount} / ${state.totalCount}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ApkDoneCard(state: CleanerViewModel.ApkUiState, onAgain: () -> Unit) {
    AnimatedVisibility(visible = true, enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.96f), exit = fadeOut(tween(150))) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 渐变圆形成功图标
                Surface(shape = CircleShape, modifier = Modifier.size(80.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                            )
                        )
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("整理完成", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(8.dp))
                Text(state.message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("已保存到 /storage/emulated/0/apk整理/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onAgain, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("再扫描一次", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==================== 已安装应用大小排行 ====================

@Composable
private fun InstalledAppStorageSection(
    state: CleanerViewModel.AppStorageUiState,
    needsAuth: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 标题区
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("应用占用空间排行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val subtitle = if (state.isLegacy) "降级模式：仅显示 APK 安装包大小（车机或未授权时）" else "已安装应用的实际存储占用（含 APK+数据+缓存）"
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when {
            // 标准模式且未授权：提示授权
            needsAuth && state.phase != CleanerViewModel.AppStoragePhase.SCANNING -> {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("需要使用情况访问权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(6.dp))
                        Text("查询应用的完整存储占用需要「使用情况访问权限」。未授权时也可扫描，但仅显示 APK 安装包大小。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onRequestPermission, shape = RoundedCornerShape(12.dp)) {
                                Text("去授权")
                            }
                            Button(onClick = onScan, shape = RoundedCornerShape(12.dp)) {
                                Text("仅扫 APK 大小")
                            }
                        }
                    }
                }
            }

            // 扫描中
            state.phase == CleanerViewModel.AppStoragePhase.SCANNING -> {
                AppStorageScanningCard(state)
            }

            // 扫描完成
            state.phase == CleanerViewModel.AppStoragePhase.DONE -> {
                AppStorageSummaryCard(state, onRescan = onScan)
                // 列出所有应用（按大小降序）
                state.apps.forEach { app ->
                    AppStorageItemCard(app, maxSize = state.apps.firstOrNull()?.totalBytes ?: 1L)
                }
            }

            // 空闲状态（降级模式或已授权）
            else -> {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("扫描已安装应用占用", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val desc = if (state.isLegacy) "查看设备上所有已安装应用的 APK 安装包大小并排序" else "查看设备上所有已安装应用的实际存储占用并排序"
                        Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = onScan,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("开始扫描", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppStorageScanningCard(state: CleanerViewModel.AppStorageUiState) {
    val animProgress by animateFloatAsState(state.progress, animationSpec = tween(300), label = "app-storage-scan")
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
            Spacer(Modifier.height(14.dp))
            Text("正在扫描已安装应用…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.currentName.ifEmpty { "准备中…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppStorageSummaryCard(state: CleanerViewModel.AppStorageUiState, onRescan: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("共 ${state.apps.size} 个应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("总占用 ${formatBytes(state.totalSize)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            OutlinedButton(onClick = onRescan, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新扫描")
            }
        }
    }
}

@Composable
private fun AppStorageItemCard(app: AppStorageScanner.AppStorageInfo, maxSize: Long) {
    val fraction = if (maxSize > 0) (app.totalBytes.toFloat() / maxSize).coerceIn(0f, 1f) else 0f
    val animFraction by animateFloatAsState(fraction, animationSpec = tween(500), label = "bar")
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("应用 ${formatBytes(app.appBytes)}")
                            append(" · 数据 ${formatBytes(app.dataBytes)}")
                            if (app.cacheBytes > 0) append(" · 缓存 ${formatBytes(app.cacheBytes)}")
                            if (app.isSystemApp) append(" · 系统应用")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(formatBytes(app.totalBytes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            // 占比进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animFraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
