package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicQueueManagerTest {
    private val tracks = (1..4).map { MusicTrack("$it", "Song $it", listOf("qix")) }

    @Test fun renewsAnExpiredRollingCursorWithoutRepeatingTheSeed() = runBlocking {
        val requests = mutableListOf<String?>()
        val sample = object : MusicRepository by FakeMusicRepository(tracks) {
            override suspend fun candidates(limit: Int, cursor: String?): MusicPage {
                requests += cursor
                return when (cursor) {
                    "old" -> throw MusicException(MusicFailure.INVALID_REQUEST)
                    null -> MusicPage(listOf(tracks[0], tracks[1]), "new", strategy = "rolling_candidates")
                    "new" -> throw MusicException(MusicFailure.INVALID_REQUEST)
                    else -> error("unexpected cursor")
                }
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = MusicPage(emptyList())
        }
        val queue = MusicQueueManager({ sample })
        queue.reset(MusicQueueSeed(listOf(tracks[0]), "old", rolling = true), startAt = tracks[0].id)
        assertEquals(tracks[1], queue.next()?.track)
        assertNull(queue.next())
        assertEquals(listOf("old", null, "new"), requests)
    }

    @Test fun loadsFullRecommendationListBeforeRadioFallback() = runBlocking {
        val all = (1..75).map { MusicTrack(it.toString(), "Song $it", listOf("qix")) }
        val feed = MusicFeedStore()
        var loads = 0
        var radio = 0
        val repository = object : MusicRepository by FakeMusicRepository(all) {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                loads++
                return MusicPage(all)
            }
            override suspend fun radioRecommendations(): List<MusicTrack> {
                radio++
                return emptyList()
            }
        }
        val queue = MusicQueueManager({ repository }, feedStore = feed)
        all.forEach { assertEquals(it, queue.next()?.track) }
        assertEquals(all, feed.snapshot.value.tracks)
        assertEquals(1, loads)
        assertEquals(0, radio)
        assertNull(queue.next())
        assertEquals(1, radio)
    }

    @Test fun refreshedFeedReplacesPendingTracksAndUsesTheNewCursor() = runBlocking {
        val feed = MusicFeedStore()
        val initial = feed.updateRecommendations(MusicPage(tracks.take(2), "old", strategy = "rolling_candidates"))
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun candidates(limit: Int, cursor: String?): MusicPage {
                assertEquals("new", cursor)
                return MusicPage(listOf(tracks[3]), strategy = "rolling_candidates", discoveryTrackIds = setOf(tracks[3].id))
            }
        }
        val queue = MusicQueueManager({ source }, feedStore = feed)
        queue.reset(MusicQueueSeed(initial.tracks, initial.nextCursor, rolling = true, feedVersion = initial.version))
        assertTrue(queue.recordSuccessfulPlayback(checkNotNull(queue.next())))

        val refreshed = feed.updateRecommendations(MusicPage(listOf(tracks[0], tracks[2]), "new", strategy = "rolling_candidates"))
        assertEquals(initial.version + 1, refreshed.version)
        val next = checkNotNull(queue.next())
        assertEquals(tracks[2], next.track)
        assertTrue(queue.recordSuccessfulPlayback(next))
        assertEquals(tracks[0], queue.previous()?.track)
        assertEquals(tracks[3], queue.next()?.track)
        assertEquals(listOf(tracks[0], tracks[2], tracks[3]), feed.snapshot.value.tracks)
        assertEquals(setOf(tracks[3].id), feed.snapshot.value.discoveryTrackIds)
    }

    @Test fun retainsOnlyTracksAppendedToThePlaybackQueue() = runBlocking {
        val original = tracks.first()
        val candidates = (1..13).map { MusicTrack("fallback-$it", "Fallback $it", listOf("qix")) }
        val feed = MusicFeedStore()
        feed.updateRecommendations(MusicPage(listOf(original)))
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun radioRecommendations() = listOf(original) + candidates
        }
        val queue = MusicQueueManager({ source }, feedStore = feed)
        queue.reset(MusicQueueSeed(listOf(original), null), startAt = original.id)
        assertEquals(candidates.first(), queue.next()?.track)
        assertEquals(listOf(original) + candidates.take(12), feed.snapshot.value.tracks)
    }

    @Test fun fallbackThatFinishesAfterRecommendationRefreshIsDiscarded() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val feed = MusicFeedStore()
        feed.updateRecommendations(MusicPage(listOf(tracks[0])))
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun radioRecommendations(): List<MusicTrack> {
                started.complete(Unit)
                release.await()
                return listOf(tracks[2], tracks[3])
            }
        }
        val queue = MusicQueueManager({ source }, feedStore = feed)
        queue.reset(MusicQueueSeed(listOf(tracks[0]), null), startAt = tracks[0].id)
        val next = async { queue.next() }
        started.await()
        feed.updateRecommendations(MusicPage(listOf(tracks[1])))
        release.complete(Unit)
        assertEquals(tracks[1], next.await()?.track)
        assertEquals(listOf(tracks[1]), feed.snapshot.value.tracks)
    }

    @Test fun rollingPageFromThePreviousFeedIsDiscardedEvenWhenItReturnsAfterRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val feed = MusicFeedStore()
        val initial = feed.updateRecommendations(MusicPage(listOf(tracks[0]), "old", strategy = "rolling_candidates"))
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun candidates(limit: Int, cursor: String?): MusicPage {
                assertEquals("old", cursor)
                started.complete(Unit)
                release.await()
                return MusicPage(listOf(tracks[2]), strategy = "rolling_candidates")
            }
        }
        val queue = MusicQueueManager({ source }, feedStore = feed)
        queue.reset(MusicQueueSeed(initial.tracks, initial.nextCursor, rolling = true), startAt = tracks[0].id)
        val next = async { queue.next() }
        started.await()
        feed.updateRecommendations(MusicPage(listOf(tracks[1]), strategy = "rolling_candidates"))
        release.complete(Unit)
        assertEquals(tracks[1], next.await()?.track)
        assertEquals(listOf(tracks[1]), feed.snapshot.value.tracks)
    }

    @Test fun radioThenSearchFillAnExhaustedQueueAndStopAfterTwoEmptyRounds() = runBlocking {
        var radioCalls = 0
        val searchCursors = mutableListOf<String?>()
        val source = object : MusicRepository by FakeMusicRepository(tracks.take(2)) {
            override suspend fun radioRecommendations(): List<MusicTrack> {
                radioCalls++
                return listOf(tracks[2], tracks[3].copy(availability = "unavailable"), tracks[0], tracks[1])
            }
            override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
                assertEquals(20, limit)
                searchCursors += cursor
                return when (cursor) {
                    null -> MusicPage(listOf(tracks[3]), "first")
                    "first" -> MusicPage(emptyList(), "second")
                    "second" -> MusicPage(emptyList(), "third")
                    "third" -> MusicPage(emptyList(), "fourth")
                    else -> MusicPage(emptyList())
                }
            }
        }
        val queue = MusicQueueManager({ source }, epochSeconds = { 0 })
        repeat(2) { index -> assertEquals(tracks[index], queue.next()?.track) }
        assertEquals(0, radioCalls)
        assertTrue(searchCursors.isEmpty())
        assertEquals(tracks[2], queue.next()?.track)
        assertEquals(tracks[3], queue.next()?.track)
        assertNull(queue.next())
        assertFalse(queue.canNext)
        assertEquals(1, radioCalls)
        assertEquals(listOf(null, "first", "second", "third", "fourth"), searchCursors)
    }

    @Test fun radioReturnsWhileSearchIsStillWaiting() = runBlocking {
        val searchStarted = CompletableDeferred<Unit>()
        val searchCancelled = CompletableDeferred<Unit>()
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun radioRecommendations(): List<MusicTrack> {
                searchStarted.await()
                return listOf(tracks[0])
            }
            override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
                searchStarted.complete(Unit)
                try { awaitCancellation() } finally { searchCancelled.complete(Unit) }
            }
        }
        val queue = MusicQueueManager({ source })
        assertEquals(tracks[0], withTimeout(1500) { queue.next()?.track })
        assertTrue(searchCancelled.isCompleted)
    }

    @Test fun foregroundJoinsPrefetchWithoutAnotherRequest() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun radioRecommendations(): List<MusicTrack> {
                calls++
                started.complete(Unit)
                release.await()
                return listOf(tracks[1])
            }
        }
        val queue = MusicQueueManager({ source })
        queue.reset(MusicQueueSeed(listOf(tracks[0]), null))
        val background = launch { queue.prefetchFallback() }
        started.await()
        assertEquals(tracks[0], queue.next()?.track)
        val foreground = async { queue.next()?.track }
        yield()
        release.complete(Unit)
        assertEquals(tracks[1], foreground.await())
        background.join()
        assertEquals(1, calls)
    }

    @Test fun lateFallbackCannotBeAddedToTheNewSession() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override suspend fun radioRecommendations(): List<MusicTrack> {
                started.complete(Unit)
                release.await()
                return listOf(tracks[0])
            }
        }
        val queue = MusicQueueManager({ source })
        queue.reset(MusicQueueSeed(emptyList(), null))
        val old = async { queue.next() }
        started.await()
        queue.reset(MusicQueueSeed(listOf(tracks[1]), null))
        release.complete(Unit)
        assertNull(old.await())
        assertEquals(tracks[1], queue.next()?.track)
    }

    @Test fun fatalFallbackErrorsStopTheRoundWithTheirOriginalReason() = runBlocking {
        for (reason in listOf(MusicFailure.RATE_LIMITED, MusicFailure.CONNECTION, MusicFailure.UNAUTHORIZED)) {
            var calls = 0
            val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
                override suspend fun radioRecommendations(): List<MusicTrack> {
                    calls++
                    throw MusicException(reason, 27)
                }
            }
            val queue = MusicQueueManager({ source })
            repeat(2) {
                val failure = runCatching { queue.next() }.exceptionOrNull() as MusicException
                assertEquals(reason, failure.failure)
                assertEquals(27L, failure.retryAfterSeconds)
            }
            assertEquals(1, calls)
            assertFalse(queue.canNext)
        }
    }

    @Test fun hourlyRotationDiscardsUnqueuedSearchTracksAndRetainsSessionDedupe() = runBlocking {
        var epoch = 28_799L
        val queries = mutableListOf<Pair<String, String?>>()
        val old = (1..13).map { MusicTrack("old-$it", "Old $it", listOf("qix")) }
        val source = object : MusicRepository by FakeMusicRepository(emptyList()) {
            override fun fallbackSearchQueries(context: MusicTrack?) = listOf(fallbackQuery(epoch))
            override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
                queries += query to cursor
                return if (query == "爵士") MusicPage(old, "old-cursor")
                    else MusicPage(listOf(old.first(), tracks[0]))
            }
        }
        val queue = MusicQueueManager({ source }, epochSeconds = { epoch })
        old.take(12).forEach { assertEquals(it, queue.next()?.track) }
        epoch++
        assertEquals(tracks[0], queue.next()?.track)
        assertEquals(listOf("爵士" to null, "华语流行" to null), queries)
    }

    @Test fun eightStylesUseShanghaiHourBoundariesIncludingNegativeEpoch() {
        val start = java.time.OffsetDateTime.parse("2026-11-19T00:00:00+08:00").toEpochSecond()
        val styles = listOf("华语流行", "摇滚", "民谣", "电子", "说唱", "轻音乐", "ACG", "爵士")
        for (hour in 0 until 24) {
            val boundary = start + hour * 3600
            assertEquals(styles[hour % 8], fallbackQuery(boundary))
            assertEquals(styles[(hour + 7) % 8], fallbackQuery(boundary - 1))
        }
        assertEquals("爵士", fallbackQuery(-1))
    }

    @Test fun fallbackSearchQueriesUseRepositoryExtensionPoint() = runBlocking {
        val queries = mutableListOf<String>()
        val source = object : MusicRepository by FakeMusicRepository(tracks) {
            override fun fallbackSearchQueries(context: MusicTrack?) = listOf("artist:" + context?.artists?.firstOrNull().orEmpty())
            override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
                queries.add(query)
                return MusicPage(emptyList())
            }
        }
        val queue = MusicQueueManager({ source })
        repeat(tracks.size) { assertTrue(queue.recordSuccessfulPlayback(queue.next()!!)) }
        assertNull(queue.next())
        assertEquals(listOf("artist:qix"), queries)
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
        assertNull(queue.next())
        var requests = 0
        val repeating = object : MusicRepository {
            override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
                requests++; return MusicPage(listOf(tracks[0], tracks[0]), "same")
            }
            override suspend fun search(query: String, limit: Int, cursor: String?) = MusicPage(emptyList())
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
            override suspend fun search(query: String, limit: Int, cursor: String?) = MusicPage(emptyList())
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
