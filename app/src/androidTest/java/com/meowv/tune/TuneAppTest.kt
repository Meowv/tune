package com.meowv.tune

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Before
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.settings.SettingsViewModel
import com.meowv.tune.radio.RadioViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import com.meowv.tune.domain.PlaybackPhase

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TuneAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication

    @Before
    fun resetDirectoryState() {
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        runBlocking {
            app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.GLOBAL)) }
        }
        compose.onNodeWithTag("station_list").performScrollToIndex(0)
        awaitScope("收听范围，不限区域，切换地区")
    }

    @Test
    fun stationPlayButtonControlsTheLivePlayer() {
        val first = "station_${UUID(0, 1)}"
        val second = "station_${UUID(0, 2)}"
        compose.waitUntil(5000) { app.playerController.connected.value }
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) { app.playerController.state.value.phase == PlaybackPhase.PLAYING }
        compose.onNodeWithTag("player_previous").assertIsNotEnabled()
        compose.onNodeWithTag("player_next").assertIsEnabled().performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 2) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("player_previous").assertIsEnabled().performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("player_previous").assertIsNotEnabled()
        compose.onNodeWithTag(first).assertHasClickAction().assertIsSelected()
        selectPage("nav_music")
        compose.onNode(hasText("Test station 1") and hasAnyAncestor(hasTestTag("global_player"))).assertIsDisplayed()
        selectPage("nav_settings")
        compose.onNode(hasText("Test station 1") and hasAnyAncestor(hasTestTag("global_player"))).assertIsDisplayed()
        selectPage("nav_radio")
        compose.onNodeWithTag(first).assertIsSelected()
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(second))
        compose.onNodeWithTag("player_pause").performClick()
        compose.waitUntil(3000) { app.playerController.state.value.phase == PlaybackPhase.PAUSED }
        compose.onNodeWithTag("player_pause").performClick()
        compose.waitUntil(3000) { app.playerController.state.value.phase == PlaybackPhase.PLAYING }
        compose.onNodeWithTag("player_stop").performClick()
        compose.waitUntil(3000) { app.playerController.state.value.phase == PlaybackPhase.IDLE }
    }

    @Test
    fun mobileWarningRequiresConfirmationBeforePlayback() {
        app.mockNetwork.value = NetworkState(NetworkType.CELLULAR, metered = true)
        runBlocking {
            app.userSettings.update { it.copy(wifiOnly = false, dataWarning = true) }
            app.userSettings.settings.first { !it.wifiOnly && it.dataWarning }
        }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("使用移动网络播放？").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(PlaybackPhase.ERROR, app.playerController.state.value.phase)
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun mobileWarningAlsoGuardsNextTrack() {
        app.mockNetwork.value = NetworkState(NetworkType.CELLULAR, metered = true)
        runBlocking {
            app.userSettings.update { it.copy(wifiOnly = false, dataWarning = true) }
            app.userSettings.settings.first { !it.wifiOnly && it.dataWarning }
        }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("使用移动网络播放？").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }

        compose.onNodeWithTag("player_next").assertIsEnabled().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("使用移动网络播放？").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(UUID(0, 1), app.playerController.state.value.station?.uuid)
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 2) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun wifiOnlyAndOfflineBlockPlayback() {
        app.mockNetwork.value = NetworkState(NetworkType.CELLULAR, metered = true)
        runBlocking {
            app.userSettings.update { it.copy(wifiOnly = true, dataWarning = true) }
            app.userSettings.settings.first { it.wifiOnly }
        }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("仅 Wi-Fi 播放已开启").fetchSemanticsNodes().isNotEmpty() }
        assertNotEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)

        app.mockNetwork.value = NetworkState(NetworkType.OFFLINE, metered = false)
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("网络不可用").fetchSemanticsNodes().isNotEmpty() }
        assertNotEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)
    }

    @Test
    fun networkChangePausesAndReconnectDoesNotAutoResume() {
        runBlocking {
            app.userSettings.update { it.copy(wifiOnly = false, dataWarning = true) }
            app.userSettings.settings.first { !it.wifiOnly && it.dataWarning }
        }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) { app.playerController.state.value.phase == PlaybackPhase.PLAYING }

        app.mockNetwork.value = NetworkState(NetworkType.CELLULAR, metered = true)
        compose.waitUntil(5000) {
            app.playerController.state.value.phase == PlaybackPhase.PAUSED &&
                app.playerController.state.value.error == com.meowv.tune.domain.TuneError.MOBILE_CONFIRMATION
        }
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(3000) { app.playerController.state.value.error == null }

        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        Thread.sleep(500)
        assertEquals(PlaybackPhase.PAUSED, app.playerController.state.value.phase)
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun exhaustedRecoveryExposesWorkingRetryControl() {
        val stations = (1L..4L).map { UUID(0, it) }
        try {
            stations.forEach { uuid -> app.resolveFailures[uuid] = AtomicInteger(20) }
            val first = "station_${UUID(0, 1)}"
            compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
            compose.onNodeWithTag(first).performClick()
            compose.waitUntil(10_000) {
                app.playerController.state.value.phase == PlaybackPhase.ERROR
            }
            compose.onNodeWithTag("player_pause").assertIsEnabled()
                .assertContentDescriptionEquals("重试")

            app.resolveFailures.clear()
            compose.onNodeWithTag("player_pause").performClick()
            compose.waitUntil(8000) {
                app.playerController.state.value.phase == PlaybackPhase.PLAYING &&
                    app.playerController.state.value.station?.uuid == UUID(0, 4)
            }
        } finally {
            app.resolveFailures.clear()
            runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
        }
    }

    @Test
    fun pagesShareTheSameNavigationAndPlayer() {
        compose.onNodeWithTag("nav_radio").assertIsSelected()
        compose.onNodeWithTag("page_radio").assertIsDisplayed()
        selectPage("nav_music")
        compose.onNodeWithTag("page_music").assertIsDisplayed()
        compose.onNodeWithText("开发中").performScrollTo().assertIsDisplayed()
        selectPage("nav_settings")
        compose.onNodeWithTag("page_settings").assertIsDisplayed()
        compose.onNodeWithText("启动自动播放").performScrollTo().assertIsDisplayed()
        val config = compose.activity.resources.configuration
        if (config.screenWidthDp >= 1000 && config.fontScale == 1f) {
            val version = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0).versionName.orEmpty()
            val aboutLabel = compose.activity.getString(R.string.about_app_version, version)
            val about = compose.onNodeWithText(aboutLabel).getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("page_settings").getUnclippedBoundsInRoot()
            assertTrue("Default car settings must show the version completely", about.bottom <= viewport.bottom)
        }
        compose.onNodeWithTag("global_player").assertIsDisplayed()
        compose.onNodeWithTag("player_previous").assertIsNotEnabled()
        compose.onNodeWithTag("player_pause").assertIsNotEnabled()
        compose.onNodeWithTag("player_next").assertIsNotEnabled()
        selectPage("nav_radio")
        compose.onNodeWithTag("page_radio").assertIsDisplayed()
    }

    @Test
    fun selectedScopeAndPageSurviveActivityRecreation() {
        val label = countryScopeLabel()
        openScopePicker()
        compose.onNodeWithTag("scope_picker").assertIsDisplayed()
        compose.onNodeWithTag("scope_options").performScrollToNode(hasTestTag("scope_JP"))
        compose.onNodeWithTag("scope_JP").performClick()
        awaitScope(label)
        compose.onNodeWithTag("scope_selector").assertContentDescriptionEquals(label)
        selectPage("nav_music")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav_music").assertIsSelected()
        compose.onNodeWithTag("page_music").assertIsDisplayed()
        selectPage("nav_radio")
        awaitScope(label)
        compose.onNodeWithTag("scope_selector").assertContentDescriptionEquals(label)
        compose.onNodeWithTag("global_player").assertIsDisplayed()
    }

    @Test
    fun scopeDialogRestoresAndClosesExplicitly() {
        openScopePicker()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("scope_picker").assertIsDisplayed()
        compose.onNodeWithTag("scope_Global").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithTag("scope_close").performClick()
        compose.onNodeWithTag("scope_picker").assertDoesNotExist()
        openScopePicker()
        compose.onNodeWithTag("scope_options").performScrollToNode(hasTestTag("scope_Global"))
        compose.onNodeWithTag("scope_Global").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithContentDescription("收听范围，不限区域，切换地区")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("scope_selector").assertContentDescriptionEquals("收听范围，不限区域，切换地区")
    }

    @Test
    fun fixedControlsHaveEnoughSpaceAndClearSemantics() {
        val player = compose.onNodeWithTag("global_player").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("station_list").getUnclippedBoundsInRoot()
        assertTrue("List must end before the fixed player", list.bottom <= player.top)
        val minimum = if (compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()) 56.dp else 48.dp
        listOf("nav_radio", "nav_music", "nav_settings", "player_previous", "player_pause", "player_next").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag width", bounds.right - bounds.left + .5.dp >= minimum)
            assertTrue("$tag height", bounds.bottom - bounds.top + .5.dp >= minimum)
        }
        compose.onNodeWithTag("nav_radio").assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("brand_name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        assertFalse("Brand overflow: width=${results.single().didOverflowWidth}, height=${results.single().didOverflowHeight}, lines=${results.single().lineCount}, exceeded=${results.single().multiParagraph.didExceedMaxLines}, paragraph=${results.single().multiParagraph.width}x${results.single().multiParagraph.height}, size=${results.single().size}", results.single().hasVisualOverflow)
        assertEquals(1, results.single().lineCount)
        assertTrue(results.single().getLineRight(0) <= results.single().size.width + 1f)
        compose.onNodeWithTag("scope_selector").assertHasClickAction()
    }

    @Test
    fun dpadCanMoveBetweenNavigationItemsAndActivateThem() {
        val car = compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        compose.waitForIdle()
        compose.onNodeWithTag("nav_radio").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("nav_radio").assertIsFocused().performKeyInput {
            pressKey(if (car) Key.DirectionDown else Key.DirectionRight)
        }
        compose.onNodeWithTag("nav_music").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("nav_music").assertIsSelected()
        compose.onNodeWithTag("page_music").assertIsDisplayed()
        compose.onNodeWithTag("nav_music").performKeyInput {
            pressKey(if (car) Key.DirectionDown else Key.DirectionRight)
        }
        compose.onNodeWithTag("nav_settings").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        compose.onNodeWithTag("page_settings").assertIsDisplayed()
    }

    @Test
    fun stationScrollPositionSurvivesNavigationAndRecreation() {
        val tag = "station_${UUID(0, 6)}"
        compose.waitUntil(5000) { compose.onAllNodesWithText("Test station 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("station_list").performScrollToIndex(6)
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val queries = app.queries.get()
        selectPage("nav_music")
        selectPage("nav_radio")
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals(queries, app.queries.get())
    }

    @Test
    fun countryListLoadsNextPageAtEnd() {
        runBlocking { app.userSettings.update { it.copy(scope = RadioScope(ScopeMode.COUNTRY, app.country)) } }
        val label = countryScopeLabel()
        awaitScope(label)
        compose.onNodeWithTag("directory_refresh").performClick()
        compose.waitUntil(5000) {
            var firstPageReady = false
            compose.activityRule.scenario.onActivity { activity ->
                val radio = ViewModelProvider(activity)[RadioViewModel::class].radioState.value
                firstPageReady = !radio.isLoading && radio.cacheState == null && radio.stations.size == 30 && radio.hasMore
            }
            firstPageReady
        }
        val queries = app.queries.get()
        compose.onNodeWithTag("station_list").performScrollToIndex(30)
        compose.waitUntil(5000) { app.queries.get() > queries }
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag("station_${UUID(0, 36)}"))
        compose.onNodeWithTag("station_${UUID(0, 36)}").assertIsDisplayed()
        assertEquals(queries + 1, app.queries.get())
    }

    @Test
    fun failedRefreshKeepsCachedRowsAndCanRetry() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Test station 1").fetchSemanticsNodes().isNotEmpty() }
        app.failQueries = true
        try {
            compose.onNodeWithTag("directory_refresh").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("加载失败").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("station_list").performScrollToNode(hasText("缓存"))
            compose.onNodeWithText("缓存").assertIsDisplayed()
            compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag("station_${UUID(0, 1)}"))
            compose.onNodeWithTag("station_${UUID(0, 1)}").assertIsDisplayed()
        } finally {
            app.failQueries = false
        }
        compose.onNodeWithTag("station_list").performScrollToNode(hasText("重试"))
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("加载失败").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun emptyCountryDoesNotShowGlobalOrDemoRows() {
        val country = requireNotNull(CountryCode.parse("US"))
        runBlocking { app.userSettings.update { it.copy(scope = RadioScope(ScopeMode.COUNTRY, country)) } }
        val activity = compose.activity
        awaitScope(activity.getString(R.string.scope_picker_description, country.displayName(activity.resources.configuration.locales[0])))
        compose.waitUntil(5000) { compose.onAllNodesWithText("暂无可用电台").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("暂无可用电台").assertIsDisplayed()
        compose.onNodeWithText("Test station 1").assertDoesNotExist()
    }

    @Test
    fun accessibilityNodesExposeLabelsSelectionAndFocusActions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        val previousEventTypes = info.eventTypes
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        try {
            val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            compose.waitUntil(5000) { manager.isTouchExplorationEnabled }
            compose.waitForIdle()
            compose.onNodeWithTag("scope_selector").performScrollTo()
            fun currentRoot(): AccessibilityNodeInfo? = automation.rootInActiveWindow
                ?: automation.windows.mapNotNull { it.root }.firstOrNull { it.packageName == "com.meowv.tune" }
            compose.waitUntil(15000) { currentRoot() != null }
            val root = requireNotNull(currentRoot())
            fun findDescription(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
                if (node.contentDescription?.toString() == label) return node
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    findDescription(child, label)?.let { return it }
                }
                return null
            }
            fun interactiveParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
                var result = node
                while (!result.isFocusable && !result.isClickable && result.parent != null) result = result.parent
                return result
            }
            val nav = interactiveParent(findDescription(root, "电台") ?: error("Radio accessibility label missing"))
            assertTrue("Selected tab must expose platform selection: $nav", nav.isSelected)
            assertTrue(nav.isFocusable)
            val scopeLabel = findDescription(root, "收听范围，不限区域，切换地区") ?: error("Scope accessibility label missing")
            val scope = interactiveParent(scopeLabel)
            assertTrue(scope.isClickable)
            scopeLabel.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            assertTrue(scopeLabel.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            compose.waitUntil(5000) {
                currentRoot()?.let { findDescription(it, "收听范围，不限区域，切换地区") }?.isAccessibilityFocused == true
            }
            assertTrue(scope.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.onNodeWithTag("scope_picker").assertIsDisplayed()
            compose.onNodeWithTag("scope_Global").assertIsSelected()
            compose.onNodeWithTag("scope_close").assertContentDescriptionEquals("关闭")
        } finally {
            info.flags = previousFlags
            info.eventTypes = previousEventTypes
            automation.serviceInfo = info
        }
    }

    private fun openScopePicker() {
        compose.onNodeWithTag("scope_selector").performScrollTo().performClick()
    }

    private fun countryScopeLabel(): String {
        val activity = compose.activity
        return activity.getString(R.string.scope_picker_description, app.country.displayName(activity.resources.configuration.locales[0]))
    }

    private fun awaitScope(label: String) {
        try {
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: ComposeTimeoutException) {
            val scope = runBlocking { app.userSettings.settings.first().scope }
            val selector = compose.onAllNodesWithTag("scope_selector").fetchSemanticsNodes()
                .map { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
            var models = ""
            compose.activityRule.scenario.onActivity { activity ->
                val provider = ViewModelProvider(activity)
                val settings = provider[SettingsViewModel::class].settings.value
                val radio = provider[RadioViewModel::class].radioState.value
                models = "settings=${settings?.scope}, radio=${radio.scope}/${radio.isLoading}/${radio.error}"
            }
            throw AssertionError("Expected $label, selector=$selector, saved=$scope, $models", error)
        }
    }

    private fun selectPage(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        node.performClick()
    }

}
