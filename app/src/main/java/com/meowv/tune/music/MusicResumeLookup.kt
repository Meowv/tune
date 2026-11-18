package com.meowv.tune.music

import com.meowv.tune.domain.music.MusicException
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicRepository
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.canStartPlayback

internal suspend fun MusicRepository.findRememberedTrack(track: MusicTrack): MusicTrack {
    if (track.matches.isEmpty()) throw MusicException(MusicFailure.TRACK_NOT_FOUND)
    val query = listOf(track.title, track.artists.firstOrNull().orEmpty()).joinToString(" ").trim().take(100)
    var cursor: String? = null
    var unavailable = false
    repeat(3) {
        val page = search(query, limit = 20, cursor = cursor)
        page.items.firstOrNull { item ->
            item.canStartPlayback && item.matches.any { fresh ->
                track.matches.any { saved -> fresh.providerId == saved.providerId && fresh.sourceTrackId == saved.sourceTrackId }
            }
        }?.let { return it }
        unavailable = unavailable || page.warnings.any { warning ->
            val (source, reason) = warning.split(':', limit = 2)
                .takeIf { it.size == 2 } ?: return@any false
            reason != "deferred" && (source == "search" || track.matches.any { it.providerId == source })
        }
        val next = page.nextCursor
        if (next == null || next == cursor)
            throw MusicException(if (unavailable || page.partial && page.warnings.isEmpty())
                MusicFailure.SOURCE_UNAVAILABLE else MusicFailure.TRACK_NOT_FOUND)
        cursor = next
    }
    throw MusicException(if (unavailable) MusicFailure.SOURCE_UNAVAILABLE else MusicFailure.TRACK_NOT_FOUND)
}
