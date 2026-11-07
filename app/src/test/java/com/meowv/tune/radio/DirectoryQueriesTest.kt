package com.meowv.tune.radio

import com.meowv.tune.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

private val JP = requireNotNull(CountryCode.parse("JP"))
private val US = requireNotNull(CountryCode.parse("US"))
internal fun testStation(number: Long, country: CountryCode? = JP) =
    Station(UUID(0, number), "Station $number", country)

internal class StubRadioRepository(
    private val countryList: suspend () -> List<RadioCountry> = { emptyList() },
    private val countryPage: suspend (CountryCode, Int, Int) -> StationPage = { _, _, _ -> error("Unexpected country call") },
    private val random: suspend () -> List<Station> = { error("Unexpected random call") },
    private val byUuid: suspend (UUID) -> Station? = { null }
) : RadioRepository {
    override suspend fun countries() = countryList()
    override suspend fun stationsForCountry(country: CountryCode, offset: Int, limit: Int) =
        countryPage(country, offset, limit)
    override suspend fun randomGlobalStations(limit: Int) = random()
    override suspend fun stationByUuid(uuid: UUID) = byUuid(uuid)
}

internal val idlePlayer = object : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    override suspend fun dispatch(command: PlayerCommand) = error("Unexpected playback command")
}

class DirectoryQueriesTest {
    @Test fun stationByUuidUsesSingleStationEndpoint() = runBlocking {
        val station = testStation(7)
        val repository = RadioBrowserRepository({ emptyList() }) { endpoint, query ->
            assertTrue(endpoint.startsWith("/json/stations/byuuid/"))
            assertTrue(query.isEmpty())
            if (!endpoint.endsWith(station.uuid.toString())) return@RadioBrowserRepository "[]"
            val row = mapOf("stationuuid" to station.uuid.toString(), "countrycode" to "JP", "name" to station.name)
            row.entries.joinToString(",", "[{", "}]") { "\"${it.key}\":\"${it.value}\"" }
        }
        assertEquals(station, repository.stationByUuid(station.uuid))
        assertNull(repository.stationByUuid(testStation(8).uuid))
    }

    @Test fun countryQueryFiltersRowsWithoutChangingRawOffset() = runBlocking {
        val jp = testStation(1)
        val us = testStation(2, US)
        val repository = RadioBrowserRepository({ emptyList() }) { endpoint, query ->
            assertEquals("/json/stations/search", endpoint)
            assertEquals(mapOf("countrycode" to "JP", "hidebroken" to "true", "order" to "clickcount",
                "reverse" to "true", "limit" to "4", "offset" to "30"), query)
            """[{"stationuuid":"${jp.uuid}","countrycode":"JP","name":"${jp.name}"},
                {"stationuuid":"${jp.uuid}","countrycode":"JP"},{"stationuuid":"${us.uuid}","countrycode":"US"},null]"""
        }
        assertEquals(StationPage(listOf(jp), 34), repository.stationsForCountry(JP, 30, 4))
    }

    @Test fun randomQueryHasNoOffsetOrCountryFilter() = runBlocking {
        val a = testStation(1)
        val b = testStation(2, US)
        val repository = RadioBrowserRepository({ emptyList() }) { _, query ->
            assertEquals(mapOf("hidebroken" to "true", "order" to "random", "limit" to "30"), query)
            """[{"stationuuid":"${a.uuid}","name":"${a.name}","countrycode":"JP"},
                {"stationuuid":"invalid"},{"stationuuid":"${a.uuid}","countrycode":"US"},
                {"stationuuid":"${b.uuid}","name":"${b.name}","countrycode":"US"}]"""
        }
        assertEquals(listOf(a, b), repository.randomGlobalStations(30))
    }

    @Test fun paginationSkipsDuplicateOnlyPagesAndStopsAtBound() = runBlocking {
        val known = testStation(1)
        val next = testStation(2)
        val offsets = mutableListOf<Int>()
        val repository = StubRadioRepository(countryPage = { _, offset, limit ->
            offsets += offset
            assertEquals(30, limit)
            if (offset == 60) StationPage(listOf(known, next, testStation(3, US)))
            else StationPage(listOf(known), offset + 30)
        })
        assertEquals(StationPage(listOf(known, next)), appendCountryPage(repository, JP, StationPage(listOf(known), 30)))
        assertEquals(listOf(30, 60), offsets)
        val repeated = StubRadioRepository(countryPage = { _, offset, _ ->
            offsets += offset
            StationPage(listOf(known), offset + 30)
        })
        assertEquals(StationPage(listOf(known)), appendCountryPage(repeated, JP, StationPage(listOf(known), 30)))
        assertEquals(listOf(30, 60, 30, 60, 90), offsets)
    }

    @Test fun viewModelSerializesPaginationAndRefreshResetsOffset() = runBlocking {
        withTimeout(5000) {
            val offsets = mutableListOf<Int>()
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val repository = StubRadioRepository(countryPage = { _, offset, _ ->
                offsets += offset
                if (offset == 0) StationPage(listOf(testStation(1)), 30)
                else {
                    started.complete(Unit)
                    release.await()
                    StationPage(listOf(testStation(2)))
                }
            })
            val lifetime = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val model = RadioViewModel(repository, idlePlayer, CountryResolver({ null }, { null }), lifetime)
                val scope = RadioScope(ScopeMode.COUNTRY, JP)
                model.refresh(scope)
                model.radioState.first { !it.isLoading }
                model.loadMore()
                started.await()
                model.loadMore()
                release.complete(Unit)
                assertEquals(2, model.radioState.first { !it.isLoading }.stations.size)
                model.refresh(scope)
                assertEquals(1, model.radioState.first { !it.isLoading }.stations.size)
                assertEquals(listOf(0, 30, 0), offsets)
            } finally {
                lifetime.cancel()
            }
        }
    }

    @Test fun manualEmptyStaysLocalWhileAutoCanFallBack() = runBlocking {
        withTimeout(5000) {
            val repository = StubRadioRepository(
                countryList = { listOf(RadioCountry(JP, 1), RadioCountry(US, 1)) },
                countryPage = { country, _, _ ->
                    if (country == JP) StationPage() else StationPage(listOf(testStation(2, US)))
                }
            )
            val lifetime = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val model = RadioViewModel(repository, idlePlayer, CountryResolver({ "JP" }, { "US" }), lifetime)
                model.refresh(RadioScope(ScopeMode.COUNTRY, JP))
                val empty = model.radioState.first { !it.isLoading }
                assertEquals(JP, empty.resolvedCountryCode)
                assertTrue(empty.stations.isEmpty())
                model.refresh(RadioScope())
                val fallback = model.radioState.first { !it.isLoading }
                assertEquals(US, fallback.resolvedCountryCode)
                assertEquals(US, fallback.stations.single().countryCode)
            } finally {
                lifetime.cancel()
            }
        }
    }

    @Test fun randomPoolBoundsEmptyAndDuplicateBatchesAndIsCancellable() = runBlocking {
        var count = 0
        val known = testStation(1)
        val pool = RandomStationPool { count++; if (count == 1) emptyList() else listOf(known) }
        assertNull(pool.take(setOf(known.uuid)))
        assertEquals(3, count)
        val start = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val waiting = RandomStationPool {
            start.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val request = launch { waiting.take(emptySet()) }
        start.await()
        request.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }

    @Test fun returningToTheFirstCountryRejectsAnOlderResponseForThatSameCountry() = runBlocking {
        withTimeout(5000) {
            val oldRequestStarted = CompletableDeferred<Unit>()
            val oldResponse = CompletableDeferred<Unit>()
            var japanQueries = 0
            val old = testStation(1)
            val latest = testStation(2)
            val repository = StubRadioRepository(countryPage = { country, _, _ ->
                when {
                    country == US -> StationPage(listOf(testStation(3, US)))
                    ++japanQueries == 1 -> withContext(NonCancellable) {
                        oldRequestStarted.complete(Unit)
                        oldResponse.await()
                        StationPage(listOf(old))
                    }
                    else -> StationPage(listOf(latest))
                }
            })
            val lifetime = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val model = RadioViewModel(repository, idlePlayer, CountryResolver({ null }, { null }), lifetime)
                val japan = RadioScope(ScopeMode.COUNTRY, JP)
                model.setScope(japan)
                oldRequestStarted.await()
                val original = lifetime.coroutineContext.job.children.single()
                model.setScope(RadioScope(ScopeMode.COUNTRY, US))
                model.radioState.first { it.resolvedCountryCode == US }
                model.setScope(japan)
                model.radioState.first { latest in it.stations }
                oldResponse.complete(Unit)
                original.join()
                model.setScope(japan)
                assertEquals(listOf(latest), model.radioState.value.stations)
                assertEquals(2, japanQueries)
            } finally {
                oldResponse.complete(Unit)
                lifetime.cancel()
            }
        }
    }
}
