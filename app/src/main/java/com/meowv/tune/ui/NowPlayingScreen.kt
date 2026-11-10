package com.meowv.tune.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.meowv.tune.playback.SleepTimerState
import com.meowv.tune.playback.sleepTimerRemainingMinutes
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneTextStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingScreen(
    isCar: Boolean,
    connected: Boolean,
    state: PlayerUiState,
    station: Station,
    shuffle: Boolean,
    sleepTimer: SleepTimerState,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val motionEnabled = rememberMotionEnabled()
    val animationMillis = if (motionEnabled) 220 else 0
    val phase = state.phase
    val buffering = phase == PlaybackPhase.RESOLVING ||
        phase == PlaybackPhase.RETRYING || phase == PlaybackPhase.BUFFERING
    val controlsLocked = buffering
    val canChangeStation = connected && !controlsLocked
    val onNextCurrent by rememberUpdatedState(onNext)
    val onPreviousCurrent by rememberUpdatedState(onPrevious)
    var sleepPanelOpen by rememberSaveable { mutableStateOf(false) }
    var customSleepOpen by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        val wide = isCar || isNowPlayingWide(windowWidth.value, windowHeight.value)
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
                        connected = connected,
                        buffering = buffering,
                        animationMillis = animationMillis,
                        canNext = connected && state.canNext && !controlsLocked,
                        canPrevious = connected && state.canPrevious && !controlsLocked,
                        shuffle = shuffle,
                        sleepTimer = sleepTimer,
                        onToggle = onToggle,
                        onNext = { if (canChangeStation) onNextCurrent() },
                        onPrevious = { if (canChangeStation) onPreviousCurrent() },
                        onToggleShuffle = onToggleShuffle,
                        onSleepTimer = { sleepPanelOpen = true },
                        onCollapse = onCollapse
                    )
                } else {
                    PhoneNowPlayingContent(
                        state = state,
                        station = station,
                        connected = connected,
                        buffering = buffering,
                        animationMillis = animationMillis,
                        canNext = connected && state.canNext && !controlsLocked,
                        canPrevious = connected && state.canPrevious && !controlsLocked,
                        shuffle = shuffle,
                        sleepTimer = sleepTimer,
                        onToggle = onToggle,
                        onNext = { if (canChangeStation) onNextCurrent() },
                        onPrevious = { if (canChangeStation) onPreviousCurrent() },
                        onToggleShuffle = onToggleShuffle,
                        onSleepTimer = { sleepPanelOpen = true },
                        onCollapse = onCollapse
                    )
                }
            }
        }
    }

    if (sleepPanelOpen) {
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
    if (customSleepOpen) {
        CustomSleepTimerDialog(
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
    Canvas(modifier.fillMaxSize()) {
        val center = Offset(
            x = size.width * if (wide) .31f else .5f,
            y = size.height * if (wide) .5f else .39f
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF2A214F),
                    Color(0xFF151A34),
                    TuneColors.Background
                ),
                center = center,
                radius = size.maxDimension * .78f
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    TuneColors.Primary.copy(alpha = .19f),
                    TuneColors.Accent.copy(alpha = .07f),
                    Color.Transparent
                ),
                center = center,
                radius = size.minDimension * if (wide) .43f else .58f
            ),
            radius = size.minDimension * if (wide) .43f else .58f,
            center = center
        )
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    TuneColors.Primary.copy(alpha = .09f),
                    Color.Transparent
                ),
                center = center,
                radius = size.minDimension * if (wide) .48f else .64f
            ),
            topLeft = Offset(
                center.x - size.minDimension * if (wide) .48f else .64f,
                center.y - size.minDimension * if (wide) .37f else .46f
            ),
            size = Size(
                size.minDimension * if (wide) .96f else 1.28f,
                size.minDimension * if (wide) .74f else .92f
            )
        )
        drawCircle(
            color = TuneColors.PrimaryLight.copy(alpha = .055f),
            radius = size.minDimension * if (wide) .37f else .49f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        val topWave = Path().apply {
            moveTo(0f, size.height * .24f)
            cubicTo(
                size.width * .18f, size.height * .18f,
                size.width * .38f, size.height * .04f,
                size.width * .64f, size.height * .12f
            )
            cubicTo(
                size.width * .81f, size.height * .17f,
                size.width * .91f, size.height * .09f,
                size.width, size.height * .07f
            )
            lineTo(size.width, 0f)
            lineTo(0f, 0f)
            close()
        }
        drawPath(
            topWave,
            Brush.linearGradient(
                listOf(
                    Color(0xFF49317D).copy(alpha = .18f),
                    Color(0xFF251E52).copy(alpha = .08f),
                    Color.Transparent
                )
            )
        )
        val secondaryTopWave = Path().apply {
            moveTo(0f, size.height * .33f)
            cubicTo(
                size.width * .20f, size.height * .26f,
                size.width * .35f, size.height * .16f,
                size.width * .58f, size.height * .2f
            )
            cubicTo(
                size.width * .73f, size.height * .23f,
                size.width * .87f, size.height * .18f,
                size.width, size.height * .12f
            )
            lineTo(size.width, size.height * .29f)
            cubicTo(
                size.width * .78f, size.height * .34f,
                size.width * .62f, size.height * .27f,
                size.width * .42f, size.height * .29f
            )
            cubicTo(
                size.width * .22f, size.height * .31f,
                size.width * .10f, size.height * .39f,
                0f, size.height * .4f
            )
            close()
        }
        drawPath(
            secondaryTopWave,
            Brush.horizontalGradient(
                listOf(
                    Color(0xFF5F3B99).copy(alpha = .09f),
                    Color(0xFF8B5CF6).copy(alpha = .14f),
                    Color.Transparent
                )
            )
        )

        val lowerWave = Path().apply {
            moveTo(0f, size.height * .88f)
            cubicTo(
                size.width * .18f, size.height * .83f,
                size.width * .35f, size.height * .94f,
                size.width * .52f, size.height * .89f
            )
            cubicTo(
                size.width * .72f, size.height * .84f,
                size.width * .84f, size.height * .91f,
                size.width, size.height * .86f
            )
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            lowerWave,
            Brush.verticalGradient(
                listOf(Color(0xFF34205E).copy(alpha = .46f), Color(0xFF111832))
            )
        )

        val bottomWave = Path().apply {
            moveTo(0f, size.height * .95f)
            cubicTo(
                size.width * .16f, size.height * .91f,
                size.width * .33f, size.height * .99f,
                size.width * .51f, size.height * .95f
            )
            cubicTo(
                size.width * .72f, size.height * .90f,
                size.width * .86f, size.height * .98f,
                size.width, size.height * .94f
            )
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(bottomWave, Color(0xFF14162F).copy(alpha = .9f))
    }
}

@Composable
private fun NowPlayingTopBar(
    isCar: Boolean,
    animationMillis: Int,
    onCollapse: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(if (isCar) 68.dp else 58.dp)) {
        NowPlayingIconButton(
            icon = R.drawable.ic_ui_chevron_down,
            description = stringResource(R.string.now_playing_collapse),
            isCar = isCar,
            tag = "now_playing_collapse",
            animationMillis = animationMillis,
            iconSize = if (isCar) 28.dp else 24.dp,
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Row(
            Modifier.align(Alignment.Center).testTag("now_playing_brand"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCar) 12.dp else 9.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(if (isCar) 50.dp else 38.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    TuneColors.Primary.copy(alpha = .2f),
                                    TuneColors.Accent.copy(alpha = .06f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                )
                BrandLogo(Modifier.size(if (isCar) 38.dp else 30.dp))
            }
            Text(
                stringResource(R.string.brand_full),
                color = TuneColors.Text,
                fontSize = if (isCar) 19.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TuneTextStyle
            )
        }
    }
}

@Composable
private fun PhoneNowPlayingContent(
    state: PlayerUiState,
    station: Station,
    connected: Boolean,
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
        val compact = maxHeight < 560.dp
        Column(
            Modifier.fillMaxSize().focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val artworkSize = minOf(
                    maxWidth * if (compact) .64f else .72f,
                    maxHeight * .94f,
                    if (compact) 230.dp else 280.dp
                ).coerceAtLeast(1.dp)
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
            Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
            StationTitle(
                station = station,
                isCar = false,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(if (compact) 8.dp else 50.dp))
            TransportControls(
                state = state,
                connected = connected,
                buffering = buffering,
                animationMillis = animationMillis,
                isCar = false,
                canNext = canNext,
                canPrevious = canPrevious,
                onToggle = onToggle,
                onNext = onNext,
                onPrevious = onPrevious,
                modifier = Modifier.fillMaxWidth()
            )
            NowPlayingNotice(
                state,
                modifier = Modifier.height(if (compact) 28.dp else 34.dp)
            )
            Spacer(Modifier.weight(if (compact) .12f else .40f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NowPlayingTools(
                    isCar = false,
                    shuffle = shuffle,
                    sleepTimer = sleepTimer,
                    animationMillis = animationMillis,
                    onToggleShuffle = onToggleShuffle,
                    onSleepTimer = onSleepTimer,
                    modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth()
                )
            }
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        }
    }
}

@Composable
private fun WideNowPlayingContent(
    isCar: Boolean,
    maxWidth: Dp,
    maxHeight: Dp,
    state: PlayerUiState,
    station: Station,
    connected: Boolean,
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
    Box(Modifier.fillMaxSize()) {
        val artworkSize = minOf(
            maxWidth * if (isCar) .36f else .34f,
            maxHeight * .68f,
            if (isCar) 420.dp else 330.dp
        ).coerceAtLeast(150.dp)
        Row(
            Modifier.fillMaxSize().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(if (isCar) 44.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1.04f).fillMaxHeight(), contentAlignment = Alignment.Center) {
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
                Modifier.weight(.96f).fillMaxHeight().widthIn(max = if (isCar) 560.dp else 430.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                StationTitle(
                    station = station,
                    isCar = isCar,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(if (isCar) 22.dp else 14.dp))
                TransportControls(
                    state = state,
                    connected = connected,
                    buffering = buffering,
                    animationMillis = animationMillis,
                    isCar = isCar,
                    canNext = canNext,
                    canPrevious = canPrevious,
                    onToggle = onToggle,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    modifier = Modifier.fillMaxWidth()
                )
                NowPlayingNotice(state, modifier = Modifier.height(34.dp))
                Spacer(Modifier.weight(1f))
                NowPlayingTools(
                    isCar = isCar,
                    shuffle = shuffle,
                    sleepTimer = sleepTimer,
                    animationMillis = animationMillis,
                    onToggleShuffle = onToggleShuffle,
                    onSleepTimer = onSleepTimer,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun StationTitle(station: Station, isCar: Boolean, modifier: Modifier) {
    val title = station.name.ifBlank { stringResource(R.string.station_unnamed) }
    val motionEnabled = rememberMotionEnabled()
    Box(
        modifier.testTag("now_playing_title"),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = title,
            animationSpec = tween(if (motionEnabled) 180 else 0),
            label = "now_playing_title"
        ) { current ->
            Text(
                current,
                modifier = Modifier.fillMaxWidth(),
                color = TuneColors.Text,
                fontSize = if (isCar) 34.sp else 30.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TuneTextStyle
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
                alpha = 1f - (abs(offset.value) / size.width.coerceAtLeast(1f)) * .28f
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
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
                .background(
                    Brush.radialGradient(
                        listOf(
                            TuneColors.Accent.copy(alpha = .22f),
                            TuneColors.Primary.copy(alpha = .08f),
                            Color.Transparent
                        )
                    ),
                    shape
                )
        )
        Box(
            Modifier.matchParentSize().clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2B2852), Color(0xFF171E39))
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            TuneColors.PrimaryLight.copy(alpha = .42f),
                            TuneColors.Primary.copy(alpha = .14f),
                            TuneColors.BorderSoft.copy(alpha = .55f)
                        )
                    ),
                    shape = shape
                ),
            contentAlignment = Alignment.Center
        ) {
            val url = station.iconUrl
            if (url.isNullOrBlank()) {
                TuneIcon(
                    R.drawable.ic_radio,
                    Modifier.fillMaxSize(.42f),
                    TuneColors.Primary.copy(alpha = .9f)
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
}

@Composable
private fun TransportControls(
    state: PlayerUiState,
    connected: Boolean,
    buffering: Boolean,
    animationMillis: Int,
    isCar: Boolean,
    canNext: Boolean,
    canPrevious: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = if (isCar) 430.dp else 350.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NowPlayingIconButton(
                icon = R.drawable.ic_now_playing_previous,
                description = stringResource(R.string.player_previous),
                isCar = isCar,
                tag = "now_playing_previous",
                enabled = canPrevious,
                animationMillis = animationMillis,
                iconSize = if (isCar) 34.dp else 30.dp,
                onClick = onPrevious
            )
            WaveformPlaybackControl(
                state = state,
                enabled = connected && !buffering,
                buffering = buffering,
                isCar = isCar,
                animationMillis = animationMillis,
                onClick = onToggle
            )
            NowPlayingIconButton(
                icon = R.drawable.ic_now_playing_next,
                description = stringResource(R.string.player_next),
                isCar = isCar,
                tag = "now_playing_next",
                enabled = canNext,
                animationMillis = animationMillis,
                iconSize = if (isCar) 34.dp else 30.dp,
                onClick = onNext
            )
        }
    }
}

@Composable
private fun WaveformPlaybackControl(
    state: PlayerUiState,
    enabled: Boolean,
    buffering: Boolean,
    isCar: Boolean,
    animationMillis: Int,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .96f else 1f,
        animationSpec = tween(if (animationMillis > 0) 100 else 0),
        label = "now_playing_wave_press"
    )
    val playing = state.phase == PlaybackPhase.PLAYING
    val failed = state.error != null && !buffering
    val label = when {
        buffering -> stringResource(R.string.playback_buffering)
        failed -> stringResource(R.string.directory_retry)
        playing -> stringResource(R.string.now_playing_pause_accessibility)
        else -> stringResource(R.string.now_playing_resume_accessibility)
    }
    val shape = RoundedCornerShape(24.dp)
    val background by animateColorAsState(
        targetValue = if (pressed) TuneColors.Primary.copy(alpha = .09f) else Color.Transparent,
        animationSpec = tween(if (animationMillis > 0) 100 else 0),
        label = "now_playing_wave_background"
    )
    Box(
        Modifier.size(
            width = if (isCar) 142.dp else 112.dp,
            height = if (isCar) 82.dp else 68.dp
        )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .then(
                if (focused) Modifier.border(2.dp, TuneColors.Primary, shape)
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
            .testTag("now_playing_play")
            .semantics {
                role = Role.Button
                contentDescription = label
                this.focused = focused
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        val visual = when {
            buffering -> WaveVisual.Loading
            failed -> WaveVisual.Error
            playing -> WaveVisual.Playing
            else -> WaveVisual.Paused
        }
        Crossfade(
            targetState = visual,
            animationSpec = tween(if (animationMillis > 0) 160 else 0),
            label = "now_playing_wave_state"
        ) { current ->
            when (current) {
                WaveVisual.Playing -> PlayingWaveform(
                    animating = true,
                    showPlayIcon = false,
                    modifier = Modifier.fillMaxSize(.78f)
                )
                WaveVisual.Paused -> PlayingWaveform(
                    animating = false,
                    showPlayIcon = true,
                    modifier = Modifier.fillMaxSize(.78f)
                )
                WaveVisual.Loading -> CircularProgressIndicator(
                    Modifier.size(if (isCar) 32.dp else 27.dp),
                    color = TuneColors.PrimaryLight,
                    strokeWidth = 2.dp,
                    trackColor = TuneColors.Primary.copy(alpha = .16f)
                )
                WaveVisual.Error -> TuneIcon(
                    R.drawable.ic_refresh,
                    Modifier.size(if (isCar) 32.dp else 27.dp),
                    TuneColors.Error
                )
            }
        }
    }
}

private enum class WaveVisual { Playing, Paused, Loading, Error }

@Composable
private fun PlayingWaveform(
    animating: Boolean,
    showPlayIcon: Boolean,
    modifier: Modifier = Modifier
) {
    val motionEnabled = rememberMotionEnabled()
    val phase: Float = if (animating && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "now_playing_wave")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = (PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1_700, easing = LinearEasing)
            ),
            label = "now_playing_wave_phase"
        )
        value
    } else {
        0f
    }
    val staticLevels = remember { listOf(.36f, .62f, .84f, 1f, .74f, .5f, .3f) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val count = staticLevels.size
            val gap = size.width / (count * 2f + 1f)
            val barWidth = gap.coerceAtMost(size.width * .055f)
            staticLevels.forEachIndexed { index, baseLevel ->
                val animatedLevel = (.34f + .66f * (.5f + .5f * sin(phase + index * .83f)))
                val centerWeight = when (index) {
                    3 -> 1.14f
                    2, 4 -> 1.05f
                    else -> 1f
                }
                val level = if (animating && motionEnabled) {
                    ((baseLevel * .48f + animatedLevel * .52f) * centerWeight).coerceIn(.22f, 1f)
                } else {
                    (baseLevel * centerWeight).coerceIn(.22f, 1f)
                }
                val height = size.height * level
                val left = gap + index * (gap + barWidth) + (gap - barWidth) / 2f
                if (animating) {
                    drawRoundRect(
                        color = TuneColors.Primary.copy(alpha = .1f),
                        topLeft = Offset(left - barWidth * .65f, (size.height - height) / 2f - 3.dp.toPx()),
                        size = Size(barWidth * 2.3f, height + 6.dp.toPx()),
                        cornerRadius = CornerRadius(barWidth)
                    )
                }
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            TuneColors.PrimaryLight.copy(alpha = if (animating) .94f else .45f),
                            TuneColors.Primary.copy(alpha = if (animating) .72f else .3f)
                        ),
                        startY = (size.height - height) / 2f,
                        endY = (size.height + height) / 2f
                    ),
                    topLeft = Offset(left, (size.height - height) / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f)
                )
            }
        }
        if (showPlayIcon) {
            Box(
                Modifier.size(if (showPlayIcon) 26.dp else 0.dp)
                    .background(TuneColors.Background.copy(alpha = .82f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                TuneIcon(
                    R.drawable.ic_radio_play,
                    Modifier.size(18.dp),
                    TuneColors.PrimaryLight
                )
            }
        }
    }
}

@Composable
private fun NowPlayingIconButton(
    icon: Int,
    description: String,
    isCar: Boolean,
    tag: String,
    animationMillis: Int,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val tint by animateColorAsState(
        targetValue = if (enabled) TuneColors.Text else TuneColors.Muted.copy(alpha = .36f),
        animationSpec = tween(animationMillis),
        label = "now_playing_icon_tint"
    )
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .92f else 1f,
        animationSpec = tween(if (animationMillis > 0) 100 else 0),
        label = "now_playing_icon_scale"
    )
    val touch = if (isCar) 64.dp else TuneSizes.Touch
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.size(touch)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .then(
                if (focused) Modifier.border(2.dp, TuneColors.Primary, shape)
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
        TuneIcon(
            icon,
            Modifier.size(iconSize),
            tint
        )
    }
}

@Composable
private fun NowPlayingNotice(state: PlayerUiState, modifier: Modifier = Modifier) {
    val buffering = state.phase == PlaybackPhase.RESOLVING ||
        state.phase == PlaybackPhase.RETRYING || state.phase == PlaybackPhase.BUFFERING
    val text = when {
        state.error != null -> playbackErrorText(state.error, state.statusCode)
            ?: stringResource(R.string.playback_unavailable)
        buffering -> stringResource(R.string.playback_connecting_source)
        else -> null
    }
    val motionEnabled = rememberMotionEnabled()
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Crossfade(
            targetState = text,
            animationSpec = tween(if (motionEnabled) 160 else 0),
            label = "now_playing_notice"
        ) { current ->
            if (current != null) {
                Text(
                    current,
                    color = if (state.error != null) TuneColors.Error else TuneColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = TuneTextStyle
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
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(if (isCar) 34.dp else 28.dp)
    val randomLabel = stringResource(R.string.player_order_shuffle)
    val sleepLabel = stringResource(R.string.now_playing_sleep_timer)
    val remaining = if (sleepTimer.isActive) {
        sleepRemainingLabel(sleepTimer.remainingMillis)
    } else null
    val sleepDescription = remaining?.let {
        stringResource(R.string.now_playing_sleep_accessibility_active, it)
    } ?: stringResource(R.string.now_playing_sleep_accessibility)
    Row(
        modifier.height(if (isCar) 80.dp else 76.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        TuneColors.Surface.copy(alpha = .88f),
                        TuneColors.Player.copy(alpha = .72f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        TuneColors.PrimaryLight.copy(alpha = .3f),
                        TuneColors.BorderSoft.copy(alpha = .78f),
                        TuneColors.Accent.copy(alpha = .12f)
                    )
                ),
                shape = shape
            )
            .focusGroup()
            .testTag("now_playing_tools"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarAction(
            tag = "now_playing_shuffle",
            icon = R.drawable.ic_order_shuffle,
            label = randomLabel,
            detail = null,
            selected = shuffle,
            description = randomLabel,
            isCar = isCar,
            animationMillis = animationMillis,
            reserveDetail = false,
            modifier = Modifier.weight(1f),
            onClick = onToggleShuffle
        )
        Box(
            Modifier.width(1.dp).height(if (isCar) 42.dp else 36.dp)
                .background(TuneColors.Muted.copy(alpha = .24f))
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
            reserveDetail = true,
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
    reserveDetail: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
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
            .clickable(role = Role.Button, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = description
                this.selected = selected
                this.focused = focused
            }
            .padding(horizontal = if (isCar) 18.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        TuneIcon(
            icon,
            Modifier.size(if (isCar) 26.dp else 24.dp),
            contentColor
        )
        Spacer(Modifier.width(if (isCar) 10.dp else 8.dp))
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                label,
                color = if (selected) TuneColors.Text else contentColor,
                fontSize = if (isCar) 16.sp else 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TuneTextStyle
            )
            if (reserveDetail) {
                Box(
                    Modifier.height(16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Crossfade(
                        targetState = detail,
                        animationSpec = tween(if (animationMillis > 0) 160 else 0),
                        label = "now_playing_tool_detail"
                    ) { current ->
                        if (current != null) {
                            Text(
                                current,
                                color = TuneColors.PrimaryLight.copy(alpha = .78f),
                                fontSize = if (isCar) 13.sp else 12.sp,
                                maxLines = 1,
                                style = TuneTextStyle
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sleepRemainingLabel(remainingMillis: Long): String {
    val minutes = sleepTimerRemainingMinutes(remainingMillis)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.sleep_timer_remaining_minutes, minutes)
        rest == 0 -> stringResource(R.string.sleep_timer_remaining_hours, hours)
        else -> stringResource(R.string.sleep_timer_remaining_hours_minutes, hours, rest)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerPanel(
    isCar: Boolean,
    timer: SleepTimerState,
    onDismiss: () -> Unit,
    onPreset: (Int) -> Unit,
    onCustom: () -> Unit,
    onCancelTimer: () -> Unit
) {
    val remaining = if (timer.isActive) sleepRemainingLabel(timer.remainingMillis) else null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TuneColors.Surface,
        contentColor = TuneColors.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TuneColors.Border) }
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    listOf(
                        TuneColors.Surface,
                        TuneColors.Player.copy(alpha = .96f),
                        TuneColors.Background
                    )
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag("sleep_timer_panel")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TuneIcon(
                        R.drawable.ic_sleep,
                        Modifier.size(24.dp),
                        TuneColors.PrimaryLight
                    )
                    Text(
                        stringResource(R.string.now_playing_sleep_timer),
                        color = TuneColors.Text,
                        fontSize = if (isCar) 23.sp else 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                        style = TuneTextStyle
                    )
                }
                if (remaining != null) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        stringResource(R.string.sleep_timer_remaining_prefix, remaining),
                        color = TuneColors.PrimaryLight,
                        fontSize = 14.sp,
                        style = TuneTextStyle
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        SleepTimerPreset(
                            minutes = minutes,
                            modifier = Modifier.weight(1f),
                            onClick = { onPreset(minutes) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SleepTimerMenuRow(
                    label = stringResource(R.string.sleep_timer_custom),
                    tag = "sleep_timer_custom",
                    onClick = onCustom
                )
                if (timer.isActive) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = TuneColors.Divider
                    )
                    SleepTimerMenuRow(
                        label = stringResource(R.string.sleep_timer_cancel),
                        tag = "sleep_timer_cancel",
                        destructive = true,
                        onClick = onCancelTimer
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun SleepTimerPreset(
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.heightIn(min = 50.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        TuneColors.Raised.copy(alpha = .96f),
                        TuneColors.Player.copy(alpha = .86f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        TuneColors.PrimaryLight.copy(alpha = .28f),
                        TuneColors.BorderSoft.copy(alpha = .7f)
                    )
                ),
                shape = shape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("sleep_timer_$minutes")
            .semantics {
                role = Role.Button
                contentDescription = "$minutes 分钟"
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.sleep_timer_minutes, minutes),
            color = TuneColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            style = TuneTextStyle
        )
    }
}

@Composable
private fun SleepTimerMenuRow(
    label: String,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clip(TuneShapes.Control)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (destructive) TuneColors.Error else TuneColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            style = TuneTextStyle
        )
    }
}

@Composable
private fun CustomSleepTimerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable { mutableStateOf("") }
    val minutes = value.toIntOrNull()
    val valid = minutes != null && minutes in 1..480
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.sleep_timer_custom_body),
                    color = TuneColors.Muted,
                    fontSize = 14.sp,
                    style = TuneTextStyle
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        value = input.filter(Char::isDigit).take(3)
                    },
                    label = { Text(stringResource(R.string.sleep_timer_minutes_label)) },
                    singleLine = true,
                    isError = value.isNotBlank() && !valid,
                    supportingText = {
                        Text(
                            stringResource(
                                if (value.isNotBlank() && !valid) {
                                    R.string.sleep_timer_custom_invalid
                                } else {
                                    R.string.sleep_timer_custom_range
                                }
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("sleep_timer_custom_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(requireNotNull(minutes)) },
                modifier = Modifier.testTag("sleep_timer_custom_confirm")
            ) {
                Text(stringResource(R.string.sleep_timer_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = TuneColors.Surface,
        textContentColor = TuneColors.Text,
        titleContentColor = TuneColors.Text
    )
}
