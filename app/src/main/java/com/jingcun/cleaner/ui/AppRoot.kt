package com.jingcun.cleaner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jingcun.cleaner.util.formatBytes

/** 顶部级目的地：归纳 / 提取 / 设置 */
sealed class TopDest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Organize : TopDest("organize", "归纳", Icons.Filled.Inventory2)
    data object Extract : TopDest("extract", "提取", Icons.Filled.Smartphone)
    data object Settings : TopDest("settings", "设置", Icons.Filled.Settings)
}

private val topDestinations = listOf(TopDest.Organize, TopDest.Extract, TopDest.Settings)

/** 是否宽屏（平板/车机）→ 侧边 Rail 导航；窄屏用底部 Bar */
fun WindowSizeClass.isWide(): Boolean = widthSizeClass != WindowWidthSizeClass.Compact

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun AppRoot(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: TopDest.Organize.route
    val vm: ApkViewModel = viewModel()
    val updateState by vm.updateState.collectAsState()

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            topDestinations.forEach { dest ->
                val selected = currentRoute == dest.route
                item(
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            navController.navigate(dest.route) {
                                popUpTo(TopDest.Organize.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                )
            }
        },
        // 宽屏（车机/平板）使用侧边 Rail，窄屏自动 BottomBar
        layoutType = if (windowSizeClass.isWide()) NavigationSuiteType.NavigationRail
                     else NavigationSuiteType.NavigationBar,
    ) {
        NavHost(
            navController = navController,
            startDestination = TopDest.Organize.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
        ) {
            composable(TopDest.Organize.route) { OrganizeScreen() }
            composable(TopDest.Extract.route) { ExtractScreen() }
            composable(TopDest.Settings.route) { SettingsScreen() }
        }
    }

    // 全局 OTA 更新弹窗：仅在有新版本/下载中/已下载时弹出
    UpdateDialog(vm = vm, state = updateState)
}

/**
 * OTA 更新全局弹窗。
 * - AVAILABLE：展示版本号+更新日志，提供「立即下载」/「稍后」
 * - DOWNLOADING：展示进度条，提供「取消」
 * - DOWNLOADED：提示下载完成，提供「立即安装」/「稍后」
 */
@Composable
private fun UpdateDialog(vm: ApkViewModel, state: ApkViewModel.UpdateUiState) {
    val show = !state.dialogDismissed && state.phase in listOf(
        ApkViewModel.UpdatePhase.AVAILABLE,
        ApkViewModel.UpdatePhase.DOWNLOADING,
        ApkViewModel.UpdatePhase.DOWNLOADED,
    )
    if (!show) return

    when (state.phase) {
        ApkViewModel.UpdatePhase.AVAILABLE -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = { vm.dismissUpdate() },
                title = {
                    Text("发现新版本 v${info?.versionName ?: ""}", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        info?.fileSize?.let {
                            Text("安装包大小：${formatBytes(it)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text("更新日志：", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info?.releaseNotes ?: "暂无更新说明",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { vm.downloadUpdate() }) {
                        Text("立即下载")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissUpdate() }) { Text("稍后") }
                },
            )
        }

        ApkViewModel.UpdatePhase.DOWNLOADING -> {
            val animPercent by animateFloatAsState(state.percent, animationSpec = tween(200), label = "ota-dl")
            AlertDialog(
                onDismissRequest = { vm.dismissUpdate() },
                title = { Text("正在下载新版本", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { animPercent.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${(animPercent * 100).toInt()}%  ·  ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { vm.dismissUpdate() }) { Text("取消") }
                },
            )
        }

        ApkViewModel.UpdatePhase.DOWNLOADED -> {
            AlertDialog(
                onDismissRequest = { vm.dismissUpdate() },
                title = { Text("下载完成", fontWeight = FontWeight.Bold) },
                text = {
                    Text("新版本已下载完成，是否立即安装？\n\n安装时系统会弹出安装确认页，请允许「安装未知应用」权限。")
                },
                confirmButton = {
                    Button(onClick = { vm.installUpdate() }) { Text("立即安装") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissUpdate() }) { Text("稍后") }
                },
            )
        }

        else -> {}
    }
}
