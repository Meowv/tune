package com.meowv.tune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.meowv.tune.R
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.canStartPlayback
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
    onClose: () -> Unit,
    list: LazyListState = rememberLazyListState()
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val isCar = isCarWindow(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    val submitEnabled = !state.loading && !state.selecting && state.query.isNotBlank()
    val submit: () -> Unit = {
        if (submitEnabled) {
            keyboard?.hide()
            focus.clearFocus()
            onSearch()
        }
    }
    val visibleTracks = remember(state.items) { state.items.filter { it.canStartPlayback } }
    val searchFailure = state.failure.takeIf { state.selectedId == null }
    LaunchedEffect(Unit) { if (!isCar && !state.searched) requester.requestFocus() }

    Surface(color = TuneColors.Background, modifier = Modifier.fillMaxSize().testTag("music_search_page")) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            val horizontal = if (isCar) 24.dp else 16.dp
            val touch = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = TuneSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.size(touch).testTag("music_search_close")) {
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
                        modifier = Modifier.weight(1f).focusRequester(requester).testTag("music_search_query")
                    )
                    FilledTonalIconButton(
                        onClick = submit,
                        enabled = submitEnabled,
                        modifier = Modifier.size(touch).testTag("music_search_submit"),
                        shape = TuneShapes.Control
                    ) {
                        Icon(painterResource(R.drawable.ic_ui_search), stringResource(R.string.music_search))
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("music_search_results"), state = list,
                        contentPadding = PaddingValues(horizontal = horizontal, vertical = TuneSpacing.Hairline)) {
                        if (searchFailure != null && (visibleTracks.isNotEmpty() || state.nextCursor != null)) item(key = "search_error") {
                            DirectoryFeedback(searchFailureMessage(searchFailure))
                        }
                        itemsIndexed(visibleTracks, key = { _, track -> track.id }) { index, track ->
                            val selected = state.selectedId == track.id
                            val error = state.failure.takeIf { selected }?.let { stringResource(musicPlaybackFailureMessage(it)) }
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
                        if (state.loading && visibleTracks.isNotEmpty()) item(key = "search_loading") {
                            DirectoryLoading(Modifier.fillMaxWidth().padding(vertical = TuneSpacing.Medium),
                                size = 22.dp, tag = "music_search_loading")
                        } else if (state.nextCursor != null && !state.loading) {
                            item(key = "search_more") {
                                TextButton(
                                    onClick = onMore,
                                    enabled = !state.loading && !state.selecting,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = touch).testTag("music_search_more")
                                ) {
                                    Text(stringResource(if (searchFailure != null) R.string.music_retry else R.string.music_search_more))
                                }
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
                        if (state.loading) {
                            DirectoryLoading(Modifier.fillMaxSize(), tag = "music_search_loading")
                        } else if (message != null && state.nextCursor == null) {
                            DirectoryMessage(
                                if (message == R.string.music_search_empty) R.drawable.ic_ui_search else R.drawable.ic_ui_info,
                                TuneColors.Muted, message, Modifier.fillMaxSize().testTag("music_search_empty")
                            )
                        }
                    }
                }
            }
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
