package com.meowv.tune.music

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicSearchHistoryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun retainsTenDistinctTermsAndClearSurvivesReopen() = runBlocking<Unit> {
        val file = File(folder.root, "search.preferences_pb")
        open(file) { store ->
            (1..12).forEach { store.add("Song $it") }
            store.add("  Song 5  ")
            store.add("")
            store.add("https://example.test/?token=private")
            store.add("www.example.test")
            store.add("invalid\nquery")
            assertEquals(listOf("Song 5", "Song 12", "Song 11", "Song 10", "Song 9", "Song 8", "Song 7", "Song 6", "Song 4", "Song 3"), store.read())
        }
        open(file) { store ->
            assertEquals(10, store.read().size)
            store.clear()
            assertEquals(emptyList<String>(), store.read())
        }
        open(file) { assertEquals(emptyList<String>(), it.read()) }
    }

    private suspend fun open(file: File, block: suspend (MusicSearchHistoryStore) -> Unit) {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(MusicSearchHistoryStore(PreferenceDataStoreFactory.create(scope = lifetime, produceFile = { file })))
        } finally {
            lifetime.coroutineContext.job.cancelAndJoin()
        }
    }
}
