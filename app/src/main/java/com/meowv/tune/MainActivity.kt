package com.meowv.tune

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.music.MusicException
import com.meowv.tune.domain.music.MusicFailure
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.meowv.tune.music.MusicSearchViewModel
import com.meowv.tune.ui.MusicSearchScreen
import com.meowv.tune.music.MusicViewModel
import com.meowv.tune.settings.SettingsViewModel
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.meowv.tune.ui.TuneApp
import com.meowv.tune.ui.theme.TuneTheme
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.radio.CountryResolver
import com.meowv.tune.radio.DirectoryRefresh
import com.meowv.tune.radio.RadioViewModel
import com.meowv.tune.update.LocalVersion
import com.meowv.tune.update.UpdateChecker
import com.meowv.tune.update.UpdateViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val tuneApp get() = application as TuneApplication
    private var firstFrameReady = false
    private var hasEnteredForeground = false
    private var carShell = false
    private var mediaLease: AutoCloseable? = null
    private var playbackEntryJob: Job? = null
    private var resumeRadioOnEntry = false
    private var notificationRequested = false
    private val snackbar = SnackbarHostState()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    private val musicModel by viewModels<MusicViewModel> {
        viewModelFactory { initializer {
            val app = tuneApp
            MusicViewModel(app.musicRepository,
                requestPlayback = { seed -> app.playerController.dispatch(PlayerCommand.PlayMusic(seed = seed)) },
                playback = app.playerController.state,
                feedStore = app.musicFeed, listening = app.musicListening)
        } }
    }
    private val musicSearchModel by viewModels<MusicSearchViewModel> {
        viewModelFactory { initializer {
            MusicSearchViewModel(tuneApp.musicRepository,
                play = { track ->
                    val controller = tuneApp.playerController
                    controller.dispatch(PlayerCommand.PlayMusic(track))
                    val result = withTimeoutOrNull(18_000) {
                        controller.state.first {
                            it.source == PlaybackSource.MUSIC && (it.phase == PlaybackPhase.ERROR ||
                                it.phase == PlaybackPhase.PLAYING)
                        }
                    }
                    if (result == null || result.phase == PlaybackPhase.ERROR || result.music?.id != track.id)
                        throw MusicException(result?.musicFailure ?: MusicFailure.PLAYBACK_UNAVAILABLE)
                }, historyStore = tuneApp.musicSearchHistory)
        } }
    }
    private val settingsModel by viewModels<SettingsViewModel> {
        viewModelFactory { initializer { SettingsViewModel(tuneApp.userSettings) } }
    }
    private val radioModel by viewModels<RadioViewModel> {
        viewModelFactory {
            initializer {
                RadioViewModel(tuneApp.radioRepository, tuneApp.playerController, CountryResolver(applicationContext),
                    cache = tuneApp.directoryCache, queue = tuneApp.radioQueue, countryOptions = tuneApp::loadCountries)
            }
        }
    }
    private val updateModel by viewModels<UpdateViewModel> {
        viewModelFactory {
            initializer {
                UpdateViewModel(
                    repository = tuneApp.updateRepository,
                    checker = UpdateChecker(),
                    localVersion = ::localVersion,
                    downloader = tuneApp.apkDownloader,
                    verifier = tuneApp.apkVerifier,
                    installer = tuneApp.apkInstaller,
                    store = tuneApp.installStore
                )
            }
        }
    }

    private fun localVersion(): LocalVersion {
        val info = packageManager.getPackageInfo(packageName, 0)
        return LocalVersion(
            packageName = packageName,
            versionName = info.versionName ?: BuildConfig.VERSION_NAME,
            versionCode = info.longVersionCode,
            sdkInt = Build.VERSION.SDK_INT,
            channel = tuneApp.updateChannel
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val background = getColor(R.color.background)
        enableEdgeToEdge(SystemBarStyle.dark(background), SystemBarStyle.dark(background))
        splash.setKeepOnScreenCondition { !firstFrameReady }
        lifecycleScope.launch {
            settingsModel.settings.filterNotNull().map { it.scope }.distinctUntilChanged().collect(radioModel::setScope)
        }
        // 设置页刷新的结果统一走同一条 Snackbar：先复位状态再提示，避免旋转后重复弹出。
        lifecycleScope.launch {
            radioModel.refreshState.collect { state ->
                when (state) {
                    DirectoryRefresh.Success -> {
                        radioModel.acknowledgeRefresh()
                        snackbar.showSnackbar(getString(R.string.data_refreshed))
                    }
                    is DirectoryRefresh.Failed -> {
                        radioModel.acknowledgeRefresh()
                        snackbar.showSnackbar(
                            getString(R.string.data_refresh_failed, refreshFailureReason(state.error))
                        )
                    }
                    DirectoryRefresh.Idle, DirectoryRefresh.Running -> Unit
                }
            }
        }
        val activation = tuneApp.musicActivation
        if (activation != null) lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { activation.runWhileForeground() }
        }
        resumeRadioOnEntry = savedInstanceState == null && !tuneApp.autoPlayAttempted
        setContent {
            val musicDevice = activation?.state?.collectAsStateWithLifecycle()?.value
            val musicBrowse by musicModel.browse.collectAsStateWithLifecycle()
            val musicState by musicModel.state.collectAsStateWithLifecycle()
            val musicSearch by musicSearchModel.state.collectAsStateWithLifecycle()
            val searchList = key(musicSearch.query, musicSearch.resultVersion) {
                androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            }
            val searchContent: @androidx.compose.runtime.Composable (Boolean) -> Unit = { isCar ->
                MusicSearchScreen(
                    musicSearch, musicSearchModel::queryChanged, musicSearchModel::search,
                    musicSearchModel::more,
                    { track -> ensureNotificationPermission(); musicSearchModel.select(track) },
                    musicSearchModel::selectHistory, musicSearchModel::clearHistory,
                    musicSearchModel::close, searchList, isCar = isCar
                )
            }
            val settings by settingsModel.settings.collectAsStateWithLifecycle()
            val storageError by settingsModel.storageError.collectAsStateWithLifecycle()
            val countries by radioModel.countries.collectAsStateWithLifecycle()
            val refresh by radioModel.refreshState.collectAsStateWithLifecycle()
            val update by updateModel.state.collectAsStateWithLifecycle()
            val local by updateModel.local.collectAsStateWithLifecycle()
            val install by updateModel.installState.collectAsStateWithLifecycle()
            val upgradedTo by updateModel.upgradedTo.collectAsStateWithLifecycle()
            TuneTheme {
                TuneApp(
                    settings = settings ?: TuneSettings(),
                    musicState = musicState,
                    musicBrowse = musicBrowse,
                    musicDevice = musicDevice,
                    onMusicActivated = musicModel::onDeviceActivated,
                    onEnterMusic = musicModel::enter,
                    onLoadMusic = musicModel::loadFeed,
                    onSearchMusic = musicSearchModel::open,
                    musicSearchOpen = musicSearch.visible,
                    onCloseMusicSearch = musicSearchModel::close,
                    musicSearchContent = searchContent,
                    storageError = storageError,
                    radio = radioModel.radioState,
                    playback = tuneApp.playerController.state,
                    playbackConnected = tuneApp.playerController.connected,
                    sleepTimer = tuneApp.sleepTimer.state,
                    snackbar = snackbar,
                    directoryRefreshing = refresh == DirectoryRefresh.Running,
                    localVersion = local,
                    update = update,
                    install = install,
                    upgradedTo = upgradedTo,
                    onCheckUpdate = updateModel::check,
                    onDownloadUpdate = updateModel::download,
                    onCancelDownload = updateModel::cancelDownload,
                    onInstallUpdate = updateModel::installApk,
                    onPlayback = ::dispatchPlayback,
                    onSetSleepTimer = { minutes ->
                        if (minutes == null) {
                            tuneApp.sleepTimer.cancel()
                        } else {
                            runCatching { tuneApp.sleepTimer.start(minutes) }
                        }
                    },
                    onSelectTarget = radioModel::selectTarget,
                    onDisplayModeChange = { mode ->
                        settingsModel.update { it.copy(displayMode = mode) }
                    },
                    countries = countries,
                    onLoadCountries = radioModel::loadCountries,
                    onRefreshRadio = { settingsModel.settings.value?.let { radioModel.refresh(it.scope) } },
                    onRefreshAll = {
                        settingsModel.settings.value?.let { radioModel.refreshManually(it.scope) }
                        musicModel.loadFeed()
                    },
                    onLoadMore = radioModel::loadMore,
                    onSettingsChange = settingsModel::update,
                    onClearCache = ::clearCaches,
                    onShellChanged = {
                        carShell = it
                        applySystemBars()
                    },
                    onFirstLayout = { firstFrameReady = true }
                )

            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    // 恢复上次电台信息；仅在开启自动播放时，等待目录与服务就绪后起播。
    private suspend fun resumeLastStation() {
        val settings = settingsModel.settings.filterNotNull().first()
        val uuid = settings.lastSuccessfulStation
        // 旧记录只有 UUID 时补齐展示元数据；关闭自动播放也恢复目标，但不发播放命令。
        if (uuid != null && settings.lastPlayedStation == null && !settings.autoPlay) {
            val remembered = runCatching { tuneApp.radioRepository.stationByUuid(uuid) }.getOrNull()
            if (remembered != null) tuneApp.userSettings.refreshRememberedStation(remembered)
        }
        if (!settings.autoPlay) return
        withTimeoutOrNull(8000) {
            radioModel.radioState.first { !it.isLoading && (it.stations.isNotEmpty() || it.error != null) }
        } ?: return
        // 目录可能比媒体服务连接更快就绪，等控制器可用再发命令。
        withTimeoutOrNull(5000) { tuneApp.playerController.connected.first { it } } ?: return
        if (!tuneApp.userSettings.settings.first().autoPlay ||
            tuneApp.playerController.state.value.phase != com.meowv.tune.domain.PlaybackPhase.IDLE
        ) return
        if (uuid != null) dispatchPlayback(PlayerCommand.Resume(uuid))
        else radioModel.radioState.value.stations.firstOrNull()?.let { dispatchPlayback(PlayerCommand.Play(it)) }
    }

    private fun dispatchPlayback(command: PlayerCommand) {
        if (command !is PlayerCommand.Resume) ensureNotificationPermission()
        lifecycleScope.launch {
            try {
                tuneApp.playerController.dispatch(command)
            } catch (_: TuneFailure) {
                Toast.makeText(this@MainActivity, R.string.playback_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // API 33+ 才有运行时通知权限。拒绝后前台播放仍可用，只是通知栏与锁屏不显示控制。
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (notificationRequested) return
        notificationRequested = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        // 从系统安装器/授权页返回时补查真实结果，不依赖 Activity 一直存活。
        updateModel.onResume()
    }

    override fun onStart() {
        super.onStart()
        if (hasEnteredForeground) musicModel.onForeground()
        hasEnteredForeground = true
        val controller = tuneApp.playerController
        mediaLease = controller.acquire()
        playbackEntryJob = lifecycleScope.launch {
            withTimeoutOrNull(5000) { controller.connected.first { it } } ?: return@launch
            // 「自动播放」是全局开关：关闭时音乐也只恢复上下文、不自动发声。
            val autoPlay = withTimeoutOrNull(1_000) {
                settingsModel.settings.filterNotNull().first().autoPlay
            } ?: false
            val restoredMusic = controller.restoreMusicOnEntry(autoPlay)
            if (resumeRadioOnEntry && !tuneApp.autoPlayAttempted) {
                resumeRadioOnEntry = false
                tuneApp.autoPlayAttempted = true
                if (!restoredMusic) resumeLastStation()
            }
        }
    }

    override fun onStop() {
        playbackEntryJob?.cancel()
        tuneApp.playerController.checkpointMusic()
        mediaLease?.close()
        mediaLease = null
        super.onStop()
    }

    // 清理缓存覆盖电台目录与图片缓存；音乐播放断点属于续播状态，不在此清除。
    private fun clearCaches() {
        lifecycleScope.launch {
            val message = try {
                tuneApp.directoryCache.clear()
                withContext(Dispatchers.IO) {
                    runCatching { SingletonImageLoader.get(this@MainActivity).diskCache?.clear() }
                }
                R.string.cache_cleared
            } catch (_: IOException) {
                R.string.cache_clear_failed
            }
            snackbar.showSnackbar(getString(message))
        }
    }

    // 刷新失败只说人话，不把异常类型暴露给用户。
    private fun refreshFailureReason(error: TuneError?): String = getString(
        when (error) {
            TuneError.NETWORK_OFFLINE -> R.string.directory_reason_offline
            TuneError.TIMEOUT -> R.string.directory_reason_timeout
            else -> R.string.directory_reason_unavailable
        }
    )

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.statusBars())
            if (carShell) {
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}
