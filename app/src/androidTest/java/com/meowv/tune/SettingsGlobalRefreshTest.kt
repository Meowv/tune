package com.meowv.tune

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.music.MusicPage
import com.meowv.tune.domain.music.MusicRepository
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.music.FakeMusicRepository
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// 设置页的“刷新数据”已扩展为全局：除电台目录外，也要重新拉取音乐推荐列表。
@OptIn(ExperimentalTestApi::class)
class SettingsGlobalRefreshTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun refreshDataReloadsMusicFeed() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as TuneTestApplication
        val calls = AtomicInteger()
        val fake = FakeMusicRepository(listOf(MusicTrack("refresh-1", "刷新测试曲目", listOf("qix"))))
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                calls.incrementAndGet()
                return fake.recommendations(limit, cursor)
            }
        }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(8000) { app.playerController.connected.value }
                compose.onNodeWithTag("nav_settings").performClick()
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("page_settings").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("page_settings").performScrollToNode(hasText("刷新数据"))

                val before = calls.get()
                compose.onNodeWithText("刷新数据").performClick()
                compose.waitUntil(8000) { calls.get() > before }
                assertTrue("刷新数据应触发音乐推荐重新加载", calls.get() > before)
            }
        } finally {
            app.testMusicRepository = null
        }
    }
}
