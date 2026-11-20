package com.meowv.tune.music

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MusicPlaybackUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun musicPageAndImmersiveControlsFollowTheActiveSource() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        val originals = InstrumentationRegistry.getArguments().getString("musicOriginals") == "true"
        val tracks = (1..3).map { MusicTrack("ui-$it", if (it == 1) "睡醒就好了" else if (it == 2) "今天也不错" else "第三首", listOf("qix"), durationMs = 12000) }
        val audio = tracks.mapIndexed { index, track ->
            track.id to PlayableAudio(track.id, "test", track.id, 1.0,
                if (originals) "https://original.test/original-00${index + 1}.mp3" else "https://test.example/radio.mp3",
                "audio/mpeg", 12000, seekable = true)
        }.toMap()
        val unavailable = MusicTrack("unavailable", "不存在的曲目", listOf("qix"), availability = "unavailable")
        val fake = FakeMusicRepository(listOf(unavailable) + tracks, audio)
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun recommendations(limit: Int, cursor: String?) =
                fake.recommendations(limit, cursor).copy(partial = true, warnings = listOf("qqmusic:source_unavailable"))
        }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = PlaybackOrder.SEQUENTIAL) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val connection = app.playerController
            val musicPage = hasAnyAncestor(hasTestTag("page_music"))
            val listMode = compose.onNode(hasTestTag("layout_list") and musicPage)
            val cardMode = compose.onNode(hasTestTag("layout_card") and musicPage)
            val feed = compose.onNodeWithTag("music_feed")
            withTimeout(5000) { connection.connected.first { ready -> ready } }
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("music_feed_track_ui-1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_feed_track_ui-1").performClick()
                withTimeout(6000) { connection.state.first { state -> state.phase == PlaybackPhase.PLAYING && state.music?.id == "ui-1" } }
                compose.onNodeWithTag("music_feed").assertIsDisplayed()
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("music_feed_track_ui-2").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText("音乐") and hasAnyAncestor(hasTestTag("page_music"))).assertDoesNotExist()
                compose.onNodeWithText("推荐").assertDoesNotExist()
                compose.onNodeWithText("飙升榜").assertDoesNotExist()
                compose.onNodeWithText("部分来源未能提供结果，已显示可用内容").assertDoesNotExist()
                compose.onNodeWithText("不存在的曲目").assertDoesNotExist()
                val feedBounds = feed.getUnclippedBoundsInRoot()
                val firstBounds = compose.onNodeWithTag("music_feed_track_ui-1").getUnclippedBoundsInRoot()
                val secondBounds = compose.onNodeWithTag("music_feed_track_ui-2").getUnclippedBoundsInRoot()
                val car = compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()
                if (car && feedBounds.right.value - feedBounds.left.value >= 660f &&
                    feedBounds.bottom.value - feedBounds.top.value >= 280f) {
                    assertEquals(firstBounds.top.value, secondBounds.top.value, 1f)
                    assertTrue(secondBounds.left >= firstBounds.right)
                } else {
                    assertTrue(secondBounds.top >= firstBounds.bottom)
                }
                compose.onNodeWithTag("music_feed_track_ui-2").performClick()
                withTimeout(6000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "ui-2" } }
                val rowControl = compose.onNodeWithTag("music_track_action_ui-2", useUnmergedTree = true)
                rowControl.performTouchInput { click() }
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PAUSED && it.music?.id == "ui-2" } }
                compose.onNodeWithTag("now_playing_screen").assertDoesNotExist()
                compose.onNodeWithTag("music_feed").assertIsDisplayed()

                rowControl.performTouchInput { click() }
                withTimeout(6000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "ui-2" } }
                compose.onNodeWithTag("now_playing_screen").assertDoesNotExist()
                cardMode.performClick()
                compose.onNodeWithTag("music_cards").assertIsDisplayed()
                val currentCard = compose.onNodeWithTag("music_feed_card_ui-2")
                currentCard.assertIsSelected()
                currentCard.performTouchInput { swipeLeft() }
                compose.onNodeWithTag("music_feed_card_ui-3").assertIsSelected()
                assertEquals("ui-2", connection.state.value.music?.id)
                assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)

                listMode.performClick()
                cardMode.performClick()
                compose.onNodeWithTag("music_feed_card_ui-3").assertIsSelected()
                compose.onNodeWithTag("circular_player_entry").performClick()
                compose.onNodeWithTag("now_playing_collapse").performClick()
                compose.onNodeWithTag("music_feed_card_ui-3").assertIsSelected()
                assertEquals("ui-2", connection.state.value.music?.id)

                compose.onNodeWithTag("music_feed_card_ui-3").performTouchInput { swipeRight() }
                currentCard.assertIsSelected().performClick()
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PAUSED && it.music?.id == "ui-2" } }
                compose.onNodeWithTag("now_playing_screen").assertDoesNotExist()
                currentCard.performClick()
                withTimeout(6000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "ui-2" } }
                capture("cards")
                listMode.performClick()
                compose.onNodeWithTag("music_feed_track_ui-1").performClick()
                withTimeout(6000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "ui-1" } }
                cardMode.performClick()
                compose.onNodeWithTag("music_feed_card_ui-1").assertIsSelected()
                listMode.performClick()
                compose.onNodeWithTag("music_feed_track_ui-2").performClick()
                withTimeout(6000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "ui-2" } }
                capture("page")
                compose.onNodeWithTag("circular_player_entry").performClick()
                compose.onNodeWithTag("now_playing_screen").assertExists()
                val toggle = compose.onNodeWithTag("now_playing_play")
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val keyTime = SystemClock.uptimeMillis()
                assertTrue(automation.injectInputEvent(KeyEvent(keyTime, keyTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0), true))
                assertTrue(automation.injectInputEvent(KeyEvent(keyTime, keyTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0), true))
                compose.waitForIdle()
                toggle.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                toggle.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
                compose.onNodeWithTag("now_playing_next").assertIsFocused()
                toggle.assertIsDisplayed().performClick()
                withTimeout(3000) { connection.state.first { state -> state.phase == PlaybackPhase.PAUSED } }
                compose.onNodeWithTag("music_seek").assertIsDisplayed().performTouchInput {
                    swipe(Offset(width * .25f, center.y), Offset(width * .75f, center.y), 200)
                }
                assertTrue(connection.state.value.canSeek)
                compose.onNodeWithTag("now_playing_sleep").assertIsEnabled()
                val orderLabel = hasTestTag("now_playing_shuffle_label")
                compose.onNodeWithTag("now_playing_shuffle").assertIsDisplayed().performClick()
                compose.waitUntil(5000) { compose.onNode(orderLabel and hasText("随机播放"), useUnmergedTree = true).isDisplayed() }
                compose.onNodeWithTag("now_playing_shuffle").performClick()
                compose.waitUntil(5000) { compose.onNode(orderLabel and hasText("顺序播放"), useUnmergedTree = true).isDisplayed() }
                val artwork = compose.onNodeWithTag("now_playing_artwork")
                artwork.assertIsDisplayed().performTouchInput { swipeLeft() }
                withTimeout(6000) { connection.state.first { state -> state.phase == PlaybackPhase.PLAYING && state.music?.id == "ui-3" } }
                artwork.performTouchInput { swipeRight() }
                withTimeout(6000) { connection.state.first { state -> state.phase == PlaybackPhase.PLAYING && state.music?.id == "ui-2" } }
                compose.onNodeWithTag("now_playing_next").assertIsDisplayed().performClick()
                withTimeout(6000) { connection.state.first { state -> state.phase == PlaybackPhase.PLAYING && state.music?.id == "ui-3" } }
                toggle.assertIsDisplayed().performClick()
                withTimeout(3000) { connection.state.first { state -> state.phase == PlaybackPhase.PAUSED } }
                capture("immersive")
                artwork.assertIsDisplayed().performTouchInput { swipeDown() }
                try {
                    compose.waitUntil(5000) {
                        compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isEmpty() && feed.isDisplayed()
                    }
                } catch (timeout: ComposeTimeoutException) {
                    throw AssertionError(compose.onRoot().printToString(), timeout)
                }
                compose.onNodeWithTag("nav_radio").performClick()
                compose.waitUntil(5000) { compose.onNodeWithTag("page_radio").isDisplayed() }
                compose.onNodeWithTag("nav_music").performClick()
                compose.waitUntil(5000) { feed.isDisplayed() }
                scenario.recreate()
                compose.waitUntil(5000) { feed.isDisplayed() }
                assertEquals("ui-3", connection.state.value.music?.id)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
            }
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mode = if (context.resources.configuration.screenWidthDp >= 600) "car" else "phone"
        val file = File(context.cacheDir, "music-evidence/$mode-playback-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
