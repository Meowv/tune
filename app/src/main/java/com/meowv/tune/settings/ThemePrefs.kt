package com.meowv.tune.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.meowv.tune.domain.ThemeMode

private const val THEME_KEY = "mode"

internal val ThemeMode.storageValue: String
    get() = when (this) {
        ThemeMode.FOLLOW_SYSTEM -> "system"
        ThemeMode.LIGHT -> "light"
        ThemeMode.DARK -> "dark"
    }

internal interface ThemeStore {
    fun read(): String?
    fun write(value: String)
}

internal class ThemePrefs(private val store: ThemeStore) {
    fun read(): ThemeMode = ThemeMode.entries.firstOrNull { it.storageValue == store.read() }
        ?: ThemeMode.FOLLOW_SYSTEM

    fun write(mode: ThemeMode) = store.write(mode.storageValue)
}

internal fun themePrefs(context: Context): ThemePrefs {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("tune_theme", Context.MODE_PRIVATE)
    return ThemePrefs(object : ThemeStore {
        override fun read(): String? = prefs.getString(THEME_KEY, null)
        override fun write(value: String) {
            prefs.edit().putString(THEME_KEY, value).apply()
        }
    })
}

/** DataStore 是运行时真相，这里只给启动首帧提供同步可读的模式。 */
internal fun resolveThemeIsDark(context: Context, prefs: ThemePrefs = themePrefs(context)): Boolean {
    val mode = prefs.read()
    if (mode == ThemeMode.FOLLOW_SYSTEM) {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
    return mode == ThemeMode.DARK
}
