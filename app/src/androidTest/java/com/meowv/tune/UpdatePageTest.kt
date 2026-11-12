package com.meowv.tune

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.TuneSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class UpdatePageTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication

    @Before
    fun resetSettings() = runBlocking<Unit> {
        app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.GLOBAL)) }
    }

    @Test
    fun settingsEntryShowsUpdateStatusAndOpensPage() {
        openSettings()
        scrollTo("版本与更新")
        val localVersion = compose.activity.packageManager
            .getPackageInfo(compose.activity.packageName, 0).versionName
        compose.waitUntil(8000) {
            compose.onAllNodesWithText("v$localVersion · 发现新版本 v1.1.0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("版本与更新").performClick()

        compose.waitUntil(8000) {
            compose.onAllNodesWithTag("page_update").fetchSemanticsNodes().isNotEmpty()
        }
        // 覆盖层刚组合时首帧可能还没完成布局，等一帧再断言可见。
        compose.waitForIdle()
        compose.onNodeWithTag("update_status_title").assertIsDisplayed()
        compose.onNodeWithText("发现新版本 1.1.0").assertIsDisplayed()
        compose.onNodeWithText("MeowvTune 1.1.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("优化电台播放体验").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2026-11-15").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("stable").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("update_back").performClick()
        compose.waitUntil(4000) {
            compose.onAllNodesWithTag("page_update").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("page_settings").assertIsDisplayed()
    }

    private fun openSettings() {
        repeat(3) {
            compose.onNodeWithTag("nav_settings").performClick()
            compose.waitForIdle()
            if (compose.onAllNodesWithTag("page_settings").fetchSemanticsNodes().isNotEmpty()) return
        }
        throw AssertionError("设置页未打开")
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("page_settings").performScrollToNode(hasText(text))
    }
}
