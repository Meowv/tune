package com.meowv.tune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.music.MusicUiState
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun MusicScreen(
    isCar: Boolean,
    state: MusicUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    connected: Boolean = false,
    onCommand: (PlayerCommand) -> Unit = {},
    onSearch: (() -> Unit)? = null
) {
    if (state is MusicUiState.Active) {
        Column(Modifier.fillMaxSize().background(TuneColors.Background).testTag("page_music")) {
            if (onSearch != null) MusicSearchEntry(onSearch, Modifier.align(Alignment.End))
            MusicNowPlayingContent(isCar, state.playback, connected, onCommand, Modifier.weight(1f))
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(TuneColors.Background).testTag("page_music")) {
        val availableHeight = maxHeight
        val artSize = (if (isCar) 200.dp else 223.dp).coerceAtMost(maxWidth - 48.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (isCar) 40.dp else 24.dp, vertical = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.menu_music), color = TuneColors.Text, fontSize = if (isCar) 28.sp else 27.sp,
                    fontWeight = FontWeight.Bold, style = TuneTextStyle, modifier = Modifier.semantics { heading() })
                if (onSearch != null && state != MusicUiState.NotConfigured) MusicSearchEntry(onSearch)
            }
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .heightIn(min = (availableHeight - 80.dp).coerceAtLeast(artSize + 160.dp)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painterResource(if (isCar) R.drawable.music_record_car else R.drawable.music_record),
                    contentDescription = null, modifier = Modifier.size(artSize))
                Spacer(Modifier.height(TuneSpacing.Section))
                val message = when (state) {
                    MusicUiState.NotConfigured -> R.string.music_not_configured
                    MusicUiState.Idle -> R.string.music_idle
                    MusicUiState.Loading -> R.string.music_loading
                    MusicUiState.Empty -> R.string.music_empty
                    is MusicUiState.Ready -> R.string.music_ready
                    is MusicUiState.Active -> R.string.music_ready
                    is MusicUiState.Error -> musicPlaybackFailureMessage(state.failure)
                }
                if (state is MusicUiState.Ready) {
                    Text(state.track.title, color = TuneColors.Text, style = TuneTextStyle,
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("music_title"))
                    Text(state.track.artists.joinToString(" / "), color = TuneColors.Text, style = TuneTextStyle)
                    Spacer(Modifier.height(8.dp))
                }
                Text(stringResource(message), color = TuneColors.Text, fontSize = 18.sp, style = TuneTextStyle)
                Spacer(Modifier.height(16.dp))
                val needsConfiguration = state == MusicUiState.NotConfigured ||
                    state is MusicUiState.Error && state.failure in setOf(MusicFailure.UNAUTHORIZED, MusicFailure.NOT_CONFIGURED)
                when {
                    needsConfiguration -> Unit
                    state == MusicUiState.Loading -> {
                        CircularProgressIndicator(Modifier.size(24.dp).testTag("music_loading"))
                        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp).testTag("music_cancel")) {
                            Text(stringResource(R.string.music_cancel))
                        }
                    }
                    state !is MusicUiState.Ready -> Button(onClick = onStart, modifier = Modifier.heightIn(min = 56.dp).testTag("music_start")) {
                        Text(stringResource(if (state is MusicUiState.Error || state == MusicUiState.Empty) R.string.music_retry else R.string.music_start))
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicSearchEntry(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onSearch, modifier = modifier.heightIn(min = 56.dp).testTag("music_search_open")) {
        Text(stringResource(R.string.music_search))
    }
}
