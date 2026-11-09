package com.meowv.tune.ui

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
import androidx.compose.foundation.selection.selectable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowv.tune.R
import com.meowv.tune.domain.*
import com.meowv.tune.radio.CountryOptions
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle
import kotlinx.coroutines.delay
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
internal fun RadioScreen(
    isCar: Boolean,
    radio: StateFlow<RadioUiState>,
    playback: PlayerUiState,
    connected: Boolean,
    displayMode: RadioDisplayMode,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    onLoadMore: () -> Unit,
    onPlayback: (PlayerCommand) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onRefresh: () -> Unit,
    onScopeClick: () -> Unit
) {
    val state by radio.collectAsStateWithLifecycle()
    key(state.scope, state.resolvedCountryCode) {
        RadioDirectory(
            isCar, state, playback, connected, displayMode, onDisplayModeChange,
            onLoadMore, if (connected) onPlayback else null, onSelectTarget, onRefresh, onScopeClick
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
    onScopeClick: () -> Unit
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            RegionChip(scopeName(state, locale), isCar, Modifier.testTag("scope_selector"), onScopeClick)
            Spacer(Modifier.weight(1f))
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
                        isCar, state, playback, selectedId, onPlayback, onLoadMore, onSelectTarget, onRefresh
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
    LaunchedEffect(listState, state.stations.size, state.hasMore, state.isLoading, state.error) {
        if (state.hasMore && !state.isLoading && state.error == null) {
            snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last > 0 && last >= state.stations.size - 3
            }.filter { it }.collect { loadMore() }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("station_list"),
        state = listState,
        contentPadding = PaddingValues(
            start = if (isCar) 24.dp else 16.dp,
            end = if (isCar) 24.dp else 16.dp,
            bottom = 12.dp
        )
    ) {
        if (state.error != null) item(key = "directory_error") {
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
        when {
            state.isLoading -> item(key = "directory_loading") { DirectoryFeedback(R.string.directory_loading) }
            state.error == null && state.stations.isEmpty() -> item(key = "directory_empty") {
                DirectoryFeedback(R.string.directory_empty)
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
    val action: (() -> Unit)? = when {
        onPlayback == null -> null
        // 连接/缓冲中重复点击只会把正在起播的流推倒重来，热区保持禁用。
        isCurrent && phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING, PlaybackPhase.BUFFERING) -> null
        isCurrent && phase == PlaybackPhase.PLAYING -> null
        isCurrent && phase == PlaybackPhase.PAUSED -> ({ onPlayback(PlayerCommand.TogglePause) })
        isCurrent && phase == PlaybackPhase.ERROR -> ({ onPlayback(PlayerCommand.TogglePause) })
        else -> ({
            onSelectTarget(station)
            onPlayback(PlayerCommand.Play(station))
        })
    }
    val stateDescription = when {
        isCurrent && phase == PlaybackPhase.PLAYING -> stringResource(R.string.playback_live)
        isCurrent && phase == PlaybackPhase.PAUSED -> stringResource(R.string.playback_paused)
        isCurrent && phase == PlaybackPhase.ERROR -> stringResource(R.string.directory_retry)
        else -> null
    }
    val shape = TuneShapes.Station
    // 选中背景平滑过渡：普通透明 → 淡紫高亮，左缘细紫色指示条保持不变。
    val rowBackground by animateColorAsState(
        if (selected) TuneColors.Primary.copy(alpha = .14f) else Color.Transparent,
        animationSpec = tween(220), label = "station_row_background"
    )
    val buffering = isCurrent &&
        phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING, PlaybackPhase.BUFFERING)
    val playing = isCurrent && phase == PlaybackPhase.PLAYING
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
            .padding(horizontal = if (isCar) 12.dp else 10.dp),
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
        // 播放状态与选中状态分离：波形只在真正播放时出现，缓冲转小圈，失败给重试键，其余一律播放键。
        Box(
            Modifier.size(if (isCar) 26.dp else 24.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                playing -> PlayingWaveBars(Modifier.size(if (isCar) 24.dp else 22.dp), TuneColors.Primary)
                buffering -> CircularProgressIndicator(
                    Modifier.size(if (isCar) 20.dp else 18.dp),
                    color = TuneColors.Primary,
                    strokeWidth = 2.dp,
                    trackColor = TuneColors.Primary.copy(alpha = .22f),
                    strokeCap = StrokeCap.Round
                )
                isCurrent && phase == PlaybackPhase.ERROR -> TuneIcon(
                    R.drawable.ic_refresh,
                    Modifier.size(if (isCar) 22.dp else 20.dp),
                    TuneColors.Error
                )
                else -> TuneIcon(
                    R.drawable.ic_play,
                    Modifier.size(if (isCar) 24.dp else 22.dp),
                    if (selected) TuneColors.Primary else TuneColors.Muted
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
    onRefresh: () -> Unit
) {
    if (state.stations.isEmpty()) {
        when {
            state.isLoading -> DirectoryFeedback(R.string.directory_loading)
            state.error != null -> DirectoryFeedback(R.string.directory_error, onRefresh)
            else -> DirectoryFeedback(R.string.directory_empty)
        }
        return
    }
    val queue = state.stations
    val pagerState = rememberPagerState(
        initialPage = queue.indexOfFirst { it.uuid == selectedId }.coerceAtLeast(0),
        pageCount = { queue.size }
    )
    LaunchedEffect(selectedId) {
        val index = queue.indexOfFirst { it.uuid == selectedId }
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentOnPlayback by rememberUpdatedState(onPlayback)
    val currentOnSelectTarget by rememberUpdatedState(onSelectTarget)
    // 播放中（含连接/缓冲/重试）滑动后自动续播；暂停或空闲只换目标，不主动起播。
    val currentPlaying by rememberUpdatedState(
        playback.phase in setOf(
            PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING,
            PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING
        )
    )
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        val station = queue.getOrNull(page) ?: return@LaunchedEffect
        if (station.uuid != currentSelectedId) {
            currentOnSelectTarget(station)
            if (currentPlaying) {
                // 快速连续滑动时，页变化会取消上一个协程，只有最终停留的台真正发起播放。
                delay(CARD_AUTOPLAY_DEBOUNCE_MS)
                currentOnPlayback?.invoke(PlayerCommand.Play(station))
            }
        }
        if (page >= queue.lastIndex - 2) onLoadMore()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 卡片尺寸整体收敛约 6%，台标随卡片高度再缩 10% 左右，四周保留呼吸空间。
        val width = (if (isCar) 366.dp else 262.dp).coerceAtMost(maxWidth - 48.dp)
        val height = (if (isCar) 366.dp else 308.dp).coerceAtMost(maxHeight - 24.dp)
        // 高度吃紧时先缩台标，给两行名称和国家行留位置，避免文字被裁。
        val artwork = (height - 48.dp - 112.dp).coerceIn(88.dp, if (isCar) 194.dp else 148.dp)
        val side = ((maxWidth - width) / 2).coerceAtLeast(16.dp)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("station_cards"),
            pageSize = PageSize.Fixed(width),
            contentPadding = PaddingValues(horizontal = side),
            pageSpacing = 12.dp,
            key = { index -> queue[index].uuid.toString() }
        ) { page ->
            // 卡片在可视区内垂直居中，避免旧版头重脚轻的大面积上下留白。
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                val station = queue[page]
                StationCard(
                    station = station, isCar = isCar, artwork = artwork,
                    // 电台所属国家与当前选择区域一致时不重复展示国家文字。
                    showCountry = station.countryCode != null &&
                        station.countryCode != state.resolvedCountryCode,
                    onClick = {
                        onSelectTarget(station)
                        onPlayback?.invoke(PlayerCommand.Play(station))
                    },
                    modifier = Modifier.width(width).height(height)
                )
            }
        }
    }
}

@Composable
private fun StationCard(
    station: Station, isCar: Boolean, artwork: Dp, showCountry: Boolean,
    onClick: () -> Unit, modifier: Modifier
) {
    Column(
        modifier.background(TuneColors.Raised, TuneShapes.Card).clickable(onClick = onClick)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StationBadge(station, artwork)
        Spacer(Modifier.height(if (isCar) 20.dp else 16.dp))
        // 名称固定占两行高度：短名居中不上下跳动，长名在第二行末尾省略。
        val nameLine = if (isCar) 32.sp else 27.sp
        val nameBlock = with(LocalDensity.current) { (nameLine.value * fontScale).dp * 2 }
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
            Spacer(Modifier.height(8.dp))
            Text(
                it.displayName(CountryLocale),
                color = TuneColors.Muted, fontSize = if (isCar) 14.sp else 12.sp, style = TuneTextStyle
            )
        }
    }
}

@Composable
private fun RegionChip(name: String, isCar: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val description = stringResource(R.string.scope_picker_description, name)
    Surface(
        onClick = onClick,
        color = TuneColors.Surface,
        shape = TuneShapes.Chip,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) TuneColors.Primary else TuneColors.Border),
        modifier = modifier.height(if (isCar) 52.dp else 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description }
    ) {
        Row(
            Modifier.padding(horizontal = if (isCar) 16.dp else 12.dp).clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TuneIcon(R.drawable.ic_region, Modifier.size(if (isCar) 20.dp else 18.dp))
            Text(
                name, color = TuneColors.Text, fontSize = if (isCar) 17.sp else 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, style = TuneTextStyle
            )
            TuneIcon(R.drawable.ic_chevron_down, Modifier.size(16.dp), TuneColors.Muted)
        }
    }
}

@Composable
private fun LayoutSwitcher(mode: RadioDisplayMode, onChange: (RadioDisplayMode) -> Unit, isCar: Boolean) {
    // 触控格 48/52dp，内部视觉块 40/44dp：外观不变，热区达到最小触控尺寸。
    val cell = if (isCar) 52.dp else 48.dp
    val tile = if (isCar) 44.dp else 40.dp
    Row(
        Modifier.clip(TuneShapes.Navigation).background(TuneColors.Surface, TuneShapes.Navigation),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(RadioDisplayMode.LIST, RadioDisplayMode.CARD).forEach { value ->
            val selected = mode == value
            val label = stringResource(
                if (value == RadioDisplayMode.LIST) R.string.layout_list else R.string.layout_card
            )
            val itemBackground by animateColorAsState(
                if (selected) TuneColors.Selected else Color.Transparent,
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
                        Modifier.size(if (isCar) 24.dp else 20.dp),
                        iconColor
                    )
                }
            }
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
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
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
