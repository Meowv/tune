package com.meowv.tune.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.Station
import com.meowv.tune.playback.SleepTimerState
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneMotion
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneTypography
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch

@Composable
internal fun NowPlayingScreen(
    isCar: Boolean,
    connected: Boolean,
    state: PlayerUiState,
    station: Station?,
    shuffle: Boolean,
    sleepTimer: SleepTimerState,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    onSelectStation: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    canReconnect: Boolean = false
) {
    val motionEnabled = rememberMotionEnabled()
    val animationMillis = if (motionEnabled) TuneMotion.ColorChange else 0
    val phase = state.phase
    val buffering = phase == PlaybackPhase.RESOLVING ||
        phase == PlaybackPhase.RETRYING || phase == PlaybackPhase.BUFFERING
    val canChangeMedia = connected
    val onNextCurrent by rememberUpdatedState(onNext)
    val onPreviousCurrent by rememberUpdatedState(onPrevious)
    var sleepPanelOpen by rememberSaveable { mutableStateOf(false) }
    var customSleepOpen by rememberSaveable { mutableStateOf(false) }
    val isMusic = state.source == PlaybackSource.MUSIC
    val track = state.music
    val media = if (isMusic) {
        when {
            track != null -> NowPlayingMedia(track.id, track.title, track.coverUrl, true)
            phase == PlaybackPhase.IDLE -> null
            else -> NowPlayingMedia("", stringResource(R.string.menu_music), null, true)
        }
    } else station?.let {
        NowPlayingMedia(it.uuid, it.name.ifBlank { stringResource(R.string.station_unnamed) }, it.iconUrl, false)
    }
    val details: (@Composable (Boolean, @Composable () -> Unit) -> Unit)? = if (isMusic) {
        { compact, title -> MusicNowPlayingDetails(state, isCar, connected, compact, onSeek, title) }
    } else null

    BoxWithConstraints(modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        val safeDrawing = WindowInsets.safeDrawing
        val topInset = with(LocalDensity.current) { safeDrawing.getTop(this).toDp() }
        val wide = isCar || isNowPlayingWide(windowWidth.value, windowHeight.value) ||
            (windowWidth >= 560.dp && windowWidth > windowHeight)
        NowPlayingBackdrop(
            wide = wide,
            modifier = Modifier.fillMaxSize().testTag("now_playing_backdrop")
        )
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .windowInsetsPadding(
                    if (isCar) WindowInsets.displayCutout else WindowInsets.safeDrawing
                )
                .testTag("now_playing_safe_content")
        ) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val contentPadding = if (wide) {
                Modifier.padding(horizontal = if (isCar) 32.dp else 24.dp)
            } else {
                Modifier.padding(horizontal = if (availableWidth < 360.dp) 16.dp else 22.dp)
            }
            Column(Modifier.fillMaxSize().then(contentPadding)) {
                if (!isCar) {
                    NowPlayingTopBar(animationMillis, onCollapse, compact = availableHeight < 360.dp)
                }
                if (media == null) {
                    if (isCar) {
                        NowPlayingCollapseButton(
                            isCar = true,
                            animationMillis = animationMillis,
                            onCollapse = onCollapse,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().testTag("now_playing_empty"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        BrandLogo(Modifier.size(if (isCar) 112.dp else 96.dp), withPlate = true)
                        Spacer(Modifier.height(24.dp))
                        val emptyMessage = when {
                            !connected -> R.string.playback_service_disconnected
                            isMusic -> R.string.player_music_unselected
                            else -> R.string.player_unselected
                        }
                        val reconnect = !connected && canReconnect
                        Text(
                            stringResource(emptyMessage),
                            color = TuneColors.Text,
                            style = TuneTypography.control(isCar)
                        )
                        Spacer(Modifier.height(TuneSpacing.Large))
                        androidx.compose.material3.TextButton(
                            onClick = if (reconnect) onToggle else onSelectStation,
                            modifier = Modifier.heightIn(min = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
                                .testTag("now_playing_select_station")
                        ) {
                            Text(stringResource(when {
                                reconnect -> R.string.playback_reconnect_service
                                isMusic -> R.string.action_select_music
                                else -> R.string.action_select_station
                            }))
                        }
                    }
                } else if (wide) {
                    WideNowPlayingContent(
                        isCar = isCar,
                        state = state,
                        media = media,
                        details = details,
                        connected = connected,
                        canReconnect = canReconnect,
                        buffering = buffering,
                        animationMillis = animationMillis,
                        canNext = canChangeMedia && state.canNext,
                        canPrevious = canChangeMedia && state.canPrevious,
                        shuffle = shuffle,
                        sleepTimer = sleepTimer,
                        onToggle = onToggle,
                        onNext = { if (canChangeMedia) onNextCurrent() },
                        onPrevious = { if (canChangeMedia) onPreviousCurrent() },
                        onToggleShuffle = onToggleShuffle,
                        onSleepTimer = { sleepPanelOpen = true },
                        onCollapse = onCollapse
                    )
                } else {
                    PhoneNowPlayingContent(
                        state = state,
                        media = media,
                        details = details,
                        radioTitleTop = if (details == null && windowHeight >= 700.dp) {
                            windowHeight * .53f - topInset - PhoneNowPlayingTopBarHeight
                        } else null,
                        connected = connected,
                        canReconnect = canReconnect,
                        buffering = buffering,
                        animationMillis = animationMillis,
                        canNext = canChangeMedia && state.canNext,
                        canPrevious = canChangeMedia && state.canPrevious,
                        shuffle = shuffle,
                        sleepTimer = sleepTimer,
                        onToggle = onToggle,
                        onNext = { if (canChangeMedia) onNextCurrent() },
                        onPrevious = { if (canChangeMedia) onPreviousCurrent() },
                        onToggleShuffle = onToggleShuffle,
                        onSleepTimer = { sleepPanelOpen = true },
                        onCollapse = onCollapse
                    )
                }
            }
        }
    }

    if (sleepPanelOpen && (station != null || state.music != null)) {
        SleepTimerPanel(
            isCar = isCar,
            timer = sleepTimer,
            onDismiss = { sleepPanelOpen = false },
            onPreset = {
                onSetSleepTimer(it)
                sleepPanelOpen = false
            },
            onCustom = {
                sleepPanelOpen = false
                customSleepOpen = true
            },
            onCancelTimer = {
                onSetSleepTimer(null)
                sleepPanelOpen = false
            }
        )
    }
    if (customSleepOpen && (station != null || state.music != null)) {
        CustomSleepTimerDialog(
            isCar = isCar,
            onDismiss = { customSleepOpen = false },
            onConfirm = {
                onSetSleepTimer(it)
                customSleepOpen = false
            }
        )
    }
}

@Composable
private fun NowPlayingBackdrop(wide: Boolean, modifier: Modifier = Modifier) {
    val background = TuneColors.Background
    val glow = TuneColors.Accent.copy(alpha = .10f)
    val shade = TuneColors.Raised.copy(alpha = .32f)
    Canvas(modifier) {
        drawRect(background)
        drawRect(
            Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = Offset(size.width * if (wide) .31f else .5f, size.height * if (wide) .50f else .34f),
                radius = (size.minDimension * if (wide) .55f else .68f).coerceAtLeast(1f)
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(shade, Color.Transparent),
                center = Offset(size.width * .95f, size.height * .85f),
                radius = (size.maxDimension * .52f).coerceAtLeast(1f)
            )
        )
    }
}

private val PhoneNowPlayingTopBarHeight = 58.dp

@Composable
private fun NowPlayingTopBar(
    animationMillis: Int,
    onCollapse: () -> Unit,
    compact: Boolean
) {
    Box(Modifier.fillMaxWidth().height(if (compact) TuneSizes.Touch else PhoneNowPlayingTopBarHeight)) {
        NowPlayingCollapseButton(
            isCar = false,
            animationMillis = animationMillis,
            onCollapse = onCollapse,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        BrandLockup(
            label = stringResource(R.string.brand_full),
            logoSize = 25.dp,
            style = TuneTypography.control(isCar = false),
            modifier = Modifier.align(Alignment.Center)
                .padding(horizontal = TuneSizes.Touch)
                .testTag("now_playing_brand"),
            logoTag = "now_playing_brand_logo"
        )
    }
}

@Composable
private fun NowPlayingCollapseButton(
    isCar: Boolean,
    animationMillis: Int,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    NowPlayingIconButton(
        icon = R.drawable.ic_ui_chevron_down,
        description = stringResource(R.string.now_playing_collapse),
        tag = "now_playing_collapse",
        animationMillis = animationMillis,
        iconSize = TuneSizes.IconLg,
        modifier = modifier,
        surfaceSize = null,
        touchSize = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch,
        onClick = onCollapse
    )
}

@Composable
private fun PhoneNowPlayingContent(
    state: PlayerUiState,
    media: NowPlayingMedia,
    details: (@Composable (Boolean, @Composable () -> Unit) -> Unit)?,
    radioTitleTop: Dp?,
    connected: Boolean,
    canReconnect: Boolean,
    buffering: Boolean,
    animationMillis: Int,
    canNext: Boolean,
    canPrevious: Boolean,
    shuffle: Boolean,
    sleepTimer: SleepTimerState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSleepTimer: () -> Unit,
    onCollapse: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        val compact = availableHeight < 560.dp
        val compactDetails = maxHeight < 360.dp
        val detailsHeight = maxHeight * if (compact) .43f else .40f
        val artworkGap = if (compact) 6.dp else 14.dp
        val artworkSlot = radioTitleTop
            ?.let { (it - artworkGap).coerceIn(0.dp, maxHeight * .60f) }
        Column(
            Modifier.fillMaxSize().focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().then(
                    if (artworkSlot == null) Modifier.weight(1f) else Modifier.height(artworkSlot)
                ),
                contentAlignment = Alignment.Center
            ) {
                val artworkSize = minOf(
                    maxWidth * .72f,
                    maxHeight * .94f,
                    292.dp
                ).coerceAtLeast(1.dp)
                NowPlayingArtworkGesture(
                    media = media,
                    canNext = canNext,
                    canPrevious = canPrevious,
                    animationMillis = animationMillis,
                    modifier = Modifier.size(artworkSize),
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onCollapse = onCollapse
                )
            }
            Spacer(Modifier.height(artworkGap))
            if (details == null) {
                NowPlayingTitle(media, isCar = false, Modifier.fillMaxWidth(), compactDetails)
            } else {
                Box(Modifier.widthIn(max = 430.dp).fillMaxWidth().heightIn(max = detailsHeight)) {
                    details(compactDetails) {
                        NowPlayingTitle(media, isCar = false, Modifier.fillMaxWidth(), compactDetails)
                    }
                }
            }
            val controlsGap = when {
                details != null -> 8.dp
                !compact -> (availableHeight * .034f).coerceIn(TuneSpacing.Section, 48.dp)
                artworkSlot != null -> 16.dp
                else -> 8.dp
            }
            Spacer(Modifier.height(controlsGap))
            TransportControls(
                state = state,
                connected = connected,
                canReconnect = canReconnect,
                buffering = buffering,
                animationMillis = animationMillis,
                isCar = false,
                canNext = canNext,
                canPrevious = canPrevious,
                onToggle = onToggle,
                onNext = onNext,
                onPrevious = onPrevious,
                modifier = Modifier.fillMaxWidth(),
                compact = compactDetails
            )
            NowPlayingNotice(
                state,
                connected = connected,
                isCar = false,
                compact = compactDetails
            )
            val lowerSpacingWeight = if (artworkSlot != null) .65f else .12f
            Spacer(Modifier.weight(lowerSpacingWeight))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NowPlayingTools(
                    isCar = false,
                    shuffle = shuffle,
                    sleepTimer = sleepTimer,
                    animationMillis = animationMillis,
                    onToggleShuffle = onToggleShuffle,
                    onSleepTimer = onSleepTimer,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    compact = compactDetails
                )
            }
            Spacer(Modifier.height(if (compact) TuneSpacing.Small else TuneSpacing.Medium))
        }
    }
}

@Composable
private fun WideNowPlayingContent(
    isCar: Boolean,
    state: PlayerUiState,
    media: NowPlayingMedia,
    details: (@Composable (Boolean, @Composable () -> Unit) -> Unit)?,
    connected: Boolean,
    canReconnect: Boolean,
    buffering: Boolean,
    animationMillis: Int,
    canNext: Boolean,
    canPrevious: Boolean,
    shuffle: Boolean,
    sleepTimer: SleepTimerState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSleepTimer: () -> Unit,
    onCollapse: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = if (isCar && maxWidth >= 600.dp) TuneSpacing.Wide else TuneSpacing.Large
        val columnsWidth = (maxWidth - gap).coerceAtLeast(0.dp)
        val controlsWidth = (columnsWidth * .60f)
            .coerceAtLeast(if (isCar) 220.dp else 208.dp)
            .coerceAtMost(columnsWidth)
        val compact = maxHeight < (if (isCar) 408.dp else 360.dp)
        Row(
            Modifier.fillMaxSize().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val artworkSize = minOf(maxWidth, maxHeight * .72f, 308.dp).coerceAtLeast(1.dp)
                NowPlayingArtworkGesture(
                    media = media,
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
                Modifier.width(controlsWidth).fillMaxHeight().testTag("now_playing_info_column"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isCar) {
                    NowPlayingCollapseButton(
                        isCar = true,
                        animationMillis = animationMillis,
                        onCollapse = onCollapse,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
                if (details == null && (isCar || !compact)) {
                    Spacer(Modifier.weight(1f))
                }
                Column(
                    Modifier.fillMaxWidth().then(
                        if (details != null || (compact && !isCar)) {
                            Modifier.weight(1f)
                        } else Modifier
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (details == null) {
                        NowPlayingTitle(media, isCar, Modifier.fillMaxWidth(), compact)
                    } else {
                        details(compact) { NowPlayingTitle(media, isCar, Modifier.fillMaxWidth(), compact) }
                    }
                }
                Spacer(Modifier.height(when {
                    compact -> TuneSpacing.Hairline
                    isCar && details == null -> TuneSpacing.Section
                    else -> TuneSpacing.Medium
                }))
                TransportControls(
                    state = state,
                    connected = connected,
                    canReconnect = canReconnect,
                    buffering = buffering,
                    animationMillis = animationMillis,
                    isCar = isCar,
                    canNext = canNext,
                    canPrevious = canPrevious,
                    onToggle = onToggle,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    modifier = Modifier.fillMaxWidth(),
                    compact = compact
                )
                NowPlayingNotice(state, connected, isCar, compact)
                if (!compact && details != null) {
                    Spacer(Modifier.weight(.32f))
                }
                NowPlayingTools(
                    isCar = isCar,
                    shuffle = shuffle,
                    sleepTimer = sleepTimer,
                    animationMillis = animationMillis,
                    onToggleShuffle = onToggleShuffle,
                    onSleepTimer = onSleepTimer,
                    modifier = Modifier.widthIn(max = 370.dp).fillMaxWidth(),
                    compact = compact
                )
                if (details == null && (isCar || !compact)) Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(if (compact) TuneSpacing.Hairline else TuneSpacing.Medium))
            }
        }
    }
}

@Composable
private fun NowPlayingTitle(media: NowPlayingMedia, isCar: Boolean, modifier: Modifier, compact: Boolean = false) {
    val title = media.title
    val motionEnabled = rememberMotionEnabled()
    Box(
        modifier.testTag("now_playing_title"),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = title,
            animationSpec = tween(if (motionEnabled) TuneMotion.ColorChange else 0),
            label = "now_playing_title"
        ) { current ->
            Text(
                current,
                modifier = Modifier.fillMaxWidth(),
                color = TuneColors.Text,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TuneTypography.playbackTitle(isCar && !compact)
            )
        }
    }
}

private data class NowPlayingMedia(val key: Any, val title: String, val artworkUrl: String?, val isMusic: Boolean)

@Composable
private fun NowPlayingArtworkGesture(
    media: NowPlayingMedia,
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
    val description = stringResource(
        if (media.isMusic) R.string.now_playing_music_artwork else R.string.now_playing_artwork, media.title
    )

    Box(
        modifier
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (abs(offset.value) / size.width.coerceAtLeast(1f)) * .28f
            }
            .testTag("now_playing_artwork")
            .semantics { contentDescription = description }
            .pointerInput(media.key) {
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
                                offset.snapTo(
                                    (offset.value + delta).coerceIn(
                                        -size.width * .32f,
                                        size.width * .32f
                                    )
                                )
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
                                    spring(
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
                            if (animationMillis > 0) {
                                offset.animateTo(0f, tween(160))
                            } else {
                                offset.snapTo(0f)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = media,
            animationSpec = tween(if (animationMillis > 0) 180 else 0),
            label = "now_playing_media"
        ) { current ->
            NowPlayingArtwork(current, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NowPlayingArtwork(media: NowPlayingMedia, modifier: Modifier) {
    val shape = TuneShapes.Card
    Box(modifier.shadow(18.dp, shape, clip = false, ambientColor = TuneColors.Shadow, spotColor = TuneColors.Shadow).clip(shape)) {
        MediaArtwork(media.artworkUrl, media.isMusic, artworkKey = media.key.toString(), modifier = Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize()
                .border(1.dp, TuneColors.PrimaryLight.copy(alpha = .16f), shape)
        )
    }
}

@Composable
private fun TransportControls(
    state: PlayerUiState,
    connected: Boolean,
    canReconnect: Boolean,
    buffering: Boolean,
    animationMillis: Int,
    isCar: Boolean,
    canNext: Boolean,
    canPrevious: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val isMusic = state.source == PlaybackSource.MUSIC
    val skipTouch = when {
        isCar && compact -> TuneSizes.TouchCar
        isCar -> 60.dp
        compact -> TuneSizes.Touch
        else -> 52.dp
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = when {
                isCar && compact -> 216.dp
                isCar -> 252.dp
                else -> 224.dp
            }).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NowPlayingIconButton(
                icon = R.drawable.ic_now_playing_previous,
                description = stringResource(if (isMusic) R.string.music_previous else R.string.player_previous),
                touchSize = skipTouch,
                tag = "now_playing_previous",
                enabled = canPrevious,
                animationMillis = animationMillis,
                iconSize = if (isCar) TuneSizes.IconXl else TuneSizes.IconMd,
                onClick = onPrevious
            )
            PrimaryPlaybackControl(
                state = state,
                connected = connected,
                canReconnect = canReconnect,
                buffering = buffering,
                isCar = isCar,
                animationMillis = animationMillis,
                compact = compact,
                onClick = onToggle
            )
            NowPlayingIconButton(
                icon = R.drawable.ic_now_playing_next,
                description = stringResource(if (isMusic) R.string.music_next else R.string.player_next),
                touchSize = skipTouch,
                tag = "now_playing_next",
                enabled = canNext,
                animationMillis = animationMillis,
                iconSize = if (isCar) TuneSizes.IconXl else TuneSizes.IconMd,
                onClick = onNext
            )
        }
    }
}

@Composable
private fun PrimaryPlaybackControl(
    state: PlayerUiState,
    connected: Boolean,
    canReconnect: Boolean,
    buffering: Boolean,
    isCar: Boolean,
    animationMillis: Int,
    compact: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .94f else 1f,
        animationSpec = tween(if (animationMillis > 0) TuneMotion.Press else 0),
        label = "now_playing_primary_press"
    )
    var focused by remember { mutableStateOf(false) }
    val isRadio = state.source == PlaybackSource.RADIO
    val visual = when {
        !connected -> PlaybackControlVisual.Disconnected
        buffering -> PlaybackControlVisual.Loading
        state.error != null || state.phase == PlaybackPhase.ERROR -> PlaybackControlVisual.Error
        state.phase == PlaybackPhase.PLAYING -> PlaybackControlVisual.Playing
        else -> PlaybackControlVisual.Paused
    }
    val canCancel = pendingPlaybackCommand(state) != null
    val enabled = if (connected) !buffering || canCancel else canReconnect
    val label = stringResource(
        when (visual) {
            PlaybackControlVisual.Disconnected -> if (canReconnect) R.string.playback_reconnect_service
                else R.string.playback_service_disconnected
            PlaybackControlVisual.Loading -> if (canCancel) R.string.playback_cancel_loading else R.string.playback_buffering
            PlaybackControlVisual.Error -> R.string.directory_retry
            PlaybackControlVisual.Playing -> R.string.now_playing_pause_accessibility
            PlaybackControlVisual.Paused -> R.string.now_playing_resume_accessibility
        }
    )
    val diameter = when {
        compact -> if (isCar) TuneSizes.TouchCar else TuneSizes.Touch
        isCar -> 76.dp
        else -> 63.dp
    }
    val foreground = if (connected) TuneColors.OnPrimary else TuneColors.Muted
    val surface = Brush.linearGradient(
        if (connected) listOf(TuneColors.Primary, androidx.compose.ui.graphics.lerp(TuneColors.Primary, TuneColors.Accent, .24f))
        else listOf(TuneColors.Raised, TuneColors.Surface)
    )
    Box(
        Modifier.size(diameter)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .testTag("now_playing_play")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    if (connected) 3.dp else 0.dp, CircleShape, clip = false,
                    ambientColor = TuneColors.Shadow, spotColor = TuneColors.Shadow
                )
                .background(surface, CircleShape)
                .then(if (focused) Modifier.border(2.dp, TuneColors.FocusOnAccent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = visual,
                animationSpec = tween(animationMillis),
                label = "now_playing_primary_state"
            ) { current ->
                val iconSize = if (diameter < 60.dp) TuneSizes.IconMd else TuneSizes.IconXl
                when {
                    current == PlaybackControlVisual.Loading -> {
                        Box(contentAlignment = Alignment.Center) {
                            TuneLoadingIndicator(Modifier.size(iconSize).clearAndSetSemantics {}, tint = foreground)
                            if (canCancel) TuneIcon(R.drawable.ic_close, Modifier.size(iconSize * .48f), foreground)
                        }
                    }
                    current == PlaybackControlVisual.Playing && isRadio ->
                        PlayingWaveform(foreground, Modifier.size(iconSize + 4.dp, iconSize))
                    else -> {
                        val icon = when (current) {
                            PlaybackControlVisual.Error, PlaybackControlVisual.Disconnected -> R.drawable.ic_refresh
                            PlaybackControlVisual.Playing -> R.drawable.ic_music_pause
                            else -> R.drawable.ic_radio_play
                        }
                        TuneIcon(icon, Modifier.size(iconSize), foreground)
                    }
                }
            }
        }
    }
}

private enum class PlaybackControlVisual { Playing, Paused, Loading, Error, Disconnected }

@Composable
private fun PlayingWaveform(color: Color, modifier: Modifier = Modifier) {
    val motionEnabled = rememberMotionEnabled()
    val animation = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "now_playing_wave")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(tween(1_700, easing = LinearEasing)),
            label = "now_playing_wave_phase"
        )
    } else null
    val levels = remember { listOf(.48f, .87f, .65f, 1f, .43f) }
    Canvas(modifier) {
        val phase = animation?.value
        val spacing = size.width / levels.size
        val barWidth = spacing * .5f
        levels.forEachIndexed { index, level ->
            val oscillation = if (phase != null) .77f + .23f * sin(phase + index * .83f) else 1f
            val height = size.height * level * oscillation
            drawRoundRect(
                color = color,
                topLeft = Offset((index + .5f) * spacing - barWidth / 2f, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

@Composable
private fun NowPlayingIconButton(
    icon: Int,
    description: String,
    touchSize: Dp,
    tag: String,
    animationMillis: Int,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    surfaceSize: Dp? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val tint by animateColorAsState(
        targetValue = if (enabled) TuneColors.Text else TuneColors.DisabledContent,
        animationSpec = tween(animationMillis),
        label = "now_playing_icon_tint"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .92f else 1f,
        animationSpec = tween(if (animationMillis > 0) TuneMotion.Press else 0),
        label = "now_playing_icon_scale"
    )
    val shape = TuneShapes.Navigation
    Box(
        modifier.size(touchSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .then(
                if (focused) Modifier.border(2.dp, TuneColors.Primary, shape)
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = description
                this.focused = focused
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        if (surfaceSize != null) {
            Box(
                Modifier.size(surfaceSize)
                    .background(TuneColors.Raised.copy(alpha = .75f), TuneShapes.Station)
                    .border(1.dp, TuneColors.BorderStrong, TuneShapes.Station)
            )
        }
        TuneIcon(icon, Modifier.size(iconSize), tint)
    }
}

@Composable
private fun NowPlayingNotice(state: PlayerUiState, connected: Boolean, isCar: Boolean, compact: Boolean) {
    val text = when {
        !connected -> stringResource(R.string.playback_service_disconnected)
        state.source == PlaybackSource.MUSIC -> musicPlaybackErrorText(state)
        else -> playbackErrorText(state.error, state.statusCode)
    }
    if (compact && text == null) return
    val motionEnabled = rememberMotionEnabled()
    val style = TuneTypography.secondary(isCar)
    val maxLines = if (compact) 1 else 2
    val height = with(LocalDensity.current) { style.lineHeight.toDp() } * maxLines
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Crossfade(
            targetState = text,
            animationSpec = tween(if (motionEnabled) 160 else 0),
            label = "now_playing_notice"
        ) { current ->
            if (current != null) {
                Text(
                    current,
                    color = TuneColors.Error,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = style,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

@Composable
private fun NowPlayingTools(
    isCar: Boolean,
    shuffle: Boolean,
    sleepTimer: SleepTimerState,
    animationMillis: Int,
    onToggleShuffle: () -> Unit,
    onSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val shape = TuneShapes.Navigation
    // 播放顺序按钮展示"当前生效的模式"，点击切换到另一种；图标、文字、读屏描述共用同一状态。
    val sequential = !shuffle
    val orderLabel = stringResource(
        if (sequential) R.string.player_order_sequential else R.string.player_order_shuffle
    )
    val nextOrderLabel = stringResource(
        if (sequential) R.string.player_order_shuffle else R.string.player_order_sequential
    )
    val orderDescription = stringResource(
        R.string.player_order_accessibility, orderLabel, nextOrderLabel
    )
    val orderIcon = if (sequential) R.drawable.ic_order_sequential else R.drawable.ic_order_shuffle
    val sleepLabel = stringResource(R.string.now_playing_sleep_timer)
    val remaining = if (sleepTimer.isActive) {
        sleepRemainingLabel(sleepTimer.remainingMillis)
    } else null
    val sleepDescription = remaining?.let {
        stringResource(R.string.now_playing_sleep_accessibility_active, it)
    } ?: stringResource(R.string.now_playing_sleep_accessibility)
    val minHeight = if (isCar || !compact) TuneSizes.TouchCar else TuneSizes.Touch
    val textHeight = with(LocalDensity.current) {
        TuneTypography.secondary(isCar).lineHeight.toDp() * (if (compact) 2 else 1) +
            TuneTypography.meta(isCar).lineHeight.toDp()
    }
    val height = maxOf(minHeight, textHeight + TuneSpacing.Small)
    Row(
        modifier.height(height).clip(shape)
            .background(TuneColors.Player.copy(alpha = .88f))
            .border(1.dp, TuneColors.Border, shape)
            .focusGroup()
            .testTag("now_playing_tools"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarAction(
            tag = "now_playing_shuffle",
            icon = orderIcon,
            label = orderLabel,
            detail = null,
            selected = true,
            description = orderDescription,
            isCar = isCar,
            animationMillis = animationMillis,
            compact = compact,
            announceSelected = false,
            modifier = Modifier.weight(1f),
            onClick = onToggleShuffle
        )
        Box(
            Modifier.width(1.dp).height(28.dp).background(TuneColors.BorderSoft)
        )
        ToolbarAction(
            tag = "now_playing_sleep",
            icon = R.drawable.ic_sleep,
            label = sleepLabel,
            detail = remaining,
            selected = sleepTimer.isActive,
            description = sleepDescription,
            isCar = isCar,
            animationMillis = animationMillis,
            compact = compact,
            modifier = Modifier.weight(1f),
            onClick = onSleepTimer
        )
    }
}

@Composable
private fun ToolbarAction(
    tag: String,
    icon: Int,
    label: String,
    detail: String?,
    selected: Boolean,
    description: String,
    isCar: Boolean,
    animationMillis: Int,
    announceSelected: Boolean = true,
    modifier: Modifier,
    compact: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Control
    val contentColor by animateColorAsState(
        targetValue = if (selected) TuneColors.PrimaryLight else TuneColors.Muted,
        animationSpec = tween(animationMillis),
        label = "now_playing_tool_content"
    )
    Row(
        modifier.fillMaxHeight()
            .clip(shape)
            .then(
                if (focused) Modifier.border(2.dp, TuneColors.Primary, shape)
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (announceSelected) this.selected = selected
                this.focused = focused
            }
            .padding(horizontal = if (compact) TuneSpacing.Small else TuneSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        TuneIcon(
            icon,
            Modifier.size(if (isCar) TuneSizes.IconMd else TuneSizes.IconSm),
            contentColor
        )
        Spacer(Modifier.width(if (isCar) 10.dp else 8.dp))
        Column(
            (if (compact) Modifier.weight(1f, fill = false) else Modifier).animateContentSize(
                animationSpec = tween(if (animationMillis > 0) 160 else 0)
            ),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                label,
                modifier = Modifier.testTag("${tag}_label"),
                color = if (selected) TuneColors.Text else contentColor,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = TuneTypography.secondary(isCar)
            )
            if (detail != null) {
                Crossfade(
                    targetState = detail,
                    animationSpec = tween(if (animationMillis > 0) TuneMotion.ColorChange else 0),
                    label = "now_playing_tool_detail"
                ) { current ->
                    Text(
                        current,
                        color = TuneColors.PrimaryLight,
                        maxLines = 1,
                        style = TuneTypography.meta(isCar)
                    )
                }
            }
        }
    }
}
