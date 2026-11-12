package com.meowv.tune.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.ui.theme.TuneColors

internal enum class CircularPlayerVisual { Default, Loading, Playing, Paused, Error, Disconnected }

internal fun circularPlayerVisual(state: PlayerUiState, connected: Boolean): CircularPlayerVisual = when {
    state.station == null || state.phase == PlaybackPhase.IDLE -> CircularPlayerVisual.Default
    !connected -> CircularPlayerVisual.Disconnected
    state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.RETRYING) -> CircularPlayerVisual.Loading
    state.phase == PlaybackPhase.PLAYING -> CircularPlayerVisual.Playing
    state.phase == PlaybackPhase.PAUSED -> CircularPlayerVisual.Paused
    else -> CircularPlayerVisual.Error
}

@Stable
internal class PlayerRotationState(stationKey: String = "", angle: Float = 0f) {
    var stationKey by mutableStateOf(stationKey)
        private set
    var angle by mutableFloatStateOf(angle)
        private set

    fun select(key: String) {
        if (key != stationKey) {
            stationKey = key
            angle = 0f
        }
    }

    fun updateAngle(value: Float) { angle = value % 360f }

    companion object {
        val Saver = listSaver<PlayerRotationState, Any>(
            save = { state -> Snapshot.withoutReadObservation { listOf(state.stationKey, state.angle) } },
            restore = { PlayerRotationState(it[0] as String, it[1] as Float) }
        )
    }
}

@Composable
internal fun CircularPlayerEntry(
    state: PlayerUiState,
    connected: Boolean,
    rotation: PlayerRotationState,
    isCar: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = circularPlayerVisual(state, connected)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motion = rememberMotionEnabled()
    val animate = enabled && motion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val rotating = animate && visual == CircularPlayerVisual.Playing
    val stationKey = if (visual == CircularPlayerVisual.Default) "" else state.station?.uuid.toString()
    val animation = remember(rotation) { Snapshot.withoutReadObservation { Animatable(rotation.angle) } }
    LaunchedEffect(stationKey, rotating) {
        rotation.select(stationKey)
        animation.snapTo(rotation.angle)
        if (rotating) {
            animation.animateTo(
                rotation.angle + 360f,
                infiniteRepeatable(tween(20_000, easing = LinearEasing))
            ) { rotation.updateAngle(value) }
        }
    }
    val label = stringResource(when (visual) {
        CircularPlayerVisual.Default -> R.string.player_unselected
        CircularPlayerVisual.Loading -> when (state.phase) {
            PlaybackPhase.RESOLVING -> R.string.playback_resolving
            PlaybackPhase.RETRYING -> R.string.playback_retrying
            else -> R.string.playback_buffering
        }
        CircularPlayerVisual.Playing -> R.string.playback_playing
        CircularPlayerVisual.Paused -> R.string.playback_paused
        CircularPlayerVisual.Error -> R.string.playback_failed
        CircularPlayerVisual.Disconnected -> R.string.playback_service_disconnected
    })
    val openLabel = stringResource(R.string.now_playing_open)
    var focused by remember { mutableStateOf(false) }
    val diameter = if (isCar) 64.dp else 56.dp
    val accent = when (visual) {
        CircularPlayerVisual.Error -> TuneColors.Error
        CircularPlayerVisual.Default, CircularPlayerVisual.Disconnected -> TuneColors.Muted
        else -> TuneColors.Primary
    }
    Box(
        modifier.size(diameter).onFocusChanged { focused = it.isFocused }
            .clip(CircleShape).background(TuneColors.Raised)
            .border(if (focused) 2.dp else 1.dp, if (focused) TuneColors.Primary else accent.copy(alpha = .5f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .testTag("circular_player_entry")
            .semantics {
                contentDescription = openLabel
                stateDescription = label
            },
        contentAlignment = Alignment.Center
    ) {
        val station = state.station.takeUnless { visual == CircularPlayerVisual.Default }
        Box(
            Modifier.fillMaxSize().padding(4.dp).clip(CircleShape)
                .graphicsLayer { rotationZ = if (rotation.stationKey == stationKey) rotation.angle else 0f },
            contentAlignment = Alignment.Center
        ) {
            val url = station?.iconUrl
            var artworkFailed by remember(station?.uuid, url) { mutableStateOf(false) }
            if (url.isNullOrBlank() || artworkFailed) {
                BrandLogo(Modifier.fillMaxSize().testTag("circular_player_logo"))
            } else {
                key(station.uuid, url) {
                    val fallback = painterResource(R.drawable.ic_meowvtune_logo)
                    AsyncImage(
                        model = url, contentDescription = null,
                        modifier = Modifier.fillMaxSize().testTag("circular_player_artwork"),
                        placeholder = fallback, error = fallback, fallback = fallback,
                        onError = { artworkFailed = true },
                        contentScale = BadgeContentScale
                    )
                }
            }
        }
        if (visual == CircularPlayerVisual.Loading) {
            if (animate) {
                CircularProgressIndicator(Modifier.fillMaxSize().padding(1.dp), color = TuneColors.Primary, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { .7f }, modifier = Modifier.fillMaxSize().padding(1.dp), color = TuneColors.Primary, strokeWidth = 2.dp)
            }
        }
        if (visual in setOf(CircularPlayerVisual.Playing, CircularPlayerVisual.Paused, CircularPlayerVisual.Error, CircularPlayerVisual.Disconnected)) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp)
                    .background(TuneColors.Surface, CircleShape).padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                when (visual) {
                    CircularPlayerVisual.Playing, CircularPlayerVisual.Paused -> Canvas(Modifier.fillMaxSize()) {
                        val heights = if (visual == CircularPlayerVisual.Playing) listOf(.45f, 1f, .65f) else listOf(.85f, .85f)
                        val step = size.width / heights.size
                        heights.forEachIndexed { index, fraction ->
                            val height = size.height * fraction
                            drawRoundRect(accent, Offset(index * step + step * .2f, (size.height - height) / 2), Size(step * .6f, height), CornerRadius(1.dp.toPx()))
                        }
                    }
                    CircularPlayerVisual.Disconnected -> TuneIcon(R.drawable.ic_close, Modifier.fillMaxSize(), accent)
                    else -> TuneIcon(R.drawable.ic_ui_info, Modifier.fillMaxSize(), accent)
                }
            }
        }
    }
}
