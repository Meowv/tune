package com.meowv.tune.music

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.MusicTrackMatch
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicListeningHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun sameCandidatesFollowDeviceHistoryAndMixTwelveWithEight() {
        val tracks = (1..24).map(::track)
        val discovery = tracks.drop(14).map { it.id }.toSet()
        val first = tracks.first()
        val last = tracks.last()
        val now = 100_000_000L
        val left = MusicListeningEvent("heard-left", MusicRecording.of(first), now - 1_000)
        val right = MusicListeningEvent("heard-right", MusicRecording.of(last), now - 1_000)
        val a = MusicRecommendationRanker.rank(tracks, discovery, listOf(left), now)
        val b = MusicRecommendationRanker.rank(tracks, discovery, listOf(right), now)
        assertFalse(a.take(20).contains(first))
        assertFalse(b.take(20).contains(last))
        assertNotEquals(a.take(20), b.take(20))
        assertEquals(8, a.take(20).count { it.id in discovery })
        assertTrue(a.zipWithNext().all { (x, y) -> x.artists != y.artists })
    }

    @Test fun matchesAcrossSourcesButKeepsDistinctRecordings() {
        val original = track(1).copy(title = "晴天", artists = listOf("周杰伦"),
            matches = listOf(MusicTrackMatch("qqmusic", "fixture-1")))
        val alternate = original.copy(id = "netease-alt", durationMs = 100500,
            matches = listOf(MusicTrackMatch("netease", "fixture-2")))
        val live = alternate.copy(id = "netease-live", title = "晴天 (Live)",
            matches = listOf(MusicTrackMatch("netease", "fixture-3")))
        val result = MusicRecommendationRanker.rank(listOf(original, alternate, live), emptySet(), emptyList(), 1000L)
        assertEquals(listOf(original, live), result)
    }

    @Test fun elapsedListeningSurvivesPauseAndIgnoresSeekOrFailureAsFeedback() {
        var time = 0L
        val recorded = mutableListOf<MusicListeningEvent>()
        val tracker = MusicListeningTracker({ time }, { 100_000_000 + time }, emit = recorded::add)
        tracker.start(track(1))
        tracker.observe(true)
        time += 10_000
        tracker.observe(false)
        time += 90_000
        tracker.observe(true)
        time += 19_999
        tracker.observe(false)
        assertTrue(recorded.isEmpty())
        tracker.observe(true)
        time += 1
        tracker.observe(false)
        assertEquals(1, recorded.size)
        tracker.finish(completed = true)
        assertFalse(recorded.single().completed)
        tracker.start(track(2))
        tracker.finish()
        assertEquals(1, recorded.size)
        tracker.start(track(2))
        tracker.observe(true)
        time += 3_000
        tracker.finish(earlySkip = true)
        assertTrue(recorded.last().skipped)
    }

    @Test fun qualifiedHistoryStillAffectsRankingAfterDataStoreReopens() = runBlocking<Unit> {
        val file = File(folder.root, "history.preferences_pb")
        val now = 100_000_000L
        suspend fun open(block: suspend (MusicListeningHistory) -> Unit) {
            val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val preferences = PreferenceDataStoreFactory.create(scope = lifetime, produceFile = { file })
                block(MusicListeningHistory(preferences, { now }, lifetime))
            } finally {
                lifetime.coroutineContext.job.cancelAndJoin()
            }
        }

        open { history ->
            history.record(MusicListeningEvent("sample", MusicRecording.of(track(1)), now - 1_000))
            withTimeout(2_000) { while (history.freshCount(listOf(track(1))) != 0) delay(10) }
        }
        open { history ->
            assertEquals(listOf(track(2), track(1)), history.rank(listOf(track(1), track(2)), emptySet()))
        }
    }

    @Test fun trialSummariesCompareOnlyCountsAndRestoreThePreviousDay() {
        val day = 86_400_000L
        val trial = MusicRecommendationTrial()
        trial.displayed(day, listOf("private-track-a", "private-track-b"))
        trial.attempted(day)
        val restored = MusicRecommendationTrial()
        restored.restore(trial.encode())
        restored.displayed(day * 2, listOf("private-track-b", "private-track-c"))
        restored.recorded(MusicListeningEvent("sample", MusicRecording.of(track(1)), day * 2, automatic = true), true)
        val summary = restored.summary()
        assertTrue(summary.contains("top=2 overlap=1 previous=2 automatic=1 repeated=1"))
        assertFalse(summary.contains("private-track-"))
    }

    private fun track(index: Int) = MusicTrack("item-$index", "Track $index", listOf("Singer $index"), durationMs = 100_000)
}
