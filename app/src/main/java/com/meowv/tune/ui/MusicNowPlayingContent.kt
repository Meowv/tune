package com.meowv.tune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.meowv.tune.music.MusicLyrics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.ui.theme.*

@Composable
internal fun MusicNowPlayingContent(
    isCar: Boolean,
    state: PlayerUiState,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 560.dp && (isCar || maxWidth > maxHeight)
        val coverSize = if (wide) minOf(240.dp, maxHeight * .54f, maxWidth * .35f)
            else minOf(260.dp, maxHeight * .34f, maxWidth - 32.dp).coerceAtLeast(64.dp)
        val artwork: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = TuneShapes.Player, color = MaterialTheme.colorScheme.surfaceVariant) {
                    MusicCover(state.music, Modifier.size(coverSize).testTag("music_artwork"))
                }
                Spacer(Modifier.height(12.dp))
                Text(state.music?.title ?: stringResource(R.string.menu_music), color = TuneColors.Text,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("music_title"))
                Text(state.music?.artists?.joinToString(" / ").orEmpty(), color = TuneColors.Muted,
                    textAlign = TextAlign.Center)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) { artwork() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    MusicPlaybackControls(isCar, state, connected, onCommand)
                }
            }
        } else {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                artwork()
                Spacer(Modifier.height(12.dp))
                MusicPlaybackControls(isCar, state, connected, onCommand)
            }
        }
    }
}

@Composable
private fun MusicPlaybackControls(
    isCar: Boolean,
    state: PlayerUiState,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit
) {
    if (state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.RETRYING)) {
        Text(stringResource(R.string.music_loading), color = TuneColors.Muted)
    }
    if (state.phase == PlaybackPhase.ERROR || state.error != null) {
        Text(stringResource(musicPlaybackFailureMessage(state.musicFailure)), color = TuneColors.Error)
    }
    if (state.musicLyrics.isNotEmpty()) {
        val index = MusicLyrics.activeLine(state.musicLyrics, state.positionMs ?: 0)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("music_lyrics"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.musicLyrics.getOrNull(index)?.text.orEmpty(), color = TuneColors.Text,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.musicLyrics.getOrNull(index + 1)?.text.orEmpty(), color = TuneColors.Muted,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val duration = state.durationMs
    if (duration != null && duration > 0) {
        var drag by remember(state.music?.id) { mutableStateOf<Float?>(null) }
        val position = drag?.toLong() ?: state.positionMs ?: 0
        val description = stringResource(R.string.music_seek_description,
            position / 60_000, position / 1000 % 60, duration / 60_000, duration / 1000 % 60)
        if (state.canSeek) Slider(value = drag ?: (state.positionMs ?: 0).toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { onCommand(PlayerCommand.Seek(it.toLong())) }; drag = null },
            valueRange = 0f..duration.toFloat(), enabled = connected,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().semantics { stateDescription = description }.testTag("music_seek"))
        Text("${musicTime(position)} / ${musicTime(duration)}", color = TuneColors.Muted,
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onCommand(PlayerCommand.Previous) }, enabled = connected && state.canPrevious,
            modifier = Modifier.size(if (isCar) 56.dp else 48.dp).testTag("music_previous")) {
            Icon(painterResource(R.drawable.ic_now_playing_previous), stringResource(R.string.music_previous))
        }
        FilledIconButton(onClick = { onCommand(PlayerCommand.TogglePause) }, enabled = connected,
            modifier = Modifier.size(64.dp).testTag("music_toggle")) {
            MusicToggleIcon(state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING,
                PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING))
        }
        IconButton(onClick = { onCommand(PlayerCommand.Next) }, enabled = connected && state.canNext,
            modifier = Modifier.size(if (isCar) 56.dp else 48.dp).testTag("music_next")) {
            Icon(painterResource(R.drawable.ic_now_playing_next), stringResource(R.string.music_next))
        }
    }

}

internal fun musicPlaybackFailureMessage(failure: MusicFailure?) = when (failure) {
    MusicFailure.NOT_CONFIGURED -> R.string.music_not_configured
    MusicFailure.UNAUTHORIZED -> R.string.music_unauthorized
    MusicFailure.FORBIDDEN -> R.string.music_playback_forbidden
    MusicFailure.RATE_LIMITED -> R.string.music_rate_limited
    MusicFailure.SOURCE_TIMEOUT -> R.string.music_playback_timeout
    MusicFailure.SOURCE_UNAVAILABLE -> R.string.music_playback_source_unavailable
    MusicFailure.NO_PLAYABLE_MATCH -> R.string.music_empty
    else -> R.string.music_failed
}

internal fun musicTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"
