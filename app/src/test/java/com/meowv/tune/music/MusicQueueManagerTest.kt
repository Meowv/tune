package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicQueueManagerTest {
    private val tracks = (1..4).map { MusicTrack("$it", "Song $it", listOf("qix")) }

    @Test fun radioOnlyFillsAnExhaustedQueueAndStopsOnRepeats() = runBlocking {
        var calls = 0
        val source = object : MusicRepository by FakeMusicRepository(tracks.take(2)) {
            override suspend fun radioRecommendations(): List<MusicTrack> {
                calls++
                return listOf(tracks[0], tracks[2], tracks[3].copy(availability = "unavailable"))
            }
        }
        val queue = MusicQueueManager({ source })
        repeat(2) { index -> assertEquals(tracks[index], queue.next()?.track) }
        assertEquals(0, calls)
        assertEquals(tracks[2], queue.next()?.track)
        assertEquals(1, calls)
        assertNull(queue.next())
        assertFalse(queue.canNext)
        assertEquals(2, calls)
    }

    @Test fun shuffledQueueKeepsEarlierTracksAndThePreparedCandidate() = runBlocking {
        val seed = MusicQueueSeed(tracks, null)
        val queue = MusicQueueManager({ FakeMusicRepository(tracks) })
        queue.reset(seed, startAt = tracks.last().id)
        assertNull(queue.next())

        queue.shuffle = true
        queue.reset(seed, startAt = tracks.last().id)
        val prepared = queue.nextTrack
        assertNotNull(prepared)
        assertEquals(prepared, queue.nextTrack)
        assertEquals(prepared, queue.next()?.track)
        val rest = List(2) { queue.next()?.track }
        assertEquals(tracks.dropLast(1).toSet(), (listOf(prepared) + rest).toSet())
        assertNull(queue.next())
    }

    @Test fun onlyConfirmedPlaybackEntersHistoryAndOldSelectionsCannotCommit() = runBlocking {
        val queue = MusicQueueManager({ FakeMusicRepository(tracks) })
        val first = queue.next()!!
        val second = queue.next()!!
        assertFalse(queue.recordSuccessfulPlayback(first))
        assertTrue(queue.recordSuccessfulPlayback(second))
        assertFalse(queue.recordSuccessfulPlayback(second))
        assertFalse(queue.canPrevious)
        val third = queue.next()!!
        assertTrue(queue.recordSuccessfulPlayback(third))
        val previous = queue.previous()!!
        assertEquals(second.track, previous.track)
        assertTrue(queue.recordSuccessfulPlayback(previous))
        assertEquals(third.track, queue.next()!!.track)
    }

    @Test fun exhaustedCatalogStopsAndDuplicatePagesHaveBoundedRequests() = runBlocking {
        val queue = MusicQueueManager({ FakeMusicRepository(tracks.take(2)) })
        repeat(2) { assertTrue(queue.recordSuccessfulPlayback(queue.next()!!)) }
        assertNull(queue.next())
        assertFalse(queue.canNext)
        var requests = 0
        val repeating = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                requests++; return MusicPage(listOf(tracks[0], tracks[0]), "same")
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val deduped = MusicQueueManager({ repeating })
        assertEquals(tracks[0], deduped.next()!!.track)
        assertNull(deduped.next())
        assertEquals(2, requests)
    }

    @Test fun cancellationDoesNotPublishLatePagesAndInvalidationRejectsOldSuccess() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var first = true
        val repository = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                if (first) { first = false; started.complete(Unit); withContext(NonCancellable) { gate.await() } }
                return MusicPage(tracks)
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val queue = MusicQueueManager({ repository })
        val old = launch { queue.next(); error("Cancelled request returned") }
        started.await(); old.cancel(); gate.complete(Unit); old.join()
        val selection = queue.next()!!
        assertEquals(tracks[0], selection.track)
        queue.invalidate()
        assertFalse(queue.recordSuccessfulPlayback(selection))
    }

    @Test fun failedPreviousDoesNotMoveHistoryCursor() = runBlocking {
        val queue = MusicQueueManager({ FakeMusicRepository(tracks) })
        repeat(3) { queue.recordSuccessfulPlayback(queue.next()!!) }
        val previous = queue.previous()!!
        queue.invalidate()
        assertFalse(queue.recordSuccessfulPlayback(previous))
        assertEquals("2", queue.previous()!!.track.id)
    }

    @Test fun firstPlayableCandidateDoesNotWaitForTheSecondPageAndPeekDoesNotConsume() = runBlocking {
        var requests = 0
        val repo = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                requests++
                return if (cursor == null) MusicPage(listOf(tracks[0]), "second") else MusicPage(tracks.drop(1))
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val queue = MusicQueueManager({ repo })
        val first = queue.next()!!
        assertEquals(1, requests)
        assertTrue(queue.recordSuccessfulPlayback(first))
        assertTrue(queue.needsReplenish)
        queue.replenish()
        assertEquals(2, requests)
        assertEquals(tracks[1], queue.nextTrack)
        assertEquals(tracks[1], queue.nextTrack)
        assertEquals(tracks[1], queue.next()!!.track)
        assertFalse(queue.canPrevious)
    }

    @Test fun resetRejectsUncancelledOldPagesAndConcurrentReplenishmentSharesTheCursor() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val repo = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                requests++
                if (requests == 1) { started.complete(Unit); gate.await(); return MusicPage(listOf(tracks[0])) }
                return MusicPage(listOf(tracks[1]))
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val queue = MusicQueueManager({ repo })
        val old = launch { queue.replenish() }
        started.await()
        queue.reset()
        gate.complete(Unit); old.join()
        assertNull(queue.nextTrack)
        coroutineScope { repeat(3) { launch { queue.replenish() } } }
        assertEquals(2, requests)
        assertEquals(tracks[1], queue.nextTrack)
    }

    @Test fun recentSuccessSurvivesHistoryBranchingAndCandidateWindowTurnover() = runBlocking {
        val batches = listOf(
            MusicPage(tracks.take(3), "second"),
            MusicPage(listOf(tracks[3], tracks[0].copy(id = "five"), tracks[0].copy(id = "six")), "third"),
            MusicPage(listOf(tracks[2], tracks[0].copy(id = "seven")))
        )
        var requests = 0
        val repo = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?) = batches[requests++]
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val queue = MusicQueueManager({ repo }, batchSize = 3, historyLimit = 3)
        repeat(3) { assertTrue(queue.recordSuccessfulPlayback(queue.next()!!)) }
        assertTrue(queue.recordSuccessfulPlayback(queue.previous()!!))
        queue.select(tracks[0].copy(id = "branch")).also { assertTrue(queue.recordSuccessfulPlayback(it)) }
        queue.replenish()
        val remaining = mutableListOf<String>()
        repeat(4) { queue.next()?.let { remaining += it.track.id } }
        assertFalse("Recently played third track must not reappear after its history branch was removed", "3" in remaining)
        assertEquals(listOf("4", "five", "six", "seven"), remaining)
    }

    @Test fun twoShortSkipsPreferAnAlternativeDeterministicallyWithoutExtraRequests() = runBlocking {
        var now = 0L
        val candidates = (1..4).map { MusicTrack("a$it", "Same title", listOf("Ａrtist")) } +
            MusicTrack("other", "Same title", listOf("Other"))
        val queue = MusicQueueManager({ FakeMusicRepository(candidates) }, nowMillis = { now })
        val first = queue.next()!!; queue.recordSuccessfulPlayback(first); queue.skip(first.track)
        val second = queue.next()!!; queue.recordSuccessfulPlayback(second); now++; queue.skip(second.track)
        assertEquals("other", queue.nextTrack!!.id)
        assertEquals("other", queue.next()!!.track.id)
        now += 30001
        assertEquals("a3", queue.nextTrack!!.id)
        assertEquals("a3", queue.next()!!.track.id)
    }

    @Test fun unplayedOrDuplicateSkipDoesNotCreateArtistPreferenceAndSameArtistCanStillAdvance() = runBlocking {
        val candidates = tracks + MusicTrack("other", "Other", listOf("other"))
        val queue = MusicQueueManager({ FakeMusicRepository(candidates) })
        val first = queue.next()!!; queue.skip(first.track); queue.skip(first.track)
        assertEquals("2", queue.nextTrack!!.id)
        val second = queue.next()!!; queue.skip(second.track, affectRanking = false)
        assertEquals("3", queue.nextTrack!!.id)
        val singleArtist = MusicQueueManager({ FakeMusicRepository(tracks) })
        repeat(2) { val next = singleArtist.next()!!; singleArtist.recordSuccessfulPlayback(next); singleArtist.skip(next.track) }
        assertEquals("3", singleArtist.nextTrack!!.id)
        assertEquals("3", singleArtist.next()!!.track.id)
    }

    @Test fun cyclicAndAllDuplicatePagesStopInsteadOfRequestingForever() = runBlocking {
        var requests = 0
        val repo = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                requests++
                return MusicPage(listOf(tracks[0]), if (cursor == "a") "b" else "a")
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val queue = MusicQueueManager({ repo })
        val first = queue.next()!!; queue.recordSuccessfulPlayback(first)
        assertNull(queue.next())
        repeat(4) { queue.replenish(); assertNull(queue.next()) }
        assertFalse(queue.canNext)
        assertEquals(3, requests)
    }
}
