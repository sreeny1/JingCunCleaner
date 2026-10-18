package com.jingcun.cleaner.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** APK 整理模式：移动（整理后删除原文件）或复制（保留原文件） */
enum class ApkOrganizeMode(val title: String, val desc: String) {
    MOVE("移动", "整理后删除原 APK 文件，节省空间"),
    COPY("复制", "整理后保留原 APK 文件"),
}

/** 屏幕方向模式：自动跟随重力感应 / 强制横屏 / 强制竖屏 */
enum class ScreenOrientation(val title: String, val desc: String) {
    AUTO("自动", "跟随设备重力感应自动旋转"),
    LANDSCAPE("横屏", "强制横屏，适合平板/车机"),
    PORTRAIT("竖屏", "强制竖屏，适合手机"),
}

/** 主题模式：日间 / 夜间 / 跟随系统 */
enum class ThemeMode(val title: String, val desc: String) {
    DAY("日间", "始终使用浅色主题"),
    NIGHT("夜间", "始终使用深色主题"),
    SYSTEM("跟随系统", "随系统深色模式自动切换"),
}

/** 应用设置 */
data class AppSettings(
    val apkOrganizeMode: ApkOrganizeMode = ApkOrganizeMode.MOVE,
    val screenOrientation: ScreenOrientation = ScreenOrientation.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val APK_MODE = stringPreferencesKey("apk_organize_mode")
        val SCREEN_ORIENTATION = stringPreferencesKey("screen_orientation")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { /* DataStore 文件损坏时返回默认设置，避免崩溃 */ emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            AppSettings(
                apkOrganizeMode = runCatching {
                    ApkOrganizeMode.valueOf(p[Keys.APK_MODE] ?: ApkOrganizeMode.MOVE.name)
                }.getOrDefault(ApkOrganizeMode.MOVE),
                screenOrientation = runCatching {
                    ScreenOrientation.valueOf(p[Keys.SCREEN_ORIENTATION] ?: ScreenOrientation.AUTO.name)
                }.getOrDefault(ScreenOrientation.AUTO),
                themeMode = runCatching {
                    ThemeMode.valueOf(p[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
                }.getOrDefault(ThemeMode.SYSTEM),
            )
        }

    suspend fun setApkOrganizeMode(v: ApkOrganizeMode) =
        context.dataStore.edit { it[Keys.APK_MODE] = v.name }

    suspend fun setScreenOrientation(v: ScreenOrientation) =
        context.dataStore.edit { it[Keys.SCREEN_ORIENTATION] = v.name }

    suspend fun setThemeMode(v: ThemeMode) =
        context.dataStore.edit { it[Keys.THEME_MODE] = v.name }
}
