package com.meowv.tune.ui

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun TuneIcon(@DrawableRes resource: Int, modifier: Modifier = Modifier, tint: Color = TuneColors.Primary) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
internal fun BrandLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_meowvtune_logo), contentDescription = null, modifier = modifier)
}

/**
 * 播放中的动态音频波形：4 根竖条以不同周期低幅起伏。
 * 系统关闭动画（Animator duration scale = 0）时退化为静态条，避免无谓消耗。
 */
@Composable
internal fun PlayingWaveBars(modifier: Modifier = Modifier, color: Color = TuneColors.Primary) {
    val context = LocalContext.current
    val motionEnabled = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val fractions: List<androidx.compose.runtime.State<Float>> = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "playing_wave")
        List(WAVE_BAR_COUNT) { index ->
            transition.animateFloat(
                initialValue = .28f, targetValue = .96f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 560 + index * 130, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wave_bar_$index"
            )
        }
    } else {
        WAVE_STATIC_LEVELS.map { remember { mutableStateOf(it) } }
    }
    Canvas(modifier) {
        val gap = size.width / (WAVE_BAR_COUNT * 2f + 1f)
        val barWidth = gap
        fractions.forEachIndexed { index, state ->
            val height = (size.height * state.value).coerceIn(size.height * .2f, size.height)
            val left = gap + index * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

private const val WAVE_BAR_COUNT = 4
private val WAVE_STATIC_LEVELS = listOf(.42f, .78f, .55f, .9f)

private enum class PlayerButtonVisual { Idle, Playing, Buffering }

/** 底部播放器操作按钮：统一 48dp（车机 56dp）扁平热区，无底色，视觉全交给图标。 */
@Composable
private fun MiniPlayerAction(
    tag: String,
    label: String,
    enabled: Boolean,
    isCar: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        Modifier.size(if (isCar) 56.dp else 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
internal fun StationBadge(station: Station?, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * .22f)).background(TuneColors.Raised),
        contentAlignment = Alignment.Center
    ) {
        if (station?.iconUrl == null) {
            TuneIcon(R.drawable.ic_radio, Modifier.size(size * .55f))
        } else {
            val fallback = painterResource(R.drawable.ic_radio)
            AsyncImage(
                station.iconUrl, null, Modifier.fillMaxSize(),
                placeholder = fallback, error = fallback, contentScale = ContentScale.Crop
            )
        }
    }
}

/** 品牌栏：手机 64dp / 车机 78dp，单行 Logo + 名称。 */
@Composable
internal fun MeowvTopBar(isCar: Boolean) {
    Column(Modifier.testTag("brand_header")) {
        Row(
            Modifier.fillMaxWidth().height(if (isCar) 78.dp else 64.dp)
                .background(if (isCar) TuneColors.Surface else TuneColors.Background)
                .padding(horizontal = if (isCar) 24.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BrandLogo(Modifier.size(if (isCar) 44.dp else 36.dp))
            Text(
                stringResource(R.string.brand_full),
                modifier = Modifier.weight(1f).testTag("brand_name"),
                color = TuneColors.Text,
                fontSize = if (isCar) 23.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
                style = TuneTextStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isCar) HorizontalDivider(color = TuneColors.Divider, thickness = .5.dp)
    }
}

@Composable
internal fun MeowvNavigationBar(page: TunePage, onPage: (TunePage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(TuneColors.Background)
            .focusGroup().testTag("bottom_navigation"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TunePage.entries.forEach { item ->
            NavigationItem(item, item == page, isCar = false, Modifier.weight(1f), onPage)
        }
    }
}

@Composable
internal fun MeowvNavigationRail(page: TunePage, onPage: (TunePage) -> Unit) {
    Column(
        Modifier.width(180.dp).fillMaxHeight().background(TuneColors.Surface)
            .verticalScroll(rememberScrollState())
            .focusGroup().testTag("side_navigation")
            .padding(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TunePage.entries.forEach { item ->
            NavigationItem(item, item == page, isCar = true, Modifier.fillMaxWidth(), onPage)
        }
    }
}

@Composable
private fun NavigationItem(
    page: TunePage,
    selected: Boolean,
    isCar: Boolean,
    modifier: Modifier,
    onPage: (TunePage) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(page.label)
    val color by animateColorAsState(
        if (selected) TuneColors.Primary else TuneColors.Muted,
        animationSpec = tween(220), label = "nav_item_color"
    )
    val background by animateColorAsState(
        if (selected) TuneColors.Selected else Color.Transparent,
        animationSpec = tween(220), label = "nav_item_background"
    )
    val shape = RoundedCornerShape(14.dp)
    val resource = when (page) {
        TunePage.Radio -> R.drawable.ic_radio
        TunePage.Music -> R.drawable.ic_music
        TunePage.Settings -> R.drawable.ic_settings
    }
    Box(
        modifier.height(if (isCar) 64.dp else 56.dp).padding(horizontal = if (isCar) 0.dp else 9.dp)
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected, role = Role.Tab, onClick = { onPage(page) })
            .testTag("nav_${page.name.lowercase()}")
            .semantics { contentDescription = label }
            .padding(horizontal = if (isCar) 16.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        val icon = @Composable { TuneIcon(resource, Modifier.size(if (isCar) 27.dp else 22.dp), color) }
        val text = @Composable {
            Text(
                label, color = color, fontSize = if (isCar) 17.sp else 11.sp, style = TuneTextStyle,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, modifier = Modifier.clearAndSetSemantics { }
            )
        }
        if (isCar) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                icon(); text()
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                icon(); Spacer(Modifier.height(4.dp)); text()
            }
        }
    }
}

/**
 * 全局播放器：只保留台标、台名与一个播放/暂停按钮。
 * 信息区横滑切台，按钮独立响应，避免误触。
 */
@Composable
internal fun MeowvMiniPlayer(
    isCar: Boolean,
    state: PlayerUiState,
    station: Station?,
    connected: Boolean,
    shuffle: Boolean,
    onToggleOrder: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val phase = state.phase
    val buffering = phase == PlaybackPhase.RESOLVING ||
        phase == PlaybackPhase.RETRYING || phase == PlaybackPhase.BUFFERING
    val playing = phase == PlaybackPhase.PLAYING
    val canToggle = connected && station != null && !buffering
    val active = connected && station != null
    val controlTint = if (active) TuneColors.Primary else TuneColors.Muted
    val thresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val currentNext by rememberUpdatedState(onNext)
    val currentPrevious by rememberUpdatedState(onPrevious)
    // 随机模式下左右滑动始终可用，由上层随机取台；顺序模式才受队列边界限制。
    val currentCanNext by rememberUpdatedState(if (shuffle) station != null else state.canNext)
    val currentCanPrevious by rememberUpdatedState(if (shuffle) station != null else state.canPrevious)
    val name = station?.name?.ifBlank { stringResource(R.string.station_unnamed) }
        ?: stringResource(R.string.player_unselected)
    val pauseLabel = stringResource(R.string.action_pause)
    val playLabel = stringResource(R.string.action_play)
    val retryLabel = stringResource(R.string.directory_retry)
    val bufferingLabel = stringResource(R.string.playback_buffering)
    val buttonLabel = when {
        buffering -> bufferingLabel
        phase == PlaybackPhase.ERROR && state.error != null -> retryLabel
        playing -> pauseLabel
        else -> playLabel
    }
    val nextLabel = stringResource(R.string.player_next)
    val previousLabel = stringResource(R.string.player_previous)
    val orderLabel = stringResource(
        if (shuffle) R.string.player_order_shuffle else R.string.player_order_sequential
    )

    Column(Modifier.fillMaxWidth().background(if (isCar) TuneColors.Player else TuneColors.Background)) {
        if (isCar) HorizontalDivider(color = TuneColors.Divider, thickness = .5.dp)
        Row(
            Modifier.fillMaxWidth()
                .padding(
                    start = if (isCar) 0.dp else 10.dp, end = if (isCar) 0.dp else 10.dp,
                    top = if (isCar) 0.dp else 8.dp, bottom = if (isCar) 0.dp else 8.dp
                )
                .height(if (isCar) 80.dp else 68.dp)
                .background(TuneColors.Player, RoundedCornerShape(if (isCar) 0.dp else 24.dp))
                .testTag("global_player")
                .padding(horizontal = if (isCar) 24.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StationBadge(station, if (isCar) 52.dp else 46.dp)
            var drag by remember { mutableStateOf(0f) }
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .testTag("player_info")
                    .pointerInput(station?.uuid) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onHorizontalDrag = { change, amount ->
                                drag += amount
                                change.consume()
                            },
                            onDragEnd = {
                                when {
                                    drag < -thresholdPx && currentCanNext -> currentNext()
                                    drag > thresholdPx && currentCanPrevious -> currentPrevious()
                                }
                                drag = 0f
                            }
                        )
                    }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(nextLabel) {
                                if (currentCanNext) { onNext(); true } else false
                            },
                            CustomAccessibilityAction(previousLabel) {
                                if (currentCanPrevious) { onPrevious(); true } else false
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    name, color = TuneColors.Text, fontSize = if (isCar) 18.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = TuneTextStyle
                )
            }
            // 右侧两个操作按钮统一为扁平图标按钮：无实心底色，仅靠图标颜色表达状态。
            MiniPlayerAction("player_order", orderLabel, enabled = true, isCar = isCar, onClick = onToggleOrder) {
                TuneIcon(
                    if (shuffle) R.drawable.ic_order_shuffle else R.drawable.ic_order_sequential,
                    Modifier.size(if (isCar) 23.dp else 21.dp),
                    if (shuffle) TuneColors.Primary else TuneColors.Muted
                )
            }
            MiniPlayerAction("player_pause", buttonLabel, enabled = canToggle, isCar = isCar, onClick = onToggle) {
                Crossfade(
                    targetState = when {
                        buffering -> PlayerButtonVisual.Buffering
                        playing -> PlayerButtonVisual.Playing
                        else -> PlayerButtonVisual.Idle
                    },
                    animationSpec = tween(180), label = "player_button_icon"
                ) { visual ->
                    // 与列表行状态一致：播放中是动态波形，缓冲是小转圈，其余为播放三角。
                    when (visual) {
                        PlayerButtonVisual.Buffering -> CircularProgressIndicator(
                            Modifier.size(if (isCar) 24.dp else 22.dp),
                            color = controlTint,
                            strokeWidth = 2.dp,
                            trackColor = TuneColors.Muted.copy(alpha = .2f)
                        )
                        PlayerButtonVisual.Playing -> PlayingWaveBars(
                            Modifier.size(if (isCar) 24.dp else 22.dp), controlTint
                        )
                        PlayerButtonVisual.Idle -> TuneIcon(
                            R.drawable.ic_play, Modifier.size(if (isCar) 26.dp else 24.dp), controlTint
                        )
                    }
                }
            }
        }
    }
}

/** 设置/列表共用的图标按钮，保留最小 48dp 热区。 */
@Composable
internal fun PreviewControl(
    @DrawableRes icon: Int,
    description: String,
    targetSize: Dp,
    visualSize: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    tint: Color = TuneColors.Text,
    tag: String = "",
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier.size(targetSize).testTag(tag)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (onClick == null) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(if (visualSize.value > 0) visualSize else iconSize)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            TuneIcon(icon, Modifier.requiredSize(iconSize), tint)
        }
    }
}
