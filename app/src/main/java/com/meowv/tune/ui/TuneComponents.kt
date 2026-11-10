package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
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
import kotlinx.coroutines.launch

@Composable
internal fun TuneIcon(@DrawableRes resource: Int, modifier: Modifier = Modifier, tint: Color = TuneColors.Primary) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
internal fun BrandLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_meowvtune_logo), contentDescription = null, modifier = modifier)
}

private enum class PlayerButtonVisual { Play, Pause, Loading, Retry, Unavailable }

/** 底部播放器操作按钮的通用热区：48dp（车机 56dp），内容由调用方决定。 */
@Composable
private fun MiniPlayerAction(
    tag: String,
    label: String,
    enabled: Boolean,
    isCar: Boolean,
    onClick: () -> Unit,
    surface: Color = Color.Transparent,
    surfaceSize: Dp = if (isCar) 48.dp else 44.dp,
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
        Box(
            Modifier.size(surfaceSize).background(surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/**
 * 播放器主操作按钮：48dp 热区（车机 56dp），用极低对比度的圆形底承载主操作。
 * 与随机键、列表行保持同一套图标语言；状态靠图形与颜色区分：
 * 播放/暂停为品牌紫，缓冲是品牌紫加载环，失败转错误色重试键，未选台为灰色。
 */
@Composable
private fun PlayerActionButton(
    visual: PlayerButtonVisual,
    label: String,
    enabled: Boolean,
    isCar: Boolean,
    onClick: () -> Unit
) {
    // 主操作图标比次级图标大一档，靠尺寸和底色拉开层级，不用高饱和实心圆。
    val glyph = if (isCar) TuneSizes.IconXl else TuneSizes.IconLg
    val tint = when (visual) {
        PlayerButtonVisual.Play, PlayerButtonVisual.Pause -> TuneColors.Primary
        PlayerButtonVisual.Loading -> TuneColors.Primary
        PlayerButtonVisual.Retry -> TuneColors.Error
        PlayerButtonVisual.Unavailable -> TuneColors.Muted.copy(alpha = TuneColors.DisabledAlpha)
    }
    val surface = when (visual) {
        PlayerButtonVisual.Play, PlayerButtonVisual.Pause -> TuneColors.Primary.copy(alpha = .14f)
        else -> Color.Transparent
    }
    MiniPlayerAction(
        "player_pause", label, enabled, isCar, onClick,
        surface = surface, surfaceSize = if (isCar) 48.dp else 44.dp
    ) {
        Crossfade(visual, animationSpec = tween(160), label = "player_action_glyph") { state ->
            when (state) {
                PlayerButtonVisual.Loading -> CircularProgressIndicator(
                    Modifier.size(glyph - 2.dp),
                    color = tint,
                    strokeWidth = 2.dp,
                    trackColor = tint.copy(alpha = .22f),
                    strokeCap = StrokeCap.Round
                )
                PlayerButtonVisual.Pause -> TuneIcon(
                    R.drawable.ic_radio_pause, Modifier.size(glyph), tint
                )
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
    // 64dp 只是让出上下呼吸位；条目本身仍是 56dp，热区不缩水。
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(TuneColors.Background)
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
        if (selected) TuneColors.NavSelected else Color.Transparent,
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
        // 选中底只包住图标与文字，宽度比整格小一截；外框仍是完整的 56/64dp 热区。
        Box(
            Modifier.then(if (isCar) Modifier.fillMaxWidth() else Modifier)
                .clip(shape)
                .background(background)
                .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
                .padding(
                    horizontal = if (isCar) 16.dp else 12.dp,
                    vertical = if (isCar) 10.dp else 4.dp
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
    val thresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
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
        playing -> PlayerButtonVisual.Pause
        else -> PlayerButtonVisual.Play
    }
    val buttonLabel = when (visual) {
        PlayerButtonVisual.Pause -> pauseLabel
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
                .background(
                    TuneColors.Player,
                    if (isCar) RoundedCornerShape(0.dp) else TuneShapes.Player
                )
                .testTag("global_player")
                .padding(horizontal = if (isCar) 24.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StationBadge(station, if (isCar) 52.dp else 46.dp)
            // 横滑只作用在信息区：位移画在文字层，热区不动，避免和右侧按钮抢手势。
            val dragOffset = remember { Animatable(0f) }
            // 位移上限必须大于换台阈值，否则快速滑动会被夹住而永远达不到阈值。
            val dragLimit = with(LocalDensity.current) { 96.dp.toPx() }
            val scope = rememberCoroutineScope()
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .testTag("player_info")
                    .pointerInput(station?.uuid) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                // 越界即夹住，松手后回弹到原位；位移只读 layer，不触发重组。
                                scope.launch {
                                    dragOffset.snapTo(
                                        (dragOffset.value + amount).coerceIn(-dragLimit, dragLimit)
                                    )
                                }
                            },
                            onDragEnd = {
                                val settled = dragOffset.value
                                when {
                                    settled < -thresholdPx && currentCanNext -> currentNext()
                                    settled > thresholdPx && currentCanPrevious -> currentPrevious()
                                }
                                scope.launch { dragOffset.animateTo(0f, DragSpring) }
                            },
                            onDragCancel = { scope.launch { dragOffset.animateTo(0f, DragSpring) } }
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
                    overflow = TextOverflow.Ellipsis, style = TuneTextStyle,
                    modifier = Modifier.graphicsLayer { translationX = dragOffset.value }
                )
            }
            // 顺序开关是模式开关：关=次级色，开=品牌紫；播放键是主操作，用低对比度圆底承载。
            MiniPlayerAction("player_order", orderLabel, enabled = true, isCar = isCar, onClick = onToggleOrder) {
                TuneIcon(
                    if (shuffle) R.drawable.ic_order_shuffle else R.drawable.ic_order_sequential,
                    Modifier.size(if (isCar) TuneSizes.IconLg else TuneSizes.IconMd),
                    if (shuffle) TuneColors.Primary else TuneColors.Muted
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

/** 底栏横滑松手后的回弹：略带回弹但不来回摆动，避免干扰正在看的台名。 */
private val DragSpring = spring<Float>(
    dampingRatio = .85f,
    stiffness = Spring.StiffnessMediumLow
)
