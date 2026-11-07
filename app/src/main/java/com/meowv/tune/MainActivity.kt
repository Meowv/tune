package com.meowv.tune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.meowv.tune.ui.TuneApp
import com.meowv.tune.ui.theme.TuneTheme

class MainActivity : ComponentActivity() {
    private var firstFrameReady = false
    private var carShell = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val background = getColor(R.color.background)
        enableEdgeToEdge(SystemBarStyle.dark(background), SystemBarStyle.dark(background))
        splash.setKeepOnScreenCondition { !firstFrameReady }
        setContent {
            TuneTheme {
                TuneApp(
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

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (carShell) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
