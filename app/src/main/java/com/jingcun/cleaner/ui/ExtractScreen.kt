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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExtractScreen() {
    val vm: ApkViewModel = viewModel()
    val state by vm.extractState.collectAsState()
    val shizukuStatus by vm.shizukuStatus.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var showBatchConfirm by rememberSaveable { mutableStateOf(false) }
    var showShizukuGuide by rememberSaveable { mutableStateOf(false) }

    // 进入页面自动加载已安装应用列表
    LaunchedEffect(Unit) {
        if (vm.shouldAutoLoadApps()) vm.loadInstalledApps()
    }

    val filtered = androidx.compose.runtime.remember(state.apps, filter, query) {
        state.apps.filter { app ->
            val passFilter = when (filter) {
                "user" -> !app.isSystemApp
                "system" -> app.isSystemApp
                "can" -> app.canDirectExtract
                else -> true
            }
            val passQuery = query.isBlank() ||
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            passFilter && passQuery
        }
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
            item { ShizukuStatusCard(shizukuStatus, onRequest = vm::requestShizukuPermission, onGuide = { showShizukuGuide = true }) }

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

            // 过滤 chips（含「可提取」：当前权限下无需 ADB/Root 即可提取的应用）
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
                    FilterChip(
                        selected = filter == "can",
                        onClick = { filter = "can" },
                        label = { Text("可提取") },
                    )
                }
            }

            // 批量提取按钮/进度（跟随当前筛选列表，提取前弹确认框）
            item {
                if (state.batchRunning) {
                    BatchProgressCard(
                        done = state.batchDone,
                        total = state.batchTotal,
                        current = state.batchCurrent,
                        onCancel = vm::cancelBatchExtract,
                    )
                } else {
                    val batchTargets = filtered.count { !vm.isSelfApp(it) }
                    Column {
                        Button(
                            onClick = { showBatchConfirm = true },
                            enabled = batchTargets > 0,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("提取当前列表（$batchTargets）", fontWeight = FontWeight.Bold)
                        }
                        if (state.batchMessage.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    state.batchMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                val failed = vm.failedExtractCount()
                                if (failed > 0) {
                                    Spacer(Modifier.width(10.dp))
                                    TextButton(onClick = vm::retryFailedExtracts) {
                                        Text("重试失败项（$failed）", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
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
                            // M3 Expressive 加载动画
                            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
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
                        batchRunning = state.batchRunning,
                        onExtract = { vm.extractApp(app) },
                        onClearResult = { vm.clearExtractResult(app.packageName) },
                    )
                }
            }
        }
    }

    // 批量提取确认框：避免误触一次性提取大量应用
    if (showBatchConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBatchConfirm = false },
            title = { Text("批量提取确认", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将提取当前列表中 ${filtered.count { !vm.isSelfApp(it) }} 个应用的安装包到「APK归纳/提取」目录，" +
                        "期间请保持应用在前台。确认开始？",
                )
            },
            confirmButton = {
                TextButton(onClick = { showBatchConfirm = false; vm.extractBatch(filtered) }) {
                    Text("开始提取", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchConfirm = false }) { Text("取消") }
            },
        )
    }

    // Shizuku（ADB 权限）开启教程
    if (showShizukuGuide) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showShizukuGuide = false },
            title = { Text("如何开启 ADB 权限", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "用户应用的安装包受系统保护，开启 ADB 权限（通过开源工具 Shizuku）后即可提取。" +
                            "系统应用无需此操作。开启步骤：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "方式一：手机 / 平板（Android 11 及以上）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "1. 安装 Shizuku（GitHub 搜索 RikkaApps/Shizuku）\n" +
                            "2. 打开 Shizuku →「通过无线调试启动」按提示配对\n" +
                            "3. 回到本应用，等待状态卡变为「已就绪」后点「授权」",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "方式二：车机 / 电视（需一台电脑）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "1. 车机开启开发者选项（比亚迪等车机通常在「设置 → 关于 → 版本号」连点 7 次）\n" +
                            "2. 打开「USB 调试」/「网络 ADB 调试」\n" +
                            "3. 电脑执行：adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n" +
                            "4. 回到本应用点「授权」",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "不开启也没有关系：系统应用可正常提取，用户应用会明确标注「需ADB」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showShizukuGuide = false }) { Text("我知道了") }
            },
        )
    }
}

/** ADB（Shizuku）权限状态卡 */
@Composable
private fun ShizukuStatusCard(status: ShizukuStatus, onRequest: () -> Unit, onGuide: () -> Unit) {
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
            } else if (status == ShizukuStatus.NOT_RUNNING) {
                TextButton(onClick = onGuide) { Text("开启教程", fontWeight = FontWeight.SemiBold) }
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
            // M3 Expressive 波浪进度条
            LinearWavyProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
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
    batchRunning: Boolean,
    onExtract: () -> Unit,
    onClearResult: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 图标按显示尺寸×屏幕密度渲染，避免高分屏（车机 1080p+）上发糊
    val iconSizePx = with(density) { 42.dp.toPx() }.toInt()
    val iconBitmap = androidx.compose.runtime.remember(app.icon, iconSizePx) {
        drawableToImageBitmap(app.icon, iconSizePx)
    }
    // 成功提示 6 秒后自动消失；失败提示保留
    LaunchedEffect(app.packageName, result?.first) {
        if (result?.first == true) {
            kotlinx.coroutines.delay(6000)
            onClearResult()
        }
    }
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
                        if (!app.canDirectExtract) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                Text(
                                    "需ADB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
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
                    // 批量提取进行中禁用单项按钮，防止与批量任务并发写同一文件
                    IconButton(onClick = onExtract, enabled = !batchRunning) {
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
