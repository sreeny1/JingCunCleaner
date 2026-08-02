package com.jingcun.cleaner.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingcun.cleaner.data.ApkOrganizeMode
import com.jingcun.cleaner.data.EmptyFolderScope
import com.jingcun.cleaner.data.ScreenOrientation
import com.jingcun.cleaner.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val vm: CleanerViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    val updateState by vm.updateState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
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
            // 在线更新（OTA）
            item { UpdateCheckCard(vm = vm, state = updateState) }
            // 回收站
            item {
                SettingCard("回收站", "删除文件时移入回收站，可一键恢复，避免误删") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用回收站", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = settings.recycleBinEnabled, onCheckedChange = { vm.setRecycleBinEnabled(it) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("保留天数：${settings.trashRetentionDays} 天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 7, 14, 30).forEach { d ->
                            FilterChip(
                                selected = settings.trashRetentionDays == d,
                                onClick = { vm.setTrashRetentionDays(d) },
                                label = { Text("$d 天") },
                            )
                        }
                    }
                }
            }
            // 空文件夹扫描范围
            item {
                SettingCard("空文件夹扫描", "设置扫描空文件夹的范围") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.emptyFolderScope == EmptyFolderScope.ROOT_ONLY,
                            onClick = { vm.setEmptyFolderScope(EmptyFolderScope.ROOT_ONLY) },
                            label = { Text(EmptyFolderScope.ROOT_ONLY.title) },
                        )
                        FilterChip(
                            selected = settings.emptyFolderScope == EmptyFolderScope.ALL,
                            onClick = { vm.setEmptyFolderScope(EmptyFolderScope.ALL) },
                            label = { Text(EmptyFolderScope.ALL.title) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("默认仅扫描根目录空文件夹，更安全快速", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 扫描后直接清理
            item {
                SettingCard("扫描后直接清理", "扫描完成自动清理选中项，无需手动点击") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = settings.scanThenClean, onCheckedChange = { vm.setScanThenClean(it) })
                    }
                    if (settings.scanThenClean) {
                        Spacer(Modifier.height(6.dp))
                        Text("⚠️ 开启后扫描完成将立即清理，请确认回收站已开启以防误删", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // APK 整理模式
            item {
                SettingCard("APK 整理模式", "整理时复制（保留原文件）或移动（删除原文件）") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApkOrganizeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.apkOrganizeMode == mode,
                                onClick = { vm.setApkOrganizeMode(mode) },
                                label = { Text(mode.title) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(settings.apkOrganizeMode.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 屏幕方向
            item {
                SettingCard("屏幕方向", "手动切换横屏/竖屏，适合无重力感应的车机/平板设备") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenOrientation.entries.forEach { orient ->
                            FilterChip(
                                selected = settings.screenOrientation == orient,
                                onClick = { vm.setScreenOrientation(orient) },
                                label = { Text(orient.title) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(settings.screenOrientation.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 说明
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("提示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("· 所有设置即时生效并自动保存", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("· 回收站文件存于 /storage/emulated/0/.JingCunTrash/", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("· APK 整理目录为 /storage/emulated/0/apk整理/", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * OTA 检查更新卡片：显示当前版本号 + 「检查更新」按钮 + 状态文案。
 * 详细的更新弹窗（更新说明/下载进度/安装）由 AppRoot 中的 UpdateDialog 全局展示。
 */
@Composable
private fun UpdateCheckCard(vm: CleanerViewModel, state: CleanerViewModel.UpdateUiState) {
    val currentVer = vm.currentVersionName().ifBlank { "未知" }
    val checking = state.phase == CleanerViewModel.UpdatePhase.CHECKING
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("在线更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("当前版本 v$currentVer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.checkForUpdate(silent = false) },
                    enabled = !checking,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("检查中…")
                    } else {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("检查更新")
                    }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(state),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun statusColor(state: CleanerViewModel.UpdateUiState) = when (state.phase) {
    CleanerViewModel.UpdatePhase.AVAILABLE, CleanerViewModel.UpdatePhase.DOWNLOADED ->
        MaterialTheme.colorScheme.primary
    CleanerViewModel.UpdatePhase.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
    CleanerViewModel.UpdatePhase.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusText(state: CleanerViewModel.UpdateUiState): String = when (state.phase) {
    CleanerViewModel.UpdatePhase.IDLE -> if (state.manual) state.message.ifBlank { "已是最新版本" } else "点击检查 GitHub 最新版本"
    CleanerViewModel.UpdatePhase.CHECKING -> "正在检查…"
    CleanerViewModel.UpdatePhase.AVAILABLE -> "发现新版本 v${state.info?.versionName ?: ""}"
    CleanerViewModel.UpdatePhase.DOWNLOADING -> {
        val p = (state.percent * 100).toInt()
        "下载中 $p%（${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}）"
    }
    CleanerViewModel.UpdatePhase.DOWNLOADED -> "下载完成，点击安装"
    CleanerViewModel.UpdatePhase.ERROR -> state.message.ifBlank { "检查失败" }
}
