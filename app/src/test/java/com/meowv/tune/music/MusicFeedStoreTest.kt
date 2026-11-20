package com.meowv.tune.music

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.meowv.tune.domain.music.MusicPage
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.MusicTrackMatch
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicFeedStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val a = MusicTrack("a", "A", listOf("Singer"))
    private val b = MusicTrack("b", "B", listOf("Singer"))
    private val fallback = MusicTrack("c", "Fallback", listOf("Singer"), coverUrl = "https://example.test/c.png",
        matches = listOf(MusicTrackMatch("qqmusic", "source-c")))

    @Test fun persistsAcrossReopenAndClearsOnlyOnCompleteMembershipChange() = runBlocking<Unit> {
        val file = File(folder.root, "music_feed.preferences_pb")
        val oldVersion = open(file) { store ->
            val version = store.updateRecommendations(MusicPage(listOf(a, b))).version
            store.appendFallback(version, listOf(fallback, a, fallback, fallback.copy(id = "hidden", availability = "unavailable")))
            assertEquals(listOf(a, b, fallback), store.snapshot.value.tracks)
            version
        }
        open(file) { store ->
            assertEquals(listOf(a, b, fallback), store.restore().tracks)
            val partial = store.updateRecommendations(MusicPage(listOf(a), partial = true))
            assertEquals(listOf(a, b, fallback), partial.tracks)

            val renamed = a.copy(title = "A updated")
            val reordered = store.updateRecommendations(MusicPage(listOf(b, renamed, b)))
            assertEquals(oldVersion, reordered.version)
            assertEquals(listOf(b, renamed, fallback), reordered.tracks)

            val changed = store.updateRecommendations(MusicPage(listOf(renamed)))
            assertEquals(oldVersion + 1, changed.version)
            assertEquals(listOf(renamed), changed.tracks)
            assertEquals(changed, store.appendFallback(oldVersion, listOf(fallback)))
        }
        open(file) { store ->
            assertTrue(store.restore().fallback.isEmpty())
            assertEquals(listOf("a"), store.snapshot.value.tracks.map { it.id })
        }
    }

    private suspend fun <T> open(file: File, block: suspend (MusicFeedStore) -> T): T {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return block(MusicFeedStore(PreferenceDataStoreFactory.create(scope = lifetime, produceFile = { file })))
        } finally {
            lifetime.coroutineContext.job.cancelAndJoin()
        }
    }
}
