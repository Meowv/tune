package com.meowv.tune.music

import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicSearchViewModelTest {
    private val track = MusicTrack("one", "One", listOf("qix"))
    private class Repository(val search: suspend (String, String?) -> MusicPage) : MusicRepository {
        override suspend fun search(query: String, limit: Int, cursor: String?) = search(query, cursor)
        override suspend fun recommendations(limit: Int, cursor: String?) = error("Search must not request recommendations")
        override suspend fun resolve(trackId: String, preferredProviderId: String?) = error("Service owns resolution")
    }

    @Test fun openingAndTypingDoNotRequestAndOnlySelectedResultIsDispatched() = runBlocking {
        var requests = 0
        val selected = mutableListOf<MusicTrack>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicSearchViewModel(Repository { query, _ ->
                requests++; assertEquals("qix", query); MusicPage(listOf(track,
                    track.copy(id = "restricted", availability = "unavailable"), track.copy(id = "metadata", availability = "metadata_only")))
            }, { selected += it }, scope)
            model.open(); model.queryChanged(" qix ")
            assertEquals(0, requests)
            model.search()
            assertEquals(1, requests)
            model.select(track.copy(id = "unknown"))
            model.select(track.copy(id = "restricted", availability = "unavailable"))
            model.select(track.copy(id = "metadata", availability = "metadata_only"))
            assertTrue(selected.isEmpty())
            model.select(track)
            assertEquals(listOf(track), selected)
            assertFalse(model.state.value.visible)
        } finally { scope.cancel() }
    }

    @Test fun changingQueryAndClosingRejectLateUncancellableResponses() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicSearchViewModel(Repository { query, _ ->
                if (query == "old") withContext(NonCancellable) { gate.await() }
                if (query == "closing") withContext(NonCancellable) { closing.await() }
                MusicPage(listOf(track.copy(id = query)))
            }, {}, scope)
            model.open(); model.queryChanged("old"); model.search()
            model.queryChanged("new"); model.search()
            assertEquals("new", model.state.value.items.single().id)
            gate.complete(Unit)
            assertEquals("new", model.state.value.items.single().id)
            model.queryChanged("closing"); model.search(); model.close()
            closing.complete(Unit)
            assertFalse(model.state.value.visible)
            assertTrue(model.state.value.items.isEmpty())
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.searched)
        } finally { gate.complete(Unit); closing.complete(Unit); scope.cancel() }
    }

    @Test fun paginationDeduplicatesAndStopsRepeatedCursorWithoutAutomaticRequests() = runBlocking {
        var requests = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicSearchViewModel(Repository { _, cursor ->
                requests++
                MusicPage(if (cursor == null) listOf(track) else listOf(track, track.copy(id = "two")), "same")
            }, {}, scope)
            model.open(); model.queryChanged("qix"); model.search(); model.more(); model.more()
            assertEquals(2, requests)
            assertEquals(listOf("one", "two"), model.state.value.items.map { it.id })
            assertNull(model.state.value.nextCursor)
        } finally { scope.cancel() }
    }

    @Test fun failedPageRetainsResultsAndSelectionFailureDoesNotDismissSearch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicSearchViewModel(Repository { _, cursor ->
                if (cursor != null) throw MusicException(MusicFailure.RATE_LIMITED)
                MusicPage(listOf(track), "next", partial = true)
            }, { throw TuneFailure(TuneError.PLAYBACK) }, scope)
            model.open(); model.queryChanged("qix"); model.search(); model.more()
            assertEquals(listOf(track), model.state.value.items)
            assertTrue(model.state.value.partial)
            assertEquals(MusicFailure.RATE_LIMITED, model.state.value.appendFailure)
            model.select(track)
            assertTrue(model.state.value.visible)
            assertFalse(model.state.value.selecting)
            assertEquals(MusicFailure.PLAYBACK_UNAVAILABLE, model.state.value.selectionFailure)
        } finally { scope.cancel() }
    }

    @Test fun initialAndAppendRetriesUseTheirOwnTargetsAndSuppressDuplicateRequests() = runBlocking {
        val requests = mutableListOf<String?>()
        val append = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicSearchViewModel(Repository { _, cursor ->
                requests.add(cursor)
                when (requests.size) {
                    1, 3 -> throw MusicException(MusicFailure.CONNECTION)
                    2 -> MusicPage(listOf(track), "next")
                    else -> { append.await(); MusicPage(listOf(track.copy(id = "two"))) }
                }
            }, {}, scope)
            model.open(); model.queryChanged("qix")
            model.search()
            assertEquals(MusicFailure.CONNECTION, model.state.value.failure)
            assertNull(model.state.value.appendFailure)
            model.search()
            assertNull(model.state.value.failure)
            model.more()
            assertEquals(MusicFailure.CONNECTION, model.state.value.appendFailure)
            assertNull(model.state.value.failure)
            assertEquals(listOf(track), model.state.value.items)
            assertEquals("next", model.state.value.nextCursor)

            model.more(); model.more(); model.search()
            assertEquals(MusicSearchRequest.APPEND, model.state.value.request)
            assertEquals(listOf(null, null, "next", "next"), requests)
            append.complete(Unit)
            assertNull(model.state.value.appendFailure)
            assertNull(model.state.value.nextCursor)
            assertEquals(listOf("one", "two"), model.state.value.items.map { it.id })
        } finally { append.complete(Unit); scope.cancel() }
    }

    @Test fun invalidQueryDoesNotRequestAndRepeatedSubmitDoesNotOverlap() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = MusicSearchViewModel(Repository { _, _ -> requests++; gate.await(); MusicPage(emptyList()) }, {}, scope)
            model.open(); model.queryChanged("bad\nquery"); model.search()
            assertEquals(0, requests)
            assertEquals(MusicFailure.INVALID_REQUEST, model.state.value.failure)
            model.queryChanged("qix"); model.search(); model.search()
            assertEquals(1, requests)
            gate.complete(Unit)
            assertTrue(model.state.value.searched)
            assertTrue(model.state.value.items.isEmpty())
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun deferredPlayableSourceUpgradesMetadataRowWithoutDuplicatingOrKeepingItDisabled() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val metadata = track.copy(id = "metadata", availability = "metadata_only", matches = listOf(MusicTrackMatch("qqmusic", "12")))
        val playable = track.copy(id = "playable", matches = listOf(MusicTrackMatch("netease", "34"), MusicTrackMatch("qqmusic", "12", .99, "studio")))
        try {
            val model = MusicSearchViewModel(Repository { _, cursor ->
                if (cursor == null) MusicPage(listOf(metadata), "later", partial = true, warnings = listOf("netease:deferred"))
                else MusicPage(listOf(playable), partial = true, warnings = listOf("qqmusic:rate_limited", "unknown:forbidden"))
            }, {}, scope)
            model.open(); model.queryChanged("qix"); model.search()
            assertFalse(model.state.value.items.single().canStartPlayback)
            assertEquals(mapOf("netease" to MusicSearchWarning.DEFERRED), model.state.value.sourceWarnings)
            model.more()
            assertEquals(listOf(playable), model.state.value.items)
            assertTrue(model.state.value.items.single().canStartPlayback)
            assertTrue(model.state.value.partial)
            assertEquals(mapOf("qqmusic" to MusicSearchWarning.RATE_LIMITED), model.state.value.sourceWarnings)
        } finally { scope.cancel() }
    }

    @Test fun latestSameIdCanDisablePlaybackAndUnrelatedSameTitleIsNotMerged() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val second = track.copy(id = "different", artists = listOf("Other"))
            val unavailable = track.copy(availability = "unavailable")
            val model = MusicSearchViewModel(Repository { _, cursor ->
                if (cursor == null) MusicPage(listOf(track), "next") else MusicPage(listOf(unavailable, second))
            }, {}, scope)
            model.open(); model.queryChanged("qix"); model.search(); model.more()
            assertEquals(listOf(unavailable, second), model.state.value.items)
        } finally { scope.cancel() }
    }
}
