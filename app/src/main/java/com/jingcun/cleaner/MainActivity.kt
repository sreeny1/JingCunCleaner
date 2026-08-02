package com.jingcun.cleaner

import android.content.pm.ActivityInfo
import android.os.Bundle
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 安全调用 enableEdgeToEdge，部分定制 ROM 可能在此抛异常
        runCatching { enableEdgeToEdge() }

        // 高刷适配：默认跟随系统最高刷新率（60/90/120/144Hz 自动适配）
        applyHighRefreshRate()

        // 持续监听屏幕方向设置：启动时应用上次设置，运行时切换立即生效
        val repo = SettingsRepository(application)
        lifecycleScope.launch {
            repo.settings
                .catch { /* DataStore 损坏时静默忽略，使用默认值 */ }
                .map { it.screenOrientation }
                .distinctUntilChanged()
                .collect { runCatching { applyOrientation(it) } }
        }

        setContent {
            JingCunTheme {
                AppRoot(windowSizeClass = calculateWindowSizeClass(this@MainActivity))
            }
        }
    }

    /**
     * 高刷新率适配：默认 60Hz；若系统支持更高刷新率（90/120/144Hz）则自动跟随系统最高。
     */
    private fun applyHighRefreshRate() {
        runCatching {
            val display = display ?: return@runCatching
            val currentMode = display.mode
            val modes = display.supportedModes
            if (modes.isNullOrEmpty()) return@runCatching
            val sameResModes = modes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            val candidates = if (sameResModes.isNotEmpty()) sameResModes else modes.toList()
            val bestMode = candidates.maxByOrNull { it.refreshRate } ?: return@runCatching
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
