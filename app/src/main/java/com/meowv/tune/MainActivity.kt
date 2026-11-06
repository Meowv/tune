package com.meowv.tune

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.meowv.tune.databinding.ActivityMainBinding
import com.meowv.tune.ui.TuneUi

class MainActivity : Activity() {
    private lateinit var ui: TuneUi
    private var carSystemBars: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        splashScreen.setKeepOnScreenCondition {
            val shell = binding.mainShell.getChildAt(0)
            shell == null || !shell.isLaidOut
        }
        ui = TuneUi(this, savedInstanceState?.getInt(SELECTED_PAGE) ?: 0, savedInstanceState?.getString(SELECTED_SCOPE) ?: "AUTO")
        binding.mainShell.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val density = resources.displayMetrics.density
            val car = TuneUi.isCarWindow((right - left) / density, (bottom - top) / density)
            if (carSystemBars != car) {
                carSystemBars = car
                if (car) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility = if (car) {
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                } else View.SYSTEM_UI_FLAG_VISIBLE
            }
            ui.render(
                binding.mainShell,
                right - left - binding.mainShell.paddingLeft - binding.mainShell.paddingRight,
                bottom - top - binding.mainShell.paddingTop - binding.mainShell.paddingBottom
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SELECTED_PAGE, ui.selectedPage)
        outState.putString(SELECTED_SCOPE, ui.selectedScope)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val SELECTED_SCOPE = "selected_scope"
        private const val SELECTED_PAGE = "selected_page"
    }
}
