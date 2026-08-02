package com.jingcun.cleaner

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.jingcun.cleaner.data.ScreenOrientation
import com.jingcun.cleaner.data.SettingsRepository
import com.jingcun.cleaner.ui.AppRoot
import com.jingcun.cleaner.ui.theme.JingCunTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 高刷适配：默认跟随系统最高刷新率（60/90/120/144Hz 自动适配）
        applyHighRefreshRate()

        // 持续监听屏幕方向设置：启动时应用上次设置，运行时切换立即生效
        val repo = SettingsRepository(application)
        lifecycleScope.launch {
            repo.settings
                .map { it.screenOrientation }
                .distinctUntilChanged()
                .collect { applyOrientation(it) }
        }

        setContent {
            JingCunTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                AppRoot(windowSizeClass = windowSizeClass)
            }
        }
    }

    /**
     * 高刷新率适配：默认 60Hz；若系统支持更高刷新率（90/120/144Hz）则自动跟随系统最高。
     *
     * 实现要点（避免常见坑）：
     * 1. 只在「与当前分辨率相同的 mode」中挑刷新率最高的，避免误改分辨率
     * 2. 仅当最高刷新率 > 60Hz 时才主动设置，60Hz 设备保持系统默认不额外耗电
     * 3. 通过 preferredDisplayModeId 设置，无需声明 manifest 权限
     *
     * 参考：Android 官方 Display.Mode / setPreferredDisplayModeId 文档
     */
    private fun applyHighRefreshRate() {
        runCatching {
            val display = display ?: return@runCatching
            val currentMode = display.mode
            val modes = display.supportedModes
            if (modes.isNullOrEmpty()) return@runCatching
            // 仅在当前分辨率相同的 mode 中挑选，防止分辨率被切换
            val sameResModes = modes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            val candidates = if (sameResModes.isNotEmpty()) sameResModes else modes.toList()
            val bestMode = candidates.maxByOrNull { it.refreshRate } ?: return@runCatching
            // 仅当系统最高刷新率高于 60Hz 时才主动设置
            if (bestMode.refreshRate <= 60f) return@runCatching
            val attrs = window.attributes
            attrs.preferredDisplayModeId = bestMode.modeId
            window.attributes = attrs
        }
    }

    /** 将屏幕方向设置映射到 ActivityInfo 方向常量 */
    private fun applyOrientation(orientation: ScreenOrientation) {
        requestedOrientation = when (orientation) {
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
}
