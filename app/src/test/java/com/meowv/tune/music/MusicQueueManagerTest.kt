package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicQueueManagerTest {
    private val tracks = (1..4).map { MusicTrack("$it", "Song $it", listOf("qix")) }

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
}
