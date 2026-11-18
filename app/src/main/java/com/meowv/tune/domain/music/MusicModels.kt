package com.meowv.tune.domain.music

import java.time.Instant

data class MusicTrackMatch(
    val providerId: String,
    val sourceTrackId: String,
    val confidence: Double? = null,
    val edition: String? = null
)

data class MusicTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val isrc: String? = null,
    val availability: String = "candidate",
    val matches: List<MusicTrackMatch> = emptyList()
)

data class PlayableAudio(
    val trackId: String,
    val providerId: String,
    val sourceTrackId: String,
    val confidence: Double,
    val url: String,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val expiresAt: Instant? = null,
    val seekable: Boolean
)

data class MusicLyricLine(val timeMs: Long, val text: String)

data class MusicPage(
    val items: List<MusicTrack>,
    val nextCursor: String? = null,
    val partial: Boolean = false,
    val warnings: List<String> = emptyList(),
    val strategy: String? = null,
    val generatedAt: Instant? = null
)

enum class MusicFailure {
    NOT_CONFIGURED, INVALID_REQUEST, UNAUTHORIZED, FORBIDDEN, TRACK_NOT_FOUND, NO_PLAYABLE_MATCH,
    RATE_LIMITED, SOURCE_TIMEOUT, SOURCE_UNAVAILABLE, INTERNAL_ERROR,
    CONNECTION, TLS, INVALID_RESPONSE, PLAYBACK_UNAVAILABLE
}

// 仅传递安全分类，不持有原始响应、凭据或媒体地址。
class MusicException(val failure: MusicFailure, val retryAfterSeconds: Long? = null) : Exception(failure.name)

internal val MusicTrack.canStartPlayback: Boolean get() = availability in setOf("candidate", "available")
