package com.meowv.tune.domain

import java.util.Locale
import java.util.UUID

@JvmInline
value class CountryCode private constructor(val value: String) {
    fun displayName(locale: Locale): String = Locale("", value).getDisplayCountry(locale).ifBlank { value }

    companion object {
        private val validCodes = Locale.getISOCountries().toSet()

        fun parse(value: String?): CountryCode? =
            value?.trim()?.uppercase(Locale.ROOT)?.takeIf(validCodes::contains)?.let(::CountryCode)
    }
}

enum class ScopeMode { AUTO, COUNTRY, GLOBAL }

data class RadioScope(val mode: ScopeMode = ScopeMode.AUTO, val countryCode: CountryCode? = null) {
    init {
        require((mode == ScopeMode.COUNTRY) == (countryCode != null))
    }
}

data class Station(
    val uuid: UUID,
    val name: String,
    val countryCode: CountryCode?,
    val iconUrl: String? = null,
    val streamUrl: String? = null,
    val resolvedStreamUrl: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val isHls: Boolean = false
)

enum class TuneError {
    TIMEOUT, CONNECTION, TLS, SERVER, HTTP, INVALID_RESPONSE, STORAGE, PLAYBACK, UNSUPPORTED_MEDIA,
    NETWORK_OFFLINE, AUDIO_FOCUS_LOST, AUDIO_DISCONNECTED
}

// 错误只携带可安全展示的类别和状态码。
class TuneFailure(val kind: TuneError, val statusCode: Int? = null) : Exception(kind.name)

enum class DirectoryCacheState { FRESH, STALE }

enum class RadioDisplayMode { LIST, CARD }

enum class PlaybackOrder { SEQUENTIAL, SHUFFLE }

data class RadioUiState(
    val scope: RadioScope = RadioScope(),
    val resolvedCountryCode: CountryCode? = null,
    val stations: List<Station> = emptyList(),
    val selectedStationUuid: UUID? = null,
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val cacheState: DirectoryCacheState? = null,
    val error: TuneError? = null
)

enum class PlaybackSource { RADIO, MUSIC }

enum class PlaybackPhase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, RETRYING, ERROR }

enum class NetworkType { OFFLINE, WIFI, ETHERNET, CELLULAR, OTHER }

data class NetworkState(
    val type: NetworkType = NetworkType.OFFLINE,
    val metered: Boolean = false
) {
    val available: Boolean get() = type != NetworkType.OFFLINE
}

data class PlayerUiState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val station: Station? = null,
    val error: TuneError? = null,
    val source: PlaybackSource = PlaybackSource.RADIO,
    val isLive: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val statusCode: Int? = null
)

data class RadioCountry(val code: CountryCode, val stationCount: Int) {
    init {
        require(stationCount > 0)
    }

    fun displayName(locale: Locale): String = code.displayName(locale)
}

// AUTO 的有效国家与偏好分开，供缓存、分页和队列共同识别范围。
data class ResolvedRadioScope(val scope: RadioScope, val country: CountryCode?) {
    init {
        when (scope.mode) {
            ScopeMode.COUNTRY -> require(scope.countryCode == country)
            ScopeMode.GLOBAL -> require(country == null)
            ScopeMode.AUTO -> Unit
        }
    }
}

data class StationPage(val stations: List<Station> = emptyList(), val nextOffset: Int? = null)
