package com.meowv.tune.ui

import androidx.annotation.StringRes
import com.meowv.tune.R
import com.meowv.tune.domain.LayoutMode

enum class TunePage(@StringRes val label: Int) {
    Radio(R.string.menu_radio), Music(R.string.menu_music), Settings(R.string.menu_settings)
}

internal fun isCarWindow(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 600 && heightDp >= 360 &&
        (widthDp >= 840 && widthDp > heightDp || widthDp / heightDp >= 1.35f)

/**
 * 布局偏好只影响 Shell，不触碰播放。
 * 强制车机时若窗口达不到车机最小约束（600×360dp），安全回退到手机 Shell。
 */
internal fun resolveShell(layout: LayoutMode, widthDp: Float, heightDp: Float): Boolean = when (layout) {
    LayoutMode.AUTO -> isCarWindow(widthDp, heightDp)
    LayoutMode.PHONE -> false
    LayoutMode.CAR -> widthDp >= 600 && heightDp >= 360
}
