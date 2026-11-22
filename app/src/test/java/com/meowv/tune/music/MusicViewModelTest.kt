package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicViewModelTest {
    @Test fun restoresAndObservesTheFeedWhileRecommendationRefreshFails() = runBlocking {
        val original = MusicTrack("a", "A", listOf("Singer"))
        val fallback = MusicTrack("b", "B", listOf("Singer"))
        val feed = MusicFeedStore()
        val version = feed.updateRecommendations(MusicPage(listOf(original))).version
        val release = CompletableDeferred<Unit>()
        val repo = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                release.await()
                throw MusicException(MusicFailure.CONNECTION)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicViewModel(repo, scope = scope, feedStore = feed)
            model.enter()
            assertEquals(listOf(original), model.browse.value.tracks)
            feed.appendFallback(version, listOf(fallback))
            release.complete(Unit)
            assertEquals(MusicFailure.CONNECTION, model.browse.value.failure)
            assertEquals(listOf(original, fallback), model.browse.value.tracks)
        } finally { scope.cancel() }
    }

    @Test fun browseUsesOneRequestAndKeepsAllPlayableCandidates() = runBlocking {
        var requests = 0
        val repo = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                requests++
                assertNull(cursor)
                val tracks = (1..75).map { MusicTrack(it.toString(), "Track $it", listOf("qix")) }
                return MusicPage(tracks + tracks.first() +
                    MusicTrack("hidden", "H", listOf("qix"), availability = "metadata_only"), partial = true)
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicViewModel(repo, scope = scope)
            model.enter()
            val feed = model.browse.value
            assertEquals((1..75).map(Int::toString), feed.tracks.map { it.id })
            assertTrue(feed.loaded)
            assertFalse(feed.loading)
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }

    @Test fun activationReloadsTheMusicPageAfterItsPendingRequestFinishes() = runBlocking {
        val initial = CompletableDeferred<Unit>()
        val track = MusicTrack("a", "A", listOf("Singer"))
        var requests = 0
        val repository = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                if (++requests == 1) {
                    initial.await()
                    throw MusicException(MusicFailure.NOT_CONFIGURED)
                }
                return MusicPage(listOf(track))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicViewModel(repository, scope = scope)
            model.enter()
            model.onDeviceActivated()
            assertEquals(1, requests)
            initial.complete(Unit)
            assertEquals(2, requests)
            assertEquals(listOf(track), model.browse.value.tracks)
            assertNull(model.browse.value.failure)
        } finally {
            scope.cancel()
        }
    }

    @Test fun emptyPartialFeedKeepsTheActionableFailure() = runBlocking {
        val repo = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun recommendations(limit: Int, cursor: String?) =
                MusicPage(emptyList(), partial = true, warnings = listOf("qqmusic:rate_limited"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicViewModel(repo, scope = scope)
            model.enter()
            assertTrue(model.browse.value.tracks.isEmpty())
            assertEquals(MusicFailure.RATE_LIMITED, model.browse.value.failure)
        } finally { scope.cancel() }
    }
}
