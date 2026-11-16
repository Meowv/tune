package com.meowv.tune.music

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MusicPlaybackUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun musicPageAndImmersiveControlsFollowTheActiveSource() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        val originals = InstrumentationRegistry.getArguments().getString("musicOriginals") == "true"
        val tracks = (1..2).map { MusicTrack("ui-$it", if (it == 1) "睡醒就好了" else "今天也不错", listOf("qix"), durationMs = 12000) }
        val audio = tracks.mapIndexed { index, track ->
            track.id to PlayableAudio(track.id, "test", track.id, 1.0,
                if (originals) "https://original.test/original-00${index + 1}.mp3" else "https://test.example/radio.mp3",
                "audio/mpeg", 12000, seekable = true)
        }.toMap()
        app.testMusicRepository = FakeMusicRepository(tracks, audio)
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val connection = app.playerController
            withTimeout(5000) { connection.connected.first { ready -> ready } }
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_start").performScrollTo().performClick()
                withTimeout(6000) { connection.state.first { state -> state.phase == PlaybackPhase.PLAYING && state.music?.id == "ui-1" } }
                compose.onNodeWithTag("music_toggle").performScrollTo().performClick()
                withTimeout(3000) { connection.state.first { state -> state.phase == PlaybackPhase.PAUSED } }
                compose.onNodeWithTag("music_seek").performScrollTo().performTouchInput { swipe(centerLeft, centerRight, 200) }
                assertTrue(connection.state.value.canSeek)
                capture("page")
                compose.onNodeWithTag("circular_player_entry").performClick()
                compose.onNodeWithTag("now_playing_screen").assertExists()
                compose.onNodeWithTag("music_sleep_timer").performScrollTo().assertIsEnabled()
                compose.onNodeWithTag("music_next").performScrollTo().performClick()
                withTimeout(6000) { connection.state.first { state -> state.phase == PlaybackPhase.PLAYING && state.music?.id == "ui-2" } }
                compose.onNodeWithTag("music_toggle").performScrollTo().performClick()
                withTimeout(3000) { connection.state.first { state -> state.phase == PlaybackPhase.PAUSED } }
                capture("immersive")
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
            }
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mode = if (context.resources.configuration.screenWidthDp >= 600) "car" else "phone"
        val file = File(context.getExternalFilesDir(null), "music-evidence/$mode-playback-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
