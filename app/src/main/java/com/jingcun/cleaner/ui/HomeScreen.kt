package com.jingcun.cleaner.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingcun.cleaner.scanner.JunkCategory
import com.jingcun.cleaner.scanner.JunkItem
import com.jingcun.cleaner.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val vm: CleanerViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.updatePermission() }

    // 运行时权限弹窗（降级模式用，车机无全盘授权页时走这里）
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.updatePermission() }

    val requestPermission: () -> Unit = {
        // 优先尝试标准全盘授权页
        val fullIntent = vm.requestStoragePermission()
        if (fullIntent != null) {
            runCatching { permissionLauncher.launch(fullIntent) }
                .onFailure {
                    // 全盘页跳转失败，降级到运行时弹窗
                    runtimePermissionLauncher.launch(vm.legacyStoragePermissions())
                }
        } else {
            // 车机无全盘授权页，直接走运行时弹窗授权
            runtimePermissionLauncher.launch(vm.legacyStoragePermissions())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("净存清理", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    // 重新扫描按钮（任何阶段都可点）
                    val enabled = state.phase != Phase.SCANNING && state.phase != Phase.CLEANING
                    IconButton(onClick = { vm.rescan() }, enabled = enabled) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (state.phase == Phase.DONE) {
                BottomCleanBar(
                    selectedCount = state.selected.size,
                    selectedSize = selectedSizeOf(state),
                    onClean = vm::clean,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StorageCard(state) }

            when (state.phase) {
                Phase.IDLE -> {
                    if (!state.hasStoragePermission) item { PermissionCard(onGrant = requestPermission) }
                    item { IdleScanCard(enabled = state.hasStoragePermission, onScan = vm::startScan) }
                    item { FeatureHintCard() }
                }
                Phase.SCANNING -> item { ScanningCard(state) }
                Phase.DONE -> {
                    item { SummaryCard(state, vm) }
                    JunkCategory.DISPLAY_ORDER.forEach { cat ->
                        val list = state.results[cat]
                        if (!list.isNullOrEmpty()) item { CategoryCard(cat, list, state.selected, vm) }
                    }
                    if (state.results.isEmpty()) item { EmptyResultCard(onRescan = vm::startScan) }
                }
                Phase.CLEANING -> item { CleaningCard(state) }
                Phase.CLEANED -> item {
                    CleanedCard(state, onRescan = { vm.reset(); vm.startScan() })
                }
            }
        }
    }
}

@Composable
private fun StorageCard(state: UiState) {
    val used = (state.storageTotal - state.storageFree).coerceAtLeast(0L)
    val usedFraction = if (state.storageTotal > 0) used.toFloat() / state.storageTotal else 0f
    val animFraction by animateFloatAsState(usedFraction, animationSpec = tween(800), label = "ring")

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(124.dp), contentAlignment = Alignment.Center) {
                StorageRing(animFraction)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(animFraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    Text("已使用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("存储空间", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                StorageRow("已用", formatBytes(used))
                Spacer(Modifier.height(4.dp))
                StorageRow("可用", formatBytes(state.storageFree))
                Spacer(Modifier.height(4.dp))
                StorageRow("总计", formatBytes(state.storageTotal))
            }
        }
    }
}

@Composable
private fun StorageRing(usedFraction: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    // 渐变画刷：主色到第三色，让圆环更有层次
    val brush = androidx.compose.ui.graphics.Brush.sweepGradient(listOf(primary, tertiary, primary))
    Canvas(modifier = Modifier.size(124.dp)) {
        val stroke = 16.dp.toPx()
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        drawArc(color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = Size(diameter, diameter), style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawArc(brush = brush, startAngle = -90f, sweepAngle = 360f * usedFraction.coerceIn(0f, 1f), useCenter = false, topLeft = topLeft, size = Size(diameter, diameter), style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun StorageRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text("需要存储访问权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text("为了扫描并清理垃圾文件，需要授予存储访问权限。数据不会被上传，所有清理均在本地完成。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("授予权限") }
        }
    }
}

@Composable
private fun IdleScanCard(enabled: Boolean, onScan: () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // 图标容器用渐变背景提升质感
            Surface(shape = CircleShape, modifier = Modifier.size(104.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                    )
                )) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(56.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("一键扫描垃圾文件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("扫描应用缓存、APK 残留、临时/日志、缩略图缓存、系统残留与空文件夹", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            // 主按钮用渐变背景
            Button(onClick = onScan, enabled = enabled, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.CleaningServices, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始扫描", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            if (!enabled) {
                Spacer(Modifier.height(10.dp))
                Text("请先授予存储访问权限", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FeatureHintCard() {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("清理范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            FeatureRow(Icons.Filled.Cached, "应用缓存", "各应用缓存目录")
            FeatureRow(Icons.Filled.Android, "APK 残留", "已安装版本的旧安装包")
            FeatureRow(Icons.Filled.Image, "缩略图缓存", ".thumbnails 等缩略图")
            FeatureRow(Icons.Filled.Description, "临时/日志", ".tmp .log .bak .old")
            FeatureRow(Icons.Filled.BugReport, "系统残留", ".DS_Store LOST.DIR 等")
            FeatureRow(Icons.Filled.FolderOpen, "空文件夹", "递归识别空目录")
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScanningCard(state: UiState) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
            Spacer(Modifier.height(16.dp))
            Text("正在扫描…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(state.currentPath.ifEmpty { "准备中…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SummaryCard(state: UiState, vm: CleanerViewModel) {
    val allSelected = state.results.values.flatten().isNotEmpty() && state.selected.size == state.results.values.flatten().size
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allSelected, onCheckedChange = { vm.selectAll(it) })
                Column(modifier = Modifier.weight(1f)) {
                    Text("发现 ${state.totalItems} 项可清理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("共计 ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(cat: JunkCategory, list: List<JunkItem>, selected: Set<String>, vm: CleanerViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val allChecked = list.isNotEmpty() && list.all { it.path in selected }
    val catSize = list.sumOf { it.size }
    val selectedInCat = list.count { it.path in selected }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allChecked, onCheckedChange = { vm.setCategorySelected(cat, it) })
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(categoryIcon(cat), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp)) }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(cat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (cat == JunkCategory.EMPTY_FOLDER) "${list.size} 个空文件夹" else "${list.size} 项 · ${formatBytes(catSize)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (cat != JunkCategory.EMPTY_FOLDER && selectedInCat > 0) {
                    Text(formatBytes(list.filter { it.path in selected }.sumOf { it.size }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "收起" else "展开") }
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                Column {
                    HorizontalDivider()
                    list.take(60).forEach { item ->
                        val checked = item.path in selected
                        Row(modifier = Modifier.fillMaxWidth().clickable { vm.toggleSelect(item.path) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { vm.toggleSelect(item.path) })
                            Spacer(Modifier.width(8.dp))
                            Text(item.path.substringAfter("/storage/emulated/0/").ifEmpty { item.path }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (!item.isDirectory && item.size > 0) Text(formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (list.size > 60) Text("…还有 ${list.size - 60} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyResultCard(onRescan: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("设备很干净", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("未发现可清理的垃圾文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRescan) { Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("重新扫描") }
        }
    }
}

@Composable
private fun CleaningCard(state: UiState) {
    val animProgress by animateFloatAsState(state.progress.coerceIn(0f, 1f), animationSpec = tween(300), label = "clean")
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在清理…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(progress = { animProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(10.dp))
            Text("${state.cleanedCount} 项已处理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CleanedCard(state: UiState, onRescan: () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // 成功图标用渐变圆形容器
            Surface(shape = CircleShape, modifier = Modifier.size(80.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                    )
                )) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("清理完成", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(8.dp))
            Text("已释放 ${formatBytes(state.freedBytes)}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            if (state.trashedCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text("${state.trashedCount} 项已移入回收站，可前往「回收站」恢复", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRescan, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("再扫描一次", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BottomCleanBar(selectedCount: Int, selectedSize: Long, onClean: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("已选 $selectedCount 项", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatBytes(selectedSize), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Button(onClick = onClean, enabled = selectedCount > 0, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.height(52.dp)) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("一键清理", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun categoryIcon(cat: JunkCategory): ImageVector = when (cat) {
    JunkCategory.APP_CACHE -> Icons.Filled.Cached
    JunkCategory.APK_RESIDUAL -> Icons.Filled.Android
    JunkCategory.THUMBNAIL -> Icons.Filled.Image
    JunkCategory.TEMP_LOG -> Icons.Filled.Description
    JunkCategory.SYSTEM_JUNK -> Icons.Filled.BugReport
    JunkCategory.EMPTY_FOLDER -> Icons.Filled.FolderOpen
}
