package com.meowv.tune.domain

import java.util.Locale
import java.util.UUID

@JvmInline
value class CountryCode private constructor(val value: String) {
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
    TIMEOUT, CONNECTION, TLS, SERVER, HTTP, INVALID_RESPONSE, STORAGE, PLAYBACK
}

// 错误只携带可安全展示的类别和状态码。
class TuneFailure(val kind: TuneError, val statusCode: Int? = null) : Exception(kind.name)

data class RadioUiState(
    val scope: RadioScope = RadioScope(),
    val resolvedCountryCode: CountryCode? = null,
    val stations: List<Station> = emptyList(),
    val selectedStationUuid: UUID? = null,
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val error: TuneError? = null
)

enum class PlaybackSource { RADIO, MUSIC }

enum class PlaybackPhase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, RETRYING, ERROR }

data class PlayerUiState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val station: Station? = null,
    val error: TuneError? = null,
    val source: PlaybackSource = PlaybackSource.RADIO,
    val isLive: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false
)
