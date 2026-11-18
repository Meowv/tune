package com.meowv.tune.music

import com.meowv.tune.domain.music.MusicException
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicPage
import com.meowv.tune.domain.music.MusicRepository
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.MusicTrackMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicResumePointTest {
    private val track = MusicTrack("previous", "Song", listOf("Artist"), durationMs = 200_000,
        matches = listOf(MusicTrackMatch("qqmusic", "same-recording")))

    @Test fun roundTripKeepsTrackAndPositionAndIgnoresInvalidNumbers() {
        val point = MusicResumePoint(track, 60_000, true)
        val json = MusicJson.encodeResumePoint(point)
        assertEquals(point, MusicJson.decodeResumePoint(json))
        assertEquals(point.copy(positionMs = 0), MusicJson.decodeResumePoint(json.replace("60000", "-9")))
        assertEquals(0L, point.startPosition(seekable = true, durationMs = 60_000))
        assertEquals(0L, point.startPosition(seekable = false, durationMs = 200_000))
        assertEquals(60_000L, point.startPosition(seekable = true, durationMs = 200_000))
        assertNull(MusicJson.decodeResumePoint("{broken"))
    }

    @Test fun expiredIndexRehydratesOnlyTheIdenticalRecording() = runBlocking {
        val wrong = track.copy(id = "wrong", matches = listOf(MusicTrackMatch("qqmusic", "live-version")))
        val renewed = track.copy(id = "renewed")
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun search(query: String, limit: Int, cursor: String?) =
                if (cursor == null) MusicPage(listOf(wrong), nextCursor = "next") else MusicPage(listOf(renewed))
        }
        assertEquals(renewed, source.findRememberedTrack(track))
        val missing = FakeMusicRepository(listOf(wrong))
        val error = runCatching { missing.findRememberedTrack(track) }.exceptionOrNull() as MusicException
        assertEquals(MusicFailure.TRACK_NOT_FOUND, error.failure)
    }
}
