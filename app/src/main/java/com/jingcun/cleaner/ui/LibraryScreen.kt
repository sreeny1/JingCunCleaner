package com.jingcun.cleaner.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingcun.cleaner.apk.ApkOrganizer
import com.jingcun.cleaner.util.formatBytes

/**
 * APK 库页：浏览设备本地所有 APK 文件（图标 / 名称 / 版本 / 大小），
 * 顶部标签按存储卷切换，点击单个文件弹操作菜单（安装 / 删除 / 详情）。
 * 与归纳页共用同一份扫描结果与扫描缓存，进入页面不重复扫描。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen() {
    val vm: ApkViewModel = viewModel()
    val state by vm.organizeState.collectAsState()
    val libState by vm.libraryState.collectAsState()
    var selectedVolume by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTarget by remember { mutableStateOf<ApkOrganizer.ApkInfo?>(null) }
    var detailTarget by remember { mutableStateOf<ApkOrganizer.ApkInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<ApkOrganizer.ApkInfo?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 进入页面自动扫描（与归纳页共用防重复标记，两边不重复扫描）
    LaunchedEffect(Unit) { if (vm.shouldAutoScan()) vm.scanApks() }

    // 安装 / 删除结果提示（Snackbar 展示后清除消息）
    LaunchedEffect(libState.installMessage, libState.deleteMessage) {
        val msg = libState.installMessage.ifEmpty { libState.deleteMessage }
        if (msg.isNotEmpty()) {
            snackbar.showSnackbar(msg)
            vm.clearLibraryMessages()
        }
    }

    // 卷标签：全部 + 各存储卷（分组与筛选均按显示名，和归纳页卷筛选一致）
    val volumes = remember(state.apks) {
        state.apks.groupingBy { it.volumeName.ifEmpty { "未知位置" } }.eachCount()
    }
    val filtered = remember(state.apks, selectedVolume) {
        state.apks.filter { selectedVolume == null || it.volumeName.ifEmpty { "未知位置" } == selectedVolume }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("APK库", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            // 扫描中且尚无数据：居中进度
            state.phase == OrganizePhase.SCANNING && state.apks.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LoadingIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        state.currentName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 空状态：尚未扫描 / 未找到 / 扫描失败
            state.apks.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (state.message.isNotEmpty()) state.message else "尚未扫描，点击下方按钮开始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.scanApks(force = true) },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("扫描 APK 文件", fontWeight = FontWeight.Bold)
                    }
                }
            }
            // 列表：卷标签 + APK 项
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "volumes") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedVolume == null,
                                onClick = { selectedVolume = null },
                                label = { Text("全部 · ${state.apks.size}") },
                            )
                            volumes.forEach { (name, count) ->
                                FilterChip(
                                    selected = selectedVolume == name,
                                    onClick = {
                                        selectedVolume = if (selectedVolume == name) null else name
                                    },
                                    label = { Text("$name · $count") },
                                )
                            }
                        }
                    }
                    items(filtered, key = { it.sourceFile.absolutePath }) { apk ->
                        LibraryItemCard(apk, vm = vm, onClick = { menuTarget = apk })
                    }
                }
            }
        }
    }

    // 操作菜单：安装 / 删除 / 详情
    menuTarget?.let { apk ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = {
                Text(apk.appName.ifEmpty { apk.sourceFile.name }, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        listOfNotNull(
                            apk.versionName.ifEmpty { "未识别" },
                            formatBytes(apk.size),
                            apk.volumeName.ifEmpty { null },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (apk.integrity != ApkOrganizer.ApkIntegrity.OK) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "此文件可能不完整或非标准 APK，安装可能失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            menuTarget = null
                            vm.installApkFromLibrary(apk)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("安装", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            menuTarget = null
                            confirmDelete = apk
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("删除", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            menuTarget = null
                            detailTarget = apk
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("详情", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menuTarget = null }) { Text("关闭") }
            },
        )
    }

    // 详情对话框
    detailTarget?.let { apk ->
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            title = { Text("文件详情", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("名称", apk.appName.ifEmpty { apk.sourceFile.name })
                    DetailRow("包名", apk.packageName.ifEmpty { "未识别" })
                    DetailRow("版本", apk.versionName.ifEmpty { "未识别" })
                    DetailRow("版本码", if (apk.versionCode > 0) apk.versionCode.toString() else "未识别")
                    DetailRow("大小", formatBytes(apk.size))
                    DetailRow("所在位置", apk.volumeName.ifEmpty { "未知位置" })
                    DetailRow(
                        "完整性",
                        when (apk.integrity) {
                            ApkOrganizer.ApkIntegrity.OK -> "正常"
                            ApkOrganizer.ApkIntegrity.INCOMPLETE -> "不完整（可能下载中断）"
                            ApkOrganizer.ApkIntegrity.NON_APK -> "非标准安装包"
                            ApkOrganizer.ApkIntegrity.UNKNOWN -> "元信息无法识别"
                        },
                    )
                    DetailRow("路径", apk.sourceFile.absolutePath)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { detailTarget = null }) { Text("关闭") }
            },
        )
    }

    // 删除确认框
    confirmDelete?.let { apk ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除 APK", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将删除「${apk.appName.ifEmpty { apk.sourceFile.name }}」（${formatBytes(apk.size)}）。\n\n" +
                        when {
                            apk.inTargetDir -> "此文件位于「${ApkOrganizer.TARGET_DIR_NAME}」目录中，删除后不可恢复。"
                            apk.alreadyOrganized -> "归纳目录中已有该文件副本，删除后不影响已归纳的文件。"
                            else -> "删除后不可恢复。"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.deleteApk(apk)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

/** APK 库列表项：图标（异步解析渐进浮现）+ 名称 + 版本/大小 + 完整性与已归纳徽标 */
@Composable
private fun LibraryItemCard(apk: ApkOrganizer.ApkInfo, vm: ApkViewModel, onClick: () -> Unit) {
    val key = "${apk.sourceFile.absolutePath}:${apk.size}"
    var icon by remember(key) { mutableStateOf(vm.cachedIcon(apk)) }
    LaunchedEffect(key) { if (icon == null) icon = vm.loadIcon(apk) }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 应用图标（解析完成前显示底色占位）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                icon?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    apk.appName.ifEmpty { apk.sourceFile.name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        apk.versionName.ifEmpty { "未识别" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatBytes(apk.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (apk.integrity != ApkOrganizer.ApkIntegrity.OK || apk.alreadyOrganized || apk.inTargetDir) {
                    Spacer(Modifier.height(5.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (apk.integrity != ApkOrganizer.ApkIntegrity.OK) {
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                Text(
                                    when (apk.integrity) {
                                        ApkOrganizer.ApkIntegrity.INCOMPLETE -> "不完整·可能无法安装"
                                        ApkOrganizer.ApkIntegrity.NON_APK -> "非标准安装包"
                                        else -> "元信息无法识别"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (apk.alreadyOrganized || apk.inTargetDir) {
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    "已归纳",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 详情对话框的「标签 - 值」行 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
