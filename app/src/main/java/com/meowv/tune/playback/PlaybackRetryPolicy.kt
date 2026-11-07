package com.meowv.tune.playback

import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure

internal data class PlaybackRetryPolicy(
    val maxSameStationRetries: Int = 2,
    val maxAlternateStations: Int = 3,
    val backoffMillis: List<Long> = listOf(1_000L, 2_000L)
) {
    init {
        require(maxSameStationRetries >= 0)
        require(maxAlternateStations >= 0)
        require(backoffMillis.isNotEmpty())
    }

    fun delayFor(retry: Int): Long = backoffMillis[(retry - 1).coerceIn(backoffMillis.indices)]

    fun canRetrySame(retry: Int) = retry <= maxSameStationRetries

    fun canTryAlternate(count: Int) = count < maxAlternateStations

    fun isRetryable(failure: TuneFailure): Boolean =
        failure.kind in setOf(TuneError.TIMEOUT, TuneError.CONNECTION, TuneError.TLS, TuneError.SERVER) ||
            failure.kind == TuneError.HTTP && failure.statusCode in 500..599
}
