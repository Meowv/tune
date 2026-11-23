package com.meowv.tune

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val isCar = compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()
        val settings = compose.onNodeWithTag("page_settings").getUnclippedBoundsInRoot()
        if (isCar && settings.right - settings.left >= 660.dp) {
            val autoplay = compose.onNodeWithTag("setting_${R.string.setting_autoplay}").getUnclippedBoundsInRoot()
            val source = compose.onNodeWithTag("setting_${R.string.setting_source}").getUnclippedBoundsInRoot()
            assertEquals(autoplay.top.value, source.top.value, 1f)
            assertTrue(source.left > autoplay.right)
        }
        capture("settings")
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
        compose.onNodeWithText("当前版本 v$localVersion").assertIsDisplayed()
        compose.onNodeWithTag("update_status_title").assertIsDisplayed()
        compose.onNodeWithText("发现新版本 1.1.0").assertIsDisplayed()
        val status = compose.onNodeWithTag("update_status_title").getUnclippedBoundsInRoot()
        val version = compose.onNodeWithTag("update_version_card").getUnclippedBoundsInRoot()
        if (isCar) {
            compose.onNodeWithTag("side_navigation").assertIsDisplayed()
            val updateBounds = compose.onNodeWithTag("page_update").getUnclippedBoundsInRoot()
            val playerBounds = compose.onNodeWithTag("mini_player_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(updateBounds.bottom <= playerBounds.top)
            if (updateBounds.right - updateBounds.left >= 668.dp) {
                assertTrue(status.left > version.right)
            } else {
                assertTrue(status.bottom <= version.top)
            }
            compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("update_back")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assertIsFocused()
        }
        capture("update")
        compose.onNodeWithText("构建版本").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("优化电台播放体验").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2026-11-15").assertDoesNotExist()
        compose.onNodeWithText("稳定版").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("update_back").performClick()
        compose.waitUntil(4000) {
            compose.onAllNodesWithTag("page_update").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("page_settings").assertIsDisplayed()
        if (isCar) {
            scrollTo("版本与更新")
            compose.onNodeWithText("版本与更新").performClick()
            compose.onNodeWithTag("nav_radio").performClick()
            compose.onNodeWithTag("page_update").assertDoesNotExist()
            compose.onNodeWithTag("page_radio").assertIsDisplayed()
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureV5Settings") != "true") return
        val file = File(compose.activity.cacheDir, "v5-$name.png")
        file.outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
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
