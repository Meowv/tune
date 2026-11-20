package com.meowv.tune.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioDisplayMode
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicQueueSeed
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.canStartPlayback
import com.meowv.tune.music.MusicFeed
import com.meowv.tune.music.MusicUiState
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
internal fun MusicScreen(
    isCar: Boolean,
    state: MusicUiState,
    browse: MusicFeed,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    onSearch: () -> Unit,
    onLoad: () -> Unit,
    animationsEnabled: Boolean = true
) {
    val playback = (state as? MusicUiState.Active)?.playback
    val currentId = playback?.music?.id
    val visual = playback?.let { playbackVisual(it.phase, connected) } ?: StationPlaybackVisual.None
    val playbackError = playback?.takeIf { it.error == null }?.let { musicPlaybackErrorText(it) }
    val waitingForAccess = browse.failure == MusicFailure.NOT_CONFIGURED
    val list = rememberLazyGridState()
    var mode by rememberSaveable { mutableStateOf(RadioDisplayMode.LIST) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var listSelectionId by rememberSaveable { mutableStateOf<String?>(null) }
    val listTrackToCenter = listSelectionId?.takeIf {
        it == currentId && playback?.phase == PlaybackPhase.PLAYING
    }

    LaunchedEffect(currentId, playback?.phase, playback?.operationFailure) {
        if (playback?.phase == PlaybackPhase.ERROR || playback?.operationFailure != null ||
            playback?.phase == PlaybackPhase.PLAYING && currentId != listSelectionId) {
            listSelectionId = null
        }
    }

    Surface(color = TuneColors.Background, modifier = Modifier.fillMaxSize().testTag("page_music")) {
        Column(Modifier.fillMaxSize()) {
            if (state != MusicUiState.NotConfigured) {
                DirectoryToolbar(isCar) {
                    val search = stringResource(R.string.music_search)
                    DirectoryActionChip(search, search, R.drawable.ic_ui_search, isCar,
                        Modifier.weight(1f, fill = false).padding(end = TuneSpacing.Medium)
                            .testTag("music_search_open"), onClick = onSearch)
                    LayoutSwitcher(mode, { mode = it }, isCar)
                }
            }

            val statusModifier = Modifier.weight(1f).fillMaxWidth()
            if (browse.tracks.isEmpty()) {
                when {
                    state == MusicUiState.NotConfigured || waitingForAccess -> Unit
                    browse.failure != null -> DirectoryMessage(
                        R.drawable.ic_ui_info, TuneColors.Error, musicPlaybackFailureMessage(browse.failure), statusModifier
                    ) {
                        TextButton(onClick = onLoad) { Text(stringResource(R.string.music_retry)) }
                    }
                    browse.loaded -> DirectoryMessage(
                        R.drawable.ic_music, TuneColors.Muted, R.string.music_empty, statusModifier
                    ) {
                        TextButton(onClick = onSearch) { Text(stringResource(R.string.music_search)) }
                    }
                    else -> DirectoryLoading(statusModifier, tag = "music_feed_loading")
                }
                return@Column
            }

            val clickTrack: (MusicTrack) -> Unit = { song ->
                if (song.id == currentId && visual in setOf(StationPlaybackVisual.Playing, StationPlaybackVisual.Paused)) {
                    onCommand(PlayerCommand.TogglePause)
                } else {
                    listSelectionId = song.id.takeIf { mode == RadioDisplayMode.LIST }
                    onCommand(PlayerCommand.PlayMusic(song, browse.seed ?: MusicQueueSeed(browse.tracks, null)))
                }
            }
            if (browse.failure != null && !waitingForAccess) {
                DirectoryFeedback(musicPlaybackFailureMessage(browse.failure), onLoad)
            }

            if (mode == RadioDisplayMode.CARD) {
                MusicCards(browse.tracks, currentId, visual, playbackError, connected, isCar,
                    previewId, listTrackToCenter, { id ->
                        previewId = id
                        if (id == listTrackToCenter) listSelectionId = null
                    }, animationsEnabled, statusModifier, clickTrack)
                if (browse.loading) DirectoryLoading(Modifier.fillMaxWidth().padding(bottom = TuneSpacing.Small),
                    size = 22.dp, tag = "music_feed_loading")
            } else {
                val horizontal = if (isCar) 24.dp else 16.dp
                BoxWithConstraints(statusModifier) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(directoryColumnCount(isCar, maxWidth.value, maxHeight.value)),
                        modifier = Modifier.fillMaxSize().testTag("music_feed"),
                        state = list,
                        contentPadding = PaddingValues(start = horizontal, end = horizontal, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)
                    ) {
                        items(browse.tracks.size, key = { browse.tracks[it].id }) { index ->
                            val track = browse.tracks[index]
                            val current = track.id == currentId
                            val rowVisual = if (current) visual else StationPlaybackVisual.None
                            Column {
                                MusicTrackRow(track, isCar, current, rowVisual,
                                    enabled = connected && !rowVisual.busy, error = playbackError.takeIf { current },
                                    animationsEnabled = animationsEnabled,
                                    modifier = Modifier.testTag("music_feed_track_${track.id}")) {
                                    clickTrack(track)
                                }
                                if (index < browse.tracks.lastIndex) MusicTrackDivider()
                            }
                        }
                        if (browse.loading) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "music_feed_loading") {
                            DirectoryLoading(Modifier.fillMaxWidth().padding(vertical = TuneSpacing.Medium),
                                size = 22.dp, tag = "music_feed_loading")
                        }
                    }
                }
            }
        }
    }
}

private val StationPlaybackVisual.busy: Boolean
    get() = this == StationPlaybackVisual.Connecting || this == StationPlaybackVisual.Buffering ||
        this == StationPlaybackVisual.Retrying

@Composable
private fun MusicCards(
    tracks: List<MusicTrack>,
    currentId: String?,
    visual: StationPlaybackVisual,
    playbackError: String?,
    connected: Boolean,
    isCar: Boolean,
    previewId: String?,
    listTrackToCenter: String?,
    onPreview: (String) -> Unit,
    animationsEnabled: Boolean,
    modifier: Modifier,
    onPlay: (MusicTrack) -> Unit
) {
    val currentIndex = tracks.indexOfFirst { it.id == currentId }
    val retainedIndex = tracks.indexOfFirst { it.id == previewId }
    val prioritizeCurrent = listTrackToCenter != null && currentIndex >= 0
    val pager = rememberPagerState(
        initialPage = when {
            prioritizeCurrent -> currentIndex
            retainedIndex >= 0 -> retainedIndex
            else -> currentIndex.coerceAtLeast(0)
        }
    ) { tracks.size }
    val animate = rememberMotionEnabled()
    val reportPreview by rememberUpdatedState(onPreview)
    var observedPlaybackId by remember { mutableStateOf(currentId) }

    LaunchedEffect(pager, tracks, currentId, listTrackToCenter) {
        if (prioritizeCurrent) snapshotFlow { !pager.isScrollInProgress }.first { it }
        val followsPlayback = currentId != observedPlaybackId && previewId == observedPlaybackId && currentIndex >= 0
        observedPlaybackId = currentId
        if (!pager.isScrollInProgress) {
            val target = when {
                prioritizeCurrent || followsPlayback -> currentIndex
                retainedIndex >= 0 -> retainedIndex
                else -> currentIndex.coerceAtLeast(0)
            }
            if (pager.settledPage != target) {
                if (followsPlayback && animate && animationsEnabled) pager.animateScrollToPage(target)
                else pager.scrollToPage(target)
            }
        }
        // Pager 的定位和滑动只保存预览，用户点卡片才发送播放命令。
        snapshotFlow { if (pager.isScrollInProgress) null else tracks.getOrNull(pager.settledPage)?.id }
            .filterNotNull().collect { reportPreview(it) }
    }

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val statusHeight = if (isCar) 28.dp else 22.dp
        val statusGap = if (isCar) TuneSpacing.Large else TuneSpacing.Medium
        val viewport = if (isCar) maxWidth else minOf(maxWidth, 400.dp)
        val peek = if (tracks.size > 1) 16.dp else 0.dp
        val width = if (isCar) minOf(366.dp, (viewport - 48.dp).coerceAtLeast(1.dp))
            else (viewport - (peek + TuneSpacing.Medium) * 2).coerceAtLeast(1.dp)
        val height = minOf(if (isCar) 366.dp else 340.dp, width * 1.18f,
            (maxHeight - statusHeight - statusGap - TuneSpacing.Section).coerceAtLeast(1.dp))
        HorizontalPager(
            state = pager,
            pageSize = PageSize.Fixed(width),
            contentPadding = PaddingValues(horizontal = (viewport - width) / 2),
            pageSpacing = TuneSpacing.Medium,
            key = { tracks[it].id },
            modifier = Modifier.width(viewport).fillMaxHeight().testTag("music_cards")
        ) { index ->
            val track = tracks[index]
            val current = track.id == currentId
            val cardVisual = if (current) visual else StationPlaybackVisual.None
            val fullyVisible by remember(pager, index) {
                derivedStateOf {
                    val layout = pager.layoutInfo
                    val page = layout.visiblePagesInfo.firstOrNull { it.index == index }
                    page != null && page.offset >= layout.viewportStartOffset &&
                        page.offset + layout.pageSize <= layout.viewportEndOffset
                }
            }
            val showContent = fullyVisible || pager.isScrollInProgress
            Box(Modifier.fillMaxHeight().width(width), contentAlignment = BiasAlignment(0f, -.12f)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(height)) {
                        val cardTag = if (showContent) Modifier.testTag("music_feed_card_${track.id}") else Modifier
                        MusicCard(track, isCar, current, cardVisual, connected && !cardVisual.busy,
                            interactive = fullyVisible && !pager.isScrollInProgress,
                            modifier = Modifier.matchParentSize().then(cardTag)
                                .graphicsLayer {
                                    alpha = if (showContent) 1f else .4f
                                    scaleX = if (showContent) 1f else .92f
                                    scaleY = scaleX
                                }
                                .semantics {
                                    if (fullyVisible && index == pager.settledPage) selected = true
                                }
                        ) { onPlay(track) }
                    }
                    Spacer(Modifier.height(statusGap))
                    when {
                        !fullyVisible -> Spacer(Modifier.height(statusHeight))
                        current && playbackError != null -> Text(
                            playbackError, color = TuneColors.Error, textAlign = TextAlign.Center,
                            fontSize = if (isCar) 14.sp else 13.sp, style = TuneTextStyle,
                            modifier = Modifier.fillMaxWidth().heightIn(min = statusHeight)
                        )
                        else -> StationPlaybackStatus(
                            cardVisual, isCar,
                            animationsEnabled = animationsEnabled && !pager.isScrollInProgress,
                            modifier = Modifier.fillMaxWidth().height(statusHeight)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicCard(
    track: MusicTrack,
    isCar: Boolean,
    current: Boolean,
    visual: StationPlaybackVisual,
    enabled: Boolean,
    interactive: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val status = playbackVisualLabel(visual)
    val action = stringResource(musicActionLabel(visual))
    val interaction = if (interactive) {
        Modifier.onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClickLabel = action, role = Role.Button, onClick = onClick)
            .semantics { if (status != null) stateDescription = status }
    } else Modifier.clearAndSetSemantics { hideFromAccessibility() }
    Surface(shape = TuneShapes.Card, color = TuneColors.Raised,
        border = if (interactive && focused) BorderStroke(2.dp, TuneColors.Primary) else null,
        modifier = modifier.clip(TuneShapes.Card).then(interaction)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 280.dp
            val pad = if (compact) TuneSpacing.Large else TuneSpacing.Section
            val cover = minOf(if (isCar) 194.dp else 148.dp, maxWidth - pad * 2, maxHeight - pad * 2 - 76.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (cover >= 32.dp) {
                    MusicCover(track, Modifier.size(cover), TuneShapes.Station)
                    Spacer(Modifier.height(TuneSpacing.Medium))
                }
                Text(track.title, color = if (current) TuneColors.PrimaryLight else TuneColors.Text,
                    style = TuneTextStyle, fontSize = if (isCar) 26.sp else 22.sp,
                    lineHeight = if (isCar) 32.sp else 27.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(TuneSpacing.Hairline))
                Text(track.artists.joinToString(" / "), color = TuneColors.Muted,
                    style = TuneTextStyle, fontSize = if (isCar) 16.sp else 14.sp,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun musicActionLabel(visual: StationPlaybackVisual): Int = when (visual) {
    StationPlaybackVisual.Error -> R.string.music_retry
    StationPlaybackVisual.Playing -> R.string.action_pause
    StationPlaybackVisual.Paused -> R.string.now_playing_resume_accessibility
    else -> R.string.action_play
}

@Composable
internal fun MusicCover(track: MusicTrack?, modifier: Modifier = Modifier, shape: Shape = TuneShapes.Control) {
    MediaArtwork(track?.coverUrl, isMusic = true, modifier = modifier.clip(shape))
}

@Composable
internal fun MusicTrackRow(
    track: MusicTrack,
    isCar: Boolean,
    current: Boolean = false,
    visual: StationPlaybackVisual = StationPlaybackVisual.None,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    error: String? = null,
    animationsEnabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val animate = rememberMotionEnabled()
    val fill by animateColorAsState(if (current) TuneColors.Primary.copy(alpha = .10f) else Color.Transparent,
        tween(if (animate) 220 else 0), label = "music_row_selection")
    val shape = TuneShapes.Station
    val status = playbackVisualLabel(visual)
    val action = stringResource(musicActionLabel(visual))
    Row(
        modifier.fillMaxWidth().heightIn(min = if (isCar) 72.dp else 78.dp)
            .clip(shape).background(fill)
            .drawBehind {
                if (current) {
                    val width = 3.dp.toPx()
                    val height = (if (isCar) 30.dp else 26.dp).toPx()
                    drawRoundRect(TuneColors.Primary, Offset(0f, (size.height - height) / 2),
                        Size(width, height), CornerRadius(width / 2))
                }
            }
            .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled && track.canStartPlayback, onClickLabel = action, role = Role.Button, onClick = onClick)
            .semantics { if (status != null) stateDescription = status }
            .padding(start = if (isCar) 12.dp else 10.dp, end = if (isCar) 8.dp else 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCar) 16.dp else 12.dp)
    ) {
        MusicCover(track, Modifier.size(if (isCar) 48.dp else 54.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.title, color = TuneColors.Text, style = TuneTextStyle,
                fontSize = if (isCar) 20.sp else 17.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artists.joinToString(" / "), style = TuneTextStyle, color = TuneColors.Muted,
                fontSize = if (isCar) 15.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (error != null) Text(error, color = TuneColors.Error, fontSize = if (isCar) 14.sp else 12.sp, style = TuneTextStyle)
        }
        Box(
            Modifier.size(if (isCar) TuneSizes.TouchCar else TuneSizes.Touch).testTag("music_track_action_${track.id}"),
            contentAlignment = Alignment.Center
        ) {
            when (visual) {
                StationPlaybackVisual.Connecting, StationPlaybackVisual.Buffering, StationPlaybackVisual.Retrying ->
                    CircularProgressIndicator(Modifier.size(TuneSizes.IconSm), color = TuneColors.Primary,
                        strokeWidth = 2.dp, strokeCap = StrokeCap.Round, trackColor = TuneColors.Primary.copy(alpha = .22f))
                StationPlaybackVisual.Playing -> PlayingWaveBars(
                    Modifier.size(TuneSizes.IconMd), TuneColors.Primary, animationsEnabled
                )
                StationPlaybackVisual.Error -> TuneIcon(R.drawable.ic_refresh, Modifier.size(TuneSizes.IconMd), TuneColors.Error)
                else -> TuneIcon(R.drawable.ic_radio_play, Modifier.size(TuneSizes.IconMd),
                    if (!enabled) TuneColors.IconIdle.copy(alpha = TuneColors.DisabledAlpha)
                    else if (current) TuneColors.Primary else TuneColors.IconIdle)
            }
        }
    }
}

@Composable
internal fun MusicTrackDivider() {
    HorizontalDivider(Modifier.padding(start = 76.dp),
        thickness = .5.dp, color = TuneColors.Divider.copy(alpha = .55f))
}
