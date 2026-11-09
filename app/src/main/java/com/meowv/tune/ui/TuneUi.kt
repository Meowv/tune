package com.meowv.tune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.RadioDisplayMode
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.radio.CountryOptions
import com.meowv.tune.ui.theme.TuneColors
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TuneApp(
    settings: TuneSettings,
    storageError: Boolean,
    radio: StateFlow<RadioUiState>,
    playback: StateFlow<PlayerUiState>,
    playbackConnected: StateFlow<Boolean>,
    onPlayback: (PlayerCommand) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    countries: CountryOptions,
    onLoadCountries: (Boolean) -> Unit,
    onRefreshRadio: () -> Unit,
    onLoadMore: () -> Unit,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onClearDirectoryCache: () -> Unit,
    onShellChanged: (Boolean) -> Unit,
    onFirstLayout: () -> Unit
) {
    val connected by playbackConnected.collectAsStateWithLifecycle()
    val playbackState by playback.collectAsStateWithLifecycle()
    val radioState by radio.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(TunePage.Radio) }
    var showScopePicker by rememberSaveable { mutableStateOf(false) }
    var networkPromptDismissed by rememberSaveable { mutableStateOf(false) }
    val submitPlayback: (PlayerCommand) -> Unit = { command ->
        networkPromptDismissed = false
        onPlayback(command)
    }
    LaunchedEffect(showScopePicker) { if (showScopePicker) onLoadCountries(false) }
    val pageStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val errorText = playbackErrorText(playbackState.error, playbackState.statusCode)
    LaunchedEffect(playbackState.error) {
        if (errorText != null) snackbar.showSnackbar(errorText, duration = SnackbarDuration.Short)
    }

    val selectedId = radioState.selectedStationUuid ?: playbackState.station?.uuid
    val target = radioState.stations.firstOrNull { it.uuid == selectedId } ?: playbackState.station
    val paused = playbackState.phase == PlaybackPhase.PAUSED
    val idle = playbackState.phase == PlaybackPhase.IDLE
    val adjacent: (Int) -> Station? = { offset ->
        val list = radioState.stations
        val index = list.indexOfFirst { it.uuid == target?.uuid }
        if (index < 0) null else list.getOrNull(index + offset)
    }
    val next: () -> Unit = {
        if (paused || idle) adjacent(1)?.let(onSelectTarget)
        else submitPlayback(PlayerCommand.Next)
    }
    val previous: () -> Unit = {
        if (paused || idle) adjacent(-1)?.let(onSelectTarget)
        else submitPlayback(PlayerCommand.Previous)
    }
    val toggle: () -> Unit = {
        val station = target
        when {
            station == null -> Unit
            (paused || idle) && station.uuid != playbackState.station?.uuid ->
                submitPlayback(PlayerCommand.Play(station))
            else -> submitPlayback(PlayerCommand.TogglePause)
        }
    }

    Surface(color = TuneColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowWidth = maxWidth
            val isCar = resolveShell(settings.layout, windowWidth.value, maxHeight.value)
            LaunchedEffect(isCar) { onShellChanged(isCar) }
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(if (isCar) WindowInsets.displayCutout else WindowInsets.safeDrawing)
                    .onGloballyPositioned { onFirstLayout() }
                    .testTag(if (isCar) "shell_car" else "shell_phone")
            ) {
                MeowvTopBar(isCar)
                if (storageError) {
                    Text(
                        stringResource(R.string.settings_storage_error), color = TuneColors.Text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                if (isCar) {
                    Row(Modifier.weight(1f)) {
                        MeowvNavigationRail(page) { page = it }
                        Box(Modifier.weight(1f).fillMaxHeight()) { pageContent(page, pageStates, isCar, settings, radio, playbackState, connected, radioState, submitPlayback, onSelectTarget, onDisplayModeChange, onSettingsChange, onRefreshRadio, onLoadMore, onClearDirectoryCache, { showScopePicker = true }) }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) { pageContent(page, pageStates, isCar, settings, radio, playbackState, connected, radioState, submitPlayback, onSelectTarget, onDisplayModeChange, onSettingsChange, onRefreshRadio, onLoadMore, onClearDirectoryCache, { showScopePicker = true }) }
                }
                MeowvMiniPlayer(
                    isCar = isCar,
                    state = playbackState,
                    station = target,
                    connected = connected,
                    onToggle = toggle,
                    onNext = next,
                    onPrevious = previous
                )
                if (!isCar) MeowvNavigationBar(page) { page = it }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (isCar) 96.dp else 152.dp)
            )
            if (showScopePicker) {
                ScopePicker(
                    isCar, maxHeight, settings.scope, countries, { onLoadCountries(true) },
                    onDismiss = { showScopePicker = false }
                ) { selected ->
                    onSettingsChange { current -> current.copy(scope = selected) }
                    showScopePicker = false
                }
            }
            if (playbackState.error == TuneError.MOBILE_CONFIRMATION && !networkPromptDismissed) {
                AlertDialog(
                    onDismissRequest = {
                        networkPromptDismissed = true
                        onPlayback(PlayerCommand.DismissNetworkPrompt)
                    },
                    title = { Text(stringResource(R.string.mobile_playback_title), color = TuneColors.Text) },
                    text = { Text(stringResource(R.string.mobile_playback_message), color = TuneColors.Muted) },
                    confirmButton = {
                        TextButton(onClick = { onPlayback(PlayerCommand.AllowMeteredRetry) }) {
                            Text(stringResource(R.string.action_continue))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            networkPromptDismissed = true
                            onPlayback(PlayerCommand.DismissNetworkPrompt)
                        }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                    containerColor = TuneColors.Surface,
                    textContentColor = TuneColors.Text
                )
            }
        }
    }
}

@Composable
private fun playbackErrorText(error: TuneError?, statusCode: Int?): String? = when (error) {
    null -> null
    TuneError.MOBILE_CONFIRMATION -> null
    TuneError.NETWORK_OFFLINE -> stringResource(R.string.playback_offline)
    TuneError.WIFI_ONLY -> stringResource(R.string.playback_wifi_only)
    TuneError.AUDIO_FOCUS_LOST -> stringResource(R.string.playback_focus_lost)
    TuneError.AUDIO_DISCONNECTED -> stringResource(R.string.playback_disconnected)
    TuneError.HTTP, TuneError.SERVER -> statusCode?.let { stringResource(R.string.playback_http, it) }
        ?: stringResource(R.string.playback_unavailable)
    TuneError.TIMEOUT -> stringResource(R.string.playback_timeout)
    TuneError.TLS -> stringResource(R.string.playback_tls)
    TuneError.INVALID_RESPONSE -> stringResource(R.string.playback_no_source)
    TuneError.CONNECTION -> stringResource(R.string.playback_connection)
    TuneError.UNSUPPORTED_MEDIA -> stringResource(R.string.playback_format)
    else -> stringResource(R.string.playback_unavailable)
}

@Composable
private fun pageContent(
    page: TunePage,
    pageStates: androidx.compose.runtime.saveable.SaveableStateHolder,
    isCar: Boolean,
    settings: TuneSettings,
    radio: StateFlow<RadioUiState>,
    playback: PlayerUiState,
    connected: Boolean,
    radioState: RadioUiState,
    onPlayback: (PlayerCommand) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onRefreshRadio: () -> Unit,
    onLoadMore: () -> Unit,
    onClearDirectoryCache: () -> Unit,
    onScopeClick: () -> Unit
) {
    pageStates.SaveableStateProvider(page) {
        when (page) {
            TunePage.Radio -> RadioScreen(
                isCar = isCar,
                radio = radio,
                playback = playback,
                connected = connected,
                displayMode = settings.displayMode,
                onDisplayModeChange = onDisplayModeChange,
                onLoadMore = onLoadMore,
                onPlayback = onPlayback,
                onSelectTarget = onSelectTarget,
                onRefresh = onRefreshRadio,
                onScopeClick = onScopeClick
            )
            TunePage.Music -> MusicScreen(isCar)
            TunePage.Settings -> SettingsScreen(
                isCar, settings, radioState, onSettingsChange, onRefreshRadio, onClearDirectoryCache
            )
        }
    }
}
