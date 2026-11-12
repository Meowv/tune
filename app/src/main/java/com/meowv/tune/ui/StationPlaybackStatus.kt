package com.meowv.tune.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.ui.theme.TuneColors
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

internal enum class StationPlaybackVisual {
    None, Connecting, Buffering, Retrying, Playing, Paused, Error, Disconnected
}

internal fun stationPlaybackVisual(
    stationId: UUID,
    playback: PlayerUiState,
    connected: Boolean
): StationPlaybackVisual {
    if (playback.source != PlaybackSource.RADIO || playback.station?.uuid != stationId ||
        playback.phase == PlaybackPhase.IDLE
    ) return StationPlaybackVisual.None
    if (!connected) return StationPlaybackVisual.Disconnected
    return when (playback.phase) {
        PlaybackPhase.IDLE -> StationPlaybackVisual.None
        PlaybackPhase.RESOLVING -> StationPlaybackVisual.Connecting
        PlaybackPhase.BUFFERING -> StationPlaybackVisual.Buffering
        PlaybackPhase.RETRYING -> StationPlaybackVisual.Retrying
        PlaybackPhase.PLAYING -> StationPlaybackVisual.Playing
        PlaybackPhase.PAUSED -> StationPlaybackVisual.Paused
        PlaybackPhase.ERROR -> StationPlaybackVisual.Error
    }
}

@Composable
internal fun StationPlaybackStatus(
    visual: StationPlaybackVisual,
    isCar: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motionEnabled = rememberMotionEnabled()
    val animate = animationsEnabled && motionEnabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val label = when (visual) {
        StationPlaybackVisual.None -> ""
        StationPlaybackVisual.Connecting -> stringResource(R.string.playback_resolving)
        StationPlaybackVisual.Buffering -> stringResource(R.string.playback_buffering)
        StationPlaybackVisual.Retrying -> stringResource(R.string.playback_retrying)
        StationPlaybackVisual.Playing -> stringResource(R.string.playback_playing)
        StationPlaybackVisual.Paused -> stringResource(R.string.playback_paused)
        StationPlaybackVisual.Error -> stringResource(R.string.playback_failed)
        StationPlaybackVisual.Disconnected -> stringResource(R.string.playback_service_disconnected)
    }
    val tint = when (visual) {
        StationPlaybackVisual.Error -> TuneColors.Error
        StationPlaybackVisual.Paused, StationPlaybackVisual.Disconnected -> TuneColors.Muted
        else -> TuneColors.Primary
    }
    Box(
        modifier.semantics(mergeDescendants = true) { stateDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (visual != StationPlaybackVisual.None) {
            val glyphHeight = if (isCar) 28.dp else 22.dp
            Box(Modifier.fillMaxWidth().height(glyphHeight), contentAlignment = Alignment.Center) {
                when (visual) {
                    StationPlaybackVisual.Playing, StationPlaybackVisual.Paused -> StationPlaybackWaveform(
                        playing = visual == StationPlaybackVisual.Playing,
                        animating = animate && visual == StationPlaybackVisual.Playing,
                        modifier = Modifier.fillMaxWidth(.5f).fillMaxHeight()
                            .testTag("station_status_waveform")
                    )
                    StationPlaybackVisual.Connecting, StationPlaybackVisual.Buffering,
                    StationPlaybackVisual.Retrying -> {
                        if (animate) {
                            CircularProgressIndicator(Modifier.size(glyphHeight), color = tint, strokeWidth = 2.dp)
                        } else {
                            CircularProgressIndicator(
                                progress = { .7f }, modifier = Modifier.size(glyphHeight),
                                color = tint, strokeWidth = 2.dp
                            )
                        }
                    }
                    else -> TuneIcon(R.drawable.ic_ui_info, Modifier.size(glyphHeight), tint)
                }
            }
        }
    }
}

@Composable
private fun StationPlaybackWaveform(playing: Boolean, animating: Boolean, modifier: Modifier) {
    val phase = remember { Animatable(0f) }
    // 离屏、暂停或进入后台后取消循环，保留最后相位，使暂停时的收拢过渡连续。
    LaunchedEffect(animating) {
        if (animating) {
            phase.snapTo(0f)
            phase.animateTo(
                (PI * 2).toFloat(),
                infiniteRepeatable(tween(1_600, easing = LinearEasing))
            )
        }
    }
    val amplitude = animateFloatAsState(if (playing) 1f else .18f, tween(180), label = "station_wave_amplitude")
    val tint = if (playing) TuneColors.Primary else TuneColors.Muted
    Canvas(modifier) {
        val count = 16
        val step = size.width / count
        val width = step * .45f
        // 动画值只在绘制阶段读取，不触发卡片和 Pager 的逐帧重组。
        val time = phase.value
        val strength = amplitude.value
        repeat(count) { index ->
            val envelope = .45f + .55f * sin(PI * (index + .5f) / count).toFloat()
            val level = .25f + .75f * (.5f + .5f * sin(time + index * .85f))
            val height = (size.height * envelope * level * strength).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = tint,
                topLeft = Offset(step * index + (step - width) / 2, (size.height - height) / 2),
                size = Size(width, height), cornerRadius = CornerRadius(width / 2)
            )
        }
    }
}
