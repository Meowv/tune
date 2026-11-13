package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.meowv.tune.R
import com.meowv.tune.domain.*
import com.meowv.tune.radio.CountryOptions
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle
import kotlinx.coroutines.delay
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.filter

@Composable
internal fun RadioScreen(
    isCar: Boolean,
    state: RadioUiState,
    playback: PlayerUiState,
    connected: Boolean,
    displayMode: RadioDisplayMode,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    onLoadMore: () -> Unit,
    onPlayback: (PlayerCommand) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onRefresh: () -> Unit,
    onScopeClick: () -> Unit,
    animationsEnabled: Boolean = true
) {
    key(state.scope, state.resolvedCountryCode) {
        RadioDirectory(
            isCar, state, playback, connected, displayMode, onDisplayModeChange,
            onLoadMore, if (connected) onPlayback else null, onSelectTarget, onRefresh, onScopeClick, animationsEnabled
        )
    }
}

@Composable
private fun RadioDirectory(
    isCar: Boolean,
    state: RadioUiState,
    playback: PlayerUiState,
    connected: Boolean,
    displayMode: RadioDisplayMode,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    onLoadMore: () -> Unit,
    onPlayback: ((PlayerCommand) -> Unit)?,
    onSelectTarget: (Station) -> Unit,
    onRefresh: () -> Unit,
    onScopeClick: () -> Unit,
    animationsEnabled: Boolean
) {
    val locale = CountryLocale
    val selectedId = state.selectedStationUuid ?: playback.station?.uuid
    Column(Modifier.fillMaxSize().testTag("page_radio")) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = if (isCar) 24.dp else 16.dp,
                end = if (isCar) 24.dp else 16.dp,
                top = if (isCar) 16.dp else 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 胶囊按内容宽度收缩，最多让出右侧切换控件的位置；长国家名在内部省略而不是挤压右边。
            RegionChip(
                scopeName(state, locale), isCar,
                Modifier.weight(1f, fill = false).padding(end = 12.dp).testTag("scope_selector"),
                onScopeClick
            )
            LayoutSwitcher(displayMode, onDisplayModeChange, isCar)
        }
        // 列表/卡片必须拿到“头部之外”的剩余高度，否则会画到播放器下面。
        // 滚动位置提到 Crossfade 之外，列表↔卡片来回切换不会回到顶部。
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(targetState = displayMode, animationSpec = tween(200), label = "display_mode") { mode ->
                when (mode) {
                    RadioDisplayMode.LIST -> StationList(
                        isCar, state, playback, selectedId, listState, onPlayback, onLoadMore, onSelectTarget, onRefresh
                    )
                    RadioDisplayMode.CARD -> CardMode(
                        isCar, state, playback, selectedId, onPlayback, onLoadMore, onSelectTarget, onRefresh,
                        connected, animationsEnabled && displayMode == RadioDisplayMode.CARD
                    )
                }
            }
        }
    }
}

@Composable
private fun StationList(
    isCar: Boolean,
    state: RadioUiState,
    playback: PlayerUiState,
    selectedId: java.util.UUID?,
    listState: LazyListState,
    onPlayback: ((PlayerCommand) -> Unit)?,
    onLoadMore: () -> Unit,
    onSelectTarget: (Station) -> Unit,
    onRefresh: () -> Unit
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    val stationsSize by rememberUpdatedState(state.stations.size)
    val hasMore by rememberUpdatedState(state.hasMore)
    val loading by rememberUpdatedState(state.isLoading)
    val error by rememberUpdatedState(state.error)
    // 只启动一次：分页完成不应重启 snapshotFlow，否则短列表会在底部连续触发多页。
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && !loading && error == null && last > 0 && last >= stationsSize - 3
        }.filter { it }.collect { loadMore() }
    }
    // 列表始终存在（用例与滚动位置都依赖它）；首次加载/切换区域时在内容区正中叠一个状态位。
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("station_list"),
            state = listState,
            contentPadding = PaddingValues(
                start = if (isCar) 24.dp else 16.dp,
                end = if (isCar) 24.dp else 16.dp,
                bottom = 12.dp
            )
        ) {
            // 已有缓存行时失败只做顶部提示，不能盖住还能播的台。
            if (state.error != null && state.stations.isNotEmpty()) item(key = "directory_error") {
                DirectoryFeedback(R.string.directory_error, onRefresh)
            }
            itemsIndexed(state.stations, key = { _, station -> station.uuid.toString() }) { index, station ->
                StationRow(
                    station = station,
                    isCar = isCar,
                    selected = station.uuid == selectedId,
                    playback = playback,
                    onPlayback = onPlayback,
                    onSelectTarget = onSelectTarget
                )
                if (index < state.stations.lastIndex) {
                    // 分割线与台名左缘对齐（行内边距 + 台标 + 间距），不压在台标下方。
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        color = TuneColors.Divider.copy(alpha = .55f), thickness = .5.dp
                    )
                }
            }
            // 翻页加载沿用列表底部的小环，不打断已加载内容。
            if (state.isLoading && state.stations.isNotEmpty()) item(key = "directory_loading") {
                DirectoryLoading(Modifier.fillMaxWidth().padding(vertical = 20.dp), size = 22.dp)
            }
        }
        if (state.stations.isEmpty()) {
            when {
                state.isLoading -> DirectoryLoading(Modifier.fillMaxSize())
                state.error != null -> DirectoryMessage(
                    R.drawable.ic_ui_info, TuneColors.Error, R.string.directory_error,
                    Modifier.fillMaxSize()
                ) {
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.directory_retry), color = TuneColors.Primary)
                    }
                }
                else -> DirectoryMessage(
                    R.drawable.ic_radio, TuneColors.Muted, R.string.directory_empty, Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    isCar: Boolean,
    selected: Boolean,
    playback: PlayerUiState,
    onPlayback: ((PlayerCommand) -> Unit)?,
    onSelectTarget: (Station) -> Unit
) {
    val title = station.name.ifBlank { stringResource(R.string.station_unnamed) }
    val isCurrent = playback.station?.uuid == station.uuid
    val phase = playback.phase
    val buffering = isCurrent && phase in BufferingPhases
    val playing = isCurrent && phase == PlaybackPhase.PLAYING
    // 整行与右侧播放键共用同一个动作，子按钮消费点击，不会重复触发：
    // 当前台是暂停/继续，其他台是换台起播。连接/缓冲中重复点击只会把正在起播的流推倒重来，一并禁用。
    val action: (() -> Unit)? = when {
        onPlayback == null || buffering -> null
        isCurrent && phase == PlaybackPhase.ERROR -> ({ onPlayback(PlayerCommand.Play(station)) })
        isCurrent -> ({ onPlayback(PlayerCommand.TogglePause) })
        else -> ({
            onSelectTarget(station)
            onPlayback(PlayerCommand.Play(station))
        })
    }
    val stateDescription = when {
        isCurrent && playing -> stringResource(R.string.playback_live)
        isCurrent && phase == PlaybackPhase.PAUSED -> stringResource(R.string.playback_paused)
        isCurrent && phase == PlaybackPhase.ERROR -> stringResource(R.string.directory_retry)
        else -> null
    }
    val actionLabel = when {
        buffering -> stringResource(R.string.playback_buffering)
        isCurrent && phase == PlaybackPhase.ERROR -> stringResource(R.string.directory_retry)
        isCurrent && playing -> stringResource(R.string.action_pause)
        else -> stringResource(R.string.action_play)
    }
    val shape = TuneShapes.Station
    // 选中背景平滑过渡：普通透明 → 淡紫高亮，左缘细紫色指示条保持不变。
    val rowBackground by animateColorAsState(
        if (selected) TuneColors.Primary.copy(alpha = .10f) else Color.Transparent,
        animationSpec = tween(220), label = "station_row_background"
    )
    // 未播放用灰紫实心三角，当前台用品牌紫实心暂停：实心比线性三角更有分量，车机上也能一眼看清。
    val tint = if (selected) TuneColors.Primary else TuneColors.IconIdle
    val glyph = TuneSizes.IconMd
    Row(
        Modifier.fillMaxWidth().height(if (isCar) 72.dp else 78.dp)
            .clip(shape)
            .background(rowBackground)
            .drawBehind {
                if (selected) {
                    val barWidth = 3.dp.toPx()
                    val barHeight = (if (isCar) 30.dp else 26.dp).toPx()
                    drawRoundRect(
                        color = TuneColors.Primary,
                        topLeft = Offset(0f, (size.height - barHeight) / 2f),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f)
                    )
                }
            }
            .selectable(
                selected = selected,
                enabled = action != null,
                role = Role.Button,
                onClick = { action?.invoke() }
            )
            .testTag("station_${station.uuid}")
            .semantics { stateDescription?.let { this.stateDescription = it } }
            .padding(start = if (isCar) 12.dp else 10.dp, end = if (isCar) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCar) 16.dp else 12.dp)
    ) {
        StationBadge(station, if (isCar) 48.dp else 54.dp)
        Text(
            title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = TuneColors.Text, fontSize = if (isCar) 20.sp else 17.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            style = TuneTextStyle
        )
        // 播放状态与选中状态分离：播放中走波形，缓冲转小圈，失败给重试键，其余一律播放键。
        // 图标不带底色，48dp 热区独立响应，涟漪只落在按钮范围内，不铺满整行。
        Box(
            Modifier.size(if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
                .clip(CircleShape)
                .then(
                    if (action != null) Modifier.clickable(role = Role.Button, onClick = action)
                    else Modifier
                )
                .testTag("station_action_${station.uuid}")
                .semantics {
                    role = Role.Button
                    contentDescription = actionLabel
                    if (action == null) disabled()
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                buffering -> CircularProgressIndicator(
                    Modifier.size(if (isCar) 20.dp else 18.dp),
                    color = TuneColors.Primary,
                    strokeWidth = 2.dp,
                    trackColor = TuneColors.Primary.copy(alpha = .22f),
                    strokeCap = StrokeCap.Round
                )
                isCurrent && phase == PlaybackPhase.ERROR -> TuneIcon(
                    R.drawable.ic_refresh, Modifier.size(glyph), TuneColors.Error
                )
                isCurrent && playing -> PlayingWaveBars(Modifier.size(glyph), TuneColors.Primary)
                else -> TuneIcon(
                    R.drawable.ic_radio_play, Modifier.size(glyph),
                    if (action == null) tint.copy(alpha = TuneColors.DisabledAlpha) else tint
                )
            }
        }
    }
}

@Composable
private fun CardMode(
    isCar: Boolean,
    state: RadioUiState,
    playback: PlayerUiState,
    selectedId: java.util.UUID?,
    onPlayback: ((PlayerCommand) -> Unit)?,
    onLoadMore: () -> Unit,
    onSelectTarget: (Station) -> Unit,
    onRefresh: () -> Unit,
    connected: Boolean,
    animationsEnabled: Boolean
) {
    if (state.stations.isEmpty()) {
        when {
            state.isLoading -> DirectoryLoading(Modifier.fillMaxSize())
            state.error != null -> DirectoryMessage(
                R.drawable.ic_ui_info, TuneColors.Error, R.string.directory_error, Modifier.fillMaxSize()
            ) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.directory_retry), color = TuneColors.Primary)
                }
            }
            else -> DirectoryMessage(
                R.drawable.ic_radio, TuneColors.Muted, R.string.directory_empty, Modifier.fillMaxSize()
            )
        }
        return
    }
    val queue = state.stations
    val pagerState = rememberPagerState(
        initialPage = queue.indexOfFirst { it.uuid == selectedId }.coerceAtLeast(0),
        pageCount = { queue.size }
    )
    var userScrollPending by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                userScrollPending = true
            }
        }
    }
    LaunchedEffect(selectedId) {
        val index = queue.indexOfFirst { it.uuid == selectedId }
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentOnPlayback by rememberUpdatedState(onPlayback)
    val currentOnSelectTarget by rememberUpdatedState(onSelectTarget)
    // 播放中（含连接/缓冲/重试）滑动后自动续播；暂停或空闲只换目标，不主动起播。
    val currentPlaying by rememberUpdatedState(playback.phase in ActivePhases)
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress || !userScrollPending) return@LaunchedEffect
        userScrollPending = false
        val page = pagerState.settledPage
        val station = queue.getOrNull(page) ?: return@LaunchedEffect
        if (station.uuid != currentSelectedId) {
            currentOnSelectTarget(station)
            if (currentPlaying) {
                // 快速连续滑动时，页变化会取消上一个协程，只有最终停留的台真正发起播放。
                delay(CARD_AUTOPLAY_DEBOUNCE_MS)
                if (currentSelectedId == station.uuid) currentOnPlayback?.invoke(PlayerCommand.Play(station))
            }
        }
    }
    LaunchedEffect(pagerState.settledPage, queue.size) {
        if (pagerState.settledPage >= queue.lastIndex - 2) onLoadMore()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusGap = if (isCar) TuneSpacing.Large else TuneSpacing.Medium
        val statusHeight = if (isCar) 28.dp else 22.dp
        val width = (if (isCar) 366.dp else 262.dp).coerceAtMost(maxWidth - 48.dp)
        // 状态区先占位，所有页面保持同高；高度吃紧时由卡片内部缩小台标。
        val height = (if (isCar) 366.dp else 308.dp)
            .coerceAtMost((maxHeight - statusHeight - statusGap - 24.dp).coerceAtLeast(0.dp))
        val side = ((maxWidth - width) / 2).coerceAtLeast(16.dp)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("station_cards"),
            pageSize = PageSize.Fixed(width),
            contentPadding = PaddingValues(horizontal = side),
            pageSpacing = 12.dp,
            key = { index -> queue[index].uuid.toString() }
        ) { page ->
            Column(
                Modifier.fillMaxHeight().width(width),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val station = queue[page]
                StationCard(
                    station = station, isCar = isCar,
                    // 电台所属国家与当前选择区域一致时不重复展示国家文字。
                    showCountry = station.countryCode != null &&
                        station.countryCode != state.resolvedCountryCode,
                    onClick = {
                        onSelectTarget(station)
                        onPlayback?.invoke(PlayerCommand.Play(station))
                    },
                    modifier = Modifier.fillMaxWidth().height(height).testTag("station_card_${station.uuid}")
                )
                Spacer(Modifier.height(statusGap))
                StationPlaybackStatus(
                    visual = stationPlaybackVisual(station.uuid, playback, connected),
                    isCar = isCar,
                    animationsEnabled = animationsEnabled && page == pagerState.settledPage &&
                        !pagerState.isScrollInProgress,
                    modifier = Modifier.fillMaxWidth().height(statusHeight)
                        .testTag("station_status_${station.uuid}")
                )
            }
        }
    }
}

@Composable
private fun StationCard(
    station: Station, isCar: Boolean, showCountry: Boolean,
    onClick: () -> Unit, modifier: Modifier
) {
    BoxWithConstraints(
        modifier.background(TuneColors.Raised, TuneShapes.Card).clickable(onClick = onClick)
    ) {
        val padding = if (maxHeight < 280.dp) TuneSpacing.Large else TuneSpacing.Section
        val nameLine = if (isCar) 32.sp else 27.sp
        val nameBlock = with(LocalDensity.current) { nameLine.toDp() * 2 }
        val countryHeight = if (showCountry && station.countryCode != null) {
            with(LocalDensity.current) { (if (isCar) 20.sp else 18.sp).toDp() } + TuneSpacing.Small
        } else 0.dp
        val artworkGap = if (isCar) TuneSpacing.Large else TuneSpacing.Medium
        val artwork = (maxHeight - padding * 2 - nameBlock - countryHeight - artworkGap)
            .coerceIn(0.dp, if (isCar) 194.dp else 148.dp)
            .coerceAtMost((maxWidth - padding * 2).coerceAtLeast(0.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (artwork >= 32.dp) {
                StationBadge(station, artwork)
                Spacer(Modifier.height(artworkGap))
            }
            // 名称固定占两行高度，状态切换不改变卡片内容位置。
            Box(Modifier.fillMaxWidth().heightIn(min = nameBlock), contentAlignment = Alignment.Center) {
                Text(
                    station.name.ifBlank { stringResource(R.string.station_unnamed) },
                    modifier = Modifier.fillMaxWidth(),
                    color = TuneColors.Text,
                    fontSize = if (isCar) 26.sp else 22.sp,
                    lineHeight = nameLine,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = TuneTextStyle
                )
            }
            if (showCountry) station.countryCode?.let {
                Spacer(Modifier.height(TuneSpacing.Small))
                Text(
                    it.displayName(CountryLocale),
                    color = TuneColors.Muted, fontSize = if (isCar) 14.sp else 12.sp,
                    lineHeight = if (isCar) 20.sp else 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = TuneTextStyle
                )
            }
        }
    }
}

@Composable
private fun RegionChip(name: String, isCar: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val description = stringResource(R.string.scope_picker_description, name)
    // 描边压到 BorderSoft：胶囊只是入口，不该比里面的国家名更显眼。
    Surface(
        onClick = onClick,
        color = TuneColors.Surface,
        shape = TuneShapes.Chip,
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) TuneColors.Primary else TuneColors.BorderSoft
        ),
        modifier = modifier.height(if (isCar) 52.dp else 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description }
    ) {
        Row(
            Modifier
                .padding(start = if (isCar) 16.dp else 14.dp, end = if (isCar) 14.dp else 10.dp)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCar) 8.dp else 6.dp)
        ) {
            TuneIcon(
                R.drawable.ic_region,
                Modifier.size(if (isCar) TuneSizes.IconMd else TuneSizes.IconSm),
                TuneColors.Primary
            )
            // fill=false：名字短时胶囊跟着收，名字长时让位给右侧箭头并省略，不挤压视图切换。
            Text(
                name, color = TuneColors.Text, fontSize = if (isCar) 17.sp else 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TuneTextStyle, modifier = Modifier.weight(1f, fill = false)
            )
            TuneIcon(
                R.drawable.ic_chevron_down,
                Modifier.size(if (isCar) 18.dp else TuneSizes.IconXs),
                TuneColors.Muted
            )
        }
    }
}

@Composable
private fun LayoutSwitcher(mode: RadioDisplayMode, onChange: (RadioDisplayMode) -> Unit, isCar: Boolean) {
    // 触控格保持 48/52dp；选中块 40/44dp，四周留 4dp 内边距，两块之间自然形成分隔。
    val cell = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch
    val tile = if (isCar) 44.dp else 40.dp
    // 两个图标同尺寸：列表是横线+点，卡片是双层菱形，22dp 下视觉面积才对齐。
    val icon = TuneSizes.IconMd
    Row(
        Modifier.clip(TuneShapes.Navigation)
            .background(TuneColors.Raised, TuneShapes.Navigation)
            // 未选中一侧只有图标，加一道极淡描边把控件边界交代清楚。
            .border(1.dp, TuneColors.BorderSoft, TuneShapes.Navigation),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(RadioDisplayMode.LIST, RadioDisplayMode.CARD).forEach { value ->
            val selected = mode == value
            val label = stringResource(
                if (value == RadioDisplayMode.LIST) R.string.layout_list else R.string.layout_card
            )
            val itemBackground by animateColorAsState(
                if (selected) TuneColors.Segment else Color.Transparent,
                animationSpec = tween(180), label = "layout_item_background"
            )
            val iconColor by animateColorAsState(
                if (selected) TuneColors.Primary else TuneColors.Muted,
                animationSpec = tween(180), label = "layout_item_icon"
            )
            Box(
                Modifier.size(cell)
                    .clickable(role = Role.Button) { onChange(value) }
                    .testTag(if (value == RadioDisplayMode.LIST) "layout_list" else "layout_card")
                    .semantics {
                        contentDescription = label
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(tile).clip(TuneShapes.Control).background(itemBackground),
                    contentAlignment = Alignment.Center
                ) {
                    TuneIcon(
                        if (value == RadioDisplayMode.LIST) R.drawable.ic_list_mode else R.drawable.ic_card_mode,
                        Modifier.size(icon),
                        iconColor
                    )
                }
            }
        }
    }
}

/** 目录加载中：只给一个品牌紫加载环，由调用方决定铺满内容区还是作为列表底部的小环。 */
@Composable
private fun DirectoryLoading(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(modifier.testTag("directory_loading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            Modifier.size(size),
            color = TuneColors.Primary,
            strokeWidth = 2.5.dp,
            trackColor = TuneColors.Primary.copy(alpha = .22f),
            strokeCap = StrokeCap.Round
        )
    }
}

/** 内容区为空时的整屏状态位：图标 + 一句话 + 可选操作，垂直居中。 */
@Composable
private fun DirectoryMessage(
    @DrawableRes icon: Int,
    tint: Color,
    @StringRes message: Int,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        TuneIcon(icon, Modifier.size(30.dp), tint)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(message), color = TuneColors.Muted, fontSize = 14.sp,
            textAlign = TextAlign.Center, style = TuneTextStyle
        )
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

@Composable
private fun DirectoryFeedback(@StringRes message: Int, onRetry: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(message), color = TuneColors.Muted,
            modifier = Modifier.weight(1f), style = TuneTextStyle, fontSize = 14.sp
        )
        if (onRetry != null) TextButton(onClick = onRetry) {
            Text(stringResource(R.string.directory_retry), color = TuneColors.Primary)
        }
    }
}

private val CountryLocale = Locale.SIMPLIFIED_CHINESE

/** 中国相关地区在区域列表里固定置顶：大陆、香港、澳门、台湾。 */
private val ChinaRegionOrder = listOf("CN", "HK", "MO", "TW")

@Composable
private fun scopeName(state: RadioUiState, locale: Locale): String = when (state.scope.mode) {
    ScopeMode.AUTO -> state.resolvedCountryCode?.let { stringResource(R.string.scope_auto_country, it.displayName(locale)) }
        ?: stringResource(if (state.isLoading || state.error != null) R.string.scope_auto else R.string.scope_global)
    ScopeMode.COUNTRY -> requireNotNull(state.scope.countryCode).displayName(locale)
    ScopeMode.GLOBAL -> stringResource(R.string.scope_global)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScopePicker(
    isCar: Boolean, windowHeight: Dp, scope: RadioScope, countries: CountryOptions,
    onRetry: () -> Unit, onDismiss: () -> Unit, onSelect: (RadioScope) -> Unit
) {
    val height = (windowHeight - 48.dp).coerceIn(0.dp, if (isCar) 490.dp else 520.dp)
    if (isCar) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val view = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(view) {
                val window = (view.parent as DialogWindowProvider).window
                WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.systemBars())
                onDispose { }
            }
            Surface(
                Modifier.widthIn(max = 460.dp).fillMaxWidth(.9f).height(height),
                shape = TuneShapes.Dialog, color = TuneColors.Surface
            ) {
                ScopeOptions(scope, countries, onRetry, onDismiss, onSelect)
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = TuneColors.Surface, contentColor = TuneColors.Text,
            dragHandle = { BottomSheetDefaults.DragHandle(color = TuneColors.Border) }
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = height)) {
                ScopeOptions(scope, countries, onRetry, onDismiss, onSelect)
            }
        }
    }
}

@Composable
private fun ScopeOptions(
    scope: RadioScope, countries: CountryOptions, onRetry: () -> Unit,
    onDismiss: () -> Unit, onSelect: (RadioScope) -> Unit
) {
    val locale = CountryLocale
    val closeLabel = stringResource(R.string.action_close)
    val sorted = remember(countries.countries, locale) {
        val collator = Collator.getInstance(locale)
        countries.countries.map { it.code to it.displayName(locale) }
            .sortedWith { a, b ->
                // 中国相关地区固定置顶，其余按中文排序；按国家码判定，不受 ICU 显示名写法影响。
                val rankA = ChinaRegionOrder.indexOf(a.first.value)
                val rankB = ChinaRegionOrder.indexOf(b.first.value)
                when {
                    rankA >= 0 && rankB >= 0 -> rankA.compareTo(rankB)
                    rankA >= 0 -> -1
                    rankB >= 0 -> 1
                    else -> collator.compare(a.second, b.second)
                }
            }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp).testTag("scope_picker")
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.scope_label), color = TuneColors.Text, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, style = TuneTextStyle,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("scope_close").semantics { contentDescription = closeLabel }
            ) {
                TuneIcon(R.drawable.ic_close, Modifier.size(22.dp), TuneColors.Muted)
            }
        }
        LazyColumn(
            Modifier.weight(1f).focusGroup().testTag("scope_options"),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "AUTO") {
                ScopeOption("Auto", stringResource(R.string.scope_auto), scope.mode == ScopeMode.AUTO) {
                    onSelect(RadioScope())
                }
            }
            item(key = "GLOBAL") {
                ScopeOption("Global", stringResource(R.string.scope_global), scope.mode == ScopeMode.GLOBAL) {
                    onSelect(RadioScope(ScopeMode.GLOBAL))
                }
            }
            if (countries.isLoading) item { DirectoryFeedback(R.string.directory_loading) }
            if (countries.failed) item { DirectoryFeedback(R.string.directory_error, onRetry) }
            if (countries.stale) item { DirectoryFeedback(R.string.directory_stale) }
            if (sorted.isEmpty() && !countries.isLoading && !countries.failed) {
                item { DirectoryFeedback(R.string.scope_empty, onRetry) }
            }
            itemsIndexed(sorted, key = { _, item -> item.first.value }) { _, item ->
                val (code, label) = item
                val option = RadioScope(ScopeMode.COUNTRY, code)
                ScopeOption(code.value, label, scope == option) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun ScopeOption(tag: String, label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Control
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) TuneColors.Raised else TuneColors.Surface)
            .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("scope_$tag").semantics { contentDescription = label }
            .padding(horizontal = TuneSpacing.Large, vertical = TuneSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = if (selected) TuneColors.Primary else TuneColors.Text, fontSize = 16.sp,
            style = TuneTextStyle, modifier = Modifier.weight(1f).clearAndSetSemantics { }
        )
        if (selected) TuneIcon(R.drawable.ic_check, Modifier.size(22.dp))
    }
}

private const val CARD_AUTOPLAY_DEBOUNCE_MS = 300L

private val BufferingPhases = setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING, PlaybackPhase.BUFFERING)
private val ActivePhases = setOf(
    PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING
)
