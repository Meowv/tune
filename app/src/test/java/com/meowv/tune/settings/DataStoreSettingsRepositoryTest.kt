package com.meowv.tune.settings

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meowv.tune.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun preferencesSurviveReopenAndConcurrentUpdates() = runBlocking {
        val file = File(folder.root, "tune.preferences_pb")
        val japan = RadioScope(ScopeMode.COUNTRY, CountryCode.parse("JP"))
        val uuid = UUID.randomUUID()
        open(file) { repository ->
            assertEquals(TuneSettings(), repository.settings.first())
            coroutineScope {
                launch(Dispatchers.Default) { repository.update { it.copy(scope = japan) } }
                launch(Dispatchers.Default) { repository.update { it.copy(autoPlay = true) } }
                launch(Dispatchers.Default) {
                    repository.update {
                        it.copy(displayMode = RadioDisplayMode.CARD, playbackOrder = PlaybackOrder.SHUFFLE)
                    }
                }
            }
            repository.recordSuccessfulStation(uuid)
        }
        open(file) { repository ->
            assertEquals(
                TuneSettings(
                    scope = japan, autoPlay = true, lastSuccessfulStation = uuid,
                    displayMode = RadioDisplayMode.CARD, playbackOrder = PlaybackOrder.SHUFFLE
                ),
                repository.settings.first()
            )
            repository.update { it.copy(autoPlay = false, scope = RadioScope(ScopeMode.GLOBAL)) }
            repository.recordSuccessfulStation(UUID.randomUUID())
        }
        open(file) { repository ->
            val settings = repository.settings.first()
            assertFalse(settings.autoPlay)
            assertNull(settings.lastSuccessfulStation)
            assertEquals(ScopeMode.GLOBAL, settings.scope.mode)
            assertEquals(RadioDisplayMode.CARD, settings.displayMode)
            assertEquals(PlaybackOrder.SHUFFLE, settings.playbackOrder)
            repository.update { it.copy(autoPlay = true, lastSuccessfulStation = uuid) }
            assertNull(repository.settings.first().lastSuccessfulStation)
        }
    }

    @Test fun malformedPreferencesAndCorruptionRecoverToDefaults() = runBlocking {
        val file = File(folder.root, "bad.preferences_pb")
        val lifetime = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = lifetime, produceFile = { file })
            store.edit {
                it[stringPreferencesKey("scope")] = "COUNTRY"
                it[stringPreferencesKey("country")] = "ZZ"
                it[stringPreferencesKey("auto_play")] = "true"
                // 已下线的设置键即使残留也不应再影响当前设置。
                it[booleanPreferencesKey("wifi_only")] = true
                it[booleanPreferencesKey("data_warning")] = false
                it[booleanPreferencesKey("large_text")] = true
                it[booleanPreferencesKey("pause_on_disconnect")] = false
                it[stringPreferencesKey("layout")] = "CAR"
            }
            assertEquals(TuneSettings(), DataStoreSettingsRepository(store).settings.first())
        } finally {
            lifetime.coroutineContext.job.cancelAndJoin()
        }
        file.writeBytes(byteArrayOf(0x80.toByte()))
        open(file) { repository ->
            assertEquals(TuneSettings(), repository.settings.first())
            repository.update { it.copy(scope = RadioScope(ScopeMode.GLOBAL)) }
            assertEquals(ScopeMode.GLOBAL, repository.settings.first().scope.mode)
        }
    }

    private suspend fun open(file: File, block: suspend (SettingsRepository) -> Unit) {
        val lifetime = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = lifetime,
                produceFile = { file }
            )
            withTimeout(5000) { block(DataStoreSettingsRepository(store)) }
        } finally {
            lifetime.coroutineContext.job.cancelAndJoin()
        }
    }
}
