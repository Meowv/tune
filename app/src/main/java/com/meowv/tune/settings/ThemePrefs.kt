package com.meowv.tune.settings

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.meowv.tune.domain.ThemeMode

private const val THEME_KEY = "mode"

internal val ThemeMode.storageValue: String
    get() = when (this) {
        ThemeMode.FOLLOW_SYSTEM -> "system"
        ThemeMode.LIGHT -> "light"
        ThemeMode.DARK -> "dark"
    }

/** 从同步缓存读取启动首帧主题，DataStore 仍保存用户选择。 */
internal class ThemePrefs(private val prefs: SharedPreferences) {
    fun read(): ThemeMode {
        val storedMode = prefs.getString(THEME_KEY, null)
        return ThemeMode.entries.firstOrNull { it.storageValue == storedMode } ?: ThemeMode.FOLLOW_SYSTEM
    }

    fun write(mode: ThemeMode) {
        prefs.edit().putString(THEME_KEY, mode.storageValue).apply()
    }
}

internal fun themePrefs(context: Context): ThemePrefs = ThemePrefs(
    context.applicationContext.getSharedPreferences("tune_theme", Context.MODE_PRIVATE)
)

internal fun applyApplicationNightMode(context: Context, mode: ThemeMode) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val nightMode = when (mode) {
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        // 对应用单独设置时，AUTO 会清除夜间模式覆盖。
        ThemeMode.FOLLOW_SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    }
    context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightMode)
}
