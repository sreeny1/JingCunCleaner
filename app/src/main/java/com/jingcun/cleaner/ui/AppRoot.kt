package com.jingcun.cleaner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import com.jingcun.cleaner.util.formatBytes

/** 顶部级目标 */
sealed class TopDest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Home : TopDest("home", "清理", Icons.Filled.CleaningServices)
    data object Apk : TopDest("apk", "APK整理", Icons.Filled.Android)
    data object Trash : TopDest("trash", "回收站", Icons.Filled.Delete)
    data object Settings : TopDest("settings", "设置", Icons.Filled.Settings)
    data object About : TopDest("about", "关于", Icons.Filled.Info)
}

private val topDestinations = listOf(TopDest.Home, TopDest.Apk, TopDest.Trash, TopDest.Settings, TopDest.About)

/** 是否宽屏（平板/车机）→ 用双栏或导航栏形态 */
fun WindowSizeClass.isWide(): Boolean = widthSizeClass != WindowWidthSizeClass.Compact

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun AppRoot(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: TopDest.Home.route
    val vm: CleanerViewModel = viewModel()
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
                                popUpTo(TopDest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { androidx.compose.material3.Icon(dest.icon, contentDescription = dest.label) },
                    label = { androidx.compose.material3.Text(dest.label) },
                )
            }
        },
        // 强制宽屏使用 NavigationRail（车机/平板友好）；窄屏自动 BottomBar
        layoutType = if (windowSizeClass.isWide()) NavigationSuiteType.NavigationRail
                     else NavigationSuiteType.NavigationBar,
    ) {
        NavHost(
            navController = navController,
            startDestination = TopDest.Home.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
        ) {
            composable(TopDest.Home.route) { HomeScreen() }
            composable(TopDest.Apk.route) { ApkOrganizerScreen() }
            composable(TopDest.Trash.route) { TrashScreen() }
            composable(TopDest.Settings.route) { SettingsScreen() }
            composable(TopDest.About.route) { AboutScreen() }
        }
    }

    // 全局 OTA 更新弹窗：仅在有新版本/下载中/已下载时弹出
    UpdateDialog(vm = vm, state = updateState)
}

/**
 * OTA 更新全局弹窗。
 * - AVAILABLE：展示版本号+更新说明，提供「立即下载」/「稍后」
 * - DOWNLOADING：展示进度条，提供「取消」
 * - DOWNLOADED：提示下载完成，提供「立即安装」/「稍后」
 * - IDLE/CHECKING/ERROR：不弹窗（状态由设置页卡片显示）
 *
 * 引导式安装：点击「立即安装」调起系统 PackageInstaller，由用户在系统页确认。
 */
@Composable
private fun UpdateDialog(vm: CleanerViewModel, state: CleanerViewModel.UpdateUiState) {
    val show = state.phase in listOf(
        CleanerViewModel.UpdatePhase.AVAILABLE,
        CleanerViewModel.UpdatePhase.DOWNLOADING,
        CleanerViewModel.UpdatePhase.DOWNLOADED,
    )
    if (!show) return

    when (state.phase) {
        CleanerViewModel.UpdatePhase.AVAILABLE -> {
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
                        Text("更新说明：", style = MaterialTheme.typography.labelLarge)
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

        CleanerViewModel.UpdatePhase.DOWNLOADING -> {
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

        CleanerViewModel.UpdatePhase.DOWNLOADED -> {
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
