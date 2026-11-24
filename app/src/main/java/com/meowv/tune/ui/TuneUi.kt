package com.meowv.tune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.meowv.tune.music.MusicUiState
import com.meowv.tune.music.MusicDevicePhase
import com.meowv.tune.music.MusicDeviceState
import com.meowv.tune.music.MusicFeed
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.radio.CountryOptions
import com.meowv.tune.playback.SleepTimerState
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneMotion
import com.meowv.tune.update.LocalVersion
import com.meowv.tune.update.UpdateUiState
import com.meowv.tune.update.install.InstallState
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun TuneApp(
    settings: TuneSettings,
    storageError: Boolean,
    radio: StateFlow<RadioUiState>,
    playback: StateFlow<PlayerUiState>,
    playbackConnected: StateFlow<Boolean>,
    sleepTimer: StateFlow<SleepTimerState>,
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
    onSetSleepTimer: (Int?) -> Unit,
    onSelectTarget: (Station) -> Unit,
    onDisplayModeChange: (RadioDisplayMode) -> Unit,
    countries: CountryOptions,
    onLoadCountries: (Boolean) -> Unit,
    onRefreshRadio: () -> Unit,
    onRefreshAll: () -> Unit,
    onLoadMore: () -> Unit,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onClearCache: () -> Unit,
    onShellChanged: (Boolean) -> Unit,
    onFirstLayout: () -> Unit,
    musicState: MusicUiState = MusicUiState.NotConfigured,
    onSearchMusic: () -> Unit = {},
    musicSearchOpen: Boolean = false,
    onCloseMusicSearch: () -> Unit = {},
    musicSearchContent: @Composable (Boolean, @Composable () -> Unit) -> Unit = { _, _ -> },
    musicBrowse: MusicFeed = MusicFeed(),
    onEnterMusic: () -> Unit = {},
    onLoadMusic: () -> Unit = {},
    musicDevice: MusicDeviceState? = null,
    onMusicActivated: () -> Unit = {},
    onRetryMusicDevice: () -> Unit = {},
    onReconnectPlayback: (() -> Unit)? = null
) {
    val connected by playbackConnected.collectAsStateWithLifecycle()
    val playbackUi = playback.collectAsStateWithLifecycle()
    val playbackState by remember(playbackUi) {
        derivedStateOf(structuralEqualityPolicy()) { playbackUi.value.copy(positionMs = null) }
    }
    val radioState by radio.collectAsStateWithLifecycle()
    val sleepTimerState by sleepTimer.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(TunePage.Radio) }
    var showScopePicker by rememberSaveable { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(page, musicDevice?.phase) {
        if (page == TunePage.Music) {
            if (musicDevice?.phase == MusicDevicePhase.ACTIVE) onMusicActivated() else onEnterMusic()
        }
    }
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
    val navigationMillis = if (rememberMotionEnabled()) TuneMotion.Navigation else 0
    LaunchedEffect(page) {
        if (pagerState.currentPage != page.ordinal) {
            pagerState.animateScrollToPage(page.ordinal, animationSpec = tween(navigationMillis))
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            TunePage.entries.getOrNull(settled)?.let { if (it != page) page = it }
        }
    }
    val errorText = if (playbackState.source == PlaybackSource.MUSIC) musicPlaybackErrorText(playbackState)
        else playbackErrorText(playbackState.error, playbackState.statusCode)
    val operationErrorText = playbackState.operationFailure?.let { failure ->
        if (playbackState.source == PlaybackSource.MUSIC && failure.error == TuneError.PLAYBACK)
            stringResource(R.string.music_failed)
        else playbackErrorText(failure.error, failure.statusCode)
    }
    LaunchedEffect(playbackState.operationFailure) {
        if (operationErrorText != null) snackbar.showSnackbar(operationErrorText, duration = SnackbarDuration.Short)
    }
    LaunchedEffect(errorText, nowPlayingOpen) {
        if (!nowPlayingOpen && errorText != null) {
            snackbar.showSnackbar(errorText, duration = SnackbarDuration.Short)
        }
    }

    val target = resolvePlayerTarget(playbackState, radioState, settings.lastPlayedStation,
        settings.lastSuccessfulStation)
    val idle = playbackState.phase == PlaybackPhase.IDLE
    val displayRadioState = radioState.copy(
        selectedStationUuid = radioState.selectedStationUuid ?: settings.lastSuccessfulStation
    )
    val shuffle = settings.playbackOrder == PlaybackOrder.SHUFFLE
    val toggleOrder: () -> Unit = {
        onSettingsChange { current ->
            current.copy(playbackOrder = if (current.playbackOrder == PlaybackOrder.SHUFFLE)
                PlaybackOrder.SEQUENTIAL else PlaybackOrder.SHUFFLE)
        }
    }
    val onNav: (TunePage) -> Unit = { targetPage ->
        if (musicSearchOpen && targetPage != TunePage.Music) onCloseMusicSearch()
        showUpdate = false
        nowPlayingOpen = false
        page = targetPage
    }
    val cancel = pendingPlaybackCommand(playbackState)
    // 全屏页、通知和媒体键都交给播放队列，暂停时换台也会继续起播。
    val next: () -> Unit = { onPlayback(PlayerCommand.Next) }
    val previous: () -> Unit = { onPlayback(PlayerCommand.Previous) }
    val toggle: () -> Unit = {
        // 全屏始终操作它展示的台；目录浏览选择由卡片点击显式起播。
        val station = target
        when {
            !connected -> onReconnectPlayback?.invoke()
            cancel != null -> onPlayback(cancel)
            playbackState.source == PlaybackSource.MUSIC -> onPlayback(PlayerCommand.TogglePause)
            station == null -> Unit
            // 失败态一律按目标台重新起播：引擎侧可能从未建立播放意图（例如离线起播），
            // 复用 TogglePause 会退化成空操作。
            playbackState.phase == PlaybackPhase.ERROR -> onPlayback(PlayerCommand.Play(station))
            idle ->
                onPlayback(PlayerCommand.Play(station))
            else -> onPlayback(PlayerCommand.TogglePause)
        }
    }
    val visibleStation = target
    val fullscreenTransition = remember { MutableTransitionState(false) }
    LaunchedEffect(nowPlayingOpen) {
        fullscreenTransition.targetState = nowPlayingOpen
    }
    val fullscreenOccupied = nowPlayingOpen ||
        !fullscreenTransition.isIdle || fullscreenTransition.currentState
    val fullscreenFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(nowPlayingOpen) {
        if (nowPlayingOpen) focusManager.clearFocus(force = true)
    }
    val rotation = rememberSaveable(saver = PlayerRotationState.Saver) { PlayerRotationState() }
    val shellEnabled = !fullscreenOccupied && !showScopePicker
    BackHandler(enabled = fullscreenOccupied && !showUpdate && !showScopePicker) {
        nowPlayingOpen = false
    }
    val motionEnabled = rememberMotionEnabled()
    val fullscreenAnimMillis = if (motionEnabled) TuneMotion.Fullscreen else 0
    val searchVisible = musicSearchOpen && !fullscreenOccupied
    LaunchedEffect(musicSearchOpen) {
        if (!musicSearchOpen) pageStates.removeState("music_search")
    }

    Surface(color = TuneColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowWidth = maxWidth
            val windowHeight = maxHeight
            val isCar = isCarWindow(windowWidth.value, windowHeight.value)
            LaunchedEffect(isCar) { onShellChanged(isCar) }
            val carInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
            val updateSubtitle = updateSubtitle(update, localVersion.versionName)
            val playerEntry: @Composable () -> Unit = {
                CompactMediaBar(
                    state = playbackState,
                    station = target,
                    connected = connected,
                    rotation = rotation,
                    isCar = isCar,
                    enabled = shellEnabled,
                    animationMillis = fullscreenAnimMillis,
                    onToggle = toggle,
                    canReconnect = onReconnectPlayback != null,
                    onNext = next,
                    onPrevious = previous,
                    onOpen = {
                        if (playbackState.source == PlaybackSource.RADIO && playbackState.station == null && target != null) {
                            onSelectTarget(target)
                        }
                        showUpdate = false
                        nowPlayingOpen = true
                    }
                )
            }
            val updateContent: @Composable (Modifier) -> Unit = { modifier ->
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
                    modifier = modifier
                )
            }
            LaunchedEffect(showUpdate) {
                if (showUpdate) focusManager.clearFocus(force = true)
            }
            Row(
                Modifier.fillMaxSize()
                    .then(if (isCar) Modifier.windowInsetsPadding(carInsets) else Modifier)
            ) {
                if (isCar) {
                    val density = LocalDensity.current
                    val direction = LocalLayoutDirection.current
                    val insets = carInsets
                    val horizontalInsets = with(density) {
                        (insets.getLeft(density, direction) + insets.getRight(density, direction)).toDp()
                    }
                    val railWidth = carNavigationWidth((windowWidth - horizontalInsets).value, windowHeight.value).dp
                    MeowvNavigationRail(
                        page,
                        railWidth,
                        if (searchVisible) Modifier.imePadding() else Modifier,
                        onPage = onNav
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize()
                            .then(if (isCar) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                            .onGloballyPositioned { onFirstLayout() }
                            .testTag(if (isCar) "shell_car" else "shell_phone")
                            .focusProperties {
                                onEnter = {
                                    if (showUpdate || musicSearchOpen) {
                                        cancelFocusChange()
                                    } else if (fullscreenOccupied &&
                                        (!fullscreenTransition.currentState || !fullscreenFocus.requestFocus())) {
                                        cancelFocusChange()
                                    }
                                }
                            }
                            .focusGroup()
                            .then(
                                if (!shellEnabled || showUpdate || musicSearchOpen) {
                                    Modifier.semantics { hideFromAccessibility() }
                                } else Modifier
                            )
                    ) {
                        if (storageError) {
                            Text(
                                stringResource(R.string.settings_storage_error), color = TuneColors.Text,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        TunePager(
                            pagerState, Modifier.weight(1f).fillMaxWidth(), pageStates, isCar,
                            settings, displayRadioState, playbackState, connected, directoryRefreshing,
                            updateSubtitle, onPlayback, onSelectTarget, onDisplayModeChange,
                            onSettingsChange, onRefreshRadio, onRefreshAll, onLoadMore,
                            onClearCache, { showUpdate = true }, { showScopePicker = true },
                            musicState = musicState, onSearchMusic = onSearchMusic,
                            musicBrowse = musicBrowse, onLoadMusic = onLoadMusic, musicDevice = musicDevice,
                            directoryAnimationsEnabled = shellEnabled && !showUpdate && !musicSearchOpen,
                            onOpenMusicDevice = { onNav(TunePage.Settings) },
                            onRetryMusicDevice = onRetryMusicDevice
                        )
                        if (!musicSearchOpen) {
                            if (isCar) {
                                if (!fullscreenOccupied && !showUpdate) playerEntry()
                            } else {
                                MeowvBottomDock(page, playerEntry, onNav)
                            }
                        }
                    }
                    if (!shellEnabled || showUpdate || musicSearchOpen) {
                        Box(Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        })
                    }
                    if (isCar && showUpdate) {
                        Column(Modifier.fillMaxSize().background(TuneColors.Background)) {
                            updateContent(Modifier.weight(1f).fillMaxWidth())
                            playerEntry()
                        }
                    } else if (searchVisible) {
                        pageStates.SaveableStateProvider("music_search") {
                            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                            Column(
                                Modifier.fillMaxSize().background(TuneColors.Background)
                                    .windowInsetsPadding(if (isCar) WindowInsets.ime else WindowInsets.safeDrawing)
                            ) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    musicSearchContent(isCar, playerEntry)
                                }
                                if (!isCar && !imeVisible) {
                                    MeowvNavigationBar(page, onPage = onNav)
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
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
                        NowPlayingScreen(
                            isCar = isCar,
                            connected = connected,
                            state = playbackUi.value,
                            station = visibleStation,
                            shuffle = shuffle,
                            sleepTimer = sleepTimerState,
                            onCollapse = { nowPlayingOpen = false },
                            onToggle = toggle,
                            onNext = next,
                            onPrevious = previous,
                            onToggleShuffle = toggleOrder,
                            canReconnect = onReconnectPlayback != null,
                            onSetSleepTimer = onSetSleepTimer,
                            onSeek = { onPlayback(PlayerCommand.Seek(it)) },
                            onSelectStation = { onNav(if (playbackState.source == PlaybackSource.MUSIC) TunePage.Music else TunePage.Radio) },
                            modifier = Modifier.fillMaxSize().testTag("now_playing_screen")
                                .focusRequester(fullscreenFocus).focusGroup()
                        )
                    }
                }
            }
            if (!isCar && showUpdate) {
                updateContent(
                    Modifier.fillMaxSize().background(TuneColors.Background)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                )
            }
            SnackbarHost(
                snackbar,
                // 让出系统栏高度后再按播放器/导航栏厚度上移，避免提示压在播放器上。
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding((if (isCar) carInsets else WindowInsets.safeDrawing).only(WindowInsetsSides.Bottom))
                    .padding(
                        bottom = when {
                            nowPlayingOpen -> 24.dp
                            isCar -> CarMiniPlayerHeight + 8.dp
                            else -> PhoneMiniPlayerHeight + PhoneNavigationHeight + 11.dp
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
    onRefreshAll: () -> Unit,
    onLoadMore: () -> Unit,
    onClearCache: () -> Unit,
    onOpenUpdate: () -> Unit,
    onScopeClick: () -> Unit,
    directoryAnimationsEnabled: Boolean,
    musicBrowse: MusicFeed,
    musicDevice: MusicDeviceState?,
    onLoadMusic: () -> Unit,
    musicState: MusicUiState,
    onSearchMusic: () -> Unit,
    onOpenMusicDevice: () -> Unit,
    onRetryMusicDevice: () -> Unit
) {
    HorizontalPager(state = state, modifier = modifier, key = { TunePage.entries[it].name }) { index ->
        val target = TunePage.entries[index]
        val activePage = directoryAnimationsEnabled && index == state.settledPage
        Box(Modifier.fillMaxSize()) {
            pageContent(
                target, pageStates, isCar, settings, radioState, playback, connected,
                directoryRefreshing, updateSubtitle, onPlayback, onSelectTarget,
                onDisplayModeChange, onSettingsChange, onRefreshRadio,
                onRefreshAll, onLoadMore, onClearCache,
                onOpenUpdate, onScopeClick,
                activePage && !state.isScrollInProgress,
                musicBrowse, musicDevice, onLoadMusic, musicState, onSearchMusic,
                activePage = activePage,
                onOpenMusicDevice = onOpenMusicDevice,
                onRetryMusicDevice = onRetryMusicDevice
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
    onRefreshAll: () -> Unit,
    onLoadMore: () -> Unit,
    onClearCache: () -> Unit,
    onOpenUpdate: () -> Unit,
    onScopeClick: () -> Unit,
    directoryAnimationsEnabled: Boolean,
    musicBrowse: MusicFeed,
    musicDevice: MusicDeviceState?,
    onLoadMusic: () -> Unit,
    musicState: MusicUiState,
    onSearchMusic: () -> Unit,
    activePage: Boolean,
    onOpenMusicDevice: () -> Unit,
    onRetryMusicDevice: () -> Unit
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
                onScopeClick = onScopeClick,
                animationsEnabled = directoryAnimationsEnabled,
                activePage = activePage
            )
            TunePage.Music -> MusicScreen(isCar, musicState, musicBrowse, connected, onPlayback, onSearchMusic, onLoadMusic,
                displayMode = settings.musicDisplayMode,
                onDisplayModeChange = { mode -> onSettingsChange { it.copy(musicDisplayMode = mode) } },
                animationsEnabled = directoryAnimationsEnabled,
                activePage = activePage,
                device = musicDevice,
                onOpenDeviceSettings = onOpenMusicDevice,
                onRetryDevice = onRetryMusicDevice)
            TunePage.Settings -> SettingsScreen(
                isCar = isCar,
                settings = settings,
                // 刷新数据同时覆盖电台目录与音乐推荐列表，任一在途都禁用按钮并转圈。
                refreshing = directoryRefreshing || musicBrowse.loading,
                updateSubtitle = updateSubtitle,
                musicDevice = musicDevice,
                onSettingsChange = onSettingsChange,
                onRefreshAll = onRefreshAll,
                onClearCache = onClearCache,
                onOpenUpdate = onOpenUpdate
            )
        }
    }
}

internal fun resolvePlayerTarget(
    playback: PlayerUiState,
    radio: RadioUiState,
    remembered: Station?,
    rememberedId: java.util.UUID?
): Station? {
    if (playback.source == PlaybackSource.MUSIC) return null
    playback.station?.let { return it }
    radio.stations.firstOrNull { it.uuid == radio.selectedStationUuid }?.let { return it }
    radio.stations.firstOrNull { it.uuid == rememberedId }?.let { return it }
    val country = radio.resolvedCountryCode ?: radio.scope.countryCode
    if (remembered != null && (country == null || remembered.countryCode == country)) return remembered
    return radio.stations.firstOrNull()
}
