package com.meowv.tune.radio

import com.meowv.tune.domain.TuneFailure
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RadioBrowserMappingTest {
    @Test fun countryDirectoryFiltersDeduplicatesAndLocalizes() = runBlocking {
        val directory = RadioBrowserCountries { endpoint, query ->
            assertEquals("/json/countrycodes", endpoint)
            assertEquals(mapOf("hidebroken" to "true"), query)
            """[{"name":"jp","stationcount":"7"},{"name":"JP","stationcount":9},{"name":"ZZ","stationcount":8},
                {"name":"US","stationcount":0},{"name":"CN","stationcount":null},null]"""
        }
        val japan = directory.countries().single()
        assertEquals("JP", japan.code.value)
        assertEquals(9, japan.stationCount)
        assertEquals("日本", japan.displayName(Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun stationMappingKeepsPlaybackFieldsAndHandlesBadRows() {
        val id = UUID.randomUUID()
        val rows = RadioBrowserMapping.rows(
            """[{"stationuuid":"$id","name":"a\u000ab\u202ec","countrycode":"jp","favicon":"https://example.org/icon",
              "url":"http://example.org/list","url_resolved":"https://example.org/live","hls":"1","codec":"AAC","bitrate":"128"},
              {"stationuuid":"1-1-1-1-1"}, {"stationuuid":"$id","name":5,"countrycode":"ZZ","favicon":"http://example.org"}]"""
        )
        val station = RadioBrowserMapping.station(rows[0])!!
        assertEquals(id, station.uuid)
        assertEquals("a b c", station.name)
        assertEquals("JP", station.countryCode?.value)
        assertEquals("http://example.org/list", station.streamUrl)
        assertEquals("https://example.org/live", station.resolvedStreamUrl)
        assertEquals("https://example.org/icon", station.iconUrl)
        assertTrue(station.isHls)
        assertEquals(128, station.bitrate)
        assertEquals("AAC", station.codec)
        assertNull(RadioBrowserMapping.station(rows[1]))
        val incomplete = RadioBrowserMapping.station(rows[2])!!
        assertEquals("", incomplete.name)
        assertNull(incomplete.countryCode)
        assertNull(incomplete.iconUrl)
        assertThrows(TuneFailure::class.java) { RadioBrowserMapping.rows("{}") }
    }
}
