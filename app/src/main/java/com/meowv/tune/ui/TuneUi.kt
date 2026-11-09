package com.meowv.tune.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.meowv.tune.domain.PlaybackOrder
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
    snackbar: SnackbarHostState,
    directoryRefreshing: Boolean,
    onPlayback: (PlayerCommand) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    countries: CountryOptions,
    onLoadCountries: (Boolean) -> Unit,
    onRefreshRadio: () -> Unit,
    onRefreshFromSettings: () -> Unit,
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
    LaunchedEffect(showScopePicker) { if (showScopePicker) onLoadCountries(false) }
    val pageStates = rememberSaveableStateHolder()
    val errorText = playbackErrorText(playbackState.error, playbackState.statusCode)
    LaunchedEffect(playbackState.error) {
        if (errorText != null) snackbar.showSnackbar(errorText, duration = SnackbarDuration.Short)
    }

    val selectedId = radioState.selectedStationUuid ?: playbackState.station?.uuid
    val target = radioState.stations.firstOrNull { it.uuid == selectedId } ?: playbackState.station
    val paused = playbackState.phase == PlaybackPhase.PAUSED
    val idle = playbackState.phase == PlaybackPhase.IDLE
    val shuffle = settings.playbackOrder == PlaybackOrder.SHUFFLE
    val adjacent: (Int) -> Station? = { offset ->
        val list = radioState.stations
        val index = list.indexOfFirst { it.uuid == target?.uuid }
        if (index < 0) null else list.getOrNull(index + offset)
    }
    // 播放中的切台统一交给播放队列按当前顺序处理，底栏与系统媒体键走同一条路径；
    // 暂停/空闲只换目标不起播，随机模式从当前范围随机取一台，并排除正在播放的台。
    val randomTarget: () -> Station? = {
        radioState.stations.filter { it.uuid != target?.uuid }.randomOrNull()
    }
    val next: () -> Unit = {
        when {
            !paused && !idle -> onPlayback(PlayerCommand.Next)
            shuffle -> randomTarget()?.let(onSelectTarget)
            else -> adjacent(1)?.let(onSelectTarget)
        }
    }
    val previous: () -> Unit = {
        when {
            !paused && !idle -> onPlayback(PlayerCommand.Previous)
            shuffle -> randomTarget()?.let(onSelectTarget)
            else -> adjacent(-1)?.let(onSelectTarget)
        }
    }
    val toggle: () -> Unit = {
        val station = target
        when {
            station == null -> Unit
            // 失败态一律按目标台重新起播：引擎侧可能从未建立播放意图（例如离线起播），
            // 复用 TogglePause 会退化成空操作。
            playbackState.phase == PlaybackPhase.ERROR -> onPlayback(PlayerCommand.Play(station))
            (paused || idle) && station.uuid != playbackState.station?.uuid ->
                onPlayback(PlayerCommand.Play(station))
            else -> onPlayback(PlayerCommand.TogglePause)
        }
    }

    Surface(color = TuneColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowWidth = maxWidth
            val isCar = isCarWindow(windowWidth.value, maxHeight.value)
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
                        Crossfade(page, Modifier.weight(1f).fillMaxHeight(), animationSpec = tween(220), label = "page_switch") { target ->
                            Box(Modifier.fillMaxSize()) { pageContent(target, pageStates, isCar, settings, radio, playbackState, connected, directoryRefreshing, onPlayback, onSelectTarget, onDisplayModeChange, onSettingsChange, onRefreshRadio, onRefreshFromSettings, onLoadMore, onClearDirectoryCache, { showScopePicker = true }) }
                        }
                    }
                } else {
                    Crossfade(page, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(220), label = "page_switch") { target ->
                        Box(Modifier.fillMaxSize()) { pageContent(target, pageStates, isCar, settings, radio, playbackState, connected, directoryRefreshing, onPlayback, onSelectTarget, onDisplayModeChange, onSettingsChange, onRefreshRadio, onRefreshFromSettings, onLoadMore, onClearDirectoryCache, { showScopePicker = true }) }
                    }
                }
                MeowvMiniPlayer(
                    isCar = isCar,
                    state = playbackState,
                    station = target,
                    connected = connected,
                    shuffle = shuffle,
                    onToggleOrder = {
                        onSettingsChange { current ->
                            current.copy(
                                playbackOrder = if (current.playbackOrder == PlaybackOrder.SHUFFLE) {
                                    PlaybackOrder.SEQUENTIAL
                                } else {
                                    PlaybackOrder.SHUFFLE
                                }
                            )
                        }
                    },
                    onToggle = toggle,
                    onNext = next,
                    onPrevious = previous
                )
                if (!isCar) MeowvNavigationBar(page) { page = it }
            }
            SnackbarHost(
                snackbar,
                // 让出系统栏高度后再按播放器/导航栏厚度上移，避免提示压在播放器上。
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = if (isCar) 96.dp else 152.dp)
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
        }
    }
}

@Composable
private fun playbackErrorText(error: TuneError?, statusCode: Int?): String? = when (error) {
    null -> null
    TuneError.NETWORK_OFFLINE -> stringResource(R.string.playback_offline)
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
    directoryRefreshing: Boolean,
    onPlayback: (PlayerCommand) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onRefreshRadio: () -> Unit,
    onRefreshFromSettings: () -> Unit,
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
                isCar = isCar,
                settings = settings,
                refreshing = directoryRefreshing,
                onSettingsChange = onSettingsChange,
                onRefreshDirectory = onRefreshFromSettings,
                onClearDirectoryCache = onClearDirectoryCache
            )
        }
    }
}
