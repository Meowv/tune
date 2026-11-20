package com.meowv.tune.music

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicLyricLine
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.ui.MusicScreen
import com.meowv.tune.ui.NowPlayingScreen
import com.meowv.tune.ui.isCarWindow
import com.meowv.tune.ui.theme.TuneTheme
import com.meowv.tune.playback.SleepTimerState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MusicScreenTest {
    @get:Rule val compose = createComposeRule()
    private val isCar get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.run {
        isCarWindow(screenWidthDp.toFloat(), screenHeightDp.toFloat())
    }

    @Test fun unconfiguredHidesTheNoticeAndRetry() {
        compose.setContent { TuneTheme {
            MusicScreen(isCar, MusicUiState.NotConfigured, MusicFeed(), true, {}, {}, {})
        } }
        compose.onNodeWithText("音乐服务暂未就绪").assertDoesNotExist()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithTag("music_start").assertDoesNotExist()
        compose.onNodeWithTag("music_current").assertDoesNotExist()
        capture("unconfigured")
    }

    @Test fun waitingForAccessHidesTheNoticeWithOrWithoutCachedTracks() {
        val browse = mutableStateOf(MusicFeed(failure = MusicFailure.NOT_CONFIGURED))
        compose.setContent { TuneTheme {
            MusicScreen(isCar, MusicUiState.Idle, browse.value, true, {}, {}, {})
        } }
        compose.onNodeWithText("音乐服务暂未就绪").assertDoesNotExist()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithTag("music_feed_loading").assertDoesNotExist()

        val track = MusicTrack("cached", "Cached track", listOf("Test artist"))
        compose.runOnIdle { browse.value = browse.value.copy(tracks = listOf(track)) }
        compose.onNodeWithTag("music_feed_track_cached").assertIsDisplayed()
        compose.onNodeWithText("音乐服务暂未就绪").assertDoesNotExist()
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun listAndCardsDisplayTheSameTrack() {
        val track = MusicTrack("original-001", "睡醒就好了（测试片段）", listOf("qix"))
        compose.setContent { TuneTheme {
            MusicScreen(isCar, MusicUiState.Idle, MusicFeed(tracks = listOf(track)), true, {}, {}, {})
        } }
        compose.onNodeWithTag("music_feed_track_original-001").assertIsDisplayed()
        compose.onNodeWithTag("layout_card").performClick()
        compose.onNodeWithTag("music_feed_card_original-001").assertIsDisplayed()
        compose.onNodeWithTag("layout_list").performClick()
        compose.onNodeWithTag("music_feed_track_original-001").assertIsDisplayed()
    }

    @Test fun outgoingCardKeepsItsContentWhileVisiblyClippedDuringDrag() {
        val tracks = (1..2).map { MusicTrack("drag-$it", "Card $it", listOf("qix")) }
        compose.setContent { TuneTheme {
            MusicScreen(isCar, MusicUiState.Idle, MusicFeed(tracks = tracks), true, {}, {}, {})
        } }
        compose.onNodeWithTag("layout_card").performClick()
        val pager = compose.onNodeWithTag("music_cards")
        val first = compose.onNodeWithTag("music_feed_card_drag-1")
        first.assertIsSelected()
        pager.performTouchInput {
            down(center)
            moveBy(Offset(-width * (if (isCar) .40f else .24f), 0f))
        }
        first.assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Card 1", useUnmergedTree = true).assertIsDisplayed()
        pager.performTouchInput { up() }
    }

    @Test fun lyricsFollowPlaybackAndOnlyTapOrHorizontalDragSeeks() {
        val state = mutableStateOf(PlayerUiState(
            source = PlaybackSource.MUSIC, phase = PlaybackPhase.PAUSED,
            music = MusicTrack("seek", "Progress", listOf("qix")),
            durationMs = 12000, positionMs = 2000, canSeek = true
        ))
        val seeks = mutableListOf<Long>()
        compose.setContent { TuneTheme {
            NowPlayingScreen(
                isCar = isCar, connected = true, state = state.value, station = null,
                shuffle = false, sleepTimer = SleepTimerState(), onCollapse = {}, onToggle = {},
                onNext = {}, onPrevious = {}, onToggleShuffle = {}, onSetSleepTimer = {},
                onSeek = { seeks.add(it) }
            )
        } }
        val slider = compose.onNodeWithTag("music_seek")
        compose.onNodeWithTag("music_lyrics").assertDoesNotExist()
        slider.assertIsDisplayed().performTouchInput {
            down(Offset(center.x, height * .25f))
            moveBy(Offset(0f, height * .50f), delayMillis = 160)
            up()
        }
        compose.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
        slider.performTouchInput { click(Offset(width * .50f, center.y)) }
        compose.runOnIdle { assertEquals(1, seeks.size) }
        slider.performTouchInput {
            swipe(Offset(width * .20f, center.y), Offset(width * .75f, center.y), 200)
        }
        compose.runOnIdle {
            assertEquals(2, seeks.size)
            state.value = state.value.copy(
                musicLyrics = listOf(MusicLyricLine(0, "Current lyric"), MusicLyricLine(5000, "Next lyric"))
            )
        }
        val lyric = compose.onNodeWithText("Current lyric").assertIsDisplayed()
        val lyricBounds = lyric.getUnclippedBoundsInRoot()
        val sliderBounds = slider.getUnclippedBoundsInRoot()
        compose.onNodeWithTag("music_lyrics").performTouchInput { swipeUp() }
        assertEquals(lyricBounds, lyric.getUnclippedBoundsInRoot())
        assertEquals(sliderBounds, slider.getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals(2, seeks.size)
            state.value = state.value.copy(positionMs = 5500)
        }
        lyric.assertDoesNotExist()
        compose.onNodeWithText("Next lyric").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(canSeek = false)
        }
        slider.assertDoesNotExist()
        compose.onNodeWithTag("music_progress").assertExists()
        compose.runOnIdle { state.value = state.value.copy(durationMs = null) }
        compose.onNodeWithTag("music_progress").assertDoesNotExist()
        compose.onNodeWithTag("now_playing_play").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "music-evidence").apply { mkdirs() }
        val file = File(folder, "${if (isCar) "car" else "phone"}-$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
