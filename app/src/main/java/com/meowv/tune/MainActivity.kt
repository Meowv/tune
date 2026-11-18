package com.meowv.tune

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.meowv.tune.music.MusicSearchViewModel
import com.meowv.tune.ui.MusicSearchScreen
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    private var carShell = false
    private var lightMusicBars = false
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
                requestPlayback = { app.playerController.dispatch(PlayerCommand.PlayMusic()) },
                playback = app.playerController.state)
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
                })
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
                        snackbar.showSnackbar(getString(R.string.directory_refreshed))
                    }
                    is DirectoryRefresh.Failed -> {
                        radioModel.acknowledgeRefresh()
                        snackbar.showSnackbar(
                            getString(R.string.directory_refresh_failed, refreshFailureReason(state.error))
                        )
                    }
                    DirectoryRefresh.Idle, DirectoryRefresh.Running -> Unit
                }
            }
        }
        resumeRadioOnEntry = savedInstanceState == null && !tuneApp.autoPlayAttempted
        setContent {
            val musicBrowse by musicModel.browse.collectAsStateWithLifecycle()
            val musicState by musicModel.state.collectAsStateWithLifecycle()
            val musicSearch by musicSearchModel.state.collectAsStateWithLifecycle()
            val searchList = key(musicSearch.query) { rememberLazyListState() }
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
                    onEnterMusic = musicModel::enter,
                    onMusicSection = musicModel::selectSection,
                    onLoadMusic = musicModel::loadFeed,
                    onStartMusic = { ensureNotificationPermission(); musicModel.startListening() },
                    onCancelMusic = { musicModel.cancel(); dispatchPlayback(PlayerCommand.CancelMusic) },
                    onSearchMusic = musicSearchModel::open,
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
                    onRefreshFromSettings = { settingsModel.settings.value?.let { radioModel.refreshManually(it.scope) } },
                    onLoadMore = radioModel::loadMore,
                    onSettingsChange = settingsModel::update,
                    onClearDirectoryCache = ::clearDirectoryCache,
                    onShellChanged = {
                        carShell = it
                        applySystemBars()
                    },
                    onLightSystemBars = { light ->
                        if (lightMusicBars != light) {
                            lightMusicBars = light
                            applySystemBars()
                        }
                    },
                    onFirstLayout = { firstFrameReady = true }
                )
                if (musicSearch.visible) Dialog(onDismissRequest = musicSearchModel::close,
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    val light = !isSystemInDarkTheme()
                    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                    SideEffect {
                        dialogWindow?.let {
                            if (Build.VERSION.SDK_INT < 35) {
                                @Suppress("DEPRECATION")
                                it.navigationBarColor = if (light) 0xFFF8F7FC.toInt() else getColor(R.color.background)
                            }
                            WindowCompat.getInsetsController(it, it.decorView).apply {
                                isAppearanceLightStatusBars = light
                                isAppearanceLightNavigationBars = light
                            }
                        }
                    }
                    TuneTheme(light = light) {
                        MusicSearchScreen(musicSearch, musicSearchModel::queryChanged, musicSearchModel::search,
                            musicSearchModel::more,
                            { track -> ensureNotificationPermission(); musicSearchModel.select(track) }, musicSearchModel::close, searchList)
                    }
                }
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
        val controller = tuneApp.playerController
        mediaLease = controller.acquire()
        playbackEntryJob = lifecycleScope.launch {
            withTimeoutOrNull(5000) { controller.connected.first { it } } ?: return@launch
            val restoredMusic = controller.restoreMusicOnEntry()
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

    private fun clearDirectoryCache() {
        lifecycleScope.launch {
            val message = try {
                tuneApp.directoryCache.clear()
                R.string.directory_cache_cleared
            } catch (_: IOException) {
                R.string.directory_cache_clear_failed
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

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        if (Build.VERSION.SDK_INT < 35) {
            val scrim = if (lightMusicBars) 0xFFF8F7FC.toInt() else getColor(R.color.background)
            window.statusBarColor = scrim
            window.navigationBarColor = scrim
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightMusicBars
            isAppearanceLightNavigationBars = lightMusicBars
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (carShell) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
