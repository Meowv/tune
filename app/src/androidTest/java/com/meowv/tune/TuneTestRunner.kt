package com.meowv.tune

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.runner.AndroidJUnitRunner
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.RadioCountry
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.radio.DirectoryValue
import com.meowv.tune.radio.DirectoryCache
import com.meowv.tune.settings.DataStoreSettingsRepository
import com.meowv.tune.playback.PlaybackIntent
import com.meowv.tune.playback.ResolvedRadioStream
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

private val Context.testDirectory by preferencesDataStore("test_radio_directory")
private val Context.testSettings by preferencesDataStore("test_tune_settings")

class TuneTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context) =
        super.newApplication(loader, TuneTestApplication::class.java.name, context)
}

class TuneTestApplication : TuneApplication() {
    override val mediaHttp by lazy {
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
        resolutionStarts[intent.station.uuid]?.complete(Unit)
        latePlaybackDelays[intent.station.uuid]?.let { withContext(NonCancellable) { delay(it) } }
        playbackDelays[intent.station.uuid]?.let { delay(it) }
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
    internal val queries = AtomicInteger()
    internal val playbackDelays = ConcurrentHashMap<UUID, Long>()
    internal val latePlaybackDelays = ConcurrentHashMap<UUID, Long>()
    internal val resolutionStarts = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
    @Volatile internal var failQueries = false
    private val directory = listOf(RadioCountry(country, 36))
    private val stations = (1L..36L).map { Station(UUID(0, it), "Test station $it", country) }

    override suspend fun loadCountries(force: Boolean) = DirectoryValue(directory, stale = false)

    override val radioRepository: RadioRepository = object : RadioRepository {
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
    }
}
