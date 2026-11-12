package com.meowv.tune.ui

import com.meowv.tune.domain.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CircularPlayerEntryTest {
    private val station = Station(UUID(0, 1), "Radio", null)

    @Test fun phasesAndDisconnectedServiceHaveDistinctVisuals() {
        val expected = listOf(CircularPlayerVisual.Default, CircularPlayerVisual.Loading,
            CircularPlayerVisual.Loading, CircularPlayerVisual.Playing, CircularPlayerVisual.Paused,
            CircularPlayerVisual.Loading, CircularPlayerVisual.Error)
        PlaybackPhase.entries.forEachIndexed { index, phase ->
            assertEquals(expected[index], circularPlayerVisual(PlayerUiState(phase, station), true))
        }
        assertEquals(CircularPlayerVisual.Disconnected,
            circularPlayerVisual(PlayerUiState(PlaybackPhase.PLAYING, station), false))
        assertEquals(CircularPlayerVisual.Default, circularPlayerVisual(PlayerUiState(), false))
    }

    @Test fun absentOrBrokenArtworkDoesNotChangePlaybackVisual() {
        listOf(null, "", "file:///missing-image.png").forEach {
            assertEquals(CircularPlayerVisual.Playing,
                circularPlayerVisual(PlayerUiState(PlaybackPhase.PLAYING, station.copy(iconUrl = it)), true))
        }
    }

    @Test fun rotationKeepsAngleUntilTheStationChanges() {
        val rotation = PlayerRotationState()
        rotation.select("one")
        rotation.updateAngle(90f)
        assertEquals(90f, rotation.angle, .01f)
        rotation.select("one")
        assertEquals(90f, rotation.angle, .01f)
        rotation.updateAngle(360f)
        assertEquals(0f, rotation.angle, .01f)
        rotation.updateAngle(18f)
        val restored = PlayerRotationState(rotation.stationKey, rotation.angle)
        assertEquals(18f, restored.angle, .01f)
        restored.select("two")
        assertEquals(0f, restored.angle, 0f)
        restored.updateAngle(18f)
        restored.select("")
        assertEquals(0f, restored.angle, 0f)
    }
}
