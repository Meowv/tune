package com.meowv.tune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
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
                Row(Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = TuneSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else onClose() }, modifier = Modifier.size(touch).testTag("music_search_close")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.music_search_close))
                    }
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        singleLine = true,
                        enabled = !state.selecting,
                        placeholder = { Text(stringResource(R.string.music_search_hint), style = TuneTextStyle) },
                        trailingIcon = if (state.query.isNotEmpty() && !state.selecting) {
                            {
                                IconButton(onClick = { onQuery("") }) {
                                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.music_clear_query))
                                }
                            }
                        } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        shape = TuneShapes.Chip,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = TuneColors.Surface,
                            unfocusedContainerColor = TuneColors.Surface,
                            unfocusedBorderColor = TuneColors.BorderSoft
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).focusRequester(requester).testTag("music_search_query")
                    )
                    if (isCar) FilledTonalIconButton(
                        onClick = submit,
                        enabled = submitEnabled,
                        modifier = Modifier.size(56.dp).testTag("music_search_submit"),
                        shape = TuneShapes.Chip,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = TuneColors.Surface, contentColor = TuneColors.PrimaryLight,
                            disabledContainerColor = TuneColors.Surface,
                            disabledContentColor = TuneColors.Muted.copy(alpha = TuneColors.DisabledAlpha)
                        )
                    ) {
                        Icon(painterResource(R.drawable.ic_ui_search), stringResource(R.string.music_search))
                    }
                }

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (showHistory) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = TuneSpacing.Medium)
                                .verticalScroll(rememberScrollState()).testTag("music_search_history"),
                            verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.music_search_history), color = TuneColors.Text,
                                    style = TuneTextStyle, modifier = Modifier.weight(1f))
                                TextButton(onClick = onClearHistory, modifier = Modifier.testTag("music_search_history_clear")) {
                                    Text(stringResource(R.string.music_search_history_clear))
                                }
                            }
                            state.history.forEachIndexed { index, query ->
                                TextButton(
                                    onClick = { onSelectHistory(query) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = touch)
                                        .testTag("music_search_history_$index"),
                                    contentPadding = PaddingValues(horizontal = TuneSpacing.Medium)
                                ) {
                                    Text(query, color = TuneColors.Text, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    if (!showHistory) LazyVerticalGrid(
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
                                if (index < visibleTracks.lastIndex) MusicTrackDivider()
                            }
                        }
                        if (state.request == MusicSearchRequest.APPEND || state.nextCursor != null) {
                            item(key = "search_footer", span = { GridItemSpan(maxLineSpan) }) {
                                SearchPagination(state, touch, onMore)
                            }
                        }
                    }

                    if (visibleTracks.isEmpty()) {
                        val message = when {
                            searchFailure != null -> searchFailureMessage(searchFailure)
                            state.searched && state.nextCursor == null &&
                                state.sourceWarnings.containsValue(MusicSearchWarning.RATE_LIMITED) -> R.string.music_rate_limited
                            state.searched && state.nextCursor == null &&
                                state.sourceWarnings.containsValue(MusicSearchWarning.TIMEOUT) -> R.string.music_playback_timeout
                            state.searched && state.nextCursor == null && state.partial -> R.string.music_playback_source_unavailable
                            state.searched && state.nextCursor == null -> R.string.music_search_empty
                            else -> null
                        }
                        if (state.request == MusicSearchRequest.INITIAL) {
                            DirectoryLoading(Modifier.fillMaxSize(), tag = "music_search_loading")
                        } else if (message != null && state.nextCursor == null) {
                            DirectoryMessage(
                                if (message == R.string.music_search_empty) R.drawable.ic_ui_search else R.drawable.ic_ui_info,
                                if (message == R.string.music_search_empty) TuneColors.Muted else TuneColors.Error,
                                message, Modifier.fillMaxSize().testTag("music_search_empty"),
                                action = if (message != R.string.music_search_empty && submitEnabled) {
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
private fun SearchPagination(state: MusicSearchState, touch: Dp, onMore: () -> Unit) {
    when {
        state.request == MusicSearchRequest.APPEND -> DirectoryLoading(
            Modifier.fillMaxWidth().heightIn(min = touch), size = 22.dp, tag = "music_search_loading"
        )
        state.appendFailure != null -> Row(
            Modifier.fillMaxWidth().padding(horizontal = TuneSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val reason = searchFailureMessage(state.appendFailure)
            Text(
                stringResource(if (reason == R.string.music_search_failed) R.string.music_search_more_failed else reason),
                modifier = Modifier.weight(1f), color = TuneColors.Error, style = TuneTextStyle
            )
            TextButton(onClick = onMore, enabled = !state.selecting,
                modifier = Modifier.heightIn(min = touch).testTag("music_search_more")) {
                Text(stringResource(R.string.music_retry))
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
    MusicFailure.NOT_CONFIGURED, MusicFailure.UNAUTHORIZED, MusicFailure.RATE_LIMITED,
    MusicFailure.SOURCE_TIMEOUT, MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.CONNECTION,
    MusicFailure.TLS -> musicPlaybackFailureMessage(failure)
    else -> R.string.music_search_failed
}
