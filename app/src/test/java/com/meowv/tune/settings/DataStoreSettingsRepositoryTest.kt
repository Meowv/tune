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
            assertEquals(PlaybackOrder.SHUFFLE, repository.settings.first().playbackOrder)
            coroutineScope {
                launch(Dispatchers.Default) { repository.update { it.copy(scope = japan) } }
                launch(Dispatchers.Default) { repository.update { it.copy(autoPlay = true) } }
                launch(Dispatchers.Default) { repository.update { it.copy(musicDisplayMode = RadioDisplayMode.CARD) } }
                launch(Dispatchers.Default) { repository.update { it.copy(themeMode = ThemeMode.DARK) } }
                launch(Dispatchers.Default) {
                    repository.update {
                        it.copy(displayMode = RadioDisplayMode.CARD, playbackOrder = PlaybackOrder.SEQUENTIAL)
                    }
                }
            }
            repository.recordSuccessfulStation(uuid)
        }
        open(file) { repository ->
            assertEquals(
                TuneSettings(
                    scope = japan, autoPlay = true, lastSuccessfulStation = uuid,
                    displayMode = RadioDisplayMode.CARD, playbackOrder = PlaybackOrder.SEQUENTIAL,
                    musicDisplayMode = RadioDisplayMode.CARD, themeMode = ThemeMode.DARK
                ),
                repository.settings.first()
            )
            repository.update {
                it.copy(autoPlay = false, scope = RadioScope(ScopeMode.GLOBAL), displayMode = RadioDisplayMode.LIST)
            }
            repository.recordSuccessfulStation(uuid)
        }
        open(file) { repository ->
            val settings = repository.settings.first()
            assertFalse(settings.autoPlay)
            assertEquals(uuid, settings.lastSuccessfulStation)
            assertEquals(ScopeMode.GLOBAL, settings.scope.mode)
            assertEquals(RadioDisplayMode.LIST, settings.displayMode)
            assertEquals(RadioDisplayMode.CARD, settings.musicDisplayMode)
            assertEquals(PlaybackOrder.SEQUENTIAL, settings.playbackOrder)
            repository.update { it.copy(autoPlay = true, lastSuccessfulStation = uuid) }
            assertEquals(uuid, repository.settings.first().lastSuccessfulStation)
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
                it[stringPreferencesKey("music_display_mode")] = "UNKNOWN"
                // 已下线的设置键即使残留也不应再影响当前设置。
                it[booleanPreferencesKey("wifi_only")] = true
                it[booleanPreferencesKey("data_warning")] = false
                it[booleanPreferencesKey("large_text")] = true
                it[booleanPreferencesKey("pause_on_disconnect")] = false
                it[stringPreferencesKey("layout")] = "CAR"
                it[stringPreferencesKey("theme_mode")] = "UNKNOWN"
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

    @Test fun remembersStationMetadataWithoutAutoPlayAndAfterDisablingIt() = runBlocking {
        val file = File(folder.root, "remember.preferences_pb")
        val station = Station(UUID(0, 7), "Last Radio", CountryCode.parse("CN"), "https://example.test/logo.png")
        open(file) { repo ->
            repo.recordSuccessfulStation(station)
            assertFalse(repo.settings.first().autoPlay)
            repo.update { it.copy(autoPlay = true) }
            repo.update { it.copy(autoPlay = false) }
        }
        open(file) { repo ->
            assertFalse(repo.settings.first().autoPlay)
            assertEquals(station.uuid, repo.settings.first().lastSuccessfulStation)
            assertEquals(station, repo.settings.first().lastPlayedStation)
            repo.refreshRememberedStation(station.copy(uuid = UUID(0, 99), name = "Old result"))
            assertEquals(station, repo.settings.first().lastPlayedStation)
        }
    }

    @Test fun themeModeFallsBackAndSurvivesReopen() = runBlocking {
        val file = File(folder.root, "theme.preferences_pb")
        val mirror = FakeThemeStore()
        open(file, mirror) { repository ->
            assertEquals(ThemeMode.FOLLOW_SYSTEM, repository.settings.first().themeMode)
            repository.update { it.copy(themeMode = ThemeMode.LIGHT) }
            assertEquals(ThemeMode.LIGHT, repository.settings.first().themeMode)
            assertEquals("light", mirror.value)
        }
        open(file, mirror) { repository ->
            assertEquals(ThemeMode.LIGHT, repository.settings.first().themeMode)
            repository.update { it.copy(themeMode = ThemeMode.DARK) }
            assertEquals("dark", mirror.value)
        }
    }

    private class FakeThemeStore : ThemeStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private suspend fun open(
        file: File,
        mirror: ThemeStore? = null,
        block: suspend (SettingsRepository) -> Unit
    ) {
        val lifetime = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = lifetime,
                produceFile = { file }
            )
            val prefs = mirror?.let(::ThemePrefs)
            withTimeout(5000) { block(DataStoreSettingsRepository(store, prefs)) }
        } finally {
            lifetime.coroutineContext.job.cancelAndJoin()
        }
    }
}
