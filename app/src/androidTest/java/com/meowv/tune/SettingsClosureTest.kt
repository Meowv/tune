package com.meowv.tune

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.LayoutMode
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.radio.RadioViewModel
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun layoutPreferenceSwitchesShellWithoutRestartingPlayback() = runBlocking<Unit> {
        compose.waitUntil(5000) { app.playerController.connected.value }
        val scope = withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }!!
        app.radioQueue.clearScope()
        app.radioQueue.updateDirectory(scope, StationPage((1L..3L).map { station(it) }))
        val playing = station(1)
        app.playerController.dispatch(PlayerCommand.Play(playing))
        withTimeout(8000) { app.playerController.state.first { it.phase == PlaybackPhase.PLAYING } }

        openPage("nav_settings")
        compose.onNodeWithTag("shell_car").assertExists()
        scrollSettingsTo("布局")
        compose.onNodeWithText("布局").performClick()
        compose.onNodeWithTag("layout_option_phone").performClick()
        compose.waitUntil(3000) { current().layout == LayoutMode.PHONE }
        compose.onNodeWithTag("shell_phone").assertExists()

        assertEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)
        assertEquals(playing.uuid, app.playerController.state.value.station?.uuid)

        scrollSettingsTo("布局")
        compose.onNodeWithText("布局").performClick()
        compose.onNodeWithTag("layout_option_car").performClick()
        compose.waitUntil(3000) { current().layout == LayoutMode.CAR }
        compose.onNodeWithTag("shell_car").assertExists()
        assertEquals(playing.uuid, app.playerController.state.value.station?.uuid)
    }

    @Test
    fun largeTextPreferenceGrowsAndPersists() = runBlocking<Unit> {
        openPage("nav_settings")
        scrollSettingsTo("大字显示")
        val row = compose.onNodeWithText("大字显示")
        row.assertIsOff()
        val before = diagnosticsHeight()
        row.performClick()
        compose.waitUntil(3000) { current().largeText }
        val after = diagnosticsHeight()
        assertTrue("大字显示应放大文本：$before -> $after", after > before)

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { current().largeText }
        compose.onNodeWithTag("shell_car").assertExists()
        openPage("nav_settings")
        scrollSettingsTo("大字显示")
        compose.onNodeWithText("大字显示").assertIsOn()
    }

    @Test
    fun disconnectSettingPersistsAcrossRecreation() = runBlocking<Unit> {
        openPage("nav_settings")
        scrollSettingsTo("媒体断开暂停")
        compose.onNodeWithText("媒体断开暂停").assertIsOn().performClick()
        compose.waitUntil(3000) { !current().pauseOnDisconnect }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { !current().pauseOnDisconnect }
        openPage("nav_settings")
        scrollSettingsTo("媒体断开暂停")
        compose.onNodeWithText("媒体断开暂停").assertIsOff()
    }

    @Test
    fun fixedDarkThemeHasNoSwitchEntry() {
        openPage("nav_settings")
        scrollSettingsTo("主题")
        compose.onNodeWithText("深色").assertExists()
        assertFalse("固定深色不应伪装可切换", hasClickAction("主题"))
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
        scrollSettingsTo("刷新目录")
        compose.onNodeWithText("刷新目录").performClick()
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

    @Test
    fun diagnosticsStayShortAndAvoidUrls() {
        openPage("nav_settings")
        scrollSettingsTo("范围", substring = true)
        val diagnostics = compose.onNodeWithTag("settings_diagnostics")
        val text = diagnostics.fetchSemanticsNode().config.toString()
        assertFalse("诊断不应包含地址", text.contains("http", ignoreCase = true))
        assertTrue("诊断应给出范围", text.contains("范围"))
    }

    private fun current(): TuneSettings = runBlocking { app.userSettings.settings.first() }

    // 诊断行是普通文本，不受设置行固定高度影响，用它衡量字体缩放。
    private fun diagnosticsHeight(): androidx.compose.ui.unit.Dp {
        scrollSettingsTo("范围", substring = true)
        return compose.onNodeWithTag("settings_diagnostics").getBoundsInRoot().let { it.bottom - it.top }
    }

    private fun hasClickAction(text: String): Boolean =
        compose.onNodeWithText(text).fetchSemanticsNode().config.contains(
            androidx.compose.ui.semantics.SemanticsActions.OnClick
        )

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
