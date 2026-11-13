package com.meowv.tune

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meowv.tune.domain.*
import com.meowv.tune.radio.RadioViewModel
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStateConsistencyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication
    private val first get() = Station(UUID(0, 1), "Test station 1", app.country)

    @Before fun prepare() = runBlocking<Unit> {
        app.failQueries = false
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update { TuneSettings(scope = RadioScope(ScopeMode.GLOBAL),
            displayMode = RadioDisplayMode.CARD, playbackOrder = PlaybackOrder.SEQUENTIAL) }
        withTimeout(5000) { app.playerController.connected.first { it } }
        app.playerController.dispatch(PlayerCommand.Stop)
        val model = ViewModelProvider(compose.activity)[RadioViewModel::class.java]
        withTimeout(5000) { model.radioState.first { !it.isLoading && it.stations.isNotEmpty() } }
        app.playerController.dispatch(PlayerCommand.Play(first))
        withTimeout(8000) { app.playerController.state.first { it.phase == PlaybackPhase.PLAYING } }
    }

    @Test fun nextQueryFailureShowsMessageWhileFullscreenStillPausesCurrentAudio() {
        compose.onNodeWithTag("circular_player_entry").performClick()
        app.failQueries = true
        try {
            compose.onNodeWithTag("now_playing_next").performClick()
            compose.waitUntil(5000) { app.playerController.state.value.operationFailure != null }
            assertNull(app.playerController.state.value.error)
            compose.onNodeWithTag("now_playing_play")
                .assertContentDescriptionEquals(compose.activity.getString(R.string.now_playing_pause_accessibility))
                .performClick()
            compose.waitUntil(3000) { app.playerController.state.value.phase == PlaybackPhase.PAUSED }
            assertEquals(first.uuid, app.playerController.state.value.station?.uuid)
        } finally {
            app.failQueries = false
            runBlocking { app.playerController.dispatch(PlayerCommand.Stop) }
        }
    }

    @Test fun pausedBrowsingSurvivesPlaybackUpdatesAndFullscreenControlsDisplayedStation() {
        runBlocking { app.playerController.dispatch(PlayerCommand.TogglePause) }
        compose.waitUntil(3000) { app.playerController.state.value.phase == PlaybackPhase.PAUSED }
        compose.onNodeWithTag("station_cards").performTouchInput {
            swipeLeft(startX = centerX + 100f, endX = centerX - 180f, durationMillis = 500)
        }
        val model = ViewModelProvider(compose.activity)[RadioViewModel::class.java]
        compose.waitUntil(3000) { model.radioState.value.selectedStationUuid == UUID(0, 2) }
        app.mockNetwork.value = NetworkState()
        compose.waitUntil(3000) { app.playerController.state.value.error == TuneError.NETWORK_OFFLINE }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        compose.waitUntil(3000) { app.playerController.state.value.error == null }
        assertEquals(UUID(0, 2), model.radioState.value.selectedStationUuid)
        compose.onNodeWithTag("circular_player_entry").performClick()
        compose.onNode(hasText(first.name) and hasAnyAncestor(hasTestTag("now_playing_title")))
            .assertIsDisplayed()
        compose.onNodeWithTag("now_playing_play").performClick()
        compose.waitUntil(8000) { app.playerController.state.value.phase == PlaybackPhase.PLAYING }
        assertEquals(first.uuid, app.playerController.state.value.station?.uuid)
        assertEquals(UUID(0, 2), model.radioState.value.selectedStationUuid)
        runBlocking { app.playerController.dispatch(PlayerCommand.Stop) }
    }
}
