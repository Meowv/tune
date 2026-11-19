package com.meowv.tune.music

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.ui.MusicScreen
import com.meowv.tune.ui.theme.TuneTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

class MusicScreenTest {
    @get:Rule val compose = createComposeRule()
    private val isCar get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 600

    @Test fun unconfiguredShowsUnavailableWithoutStartOrPlaybackCard() {
        compose.setContent { TuneTheme {
            MusicScreen(isCar, MusicUiState.NotConfigured, MusicFeed(), {}, true, {}, {}, {})
        } }
        compose.onNodeWithText("音乐服务暂未就绪").assertIsDisplayed()
        compose.onNodeWithTag("music_start").assertDoesNotExist()
        compose.onNodeWithTag("music_current").assertDoesNotExist()
        capture("unconfigured")
    }

    @Test fun listAndCardsDisplayTheSameTrack() {
        val track = MusicTrack("original-001", "睡醒就好了（测试片段）", listOf("qix"))
        compose.setContent { TuneTheme {
            MusicScreen(isCar, MusicUiState.Idle, MusicFeed(tracks = listOf(track)), {}, true, {}, {}, {})
        } }
        compose.onNodeWithTag("music_feed_track_original-001").assertIsDisplayed()
        compose.onNodeWithTag("layout_card").performClick()
        compose.onNodeWithTag("music_feed_card_original-001").assertIsDisplayed()
        compose.onNodeWithTag("layout_list").performClick()
        compose.onNodeWithTag("music_feed_track_original-001").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "music-evidence").apply { mkdirs() }
        val file = File(folder, "${if (isCar) "car" else "phone"}-$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
