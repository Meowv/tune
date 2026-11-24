package com.meowv.tune.ui

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.meowv.tune.R
import com.meowv.tune.domain.Station
import com.meowv.tune.ui.theme.DialogDimAmount
import com.meowv.tune.ui.theme.LocalTuneIsDark
import com.meowv.tune.ui.theme.TuneBrushes
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneMotion
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle
import com.meowv.tune.ui.theme.TuneTypography
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
internal fun TuneIcon(@DrawableRes resource: Int, modifier: Modifier = Modifier, tint: Color = TuneColors.IconIdle) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
internal fun BrandLogo(modifier: Modifier = Modifier, withPlate: Boolean = false) {
    if (withPlate && LocalTuneIsDark.current) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().background(TuneColors.PrimaryLight, CircleShape))
            Image(
                painterResource(R.drawable.ic_meowvtune_logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Image(painterResource(R.drawable.ic_meowvtune_logo), contentDescription = null, modifier = modifier)
    }
}

@Composable
internal fun BrandLockup(
    label: String,
    logoSize: Dp,
    style: TextStyle,
    modifier: Modifier = Modifier,
    logoTag: String
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small)
    ) {
        BrandLogo(Modifier.size(logoSize).testTag(logoTag))
        Text(
            label, color = TuneColors.Text, style = style,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun Modifier.controlFeedback(
    interaction: InteractionSource,
    shape: Shape,
    enabled: Boolean = true,
    durationMillis: Int = TuneMotion.Press
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        targetValue = when {
            enabled && pressed -> TuneColors.PressedOverlay
            enabled && (focused || hovered) -> TuneColors.FocusOverlay
            else -> Color.Transparent
        },
        animationSpec = tween(if (rememberMotionEnabled()) durationMillis else 0),
        label = "control_feedback"
    )
    return clip(shape).background(fill)
        .then(if (enabled && focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
}

/**
 * 播放中的动态音频波形：4 根竖条以不同周期低幅起伏。
 * 系统关闭动画（Animator duration scale = 0）时退化为静态条，避免无谓消耗。
 */
@Composable
internal fun PlayingWaveBars(
    modifier: Modifier = Modifier,
    color: Color = TuneColors.Primary,
    animationsEnabled: Boolean = true
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motionEnabled = rememberMotionEnabled()
    val fractions: List<androidx.compose.runtime.State<Float>> = if (
        animationsEnabled && motionEnabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    ) {
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

@Composable
internal fun TuneDialogWindow(isCar: Boolean) {
    val view = LocalView.current
    val dark = LocalTuneIsDark.current
    DisposableEffect(view, isCar, dark) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(DialogDimAmount)
            if (isCar) WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose { }
    }
}

@Composable
internal fun TuneLoadingIndicator(
    modifier: Modifier,
    tint: Color = TuneColors.Primary,
    strokeWidth: Dp = 2.dp,
    animationsEnabled: Boolean = true
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motion = rememberMotionEnabled()
    if (animationsEnabled && motion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
        CircularProgressIndicator(modifier, color = tint, strokeWidth = strokeWidth,
            trackColor = TuneColors.TrackOff, strokeCap = StrokeCap.Round)
    } else {
        TuneIcon(R.drawable.ic_buffering, modifier.progressSemantics(), tint)
    }
}

private const val WAVE_BAR_COUNT = 4
private val WAVE_STATIC_LEVELS = listOf(.42f, .78f, .55f, .9f)

/** 台标保持图片原比例，避免裁掉贴近边缘的文字。 */
@Composable
internal fun StationBadge(station: Station?, size: Dp) {
    MediaArtwork(
        url = station?.iconUrl,
        isMusic = false,
        modifier = Modifier.size(size).clip(TuneShapes.Station)
    )
}

private val artworkTints = listOf(Color(0xFF8771BC), Color(0xFF6988B0), Color(0xFF6C938F), Color(0xFFAD799C))

@Composable
internal fun MediaArtwork(
    url: String?,
    isMusic: Boolean,
    modifier: Modifier = Modifier,
    artworkKey: String? = null,
    radioFallback: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val duration = if (isMusic && rememberMotionEnabled()) TuneMotion.Artwork else 0
    val request = remember(context, url, duration) {
        ImageRequest.Builder(context).data(url?.takeIf(String::isNotBlank)).crossfade(duration).build()
    }
    var loaded by remember(request) { mutableStateOf(false) }
    val tint = artworkTints[Math.floorMod(artworkKey.hashCode(), artworkTints.size)]
    val raised = TuneColors.Raised
    val surface = TuneColors.Surface
    val fallbackBrush = remember(tint, raised, surface) {
        Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(raised, tint, .28f), surface))
    }
    Box(modifier.background(raised), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Box(
                Modifier.matchParentSize().then(if (isMusic) Modifier.background(fallbackBrush) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (!isMusic && radioFallback != null) radioFallback()
                else TuneIcon(
                    if (isMusic) R.drawable.ic_music else R.drawable.ic_radio,
                    Modifier.fillMaxSize(if (isMusic) .46f else .42f).testTag("media_artwork_fallback"),
                    TuneColors.PrimaryLight
                )
            }
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.matchParentSize()
                    .then(if (radioFallback != null) Modifier.testTag("circular_player_artwork") else Modifier)
                    .then(if (isMusic) Modifier else Modifier.padding(TuneSpacing.Hairline)),
                contentScale = if (isMusic) ContentScale.Crop else BadgeContentScale,
                onSuccess = { loaded = true },
                onError = { loaded = false }
            )
        }
    }
}

internal val BadgeContentScale = ContentScale.Fit

/** 品牌栏：手机 64dp / 车机 80dp，单行 Logo + 名称。 */
@Composable
internal fun MeowvTopBar(isCar: Boolean) {
    Column(Modifier.testTag("brand_header")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (isCar) 72.dp else 56.dp)
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
    val currentOnPage by rememberUpdatedState(onPage)
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 40.dp.toPx() }
    val labelHeight = with(density) { TuneTypography.meta(false).lineHeight.toDp() }
    val navHeight = maxOf(PhoneNavigationHeight, NavPillTop + NavPillHeight + labelHeight + TuneSpacing.Small)
    val duration = if (rememberMotionEnabled()) TuneMotion.Navigation else 0
    BoxWithConstraints(
        modifier.fillMaxWidth().height(navHeight).background(TuneColors.Background)
            .focusGroup().testTag("bottom_navigation")
            // 菜单条本身也能横滑：左滑下一个、右滑上一个，和内容区手势同向。
            .pointerInput(swipeThreshold) {
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
                        target?.let(currentOnPage)
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
            animationSpec = tween(duration, easing = FastOutSlowInEasing),
            label = "nav_indicator"
        )
        Box(
            Modifier.offset(x = indicatorX, y = NavPillTop)
                .size(pillWidth, NavPillHeight)
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
internal fun MeowvNavigationRail(
    page: TunePage,
    width: Dp,
    modifier: Modifier = Modifier,
    onPage: (TunePage) -> Unit
) {
    BoxWithConstraints(modifier.width(width).fillMaxHeight().background(TuneColors.Surface).testTag("side_navigation")) {
        val compact = maxHeight < 440.dp
        val narrow = width < 124.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) TuneSpacing.Small else TuneSpacing.Medium,
                    vertical = if (compact) TuneSpacing.Small else TuneSpacing.Section)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BrandLockup(
                label = stringResource(R.string.app_name),
                logoSize = if (narrow) 22.dp else 26.dp,
                style = TuneTypography.control(true).copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("car_rail_brand")
                    .padding(start = TuneSpacing.Hairline, bottom = if (compact) 0.dp else TuneSpacing.Small),
                logoTag = "car_rail_brand_logo"
            )
            TunePage.entries.forEach { item ->
                NavigationItem(item, item == page, isCar = true, Modifier.fillMaxWidth(), onPage)
            }
        }
    }
}

@Composable
internal fun MeowvBottomDock(page: TunePage, playerEntry: @Composable () -> Unit, onPage: (TunePage) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(TuneColors.Background)
            .padding(horizontal = 12.dp).testTag("bottom_dock")
    ) {
        playerEntry()
        Spacer(Modifier.height(TuneSpacing.Hairline))
        MeowvNavigationBar(page, onPage = onPage)
    }
}

/** 手机底栏共用的滑动选中背景，尺寸独立于条目的触控区域。 */
internal val PhoneNavigationHeight = 58.dp
private val NavPillWidth = 59.dp
private val NavPillHeight = 30.dp
private val NavPillTop = 6.dp

@Composable
private fun NavigationItem(
    page: TunePage,
    selected: Boolean,
    isCar: Boolean,
    modifier: Modifier,
    onPage: (TunePage) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(page.label)
    val duration = if (rememberMotionEnabled()) TuneMotion.ColorChange else 0
    val color by animateColorAsState(
        if (selected) TuneColors.PrimaryLight else TuneColors.Muted,
        animationSpec = tween(duration), label = "nav_item_color"
    )
    val background by animateColorAsState(
        if (selected && isCar) TuneColors.NavSelected else Color.Transparent,
        animationSpec = tween(duration), label = "nav_item_background"
    )
    val shape = TuneShapes.Navigation
    val resource = when (page) {
        TunePage.Radio -> R.drawable.ic_radio
        TunePage.Music -> R.drawable.ic_music
        TunePage.Settings -> R.drawable.ic_settings
    }
    Box(
        modifier.heightIn(min = if (isCar) TuneSizes.TouchCar else PhoneNavigationHeight)
            .then(if (isCar) Modifier.fillMaxWidth() else Modifier.fillMaxHeight().padding(horizontal = 6.dp))
            .background(background, shape)
            .controlFeedback(interaction, shape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = { onPage(page) }
            )
            .testTag("nav_${page.name.lowercase()}")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.then(if (isCar) Modifier.fillMaxWidth() else Modifier.fillMaxWidth())
                .padding(
                    horizontal = if (isCar) TuneSpacing.Small else 0.dp,
                    vertical = if (isCar) 10.dp else 0.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            val icon = @Composable { TuneIcon(resource, Modifier.size(TuneSizes.IconLg), color) }
            val text = @Composable {
                Text(
                    label, color = color, style = if (isCar) TuneTypography.control(true) else TuneTypography.meta(false),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { }
                )
            }
            if (isCar) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    icon(); text()
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(top = NavPillTop, bottom = TuneSpacing.Hairline),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.height(NavPillHeight), contentAlignment = Alignment.Center) { icon() }
                    Spacer(Modifier.height(2.dp))
                    text()
                }
            }
        }
    }
}

@Composable
internal fun Modifier.mediaRowHighlight(selected: Boolean, isCar: Boolean): Modifier {
    val selection by animateFloatAsState(
        if (selected) 1f else 0f,
        tween(if (rememberMotionEnabled()) TuneMotion.ColorChange else 0),
        label = "media_row_selection"
    )
    val selectedColor = TuneColors.Selected
    val primary = TuneColors.Primary
    return drawBehind {
        if (selection > 0f) {
            drawRect(selectedColor, alpha = selection)
            val width = 2.dp.toPx()
            val height = (if (isCar) 30.dp else 26.dp).toPx().coerceAtMost(size.height)
            drawRoundRect(
                primary.copy(alpha = selection),
                Offset(0f, (size.height - height) / 2f),
                Size(width, height),
                CornerRadius(width / 2f)
            )
        }
    }
}

internal fun mediaRowArtworkSize(isCar: Boolean): Dp = if (isCar) 50.dp else 44.dp

internal fun mediaRowStartPadding(isCar: Boolean): Dp = if (isCar) 12.dp else 8.dp

@Composable
internal fun MediaRowDivider(isCar: Boolean) {
    HorizontalDivider(
        modifier = Modifier.padding(start = mediaRowStartPadding(isCar) + mediaRowArtworkSize(isCar) + TuneSpacing.Medium),
        thickness = .5.dp,
        color = TuneColors.Divider.copy(alpha = .7f)
    )
}

internal fun Modifier.mediaCardDepth(isCar: Boolean, offset: () -> Float): Modifier = graphicsLayer {
    val position = offset()
    val distance = abs(position).coerceIn(0f, 1f)
    alpha = 1f - distance * (if (isCar) .57f else .6f)
    scaleX = 1f - distance * .1f
    scaleY = scaleX
    transformOrigin = TransformOrigin(if (position < 0f) 0f else 1f, .5f)
}

internal fun mediaCardSize(viewport: Dp, availableHeight: Dp, isCar: Boolean): DpSize {
    val ratio = if (isCar) 1.08f else 1.30f
    val preferredWidth = (viewport * if (isCar) .46f else .72f)
        .coerceAtMost(if (isCar) 380.dp else 352.dp)
    val height = availableHeight.coerceAtLeast(1.dp)
    val width = minOf(
        preferredWidth,
        viewport - 48.dp,
        if (isCar) height * 1.35f else height / ratio
    ).coerceAtLeast(1.dp)
    return DpSize(width, minOf(height, width * ratio))
}

@Composable
internal fun Modifier.mediaCardSurface(focused: Boolean): Modifier {
    val cardBrush = TuneBrushes.Card
    val primary = TuneColors.Primary
    val borderColor = TuneColors.Divider
    return clip(TuneShapes.Card)
        .background(cardBrush)
        .border(
            if (focused) 2.dp else 1.dp,
            if (focused) primary else borderColor,
            TuneShapes.Card
        )
}

@Composable
internal fun mediaPagerFlingBehavior(pager: PagerState) =
    PagerDefaults.flingBehavior(state = pager, snapPositionalThreshold = .30f)

@Composable
internal fun BoxScope.MediaPagerControls(pager: PagerState, onNavigate: () -> Unit) {
    val scope = rememberCoroutineScope()
    val animate = rememberMotionEnabled()
    val currentPage = pager.settledPage
    BoxWithConstraints(Modifier.matchParentSize()) {
        val padding = if (maxWidth >= 360.dp) TuneSpacing.Medium else 0.dp
        listOf(-1, 1).forEach { step ->
            val previous = step < 0
            val target = currentPage + step
            PreviewControl(
                icon = if (previous) R.drawable.ic_now_playing_previous else R.drawable.ic_now_playing_next,
                description = stringResource(if (previous) R.string.card_previous else R.string.card_next),
                targetSize = TuneSizes.TouchCar,
                visualSize = 40.dp,
                iconSize = TuneSizes.IconMd,
                modifier = Modifier.align(if (previous) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = padding),
                background = TuneColors.Surface,
                tag = if (previous) "media_card_previous" else "media_card_next",
                onClick = if (target !in 0 until pager.pageCount) null else {
                    {
                        scope.launch {
                            if (!pager.isScrollInProgress && target in 0 until pager.pageCount) {
                                onNavigate()
                                if (animate) pager.animateScrollToPage(target, animationSpec = tween(TuneMotion.Card))
                                else pager.scrollToPage(target)
                            }
                        }
                        Unit
                    }
                }
            )
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
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(targetSize).testTag(tag)
            .controlFeedback(interaction, TuneShapes.Control, onClick != null)
            .clickable(enabled = onClick != null, interactionSource = interaction, indication = null,
                role = Role.Button, onClick = { onClick?.invoke() })
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
            TuneIcon(
                icon, Modifier.requiredSize(iconSize),
                if (onClick == null) TuneColors.DisabledContent else tint
            )
        }
    }
}
