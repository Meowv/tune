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
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneSettings
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        compose.onNodeWithTag("circular_player_entry").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val backdrop = compose.onNodeWithTag("now_playing_backdrop")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val car = compose.onAllNodesWithTag("side_navigation").fetchSemanticsNodes().isNotEmpty()
        val expected = if (car) compose.onNodeWithTag("shell_car").getUnclippedBoundsInRoot() else root
        assertTrue("播放背景应覆盖可用播放区域：$backdrop / $expected", (
            kotlin.math.abs((backdrop.top - expected.top).value) +
                kotlin.math.abs((backdrop.bottom - expected.bottom).value) +
                kotlin.math.abs((backdrop.left - expected.left).value) +
                kotlin.math.abs((backdrop.right - expected.right).value)
            ) <= 2f)
        val minimumTouch = if (
            car
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
        if (car) {
            compose.onNodeWithTag("car_rail_brand").assertIsDisplayed()
            compose.onNodeWithTag("car_rail_brand_logo", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("now_playing_brand").assertDoesNotExist()
            val details = compose.onNodeWithTag("now_playing_info_column").getUnclippedBoundsInRoot()
            val collapse = compose.onNodeWithTag("now_playing_collapse").getUnclippedBoundsInRoot()
            assertTrue("车机收起按钮应位于详情栏右上角：$collapse / $details",
                collapse.left >= details.left &&
                    kotlin.math.abs((collapse.right - details.right).value) <= 1f &&
                    kotlin.math.abs((collapse.top - details.top).value) <= 1f)
        } else {
            compose.onNodeWithTag("now_playing_brand").assertIsDisplayed()
            compose.onNodeWithTag("now_playing_brand_logo", useUnmergedTree = true).assertIsDisplayed()
        }
        val mainControl = compose.onNodeWithTag("now_playing_play").getUnclippedBoundsInRoot()
        val controlWidth = mainControl.right - mainControl.left
        val controlHeight = mainControl.bottom - mainControl.top
        assertTrue("圆形主控应保持等宽高：$mainControl",
            kotlin.math.abs((controlWidth - controlHeight).value) <= 1f)
        compose.onNodeWithTag("now_playing_volume").assertDoesNotExist()
        val shuffleLabel = compose.onNodeWithTag(
            "now_playing_shuffle_label",
            useUnmergedTree = true
        )
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val sleepLabel = compose.onNodeWithTag(
            "now_playing_sleep_label",
            useUnmergedTree = true
        )
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "未启用定时时，睡眠定时应与随机播放同高同基线：$shuffleLabel / $sleepLabel",
            kotlin.math.abs((shuffleLabel.top - sleepLabel.top).value) <= 1f &&
                kotlin.math.abs(
                    (shuffleLabel.bottom - shuffleLabel.top).value -
                        (sleepLabel.bottom - sleepLabel.top).value
                ) <= 1f
        )
        assertTrue(
            "Now Playing 不应展示地区副标题",
            compose.onAllNodes(
                hasText("Japan") and hasAnyAncestor(hasTestTag("now_playing_screen"))
            ).fetchSemanticsNodes().isEmpty()
        )
        if (!car && (root.bottom - root.top).value >= 700f) {
            val height = (root.bottom - root.top).value
            val title = compose.onNodeWithTag("now_playing_title")
                .getUnclippedBoundsInRoot()
            val controls = compose.onNodeWithTag("now_playing_play")
                .getUnclippedBoundsInRoot()
            val tools = compose.onNodeWithTag("now_playing_tools")
                .getUnclippedBoundsInRoot()
            val titleRatio = (title.top - root.top).value / height
            val controlsRatio = (controls.top - root.top).value / height
            val safeContent = compose.onNodeWithTag("now_playing_safe_content").getUnclippedBoundsInRoot()
            val toolsBottomGap = safeContent.bottom - tools.bottom
            assertTrue("标题位置应位于 48%–55%：$titleRatio", titleRatio in .48f..0.55f)
            assertTrue("主控位置应位于 58%–68%：$controlsRatio", controlsRatio in .58f..0.68f)
            assertTrue("工具条应靠近内容安全区底部：$toolsBottomGap", toolsBottomGap in 0.dp..16.dp)
        }

        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun longStationTitleDoesNotOverlapControls() = runBlocking {
        withTimeout(5000) { app.playerController.connected.first { it } }
        val scope = withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }!!
        val station = Station(
            uuid = UUID(0, 900),
            name = "这是一个用于验证超长电台名称不会被裁切或覆盖主播放控制的测试电台",
            countryCode = app.country
        )
        app.radioQueue.updateDirectory(scope, StationPage(listOf(station)))
        app.playerController.dispatch(PlayerCommand.Play(station))
        withTimeout(5000) {
            app.playerController.state.first { it.station?.uuid == station.uuid }
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("circular_player_entry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("circular_player_entry").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val title = compose.onNodeWithTag("now_playing_title")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val controls = compose.onNodeWithTag("now_playing_play")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue("超长名称不得覆盖主控：$title / $controls", title.bottom <= controls.top)

        app.playerController.dispatch(PlayerCommand.Stop)
        app.radioQueue.clearScope()
    }
}
