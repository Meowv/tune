package com.meowv.tune.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.Station
import com.meowv.tune.ui.theme.TuneBrushes
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneMotion
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneTypography
import java.util.Locale

internal enum class CircularPlayerVisual { Default, Loading, Playing, Paused, Error, Disconnected }

internal val PhoneMiniPlayerHeight = 56.dp
internal val CarMiniPlayerHeight = 77.dp

internal fun circularPlayerVisual(state: PlayerUiState, connected: Boolean): CircularPlayerVisual = when {
    state.station == null && state.music == null && state.source != PlaybackSource.MUSIC || state.phase == PlaybackPhase.IDLE -> CircularPlayerVisual.Default
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
internal fun CompactMediaBar(
    state: PlayerUiState,
    station: Station?,
    connected: Boolean,
    rotation: PlayerRotationState,
    isCar: Boolean,
    enabled: Boolean,
    animationMillis: Int,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {}
) {
    val music = state.source == PlaybackSource.MUSIC
    val title = if (music) state.music?.title else station?.name
    val visual = circularPlayerVisual(state, connected)
    val subtitle = when {
        !isCar -> null
        music -> state.music?.artists?.joinToString(" / ")
        else -> station?.countryCode?.displayName(Locale.SIMPLIFIED_CHINESE)
    }
    val shape = if (isCar) RoundedCornerShape(0.dp) else TuneShapes.Navigation
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (isCar) CarMiniPlayerHeight else PhoneMiniPlayerHeight)
            .clip(shape).background(if (isCar) TuneBrushes.CarPlayer else TuneBrushes.PhonePlayer)
            .border(1.dp, TuneColors.PrimaryLight.copy(alpha = .14f), shape)
            .padding(horizontal = if (isCar) 18.dp else 7.dp, vertical = 4.dp)
            .testTag("mini_player_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularPlayerEntry(
            state, connected, rotation, isCar, enabled, onOpen,
            onNext = onNext,
            onPrevious = onPrevious,
            modifier = Modifier.weight(1f),
            title = title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.player_idle),
            subtitle = subtitle,
            artworkUrl = if (music) state.music?.coverUrl else station?.iconUrl
        )
        MiniPlayerToggle(
            icon = when (visual) {
                CircularPlayerVisual.Playing -> R.drawable.ic_music_pause
                CircularPlayerVisual.Error -> R.drawable.ic_refresh
                else -> R.drawable.ic_radio_play
            },
            description = stringResource(when (visual) {
                CircularPlayerVisual.Playing -> R.string.now_playing_pause_accessibility
                CircularPlayerVisual.Error -> R.string.directory_retry
                CircularPlayerVisual.Loading -> R.string.playback_buffering
                else -> R.string.now_playing_resume_accessibility
            }),
            isCar = isCar,
            animationMillis = animationMillis,
            enabled = enabled && connected && visual != CircularPlayerVisual.Loading &&
                (if (music) state.music != null else station != null),
            onClick = onToggle
        )
    }
}

@Composable
private fun MiniPlayerToggle(
    icon: Int,
    description: String,
    isCar: Boolean,
    animationMillis: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (isCar) 16.dp else 13.dp)
    Box(
        Modifier.size(if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
            .controlFeedback(interaction, shape, enabled, if (animationMillis > 0) TuneMotion.Press else 0)
            .clickable(
                interactionSource = interaction, indication = null,
                role = Role.Button, enabled = enabled, onClick = onClick
            )
            .testTag("mini_player_toggle")
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(if (isCar) 56.dp else 44.dp)
                .background(TuneColors.Primary.copy(alpha = if (enabled) .19f else .07f), shape)
                .border(1.dp, TuneColors.PrimaryLight.copy(alpha = .15f), shape),
            contentAlignment = Alignment.Center
        ) {
            TuneIcon(
                icon, Modifier.size(if (isCar) TuneSizes.IconLg else TuneSizes.IconMd),
                TuneColors.PrimaryLight.copy(alpha = if (enabled) 1f else TuneColors.DisabledAlpha)
            )
        }
    }
}

@Composable
private fun CircularPlayerEntry(
    state: PlayerUiState,
    connected: Boolean,
    rotation: PlayerRotationState,
    isCar: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier,
    title: String,
    subtitle: String?,
    artworkUrl: String?
) {
    val visual = circularPlayerVisual(state, connected)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motion = rememberMotionEnabled()
    val animate = enabled && motion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val rotating = animate && visual == CircularPlayerVisual.Playing
    val stationKey = if (visual == CircularPlayerVisual.Default) "" else state.music?.let { "music:${it.id}" } ?: state.station?.uuid.toString()
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
    val error = if (state.source == PlaybackSource.MUSIC && connected)
        musicPlaybackErrorText(state) else null
    val status = error ?: label
    val openLabel = stringResource(R.string.now_playing_open)
    val currentTitle = state.music?.title
    val entryState = if (visual != CircularPlayerVisual.Default && !currentTitle.isNullOrBlank()) {
        stringResource(R.string.now_playing_current_state, currentTitle, status)
    } else status
    val canNext = enabled && connected && state.canNext
    val canPrevious = enabled && connected && state.canPrevious
    val currentNext by rememberUpdatedState(onNext)
    val currentPrevious by rememberUpdatedState(onPrevious)
    val swipe = if (canNext || canPrevious) Modifier.pointerInput(stationKey, canNext, canPrevious) {
        val threshold = 40.dp.toPx()
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onHorizontalDrag = { _, delta -> distance += delta },
            onDragEnd = {
                when {
                    distance <= -threshold && canNext -> currentNext()
                    distance >= threshold && canPrevious -> currentPrevious()
                }
            }
        )
    } else Modifier
    val interaction = remember { MutableInteractionSource() }
    val coverSize = if (isCar) 48.dp else 42.dp
    val coverShape = RoundedCornerShape(if (isCar) 13.dp else 11.dp)
    val accent = when (visual) {
        CircularPlayerVisual.Error -> TuneColors.Error
        CircularPlayerVisual.Default, CircularPlayerVisual.Disconnected -> TuneColors.Muted
        else -> TuneColors.Primary
    }
    Row(
        modifier.heightIn(min = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
            .controlFeedback(interaction, TuneShapes.Navigation, enabled)
            .then(swipe)
            .clickable(
                enabled = enabled, role = Role.Button, interactionSource = interaction,
                indication = null, onClick = onOpen
            )
            .testTag("circular_player_entry")
            .semantics {
                contentDescription = openLabel
                stateDescription = entryState
            }.padding(start = 2.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(coverSize).clip(coverShape).background(TuneColors.Raised)
                .border(1.dp, accent.copy(alpha = .24f), coverShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val url = artworkUrl
                var artworkFailed by remember(stationKey, url) { mutableStateOf(false) }
                if (url.isNullOrBlank() || artworkFailed) {
                    BrandLogo(
                        Modifier.fillMaxSize().padding(7.dp)
                            .graphicsLayer { rotationZ = if (rotation.stationKey == stationKey) rotation.angle else 0f }
                            .testTag("circular_player_logo")
                    )
                } else {
                    key(stationKey, url) {
                        val fallback = painterResource(R.drawable.ic_meowvtune_logo)
                        AsyncImage(
                            model = url, contentDescription = null,
                            modifier = Modifier.fillMaxSize().testTag("circular_player_artwork"),
                            placeholder = fallback, error = fallback, fallback = fallback,
                            onError = { artworkFailed = true },
                            contentScale = if (state.source == PlaybackSource.MUSIC) ContentScale.Crop else BadgeContentScale
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
        Column(Modifier.weight(1f)) {
            Text(
                title, color = TuneColors.Text, style = TuneTypography.control(isCar),
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val detail = error ?: subtitle
            if (!detail.isNullOrBlank()) {
                Text(
                    detail, color = if (error != null) TuneColors.Error else TuneColors.Muted,
                    style = TuneTypography.meta(isCar),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
