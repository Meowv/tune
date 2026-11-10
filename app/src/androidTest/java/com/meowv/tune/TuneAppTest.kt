package com.meowv.tune

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.performScrollToNode
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.RadioDisplayMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TuneAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication

    @Before
    fun resetDirectoryState() {
        awaitCompose()
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        runBlocking {
            runCatching {
                app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop)
            }
            app.sleepTimer.cancel()
            // 相邻台站断言依赖顺序模式；随机默认值由专门用例覆盖。
            app.userSettings.update {
                TuneSettings(scope = RadioScope(ScopeMode.GLOBAL), playbackOrder = PlaybackOrder.SEQUENTIAL)
            }
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
        assertFalse("首台没有上一台", app.radioQueue.canPrevious.value)
        assertTrue(app.radioQueue.canNext.value)
        compose.onNodeWithTag("player_info").performTouchInput { swipeLeft() }
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 2) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("player_info").performTouchInput { swipeRight() }
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        assertFalse("回到最早一台后不可再上一台", app.radioQueue.canPrevious.value)
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
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
        compose.waitUntil(3000) { app.playerController.state.value.phase == PlaybackPhase.IDLE }
    }

    @Test
    fun rowPlayButtonFiresSinglePlaybackRequest() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        val uuid = UUID(0, 1)
        val row = "station_$uuid"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(row))
        val before = app.resolveCalls[uuid]?.get() ?: 0
        // 点行内播放键：按钮消费手势后不能再冒泡成整行点击，否则同一台会被请求两次。
        compose.onNodeWithTag("station_action_$uuid", useUnmergedTree = true).performClick()
        compose.waitUntil(8000) { app.playerController.state.value.phase == PlaybackPhase.PLAYING }
        assertEquals("行内播放键只应发起一次播放", 1, (app.resolveCalls[uuid]?.get() ?: 0) - before)
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun cellularNetworkPlaysWithoutWarning() {
        app.mockNetwork.value = NetworkState(NetworkType.CELLULAR, metered = true)
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        assertTrue(compose.onAllNodesWithText("使用移动网络播放？").fetchSemanticsNodes().isEmpty())
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun networkLossPausesAndReconnectDoesNotAutoResume() {
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) { app.playerController.state.value.phase == PlaybackPhase.PLAYING }

        app.mockNetwork.value = NetworkState(NetworkType.OFFLINE, metered = false)
        compose.waitUntil(5000) {
            app.playerController.state.value.phase == PlaybackPhase.PAUSED &&
                app.playerController.state.value.error == com.meowv.tune.domain.TuneError.NETWORK_OFFLINE
        }

        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        compose.waitUntil(3000) { app.playerController.state.value.error == null }
        Thread.sleep(500)
        assertEquals(PlaybackPhase.PAUSED, app.playerController.state.value.phase)
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun nowPlayingShowsNetworkErrorAndRetryState() {
        playFirstStation()
        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }

        app.mockNetwork.value = NetworkState(NetworkType.OFFLINE, metered = false)
        compose.waitUntil(5000) {
            app.playerController.state.value.phase == PlaybackPhase.PAUSED &&
                app.playerController.state.value.error == com.meowv.tune.domain.TuneError.NETWORK_OFFLINE
        }
        compose.onNode(
            hasText("网络不可用") and hasAnyAncestor(hasTestTag("now_playing_screen"))
        ).assertIsDisplayed()
        compose.onNodeWithTag("now_playing_play").assertContentDescriptionEquals("重试")

        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        compose.waitUntil(3000) {
            app.playerController.state.value.error == null
        }
        compose.onNodeWithTag("now_playing_play").assertContentDescriptionEquals("继续播放")
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun orderDefaultsToShuffleAndTogglePersists() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        runBlocking { app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.GLOBAL)) } }
        compose.waitUntil(3000) { order() == PlaybackOrder.SHUFFLE }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("player_order")
            .assertContentDescriptionEquals("随机播放")
            .performClick()
        compose.waitUntil(3000) { order() == PlaybackOrder.SEQUENTIAL }
        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("now_playing_shuffle")
            .assertContentDescriptionEquals("随机播放")
            .assertIsNotSelected()
            .performClick()
        compose.waitUntil(3000) { order() == PlaybackOrder.SHUFFLE }
        compose.onNodeWithTag("now_playing_shuffle")
            .assertContentDescriptionEquals("随机播放")
            .assertIsSelected()

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { app.playerController.connected.value }
        compose.waitUntil(5000) { order() == PlaybackOrder.SHUFFLE }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("now_playing_shuffle")
            .assertContentDescriptionEquals("随机播放")
            .assertIsSelected()
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun shuffleSwipePlaysAnotherStation() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        runBlocking { app.userSettings.update { it.copy(playbackOrder = PlaybackOrder.SHUFFLE) } }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        // 随机模式下有当前台即可返回，借此确认设置已同步到播放队列再滑动。
        compose.waitUntil(3000) { app.radioQueue.canPrevious.value }

        compose.onNodeWithTag("player_info").performTouchInput { swipeLeft() }
        compose.waitUntil(8000) {
            val state = app.playerController.state.value
            state.station?.uuid != UUID(0, 1) && state.phase == PlaybackPhase.PLAYING
        }
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun cardSwipeWhilePlayingAutoPlaysSettledStation() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }

        compose.onNodeWithTag("layout_card").performClick()
        compose.waitUntil(3000) { displayMode() == RadioDisplayMode.CARD }
        compose.onNodeWithTag("station_cards").assertExists().performTouchInput { swipeLeft() }
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 2) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
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
        compose.onNodeWithText("自动播放").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("global_player").assertDoesNotExist()
        assertFalse(app.radioQueue.canPrevious.value)
        selectPage("nav_radio")
        compose.onNodeWithTag("page_radio").assertIsDisplayed()
    }

    @Test
    fun swipingOnTheBottomBarSwitchesPages() {
        compose.onNodeWithTag("nav_radio").assertIsSelected()
        // 菜单条本身也能横滑：左滑下一个、右滑上一个。
        compose.onNodeWithTag("bottom_navigation").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("page_music").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_music").assertIsSelected()
        compose.onNodeWithTag("bottom_navigation").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("page_radio").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_radio").assertIsSelected()
        compose.onNodeWithTag("page_radio").assertIsDisplayed()
    }

    @Test
    fun swipingHorizontallySwitchesPages() {
        compose.onNodeWithTag("nav_radio").assertIsSelected()
        // 列表模式下横滑整页切到音乐，导航高亮跟着走。
        compose.onNodeWithTag("page_radio").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("page_music").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_music").assertIsSelected()
        compose.onNodeWithTag("page_music").assertIsDisplayed()
        // 反方向滑回电台。
        compose.onNodeWithTag("page_music").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("page_radio").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_radio").assertIsSelected()
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
        compose.onNodeWithTag("global_player").assertDoesNotExist()
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
        val list = compose.onNodeWithTag("station_list").getUnclippedBoundsInRoot()
        val navigation = compose.onNodeWithTag("bottom_navigation").getUnclippedBoundsInRoot()
        assertTrue("List must end before navigation", list.bottom <= navigation.top)
        compose.onNodeWithTag("global_player").assertDoesNotExist()
        val minimum = if (compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()) 56.dp else 48.dp
        listOf("nav_radio", "nav_music", "nav_settings").forEach { tag ->
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
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(tag))
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
        compose.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[RadioViewModel::class].refresh(
                RadioScope(ScopeMode.COUNTRY, app.country), force = true
            )
        }
        compose.waitUntil(5000) {
            var firstPageReady = false
            compose.activityRule.scenario.onActivity { activity ->
                val radio = ViewModelProvider(activity)[RadioViewModel::class].radioState.value
                firstPageReady = !radio.isLoading && radio.cacheState == null && radio.stations.size == 30 && radio.hasMore
            }
            firstPageReady
        }
        val queries = app.queries.get()
        compose.onNodeWithTag("station_list").performScrollToIndex(29)
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
            compose.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[RadioViewModel::class].refresh(
                    RadioScope(ScopeMode.COUNTRY, app.country), force = true
                )
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("加载失败").fetchSemanticsNodes().isNotEmpty() }
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
        awaitScope(activity.getString(R.string.scope_picker_description, country.displayName(Locale.SIMPLIFIED_CHINESE)))
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
            compose.onNodeWithTag("scope_selector")
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

    @Test
    fun pausedSwipeStartsPlaybackOnTheNextStation() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.phase == PlaybackPhase.PLAYING &&
                app.playerController.state.value.station?.uuid == UUID(0, 1)
        }
        compose.onNodeWithTag("player_pause").performClick()
        compose.waitUntil(3000) { app.playerController.state.value.phase == PlaybackPhase.PAUSED }

        // 暂停时滑动换台即起播：滑到的台就是用户想听的台，不再停在暂停态。
        compose.onNodeWithTag("player_info").performTouchInput { swipeLeft() }
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 2) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        assertEquals(UUID(0, 2), radioState().selectedStationUuid)
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun displayModeSwitchKeepsPlaybackAndStation() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) { app.playerController.state.value.phase == PlaybackPhase.PLAYING }
        val queries = app.queries.get()

        compose.onNodeWithTag("layout_card").performClick()
        compose.waitUntil(3000) { displayMode() == RadioDisplayMode.CARD }
        assertEquals(UUID(0, 1), app.playerController.state.value.station?.uuid)
        assertEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)

        compose.onNodeWithTag("layout_list").performClick()
        compose.waitUntil(3000) { displayMode() == RadioDisplayMode.LIST }
        assertEquals(UUID(0, 1), app.playerController.state.value.station?.uuid)
        assertEquals(queries, app.queries.get())
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun miniPlayerOpensFullscreenAndBackKeepsPlayback() {
        playFirstStation()
        val resolutions = app.resolveCalls[UUID(0, 1)]?.get() ?: 0

        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("global_player").assertDoesNotExist()
        compose.onNode(
            hasText("Test station 1") and hasAnyAncestor(hasTestTag("now_playing_screen"))
        ).assertIsDisplayed()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("global_player").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(UUID(0, 1), app.playerController.state.value.station?.uuid)
        assertEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)
        assertEquals(resolutions, app.resolveCalls[UUID(0, 1)]?.get() ?: 0)
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun centerWaveformTogglesPlaybackWithAccessibleLabels() {
        playFirstStation()
        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("now_playing_play")
            .assertContentDescriptionEquals("暂停播放")
            .performClick()
        compose.waitUntil(3000) {
            app.playerController.state.value.phase == PlaybackPhase.PAUSED
        }
        compose.onNodeWithTag("now_playing_play")
            .assertContentDescriptionEquals("继续播放")
            .performClick()
        compose.waitUntil(5000) {
            app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun swipeUpAndArtworkGesturesUseTheSharedQueue() {
        playFirstStation()
        compose.onNodeWithTag("player_info").performTouchInput { swipeUp() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("now_playing_artwork").performTouchInput { swipeLeft() }
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 2) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("now_playing_artwork").performTouchInput { swipeRight() }
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.waitForIdle()
        compose.onNodeWithTag("now_playing_next").assertIsEnabled().performClick()
        compose.waitUntil(8000) {
            val current = app.playerController.state.value
            current.station?.uuid != null && current.station?.uuid != UUID(0, 1) &&
                current.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("now_playing_previous").performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1)
        }
        compose.onNodeWithTag("now_playing_artwork").performTouchInput { swipeDown() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("global_player").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(UUID(0, 1), app.playerController.state.value.station?.uuid)
        assertEquals(PlaybackPhase.PLAYING, app.playerController.state.value.phase)
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun sleepTimerStartsSurvivesCollapseAndCanBeCancelled() {
        playFirstStation()
        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("now_playing_sleep").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("sleep_timer_panel").fetchSemanticsNodes().isNotEmpty()
        }
        listOf(15, 30, 45, 60).forEach { minutes ->
            compose.onNodeWithTag("sleep_timer_$minutes").assertIsDisplayed()
        }
        compose.onNodeWithTag("sleep_timer_custom").assertIsDisplayed()
        compose.onNodeWithTag("sleep_timer_15").performClick()
        compose.waitUntil(3000) {
            app.sleepTimer.state.value.durationMinutes == 15
        }
        compose.onNodeWithTag("now_playing_sleep").assertIsSelected()

        compose.onNodeWithTag("now_playing_collapse").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("global_player").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("收起播放页不应取消睡眠定时", app.sleepTimer.state.value.isActive)

        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("now_playing_sleep").performClick()
        compose.onNodeWithTag("sleep_timer_cancel").performClick()
        compose.waitUntil(3000) { !app.sleepTimer.state.value.isActive }
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    @Test
    fun customSleepTimerAcceptsValidMinutes() {
        playFirstStation()
        compose.onNodeWithTag("player_info").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("now_playing_sleep").performClick()
        compose.onNodeWithTag("sleep_timer_custom").performClick()
        compose.onNodeWithTag("sleep_timer_custom_input").performTextInput("12")
        compose.onNodeWithTag("sleep_timer_custom_confirm").performClick()
        compose.waitUntil(3000) {
            app.sleepTimer.state.value.durationMinutes == 12
        }
        app.sleepTimer.cancel()
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }

    private fun playFirstStation() {
        compose.waitUntil(5000) { app.playerController.connected.value }
        val first = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.station?.uuid == UUID(0, 1) &&
                app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
    }

    private fun radioState() = ViewModelProvider(compose.activity)[RadioViewModel::class].radioState.value

    private fun displayMode() = runBlocking { app.userSettings.settings.first().displayMode }

    private fun order() = runBlocking { app.userSettings.settings.first().playbackOrder }

    private fun openScopePicker() {
        compose.onNodeWithTag("scope_selector").performClick()
    }

    private fun countryScopeLabel(): String {
        val activity = compose.activity
        return activity.getString(R.string.scope_picker_description, app.country.displayName(Locale.SIMPLIFIED_CHINESE))
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

    // 低性能模拟器上 Activity 首帧可能晚于测试线程，先等语义树就绪。
    private fun awaitCompose(timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = runCatching {
                compose.onAllNodesWithTag("brand_header").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (ready) return
            Thread.sleep(100)
        }
        throw AssertionError("Compose 语义树未就绪")
    }

    private fun selectPage(tag: String) {
        val page = when (tag) {
            "nav_settings" -> "page_settings"
            "nav_music" -> "page_music"
            else -> "page_radio"
        }
        // 慢速模拟器上首次点击可能落在重组中的布局上，重试直到目标页面出现。
        repeat(3) {
            val node = compose.onNodeWithTag(tag)
            if (compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
            node.performClick()
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(page).fetchSemanticsNodes().isNotEmpty()) return
        }
        throw AssertionError("页面未打开：$page")
    }

}
