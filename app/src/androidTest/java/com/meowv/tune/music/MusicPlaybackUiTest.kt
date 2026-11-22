package com.meowv.tune.music

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
        // 截图和多次界面操作可能耗时超过短音频的播放时长。
        val fixtureDurationMs = if (originals) 12_000L else 96_000L
        val tracks = listOf("睡醒就好了", "今天也不错", "第三首").mapIndexed { index, title ->
            MusicTrack("ui-${index + 1}", title, listOf("qix"), durationMs = fixtureDurationMs)
        }
        val audio = tracks.mapIndexed { index, track ->
            track.id to PlayableAudio(track.id, "test", track.id, 1.0,
                if (originals) "https://original.test/original-00${index + 1}.mp3" else "https://test.example/session-handoff.mp3",
                "audio/mpeg", fixtureDurationMs, seekable = true)
        }.toMap()
        val unavailable = MusicTrack("unavailable", "不存在的曲目", listOf("qix"), availability = "unavailable")
        val fake = FakeMusicRepository(listOf(unavailable) + tracks, audio)
        app.testMusicRepository = null
        app.musicResume.save(null)
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun recommendations(limit: Int, cursor: String?) =
                fake.recommendations(limit, cursor).copy(partial = true, warnings = listOf("qqmusic:source_unavailable"))
        }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update {
            it.copy(autoPlay = false, playbackOrder = PlaybackOrder.SEQUENTIAL, musicDisplayMode = RadioDisplayMode.LIST)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val connection = app.playerController
            suspend fun expectPlayback(step: String, phase: PlaybackPhase, trackId: String) {
                val timeoutMillis = if (phase == PlaybackPhase.PAUSED) 3_000L else 6_000L
                try {
                    withTimeout(timeoutMillis) {
                        connection.state.first { it.phase == phase && it.music?.id == trackId }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    val state = connection.state.value
                    throw AssertionError("$step: expected $phase/$trackId, got ${state.phase}/${state.music?.id} " +
                        "(error=${state.error}, musicFailure=${state.musicFailure})", timeout)
                }
            }
            val musicPage = hasAnyAncestor(hasTestTag("page_music"))
            val listMode = compose.onNode(hasTestTag("layout_list") and musicPage)
            val cardMode = compose.onNode(hasTestTag("layout_card") and musicPage)
            val feed = compose.onNodeWithTag("music_feed")
            val cards = compose.onNodeWithTag("music_cards")
            fun switchMode(button: SemanticsNodeInteraction, content: SemanticsNodeInteraction) {
                button.performClick()
                compose.waitUntil(5000) { content.isDisplayed() }
            }
            withTimeout(5000) { connection.connected.first { ready -> ready } }
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("music_feed_track_ui-1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_feed_track_ui-1").performClick()
                expectPlayback("选择第一首歌曲", PlaybackPhase.PLAYING, "ui-1")
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
                expectPlayback("选择第二首歌曲", PlaybackPhase.PLAYING, "ui-2")
                val rowControl = compose.onNodeWithTag("music_track_action_ui-2", useUnmergedTree = true)
                rowControl.performTouchInput { click() }
                expectPlayback("列表内暂停", PlaybackPhase.PAUSED, "ui-2")
                compose.onNodeWithTag("now_playing_screen").assertDoesNotExist()
                compose.onNodeWithTag("music_feed").assertIsDisplayed()

                rowControl.performTouchInput { click() }
                expectPlayback("列表内继续播放", PlaybackPhase.PLAYING, "ui-2")
                compose.onNodeWithTag("now_playing_screen").assertDoesNotExist()
                switchMode(cardMode, cards)
                val currentCard = compose.onNodeWithTag("music_feed_card_ui-2")
                val thirdCard = compose.onNodeWithTag("music_feed_card_ui-3")
                currentCard.assertIsSelected()
                currentCard.performTouchInput { swipeLeft() }
                thirdCard.assertIsSelected()
                expectPlayback("播放中左滑卡片切到第三首", PlaybackPhase.PLAYING, "ui-3")
                thirdCard.performClick()
                expectPlayback("卡片内暂停第三首", PlaybackPhase.PAUSED, "ui-3")
                thirdCard.performTouchInput { swipeRight() }
                currentCard.assertIsSelected()

                switchMode(listMode, feed)
                switchMode(cardMode, cards)
                currentCard.assertIsSelected()
                compose.onNodeWithTag("circular_player_entry").performClick()
                compose.onNodeWithTag("now_playing_collapse").performClick()
                currentCard.assertIsSelected()
                assertEquals("ui-3", connection.state.value.music?.id)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)

                currentCard.performClick()
                expectPlayback("点按已浏览的第二首", PlaybackPhase.PLAYING, "ui-2")
                currentCard.performClick()
                expectPlayback("卡片内暂停", PlaybackPhase.PAUSED, "ui-2")
                compose.onNodeWithTag("now_playing_screen").assertDoesNotExist()
                currentCard.performClick()
                expectPlayback("卡片内继续播放", PlaybackPhase.PLAYING, "ui-2")
                capture("cards")
                if (InstrumentationRegistry.getArguments().getString("captureV5Drag") == "true" && car) {
                    captureDragFrame(scenario)
                    assertEquals("ui-2", connection.state.value.music?.id)
                }
                switchMode(listMode, feed)
                compose.onNodeWithTag("music_feed_track_ui-1").performClick()
                expectPlayback("返回列表选择第一首", PlaybackPhase.PLAYING, "ui-1")
                switchMode(cardMode, cards)
                compose.onNodeWithTag("music_feed_card_ui-1").assertIsSelected()
                switchMode(listMode, feed)
                compose.onNodeWithTag("music_feed_track_ui-2").performClick()
                expectPlayback("打开沉浸页前选择第二首", PlaybackPhase.PLAYING, "ui-2")
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
                expectPlayback("沉浸主控暂停第二首", PlaybackPhase.PAUSED, "ui-2")
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
                expectPlayback("左滑封面播放第三首", PlaybackPhase.PLAYING, "ui-3")
                artwork.performTouchInput { swipeRight() }
                expectPlayback("右滑封面播放第二首", PlaybackPhase.PLAYING, "ui-2")
                compose.onNodeWithTag("now_playing_next").assertIsDisplayed().performClick()
                expectPlayback("下一首按钮播放第三首", PlaybackPhase.PLAYING, "ui-3")
                toggle.assertIsDisplayed().performClick()
                expectPlayback("沉浸主控暂停第三首", PlaybackPhase.PAUSED, "ui-3")
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
                switchMode(cardMode, cards)
                scenario.recreate()
                compose.waitUntil(5000) { cards.isDisplayed() }
                assertEquals("ui-3", connection.state.value.music?.id)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
                app.userSettings.update { it.copy(musicDisplayMode = RadioDisplayMode.LIST) }
            }
        }
    }

    private fun captureDragFrame(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val bounds = compose.onNodeWithTag("music_feed_card_ui-2").getUnclippedBoundsInRoot()
        var density = 1f
        val origin = IntArray(2)
        scenario.onActivity { activity ->
            activity.findViewById<View>(android.R.id.content).getLocationOnScreen(origin)
            density = activity.resources.displayMetrics.density
        }
        val startX = origin[0] + (bounds.left.value + bounds.right.value) * density / 2
        val y = origin[1] + (bounds.top.value + bounds.bottom.value) * density / 2
        val distance = (bounds.right - bounds.left).value * density * .42f
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN, startX)
        try {
            repeat(4) { step ->
                SystemClock.sleep(32)
                inject(MotionEvent.ACTION_MOVE, startX - distance * (step + 1) / 4)
            }
            SystemClock.sleep(150)
            // 手指仍按住时直接截取系统显示帧，不等待 Compose 滚动结束。
            val bitmap = requireNotNull(automation.takeScreenshot())
            val file = File(instrumentation.targetContext.cacheDir, "music-evidence/car-playback-drag.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            inject(MotionEvent.ACTION_CANCEL, startX - distance)
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
