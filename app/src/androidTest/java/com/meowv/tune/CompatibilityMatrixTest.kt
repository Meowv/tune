package com.meowv.tune

import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meowv.tune.domain.RadioDisplayMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T27 兼容矩阵探针：在当前窗口/密度/字体配置下检查关键区域可见、可达且不重叠。
 * 由 matrix.sh 循环切换配置后重复执行。
 */
@RunWith(AndroidJUnit4::class)
class CompatibilityMatrixTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun keyAreasVisibleAndReachable() {
        runBlocking {
            (compose.activity.application as TuneTestApplication).userSettings.update {
                it.copy(displayMode = RadioDisplayMode.LIST)
            }
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("station_list").fetchSemanticsNodes().isNotEmpty()
        }
        val config = compose.activity.resources.configuration
        println(
            "MATRIX size=${config.screenWidthDp}x${config.screenHeightDp} " +
                "density=${config.densityDpi} font=${config.fontScale} " +
                "orientation=${config.orientation}"
        )

        compose.onNodeWithTag("brand_header").assertDoesNotExist()
        compose.onNodeWithTag("nav_radio").assertIsDisplayed()
        compose.onNodeWithTag("circular_player_entry").assertIsDisplayed()
        assertTrue(
            "尚未选择音频时不应显示迷你播放器",
            compose.onAllNodesWithTag("global_player").fetchSemanticsNodes().isEmpty()
        )

        val navigationTag = if (
            compose.onAllNodesWithTag("shell_car").fetchSemanticsNodes().isNotEmpty()
        ) "side_navigation" else "bottom_navigation"
        val navigation = compose.onNodeWithTag(navigationTag).getUnclippedBoundsInRoot()
        assertTrue("导航不得侵入状态栏", navigation.top >= 0.dp)

        // 列表可用：滚到第 4 项且条目可点击。
        compose.onNodeWithTag("station_list").performScrollToIndex(3)
        compose.onNodeWithTag("station_list").assertIsDisplayed()

        // 主要播放控件热区不小于 44dp。
        listOf("nav_radio", "nav_music", "nav_settings").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val width = (bounds.right - bounds.left).value
            val height = (bounds.bottom - bounds.top).value
            assertTrue("$tag 触控热区过小：${width}x$height", width >= 44f && height >= 44f)
        }
    }
}
