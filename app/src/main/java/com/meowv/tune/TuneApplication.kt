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
import com.meowv.tune.domain.CountryDirectory
import com.meowv.tune.radio.DirectoryCache
import com.meowv.tune.radio.RadioBrowserCountries
import com.meowv.tune.radio.RadioBrowserRepository
import com.meowv.tune.radio.RadioQueueManager
import com.meowv.tune.domain.RadioQueue
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.SettingsRepository
import com.meowv.tune.playback.MediaProbe
import com.meowv.tune.playback.PlaybackIntent
import com.meowv.tune.playback.PlaybackRetryPolicy
import com.meowv.tune.playback.MediaUrlPolicy
import com.meowv.tune.playback.NetworkMonitor
import com.meowv.tune.playback.RadioMediaConnection
import com.meowv.tune.playback.RadioStreamResolver
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.meowv.tune.settings.settingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import com.meowv.tune.domain.NetworkState

private val Context.mirrorPreferences by preferencesDataStore(
    name = "radio_browser_mirrors",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)
private val Context.directoryPreferences by preferencesDataStore(
    name = "radio_directory_cache",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)
private val LastMirror = stringPreferencesKey("last_good_host")

open class TuneApplication : Application(), SingletonImageLoader.Factory {
    internal open val directoryCache by lazy { DirectoryCache(directoryPreferences) }
    internal open val userSettings: SettingsRepository by lazy { settingsRepository(this) }
    private val networkMonitor by lazy { NetworkMonitor(this) }
    internal open val networkState: StateFlow<NetworkState> get() = networkMonitor.state
    internal open val playbackRetryPolicy: PlaybackRetryPolicy get() = PlaybackRetryPolicy()
    private val remoteCountries by lazy { RadioBrowserCountries(radioBrowser) }
    val countryDirectory = CountryDirectory { loadCountries().value }

    internal open suspend fun loadCountries(force: Boolean = false) =
        directoryCache.loadCountries(force, remoteCountries::countries)

    internal open val radioRepository: RadioRepository by lazy { RadioBrowserRepository(countryDirectory, radioBrowser) }

    val radioQueue: RadioQueue by lazy { RadioQueueManager(radioRepository) }

    internal open val mediaHttp by lazy { MediaUrlPolicy.client() }
    private val streamResolver by lazy { RadioStreamResolver(radioBrowser, MediaProbe(mediaHttp)) }
    internal open suspend fun resolveStream(intent: PlaybackIntent) = streamResolver.resolve(intent)
    internal val playerController by lazy { RadioMediaConnection(this) }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(MediaUrlPolicy.client(httpsOnly = true))) }
        .build()

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
