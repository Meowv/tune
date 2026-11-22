package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal object MusicJson {
    fun page(body: String, recommendation: Boolean = false): MusicPage = parse {
        val root = Json.parseToJsonElement(body).jsonObject
        MusicPage(
            items = root.array("items").map { track(it.jsonObject) },
            nextCursor = root.optionalText("nextCursor"),
            partial = if (recommendation && "partial" !in root) false else root.boolean("partial"),
            warnings = if (recommendation && root["warnings"] in listOf(null, JsonNull)) emptyList() else root.array("warnings").map { it.text() },
            strategy = if (recommendation) root.requiredText("strategy") else null,
            generatedAt = if (recommendation) Instant.parse(root.requiredText("generatedAt")) else null,
            discoveryTrackIds = (root["discoveryTrackIds"] as? JsonArray)?.map { it.text() }?.toSet().orEmpty()
        )
    }

    fun resolved(body: String): PlayableAudio = parse {
        val root = Json.parseToJsonElement(body).jsonObject
        val audio = root.getValue("audio").jsonObject
        val url = audio.requiredText("url")
        require(MusicAudioUrlPolicy.allows(url))
        val confidence = (root["confidence"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: error("confidence")
        require(confidence.isFinite() && confidence in 0.0..1.0)
        PlayableAudio(root.requiredText("trackId"), root.requiredText("providerId"), root.requiredText("sourceTrackId"),
            confidence, url, audio.optionalText("mimeType"), audio.optionalLong("durationMs"),
            audio.optionalText("expiresAt")?.let(Instant::parse), audio.boolean("seekable"))
    }

    fun lyrics(body: String, trackId: String): List<MusicLyricLine> = parse {
        val root = Json.parseToJsonElement(body).jsonObject
        require(root.requiredText("trackId") == trackId)
        root.optionalText("lrc")?.takeIf { it.length <= 48 * 1024 }?.let(MusicLyrics::parse).orEmpty()
    }

    fun encodeResumePoint(point: MusicResumePoint): String = buildJsonObject {
        put("positionMs", point.positionMs)
        put("playOnEntry", point.playOnEntry)
        put("track", storedTrack(point.track))
    }.toString()

    fun encodeFeedSnapshot(snapshot: MusicFeedSnapshot): String = buildJsonObject {
        put("version", snapshot.version)
        put("complete", snapshot.complete)
        put("rolling", snapshot.rolling)
        putJsonArray("discoveryTrackIds") { snapshot.discoveryTrackIds.forEach { add(it) } }
        putJsonArray("recommendations") { snapshot.recommendations.forEach { add(storedTrack(it)) } }
        putJsonArray("fallback") { snapshot.fallback.forEach { add(storedTrack(it)) } }
    }.toString()

    fun decodeFeedSnapshot(body: String): MusicFeedSnapshot? = try {
        parse {
            val root = Json.parseToJsonElement(body).jsonObject
            MusicFeedSnapshot(
                version = root.optionalLong("version") ?: error("version"),
                complete = root.boolean("complete"),
                recommendations = root.array("recommendations").map { track(it.jsonObject) },
                fallback = root.array("fallback").map { track(it.jsonObject) },
                rolling = (root["rolling"] as? JsonPrimitive)?.booleanOrNull == true,
                discoveryTrackIds = (root["discoveryTrackIds"] as? JsonArray)?.map { it.text() }?.toSet().orEmpty()
            )
        }
    } catch (_: MusicException) {
        null
    }

    private fun storedTrack(track: MusicTrack): JsonObject = buildJsonObject {
        put("id", track.id)
        put("title", track.title)
        put("album", track.album)
        put("durationMs", track.durationMs)
        put("coverUrl", track.coverUrl?.substringBefore('?')?.substringBefore('#'))
        put("isrc", track.isrc)
        put("availability", track.availability)
        putJsonArray("artists") { track.artists.forEach { add(it) } }
        putJsonArray("sources") {
            track.matches.forEach { match ->
                add(buildJsonObject {
                    put("providerId", match.providerId)
                    put("sourceTrackId", match.sourceTrackId)
                    put("confidence", match.confidence)
                    put("edition", match.edition)
                })
            }
        }
    }

    fun decodeResumePoint(body: String): MusicResumePoint? = try {
        parse {
            require(body.length <= 32 * 1024)
            val root = Json.parseToJsonElement(body).jsonObject
            val track = track(root.getValue("track").jsonObject)
            require(track.id.length in 1..128 && track.id.none(Char::isISOControl) && track.canStartPlayback)
            val position = (root["positionMs"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.longOrNull?.takeIf { it in 0..86_400_000 } ?: 0
            MusicResumePoint(track, position, root.boolean("playOnEntry"))
        }
    } catch (_: MusicException) {
        null
    }

    private fun track(root: JsonObject) = MusicTrack(
        root.requiredText("id"), root.requiredText("title"), root.array("artists").map { it.text().also { text -> require(text.isNotBlank()) } },
        root.optionalText("album"), root.optionalLong("durationMs"), root.optionalText("coverUrl")?.takeIf(MusicAudioUrlPolicy::allows), root.optionalText("isrc"),
        root.requiredText("availability"), root.array("sources").map {
            val source = it.jsonObject
            MusicTrackMatch(source.requiredText("providerId"), source.requiredText("sourceTrackId"),
                source.optionalConfidence(), source.optionalText("edition"))
        }
    )

    fun error(body: String, status: Int, retryHeader: Long?): MusicException {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val code = runCatching { root?.requiredText("code") }.getOrNull()
        val failure = when (status) {
            400 -> MusicFailure.INVALID_REQUEST
            401 -> MusicFailure.UNAUTHORIZED
            403 -> MusicFailure.FORBIDDEN
            404 -> MusicFailure.TRACK_NOT_FOUND
            422 -> MusicFailure.NO_PLAYABLE_MATCH
            429 -> MusicFailure.RATE_LIMITED
            503 -> MusicFailure.SOURCE_UNAVAILABLE
            504 -> MusicFailure.SOURCE_TIMEOUT
            else -> if (code == "source_unavailable") MusicFailure.SOURCE_UNAVAILABLE else MusicFailure.INTERNAL_ERROR
        }
        val retry = retryHeader ?: runCatching { root?.optionalLong("retryAfterSeconds") }.getOrNull()
        return MusicException(failure, retry?.coerceAtLeast(1))
    }

    private fun JsonObject.optionalConfidence(): Double? {
        val element = get("confidence") ?: return null
        if (element == JsonNull) return null
        val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: error("confidence")
        require(value.isFinite() && value in 0.0..1.0)
        return value
    }

    private fun JsonObject.array(key: String) = getValue(key) as? JsonArray ?: error(key)
    private fun JsonElement.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("string")
    private fun JsonObject.requiredText(key: String) = getValue(key).text().also { require(it.isNotBlank()) }
    private fun JsonObject.optionalText(key: String): String? = when (val element = get(key)) {
        null, JsonNull -> null
        else -> element.text()
    }
    private fun JsonObject.optionalLong(key: String): Long? = when (val element = get(key)) {
        null, JsonNull -> null
        else -> (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.also { require(it >= 0) } ?: error(key)
    }
    private fun JsonObject.boolean(key: String) = (getValue(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error(key)
    private inline fun <T> parse(block: () -> T): T = try { block() } catch (_: SerializationException) {
        throw MusicException(MusicFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        throw MusicException(MusicFailure.INVALID_RESPONSE)
    } catch (_: IllegalStateException) {
        throw MusicException(MusicFailure.INVALID_RESPONSE)
    } catch (_: NoSuchElementException) {
        throw MusicException(MusicFailure.INVALID_RESPONSE)
    } catch (_: java.time.format.DateTimeParseException) {
        throw MusicException(MusicFailure.INVALID_RESPONSE)
    }
}
