package com.meowv.tune.playback

import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.network.RadioBrowserClient
import com.meowv.tune.radio.RadioBrowserMapping
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl

internal class ResolvedRadioStream(val url: HttpUrl, val isHls: Boolean) {
    override fun toString() = "ResolvedRadioStream(isHls=$isHls)"
}

internal class PlaybackIntent(val station: Station) {
    private val mutex = Mutex()
    private var clickAttempted = false
    private var clickUrl: String? = null

    suspend fun clickedUrl(click: suspend (UUID) -> String): String? = mutex.withLock {
        if (!clickAttempted) {
            clickAttempted = true
            clickUrl = withTimeoutOrNull(3500) {
                try {
                    parseClick(click(station.uuid), station.uuid)
                } catch (_: TuneFailure) {
                    null
                }
            }
        }
        clickUrl
    }

    private fun parseClick(body: String, uuid: UUID): String? = try {
        val json = Json.parseToJsonElement(body) as? JsonObject
        fun field(name: String) = (json?.get(name) as? JsonPrimitive)?.contentOrNull
        val id = field("stationuuid")
        if (field("ok") != "true" || id != null && !uuid.toString().equals(id, ignoreCase = true)) null
        else field("url")
    } catch (_: SerializationException) {
        null
    }
}

internal class RadioStreamResolver(
    private val click: suspend (UUID) -> String,
    private val details: suspend (UUID) -> Station?,
    private val probe: suspend (HttpUrl) -> MediaProbeResult
) {
    constructor(client: RadioBrowserClient, media: MediaProbe) : this(
        client::recordPlaybackClick,
        { uuid ->
            RadioBrowserMapping.rows(client.get("/json/stations/byuuid/$uuid"))
                .firstNotNullOfOrNull { RadioBrowserMapping.station(it)?.takeIf { station -> station.uuid == uuid } }
        },
        media::get
    )

    suspend fun resolve(intent: PlaybackIntent): ResolvedRadioStream =
        withTimeoutOrNull(12000) { resolveCandidates(intent) } ?: throw TuneFailure(TuneError.TIMEOUT)

    private suspend fun resolveCandidates(intent: PlaybackIntent): ResolvedRadioStream {
        val station = intent.station
        val lookup = Lookup()
        val first = listOf(intent.clickedUrl(click), station.resolvedStreamUrl, station.streamUrl)
        for (candidate in first) {
            lookup.tryUrl(MediaUrlPolicy.parse(candidate))?.let { return it }
        }
        val extra = try {
            details(station.uuid)
        } catch (failure: TuneFailure) {
            throw lookup.failure ?: failure
        }
        for (candidate in listOf(extra?.resolvedStreamUrl, extra?.streamUrl)) {
            lookup.tryUrl(MediaUrlPolicy.parse(candidate))?.let { return it }
        }
        throw lookup.failure ?: TuneFailure(TuneError.INVALID_RESPONSE)
    }

    private inner class Lookup {
        private val seen = mutableSetOf<HttpUrl>()
        var failure: TuneFailure? = null
            private set

        suspend fun tryUrl(url: HttpUrl?, depth: Int = 0, redirects: Int = 0): ResolvedRadioStream? {
            if (url == null || depth > 3 || redirects > 4 || seen.size >= 16 || !seen.add(url)) return null
            return when (val result = try {
                probe(url)
            } catch (error: TuneFailure) {
                failure = error
                return null
            }) {
                MediaProbeResult.Direct -> ResolvedRadioStream(url, isHls = false)
                is MediaProbeResult.Redirect -> tryUrl(MediaUrlPolicy.parse(result.location, url), depth, redirects + 1)
                is MediaProbeResult.Playlist -> {
                    if (result.format == PlaylistFormat.M3U && result.text.lineSequence().any { it.trimStart().startsWith("#EXT-X-") }) {
                        return ResolvedRadioStream(url, isHls = true)
                    }
                    for (entry in entries(result).take(8)) {
                        tryUrl(MediaUrlPolicy.parse(entry, url), depth + 1, redirects)?.let { return it }
                    }
                    null
                }
            }
        }
    }

    private fun entries(playlist: MediaProbeResult.Playlist): Sequence<String> = when (playlist.format) {
        PlaylistFormat.M3U -> playlist.text.removePrefix("\uFEFF").lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
        PlaylistFormat.PLS -> playlist.text.lineSequence().mapNotNull { PLS_FILE.matchEntire(it.trim())?.groupValues?.get(1) }
        PlaylistFormat.ASX -> ASX_REF.findAll(playlist.text).map { it.groupValues[1].replace("&amp;", "&") }
    }

    private companion object {
        val PLS_FILE = Regex("File\\d+\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
        val ASX_REF = Regex("""<ref\b[^>]*?\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    }
}
