package com.meowv.tune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.meowv.tune.R
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.canStartPlayback
import com.meowv.tune.music.MusicSearchState
import com.meowv.tune.music.MusicSearchWarning

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
    val submit = { keyboard?.hide(); focus.clearFocus(); onSearch() }
    val requester = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp
    LaunchedEffect(Unit) { if (!wide && !state.searched) requester.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("music_search_page")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.music_search), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = onClose, modifier = Modifier.testTag("music_search_close")) {
                        Text(stringResource(R.string.music_search_close))
                    }
                }
                OutlinedTextField(value = state.query, onValueChange = onQuery, singleLine = true,
                    enabled = !state.selecting, label = { Text(stringResource(R.string.music_search_hint)) },
                    trailingIcon = if (state.query.isNotEmpty() && !state.selecting) {
                        { TextButton(onClick = { onQuery("") }) { Text(stringResource(R.string.music_clear_query)) } }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester).testTag("music_search_query"))
                Button(onClick = submit, enabled = !state.loading && !state.selecting && state.query.isNotBlank(),
                    modifier = Modifier.heightIn(min = 56.dp).testTag("music_search_submit")) {
                    Text(stringResource(R.string.music_search))
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("music_search_loading"))
                state.failure?.takeIf { state.selectedId == null }?.let { failure ->
                    Text(stringResource(when (failure) {
                        MusicFailure.INVALID_REQUEST -> R.string.music_search_invalid
                        MusicFailure.NOT_CONFIGURED -> R.string.music_not_configured
                        MusicFailure.UNAUTHORIZED -> R.string.music_unauthorized
                        MusicFailure.RATE_LIMITED -> R.string.music_rate_limited
                        else -> R.string.music_search_failed
                    }), color = MaterialTheme.colorScheme.error)
                }
                if (state.searched && !state.loading && state.failure == null && state.items.isEmpty()) {
                    Text(stringResource(R.string.music_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("music_search_empty"))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("music_search_results"), state = list) {
                    if (state.partial && state.sourceWarnings.isEmpty()) item {
                        Text(stringResource(R.string.music_search_partial), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.sourceWarnings.forEach { (provider, warning) ->
                        item {
                            val message = when (warning) {
                                MusicSearchWarning.DEFERRED -> R.string.music_search_source_deferred
                                MusicSearchWarning.TIMEOUT -> R.string.music_search_source_timeout
                                MusicSearchWarning.RATE_LIMITED -> R.string.music_search_source_limited
                                MusicSearchWarning.UNAVAILABLE -> R.string.music_search_source_unavailable
                            }
                            Text(stringResource(message, stringResource(musicSourceName(provider))), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(state.items, key = { it.id }) { track ->
                        val sources = track.matches.map { stringResource(musicSourceName(it.providerId)) }.distinct()
                        MusicTrackRow(track, current = false, playing = false, enabled = !state.selecting,
                            detail = sources.takeIf { it.size > 1 || !track.canStartPlayback }?.joinToString(" · "),
                            modifier = Modifier.testTag("music_search_result_${track.id}"),
                            pending = state.selecting && state.selectedId == track.id) { onSelect(track) }
                        if (state.selectedId == track.id && state.failure != null) {
                            Text(stringResource(musicPlaybackFailureMessage(state.failure)),
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 10.dp))
                        }
                    }
                    if (state.nextCursor != null) item {
                        TextButton(onClick = onMore, enabled = !state.loading && !state.selecting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("music_search_more")) {
                            Text(stringResource(R.string.music_search_more))
                        }
                    }
                }
            }
        }
    }
}

internal fun musicSourceName(provider: String) = when (provider) {
    "netease" -> R.string.music_source_netease
    "qqmusic" -> R.string.music_source_qq
    "search" -> R.string.music_search
    else -> R.string.music_source_other
}
