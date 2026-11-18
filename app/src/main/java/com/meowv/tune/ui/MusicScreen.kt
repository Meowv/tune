package com.meowv.tune.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.canStartPlayback
import com.meowv.tune.music.MusicBrowseState
import com.meowv.tune.music.MusicSection
import com.meowv.tune.music.MusicUiState
import com.meowv.tune.ui.theme.TuneShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicScreen(
    isCar: Boolean,
    state: MusicUiState,
    browse: MusicBrowseState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenPlayer: () -> Unit,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    onSearch: () -> Unit,
    onSection: (MusicSection) -> Unit,
    onLoad: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val playback = (state as? MusicUiState.Active)?.playback
    val playing = playback?.phase == PlaybackPhase.PLAYING
    val recommendations = rememberLazyListState()
    val chart = rememberLazyListState()
    val feed = browse.feed
    Surface(color = colors.background, modifier = Modifier.fillMaxSize().testTag("page_music")) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (isCar) 24.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.menu_music), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                if (state != MusicUiState.NotConfigured) {
                    IconButton(onClick = onSearch, modifier = Modifier.testTag("music_search_open")) {
                        Icon(painterResource(R.drawable.ic_ui_search), stringResource(R.string.music_search))
                    }
                }
            }
            if (state == MusicUiState.NotConfigured) {
                Text(stringResource(R.string.music_not_configured), Modifier.padding(vertical = 24.dp))
                return@Column
            }
            val track = playback?.music
            if (playback != null && track != null) {
                val busy = playback.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.RETRYING)
                Surface(shape = TuneShapes.Player, color = colors.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 64.dp).clip(TuneShapes.Control)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.music_open_player),
                                onClick = onOpenPlayer).testTag("music_current"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MusicCover(track, Modifier.size(64.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("music_title"))
                                Text(track.artists.joinToString(" / "), color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val status = when {
                                    playback.musicFailure != null -> stringResource(musicPlaybackFailureMessage(playback.musicFailure))
                                    busy -> stringResource(R.string.music_loading)
                                    playback.phase == PlaybackPhase.PAUSED && playback.positionMs != null ->
                                        stringResource(R.string.music_resume_at, musicTime(playback.positionMs))
                                    else -> null
                                }
                                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        FilledIconButton(onClick = { onCommand(PlayerCommand.TogglePause) },
                            enabled = connected, modifier = Modifier.size(if (isCar) 56.dp else 48.dp).testTag("music_home_toggle")) {
                            MusicToggleIcon(playing || busy)
                        }
                    }
                }
            } else if (state == MusicUiState.Loading || playback?.phase == PlaybackPhase.RESOLVING) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("music_loading"))
                TextButton(onClick = onCancel, modifier = Modifier.testTag("music_cancel")) {
                    Text(stringResource(R.string.music_cancel))
                }
            } else {
                when (state) {
                    is MusicUiState.Error -> Text(stringResource(musicPlaybackFailureMessage(state.failure)), color = colors.error)
                    MusicUiState.Empty -> Text(stringResource(R.string.music_empty))
                    is MusicUiState.Ready -> {
                        Text(state.track.title, Modifier.testTag("music_title"))
                        Text(stringResource(R.string.music_ready))
                    }
                    else -> Unit
                }
                val canStart = state !is MusicUiState.Ready && (state !is MusicUiState.Error ||
                    state.failure !in setOf(MusicFailure.UNAUTHORIZED, MusicFailure.NOT_CONFIGURED))
                if (canStart) Button(onClick = onStart, enabled = connected, modifier = Modifier.heightIn(min = 48.dp).testTag("music_start")) {
                    Text(stringResource(R.string.music_start))
                }
            }
            TabRow(selectedTabIndex = browse.section.ordinal) {
                MusicSection.entries.forEach { section ->
                    Tab(selected = browse.section == section, onClick = { onSection(section) },
                        text = { Text(stringResource(if (section == MusicSection.Chart) R.string.music_chart else R.string.music_recommended)) })
                }
            }
            key(browse.section) {
                LazyColumn(state = if (browse.section == MusicSection.Chart) chart else recommendations,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("music_feed"),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (feed.loading) {
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (feed.rows.isEmpty()) items(3) {
                            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Spacer(Modifier.size(48.dp).background(colors.surfaceVariant, TuneShapes.Control))
                                Spacer(Modifier.fillMaxWidth(.55f).height(12.dp).background(colors.surfaceVariant, TuneShapes.Control))
                            }
                        }
                    }
                    if (feed.stale || feed.partial) item {
                        Text(stringResource(if (feed.stale) R.string.music_stale else R.string.music_search_partial),
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                    items(feed.rows, key = { it.track.id }) { row ->
                        val current = row.track.id == playback?.music?.id
                        val rankLabel = if (row.ranks.isEmpty()) null else row.ranks.map { rank ->
                            stringResource(R.string.music_chart_rank, stringResource(musicSourceName(rank.source)), rank.rank)
                        }.joinToString(" · ")
                        MusicTrackRow(row.track, current, current && playing, connected, rankLabel) {
                            if (current) onOpenPlayer() else onCommand(PlayerCommand.PlayMusic(row.track))
                        }
                    }
                    if (feed.failure != null || feed.cursor != null) item {
                        feed.failure?.let { Text(stringResource(musicPlaybackFailureMessage(it)), color = colors.error) }
                        TextButton(enabled = !feed.loading, onClick = { onLoad(feed.cursor != null) }) {
                            Text(stringResource(if (feed.failure != null) R.string.music_retry else R.string.music_search_more))
                        }
                    } else if (feed.loaded && feed.rows.isEmpty()) item {
                        Text(stringResource(R.string.music_empty), Modifier.padding(top = 16.dp))
                        TextButton(onClick = onSearch) { Text(stringResource(R.string.music_search)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MusicCover(track: MusicTrack?, modifier: Modifier = Modifier) {
    val fallback = painterResource(R.drawable.music_record)
    val shape = modifier.clip(TuneShapes.Control)
    if (track?.coverUrl.isNullOrBlank()) Image(fallback, null, shape)
    else {
        val context = LocalContext.current
        val duration = if (rememberMotionEnabled()) 160 else 0
        val request = remember(context, track?.coverUrl, duration) {
            ImageRequest.Builder(context).data(track?.coverUrl).crossfade(duration).build()
        }
        AsyncImage(request, null, shape, placeholder = fallback, error = fallback, contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun MusicToggleIcon(pausing: Boolean) {
    Icon(painterResource(if (pausing) R.drawable.ic_music_pause else R.drawable.ic_radio_play),
        stringResource(if (pausing) R.string.action_pause else R.string.action_play))
}

@Composable
internal fun MusicTrackRow(
    track: MusicTrack,
    current: Boolean,
    playing: Boolean,
    enabled: Boolean,
    detail: String?,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (current) colors.primaryContainer else colors.background, shape = TuneShapes.Station,
        modifier = modifier.fillMaxWidth().clip(TuneShapes.Station)
            .clickable(role = Role.Button, enabled = enabled && (current || track.canStartPlayback), onClick = onClick)) {
        Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (current) Spacer(Modifier.width(3.dp).height(28.dp).background(colors.primary, TuneShapes.Control))
            MusicCover(track, Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artists.joinToString(" / "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (!track.canStartPlayback && !current) Text(stringResource(if (track.availability == "metadata_only")
                    R.string.music_metadata_only else R.string.music_track_unavailable), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (pending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else if (current) Icon(painterResource(if (playing) R.drawable.ic_wave else R.drawable.ic_radio_play),
                stringResource(if (playing) R.string.music_now_playing else R.string.music_open_player), tint = colors.primary)
            else if (track.canStartPlayback) Icon(painterResource(R.drawable.ic_radio_play), null, tint = colors.primary)
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
}
