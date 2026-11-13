package com.meowv.tune

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneError
import com.meowv.tune.radio.RadioViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackReliabilityTest {
    @Test fun stalledResolutionStopsAtOverallBudgetWithoutTryingMoreStations() = runBlocking {
        launchReady(1L..3L) {
            val first = station(1)
            app.playbackDelays[first.uuid] = 20_000
            try {
                connection.dispatch(PlayerCommand.Play(first))
                val failed = withTimeout(11_000) { connection.state.first { it.phase == PlaybackPhase.ERROR } }
                assertEquals(TuneError.TIMEOUT, failed.error)
                assertEquals(first.uuid, failed.station?.uuid)
                delay(200)
                assertEquals(1, app.resolveCalls[first.uuid]?.get())
                assertTrue((2L..3L).all { app.resolveCalls[UUID(0, it)] == null })
            } finally {
                app.playbackDelays.clear()
            }
        }
    }

    @Test fun manualPauseDuringNetworkLossPreventsReconnectPlayback() = runBlocking {
        launchReady(1L..2L) {
            connection.dispatch(PlayerCommand.Play(station(1)))
            waitFor(station(1))
            app.mockNetwork.value = NetworkState()
            withTimeout(3000) { connection.state.first { it.error == TuneError.NETWORK_OFFLINE } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                requireNotNull(connection.engine).onSessionPause()
            }
            delay(100)
            app.mockNetwork.value = NetworkState(NetworkType.WIFI)
            delay(500)
            assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
        }
    }

    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val queue get() = app.radioQueue

    @Test fun sameStationRetriesTwiceBeforePlayback() = runBlocking {
        launchReady(1L..2L) {
            val first = station(1)
            app.resolveFailures[first.uuid] = AtomicInteger(2)
            connection.dispatch(PlayerCommand.Play(first))
            waitFor(first)
            assertEquals(3, app.resolveCalls[first.uuid]?.get())
            assertEquals(null, connection.state.value.error)
        }
    }

    @Test fun recoveryUsesAtMostThreeOrderedSameScopeAlternatives() = runBlocking {
        launchReady(1L..5L) {
            val stations = (1L..5L).map(::station)
            stations.take(4).forEach { app.resolveFailures[it.uuid] = AtomicInteger(10) }
            connection.dispatch(PlayerCommand.Play(stations.first()))
            val failed = withTimeout(10_000) {
                connection.state.first { it.phase == PlaybackPhase.ERROR }
            }
            assertEquals(TuneError.TIMEOUT, failed.error)
            stations.take(4).forEach { station ->
                assertEquals("calls=${app.resolveCalls.entries.associate { it.key to it.value.get() }}",
                    3, app.resolveCalls[station.uuid]?.get())
            }
            assertEquals(null, app.resolveCalls[stations.last().uuid])
            assertFalse(queue.canPrevious.value)

            delay(250)
            stations.take(4).forEach { assertEquals(3, app.resolveCalls[it.uuid]?.get()) }
        }
    }

    @Test fun directoryTimeoutKeepsCurrentStreamPlaying() = runBlocking {
        launchReady(1L..1L, nextOffset = 30) {
            val first = station(1)
            connection.dispatch(PlayerCommand.Play(first))
            waitFor(first)
            app.failQueries = true
            try {
                connection.dispatch(PlayerCommand.Next)
                withTimeout(3000) {
                    connection.state.first { it.operationFailure?.error == TuneError.CONNECTION }
                }
                assertEquals(null, connection.state.value.error)
                assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
                assertEquals(first.uuid, connection.state.value.station?.uuid)
            } finally {
                app.failQueries = false
            }
        }
    }

    @Test fun streamAndResolverFailuresAdvanceToNextSameScopeStation() = runBlocking {
        launchReady(24L..26L) {
            val missing = station(24)
            val noSource = station(25)
            val playable = station(26)
            connection.dispatch(PlayerCommand.Play(missing))
            waitFor(playable)
            assertEquals(1, app.resolveCalls[missing.uuid]?.get())
            assertEquals(1, app.resolveCalls[noSource.uuid]?.get())
            assertEquals(1, app.resolveCalls[playable.uuid]?.get())
        }
    }

    @Test fun scopeChangeCancelsRetryChainWithoutStartingAnotherRequest() = runBlocking {
        launchReady(1L..2L) {
            val first = station(1)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            app.resolutionStarts[first.uuid] = started
            app.latePlaybackDelays[first.uuid] = 800
            app.resolveFailures[first.uuid] = AtomicInteger(10)
            try {
                connection.dispatch(PlayerCommand.Play(first))
                withTimeout(2000) { started.await() }
                app.userSettings.update { it.copy(scope = RadioScope(ScopeMode.GLOBAL)) }
                withTimeout(3000) {
                    queue.activeScope.first { it?.scope?.mode == ScopeMode.GLOBAL }
                }
                delay(1_000)
                assertEquals(1, app.resolveCalls[first.uuid]?.get())
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
            } finally {
                app.latePlaybackDelays.clear()
                app.resolutionStarts.clear()
            }
        }
    }

    @Test fun networkLossPausesWithoutContinuingFailover() = runBlocking {
        launchReady(1L..4L) {
            val first = station(1)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            app.resolutionStarts[first.uuid] = started
            app.latePlaybackDelays[first.uuid] = 300
            app.resolveFailures[first.uuid] = AtomicInteger(10)
            try {
                connection.dispatch(PlayerCommand.Play(first))
                withTimeout(2000) { started.await() }
                app.mockNetwork.value = NetworkState(NetworkType.OFFLINE, metered = false)
                withTimeout(3000) {
                    connection.state.first { it.error == TuneError.NETWORK_OFFLINE }
                }
                delay(700)
                assertEquals(1, app.resolveCalls[first.uuid]?.get())
                assertTrue((2L..4L).all { app.resolveCalls[UUID(0, it)] == null })
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
            } finally {
                app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
                app.latePlaybackDelays.clear()
                app.resolutionStarts.clear()
            }
        }
    }

    private suspend fun launchReady(
        ordinals: LongRange?,
        nextOffset: Int? = null,
        block: suspend () -> Unit
    ) {
        app.userSettings.update {
            it.copy(scope = RadioScope(ScopeMode.COUNTRY, app.country), playbackOrder = PlaybackOrder.SEQUENTIAL)
        }
        app.resolveCalls.clear()
        app.resolveFailures.clear()
        app.latePlaybackDelays.clear()
        app.resolutionStarts.clear()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(5000) { connection.connected.first { it } }
            lateinit var model: RadioViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RadioViewModel::class] }
            withTimeout(5000) { model.radioState.first { !it.isLoading && it.stations.isNotEmpty() } }
            val scope = withTimeout(5000) { queue.activeScope.first { it != null } }!!
            queue.clearScope()
            val stations = ordinals?.map(::station).orEmpty()
            queue.updateDirectory(scope, StationPage(stations, nextOffset))
            connection.dispatch(PlayerCommand.Stop)
            try {
                block()
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.resolveFailures.clear()
                app.playbackDelays.clear()
                app.latePlaybackDelays.clear()
                app.resolutionStarts.clear()
            }
        }
    }

    private suspend fun waitFor(station: Station) {
        withTimeout(8000) {
            connection.state.first { it.station?.uuid == station.uuid && it.phase == PlaybackPhase.PLAYING }
        }
    }

    private fun station(ordinal: Long) =
        Station(UUID(0, ordinal), "Test station $ordinal", app.country)
}
