package com.meowv.tune

import android.app.Activity
import android.os.Bundle
import com.meowv.tune.databinding.ActivityMainBinding
import com.meowv.tune.ui.TuneUi

class MainActivity : Activity() {
    private lateinit var ui: TuneUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ui = TuneUi(this, savedInstanceState?.getInt(SELECTED_PAGE) ?: 0)
        // 使用实际可用窗口尺寸，分屏和窗口缩放时也重新选择布局。
        binding.mainShell.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            ui.render(
                binding.mainShell,
                right - left - binding.mainShell.paddingLeft - binding.mainShell.paddingRight,
                bottom - top - binding.mainShell.paddingTop - binding.mainShell.paddingBottom
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SELECTED_PAGE, ui.selectedPage)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val SELECTED_PAGE = "selected_page"
    }
}
