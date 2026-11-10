package com.meowv.tune.ui

import android.media.AudioManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.Station
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneTextStyle
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
internal fun NowPlayingScreen(
    isCar: Boolean,
    connected: Boolean,
    state: PlayerUiState,
    station: Station,
    countryName: String?,
    randomRangeName: String?,
    shuffle: Boolean,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(AudioManager::class.java)
    }
    val motionEnabled = rememberMotionEnabled()
    val animationMillis = if (motionEnabled) 220 else 0
    val phase = state.phase
    val buffering = phase == PlaybackPhase.RESOLVING ||
        phase == PlaybackPhase.RETRYING || phase == PlaybackPhase.BUFFERING
    val controlsLocked = buffering
    val canChangeStation = connected && !controlsLocked
    val onNextCurrent by rememberUpdatedState(onNext)
    val onPreviousCurrent by rememberUpdatedState(onPrevious)

    Box(
        modifier.fillMaxSize().background(TuneColors.Background)
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        TuneColors.Accent.copy(alpha = .13f),
                        TuneColors.Surface.copy(alpha = .72f),
                        TuneColors.Background
                    )
                )
            )
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(
                if (isCar) WindowInsets.displayCutout else WindowInsets.safeDrawing
            )
        ) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val wide = isCar || isNowPlayingWide(availableWidth.value, availableHeight.value)
            val contentPadding = if (wide) {
                Modifier.padding(horizontal = if (isCar) 32.dp else 24.dp)
            } else {
                Modifier.padding(horizontal = 20.dp)
            }
            Column(Modifier.fillMaxSize().then(contentPadding)) {
                NowPlayingTopBar(
                    isCar = isCar,
                    animationMillis = animationMillis,
                    onCollapse = onCollapse
                )
                if (wide) {
                    WideNowPlayingContent(
                        isCar = isCar,
                        maxWidth = availableWidth,
                        maxHeight = availableHeight,
                        state = state,
                        station = station,
                        countryName = countryName,
                        randomRangeName = randomRangeName,
                        connected = connected,
                        buffering = buffering,
                        animationMillis = animationMillis,
                        canNext = connected && state.canNext && !controlsLocked,
                        canPrevious = connected && state.canPrevious && !controlsLocked,
                        shuffle = shuffle,
                        onToggle = onToggle,
                        onNext = { if (canChangeStation) onNextCurrent() },
                        onPrevious = { if (canChangeStation) onPreviousCurrent() },
                        onToggleShuffle = onToggleShuffle,
                        onVolume = {
                            audioManager?.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_SAME,
                                AudioManager.FLAG_SHOW_UI
                            )
                        },
                        onCollapse = onCollapse
                    )
                } else {
                    PhoneNowPlayingContent(
                        state = state,
                        station = station,
                        countryName = countryName,
                        randomRangeName = randomRangeName,
                        connected = connected,
                        buffering = buffering,
                        animationMillis = animationMillis,
                        canNext = connected && state.canNext && !controlsLocked,
                        canPrevious = connected && state.canPrevious && !controlsLocked,
                        shuffle = shuffle,
                        onToggle = onToggle,
                        onNext = { if (canChangeStation) onNextCurrent() },
                        onPrevious = { if (canChangeStation) onPreviousCurrent() },
                        onToggleShuffle = onToggleShuffle,
                        onVolume = {
                            audioManager?.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_SAME,
                                AudioManager.FLAG_SHOW_UI
                            )
                        },
                        onCollapse = onCollapse
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingTopBar(
    isCar: Boolean,
    animationMillis: Int,
    onCollapse: () -> Unit
) {
    val label = stringResource(R.string.now_playing_collapse)
    Row(
        Modifier.fillMaxWidth().height(if (isCar) 72.dp else 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NowPlayingCircleButton(
            icon = R.drawable.ic_ui_chevron_down,
            description = label,
            isCar = isCar,
            tag = "now_playing_collapse",
            animationMillis = animationMillis,
            onClick = onCollapse
        )
        Text(
            stringResource(R.string.now_playing_page_label),
            modifier = Modifier.weight(1f),
            color = TuneColors.Muted,
            fontSize = if (isCar) 16.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = TuneTextStyle
        )
        Spacer(Modifier.width(if (isCar) TuneSizes.TouchCar else TuneSizes.Touch))
    }
}

@Composable
private fun PhoneNowPlayingContent(
    state: PlayerUiState,
    station: Station,
    countryName: String?,
    randomRangeName: String?,
    connected: Boolean,
    buffering: Boolean,
    animationMillis: Int,
    canNext: Boolean,
    canPrevious: Boolean,
    shuffle: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onVolume: () -> Unit,
    onCollapse: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val artworkSize = minOf(maxWidth * .82f, maxHeight).coerceAtLeast(1.dp)
            NowPlayingArtworkGesture(
                station = station,
                canNext = canNext,
                canPrevious = canPrevious,
                animationMillis = animationMillis,
                modifier = Modifier.size(minOf(artworkSize, 320.dp)),
                onNext = onNext,
                onPrevious = onPrevious,
                onCollapse = onCollapse
            )
        }
        Spacer(Modifier.height(12.dp))
        StationHeading(
            station = station,
            countryName = countryName,
            playing = state.phase == PlaybackPhase.PLAYING,
            isCar = false,
            centered = true
        )
        Spacer(Modifier.height(12.dp))
        PlaybackControls(
            state = state,
            connected = connected,
            buffering = buffering,
            animationMillis = animationMillis,
            isCar = false,
            canNext = canNext,
            canPrevious = canPrevious,
            onToggle = onToggle,
            onNext = onNext,
            onPrevious = onPrevious
        )
        NowPlayingNotice(state, modifier = Modifier.heightIn(min = 28.dp))
        Spacer(Modifier.height(6.dp))
        NowPlayingTools(
            isCar = false,
            shuffle = shuffle,
            rangeName = randomRangeName,
            animationMillis = animationMillis,
            onToggleShuffle = onToggleShuffle,
            onVolume = onVolume,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun WideNowPlayingContent(
    isCar: Boolean,
    maxWidth: Dp,
    maxHeight: Dp,
    state: PlayerUiState,
    station: Station,
    countryName: String?,
    randomRangeName: String?,
    connected: Boolean,
    buffering: Boolean,
    animationMillis: Int,
    canNext: Boolean,
    canPrevious: Boolean,
    shuffle: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onVolume: () -> Unit,
    onCollapse: () -> Unit
) {
    val artworkSize = minOf(
        maxHeight - if (isCar) 128.dp else 104.dp,
        maxWidth * if (isCar) .34f else .4f,
        if (isCar) 420.dp else 340.dp
    ).coerceAtLeast(170.dp)
    Row(
        Modifier.fillMaxSize().focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (isCar) 48.dp else 32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1.12f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            NowPlayingArtworkGesture(
                station = station,
                canNext = canNext,
                canPrevious = canPrevious,
                animationMillis = animationMillis,
                modifier = Modifier.size(artworkSize),
                onNext = onNext,
                onPrevious = onPrevious,
                onCollapse = onCollapse
            )
        }
        Column(
            Modifier.weight(.88f).widthIn(max = if (isCar) 560.dp else 430.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            StationHeading(
                station = station,
                countryName = countryName,
                playing = state.phase == PlaybackPhase.PLAYING,
                isCar = isCar,
                centered = false
            )
            Spacer(Modifier.height(if (isCar) 24.dp else 18.dp))
            PlaybackControls(
                state = state,
                connected = connected,
                buffering = buffering,
                animationMillis = animationMillis,
                isCar = isCar,
                canNext = canNext,
                canPrevious = canPrevious,
                onToggle = onToggle,
                onNext = onNext,
                onPrevious = onPrevious
            )
            NowPlayingNotice(state, modifier = Modifier.heightIn(min = 30.dp))
            Spacer(Modifier.height(if (isCar) 18.dp else 10.dp))
            NowPlayingTools(
                isCar = isCar,
                shuffle = shuffle,
                rangeName = randomRangeName,
                animationMillis = animationMillis,
                onToggleShuffle = onToggleShuffle,
                onVolume = onVolume,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NowPlayingArtworkGesture(
    station: Station,
    canNext: Boolean,
    canPrevious: Boolean,
    animationMillis: Int,
    modifier: Modifier,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val velocity = remember { VelocityTracker() }
    val density = LocalDensity.current
    val commit = with(density) { 52.dp.toPx() }
    val fling = with(density) { 380.dp.toPx() }
    val closeDistance = with(density) { 72.dp.toPx() }
    val currentCanNext by rememberUpdatedState(canNext)
    val currentCanPrevious by rememberUpdatedState(canPrevious)
    val currentNext by rememberUpdatedState(onNext)
    val currentPrevious by rememberUpdatedState(onPrevious)
    val currentCollapse by rememberUpdatedState(onCollapse)
    val description = stringResource(R.string.now_playing_artwork, station.name)

    Box(
        modifier
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (abs(offset.value) / (size.width.coerceAtLeast(1f))) * .28f
            }
            .testTag("now_playing_artwork")
            .semantics { contentDescription = description }
            .pointerInput(station.uuid) {
                var totalX = 0f
                var totalY = 0f
                var horizontal = false
                var vertical = false
                detectDragGestures(
                    onDragStart = {
                        totalX = 0f
                        totalY = 0f
                        horizontal = false
                        vertical = false
                        velocity.resetTracking()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        totalX += amount.x
                        totalY += amount.y
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (!horizontal && !vertical && (abs(totalX) > 8f || abs(totalY) > 8f)) {
                            horizontal = abs(totalX) >= abs(totalY)
                            vertical = !horizontal
                        }
                        if (horizontal) {
                            val available = if (amount.x < 0f) currentCanNext else currentCanPrevious
                            val delta = if (available) amount.x else amount.x * .28f
                            scope.launch {
                                offset.snapTo((offset.value + delta).coerceIn(-size.width * .32f, size.width * .32f))
                            }
                        }
                    },
                    onDragEnd = {
                        val horizontalVelocity = velocity.calculateVelocity().x
                        val x = offset.value
                        when {
                            horizontal && (x < -commit || horizontalVelocity < -fling) && currentCanNext ->
                                currentNext()
                            horizontal && (x > commit || horizontalVelocity > fling) && currentCanPrevious ->
                                currentPrevious()
                            vertical && totalY > closeDistance -> currentCollapse()
                        }
                        scope.launch {
                            if (animationMillis > 0) {
                                offset.animateTo(
                                    0f,
                                    animationSpec = spring(
                                        dampingRatio = .9f,
                                        stiffness = Spring.StiffnessMedium,
                                        visibilityThreshold = .01f
                                    )
                                )
                            } else {
                                offset.snapTo(0f)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            if (animationMillis > 0) offset.animateTo(0f, tween(160))
                            else offset.snapTo(0f)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = station,
            animationSpec = tween(if (animationMillis > 0) 180 else 0),
            label = "now_playing_station"
        ) { current ->
            StationArtwork(current, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StationArtwork(station: Station, modifier: Modifier) {
    val shape = TuneShapes.Card
    Box(
        modifier.clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(TuneColors.Raised, TuneColors.Surface)
                )
            )
            .border(1.dp, TuneColors.BorderSoft, shape),
        contentAlignment = Alignment.Center
    ) {
        val url = station.iconUrl
        if (url.isNullOrBlank()) {
            TuneIcon(
                R.drawable.ic_radio,
                Modifier.fillMaxSize(.44f),
                TuneColors.Primary.copy(alpha = .82f)
            )
        } else {
            val fallback = painterResource(R.drawable.ic_radio)
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(14.dp),
                placeholder = fallback,
                error = fallback,
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun StationHeading(
    station: Station,
    countryName: String?,
    playing: Boolean,
    isCar: Boolean,
    centered: Boolean
) {
    val alignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Text(
            station.name.ifBlank { stringResource(R.string.station_unnamed) },
            color = TuneColors.Text,
            fontSize = if (isCar) 31.sp else 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            style = TuneTextStyle
        )
        if (!countryName.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (playing) {
                    PlayingWaveBars(
                        Modifier.size(width = 17.dp, height = 11.dp),
                        TuneColors.Primary.copy(alpha = .78f)
                    )
                }
                Text(
                    countryName,
                    color = TuneColors.Muted,
                    fontSize = if (isCar) 16.sp else 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TuneTextStyle
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlayerUiState,
    connected: Boolean,
    buffering: Boolean,
    animationMillis: Int,
    isCar: Boolean,
    canNext: Boolean,
    canPrevious: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val failed = state.error != null
    val playing = state.phase == PlaybackPhase.PLAYING
    val enabled = connected && !buffering
    val previousLabel = stringResource(R.string.player_previous)
    val nextLabel = stringResource(R.string.player_next)
    val actionLabel = when {
        buffering -> stringResource(R.string.playback_buffering)
        failed -> stringResource(R.string.directory_retry)
        playing -> stringResource(R.string.action_pause)
        else -> stringResource(R.string.action_play)
    }
    Row(
        Modifier.fillMaxWidth().focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        NowPlayingCircleButton(
            icon = R.drawable.ic_ui_previous,
            description = previousLabel,
            isCar = isCar,
            tag = "now_playing_previous",
            enabled = canPrevious,
            animationMillis = animationMillis,
            onClick = onPrevious
        )
        Spacer(Modifier.width(if (isCar) 42.dp else 30.dp))
        PrimaryPlaybackButton(
            state = state,
            enabled = enabled,
            isCar = isCar,
            label = actionLabel,
            animationMillis = animationMillis,
            onClick = onToggle
        )
        Spacer(Modifier.width(if (isCar) 42.dp else 30.dp))
        NowPlayingCircleButton(
            icon = R.drawable.ic_ui_next,
            description = nextLabel,
            isCar = isCar,
            tag = "now_playing_next",
            enabled = canNext,
            animationMillis = animationMillis,
            onClick = onNext
        )
    }
}

@Composable
private fun PrimaryPlaybackButton(
    state: PlayerUiState,
    enabled: Boolean,
    isCar: Boolean,
    label: String,
    animationMillis: Int,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .94f else 1f,
        animationSpec = tween(if (animationMillis > 0) 100 else 0),
        label = "now_playing_press_scale"
    )
    val size = if (isCar) 88.dp else 76.dp
    val buffering = state.phase == PlaybackPhase.RESOLVING ||
        state.phase == PlaybackPhase.RETRYING || state.phase == PlaybackPhase.BUFFERING
    val failed = state.error != null && !buffering
    val playing = state.phase == PlaybackPhase.PLAYING
    val background by animateColorAsState(
        targetValue = if (enabled) TuneColors.Accent.copy(alpha = .9f) else TuneColors.Raised,
        animationSpec = tween(animationMillis),
        label = "now_playing_primary_background"
    )
    Box(
        Modifier.size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .then(
                if (focused) Modifier.border(2.dp, TuneColors.Text, CircleShape)
                else Modifier.border(1.dp, TuneColors.Primary.copy(alpha = .32f), CircleShape)
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .onFocusChanged { focused = it.isFocused }
            .testTag("now_playing_play")
            .semantics {
                role = Role.Button
                contentDescription = label
                this.focused = focused
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = when {
                buffering -> PrimaryVisual.Loading
                failed -> PrimaryVisual.Retry
                playing -> PrimaryVisual.Pause
                else -> PrimaryVisual.Play
            },
            animationSpec = tween(animationMillis),
            label = "now_playing_primary_visual"
        ) { visual ->
            when (visual) {
                PrimaryVisual.Loading -> CircularProgressIndicator(
                    Modifier.size(if (isCar) 34.dp else 30.dp),
                    color = TuneColors.Text,
                    strokeWidth = 2.dp,
                    trackColor = TuneColors.Text.copy(alpha = .2f)
                )
                PrimaryVisual.Retry -> TuneIcon(
                    R.drawable.ic_refresh,
                    Modifier.size(if (isCar) 32.dp else 28.dp),
                    TuneColors.Text
                )
                PrimaryVisual.Pause -> TuneIcon(
                    R.drawable.ic_pause,
                    Modifier.size(if (isCar) 36.dp else 32.dp),
                    TuneColors.Text
                )
                PrimaryVisual.Play -> TuneIcon(
                    R.drawable.ic_radio_play,
                    Modifier.size(if (isCar) 36.dp else 32.dp),
                    TuneColors.Text
                )
            }
        }
    }
}

private enum class PrimaryVisual { Play, Pause, Loading, Retry }

@Composable
private fun NowPlayingCircleButton(
    icon: Int,
    description: String,
    isCar: Boolean,
    tag: String,
    enabled: Boolean = true,
    animationMillis: Int,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val tint by animateColorAsState(
        targetValue = if (enabled) TuneColors.Text else TuneColors.Muted.copy(alpha = .4f),
        animationSpec = tween(animationMillis),
        label = "now_playing_control_tint"
    )
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .93f else 1f,
        animationSpec = tween(if (animationMillis > 0) 100 else 0),
        label = "now_playing_control_scale"
    )
    Box(
        Modifier.size(if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .then(
                if (focused) Modifier.border(2.dp, TuneColors.Primary, CircleShape)
                else Modifier
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .onFocusChanged { focused = it.isFocused }
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = description
                this.focused = focused
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        TuneIcon(icon, Modifier.size(if (isCar) 28.dp else 24.dp), tint)
    }
}

@Composable
private fun NowPlayingNotice(state: PlayerUiState, modifier: Modifier = Modifier) {
    val buffering = state.phase == PlaybackPhase.RESOLVING ||
        state.phase == PlaybackPhase.RETRYING || state.phase == PlaybackPhase.BUFFERING
    val failed = state.error != null
    val text = when {
        failed -> playbackErrorText(state.error, state.statusCode)
            ?: stringResource(R.string.playback_unavailable)
        buffering -> stringResource(R.string.playback_connecting_source)
        else -> null
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (text != null) {
            Text(
                text,
                color = if (failed) TuneColors.Error else TuneColors.Muted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TuneTextStyle
            )
        }
    }
}

@Composable
private fun NowPlayingTools(
    isCar: Boolean,
    shuffle: Boolean,
    rangeName: String?,
    animationMillis: Int,
    onToggleShuffle: () -> Unit,
    onVolume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modeLabel = when {
        !shuffle -> stringResource(R.string.player_order_sequential)
        rangeName != null -> stringResource(R.string.now_playing_shuffle_region, rangeName)
        else -> stringResource(R.string.now_playing_shuffle_global)
    }
    val modeDescription = stringResource(R.string.now_playing_shuffle_description, modeLabel)
    val volumeLabel = stringResource(R.string.now_playing_volume)
    Row(
        modifier.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (isCar) 16.dp else 8.dp)
    ) {
        NowPlayingToolChip(
            icon = R.drawable.ic_order_shuffle,
            label = modeLabel,
            description = modeDescription,
            selected = shuffle,
            isCar = isCar,
            tag = "now_playing_shuffle",
            animationMillis = animationMillis,
            onClick = onToggleShuffle,
            modifier = Modifier.weight(1.35f)
        )
        NowPlayingToolChip(
            icon = R.drawable.ic_volume,
            label = volumeLabel,
            description = volumeLabel,
            selected = false,
            isCar = isCar,
            tag = "now_playing_volume",
            animationMillis = animationMillis,
            onClick = onVolume,
            modifier = Modifier.weight(.82f)
        )
    }
}

@Composable
private fun NowPlayingToolChip(
    icon: Int,
    label: String,
    description: String,
    selected: Boolean,
    isCar: Boolean,
    tag: String,
    animationMillis: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (selected) TuneColors.Segment else TuneColors.Raised.copy(alpha = .72f),
        animationSpec = tween(animationMillis),
        label = "now_playing_tool_background"
    )
    Row(
        modifier.height(if (isCar) 56.dp else 48.dp)
            .clip(TuneShapes.Control)
            .background(background)
            .then(
                if (selected || focused) Modifier.border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) TuneColors.Text else TuneColors.Primary.copy(alpha = .52f),
                    TuneShapes.Control
                ) else Modifier
            )
            .clickable(role = Role.Button, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = description
                this.selected = selected
                this.focused = focused
            }
            .padding(horizontal = if (isCar) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCar) 9.dp else 6.dp)
    ) {
        TuneIcon(
            icon,
            Modifier.size(if (isCar) TuneSizes.IconMd else TuneSizes.IconSm),
            if (selected) TuneColors.Primary else TuneColors.Muted
        )
        Text(
            label,
            color = if (selected) TuneColors.Text else TuneColors.Muted,
            fontSize = if (isCar) 14.sp else 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TuneTextStyle
        )
    }
}

