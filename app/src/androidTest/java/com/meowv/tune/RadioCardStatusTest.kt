package com.meowv.tune

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.*
import com.meowv.tune.ui.RadioScreen
import com.meowv.tune.ui.isCarWindow
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioCardStatusTest {
    @get:Rule val compose = createComposeRule()
    private val first = Station(UUID(0, 1), "Meowv Radio", CountryCode.parse("CN"))
    private val second = Station(UUID(0, 2), "Second Radio", CountryCode.parse("US"))

    @Test
    fun cardStatusTracksPlaybackWithoutMovingTheCard() {
        val playback = mutableStateOf(PlayerUiState(PlaybackPhase.RESOLVING, first))
        val connected = mutableStateOf(true)
        val selected = mutableStateOf(first.uuid)
        val animations = mutableStateOf(true)
        val commands = mutableListOf<PlayerCommand>()
        compose.setContent {
            val config = LocalConfiguration.current
            TuneTheme {
                Box(Modifier.fillMaxSize().background(TuneColors.Background)) {
                    RadioScreen(
                        isCar = isCarWindow(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat()),
                        state = RadioUiState(stations = listOf(first, second), selectedStationUuid = selected.value),
                        playback = playback.value, connected = connected.value,
                        displayMode = RadioDisplayMode.CARD, onDisplayModeChange = {}, onLoadMore = {},
                        onPlayback = { commands.add(it) }, onSelectTarget = { selected.value = it.uuid },
                        onRefresh = {}, onScopeClick = {}, animationsEnabled = animations.value
                    )
                }
            }
        }
        val card = compose.onNodeWithTag("station_card_${first.uuid}")
        val status = compose.onNodeWithTag("station_status_${first.uuid}")
        card.assertIsDisplayed()
        val original = card.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val statusBounds = status.getUnclippedBoundsInRoot()
        assertTrue(statusBounds.top >= original.bottom)
        assertTrue(statusBounds.bottom <= root.bottom)

        val labels = listOf(
            PlaybackPhase.RESOLVING to "正在连接",
            PlaybackPhase.BUFFERING to "缓冲中",
            PlaybackPhase.RETRYING to "正在重试",
            PlaybackPhase.PLAYING to "播放中",
            PlaybackPhase.PAUSED to "已暂停",
            PlaybackPhase.ERROR to "播放失败"
        )
        labels.forEach { (phase, label) ->
            compose.runOnIdle { playback.value = PlayerUiState(phase, first) }
            status.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(original, card.getUnclippedBoundsInRoot())
        }
        compose.runOnIdle {
            playback.value = PlayerUiState(PlaybackPhase.PLAYING, first)
            connected.value = false
        }
        status.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "播放服务未连接"))
        // 手动推进时钟，避免测试框架在自动等待空闲时取消无限动画。
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { connected.value = true }
        compose.mainClock.advanceTimeBy(32)
        status.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "播放中"))
        val waveform = compose.onNodeWithTag("station_status_waveform", useUnmergedTree = true)
        val movingFrame = waveform.captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(240)
        assertFalse("播放波形应持续变化", movingFrame.sameAs(waveform.captureToImage().asAndroidBitmap()))
        compose.runOnIdle { animations.value = false }
        compose.mainClock.advanceTimeBy(32)
        val staticFrame = waveform.captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(240)
        assertTrue("关闭动画后波形应保持静止", staticFrame.sameAs(waveform.captureToImage().asAndroidBitmap()))
        compose.mainClock.autoAdvance = true
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        File(target.cacheDir, "radio-card-status.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // 暂停后浏览其他台只改变目标，状态不能跟随选中台移动，也不能自动起播。
        compose.runOnIdle { playback.value = PlayerUiState(PlaybackPhase.PAUSED, first) }
        compose.onNodeWithTag("station_cards").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("station_status_${second.uuid}")
            .assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ""))
        assertTrue(commands.isEmpty())
        compose.onNodeWithTag("station_card_${second.uuid}").performClick()
        compose.runOnIdle { assertEquals(listOf(PlayerCommand.Play(second)), commands) }
    }
}
