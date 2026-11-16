package com.meowv.tune.music

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.ui.MusicScreen
import com.meowv.tune.ui.theme.TuneTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MusicScreenTest {
    @get:Rule val compose = createComposeRule()
    private val isCar get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 600

    @Test fun unconfiguredShowsSettingsAndDoesNotStartPlayback() {
        var configured = 0
        compose.setContent { TuneTheme { MusicScreen(isCar, MusicUiState.NotConfigured, { error("Must not start") }, {}, { configured++ }) } }
        compose.onNodeWithText("音乐服务尚未配置").assertIsDisplayed()
        compose.onNodeWithTag("music_start").assertDoesNotExist()
        compose.onNodeWithTag("music_configure").performScrollTo().performClick()
        assertEquals(1, configured)
        capture("unconfigured")
    }

    @Test fun loadingCanCancelAndRepeatedStartIsNotShown() {
        val state = mutableStateOf<MusicUiState>(MusicUiState.Idle)
        var started = 0
        var cancelled = 0
        compose.setContent { TuneTheme { MusicScreen(isCar, state.value, { started++; state.value = MusicUiState.Loading }, { cancelled++ }, {}) } }
        compose.onNodeWithTag("music_start").performScrollTo().performClick()
        compose.onNodeWithTag("music_loading").assertExists()
        compose.onNodeWithTag("music_start").assertDoesNotExist()
        compose.onNodeWithTag("music_cancel").performScrollTo().performClick()
        assertEquals(1, started)
        assertEquals(1, cancelled)
    }

    @Test fun candidateIsShownWithoutPretendingToBePlaying() {
        val track = MusicTrack("original-001", "睡醒就好了（测试片段）", listOf("qix"), "今天也不错")
        compose.setContent { TuneTheme { MusicScreen(isCar, MusicUiState.Ready(track), {}, {}, {}) } }
        compose.onNodeWithTag("music_title").performScrollTo().assertTextEquals(track.title)
        compose.onNodeWithText("待播放").assertIsDisplayed()
        capture("candidate")
    }

    @Test fun errorsAndEmptyStatesOfferAppropriateActions() {
        val state = mutableStateOf<MusicUiState>(MusicUiState.Error(MusicFailure.RATE_LIMITED))
        compose.setContent { TuneTheme { MusicScreen(isCar, state.value, {}, {}, {}) } }
        compose.onNodeWithText("请求较多，请稍后再试").assertExists()
        compose.onNodeWithTag("music_start").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = MusicUiState.Empty }
        compose.onNodeWithText("暂时没有可播放的音乐").assertExists()
        compose.runOnIdle { state.value = MusicUiState.Error(MusicFailure.UNAUTHORIZED) }
        compose.onNodeWithTag("music_configure").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("music_start").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "music-evidence").apply { mkdirs() }
        val file = File(folder, "${if (isCar) "car" else "phone"}-$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
