package com.meowv.tune.ui

import com.meowv.tune.domain.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PlayerTargetTest {
    private val one = Station(UUID(0, 1), "One", CountryCode.parse("CN"))
    private val two = Station(UUID(0, 2), "Two", CountryCode.parse("CN"))
    @Test fun selectsRememberedThenFirstWithoutStartingPlayback() {
        val radio = RadioUiState(stations = listOf(one, two))
        assertEquals(two, resolvePlayerTarget(PlayerUiState(), radio, two, two.uuid))
        assertEquals(one, resolvePlayerTarget(PlayerUiState(), radio, null, null))
        assertEquals(one, resolvePlayerTarget(PlayerUiState(), radio.copy(selectedStationUuid = one.uuid), two, two.uuid))
        assertEquals(two, resolvePlayerTarget(PlayerUiState(PlaybackPhase.PAUSED, two), radio, null, null))
    }
    @Test fun restoresMetadataOutsideFirstPageButRespectsCountryScope() {
        val radio = RadioUiState(stations = listOf(one), resolvedCountryCode = CountryCode.parse("CN"))
        assertEquals(two, resolvePlayerTarget(PlayerUiState(), radio, two, two.uuid))
        assertEquals(one, resolvePlayerTarget(PlayerUiState(), radio, two.copy(countryCode = CountryCode.parse("JP")), two.uuid))
        assertNull(resolvePlayerTarget(PlayerUiState(), RadioUiState(), null, null))
    }
}
