package com.meowv.tune.radio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meowv.tune.domain.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryCacheTest {
    @Test fun accumulatedPagesKeepAllStationsAndCursorAcrossRestart() = runBlocking {
        val stations = (1L..300L).map { testStation(it) }
        open { cache, _ ->
            val started = System.nanoTime()
            (30..300 step 30).forEach { size ->
                cache.refreshStations(manual) { StationPage(stations.take(size), size) }
            }
            assertEquals(StationPage(stations, 300), cache.stations(manual)?.value)
            println("DirectoryCache: ten cumulative writes and read took ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        open { cache, _ -> assertEquals(StationPage(stations, 300), cache.stations(manual)?.value) }
    }

    @Test fun oversizedSnapshotKeepsPreviousPageAndCursorAcrossRestart() = runBlocking {
        val previous = StationPage(listOf(testStation(1)), 30)
        open { cache, _ ->
            cache.refreshStations(manual) { previous }
            val huge = testStation(2).copy(iconUrl = "https://example.org/" + "a".repeat(256_000))
            val returned = cache.refreshStations(manual) { StationPage(listOf(huge), 60) }
            assertEquals(60, returned.nextOffset)
            assertEquals(previous, cache.stations(manual)?.value)
        }
        open { cache, _ -> assertEquals(previous, cache.stations(manual)?.value) }
    }

    @get:Rule val folder = TemporaryFolder()
    private val jp = requireNotNull(CountryCode.parse("JP"))
    private val manual = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, jp), jp)
    private val auto = ResolvedRadioScope(RadioScope(), jp)
    private var now = 0L

    @Test fun reopenKeepsOnlyDirectoryFieldsAndSeparatesEffectiveScope() = runBlocking {
        val original = testStation(1).copy(streamUrl = "http://example.org/stream",
            iconUrl = "https://example.org/icon?key=value")
        open { cache, _ ->
            cache.refreshStations(manual) { StationPage(listOf(original), 30) }
            assertNull(cache.stations(auto))
            cache.refreshStations(auto) { StationPage(listOf(testStation(2))) }
            cache.loadCountries { listOf(RadioCountry(jp, 1)) }
        }
        open { cache, _ ->
            val page = requireNotNull(cache.stations(manual))
            assertFalse(page.stale)
            assertEquals(30, page.value.nextOffset)
            assertNull(page.value.stations.single().streamUrl)
            assertEquals("https://example.org/icon", page.value.stations.single().iconUrl)
            assertEquals(testStation(2), cache.stations(auto)?.value?.stations?.single())
            now = 20 * 60 * 1000L
            assertTrue(cache.stations(manual)!!.stale)
            assertFalse(cache.loadCountries { error("Countries still fresh") }.stale)
            now = 24 * 60 * 60 * 1000L
            val stale = cache.loadCountries { throw TuneFailure(TuneError.CONNECTION) }
            assertEquals(DirectoryValue(listOf(RadioCountry(jp, 1)), true, TuneError.CONNECTION), stale)
        }
    }

    @Test fun clearAndNewerWritesPreventStaleInFlightResultsFromReappearing() = runBlocking {
        open { cache, _ ->
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val older = launch {
                cache.refreshStations(manual) {
                    started.complete(Unit)
                    release.await()
                    StationPage(listOf(testStation(1)))
                }
            }
            started.await()
            cache.clear()
            cache.refreshStations(manual) { StationPage(listOf(testStation(2))) }
            release.complete(Unit)
            older.join()
            assertEquals(listOf(testStation(2)), cache.stations(manual)?.value?.stations)
            cache.clear()
        }
        open { cache, _ -> assertNull(cache.stations(manual)) }
    }

    @Test fun unsupportedVersionAndMalformedBodyAreCacheMisses() = runBlocking {
        open { cache, store ->
            store.edit {
                it[stringPreferencesKey("stations_COUNTRY_JP")] = "2|0|{}"
                it[stringPreferencesKey("countries")] = "1|0|{"
            }
            assertNull(cache.stations(manual))
            assertEquals(listOf(RadioCountry(jp, 3)), cache.loadCountries { listOf(RadioCountry(jp, 3)) }.value)
        }
    }

    @Test fun stationEvictionKeepsTheCountryDirectoryForOfflineAutoScope() = runBlocking {
        val countries = listOf(RadioCountry(jp, 1))
        val station = testStation(1)
        open { cache, _ ->
            cache.loadCountries { countries }
            cache.refreshStations(auto) { StationPage(listOf(station)) }
            listOf("AU", "BR", "CA", "CH", "CN", "DE", "ES", "FR", "GB", "IN", "IT").forEach {
                val code = requireNotNull(CountryCode.parse(it))
                val scope = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, code), code)
                now++
                cache.refreshStations(scope) { StationPage(listOf(testStation(2, code))) }
            }
        }
        open { cache, _ ->
            assertEquals(countries, cache.loadCountries { error("Country directory was evicted") }.value)
            assertNull(cache.stations(auto))
        }
    }

    @Test fun viewModelRetainsExpiredStationsWhenRefreshFails() = runBlocking {
        open { cache, _ ->
            val page = StationPage(listOf(testStation(1)))
            cache.refreshStations(manual) { page }
            now = 20 * 60 * 1000L
            val lifetime = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val repo = StubRadioRepository(countryPage = { _, _, _ -> throw TuneFailure(TuneError.SERVER) })
                val model = RadioViewModel(repo, idlePlayer, CountryResolver({ null }, { null }), lifetime, cache)
                model.refresh(manual.scope, force = false)
                val state = model.radioState.first { !it.isLoading }
                assertEquals(page.stations, state.stations)
                assertEquals(DirectoryCacheState.STALE, state.cacheState)
                assertEquals(TuneError.SERVER, state.error)
            } finally {
                lifetime.cancel()
            }
        }
    }

    private suspend fun open(
        block: suspend (DirectoryCache, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit
    ) {
        val lifetime = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = lifetime, produceFile = { File(folder.root, "cache.preferences_pb") })
            withTimeout(5000) { block(DirectoryCache(store) { now }, store) }
        } finally {
            lifetime.coroutineContext.job.cancelAndJoin()
        }
    }
}
