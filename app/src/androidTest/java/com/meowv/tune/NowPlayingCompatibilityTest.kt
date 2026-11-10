package com.meowv.tune

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.ui.isCarWindow
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingCompatibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication

    @Before
    fun prepare() = runBlocking<Unit> {
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        runCatching { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
        app.sleepTimer.cancel()
        app.userSettings.update {
            TuneSettings(
                scope = RadioScope(ScopeMode.GLOBAL),
                playbackOrder = PlaybackOrder.SEQUENTIAL
            )
        }
    }

    @Test
    fun nowPlayingControlsRemainVisibleAndTouchable() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("station_${UUID(0, 1)}").fetchSemanticsNodes().isNotEmpty()
        }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val config = compose.activity.resources.configuration
        val minimumTouch = if (
            isCarWindow(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
        ) 56.dp else 48.dp
        listOf(
            "now_playing_collapse",
            "now_playing_play",
            "now_playing_next",
            "now_playing_shuffle",
            "now_playing_sleep"
        ).forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            node.assertIsDisplayed().assertHasClickAction()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag outside top: $bounds", bounds.top >= root.top)
            assertTrue("$tag outside bottom: $bounds", bounds.bottom <= root.bottom)
            assertTrue(
                "$tag touch size: ${bounds.right - bounds.left} x ${bounds.bottom - bounds.top}",
                bounds.right - bounds.left + .5.dp >= minimumTouch &&
                    bounds.bottom - bounds.top + .5.dp >= minimumTouch
            )
        }
        val artwork = compose.onNodeWithTag("now_playing_artwork")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue("封面应保持正方形", kotlin.math.abs(
            (artwork.right - artwork.left).value - (artwork.bottom - artwork.top).value
        ) <= 1.5f)
        compose.onNodeWithTag("now_playing_artwork")
            .assertContentDescriptionEquals("电台台标：Test station 1")
        compose.onNodeWithTag("now_playing_brand").assertIsDisplayed()
        compose.onNodeWithTag("now_playing_volume").assertDoesNotExist()
        assertTrue(
            "Now Playing 不应展示地区副标题",
            compose.onAllNodes(
                hasText("Japan") and hasAnyAncestor(hasTestTag("now_playing_screen"))
            ).fetchSemanticsNodes().isEmpty()
        )

        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }
}
