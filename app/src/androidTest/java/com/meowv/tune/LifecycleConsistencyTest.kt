package com.meowv.tune

import android.app.ActivityManager
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.playback.RadioPlaybackService
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleConsistencyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication
    private val connection get() = app.playerController

    @Before
    fun prepare() = runBlocking<Unit> {
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.COUNTRY, app.country)) }
        runCatching { connection.dispatch(PlayerCommand.Stop) }
        connection.state.first { it.phase == PlaybackPhase.IDLE }
        withTimeout(5000) { connection.connected.first { it } }
        val scope = withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }!!
        app.radioQueue.clearScope()
        app.radioQueue.updateDirectory(scope, StationPage((1L..12L).map(::station)))
        app.resolveCalls.clear()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        runCatching { connection.dispatch(PlayerCommand.Stop) }
        app.radioQueue.clearScope()
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Test
    fun rotationKeepsPlaybackAndDoesNotReloadDirectory() = runBlocking<Unit> {
        playAndWait(3)
        val queries = app.queries.get()
        val resolutions = app.resolveCalls[UUID(0, 3)]?.get() ?: 0

        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        delay(800)

        assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
        assertEquals(UUID(0, 3), connection.state.value.station?.uuid)
        assertEquals("旋转不应重复请求目录", queries, app.queries.get())
        assertEquals("旋转不应重复解析音源", resolutions, app.resolveCalls[UUID(0, 3)]?.get() ?: 0)
        assertEquals("全进程仍只有一个播放服务", 1, runningServices())
    }

    @Test
    fun backgroundReturnKeepsSameStationAndSession() = runBlocking<Unit> {
        playAndWait(4)
        val resolutions = app.resolveCalls[UUID(0, 4)]?.get() ?: 0
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(600)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        withTimeout(5000) { connection.connected.first { it } }
        compose.waitForIdle()
        delay(400)

        assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
        assertEquals(UUID(0, 4), connection.state.value.station?.uuid)
        assertEquals("回前台不应重新解析音源", resolutions, app.resolveCalls[UUID(0, 4)]?.get() ?: 0)
        assertEquals(1, runningServices())
    }

    @Test
    fun recreationRestoresPageScopeAndScrollPosition() = runBlocking<Unit> {
        playAndWait(2)
        selectPage("nav_settings")
        compose.onNodeWithText("媒体断开暂停").performScrollTo()
        val resolutions = app.resolveCalls[UUID(0, 2)]?.get() ?: 0

        compose.activityRule.scenario.recreate()
        withTimeout(5000) { connection.connected.first { it } }
        compose.waitForIdle()
        delay(500)

        compose.onNodeWithTag("page_settings").assertExists()
        assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
        assertEquals(UUID(0, 2), connection.state.value.station?.uuid)
        assertEquals("重建不应重复解析音源", resolutions, app.resolveCalls[UUID(0, 2)]?.get() ?: 0)

        selectPage("nav_radio")
        compose.onNodeWithTag("station_list").performScrollToIndex(6)
        val before = compose.onNodeWithText("Test station 7").getUnclippedBoundsInRoot().top
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        delay(500)
        // 滚动位置由 rememberSaveable 保存，重建后同一条目仍在相近位置。
        val after = compose.onNodeWithText("Test station 7").getUnclippedBoundsInRoot().top
        assertTrue("滚动位置应恢复：$before -> $after", (before - after).value.let { kotlin.math.abs(it) } <= 8f)
        assertEquals(UUID(0, 2), connection.state.value.station?.uuid)
    }

    @Test
    fun navigationAcrossPagesSharesOnePlayerState() = runBlocking<Unit> {
        playAndWait(5)
        listOf("nav_music", "nav_settings", "nav_radio").forEach { tag ->
            selectPage(tag)
            compose.waitForIdle()
            assertEquals("页面切换不应改变播放状态", UUID(0, 5), connection.state.value.station?.uuid)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
        }
        assertEquals(1, runningServices())
    }

    @Test
    fun repeatedForegroundTransitionsDoNotDuplicateRequests() = runBlocking<Unit> {
        playAndWait(6)
        val resolutions = app.resolveCalls[UUID(0, 6)]?.get() ?: 0
        repeat(3) {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            delay(300)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(5000) { connection.connected.first { it } }
            compose.waitForIdle()
        }
        assertEquals(resolutions, app.resolveCalls[UUID(0, 6)]?.get() ?: 0)
        assertEquals(UUID(0, 6), connection.state.value.station?.uuid)
    }

    private suspend fun playAndWait(ordinal: Long) {
        connection.dispatch(PlayerCommand.Play(station(ordinal)))
        withTimeout(8000) {
            connection.state.first { it.station?.uuid == UUID(0, ordinal) && it.phase == PlaybackPhase.PLAYING }
        }
    }

    private fun selectPage(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    private fun runningServices(): Int {
        val manager = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .count { it.service.className == RadioPlaybackService::class.java.name }
    }

    private fun station(ordinal: Long) = Station(UUID(0, ordinal), "Test station $ordinal", app.country)
}
