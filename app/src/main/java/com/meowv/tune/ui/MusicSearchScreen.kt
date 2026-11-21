package com.meowv.tune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meowv.tune.R
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.canStartPlayback
import com.meowv.tune.music.MusicSearchRequest
import com.meowv.tune.music.MusicSearchState
import com.meowv.tune.music.MusicSearchWarning
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle
import com.meowv.tune.ui.theme.TuneTypography

@Composable
internal fun MusicSearchScreen(
    state: MusicSearchState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    onSelect: (MusicTrack) -> Unit,
    onSelectHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onClose: () -> Unit,
    list: LazyGridState = rememberLazyGridState(),
    isCar: Boolean
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = !imeVisible) { onClose() }
    val submitEnabled = !state.loading && !state.selecting && state.query.isNotBlank()
    val submit: () -> Unit = {
        if (submitEnabled) {
            keyboard?.hide()
            focus.clearFocus()
            onSearch()
        }
    }
    val visibleTracks = remember(state.items) { state.items.filter { it.canStartPlayback } }
    val searchFailure = state.failure
    val showHistory = !state.searched && state.query.isBlank() && state.history.isNotEmpty()
    LaunchedEffect(Unit) { if (!isCar && !state.searched) requester.requestFocus() }

    Surface(color = TuneColors.Background, modifier = Modifier.fillMaxSize().testTag("music_search_page")) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            val horizontal = if (isCar) 24.dp else 16.dp
            val touch = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                Row(
                    Modifier.widthIn(max = if (isCar) 746.dp else Dp.Unspecified)
                        .fillMaxWidth().padding(horizontal = horizontal, vertical = TuneSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically) {
                    SearchIconButton(
                        R.drawable.ic_arrow_back, R.string.music_search_close, touch, "music_search_close"
                    ) {
                        if (imeVisible) {
                            keyboard?.hide()
                            focus.clearFocus()
                        } else onClose()
                    }
                    SearchInput(
                        query = state.query,
                        enabled = !state.selecting,
                        isCar = isCar,
                        touch = touch,
                        requester = requester,
                        onQuery = onQuery,
                        onSubmit = submit,
                        modifier = Modifier.weight(1f)
                    )
                    if (isCar) SearchIconButton(
                        R.drawable.ic_ui_search, R.string.music_search, touch, "music_search_submit",
                        enabled = submitEnabled,
                        onClick = submit
                    )
                }

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (showHistory) {
                        SearchHistory(state.history, isCar, touch, horizontal, onSelectHistory, onClearHistory)
                    } else LazyVerticalGrid(
                        columns = GridCells.Fixed(directoryColumnCount(isCar, maxWidth.value, maxHeight.value)),
                        modifier = Modifier.fillMaxSize().testTag("music_search_results"), state = list,
                        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium),
                        contentPadding = PaddingValues(horizontal = horizontal, vertical = TuneSpacing.Hairline)) {
                        items(visibleTracks.size, key = { visibleTracks[it].id }) { index ->
                            val track = visibleTracks[index]
                            Column {
                                val selected = state.selectedId == track.id
                                val error = state.selectionFailure.takeIf { selected }?.let { stringResource(musicPlaybackFailureMessage(it)) }
                                MusicTrackRow(track, isCar,
                                    visual = when {
                                        selected && state.selecting -> StationPlaybackVisual.Connecting
                                        error != null -> StationPlaybackVisual.Error
                                        else -> StationPlaybackVisual.None
                                    },
                                    enabled = !state.selecting,
                                    modifier = Modifier.testTag("music_search_result_${track.id}"), error = error) {
                                    onSelect(track)
                                }
                                if (index < visibleTracks.lastIndex) MusicTrackDivider(isCar)
                            }
                        }
                        if (state.nextCursor != null || state.restartRequired) {
                            item(key = "search_footer", span = { GridItemSpan(maxLineSpan) }) {
                                SearchPagination(state, touch, onMore, submit)
                            }
                        }
                    }

                    if (visibleTracks.isEmpty() && !state.restartRequired) {
                        val hasMore = state.nextCursor != null
                        val message = when {
                            searchFailure != null -> searchFailureMessage(searchFailure)
                            state.searched && !hasMore &&
                                state.sourceWarnings.containsValue(MusicSearchWarning.RATE_LIMITED) -> R.string.music_rate_limited
                            state.searched && !hasMore &&
                                state.sourceWarnings.containsValue(MusicSearchWarning.TIMEOUT) -> R.string.music_playback_timeout
                            state.searched && !hasMore && state.items.isNotEmpty() -> R.string.music_search_no_playable_results
                            state.searched && !hasMore && state.partial -> R.string.music_playback_source_unavailable
                            state.searched && !hasMore -> R.string.music_search_empty
                            else -> null
                        }
                        if (state.request == MusicSearchRequest.INITIAL) {
                            DirectoryLoading(Modifier.fillMaxSize(), tag = "music_search_loading")
                        } else if (message != null && !hasMore) {
                            val noResults = message == R.string.music_search_empty ||
                                message == R.string.music_search_no_playable_results
                            DirectoryMessage(
                                if (noResults) R.drawable.ic_ui_search else R.drawable.ic_ui_info,
                                if (noResults) TuneColors.Muted else TuneColors.Error,
                                message, Modifier.fillMaxSize().testTag("music_search_empty"),
                                action = if (!noResults && submitEnabled) {
                                    {
                                        TextButton(onClick = submit, modifier = Modifier.testTag("music_search_retry")) {
                                            Text(stringResource(R.string.music_retry))
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    enabled: Boolean,
    isCar: Boolean,
    touch: Dp,
    requester: FocusRequester,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val hint = stringResource(R.string.music_search_hint)
    val shape = TuneShapes.Control
    val textStyle = TuneTypography.control(isCar)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle.copy(color = TuneColors.Text),
        cursorBrush = SolidColor(TuneColors.Primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = hint }
            .testTag("music_search_query"),
        decorationBox = { innerTextField ->
            Row(
                Modifier.heightIn(min = touch)
                    .clip(shape)
                    .background(if (focused) TuneColors.Raised else TuneColors.Surface)
                    .border(if (focused) 2.dp else 1.dp, if (focused) TuneColors.Primary else TuneColors.Border, shape)
                    .padding(start = TuneSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(R.drawable.ic_ui_search), null, Modifier.size(TuneSizes.IconSm),
                    tint = TuneColors.IconIdle)
                Box(
                    Modifier.weight(1f).padding(
                        end = if (enabled && query.isNotEmpty()) 0.dp else TuneSpacing.Medium,
                        top = TuneSpacing.Hairline, bottom = TuneSpacing.Hairline
                    )
                ) {
                    if (query.isEmpty()) Text(
                        hint, Modifier.clearAndSetSemantics {}, color = TuneColors.Muted,
                        style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    innerTextField()
                }
                if (enabled && query.isNotEmpty()) {
                    SearchIconButton(
                        R.drawable.ic_close, R.string.music_clear_query, touch, "music_search_clear",
                        showSurface = false
                    ) { onQuery("") }
                }
            }
        }
    )
}

@Composable
private fun SearchIconButton(
    icon: Int,
    description: Int,
    touch: Dp,
    tag: String,
    enabled: Boolean = true,
    showSurface: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Control
    val surface = if (showSurface) {
        Modifier.background(TuneColors.Surface, shape).border(1.dp, TuneColors.BorderSoft, shape)
    } else Modifier
    val focusOutline = if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(touch)
            .onFocusChanged { focused = it.isFocused }
            .then(surface).then(focusOutline).testTag(tag)
    ) {
        Icon(
            painterResource(icon), stringResource(description), Modifier.size(TuneSizes.IconMd),
            tint = if (enabled) TuneColors.PrimaryLight else TuneColors.Muted.copy(alpha = TuneColors.DisabledAlpha)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistory(
    history: List<String>,
    isCar: Boolean,
    touch: Dp,
    horizontal: Dp,
    onSelect: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = horizontal)
            .verticalScroll(rememberScrollState()).testTag("music_search_history")
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.music_search_history), color = TuneColors.Muted,
                style = TuneTypography.meta(isCar), modifier = Modifier.weight(1f))
            SearchIconButton(
                R.drawable.ic_ui_trash, R.string.music_search_history_clear, touch, "music_search_history_clear",
                showSurface = false, onClick = onClear
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline)
        ) {
            history.forEachIndexed { index, query ->
                var focused by remember(query) { mutableStateOf(false) }
                Box(
                    Modifier.widthIn(min = touch, max = if (isCar) 360.dp else 248.dp)
                        .heightIn(min = touch).clip(TuneShapes.Chip)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(role = Role.Button, onClick = { onSelect(query) })
                        .testTag("music_search_history_$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = if (focused) TuneColors.Raised else TuneColors.Surface,
                        shape = TuneShapes.Chip,
                        border = BorderStroke(
                            if (focused) 2.dp else 1.dp,
                            if (focused) TuneColors.Primary else TuneColors.BorderSoft
                        )
                    ) {
                        Box(
                            Modifier.heightIn(min = if (isCar) 42.dp else 36.dp)
                                .padding(horizontal = TuneSpacing.Large, vertical = TuneSpacing.Hairline),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(query, color = TuneColors.Text, style = TuneTypography.secondary(isCar),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPagination(
    state: MusicSearchState,
    touch: Dp,
    onMore: () -> Unit,
    onRestart: () -> Unit
) {
    when {
        state.request == MusicSearchRequest.APPEND || state.request == MusicSearchRequest.RESTART -> DirectoryLoading(
            Modifier.fillMaxWidth().heightIn(min = touch), size = 22.dp, tag = "music_search_loading"
        )
        state.restartRequired || state.appendFailure != null -> Row(
            Modifier.fillMaxWidth().padding(horizontal = TuneSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val reason = state.appendFailure?.let(::searchFailureMessage)
                ?: R.string.music_search_continuation_failed
            val message = if (reason == R.string.music_search_failed && !state.restartRequired) {
                R.string.music_search_more_failed
            } else reason
            Text(
                stringResource(message),
                modifier = Modifier.weight(1f), color = TuneColors.Error, style = TuneTextStyle
            )
            TextButton(
                onClick = if (state.restartRequired) onRestart else onMore,
                enabled = !state.selecting,
                modifier = Modifier.heightIn(min = touch)
                    .testTag(if (state.restartRequired) "music_search_restart" else "music_search_more")
            ) {
                Text(stringResource(if (state.restartRequired) R.string.music_search_restart else R.string.music_retry))
            }
        }
        else -> TextButton(onClick = onMore, enabled = !state.selecting,
            modifier = Modifier.fillMaxWidth().heightIn(min = touch).testTag("music_search_more")) {
            Text(stringResource(R.string.music_search_more))
        }
    }
}

private fun searchFailureMessage(failure: MusicFailure): Int = when (failure) {
    MusicFailure.INVALID_REQUEST -> R.string.music_search_invalid
    MusicFailure.NOT_CONFIGURED, MusicFailure.UNAUTHORIZED, MusicFailure.FORBIDDEN, MusicFailure.RATE_LIMITED,
    MusicFailure.SOURCE_TIMEOUT, MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.CONNECTION,
    MusicFailure.TLS -> musicPlaybackFailureMessage(failure)
    else -> R.string.music_search_failed
}
