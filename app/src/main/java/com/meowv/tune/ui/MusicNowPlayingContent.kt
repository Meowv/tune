package com.meowv.tune.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.music.MusicLyrics
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun MusicNowPlayingContent(
    isCar: Boolean,
    state: PlayerUiState,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 560.dp && (isCar || maxWidth > maxHeight)
        val height = maxHeight
        val coverSize = if (wide) {
            minOf(maxWidth * .36f, height * .60f, if (isCar) 380.dp else 300.dp)
        } else {
            minOf(maxWidth * .72f, height * .45f, 280.dp)
        }
        val artwork: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)) {
                if (coverSize >= 32.dp) {
                    MusicCover(state.music, Modifier.size(coverSize).testTag("music_artwork"), TuneShapes.Player)
                }
                Text(state.music?.title ?: stringResource(R.string.menu_music), color = TuneColors.Text,
                    style = TuneTextStyle, fontSize = if (isCar) 28.sp else 24.sp,
                    lineHeight = if (isCar) 34.sp else 30.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag("music_title"))
                state.music?.artists?.takeIf { it.isNotEmpty() }?.let { artists ->
                    Text(artists.joinToString(" / "), color = TuneColors.Muted, style = TuneTextStyle,
                        fontSize = if (isCar) 17.sp else 14.sp, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize().focusGroup(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isCar) 44.dp else 28.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, content = { artwork() })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    MusicPlaybackControls(isCar, state, connected, onCommand)
                }
            }
        } else {
            Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = height).padding(vertical = TuneSpacing.Small).focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TuneSpacing.Medium, Alignment.CenterVertically)) {
                artwork()
                MusicPlaybackControls(isCar, state, connected, onCommand)
            }
        }
    }
}

@Composable
private fun MusicPlaybackControls(
    isCar: Boolean,
    state: PlayerUiState,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit
) {
    val animationMillis = if (rememberMotionEnabled()) 220 else 0
    val buffering = state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.RETRYING)
    val failure = musicPlaybackErrorText(state)
    Column(Modifier.widthIn(max = if (isCar) 560.dp else 430.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)) {
        if (state.musicLyrics.isNotEmpty()) {
            val index = MusicLyrics.activeLine(state.musicLyrics, state.positionMs ?: 0)
            Column(Modifier.fillMaxWidth().testTag("music_lyrics"),
                verticalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline),
                horizontalAlignment = Alignment.CenterHorizontally) {
                state.musicLyrics.getOrNull(index)?.let {
                    Text(it.text, color = TuneColors.Text, style = TuneTextStyle, fontSize = if (isCar) 22.sp else 17.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                state.musicLyrics.getOrNull(index + 1)?.let {
                    Text(it.text, color = TuneColors.Muted, style = TuneTextStyle, fontSize = if (isCar) 17.sp else 14.sp,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        MusicSeek(state, connected, onCommand)
        Row(
            Modifier.widthIn(max = if (isCar) 430.dp else 350.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NowPlayingIconButton(R.drawable.ic_now_playing_previous, stringResource(R.string.music_previous),
                isCar, "music_previous", animationMillis, if (isCar) 34.dp else 30.dp,
                enabled = connected && state.canPrevious, onClick = { onCommand(PlayerCommand.Previous) })
            var focused by remember { mutableStateOf(false) }
            val toggleIcon = when {
                state.phase == PlaybackPhase.ERROR -> R.drawable.ic_refresh
                state.phase == PlaybackPhase.PLAYING -> R.drawable.ic_music_pause
                else -> R.drawable.ic_radio_play
            }
            val toggleLabel = stringResource(when {
                buffering -> R.string.action_cancel
                state.phase == PlaybackPhase.ERROR -> R.string.music_retry
                state.phase == PlaybackPhase.PLAYING -> R.string.action_pause
                else -> R.string.action_play
            })
            val status = playbackVisualLabel(playbackVisual(state.phase, connected))
            FilledIconButton(
                onClick = { onCommand(PlayerCommand.TogglePause) },
                enabled = connected,
                shape = TuneShapes.Station,
                modifier = Modifier.size(if (isCar) 80.dp else 64.dp)
                    .then(if (focused) Modifier.border(2.dp, TuneColors.PrimaryLight, TuneShapes.Station) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { if (status != null) stateDescription = status }
                    .testTag("music_toggle")
            ) {
                if (buffering) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(34.dp), color = TuneColors.Background,
                            strokeWidth = 2.dp, strokeCap = StrokeCap.Round)
                        Icon(painterResource(R.drawable.ic_close), toggleLabel, Modifier.size(TuneSizes.IconXs))
                    }
                } else {
                    Icon(painterResource(toggleIcon), toggleLabel, Modifier.size(if (isCar) 30.dp else 26.dp))
                }
            }
            NowPlayingIconButton(R.drawable.ic_now_playing_next, stringResource(R.string.music_next),
                isCar, "music_next", animationMillis, if (isCar) 34.dp else 30.dp,
                enabled = connected && state.canNext, onClick = { onCommand(PlayerCommand.Next) })
        }
        if (failure != null) {
            Text(failure, color = TuneColors.Error, style = TuneTextStyle, fontSize = if (isCar) 15.sp else 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun MusicSeek(state: PlayerUiState, connected: Boolean, onCommand: (PlayerCommand) -> Unit) {
    val duration = state.durationMs?.takeIf { it > 0 } ?: return
    var drag by remember(state.music?.id, duration, state.canSeek) { mutableStateOf<Float?>(null) }
    val position = (drag?.toLong() ?: state.positionMs ?: 0).coerceIn(0, duration)
    val description = stringResource(R.string.music_seek_description,
        position / 60_000, position / 1000 % 60, duration / 60_000, duration / 1000 % 60)
    val sliderModifier = Modifier.fillMaxWidth().semantics { stateDescription = description }
    Column(Modifier.fillMaxWidth().padding(horizontal = TuneSpacing.Small)) {
        if (state.canSeek) {
            Slider(
                value = position.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { onCommand(PlayerCommand.Seek(it.toLong())) }; drag = null },
                valueRange = 0f..duration.toFloat(),
                enabled = connected,
                modifier = sliderModifier.testTag("music_seek")
            )
        } else {
            LinearProgressIndicator(progress = { position.toFloat() / duration },
                modifier = sliderModifier.padding(vertical = TuneSpacing.Medium))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(musicTime(position), color = TuneColors.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(musicTime(duration), color = TuneColors.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun musicPlaybackErrorText(state: PlayerUiState): String? =
    if (state.musicFailure != null || state.phase == PlaybackPhase.ERROR && state.error == null)
        stringResource(musicPlaybackFailureMessage(state.musicFailure))
    else playbackErrorText(state.error, state.statusCode)

internal fun musicPlaybackFailureMessage(failure: MusicFailure?) = when (failure) {
    MusicFailure.NOT_CONFIGURED -> R.string.music_not_configured
    MusicFailure.UNAUTHORIZED -> R.string.music_unauthorized
    MusicFailure.FORBIDDEN -> R.string.music_playback_forbidden
    MusicFailure.RATE_LIMITED -> R.string.music_rate_limited
    MusicFailure.SOURCE_TIMEOUT -> R.string.music_playback_timeout
    MusicFailure.SOURCE_UNAVAILABLE -> R.string.music_playback_source_unavailable
    MusicFailure.NO_PLAYABLE_MATCH -> R.string.music_empty
    MusicFailure.CONNECTION -> R.string.playback_connection
    MusicFailure.TLS -> R.string.playback_tls
    else -> R.string.music_failed
}

internal fun musicTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"
