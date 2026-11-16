package com.meowv.tune.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.ui.theme.*
import coil3.compose.AsyncImage

@Composable
internal fun MusicNowPlayingContent(
    isCar: Boolean,
    state: PlayerUiState,
    connected: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier = Modifier,
    onSleepTimer: (() -> Unit)? = null
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val track = state.music
            val artwork = Modifier.size(if (isCar) 180.dp else 200.dp)
            if (track?.coverUrl.isNullOrBlank()) {
                Image(painterResource(if (isCar) R.drawable.music_record_car else R.drawable.music_record), null, artwork)
            } else {
                AsyncImage(track?.coverUrl, null, artwork, placeholder = painterResource(R.drawable.music_record), error = painterResource(R.drawable.music_record))
            }
            Spacer(Modifier.height(16.dp))
            Text(track?.title ?: stringResource(R.string.menu_music), color = TuneColors.Text, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.testTag("music_title"))
            Text(track?.artists?.joinToString(" / ").orEmpty(), color = TuneColors.Muted)
            if (state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.RETRYING)) {
                Text(stringResource(R.string.music_loading), color = TuneColors.Muted)
            }
            if (state.phase == PlaybackPhase.ERROR || state.error != null) {
                Text(stringResource(when (state.musicFailure) {
                    MusicFailure.NOT_CONFIGURED -> R.string.music_not_configured
                    MusicFailure.UNAUTHORIZED -> R.string.music_unauthorized
                    MusicFailure.RATE_LIMITED -> R.string.music_rate_limited
                    MusicFailure.NO_PLAYABLE_MATCH -> R.string.music_empty
                    else -> R.string.music_failed
                }), color = TuneColors.Error)
            }
            val duration = state.durationMs
            if (state.canSeek && duration != null && duration > 0) {
                var drag by remember(track?.id) { mutableStateOf<Float?>(null) }
                Slider(value = drag ?: (state.positionMs ?: 0).toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { onCommand(PlayerCommand.Seek(it.toLong())) }; drag = null },
                    valueRange = 0f..duration.toFloat(), enabled = connected,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("music_seek"))
                Text("${musicTime(drag?.toLong() ?: state.positionMs ?: 0)} / ${musicTime(duration)}", color = TuneColors.Muted)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onCommand(PlayerCommand.Previous) }, enabled = connected && state.canPrevious,
                    modifier = Modifier.size(56.dp).testTag("music_previous")) {
                    Icon(painterResource(R.drawable.ic_now_playing_previous), stringResource(R.string.music_previous))
                }
                Button(onClick = { onCommand(PlayerCommand.TogglePause) }, enabled = connected,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("music_toggle")) {
                    Text(stringResource(if (state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.RESOLVING)) R.string.action_pause else R.string.action_play))
                }
                IconButton(onClick = { onCommand(PlayerCommand.Next) }, enabled = connected && state.canNext,
                    modifier = Modifier.size(56.dp).testTag("music_next")) {
                    Icon(painterResource(R.drawable.ic_now_playing_next), stringResource(R.string.music_next))
                }
            }
            if (onSleepTimer != null) TextButton(onClick = onSleepTimer, modifier = Modifier.heightIn(min = 56.dp).testTag("music_sleep_timer")) {
                Text(stringResource(R.string.now_playing_sleep_timer))
            }
        }
    }
}

private fun musicTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"
