package com.meowv.tune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowv.tune.R
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.RadioDisplayMode
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.radio.CountryOptions
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.update.LocalVersion
import com.meowv.tune.update.UpdateUiState
import com.meowv.tune.update.install.InstallState
import java.util.Locale
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
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showScopePicker) { if (showScopePicker) onLoadCountries(false) }
    // 进入版本页时检查一次；重复进入由 ViewModel 的进行中判断挡住，不会叠加请求。
    LaunchedEffect(showUpdate) { if (showUpdate) onCheckUpdate() }
    // 首次打开设置页时补一次后台检查，让入口副标题能显示真实状态。
    LaunchedEffect(page, update) {
        if (page == TunePage.Settings && update is UpdateUiState.Idle) onCheckUpdate()
    }
    val pageStates = rememberSaveableStateHolder()
    val pagerState = rememberPagerState(initialPage = page.ordinal) { TunePage.entries.size }
    // 点菜单 → 滚到对应页；滑动停下 → 把页码写回 page，导航高亮与设置页副作用都跟着走。
    LaunchedEffect(page) {
        if (pagerState.currentPage != page.ordinal) pagerState.animateScrollToPage(page.ordinal)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            TunePage.entries.getOrNull(settled)?.let { if (it != page) page = it }
        }
    }
    val errorText = playbackErrorText(playbackState.error, playbackState.statusCode)
    LaunchedEffect(playbackState.error, nowPlayingOpen) {
        if (!nowPlayingOpen && errorText != null) {
            snackbar.showSnackbar(errorText, duration = SnackbarDuration.Short)
        }
    }

    val selectedId = radioState.selectedStationUuid ?: playbackState.station?.uuid
    // 只按 uuid 线性查找，比 remember(List) 的深比较更便宜。
    val target = radioState.stations.firstOrNull { it.uuid == selectedId } ?: playbackState.station
    val paused = playbackState.phase == PlaybackPhase.PAUSED
    val idle = playbackState.phase == PlaybackPhase.IDLE
    val shuffle = settings.playbackOrder == PlaybackOrder.SHUFFLE
    // Mini Player、全屏页、通知和媒体键都交给播放队列，暂停时换台也会继续起播。
    val next: () -> Unit = { onPlayback(PlayerCommand.Next) }
    val previous: () -> Unit = { onPlayback(PlayerCommand.Previous) }
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
    val visibleStation = playbackState.station
    val fullscreenTransition = remember { MutableTransitionState(false) }
    LaunchedEffect(nowPlayingOpen, visibleStation?.uuid) {
        fullscreenTransition.targetState = nowPlayingOpen && visibleStation != null
    }
    LaunchedEffect(visibleStation?.uuid) {
        if (visibleStation == null) nowPlayingOpen = false
    }
    val fullscreenOccupied = nowPlayingOpen ||
        !fullscreenTransition.isIdle || fullscreenTransition.currentState
    val showMiniPlayer = visibleStation != null && !fullscreenOccupied
    var lastVisibleStation by remember { mutableStateOf(visibleStation) }
    LaunchedEffect(visibleStation) {
        if (visibleStation != null) lastVisibleStation = visibleStation
    }
    BackHandler(enabled = nowPlayingOpen && !showUpdate && !showScopePicker) {
        nowPlayingOpen = false
    }
    val randomRangeName = when (settings.scope.mode) {
        ScopeMode.COUNTRY -> settings.scope.countryCode?.displayName(Locale.getDefault())
        ScopeMode.GLOBAL -> null
        ScopeMode.AUTO -> radioState.resolvedCountryCode?.displayName(Locale.getDefault())
    }
    val motionEnabled = rememberMotionEnabled()
    val fullscreenAnimMillis = if (motionEnabled) 280 else 0

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
                    .then(
                        if (fullscreenOccupied) {
                            Modifier.semantics { hideFromAccessibility() }
                        } else Modifier
                    )
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
                        TunePager(
                            pagerState, Modifier.weight(1f).fillMaxHeight(), pageStates, isCar,
                            settings, radioState, playbackState, connected, directoryRefreshing,
                            updateSubtitle, onPlayback, onSelectTarget, onDisplayModeChange,
                            onSettingsChange, onRefreshRadio, onRefreshFromSettings, onLoadMore,
                            onClearDirectoryCache, { showUpdate = true }, { showScopePicker = true }
                        )
                    }
                } else {
                    TunePager(
                        pagerState, Modifier.weight(1f).fillMaxWidth(), pageStates, isCar,
                        settings, radioState, playbackState, connected, directoryRefreshing,
                        updateSubtitle, onPlayback, onSelectTarget, onDisplayModeChange,
                        onSettingsChange, onRefreshRadio, onRefreshFromSettings, onLoadMore,
                        onClearDirectoryCache, { showUpdate = true }, { showScopePicker = true }
                    )
                }
                if (showMiniPlayer) {
                    MeowvMiniPlayer(
                        isCar = isCar,
                        state = playbackState,
                        station = visibleStation,
                        connected = connected,
                        onOpen = { nowPlayingOpen = true },
                        onToggle = toggle,
                        onNext = next,
                        onPrevious = previous
                    )
                }
                if (!isCar) MeowvNavigationBar(page) { page = it }
            }
            AnimatedVisibility(
                visibleState = fullscreenTransition,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(fullscreenAnimMillis)
                ) + fadeIn(tween(fullscreenAnimMillis)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(fullscreenAnimMillis)
                ) + fadeOut(tween(fullscreenAnimMillis))
            ) {
                val overlayStation = visibleStation ?: lastVisibleStation
                if (overlayStation != null) {
                    NowPlayingScreen(
                        isCar = isCar,
                        connected = connected,
                        state = playbackState,
                        station = overlayStation,
                        countryName = overlayStation.countryCode?.displayName(Locale.getDefault()),
                        randomRangeName = randomRangeName,
                        shuffle = shuffle,
                        onCollapse = { nowPlayingOpen = false },
                        onToggle = toggle,
                        onNext = next,
                        onPrevious = previous,
                        onToggleShuffle = {
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
                        modifier = Modifier.fillMaxSize().testTag("now_playing_screen")
                    )
                }
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
                    .padding(
                        bottom = when {
                            nowPlayingOpen -> 24.dp
                            showMiniPlayer -> if (isCar) 96.dp else 152.dp
                            isCar -> 16.dp
                            else -> 88.dp
                        }
                    )
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
internal fun playbackErrorText(error: TuneError?, statusCode: Int?): String? = when (error) {
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

/**
 * 三个主页面横向分页：点菜单滚到目标页，左右滑动切页，两者共用同一个 PagerState。
 * 页面里的纵向列表、电台卡片横滑各管一个方向，冲突交给 Compose 的嵌套滚动处理。
 */
@Composable
private fun TunePager(
    state: PagerState,
    modifier: Modifier,
    pageStates: androidx.compose.runtime.saveable.SaveableStateHolder,
    isCar: Boolean,
    settings: TuneSettings,
    radioState: RadioUiState,
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
    HorizontalPager(state = state, modifier = modifier, key = { TunePage.entries[it].name }) { index ->
        val target = TunePage.entries[index]
        Box(Modifier.fillMaxSize()) {
            pageContent(
                target, pageStates, isCar, settings, radioState, playback, connected,
                directoryRefreshing, updateSubtitle, onPlayback, onSelectTarget,
                onDisplayModeChange, onSettingsChange, onRefreshRadio,
                onRefreshFromSettings, onLoadMore, onClearDirectoryCache,
                onOpenUpdate, onScopeClick
            )
        }
    }
}

@Composable
private fun pageContent(
    page: TunePage,
    pageStates: androidx.compose.runtime.saveable.SaveableStateHolder,
    isCar: Boolean,
    settings: TuneSettings,
    radioState: RadioUiState,
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
                state = radioState,
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
