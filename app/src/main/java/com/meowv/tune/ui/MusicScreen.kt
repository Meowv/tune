package com.meowv.tune.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioDisplayMode
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.MusicQueueSeed
import com.meowv.tune.domain.music.canStartPlayback
import com.meowv.tune.music.MusicFeed
import com.meowv.tune.music.MusicUiState
import com.meowv.tune.ui.theme.TuneShapes

@Composable
internal fun MusicScreen(
    isCar: Boolean,
    state: MusicUiState,
    browse: MusicFeed,
    onOpenPlayer: () -> Unit,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    onSearch: () -> Unit,
    onLoad: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val playback = (state as? MusicUiState.Active)?.playback
    val playing = playback?.phase == PlaybackPhase.PLAYING
    val list = rememberLazyListState()
    val feed = browse
    var mode by rememberSaveable { mutableStateOf(RadioDisplayMode.LIST) }
    Surface(color = colors.background, modifier = Modifier.fillMaxSize().testTag("page_music")) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (isCar) 24.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onSearch, enabled = state != MusicUiState.NotConfigured,
                    modifier = Modifier.heightIn(min = if (isCar) 56.dp else 48.dp).testTag("music_search_open")) {
                    Icon(painterResource(R.drawable.ic_ui_search), null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_search))
                }
                LayoutSwitcher(mode, { mode = it }, isCar)
            }
            if (state == MusicUiState.NotConfigured) {
                Text(stringResource(R.string.music_not_configured), Modifier.padding(vertical = 24.dp))
                return@Column
            }
            val track = playback?.music?.takeIf { playback.phase != PlaybackPhase.ERROR }
            val play: (MusicTrack) -> Unit = { song ->
                if (song.id == track?.id) onOpenPlayer()
                else onCommand(PlayerCommand.PlayMusic(song, MusicQueueSeed(feed.tracks, null)))
            }
            if (mode == RadioDisplayMode.CARD && feed.tracks.isNotEmpty()) {
                MusicCards(feed.tracks, track?.id, playing, connected, isCar, Modifier.weight(1f), play)
                if (feed.failure != null) Text(stringResource(musicPlaybackFailureMessage(feed.failure)), color = colors.error)
                if (feed.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (feed.failure != null) TextButton(onClick = onLoad) {
                    Text(stringResource(R.string.music_retry))
                }
                return@Column
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("music_feed"),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                if (feed.loading) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (feed.tracks.isEmpty()) items(3) {
                        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Spacer(Modifier.size(48.dp).background(colors.surfaceVariant, TuneShapes.Control))
                            Spacer(Modifier.fillMaxWidth(.55f).height(12.dp).background(colors.surfaceVariant, TuneShapes.Control))
                        }
                    }
                }
                if (feed.partial) item {
                    Text(stringResource(R.string.music_search_partial),
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                items(feed.tracks, key = { it.id }) { song ->
                    val current = song.id == track?.id
                    MusicTrackRow(song, current, current && playing, connected, detail = null,
                        modifier = Modifier.testTag("music_feed_track_${song.id}")) {
                        if (current) onOpenPlayer()
                        else onCommand(PlayerCommand.PlayMusic(song, MusicQueueSeed(feed.tracks, null)))
                    }
                }
                if (feed.failure != null) item {
                    feed.failure?.let { Text(stringResource(musicPlaybackFailureMessage(it)), color = colors.error) }
                    TextButton(enabled = !feed.loading, onClick = onLoad) {
                        Text(stringResource(R.string.music_retry))
                    }
                } else if (feed.loaded && feed.tracks.isEmpty()) item {
                    Text(stringResource(R.string.music_empty), Modifier.padding(top = 16.dp))
                    TextButton(onClick = onSearch) { Text(stringResource(R.string.music_search)) }
                }
            }
        }
    }
}

@Composable
private fun MusicCards(
    tracks: List<MusicTrack>, currentId: String?, playing: Boolean, connected: Boolean,
    isCar: Boolean, modifier: Modifier, onPlay: (MusicTrack) -> Unit
) {
    val pager = rememberPagerState(initialPage = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)) { tracks.size }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = minOf(if (isCar) 366.dp else 262.dp, (maxWidth - 40.dp).coerceAtLeast(1.dp))
        val height = minOf(if (isCar) 340.dp else 300.dp, maxHeight)
        HorizontalPager(state = pager, pageSize = PageSize.Fixed(width),
            contentPadding = PaddingValues(horizontal = (maxWidth - width) / 2),
            pageSpacing = 12.dp, key = { tracks[it].id }, modifier = Modifier.fillMaxSize().testTag("music_cards")) { index ->
            val song = tracks[index]
            val current = song.id == currentId
            Surface(onClick = { onPlay(song) }, enabled = connected, shape = TuneShapes.Card,
                color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(height).testTag("music_feed_card_${song.id}")) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    MusicCover(song, Modifier.size(minOf(150.dp, height * .48f)))
                    Spacer(Modifier.height(12.dp))
                    Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artists.joinToString(" / "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(painterResource(if (current && playing) R.drawable.ic_wave else R.drawable.ic_radio_play),
                        if (current && playing) stringResource(R.string.music_now_playing) else null)
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
