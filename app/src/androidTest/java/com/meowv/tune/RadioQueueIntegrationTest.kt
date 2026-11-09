package com.meowv.tune

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.radio.RadioViewModel
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioQueueIntegrationTest {
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val queue get() = app.radioQueue

    @Test fun rapidCommandsCancelEarlierResolutionAndOnlyLatestAdvancesHistory() = runBlocking {
        launchReady {
            val first = station(1, app.country)
            val second = station(2, app.country)
            app.playbackDelays[first.uuid] = 1500
            try {
                connection.dispatch(PlayerCommand.Play(first))
                connection.dispatch(PlayerCommand.Play(second))
                waitFor(second, "rapid latest command")
                assertFalse(queue.canPrevious.value)
                assertTrue(queue.canNext.value)
            } finally {
                app.playbackDelays.clear()
            }
        }
    }

    @Test fun nextPreviousAndSingleStationCapabilitiesMatchQueueRules() = runBlocking {
        launchReady {
            val first = station(1, app.country)
            val second = station(2, app.country)
            playAndWait(first, "initial station")
            assertFalse(connection.state.value.canPrevious)
            assertTrue(connection.state.value.canNext)

            connection.dispatch(PlayerCommand.Next)
            waitFor(second, "next station")
            assertTrue(connection.state.value.canPrevious)

            connection.dispatch(PlayerCommand.Previous)
            waitFor(first, "previous station")
            assertFalse(connection.state.value.canPrevious)

            val scope = requireNotNull(queue.activeScope.value)
            queue.clearScope()
            val single = station(9, scope.country)
            queue.updateDirectory(scope, StationPage(listOf(single)))
            playAndWait(single, "single station")
            assertFalse(connection.state.value.canPrevious)
            assertTrue(connection.state.value.canNext)

            connection.dispatch(PlayerCommand.Next)
            waitFor(single, "single station wrap")
            assertFalse(connection.state.value.canPrevious)
            assertTrue(connection.state.value.canNext)
        }
    }

    @Test fun lateNonCancellableResolutionCannotOverwriteLatestCommand() = runBlocking {
        launchReady {
            val first = station(1, app.country)
            val second = station(2, app.country)
            val started = CompletableDeferred<Unit>()
            app.resolutionStarts[first.uuid] = started
            app.latePlaybackDelays[first.uuid] = 1000
            try {
                connection.dispatch(PlayerCommand.Play(first))
                withTimeout(2000) { started.await() }
                connection.dispatch(PlayerCommand.Play(second))
                waitFor(second, "latest command after late resolution")
                delay(1100)
                assertEquals(second.uuid, connection.state.value.station?.uuid)
                assertFalse(queue.canPrevious.value)
            } finally {
                app.latePlaybackDelays.clear()
                app.resolutionStarts.clear()
            }
        }
    }

    @Test fun scopeChangeKeepsCurrentAudioAndNextUsesNewQueue() = runBlocking {
        launchReady {
            val original = station(1, app.country)
            playAndWait(original, "original scope station")

            val us = requireNotNull(CountryCode.parse("US"))
            val scope = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, us), us)
            val firstUs = station(20, us)
            val secondUs = station(21, us)
            queue.clearScope()
            queue.updateDirectory(scope, StationPage(listOf(firstUs, secondUs)))

            assertEquals(original.uuid, connection.state.value.station?.uuid)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            assertEquals(scope, queue.activeScope.value)

            connection.dispatch(PlayerCommand.Next)
            waitFor(firstUs, "first station in new scope")
            connection.dispatch(PlayerCommand.Next)
            waitFor(secondUs, "second station in new scope")
            assertTrue(connection.state.value.canPrevious)
            connection.dispatch(PlayerCommand.Previous)
            waitFor(firstUs, "previous station in new scope")
        }
    }

    private suspend fun launchReady(block: suspend () -> Unit) {
        app.userSettings.update {
            it.copy(scope = RadioScope(ScopeMode.COUNTRY, app.country), playbackOrder = PlaybackOrder.SEQUENTIAL)
        }
        // 同进程其他用例可能留下故障注入或延迟，先清空再进入用例。
        app.resolveFailures.clear()
        app.playbackDelays.clear()
        app.latePlaybackDelays.clear()
        app.resolutionStarts.clear()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(5000) { connection.connected.first { it } }
            lateinit var model: RadioViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RadioViewModel::class] }
            withTimeout(5000) { model.radioState.first { !it.isLoading && it.stations.isNotEmpty() } }
            val scope = withTimeout(5000) { queue.activeScope.first { it != null } }!!
            queue.clearScope()
            val stations = (1L..36L).map { station(it, scope.country) }
            queue.updateDirectory(scope, StationPage(stations, nextOffset = null))
            // 同进程其他用例可能留下播放中的服务或未完成的解析，先归零再进入用例。
            connection.dispatch(PlayerCommand.Stop)
            withTimeout(5000) { connection.state.first { it.phase == PlaybackPhase.IDLE } }
            try {
                block()
            } finally {
                connection.dispatch(PlayerCommand.Stop)
            }
        }
    }

    private suspend fun playAndWait(station: Station, label: String) {
        connection.dispatch(PlayerCommand.Play(station))
        waitFor(station, label)
    }

    private suspend fun waitFor(station: Station, label: String) {
        try {
            withTimeout(8000) {
                connection.state.first { it.station?.uuid == station.uuid && it.phase == PlaybackPhase.PLAYING }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$label timed out: state=${connection.state.value}, scope=${queue.activeScope.value}", error)
        }
    }

    private fun station(ordinal: Long, country: CountryCode?) =
        Station(UUID(0, ordinal), "Test station $ordinal", country)
}
