package com.meowv.tune.ui

import androidx.annotation.StringRes
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.PlayerUiState

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


internal fun carNavigationWidth(widthDp: Float, heightDp: Float): Float =
    if (widthDp < 740f || heightDp < 400f) (widthDp * .16f).coerceIn(104f, 120f)
    else (widthDp * .15f).coerceIn(120f, 156f)

internal fun pendingPlaybackCommand(state: PlayerUiState): PlayerCommand? = when {
    state.phase !in CardAutoplayPhases || state.phase == PlaybackPhase.PLAYING -> null
    state.source == PlaybackSource.MUSIC -> PlayerCommand.CancelMusic
    state.phase == PlaybackPhase.BUFFERING -> PlayerCommand.TogglePause
    else -> PlayerCommand.CancelPending
}
