package com.meowv.tune.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meowv.tune.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.map

private val Context.tunePreferences by preferencesDataStore(
    name = "tune_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

fun settingsRepository(context: Context): SettingsRepository =
    DataStoreSettingsRepository(context.applicationContext.tunePreferences)

internal class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings = store.data.map(::decode)

    override suspend fun update(transform: (TuneSettings) -> TuneSettings) {
        store.edit { prefs ->
            val current = decode(prefs)
            val updated = transform(current)
            prefs[Scope] = updated.scope.mode.name
            val country = updated.scope.countryCode
            if (country == null) prefs.remove(Country) else prefs[Country] = country.value
            prefs[DisplayMode] = updated.displayMode.name
            prefs[Order] = updated.playbackOrder.name
            prefs[AutoPlay] = updated.autoPlay
            if (!updated.autoPlay) prefs.remove(LastStation)
        }
    }

    override suspend fun recordSuccessfulStation(uuid: UUID) {
        store.edit { prefs ->
            if (prefs.read(AutoPlay) == true) prefs[LastStation] = uuid.toString()
        }
    }

    private fun decode(prefs: Preferences): TuneSettings {
        // 一次取快照，避免每个字段都重新 asMap()。
        val values = prefs.asMap()
        val country = CountryCode.parse(values[Country] as? String)
        val scope = when (values[Scope] as? String) {
            ScopeMode.COUNTRY.name -> if (country == null) RadioScope() else RadioScope(ScopeMode.COUNTRY, country)
            ScopeMode.GLOBAL.name -> RadioScope(ScopeMode.GLOBAL)
            else -> RadioScope()
        }
        val autoPlay = values[AutoPlay] as? Boolean ?: false
        val last = (values[LastStation] as? String)?.let { value ->
            try {
                UUID.fromString(value).takeIf { it.toString().equals(value, ignoreCase = true) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return TuneSettings(
            scope = scope,
            autoPlay = autoPlay,
            lastSuccessfulStation = if (autoPlay) last else null,
            displayMode = RadioDisplayMode.entries.firstOrNull { it.name == values[DisplayMode] }
                ?: RadioDisplayMode.LIST,
            playbackOrder = PlaybackOrder.entries.firstOrNull { it.name == values[Order] }
                ?: PlaybackOrder.SHUFFLE
        )
    }

    private inline fun <reified T> Preferences.read(key: Preferences.Key<T>): T? = asMap()[key] as? T

    private companion object {
        val Scope = stringPreferencesKey("scope")
        val Country = stringPreferencesKey("country")
        val AutoPlay = booleanPreferencesKey("auto_play")
        val LastStation = stringPreferencesKey("last_successful_station")
        val DisplayMode = stringPreferencesKey("radio_display_mode")
        val Order = stringPreferencesKey("playback_order")
    }
}
