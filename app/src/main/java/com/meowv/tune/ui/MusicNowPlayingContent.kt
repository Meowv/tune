package com.meowv.tune.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.music.MusicLyrics
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneMotion
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTypography
import kotlin.math.abs

@Composable
internal fun MusicNowPlayingDetails(
    state: PlayerUiState,
    isCar: Boolean,
    connected: Boolean,
    compact: Boolean,
    onSeek: (Long) -> Unit,
    title: @Composable () -> Unit
) {
    val artists = state.music?.artists.orEmpty().filter { it.isNotBlank() }.joinToString(" / ")
    val album = state.music?.album?.takeIf { isCar && it.isNotBlank() }
    val subtitle = listOfNotNull(artists.takeIf { it.isNotEmpty() }, album).joinToString(" · ")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 先给进度条留出触控空间，标题和歌词共用剩余高度。
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).clipToBounds(),
            horizontalAlignment = if (isCar) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline)
        ) {
            title()
            if (subtitle.isNotEmpty() && (!compact || state.musicLyrics.isEmpty())) {
                Text(
                    subtitle,
                    color = TuneColors.Muted,
                    style = TuneTypography.secondary(isCar),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.musicLyrics.isNotEmpty()) {
                key(state.music?.id) { MusicLyricPreview(state, isCar, compact) }
            }
        }
        MusicSeek(state, connected, isCar, inlineTimes = compact, onSeek)
    }
}

@Composable
private fun MusicLyricPreview(state: PlayerUiState, isCar: Boolean, compact: Boolean) {
    val lines = state.musicLyrics
    val index = MusicLyrics.activeLine(lines, state.positionMs ?: 0)
    val animationMillis = if (rememberMotionEnabled()) TuneMotion.ColorChange else 0
    AnimatedContent(
        targetState = index,
        modifier = Modifier.fillMaxWidth().clipToBounds().testTag("music_lyrics"),
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            slideInVertically(tween(animationMillis)) { it * direction } togetherWith
                slideOutVertically(tween(animationMillis)) { -it * direction }
        },
        label = "music_lyric_line"
    ) { lineIndex ->
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            lines.getOrNull(lineIndex)?.let {
                Text(
                    it.text,
                    color = TuneColors.Text,
                    style = TuneTypography.rowTitle(isCar && !compact),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (!compact || lineIndex < 0) {
                lines.getOrNull(lineIndex + 1)?.let {
                    Text(
                        it.text,
                        color = TuneColors.Muted,
                        style = TuneTypography.secondary(isCar),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSeek(
    state: PlayerUiState,
    connected: Boolean,
    isCar: Boolean,
    inlineTimes: Boolean,
    onSeek: (Long) -> Unit
) {
    val duration = state.durationMs?.takeIf { it > 0 } ?: return
    var drag by remember(state.music?.id, duration, state.canSeek) { mutableStateOf<Float?>(null) }
    val position = (drag?.toLong() ?: state.positionMs ?: 0).coerceIn(0, duration)
    val progress = position.toFloat() / duration
    val description = stringResource(R.string.music_seek_description,
        position / 60_000, position / 1000 % 60, duration / 60_000, duration / 1000 % 60)
    val seekTrack: @Composable (Modifier) -> Unit = { modifier ->
        val sliderModifier = modifier.semantics { stateDescription = description }
        if (state.canSeek) {
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Slider(
                value = position.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { onSeek(it.toLong()) }
                    drag = null
                },
                valueRange = 0f..duration.toFloat(),
                enabled = connected,
                modifier = sliderModifier.heightIn(min = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
                    .ignoreVerticalSeekDrag(connected).testTag("music_seek"),
                interactionSource = interaction,
                thumb = {
                    Box(
                        Modifier.size(20.dp)
                            .background(
                                if (connected) TuneColors.Primary.copy(alpha = .16f) else Color.Transparent,
                                CircleShape
                            ).then(if (focused) Modifier.border(2.dp, TuneColors.PrimaryLight, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(if (drag != null) 14.dp else 11.dp)
                                .background(if (connected) TuneColors.PrimaryLight else TuneColors.Muted, CircleShape)
                        )
                    }
                },
                track = { MusicProgressTrack(progress, connected, Modifier.fillMaxWidth()) }
            )
        } else {
            MusicProgressTrack(
                progress,
                connected,
                sliderModifier.padding(vertical = TuneSpacing.Medium)
                    .progressSemantics(progress).testTag("music_progress")
            )
        }
    }
    val layout = Modifier.fillMaxWidth().padding(horizontal = TuneSpacing.Small)
    if (inlineTimes) {
        Row(
            layout, horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaybackTime(position, TuneColors.Text, isCar)
            seekTrack(Modifier.weight(1f))
            PlaybackTime(duration, TuneColors.Muted, isCar)
        }
    } else {
        Column(layout) {
            seekTrack(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PlaybackTime(position, TuneColors.Text, isCar)
                PlaybackTime(duration, TuneColors.Muted, isCar)
            }
        }
    }
}

@Composable
private fun MusicProgressTrack(progress: Float, connected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.height(4.dp)) {
        val radius = CornerRadius(size.height / 2f)
        val filledWidth = size.width * progress
        drawRoundRect(TuneColors.Border, cornerRadius = radius)
        if (filledWidth > 0f) {
            val filledStart = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - filledWidth
            val stops = if (connected) {
                listOf(TuneColors.Accent, TuneColors.PrimaryLight)
            } else {
                listOf(TuneColors.Muted, TuneColors.Muted)
            }
            drawRoundRect(
                brush = Brush.horizontalGradient(stops, startX = filledStart, endX = filledStart + filledWidth),
                topLeft = Offset(filledStart, 0f),
                size = Size(filledWidth, size.height),
                cornerRadius = radius
            )
        }
    }
}

private fun Modifier.ignoreVerticalSeekDrag(enabled: Boolean): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var verticalDrag: Boolean? = null
            do {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    ?: break
                if (verticalDrag == null) {
                    val offset = change.position - down.position
                    if (maxOf(abs(offset.x), abs(offset.y)) > viewConfiguration.touchSlop) {
                        verticalDrag = abs(offset.y) > abs(offset.x)
                    }
                }
                // Slider 会把轨道内未消费的纵向拖动识别成点击。
                if (verticalDrag == true) change.consume()
            } while (change.pressed)
        }
    }
}

@Composable
private fun PlaybackTime(positionMs: Long, color: Color, isCar: Boolean) {
    Text(
        musicTime(positionMs),
        color = color,
        style = TuneTypography.meta(isCar).copy(fontFeatureSettings = "'tnum' 1"),
        maxLines = 1
    )
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
