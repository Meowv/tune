package com.meowv.tune.radio

import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.CountryDirectory
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.network.RadioBrowserClient
import java.util.UUID

internal class RadioBrowserRepository(
    countries: CountryDirectory,
    private val get: suspend (String, Map<String, String>) -> String
) : RadioRepository, CountryDirectory by countries {
    constructor(countries: CountryDirectory, client: RadioBrowserClient) : this(countries, client::get)

    override suspend fun stationsForCountry(country: CountryCode, offset: Int, limit: Int): StationPage {
        require(offset >= 0 && limit in 1..30 && offset <= Int.MAX_VALUE - limit)
        val rows = search(mapOf(
            "countrycode" to country.value, "hidebroken" to "true", "order" to "clickcount",
            "reverse" to "true", "limit" to limit.toString(), "offset" to offset.toString()
        ))
        return StationPage(
            rows.mapNotNull(RadioBrowserMapping::station).filter { it.countryCode == country }.distinctBy { it.uuid },
            if (rows.size >= limit) offset + limit else null
        )
    }

    override suspend fun randomGlobalStations(limit: Int): List<Station> {
        require(limit in 1..30)
        return search(mapOf("hidebroken" to "true", "order" to "random", "limit" to limit.toString()))
            .mapNotNull(RadioBrowserMapping::station).distinctBy { it.uuid }
    }

    override suspend fun globalStations(offset: Int, limit: Int): StationPage {
        require(offset >= 0 && limit in 1..30 && offset <= Int.MAX_VALUE - limit)
        val rows = search(mapOf("hidebroken" to "true", "order" to "clickcount", "reverse" to "true",
            "limit" to limit.toString(), "offset" to offset.toString()))
        return StationPage(rows.mapNotNull(RadioBrowserMapping::station).distinctBy { it.uuid },
            if (rows.size >= limit) offset + limit else null)
    }

    override suspend fun stationByUuid(uuid: UUID): Station? =
        RadioBrowserMapping.rows(get("/json/stations/byuuid/$uuid", emptyMap()))
            .firstNotNullOfOrNull(RadioBrowserMapping::station)
            ?.takeIf { it.uuid == uuid }

    private suspend fun search(query: Map<String, String>) =
        RadioBrowserMapping.rows(get("/json/stations/search", query))
}
