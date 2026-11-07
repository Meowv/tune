package com.meowv.tune

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.TuneSettings
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoPlayResumeTest {
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController

    @Before
    fun reset() = runBlocking<Unit> {
        app.failResume = false
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.COUNTRY, app.country), autoPlay = false) }
        app.radioQueue.clearScope()
        cleanSlate()
        app.resumeQueries.set(0)
        app.autoPlayAttempted = false
    }

    // 上一个用例或上一轮运行可能留下播放中的服务，先停干净再验证启动行为。
    private suspend fun cleanSlate() {
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            connection.dispatch(PlayerCommand.Stop)
            withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.IDLE } }
        }
        app.radioQueue.clearScope()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        runCatching { connection.dispatch(PlayerCommand.Stop) }
        app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.COUNTRY, app.country), autoPlay = false) }
        app.radioQueue.clearScope()
        app.autoPlayAttempted = false
        app.failResume = false
    }

    @Test fun autoPlayOffKeepsPlayerIdle() = runBlocking {
        app.userSettings.update { it.copy(autoPlay = false) }
        launch { scenario ->
            delay(1500)
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            assertEquals(0, app.resumeQueries.get())
            scenario.onActivity { }
        }
    }

    @Test fun enabledAutoPlayRestoresLastSuccessfulStation() = runBlocking {
        app.userSettings.update { it.copy(autoPlay = true) }
        app.userSettings.recordSuccessfulStation(UUID(0, 1))
        withTimeout(3000) { app.userSettings.settings.first { it.lastSuccessfulStation == UUID(0, 1) } }
        launch {
            withTimeout(8000) {
                connection.state.first { it.phase == PlaybackPhase.PLAYING && it.station?.uuid == UUID(0, 1) }
            }
        }
    }

    @Test fun unknownStationLeavesRadioPageUsable() = runBlocking {
        app.userSettings.update { it.copy(autoPlay = true) }
        app.userSettings.recordSuccessfulStation(UUID(0, 99))
        withTimeout(3000) { app.userSettings.settings.first { it.lastSuccessfulStation == UUID(0, 99) } }
        launch {
            delay(1500)
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            assertEquals(1, app.resumeQueries.get())
        }
    }

    @Test fun metadataFailureLeavesPlayerIdle() = runBlocking {
        app.userSettings.update { it.copy(autoPlay = true) }
        app.userSettings.recordSuccessfulStation(UUID(0, 1))
        withTimeout(3000) { app.userSettings.settings.first { it.lastSuccessfulStation == UUID(0, 1) } }
        app.failResume = true
        launch {
            delay(1500)
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            assertNull(connection.state.value.station)
        }
    }

    @Test fun otherCountryStationIsNotRestored() = runBlocking {
        app.userSettings.update { it.copy(autoPlay = true) }
        app.userSettings.recordSuccessfulStation(app.foreignStation.uuid)
        withTimeout(3000) { app.userSettings.settings.first { it.lastSuccessfulStation == app.foreignStation.uuid } }
        launch {
            delay(1500)
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
        }
    }

    @Test fun resumeDoesNotReplayWhenReturningToForeground() = runBlocking {
        app.userSettings.update { it.copy(autoPlay = true) }
        app.userSettings.recordSuccessfulStation(UUID(0, 1))
        withTimeout(3000) { app.userSettings.settings.first { it.lastSuccessfulStation == UUID(0, 1) } }
        launch { scenario ->
            withTimeout(8000) {
                connection.state.first { it.phase == PlaybackPhase.PLAYING && it.station?.uuid == UUID(0, 1) }
            }
            val queries = app.resumeQueries.get()
            connection.dispatch(PlayerCommand.Stop)
            withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.IDLE } }
            scenario.recreate()
            delay(1200)
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            assertEquals(queries, app.resumeQueries.get())
        }
    }

    @Test fun successfulPlaybackRecordsResumePoint() = runBlocking {
        app.userSettings.update { it.copy(autoPlay = true) }
        launch {
            withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }
            val scope = app.radioQueue.activeScope.value!!
            app.radioQueue.clearScope()
            app.radioQueue.updateDirectory(scope, com.meowv.tune.domain.StationPage(
                (1L..3L).map { com.meowv.tune.domain.Station(UUID(0, it), "Test station $it", app.country) }
            ))
            connection.dispatch(PlayerCommand.Play(com.meowv.tune.domain.Station(UUID(0, 2), "Test station 2", app.country)))
            withTimeout(8000) { connection.state.first { it.phase == PlaybackPhase.PLAYING } }
            withTimeout(3000) { app.userSettings.settings.first { it.lastSuccessfulStation == UUID(0, 2) } }
        }
    }

    private suspend fun launch(block: suspend (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(5000) { connection.connected.first { it } }
            block(scenario)
        }
    }
}
