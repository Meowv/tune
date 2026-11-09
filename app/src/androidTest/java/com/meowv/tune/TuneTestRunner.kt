package com.meowv.tune

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.runner.AndroidJUnitRunner
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.RadioCountry
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.radio.DirectoryValue
import com.meowv.tune.radio.DirectoryCache
import com.meowv.tune.settings.DataStoreSettingsRepository
import com.meowv.tune.playback.PlaybackIntent
import com.meowv.tune.playback.PlaybackRetryPolicy
import com.meowv.tune.playback.ResolvedRadioStream
import com.meowv.tune.update.UpdateInfo
import com.meowv.tune.update.UpdateRepository
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val Context.testDirectory by preferencesDataStore("test_radio_directory")
private val Context.testSettings by preferencesDataStore("test_tune_settings")

class TuneTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context) =
        super.newApplication(loader, TuneTestApplication::class.java.name, context)

    override fun onStart() {
        super.onStart()
        // API 33+ 的运行时通知权限会弹出对话框打断用例，统一预授权；
        // 权限流程本身由 T27 单独验证。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                uiAutomation.grantRuntimePermission(
                    targetContext.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }
}

class TuneTestApplication : TuneApplication() {
    // liveRadio=true 时使用真实网络与真实解析，用于 T26 公网联调。
    // 首次访问时读取参数：Application 构造阶段 Instrumentation 尚未注册参数。
    internal val liveRadio: Boolean by lazy {
        InstrumentationRegistry.getArguments().getString("liveRadio") == "true"
    }
    internal val mockNetwork = MutableStateFlow(NetworkState(NetworkType.WIFI, metered = false))
    override val networkState: StateFlow<NetworkState> get() = mockNetwork
    override val playbackRetryPolicy: PlaybackRetryPolicy
        get() = if (liveRadio) super.playbackRetryPolicy else testRetryPolicy
    private val testRetryPolicy = PlaybackRetryPolicy(
        maxSameStationRetries = 2,
        maxAlternateStations = 3,
        backoffMillis = listOf(20L, 40L)
    )

    override val mediaHttp by lazy {
        if (liveRadio) return@lazy super.mediaHttp
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val file = request.url.pathSegments.last()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            if (file == "missing") {
                response.code(404).message("Not found").body(byteArrayOf().toResponseBody()).build()
            } else {
                val assets = InstrumentationRegistry.getInstrumentation().context.assets
                val body = assets.open("t15/$file").use { it.readBytes() }
                response.code(200).message("OK").body(body.toResponseBody()).build()
            }
        }.build()
    }

    override suspend fun resolveStream(intent: PlaybackIntent): ResolvedRadioStream {
        if (liveRadio) return super.resolveStream(intent)
        resolveCalls.computeIfAbsent(intent.station.uuid) { AtomicInteger() }.incrementAndGet()
        resolutionStarts[intent.station.uuid]?.complete(Unit)
        latePlaybackDelays[intent.station.uuid]?.let { withContext(NonCancellable) { delay(it) } }
        playbackDelays[intent.station.uuid]?.let { delay(it) }
        val failures = resolveFailures[intent.station.uuid]
        if (failures != null && failures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            throw TuneFailure(TuneError.TIMEOUT)
        }
        val file = when (intent.station.uuid) {
            UUID(0, 21) -> "radio.mp3"
            UUID(0, 22) -> "radio.aac"
            UUID(0, 23) -> "radio.m3u8"
            UUID(0, 24) -> "missing"
            UUID(0, 25) -> "no-source"
            else -> if (intent.station.name.startsWith("Test station ")) "radio.mp3" else intent.station.name
        }
        if (file == "no-source") throw TuneFailure(TuneError.INVALID_RESPONSE)
        return ResolvedRadioStream("https://test.example/$file".toHttpUrl(), file == "radio.m3u8")
    }

    override val directoryCache by lazy { DirectoryCache(testDirectory) }
    override val userSettings by lazy { DataStoreSettingsRepository(testSettings) }
    internal val country = requireNotNull(CountryCode.parse("JP"))
    internal val foreignStation = Station(UUID(0, 500), "Foreign station", CountryCode.parse("US"))
    internal val queries = AtomicInteger()
    internal val playbackDelays = ConcurrentHashMap<UUID, Long>()
    internal val latePlaybackDelays = ConcurrentHashMap<UUID, Long>()
    internal val resolutionStarts = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
    internal val resolveCalls = ConcurrentHashMap<UUID, AtomicInteger>()
    internal val resolveFailures = ConcurrentHashMap<UUID, AtomicInteger>()
    @Volatile internal var failQueries = false
    @Volatile internal var failResume = false
    internal val resumeQueries = AtomicInteger()
    private val directory = listOf(RadioCountry(country, 36))
    private val stations = (1L..36L).map { Station(UUID(0, it), "Test station $it", country) }

    override suspend fun loadCountries(force: Boolean) =
        if (liveRadio) super.loadCountries(force) else DirectoryValue(directory, stale = false)

    override val radioRepository: RadioRepository by lazy {
        if (liveRadio) super.radioRepository else fakeRepository
    }

    // 版本检查在测试里走固定清单，避免真实网络影响用例时序。
    override val updateRepository: UpdateRepository = object : UpdateRepository {
        override suspend fun fetch(): UpdateInfo = testManifest
    }

    val testManifest = UpdateInfo(
        packageName = "com.meowv.tune",
        channel = "stable",
        versionName = "1.1.0",
        versionCode = 101,
        minSdkVersion = 28,
        releaseDate = "2026-11-15",
        title = "MeowvTune 1.1.0",
        releaseNotes = listOf("优化电台播放体验", "修复部分问题"),
        apkUrl = "https://studio.meowv.com/data/meowvtune/1.1.0/MeowvTune-1.1.0.apk",
        apkSizeBytes = 25_600_000,
        apkSha256 = "a".repeat(64)
    )

    private val fakeRepository: RadioRepository = object : RadioRepository {
        override suspend fun countries() = directory

        override suspend fun stationsForCountry(country: CountryCode, offset: Int, limit: Int): StationPage {
            queries.incrementAndGet()
            if (failQueries) throw TuneFailure(TuneError.CONNECTION)
            if (country != this@TuneTestApplication.country) return StationPage()
            val page = stations.drop(offset).take(limit)
            return StationPage(page, (offset + limit).takeIf { it < stations.size })
        }

        override suspend fun randomGlobalStations(limit: Int): List<Station> {
            queries.incrementAndGet()
            if (failQueries) throw TuneFailure(TuneError.CONNECTION)
            return stations.take(limit)
        }

        override suspend fun stationByUuid(uuid: UUID): Station? {
            resumeQueries.incrementAndGet()
            if (failResume) throw TuneFailure(TuneError.CONNECTION)
            if (uuid == foreignStation.uuid) return foreignStation
            return stations.firstOrNull { it.uuid == uuid }
        }
    }
}
