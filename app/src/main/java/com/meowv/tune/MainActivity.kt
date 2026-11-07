package com.meowv.tune

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.radio.CountryResolver
import com.meowv.tune.radio.RadioViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val tuneApp get() = application as TuneApplication
    private var firstFrameReady = false
    private var carShell = false
    private var mediaLease: AutoCloseable? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val background = getColor(R.color.background)
        enableEdgeToEdge(SystemBarStyle.dark(background), SystemBarStyle.dark(background))
        splash.setKeepOnScreenCondition { !firstFrameReady }
        lifecycleScope.launch {
            settingsModel.settings.filterNotNull().map { it.scope }.distinctUntilChanged().collect(radioModel::setScope)
        }
        setContent {
            val settings by settingsModel.settings.collectAsStateWithLifecycle()
            val storageError by settingsModel.storageError.collectAsStateWithLifecycle()
            val countries by radioModel.countries.collectAsStateWithLifecycle()
            TuneTheme {
                TuneApp(
                    settings = settings ?: TuneSettings(),
                    storageError = storageError,
                    radio = radioModel.radioState,
                    playback = tuneApp.playerController.state,
                    playbackConnected = tuneApp.playerController.connected,
                    onPlayback = ::dispatchPlayback,
                    countries = countries,
                    onLoadCountries = radioModel::loadCountries,
                    onRefreshRadio = { settingsModel.settings.value?.let { radioModel.refresh(it.scope) } },
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

    private fun dispatchPlayback(command: PlayerCommand) {
        lifecycleScope.launch {
            try {
                tuneApp.playerController.dispatch(command)
            } catch (_: TuneFailure) {
                Toast.makeText(this@MainActivity, R.string.playback_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
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
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (carShell) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
