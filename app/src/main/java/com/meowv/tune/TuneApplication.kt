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
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.playback.MediaProbe
import com.meowv.tune.playback.PlaybackIntent
import com.meowv.tune.playback.PlaybackRetryPolicy
import com.meowv.tune.playback.MediaUrlPolicy
import com.meowv.tune.playback.NetworkMonitor
import com.meowv.tune.playback.RadioMediaConnection
import com.meowv.tune.playback.RadioStreamResolver
import com.meowv.tune.playback.SleepTimerController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.meowv.tune.settings.settingsRepository
import com.meowv.tune.update.HttpUpdateRepository
import com.meowv.tune.update.UpdateClient
import com.meowv.tune.update.UpdateRepository
import com.meowv.tune.update.install.ApkDownloader
import com.meowv.tune.update.install.ApkInstaller
import com.meowv.tune.update.install.ApkVerifier
import com.meowv.tune.update.install.InstallStore
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
private val Context.installPreferences by preferencesDataStore(
    name = "meowvtune_install",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)
private val LastMirror = stringPreferencesKey("last_good_host")

open class TuneApplication : Application(), SingletonImageLoader.Factory {
    // 同一进程只尝试一次启动恢复，避免 Activity 重建或回前台重复开流。
    internal var autoPlayAttempted = false

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

    // 版本检查只依赖一个 HTTPS 清单地址；地址与渠道来自构建配置，测试可整体替换。
    internal open val updateRepository: UpdateRepository by lazy {
        HttpUpdateRepository(
            UpdateClient(appVersionName()),
            BuildConfig.UPDATE_MANIFEST_URL
        )
    }

    internal val updateChannel: String get() = BuildConfig.UPDATE_CHANNEL

    // 下载、校验、安装三件套；下载由系统 DownloadManager 承担，安装由系统安装器承担。
    internal open val apkDownloader by lazy { ApkDownloader(this) }
    internal open val apkVerifier by lazy { ApkVerifier(this) }
    internal open val apkInstaller by lazy { ApkInstaller(this) }
    internal open val installStore by lazy { InstallStore(installPreferences) }

    internal fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    val radioQueue: RadioQueue by lazy { RadioQueueManager(radioRepository) }

    internal open val mediaHttp by lazy { MediaUrlPolicy.client() }
    private val streamResolver by lazy { RadioStreamResolver(radioBrowser, MediaProbe(mediaHttp)) }
    internal open suspend fun resolveStream(intent: PlaybackIntent) = streamResolver.resolve(intent)
    internal val playerController by lazy { RadioMediaConnection(this) }
    internal val sleepTimer by lazy {
        SleepTimerController(onExpired = {
            runCatching { playerController.dispatch(PlayerCommand.Stop) }
        })
    }

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
