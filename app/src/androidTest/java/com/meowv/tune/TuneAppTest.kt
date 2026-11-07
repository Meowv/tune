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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TuneAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

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
        openScopePicker()
        compose.onNodeWithTag("scope_picker").assertIsDisplayed()
        compose.onNodeWithTag("scope_options").performScrollToNode(hasTestTag("scope_Japan"))
        compose.onNodeWithTag("scope_Japan").performClick()
        compose.onNodeWithTag("scope_selector").assertContentDescriptionEquals("收听范围，日本，切换地区")
        selectPage("nav_music")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav_music").assertIsSelected()
        compose.onNodeWithTag("page_music").assertIsDisplayed()
        selectPage("nav_radio")
        compose.onNodeWithTag("scope_selector").assertContentDescriptionEquals("收听范围，日本，切换地区")
        compose.onNodeWithTag("global_player").assertIsDisplayed()
    }

    @Test
    fun scopeDialogRestoresAndClosesExplicitly() {
        openScopePicker()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("scope_picker").assertIsDisplayed()
        compose.onNodeWithTag("scope_Auto").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithTag("scope_close").performClick()
        compose.onNodeWithTag("scope_picker").assertDoesNotExist()
        openScopePicker()
        compose.onNodeWithTag("scope_options").performScrollToNode(hasTestTag("scope_Global"))
        compose.onNodeWithTag("scope_Global").performClick()
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
        compose.onNodeWithTag("station_list").performScrollToIndex(6)
        compose.onNodeWithTag("station_QK").assertIsDisplayed()
        selectPage("nav_music")
        selectPage("nav_radio")
        compose.onNodeWithTag("station_QK").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("station_QK").assertIsDisplayed()
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
            val scopeLabel = findDescription(root, "收听范围，中国，切换地区") ?: error("Scope accessibility label missing")
            val scope = interactiveParent(scopeLabel)
            assertTrue(scope.isClickable)
            scopeLabel.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            assertTrue(scopeLabel.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            compose.waitUntil(5000) {
                currentRoot()?.let { findDescription(it, "收听范围，中国，切换地区") }?.isAccessibilityFocused == true
            }
            assertTrue(scope.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.onNodeWithTag("scope_picker").assertIsDisplayed()
            compose.onNodeWithTag("scope_Auto").assertIsSelected()
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

    private fun selectPage(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        node.performClick()
    }

}
