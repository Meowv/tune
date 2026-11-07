package com.meowv.tune

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        compose.onNodeWithText("布局").performScrollTo().performClick()
        compose.onNodeWithTag("layout_option_phone").performClick()
        compose.waitUntil(3000) { current().layout == LayoutMode.PHONE }
        compose.onNodeWithTag("shell_phone").assertExists()

        assertEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)
        assertEquals(playing.uuid, app.playerController.state.value.station?.uuid)

        compose.onNodeWithText("布局").performScrollTo().performClick()
        compose.onNodeWithTag("layout_option_car").performClick()
        compose.waitUntil(3000) { current().layout == LayoutMode.CAR }
        compose.onNodeWithTag("shell_car").assertExists()
        assertEquals(playing.uuid, app.playerController.state.value.station?.uuid)
    }

    @Test
    fun largeTextPreferenceGrowsAndPersists() = runBlocking<Unit> {
        openPage("nav_settings")
        val row = compose.onNodeWithText("大字显示").performScrollTo()
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
        compose.onNodeWithText("大字显示").performScrollTo().assertIsOn()
    }

    @Test
    fun disconnectSettingPersistsAcrossRecreation() = runBlocking<Unit> {
        openPage("nav_settings")
        compose.onNodeWithText("媒体断开暂停").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(3000) { !current().pauseOnDisconnect }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { !current().pauseOnDisconnect }
        openPage("nav_settings")
        compose.onNodeWithText("媒体断开暂停").performScrollTo().assertIsOff()
    }

    @Test
    fun fixedDarkThemeHasNoSwitchEntry() {
        openPage("nav_settings")
        compose.onNodeWithText("主题").performScrollTo()
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
        compose.onNodeWithText("刷新目录").performScrollTo().performClick()
        compose.onNodeWithText("清理缓存").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)
        assertEquals(playing.uuid, app.playerController.state.value.station?.uuid)
    }

    @Test
    fun sourceAndLicenseEntriesOpenDialogs() {
        openPage("nav_settings")
        compose.onNodeWithText("数据来源").performScrollTo().performClick()
        compose.onNodeWithText("radio-browser.info", substring = true).assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("开源许可").performScrollTo().performClick()
        compose.onNodeWithText("Apache 2.0", substring = true).assertExists()
        compose.onNodeWithText("关闭").performClick()
    }

    @Test
    fun diagnosticsStayShortAndAvoidUrls() {
        openPage("nav_settings")
        val diagnostics = compose.onNodeWithTag("settings_diagnostics").performScrollTo()
        val text = diagnostics.fetchSemanticsNode().config.toString()
        assertFalse("诊断不应包含地址", text.contains("http", ignoreCase = true))
        assertTrue("诊断应给出范围", text.contains("范围"))
    }

    private fun current(): TuneSettings = runBlocking { app.userSettings.settings.first() }

    // 诊断行是普通文本，不受设置行固定高度影响，用它衡量字体缩放。
    private fun diagnosticsHeight(): androidx.compose.ui.unit.Dp =
        compose.onNodeWithTag("settings_diagnostics").performScrollTo().getBoundsInRoot()
            .let { it.bottom - it.top }

    private fun hasClickAction(text: String): Boolean =
        compose.onNodeWithText(text).fetchSemanticsNode().config.contains(
            androidx.compose.ui.semantics.SemanticsActions.OnClick
        )

    private fun openPage(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    private fun station(ordinal: Long) = Station(UUID(0, ordinal), "Test station $ordinal", app.country)
}
