package com.meowv.tune.ui

import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.Station
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class StationPlaybackStatusTest {
    private val station = Station(UUID(0, 1), "Radio", null)

    @Test
    fun onlyTheActualRadioStationShowsPlaybackStatus() {
        val playing = PlayerUiState(PlaybackPhase.PLAYING, station)
        assertEquals(StationPlaybackVisual.None, stationPlaybackVisual(UUID(0, 2), playing, true))
        assertEquals(StationPlaybackVisual.None, stationPlaybackVisual(station.uuid, playing.copy(station = null), true))
        assertEquals(
            StationPlaybackVisual.None,
            stationPlaybackVisual(station.uuid, playing.copy(source = PlaybackSource.MUSIC), true)
        )
    }

    @Test
    fun loadingPauseAndFailureRemainDistinctFromPlaying() {
        val expected = mapOf(
            PlaybackPhase.IDLE to StationPlaybackVisual.None,
            PlaybackPhase.RESOLVING to StationPlaybackVisual.Connecting,
            PlaybackPhase.BUFFERING to StationPlaybackVisual.Buffering,
            PlaybackPhase.RETRYING to StationPlaybackVisual.Retrying,
            PlaybackPhase.PLAYING to StationPlaybackVisual.Playing,
            PlaybackPhase.PAUSED to StationPlaybackVisual.Paused,
            PlaybackPhase.ERROR to StationPlaybackVisual.Error
        )
        expected.forEach { (phase, visual) ->
            assertEquals(phase.name, visual, stationPlaybackVisual(station.uuid, PlayerUiState(phase, station), true))
        }
    }

    @Test
    fun disconnectedServiceCannotKeepShowingPlaying() {
        val playing = PlayerUiState(PlaybackPhase.PLAYING, station)
        assertEquals(StationPlaybackVisual.Disconnected, stationPlaybackVisual(station.uuid, playing, false))
        assertEquals(StationPlaybackVisual.None, stationPlaybackVisual(UUID(0, 2), playing, false))
        assertEquals(
            StationPlaybackVisual.None,
            stationPlaybackVisual(station.uuid, playing.copy(phase = PlaybackPhase.IDLE), false)
        )
    }
}
