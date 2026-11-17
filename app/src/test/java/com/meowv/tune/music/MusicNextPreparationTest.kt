package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicNextPreparationTest {
    private val one = MusicTrack("one", "One", listOf("test"))
    private val two = one.copy(id = "two")
    private fun audio(id: String, expires: Instant? = null) = PlayableAudio(id, "test", id, 1.0,
        "https://example.org/audio.mp3", "audio/mpeg", 12000, expires, true)
    private class Repository(val loadAudio: suspend (String) -> PlayableAudio) : MusicRepository {
        override suspend fun resolve(trackId: String, preferredProviderId: String?) = loadAudio(trackId)
        override suspend fun search(query: String, limit: Int, cursor: String?) = error("No speculative search")
        override suspend fun recommendations(limit: Int, cursor: String?) = error("Queue owns candidates")
    }

    @Test fun onlyOnePreparationRunsAndForegroundCanPromoteTheSameInFlightCall() = runBlocking {
        var requests = 0
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prep = MusicNextPreparation({ Repository { id -> requests++; gate.await(); audio(id) } }, scope, {})
            prep.prepare(one); prep.prepare(one)
            assertEquals(1, requests)
            val pending = requireNotNull(prep.take(one.id))
            prep.cancel()
            gate.complete(Unit)
            assertEquals(one.id, pending.await().getOrThrow().trackId)
            assertNull(prep.take(one.id))
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun cancelledLateResultsAndFailuresCannotReplaceTheNewTarget() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var failures = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prep = MusicNextPreparation({ Repository { id ->
                if (id == one.id) {
                    withContext(NonCancellable) { gate.await() }
                    throw MusicException(MusicFailure.RATE_LIMITED, 60)
                }
                audio(id)
            } }, scope, { failures++ })
            prep.prepare(one); prep.prepare(two)
            gate.complete(Unit)
            assertEquals(two.id, prep.take(two.id)!!.await().getOrThrow().trackId)
            assertEquals(0, failures)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun expiredAndNearlyExpiredUrlsAreNotFreshAndOldCooldownDoesNotRestart() = runBlocking {
        var now = Instant.parse("2026-11-17T00:00:00Z")
        var failures = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prep = MusicNextPreparation({ Repository { throw MusicException(MusicFailure.SOURCE_UNAVAILABLE, 1) } },
                scope, { failures++ }, { now })
            assertFalse(prep.fresh(audio(one.id, now)))
            assertFalse(prep.fresh(audio(one.id, now.plusSeconds(5))))
            assertTrue(prep.fresh(audio(one.id, now.plusSeconds(6))))
            prep.prepare(one); prep.prepare(one)
            assertEquals(1, failures)
            now = now.plusSeconds(1)
            assertNull(prep.take(one.id))
        } finally { scope.cancel() }
    }

    @Test fun permanentFailureIsPromotedWithoutASecondRequestAndMismatchIsRejected() = runBlocking {
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prep = MusicNextPreparation({ Repository { calls++; throw MusicException(MusicFailure.NO_PLAYABLE_MATCH) } }, scope, {})
            prep.prepare(one)
            assertEquals(MusicFailure.NO_PLAYABLE_MATCH, (prep.take(one.id)!!.await().exceptionOrNull() as MusicException).failure)
            assertEquals(1, calls)
            prep.prepare(two)
            assertNull(prep.take(one.id))
        } finally { scope.cancel() }
    }
}
