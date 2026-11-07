package com.meowv.tune.domain

import com.meowv.tune.radio.CountryResolver
import com.meowv.tune.radio.RadioQueueManager
import com.meowv.tune.radio.RadioViewModel
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RadioContractsTest {
    @Test fun countryCodesAreNormalizedAndValidated() {
        assertEquals("US", CountryCode.parse(" us ")?.value)
        assertNull(CountryCode.parse("ZZ"))
        assertNull(CountryCode.parse("CHN"))
        assertThrows(IllegalArgumentException::class.java) { RadioScope(ScopeMode.COUNTRY) }
        assertThrows(IllegalArgumentException::class.java) {
            RadioScope(ScopeMode.GLOBAL, CountryCode.parse("JP"))
        }
    }

    @Test fun directoryFailureDoesNotReplacePlaybackAndCancelledResultCannotWin() = runBlocking {
        withTimeout(5000) {
            val started = CompletableDeferred<Unit>()
            val stale = CompletableDeferred<List<Station>>()
            val station = Station(UUID.randomUUID(), "Test", CountryCode.parse("JP"))
            val player = object : PlayerController {
                override val state = MutableStateFlow(PlayerUiState(PlaybackPhase.PLAYING, station))
                override suspend fun dispatch(command: PlayerCommand) = error("Unexpected player command")
            }
            val repository = object : RadioRepository {
                override suspend fun countries() = emptyList<RadioCountry>()
                override suspend fun stationsForCountry(country: CountryCode, offset: Int, limit: Int): StationPage {
                    started.complete(Unit)
                    return StationPage(withContext(NonCancellable) { stale.await() })
                }
                override suspend fun randomGlobalStations(limit: Int): List<Station> =
                    throw TuneFailure(TuneError.SERVER, 503)
            }
            val lifetime = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val queue = RadioQueueManager(repository)
                val model = RadioViewModel(repository, player, CountryResolver({ null }, { null }), lifetime, queue = queue)
                val local = RadioScope(ScopeMode.COUNTRY, station.countryCode)
                model.refresh(local)
                started.await()
                val localScope = ResolvedRadioScope(local, station.countryCode)
                val previous = station.copy(uuid = UUID.randomUUID())
                queue.updateDirectory(localScope, StationPage(listOf(previous, station)))
                assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(localScope, previous.uuid))))
                assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(localScope, station.uuid))))
                assertTrue(queue.canPrevious.value)
                val cancelledRequest = lifetime.coroutineContext[kotlinx.coroutines.Job]!!.children.single()
                val global = RadioScope(ScopeMode.GLOBAL)
                model.refresh(global)
                assertFalse(queue.canPrevious.value)
                val failed = model.radioState.first { it.error == TuneError.SERVER }
                assertEquals(global, failed.scope)
                stale.complete(listOf(station))
                cancelledRequest.join()
                assertEquals(failed, model.radioState.value)
                assertEquals(PlayerUiState(PlaybackPhase.PLAYING, station), model.playerState.value)
            } finally {
                stale.complete(emptyList())
                lifetime.cancel()
            }
        }
    }
}
