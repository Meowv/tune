package com.meowv.tune

import android.media.AudioManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneError
import com.meowv.tune.playback.AudioFocusEvent
import com.meowv.tune.playback.AudioFocusController
import com.meowv.tune.radio.RadioViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackAudioPolicyTest {
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val queue get() = app.radioQueue

    @Test fun systemFocusConstantsMapToExpectedEvents() {
        val events = mutableListOf<AudioFocusEvent>()
        val controller = AudioFocusController(app) { events += it }
        controller.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        controller.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        controller.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        controller.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(
            AudioFocusEvent.DUCK,
            AudioFocusEvent.TRANSIENT_LOSS,
            AudioFocusEvent.PERMANENT_LOSS,
            AudioFocusEvent.GAIN
        ), events)
    }

    @Test fun transientFocusLossResumesButPermanentLossWaitsForUser() = runBlocking {
        launchReady {
            playAndWait(station(1))
            val engine = requireNotNull(connection.engine)
            engine.onAudioFocusEvent(AudioFocusEvent.TRANSIENT_LOSS)
            withTimeout(3000) {
                connection.state.first {
                    it.phase == PlaybackPhase.PAUSED && it.error == TuneError.AUDIO_FOCUS_LOST
                }
            }
            engine.onAudioFocusEvent(AudioFocusEvent.GAIN)
            withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.error == null } }

            engine.onAudioFocusEvent(AudioFocusEvent.PERMANENT_LOSS)
            withTimeout(3000) {
                connection.state.first {
                    it.phase == PlaybackPhase.PAUSED && it.error == TuneError.AUDIO_FOCUS_LOST
                }
            }
            delay(250)
            assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)

            connection.dispatch(PlayerCommand.TogglePause)
            withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.error == null } }
        }
    }

    @Test fun duckingKeepsPlaybackRunning() = runBlocking {
        launchReady {
            playAndWait(station(1))
            val engine = requireNotNull(connection.engine)
            engine.onAudioFocusEvent(AudioFocusEvent.DUCK)
            delay(250)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            engine.onAudioFocusEvent(AudioFocusEvent.GAIN)
            delay(100)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
        }
    }

    @Test fun externalOutputRemovalAlwaysPauses() = runBlocking {
        launchReady {
            val first = station(1)
            playAndWait(first)
            requireNotNull(connection.engine).onExternalOutputRemoved()
            withTimeout(3000) {
                connection.state.first {
                    it.phase == PlaybackPhase.PAUSED && it.error == TuneError.AUDIO_DISCONNECTED
                }
            }
        }
    }

    @Test fun repeatedSystemPlayDoesNotCancelResolution() = runBlocking {
        launchReady {
            val target = station(1)
            app.playbackDelays[target.uuid] = 800
            try {
                connection.dispatch(PlayerCommand.Play(target))
                withTimeout(2000) { connection.state.first { it.phase == PlaybackPhase.RESOLVING } }
                onMain { requireNotNull(connection.engine).onSessionPlay() }
                withTimeout(5000) { connection.state.first { it.phase == PlaybackPhase.PLAYING } }
            } finally {
                app.playbackDelays.clear()
            }
        }
    }

    @Test fun manualPauseDuringFocusLossPreventsAutomaticResume() = runBlocking {
        launchReady {
            playAndWait(station(1))
            val engine = requireNotNull(connection.engine)
            onMain { engine.onAudioFocusEvent(AudioFocusEvent.TRANSIENT_LOSS) }
            withTimeout(2000) { connection.state.first { it.phase == PlaybackPhase.PAUSED } }
            onMain { engine.onSessionPause() }
            onMain { engine.onAudioFocusEvent(AudioFocusEvent.GAIN) }
            delay(500)
            assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
        }
    }

    @Test fun systemPauseDuringResolutionCanResume() = runBlocking {
        launchReady {
            val target = station(1)
            app.playbackDelays[target.uuid] = 800
            try {
                connection.dispatch(PlayerCommand.Play(target))
                withTimeout(2000) { connection.state.first { it.phase == PlaybackPhase.RESOLVING } }
                onMain { requireNotNull(connection.engine).onSessionPause() }
                withTimeout(2000) { connection.state.first { it.phase == PlaybackPhase.PAUSED } }
                delay(900)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
                onMain { requireNotNull(connection.engine).onSessionPlay() }
                withTimeout(5000) { connection.state.first { it.phase == PlaybackPhase.PLAYING } }
            } finally {
                app.playbackDelays.clear()
            }
        }
    }

    @Test fun disconnectDuringResolutionCancelsPlaybackUntilUserResumes() = runBlocking {
        launchReady {
            val target = station(1)
            app.playbackDelays[target.uuid] = 800
            try {
                connection.dispatch(PlayerCommand.Play(target))
                withTimeout(2000) { connection.state.first { it.phase == PlaybackPhase.RESOLVING } }
                onMain { requireNotNull(connection.engine).onExternalOutputRemoved() }
                withTimeout(2000) { connection.state.first { it.error == TuneError.AUDIO_DISCONNECTED } }
                delay(900)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
                connection.dispatch(PlayerCommand.TogglePause)
                withTimeout(5000) { connection.state.first { it.phase == PlaybackPhase.PLAYING } }
            } finally {
                app.playbackDelays.clear()
            }
        }
    }

    @Test fun networkLossDuringResolutionAutomaticallyResumesOnBriefReconnect() = runBlocking {
        launchReady {
            val target = station(1)
            app.playbackDelays[target.uuid] = 800
            try {
                connection.dispatch(PlayerCommand.Play(target))
                withTimeout(2000) { connection.state.first { it.phase == PlaybackPhase.RESOLVING } }
                app.mockNetwork.value = com.meowv.tune.domain.NetworkState()
                withTimeout(2000) { connection.state.first { it.error == TuneError.NETWORK_OFFLINE } }
                app.mockNetwork.value = com.meowv.tune.domain.NetworkState(com.meowv.tune.domain.NetworkType.WIFI)
                withTimeout(2000) { connection.state.first { it.error == null } }
                withTimeout(5000) { connection.state.first { it.phase == PlaybackPhase.PLAYING } }
            } finally {
                app.playbackDelays.clear()
                app.mockNetwork.value = com.meowv.tune.domain.NetworkState(com.meowv.tune.domain.NetworkType.WIFI)
            }
        }
    }

    @Test fun sleepTimerStopsPlaybackWithNoActivityController() = runBlocking {
        launchReady { scenario ->
            playAndWait(station(1))
            onMain { app.sleepTimer.start(1) }
            try {
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                withTimeout(3000) { connection.connected.first { !it } }
                withTimeout(65_000) { connection.state.first { it.phase == PlaybackPhase.IDLE } }
                assertEquals(false, app.sleepTimer.state.value.isActive)
            } finally {
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                withTimeout(5000) { connection.connected.first { it } }
                onMain { app.sleepTimer.cancel() }
            }
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private suspend fun launchReady(block: suspend (ActivityScenario<MainActivity>) -> Unit) {
        app.userSettings.update {
            it.copy(scope = RadioScope(ScopeMode.COUNTRY, app.country))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(5000) { connection.connected.first { it } }
            lateinit var model: RadioViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RadioViewModel::class] }
            withTimeout(5000) { model.radioState.first { !it.isLoading && it.stations.isNotEmpty() } }
            val scope = withTimeout(5000) { queue.activeScope.first { it != null } }!!
            queue.clearScope()
            queue.updateDirectory(scope, StationPage((1L..3L).map(::station)))
            connection.dispatch(PlayerCommand.Stop)
            try {
                block(scenario)
            } finally {
                connection.dispatch(PlayerCommand.Stop)
            }
        }
    }

    private suspend fun playAndWait(station: Station) {
        connection.dispatch(PlayerCommand.Play(station))
        withTimeout(8000) {
            connection.state.first { it.station?.uuid == station.uuid && it.phase == PlaybackPhase.PLAYING }
        }
    }

    private fun station(ordinal: Long) =
        Station(UUID(0, ordinal), "Test station $ordinal", app.country)
}
