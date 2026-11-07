package com.meowv.tune.radio

import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.StationPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val DIRECTORY_PAGE_SIZE = 30

// 无新增条目的满页最多跨过三次，避免重复响应让目录一直加载。
internal suspend fun appendCountryPage(
    repository: RadioRepository,
    country: CountryCode,
    current: StationPage
): StationPage {
    var offset = current.nextOffset ?: return current
    val known = current.stations.mapTo(hashSetOf()) { it.uuid }
    repeat(3) {
        val page = repository.stationsForCountry(country, offset, DIRECTORY_PAGE_SIZE)
        currentCoroutineContext().ensureActive()
        val new = page.stations.filter { it.countryCode == country && known.add(it.uuid) }
        val next = page.nextOffset?.takeIf { it > offset }
        if (new.isNotEmpty() || next == null) return StationPage(current.stations + new, next)
        offset = next
    }
    return current.copy(nextOffset = null)
}
