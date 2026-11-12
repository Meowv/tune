package com.meowv.tune

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meowv.tune.domain.*
import com.meowv.tune.ui.*
import com.meowv.tune.ui.theme.TuneTheme
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CircularEntryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rotationFreezesAndMenuGesturesStaySeparate() {
        val station = Station(UUID(0, 1), "Radio", null)
        val playback = mutableStateOf(PlayerUiState())
        val page = mutableStateOf(TunePage.Radio)
        val enabled = mutableStateOf(true)
        val connected = mutableStateOf(true)
        val rotation = PlayerRotationState()
        var opens = 0
        compose.setContent {
            TuneTheme {
                MeowvBottomDock(page.value, {
                    CircularPlayerEntry(playback.value, connected.value, rotation, false, enabled.value, { opens++ })
                }) { page.value = it }
            }
        }
        compose.onNodeWithTag("circular_player_entry").performClick()
        assertEquals(1, opens)
        assertEquals(TunePage.Radio, page.value)
        compose.onNodeWithTag("bottom_navigation").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(TunePage.Music, page.value); assertEquals(1, opens) }
        listOf(
            PlaybackPhase.RESOLVING to "正在连接", PlaybackPhase.BUFFERING to "缓冲中",
            PlaybackPhase.RETRYING to "正在重试", PlaybackPhase.ERROR to "播放失败"
        ).forEach { (phase, label) ->
            compose.runOnIdle { playback.value = PlayerUiState(phase, station) }
            compose.onNodeWithTag("circular_player_entry").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, label)
            )
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithTag("circular_player_entry").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "播放服务未连接")
        )
        compose.runOnIdle { connected.value = true }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { playback.value = PlayerUiState(PlaybackPhase.PLAYING, station) }
        compose.mainClock.advanceTimeBy(320)
        val moving = rotation.angle
        assertTrue("播放时必须旋转", moving > 0f)
        compose.runOnIdle { playback.value = playback.value.copy(phase = PlaybackPhase.PAUSED) }
        compose.mainClock.advanceTimeBy(32)
        val paused = rotation.angle
        compose.mainClock.advanceTimeBy(500)
        assertEquals(paused, rotation.angle, .001f)
        compose.runOnIdle { playback.value = playback.value.copy(phase = PlaybackPhase.PLAYING) }
        compose.mainClock.advanceTimeBy(320)
        assertTrue(rotation.angle > paused)
        compose.runOnIdle { enabled.value = false }
        compose.mainClock.advanceTimeBy(32)
        val covered = rotation.angle
        compose.mainClock.advanceTimeBy(500)
        assertEquals(covered, rotation.angle, .001f)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { playback.value = playback.value.copy(station = station.copy(iconUrl = "file:///missing-radio.png")) }
        compose.waitForIdle()
        compose.waitUntil(3000) {
            compose.onAllNodesWithTag("circular_player_logo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("circular_player_entry").assertIsNotEnabled()
        assertEquals(PlaybackPhase.PLAYING, playback.value.phase)
    }
}
