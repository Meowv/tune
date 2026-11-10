package com.meowv.tune.ui

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
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
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneTextStyle
import kotlin.math.abs
import kotlinx.coroutines.launch

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

private enum class PlayerButtonVisual { Play, Playing, Loading, Retry, Unavailable }

/** 底部播放器操作按钮的通用热区：48dp（车机 56dp），内容由调用方决定。 */
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
        Modifier.size(if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
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

/**
 * 播放器主操作按钮：48dp 热区（车机 56dp），不铺底色。
 * 与顺序/随机键、列表行保持同一套无底色图标语言；状态靠图形与颜色区分：
 * 播放/播放中为品牌紫，缓冲是品牌紫加载环，失败转错误色重试键，未选台为灰色。
 */
@Composable
private fun PlayerActionButton(
    visual: PlayerButtonVisual,
    label: String,
    enabled: Boolean,
    isCar: Boolean,
    onClick: () -> Unit
) {
    // 主操作图标比次级图标大一档，层级靠尺寸拉开，不额外铺底色。
    val glyph = if (isCar) TuneSizes.IconXl else TuneSizes.IconLg
    val tint = when (visual) {
        PlayerButtonVisual.Play, PlayerButtonVisual.Playing -> TuneColors.Primary
        PlayerButtonVisual.Loading -> TuneColors.Primary
        PlayerButtonVisual.Retry -> TuneColors.Error
        PlayerButtonVisual.Unavailable -> TuneColors.Muted.copy(alpha = TuneColors.DisabledAlpha)
    }
    MiniPlayerAction("player_pause", label, enabled, isCar, onClick) {
        Crossfade(visual, animationSpec = tween(160), label = "player_action_glyph") { state ->
            when (state) {
                PlayerButtonVisual.Loading -> CircularProgressIndicator(
                    Modifier.size(glyph - 2.dp),
                    color = tint,
                    strokeWidth = 2.dp,
                    trackColor = tint.copy(alpha = .22f),
                    strokeCap = StrokeCap.Round
                )
                // 播放中用波形表示「正在出声」，与列表行同一套状态语言。
                PlayerButtonVisual.Playing -> PlayingWaveBars(Modifier.size(glyph), tint)
                PlayerButtonVisual.Retry -> TuneIcon(
                    R.drawable.ic_refresh, Modifier.size(glyph), tint
                )
                PlayerButtonVisual.Play, PlayerButtonVisual.Unavailable -> TuneIcon(
                    R.drawable.ic_radio_play, Modifier.size(glyph), tint
                )
            }
        }
    }
}

/**
 * 台标容器：固定正方形 + 统一圆角，图片按容器边界裁切，不拉伸也不越界。
 * 缩放策略见 [BadgeContentScale]：近方形图铺满，其余完整展示。
 */
@Composable
internal fun StationBadge(station: Station?, size: Dp) {
    // 圆角随尺寸缩放，但不超过大卡片的 28dp，保证子元素始终比父容器更方。
    val radius = (size * .22f).coerceAtMost(24.dp)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius)).background(TuneColors.Raised),
        contentAlignment = Alignment.Center
    ) {
        val url = station?.iconUrl
        if (url.isNullOrBlank()) {
            TuneIcon(R.drawable.ic_radio, Modifier.size(size * .55f))
        } else {
            val fallback = painterResource(R.drawable.ic_radio)
            AsyncImage(
                url, null, Modifier.fillMaxSize(),
                placeholder = fallback, error = fallback,
                contentScale = BadgeContentScale
            )
        }
    }
}

/** 容器的正方形比例允许 10% 误差，超出后铺满就会切到可见内容。 */
private val BADGE_CROP_RANGE = .9f..1.11f

internal fun badgeScale(width: Float, height: Float): ContentScale {
    if (width <= 0f || height <= 0f) return ContentScale.Fit
    return if (width / height in BADGE_CROP_RANGE) ContentScale.Crop else ContentScale.Fit
}

/**
 * 按每张图的实际尺寸在绘制期决定缩放方式：不额外记录状态，也不触发重组，
 * 列表滚动时不会因为图片尺寸回写而抖动。
 */
private object BadgeContentScale : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor =
        badgeScale(srcSize.width, srcSize.height).computeScaleFactor(srcSize, dstSize)
}

/** 品牌栏：手机 64dp / 车机 80dp，单行 Logo + 名称。 */
@Composable
internal fun MeowvTopBar(isCar: Boolean) {
    Column(Modifier.testTag("brand_header")) {
        Row(
            Modifier.fillMaxWidth().height(if (isCar) 80.dp else 64.dp)
                .background(if (isCar) TuneColors.Surface else TuneColors.Background)
                .padding(horizontal = if (isCar) 24.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
    val items = TunePage.entries
    val index = items.indexOf(page).coerceAtLeast(0)
    // 手势块只在首次组合时启动，页码要用 rememberUpdatedState 读最新值，否则滑一次后方向会算错。
    val currentIndex by rememberUpdatedState(index)
    val swipeThreshold = with(LocalDensity.current) { 40.dp.toPx() }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(64.dp).background(TuneColors.Background)
            .focusGroup().testTag("bottom_navigation")
            // 菜单条本身也能横滑：左滑下一个、右滑上一个，和内容区手势同向。
            .pointerInput(Unit) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { change, amount ->
                        drag += amount
                        change.consume()
                    },
                    onDragEnd = {
                        val target = when {
                            drag <= -swipeThreshold -> items.getOrNull(currentIndex + 1)
                            drag >= swipeThreshold -> items.getOrNull(currentIndex - 1)
                            else -> null
                        }
                        target?.let(onPage)
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f }
                )
            }
    ) {
        val cell = maxWidth / items.size
        // 选中块在三个格子之间滑动，代替每个条目各自淡入淡出的底色。
        val indicatorX by animateDpAsState(
            targetValue = cell * index + (cell - NavPillSize) / 2,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            label = "nav_indicator"
        )
        Box(
            Modifier.offset(x = indicatorX, y = (64.dp - NavPillSize) / 2)
                .size(NavPillSize)
                .clip(TuneShapes.Navigation)
                .background(TuneColors.NavSelected)
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item ->
                NavigationItem(item, item == page, isCar = false, Modifier.weight(1f), onPage)
            }
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
            NavigationItem(item, item == page, isCar = true, Modifier.fillMaxWidth(), onPage, showBackground = true)
        }
    }
}

/** 手机底栏选中块是固定方块，三个条目共用，滑动指示块与它严格同尺寸。 */
private val NavPillSize = 48.dp

@Composable
private fun NavigationItem(
    page: TunePage,
    selected: Boolean,
    isCar: Boolean,
    modifier: Modifier,
    onPage: (TunePage) -> Unit,
    showBackground: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(page.label)
    val color by animateColorAsState(
        if (selected) TuneColors.Primary else TuneColors.Muted,
        animationSpec = tween(220), label = "nav_item_color"
    )
    val background by animateColorAsState(
        if (selected && showBackground) TuneColors.NavSelected else Color.Transparent,
        animationSpec = tween(220), label = "nav_item_background"
    )
    val shape = TuneShapes.Navigation
    val resource = when (page) {
        TunePage.Radio -> R.drawable.ic_radio
        TunePage.Music -> R.drawable.ic_music
        TunePage.Settings -> R.drawable.ic_settings
    }
    Box(
        modifier.height(if (isCar) 64.dp else 56.dp)
            .then(if (isCar) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 6.dp))
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected, role = Role.Tab, onClick = { onPage(page) })
            .testTag("nav_${page.name.lowercase()}")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        // 车机侧栏各自铺底色；手机底栏的底色由外层滑动指示块统一画，条目只留焦点描边。
        Box(
            Modifier.then(if (isCar) Modifier.fillMaxWidth() else Modifier.size(NavPillSize))
                .clip(shape)
                .background(background)
                .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
                .padding(
                    horizontal = if (isCar) 16.dp else 0.dp,
                    vertical = if (isCar) 10.dp else 0.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            val icon = @Composable { TuneIcon(resource, Modifier.size(TuneSizes.IconLg), color) }
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
                    icon(); Spacer(Modifier.height(3.dp)); text()
                }
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
    val failed = phase == PlaybackPhase.ERROR
    val canToggle = connected && station != null && !buffering
    val currentNext by rememberUpdatedState(onNext)
    val currentPrevious by rememberUpdatedState(onPrevious)
    // 可切台能力由播放队列按当前顺序给出（随机模式下有当前台即可返回）。
    val currentCanNext by rememberUpdatedState(state.canNext)
    val currentCanPrevious by rememberUpdatedState(state.canPrevious)
    val name = station?.name?.ifBlank { stringResource(R.string.station_unnamed) }
        ?: stringResource(R.string.player_unselected)
    val pauseLabel = stringResource(R.string.action_pause)
    val playLabel = stringResource(R.string.action_play)
    val retryLabel = stringResource(R.string.directory_retry)
    val bufferingLabel = stringResource(R.string.playback_buffering)
    val visual = when {
        station == null || !connected -> PlayerButtonVisual.Unavailable
        failed -> PlayerButtonVisual.Retry
        buffering -> PlayerButtonVisual.Loading
        playing -> PlayerButtonVisual.Playing
        else -> PlayerButtonVisual.Play
    }
    val buttonLabel = when (visual) {
        PlayerButtonVisual.Playing -> pauseLabel
        PlayerButtonVisual.Loading -> bufferingLabel
        PlayerButtonVisual.Retry -> retryLabel
        PlayerButtonVisual.Play, PlayerButtonVisual.Unavailable -> playLabel
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
                    start = if (isCar) 0.dp else 12.dp, end = if (isCar) 0.dp else 12.dp,
                    top = if (isCar) 0.dp else 8.dp, bottom = if (isCar) 0.dp else 8.dp
                )
                .height(if (isCar) 80.dp else 68.dp)
                // 裁到播放器轮廓：横滑时台标和台名可以滑出边缘，但不会画到播放器外面。
                .clip(if (isCar) RoundedCornerShape(0.dp) else TuneShapes.Player)
                .background(
                    TuneColors.Player,
                    if (isCar) RoundedCornerShape(0.dp) else TuneShapes.Player
                )
                .testTag("global_player")
                .padding(horizontal = if (isCar) 24.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 台标 + 台名整体横滑换台。手势挂在静止的外层，位移只写 layer，既不触发重组，
            // 也不会让节点跟着手指移动而把拖动增量抵消掉。
            val dragOffset = remember { Animatable(0f) }
            val dragScope = rememberCoroutineScope()
            val velocity = remember { VelocityTracker() }
            val density = LocalDensity.current
            val dragLimit = with(density) { 120.dp.toPx() }
            val commitPx = with(density) { 56.dp.toPx() }
            val flingPx = with(density) { 420.dp.toPx() }
            // 没有当前台时也算「可切」：滑一下直接从列表头/尾起播。
            val canSwipeNext = currentCanNext || station == null
            val canSwipePrevious = currentCanPrevious || station == null
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .testTag("player_info")
                    .pointerInput(station?.uuid) {
                        detectHorizontalDragGestures(
                            onDragStart = { velocity.resetTracking() },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                velocity.addPosition(change.uptimeMillis, change.position)
                                // 该方向没有下一台时给强阻尼，拉起来像橡皮筋，而不是撞到硬墙。
                                val available = if (amount < 0) canSwipeNext else canSwipePrevious
                                val delta = if (available) amount else amount * .3f
                                dragScope.launch {
                                    dragOffset.snapTo(
                                        (dragOffset.value + delta).coerceIn(-dragLimit, dragLimit)
                                    )
                                }
                            },
                            onDragEnd = {
                                // 位移够远或者甩得够快都算提交，快速轻扫不会没反应。
                                val flung = velocity.calculateVelocity().x
                                val settled = dragOffset.value
                                when {
                                    (settled < -commitPx || flung < -flingPx) && canSwipeNext -> currentNext()
                                    (settled > commitPx || flung > flingPx) && canSwipePrevious -> currentPrevious()
                                }
                                dragScope.launch { dragOffset.animateTo(0f, SwipeSpring) }
                            },
                            onDragCancel = { dragScope.launch { dragOffset.animateTo(0f, SwipeSpring) } }
                        )
                    }
                    .semantics {
                        // 无障碍动作与横滑同源：没有当前台时也能「下一个」起播。
                        customActions = listOf(
                            CustomAccessibilityAction(nextLabel) {
                                if (canSwipeNext) { onNext(); true } else false
                            },
                            CustomAccessibilityAction(previousLabel) {
                                if (canSwipePrevious) { onPrevious(); true } else false
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .graphicsLayer {
                            translationX = dragOffset.value
                            // 拉得越远越淡，提示「松手就换台」，同时不遮挡底下的播放器底色。
                            alpha = 1f - (abs(dragOffset.value) / dragLimit).coerceIn(0f, 1f) * .35f
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StationBadge(station, if (isCar) 52.dp else 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        name, color = TuneColors.Text, fontSize = if (isCar) 18.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = TuneTextStyle,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // 顺序/随机是模式开关：两种模式都按「选中」呈现，模式由图标本身区分，不做灰化。
            MiniPlayerAction("player_order", orderLabel, enabled = true, isCar = isCar, onClick = onToggleOrder) {
                TuneIcon(
                    if (shuffle) R.drawable.ic_order_shuffle else R.drawable.ic_order_sequential,
                    Modifier.size(if (isCar) TuneSizes.IconLg else TuneSizes.IconMd),
                    TuneColors.Primary
                )
            }
            PlayerActionButton(visual, buttonLabel, canToggle, isCar, onToggle)
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

/** 底栏横滑松手后的回弹：阻尼接近临界，只留一点点回弹，不来回摆动。 */
private val SwipeSpring = spring<Float>(
    dampingRatio = .88f,
    stiffness = Spring.StiffnessMedium
)
