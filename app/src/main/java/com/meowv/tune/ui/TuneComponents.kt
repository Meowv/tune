package com.meowv.tune.ui

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.meowv.tune.domain.Station
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
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
    val motionEnabled = rememberMotionEnabled()
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

@Composable
internal fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }
}

private const val WAVE_BAR_COUNT = 4
private val WAVE_STATIC_LEVELS = listOf(.42f, .78f, .55f, .9f)

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
internal object BadgeContentScale : ContentScale {
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
internal fun MeowvNavigationBar(page: TunePage, modifier: Modifier = Modifier, onPage: (TunePage) -> Unit) {
    val items = TunePage.entries
    val index = items.indexOf(page).coerceAtLeast(0)
    // 手势块只在首次组合时启动，页码要用 rememberUpdatedState 读最新值，否则滑一次后方向会算错。
    val currentIndex by rememberUpdatedState(index)
    val swipeThreshold = with(LocalDensity.current) { 40.dp.toPx() }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(64.dp).background(TuneColors.Background)
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
        val pillWidth = (cell - 12.dp).coerceIn(0.dp, NavPillWidth)
        // 选中块在三个格子之间滑动，代替每个条目各自淡入淡出的底色。
        val indicatorX by animateDpAsState(
            targetValue = cell * index + (cell - pillWidth) / 2,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            label = "nav_indicator"
        )
        Box(
            Modifier.offset(x = indicatorX, y = (64.dp - NavPillHeight) / 2)
                .size(pillWidth, NavPillHeight)
                .clip(TuneShapes.Navigation)
                .background(TuneColors.NavSelected)
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item ->
                NavigationItem(item, item == page, isCar = false, Modifier.weight(1f), onPage,
                    indicatorWidth = pillWidth)
            }
        }
    }
}

@Composable
internal fun MeowvNavigationRail(
    page: TunePage,
    playerEntry: @Composable () -> Unit,
    onPage: (TunePage) -> Unit
) {
    BoxWithConstraints(Modifier.width(180.dp).fillMaxHeight().background(TuneColors.Surface).testTag("side_navigation")) {
        val compact = maxHeight < 440.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)
            ) {
                TunePage.entries.forEach { item ->
                    NavigationItem(item, item == page, isCar = true, Modifier.fillMaxWidth(), onPage, showBackground = true)
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { playerEntry() }
        }
    }
}

@Composable
internal fun MeowvBottomDock(page: TunePage, playerEntry: @Composable () -> Unit, onPage: (TunePage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(TuneColors.Background)
            .padding(horizontal = 12.dp).testTag("bottom_dock"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { playerEntry() }
        Spacer(Modifier.width(8.dp))
        MeowvNavigationBar(page, Modifier.weight(1f), onPage)
    }
}

/** 手机底栏共用的滑动选中背景，尺寸独立于条目的触控区域。 */
private val NavPillWidth = 64.dp
private val NavPillHeight = 52.dp

@Composable
private fun NavigationItem(
    page: TunePage,
    selected: Boolean,
    isCar: Boolean,
    modifier: Modifier,
    onPage: (TunePage) -> Unit,
    showBackground: Boolean = false,
    indicatorWidth: Dp = NavPillWidth
) {
    val interaction = remember { MutableInteractionSource() }
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
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = if (isCar) LocalIndication.current else null,
                role = Role.Tab,
                onClick = { onPage(page) }
            )
            .testTag("nav_${page.name.lowercase()}")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        // 手机按压与焦点反馈和滑动选中块同尺寸，整格仍保持原有触控区域。
        if (!isCar) {
            Box(
                Modifier.size(indicatorWidth, NavPillHeight).clip(shape)
                    .indication(interaction, ripple(bounded = false, color = TuneColors.Primary))
                    .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
            )
        }
        Box(
            Modifier.then(if (isCar) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .clip(shape)
                .background(background)
                .then(if (isCar && focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
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
