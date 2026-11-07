package com.meowv.tune.radio

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.domain.DirectoryCacheState
import com.meowv.tune.domain.PlayerController
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.RadioQueue
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RadioViewModel(
    private val repository: RadioRepository,
    player: PlayerController,
    private val countryResolver: CountryResolver,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val cache: DirectoryCache? = null,
    private val queue: RadioQueue? = null
) : ViewModel(scope) {
    private val directory = MutableStateFlow(RadioUiState())
    val radioState = directory.asStateFlow()
    val playerState = player.state
    private var load: Job? = null
    private var resolved: ResolvedRadioScope? = null
    private var page = StationPage()

    @MainThread
    fun refresh(scope: RadioScope, force: Boolean = true) {
        load?.cancel()
        if (directory.value.scope != scope) {
            queue?.clearScope()
            resolved = null
            page = StationPage()
            directory.value = RadioUiState(scope = scope)
        }
        startLoad {
            for (candidate in countryResolver.candidates(scope, repository)) {
                val country = candidate.country
                val cached = cache?.stations(candidate)
                ensureActive()
                val autoCountry = scope.countryCode == null && country != null
                if (cached != null && (!autoCountry || cached.value.stations.isNotEmpty())) {
                    val freshness = if (cached.stale) DirectoryCacheState.STALE else DirectoryCacheState.FRESH
                    publish(candidate, cached.value, freshness, isLoading = force || cached.stale)
                    if (!force && !cached.stale) break
                } else if (cached != null && autoCountry && !force && !cached.stale) continue
                val first = cachedLoad(candidate) {
                    if (country == null) StationPage(repository.randomGlobalStations(DIRECTORY_PAGE_SIZE))
                    else appendCountryPage(repository, country, StationPage(nextOffset = 0))
                }
                ensureActive()
                if (autoCountry && first.stations.isEmpty()) continue
                publish(candidate, first)
                break
            }
        }
    }

    @MainThread
    fun loadMore() {
        val candidate = resolved ?: return
        val country = candidate.country ?: return
        if (load?.isActive == true || page.nextOffset == null) return
        startLoad {
            val next = cachedLoad(candidate) { appendCountryPage(repository, country, page) }
            ensureActive()
            publish(candidate, next)
        }
    }

    private fun startLoad(block: suspend CoroutineScope.() -> Unit) {
        directory.value = directory.value.copy(isLoading = true, error = null)
        load = viewModelScope.launch {
            try {
                block()
            } catch (failure: TuneFailure) {
                ensureActive()
                directory.value = directory.value.copy(isLoading = false, error = failure.kind)
            }
        }
    }

    private suspend fun cachedLoad(candidate: ResolvedRadioScope, fetch: suspend () -> StationPage): StationPage =
        cache?.refreshStations(candidate, fetch) ?: fetch()

    private fun publish(
        candidate: ResolvedRadioScope,
        result: StationPage,
        cacheState: DirectoryCacheState? = null,
        isLoading: Boolean = false
    ) {
        resolved = candidate
        page = result
        queue?.updateDirectory(candidate, result)
        directory.value = RadioUiState(
            scope = candidate.scope,
            resolvedCountryCode = candidate.country,
            stations = result.stations,
            hasMore = result.nextOffset != null,
            cacheState = cacheState,
            isLoading = isLoading
        )
    }
}
