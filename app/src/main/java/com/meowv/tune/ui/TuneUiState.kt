package com.meowv.tune.ui

import androidx.annotation.StringRes
import com.meowv.tune.R

enum class TunePage(@StringRes val label: Int) {
    Radio(R.string.menu_radio), Music(R.string.menu_music), Settings(R.string.menu_settings)
}

internal fun isCarWindow(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 600 && heightDp >= 360 &&
        (widthDp >= 840 && widthDp > heightDp || widthDp / heightDp >= 1.35f)

internal fun isNowPlayingWide(widthDp: Float, heightDp: Float): Boolean =
    isCarWindow(widthDp, heightDp)

internal fun directoryColumnCount(isCar: Boolean, widthDp: Float, heightDp: Float): Int =
    if (isCar && widthDp >= 660f && heightDp >= 280f) 2 else 1
