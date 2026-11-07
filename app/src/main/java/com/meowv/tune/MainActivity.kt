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
import com.meowv.tune.settings.settingsRepository
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.meowv.tune.ui.TuneApp
import com.meowv.tune.ui.theme.TuneTheme

class MainActivity : ComponentActivity() {
    private var firstFrameReady = false
    private var carShell = false
    private val settingsModel by viewModels<SettingsViewModel> {
        viewModelFactory { initializer { SettingsViewModel(settingsRepository(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val background = getColor(R.color.background)
        enableEdgeToEdge(SystemBarStyle.dark(background), SystemBarStyle.dark(background))
        splash.setKeepOnScreenCondition { !firstFrameReady }
        setContent {
            val settings by settingsModel.settings.collectAsStateWithLifecycle()
            val storageError by settingsModel.storageError.collectAsStateWithLifecycle()
            TuneTheme {
                TuneApp(
                    settings = settings,
                    storageError = storageError,
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

    private fun clearDirectoryCache() {
        lifecycleScope.launch {
            val message = try {
                (application as TuneApplication).directoryCache.clear()
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
