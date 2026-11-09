package com.meowv.tune

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
    private var mediaLease: AutoCloseable? = null
    private var notificationRequested = false
    private val snackbar = SnackbarHostState()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show()
            }
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
                UpdateViewModel(tuneApp.updateRepository, UpdateChecker(), localVersion())
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
        // 只有普通前台冷启动才尝试恢复；进程重建、回前台和配置变更都不重开流。
        if (savedInstanceState == null && !tuneApp.autoPlayAttempted) {
            tuneApp.autoPlayAttempted = true
            resumeLastStation()
        }
        setContent {
            val settings by settingsModel.settings.collectAsStateWithLifecycle()
            val storageError by settingsModel.storageError.collectAsStateWithLifecycle()
            val countries by radioModel.countries.collectAsStateWithLifecycle()
            val refresh by radioModel.refreshState.collectAsStateWithLifecycle()
            val update by updateModel.state.collectAsStateWithLifecycle()
            TuneTheme {
                TuneApp(
                    settings = settings ?: TuneSettings(),
                    storageError = storageError,
                    radio = radioModel.radioState,
                    playback = tuneApp.playerController.state,
                    playbackConnected = tuneApp.playerController.connected,
                    snackbar = snackbar,
                    directoryRefreshing = refresh == DirectoryRefresh.Running,
                    localVersion = updateModel.local,
                    update = update,
                    onCheckUpdate = updateModel::check,
                    onPlayback = ::dispatchPlayback,
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
                    onFirstLayout = { firstFrameReady = true }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    // 用户开启自播时恢复最后成功台；首页目录就绪后再尝试，失败保持可操作电台页。
    private fun resumeLastStation() {
        lifecycleScope.launch {
            val settings = settingsModel.settings.filterNotNull().first()
            val uuid = settings.lastSuccessfulStation
            if (!settings.autoPlay || uuid == null) return@launch
            withTimeoutOrNull(8000) {
                radioModel.radioState.first { !it.isLoading && (it.stations.isNotEmpty() || it.error != null) }
            } ?: return@launch
            // 目录可能比媒体服务连接更快就绪，等控制器可用再发命令。
            withTimeoutOrNull(5000) { tuneApp.playerController.connected.first { it } } ?: return@launch
            dispatchPlayback(PlayerCommand.Resume(uuid))
        }
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

    override fun onStart() {
        super.onStart()
        mediaLease = tuneApp.playerController.acquire()
    }

    override fun onStop() {
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

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (carShell) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
