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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val Context.testDirectory by preferencesDataStore("test_radio_directory")
private val Context.testSettings by preferencesDataStore("test_tune_settings")

class TuneTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context) =
        super.newApplication(loader, TuneTestApplication::class.java.name, context)
}

class TuneTestApplication : TuneApplication() {
    override val directoryCache by lazy { DirectoryCache(testDirectory) }
    override val userSettings by lazy { DataStoreSettingsRepository(testSettings) }
    internal val country = requireNotNull(CountryCode.parse("JP"))
    internal val queries = AtomicInteger()
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
