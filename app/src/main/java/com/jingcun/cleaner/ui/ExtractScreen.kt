package com.jingcun.cleaner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingcun.cleaner.apk.AppExtractor
import com.jingcun.cleaner.util.drawableToImageBitmap
import com.jingcun.cleaner.util.formatBytes

/**
 * 应用提取页：列出设备所有已安装应用（含系统应用），支持单个/批量提取 APK。
 *
 * 权限说明：系统应用可直接提取；用户应用受 Android 系统限制，
 * 需要 ADB 权限（Shizuku）或 Root；均无权限时提示用户暂时无法提取，
 * 但应用列表始终可见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen() {
    val vm: ApkViewModel = viewModel()
    val state by vm.extractState.collectAsState()
    val shizukuStatus by vm.shizukuStatus.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }

    // 进入页面自动加载已安装应用列表
    LaunchedEffect(Unit) {
        if (vm.shouldAutoLoadApps()) vm.loadInstalledApps()
    }

    val filtered = state.apps.filter { app ->
        val passFilter = when (filter) {
            "user" -> !app.isSystemApp
            "system" -> app.isSystemApp
            else -> true
        }
        val passQuery = query.isBlank() ||
            app.appName.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
        passFilter && passQuery
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("应用提取", fontWeight = FontWeight.Bold)
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ADB 权限状态卡
            item { ShizukuStatusCard(shizukuStatus, onRequest = vm::requestShizukuPermission) }

            // 搜索框
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用名或包名") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }

            // 过滤 chips + 批量提取
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = filter == "all",
                        onClick = { filter = "all" },
                        label = { Text("全部") },
                    )
                    FilterChip(
                        selected = filter == "user",
                        onClick = { filter = "user" },
                        label = { Text("用户应用") },
                    )
                    FilterChip(
                        selected = filter == "system",
                        onClick = { filter = "system" },
                        label = { Text("系统应用") },
                    )
                }
            }

            // 批量提取按钮/进度
            item {
                if (state.batchRunning) {
                    BatchProgressCard(
                        done = state.batchDone,
                        total = state.batchTotal,
                        current = state.batchCurrent,
                        onCancel = vm::cancelBatchExtract,
                    )
                } else {
                    val userApps = state.apps.count { !it.isSystemApp && !vm.isSelfApp(it) }
                    Button(
                        onClick = vm::extractAllUserApps,
                        enabled = userApps > 0,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("提取全部用户应用（$userApps）", fontWeight = FontWeight.Bold)
                    }
                    if (state.batchMessage.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.batchMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 应用列表
            if (state.loading) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("正在读取已安装应用…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        "没有匹配的应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(filtered, key = { it.packageName }) { app ->
                    AppItemCard(
                        app = app,
                        extracting = app.packageName in state.extracting,
                        result = state.results[app.packageName],
                        onExtract = { vm.extractApp(app) },
                    )
                }
            }
        }
    }
}

/** ADB（Shizuku）权限状态卡 */
@Composable
private fun ShizukuStatusCard(status: ShizukuStatus, onRequest: () -> Unit) {
    val (container, content) = when (status) {
        ShizukuStatus.GRANTED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ShizukuStatus.NEED_PERMISSION ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ShizukuStatus.NOT_RUNNING ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (status) {
                    ShizukuStatus.GRANTED -> Icons.Filled.CheckCircle
                    ShizukuStatus.NEED_PERMISSION -> Icons.Filled.Usb
                    ShizukuStatus.NOT_RUNNING -> Icons.Filled.WarningAmber
                },
                contentDescription = null,
                tint = content,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = when (status) {
                    ShizukuStatus.GRANTED -> "ADB 权限已就绪"
                    ShizukuStatus.NEED_PERMISSION -> "Shizuku 运行中，尚未授权"
                    ShizukuStatus.NOT_RUNNING -> "设备未开启 ADB 权限"
                }
                val desc = when (status) {
                    ShizukuStatus.GRANTED -> "可通过 ADB 权限提取全部已安装应用的 APK"
                    ShizukuStatus.NEED_PERMISSION -> "授权后可提取用户应用的 APK"
                    ShizukuStatus.NOT_RUNNING ->
                        "系统应用可直接提取；用户应用需要 ADB 权限（Shizuku）或 Root。" +
                            "当前设备暂无可用权限，用户应用暂时无法提取，但列表仍可查看。"
                }
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = content)
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = content)
            }
            if (status == ShizukuStatus.NEED_PERMISSION) {
                Button(onClick = onRequest, shape = RoundedCornerShape(12.dp)) { Text("授权") }
            }
        }
    }
}

/** 批量提取进度卡 */
@Composable
private fun BatchProgressCard(done: Int, total: Int, current: String, onCancel: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在提取 $done / $total · $current",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消")
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** 单个已安装应用条目 */
@Composable
private fun AppItemCard(
    app: AppExtractor.InstalledApp,
    extracting: Boolean,
    result: Pair<Boolean, String>?,
    onExtract: () -> Unit,
) {
    val iconBitmap = androidx.compose.runtime.remember(app.icon) { drawableToImageBitmap(app.icon) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 真实应用图标
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = app.appName,
                                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)),
                            )
                        } else {
                            Icon(Icons.Filled.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            app.appName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (app.isSystemApp) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    "系统",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "v${app.versionName.ifBlank { "未知" }} · ${formatBytes(app.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (extracting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    IconButton(onClick = onExtract) {
                        Icon(
                            if (result?.first == true) Icons.Filled.DownloadDone else Icons.Filled.Download,
                            contentDescription = "提取",
                            tint = if (result?.first == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // 提取结果提示
            AnimatedVisibility(visible = result != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                if (result != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        (if (result.first) "✓ " else "") + result.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.first) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
