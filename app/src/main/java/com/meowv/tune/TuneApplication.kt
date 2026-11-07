package com.meowv.tune

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meowv.tune.network.*
import kotlinx.coroutines.flow.first

private val Context.mirrorPreferences by preferencesDataStore(
    name = "radio_browser_mirrors",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)
private val LastMirror = stringPreferencesKey("last_good_host")

class TuneApplication : Application() {
    val radioBrowser: RadioBrowserClient by lazy {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        val http = DirectoryHttp(version)
        val store = object : LastMirrorStore {
            override suspend fun read() =
                MirrorHost.parse(mirrorPreferences.data.first().asMap()[LastMirror] as? String)

            override suspend fun write(host: MirrorHost) {
                mirrorPreferences.edit { it[LastMirror] = host.name }
            }
        }
        RadioBrowserClient(
            http,
            MirrorDirectory(
                discover = ::discoverMirrors,
                serverList = { http.get(it, "/json/servers", timeoutMillis = 2000) },
                store = store,
                nowMillis = SystemClock::elapsedRealtime
            )
        )
    }
}
