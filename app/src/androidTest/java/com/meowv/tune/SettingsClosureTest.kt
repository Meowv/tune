package com.meowv.tune

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsClosureTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication

    @Before
    fun resetSettings() = runBlocking<Unit> {
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.COUNTRY, app.country)) }
        runCatching { app.playerController.dispatch(PlayerCommand.Stop) }
        app.playerController.state.first { it.phase == PlaybackPhase.IDLE }
    }

    @Test
    fun settingsExposeOnlySupportedEntries() {
        openPage("nav_settings")
        compose.onNodeWithText("自动播放").assertExists()
        listOf(
            "启动自动播放", "媒体断开暂停", "仅 Wi-Fi 播放", "流量提醒",
            "主题", "布局", "大字显示", "播放", "数据与关于"
        ).forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }

        scrollSettingsTo("开源许可")
        listOf("刷新数据", "清理缓存", "数据来源", "开源许可").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("刷新目录").assertDoesNotExist()
        compose.onNodeWithTag("settings_diagnostics").assertDoesNotExist()
        compose.onAllNodes(hasText("Meowv Tune ·", substring = true)).assertCountEquals(0)
    }

    @Test
    fun refreshAndClearCacheKeepPlayback() = runBlocking<Unit> {
        compose.waitUntil(5000) { app.playerController.connected.value }
        val scope = withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }!!
        app.radioQueue.clearScope()
        app.radioQueue.updateDirectory(scope, StationPage((1L..3L).map { station(it) }))
        val playing = station(2)
        app.playerController.dispatch(PlayerCommand.Play(playing))
        withTimeout(8000) { app.playerController.state.first { it.phase == PlaybackPhase.PLAYING } }

        openPage("nav_settings")
        scrollSettingsTo("刷新数据")
        compose.onNodeWithText("刷新数据").performClick()
        scrollSettingsTo("清理缓存")
        compose.onNodeWithText("清理缓存").performClick()
        compose.waitForIdle()
        assertEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)
        assertEquals(playing.uuid, app.playerController.state.value.station?.uuid)
    }

    @Test
    fun sourceAndLicenseEntriesOpenDialogs() {
        openPage("nav_settings")
        scrollSettingsTo("数据来源")
        compose.onNodeWithText("数据来源").performClick()
        compose.onNodeWithText("radio-browser.info", substring = true).assertExists()
        compose.onNodeWithText("关闭").performClick()
        scrollSettingsTo("开源许可")
        compose.onNodeWithText("开源许可").performClick()
        compose.onNodeWithText("Apache 2.0", substring = true).assertExists()
        compose.onNodeWithText("关闭").performClick()
    }

    // LazyColumn 只组合可见项，先滚动列表再定位目标行。
    private fun scrollSettingsTo(text: String, substring: Boolean = false) {
        compose.onNodeWithTag("page_settings").performScrollToNode(hasText(text, substring = substring))
    }

    // 慢速模拟器上首次点击可能落在重组中的布局上，重试直到目标页面出现。
    private fun openPage(tag: String) {
        val page = when (tag) {
            "nav_settings" -> "page_settings"
            "nav_music" -> "page_music"
            else -> "page_radio"
        }
        repeat(3) {
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(page).fetchSemanticsNodes().isNotEmpty()) return
        }
        throw AssertionError("页面未打开：$page")
    }

    private fun station(ordinal: Long) = Station(UUID(0, ordinal), "Test station $ordinal", app.country)
}
