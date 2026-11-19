package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicViewModelTest {
    @Test fun browseCollectsEveryCandidatePageWithPagination() = runBlocking {
        val limits = mutableListOf<Int>()
        val cursors = mutableListOf<String?>()
        val repo = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                limits.add(limit); cursors.add(cursor)
                return when (cursor) {
                    null -> MusicPage(listOf(MusicTrack("a", "A", listOf("qix"))), "next")
                    "next" -> MusicPage(listOf(MusicTrack("a", "A", listOf("qix")), MusicTrack("b", "B", listOf("qix")),
                        MusicTrack("hidden", "H", listOf("qix"), availability = "metadata_only")), null, partial = true)
                    else -> error("Unexpected cursor")
                }
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicViewModel(repo, scope = scope)
            model.enter()
            val feed = model.browse.value
            assertEquals(listOf("a", "b"), feed.tracks.map { it.id })
            assertNull(feed.cursor)
            assertTrue(feed.loaded)
            assertFalse(feed.loading)
            assertTrue(feed.partial)
            assertEquals(listOf(50, 50), limits)
            assertEquals(listOf<String?>(null, "next"), cursors)
        } finally { scope.cancel() }
    }

    @Test fun candidatesLoadOnlyOnClickAndDuplicateRequestsAreIgnored() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val repo = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                requests++; gate.await(); return MusicPage(listOf(MusicTrack("a", "A", listOf("qix"))))
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicViewModel(repo, scope = scope)
            assertEquals(MusicUiState.Idle, model.state.value)
            assertEquals(0, requests)
            model.startListening(); model.startListening()
            assertEquals(1, requests)
            assertEquals(MusicUiState.Loading, model.state.value)
            gate.complete(Unit)
            assertTrue(model.state.value is MusicUiState.Ready)
        } finally { scope.cancel() }
    }

    @Test fun realStartDelegatesToServiceWithoutFetchingAnUnownedCandidateFirst() {
        var starts = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?) = error("Service owns candidate loading")
            override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
        }
        try {
            val model = MusicViewModel(repository, requestPlayback = { starts++ }, scope = scope)
            model.startListening()
            assertEquals(1, starts)
            assertEquals(MusicUiState.Loading, model.state.value)
        } finally { scope.cancel() }
    }

    @Test fun cancelPreventsLateResultsAndUnconfiguredDoesNotRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val unconfigured = MusicViewModel(null, scope = scope)
            unconfigured.startListening()
            assertEquals(MusicUiState.NotConfigured, unconfigured.state.value)
            val gate = CompletableDeferred<Unit>()
            val repo = object : MusicRepository {
                override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                    withContext(NonCancellable) { gate.await() }
                    return MusicPage(listOf(MusicTrack("old", "Old", listOf("qix"))))
                }
                override suspend fun search(query: String, limit: Int, cursor: String?) = error("unused")
                override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("unused")
            }
            val model = MusicViewModel(repo, scope = scope)
            model.startListening(); model.cancel(); gate.complete(Unit)
            assertEquals(MusicUiState.Idle, model.state.value)
        } finally { scope.cancel() }
    }
}
