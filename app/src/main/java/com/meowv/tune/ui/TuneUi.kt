package com.meowv.tune.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.meowv.tune.update.LocalVersion
import com.meowv.tune.update.UpdateUiState
import com.meowv.tune.update.install.InstallState
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
    localVersion: LocalVersion,
    update: UpdateUiState,
    install: InstallState,
    upgradedTo: String?,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallUpdate: () -> Unit,
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
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showScopePicker) { if (showScopePicker) onLoadCountries(false) }
    // 进入版本页时检查一次；重复进入由 ViewModel 的进行中判断挡住，不会叠加请求。
    LaunchedEffect(showUpdate) { if (showUpdate) onCheckUpdate() }
    // 首次打开设置页时补一次后台检查，让入口副标题能显示真实状态。
    LaunchedEffect(page, update) {
        if (page == TunePage.Settings && update is UpdateUiState.Idle) onCheckUpdate()
    }
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
        val current = target
        if (current == null) {
            // 还没有当前台时，第一下滑动直接从列表头/尾起播，而不是毫无反应。
            if (offset > 0) list.firstOrNull() else list.lastOrNull()
        } else {
            val index = list.indexOfFirst { it.uuid == current.uuid }
            if (index < 0) null else list.getOrNull(index + offset)
        }
    }
    // 播放中的切台统一交给播放队列按当前顺序处理，底栏与系统媒体键走同一条路径；
    // 暂停/空闲时换台即起播：滑动或媒体键换到的台，用户意图就是「听这个」。
    val randomTarget: () -> Station? = {
        radioState.stations.filter { it.uuid != target?.uuid }.randomOrNull()
    }
    val playTarget: (Station?) -> Unit = { station ->
        station?.let {
            onSelectTarget(it)
            onPlayback(PlayerCommand.Play(it))
        }
    }
    val next: () -> Unit = {
        if (!paused && !idle) onPlayback(PlayerCommand.Next)
        else playTarget(if (shuffle) randomTarget() else adjacent(1))
    }
    val previous: () -> Unit = {
        if (!paused && !idle) onPlayback(PlayerCommand.Previous)
        else playTarget(if (shuffle) randomTarget() else adjacent(-1))
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
            val updateSubtitle = updateSubtitle(update, localVersion.versionName)
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
                            Box(Modifier.fillMaxSize()) {
                                pageContent(
                                    target, pageStates, isCar, settings, radio, playbackState, connected,
                                    directoryRefreshing, updateSubtitle, onPlayback, onSelectTarget,
                                    onDisplayModeChange, onSettingsChange, onRefreshRadio,
                                    onRefreshFromSettings, onLoadMore, onClearDirectoryCache,
                                    { showUpdate = true }, { showScopePicker = true }
                                )
                            }
                        }
                    }
                } else {
                    Crossfade(page, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(220), label = "page_switch") { target ->
                        Box(Modifier.fillMaxSize()) {
                            pageContent(
                                target, pageStates, isCar, settings, radio, playbackState, connected,
                                directoryRefreshing, updateSubtitle, onPlayback, onSelectTarget,
                                onDisplayModeChange, onSettingsChange, onRefreshRadio,
                                onRefreshFromSettings, onLoadMore, onClearDirectoryCache,
                                { showUpdate = true }, { showScopePicker = true }
                            )
                        }
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
            // 版本页覆盖整个外壳（含播放器与导航），保持主菜单仍是三个。
            if (showUpdate) {
                UpdateScreen(
                    isCar = isCar,
                    local = localVersion,
                    state = update,
                    install = install,
                    upgradedTo = upgradedTo,
                    onBack = { showUpdate = false },
                    onCheck = onCheckUpdate,
                    onDownload = onDownloadUpdate,
                    onCancelDownload = onCancelDownload,
                    onInstall = onInstallUpdate,
                    modifier = Modifier.fillMaxSize()
                        .background(TuneColors.Background)
                        .windowInsetsPadding(
                            if (isCar) WindowInsets.displayCutout else WindowInsets.safeDrawing
                        )
                )
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
private fun updateSubtitle(state: UpdateUiState, versionName: String): String {
    val current = "v$versionName"
    return when (state) {
        UpdateUiState.Checking -> stringResource(R.string.update_subtitle_checking, current)
        is UpdateUiState.UpToDate -> stringResource(R.string.update_subtitle_latest, current)
        is UpdateUiState.UpdateAvailable ->
            stringResource(R.string.update_subtitle_available, current, "v${state.info.versionName}")
        is UpdateUiState.Error -> stringResource(R.string.update_subtitle_failed, current)
        is UpdateUiState.Downgrade -> stringResource(R.string.update_subtitle_downgrade, current)
        UpdateUiState.Idle -> stringResource(R.string.update_subtitle_idle, current)
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
    updateSubtitle: String,
    onPlayback: (PlayerCommand) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onRefreshRadio: () -> Unit,
    onRefreshFromSettings: () -> Unit,
    onLoadMore: () -> Unit,
    onClearDirectoryCache: () -> Unit,
    onOpenUpdate: () -> Unit,
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
                updateSubtitle = updateSubtitle,
                onSettingsChange = onSettingsChange,
                onRefreshDirectory = onRefreshFromSettings,
                onClearDirectoryCache = onClearDirectoryCache,
                onOpenUpdate = onOpenUpdate
            )
        }
    }
}
