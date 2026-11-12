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
import kotlinx.serialization.json.*
import com.meowv.tune.radio.RadioBrowserMapping
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
        }
    }

    override suspend fun recordSuccessfulStation(uuid: UUID) {
        store.edit { prefs ->
            if (prefs[LastStation] != uuid.toString()) prefs.remove(LastStationDetails)
            prefs[LastStation] = uuid.toString()
        }
    }

    override suspend fun recordSuccessfulStation(station: Station) {
        store.edit { prefs ->
            prefs[LastStation] = station.uuid.toString()
            prefs[LastStationDetails] = stationDetails(station)
        }
    }

    override suspend fun refreshRememberedStation(station: Station) {
        store.edit { prefs ->
            if (prefs[LastStation] == station.uuid.toString()) {
                prefs[LastStationDetails] = stationDetails(station)
            }
        }
    }

    // 与目录缓存一致，只保存展示信息；播放时仍按 UUID 重新解析音源。
    private fun stationDetails(station: Station): String = buildJsonObject {
        put("stationuuid", station.uuid.toString())
        put("name", station.name)
        station.countryCode?.let { put("countrycode", it.value) }
        station.iconUrl?.let { put("favicon", it.substringBefore('?').substringBefore('#')) }
        station.codec?.let { put("codec", it) }
        station.bitrate?.let { put("bitrate", it) }
        put("hls", station.isHls)
    }.toString()

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
            lastSuccessfulStation = last,
            lastPlayedStation = last?.let { uuid ->
                (values[LastStationDetails] as? String)?.let { encoded ->
                    runCatching {
                        RadioBrowserMapping.station(Json.parseToJsonElement(encoded))?.takeIf { it.uuid == uuid }
                    }.getOrNull()
                }
            },
            displayMode = RadioDisplayMode.entries.firstOrNull { it.name == values[DisplayMode] }
                ?: RadioDisplayMode.LIST,
            playbackOrder = PlaybackOrder.entries.firstOrNull { it.name == values[Order] }
                ?: PlaybackOrder.SHUFFLE
        )
    }

    private companion object {
        val Scope = stringPreferencesKey("scope")
        val Country = stringPreferencesKey("country")
        val AutoPlay = booleanPreferencesKey("auto_play")
        val LastStation = stringPreferencesKey("last_successful_station")
        val LastStationDetails = stringPreferencesKey("last_station_details")
        val DisplayMode = stringPreferencesKey("radio_display_mode")
        val Order = stringPreferencesKey("playback_order")
    }
}
