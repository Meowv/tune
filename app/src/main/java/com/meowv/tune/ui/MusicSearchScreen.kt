package com.meowv.tune.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.meowv.tune.music.MusicSearchState
import com.meowv.tune.ui.theme.TuneColors

@Composable
internal fun MusicSearchScreen(
    state: MusicSearchState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    onSelect: (MusicTrack) -> Unit,
    onClose: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = { keyboard?.hide(); focus.clearFocus(); onSearch() }
    Surface(color = TuneColors.Background, modifier = Modifier.fillMaxSize().testTag("music_search_page")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.music_search), style = MaterialTheme.typography.headlineSmall, color = TuneColors.Text)
                    TextButton(onClick = onClose, modifier = Modifier.testTag("music_search_close")) {
                        Text(stringResource(R.string.music_search_close))
                    }
                }
                OutlinedTextField(value = state.query, onValueChange = onQuery, singleLine = true,
                    enabled = !state.selecting, label = { Text(stringResource(R.string.music_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("music_search_query"))
                Button(onClick = submit, enabled = !state.loading && !state.selecting && state.query.isNotBlank(),
                    modifier = Modifier.heightIn(min = 56.dp).testTag("music_search_submit")) {
                    Text(stringResource(R.string.music_search))
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("music_search_loading"))
                state.failure?.let { failure ->
                    Text(stringResource(when (failure) {
                        MusicFailure.INVALID_REQUEST -> R.string.music_search_invalid
                        MusicFailure.NOT_CONFIGURED -> R.string.music_not_configured
                        MusicFailure.UNAUTHORIZED -> R.string.music_unauthorized
                        MusicFailure.RATE_LIMITED -> R.string.music_rate_limited
                        else -> R.string.music_search_failed
                    }), color = TuneColors.Error)
                }
                if (state.partial) Text(stringResource(R.string.music_search_partial), color = TuneColors.Muted)
                if (state.searched && !state.loading && state.failure == null && state.items.isEmpty()) {
                    Text(stringResource(R.string.music_search_empty), color = TuneColors.Muted,
                        modifier = Modifier.testTag("music_search_empty"))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("music_search_results")) {
                    items(state.items, key = { it.id }) { track ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .clickable(enabled = !state.selecting && track.availability != "unavailable") { onSelect(track) }
                            .padding(vertical = 12.dp).testTag("music_search_result_${track.id}")) {
                            Text(track.title, color = TuneColors.Text, style = MaterialTheme.typography.titleMedium)
                            Text(track.artists.joinToString(" / "), color = TuneColors.Muted)
                            track.album?.let { Text(it, color = TuneColors.Muted) }
                            track.matches.firstOrNull()?.let { source ->
                                Text(stringResource(when (source.providerId) {
                                    "netease" -> R.string.music_source_netease
                                    "original-demo" -> R.string.music_source_demo
                                    else -> R.string.music_source_other
                                }), color = TuneColors.Muted)
                            }
                            if (track.availability == "unavailable") Text(stringResource(R.string.music_track_unavailable), color = TuneColors.Muted)
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
