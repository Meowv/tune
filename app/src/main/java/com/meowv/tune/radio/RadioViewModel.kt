package com.meowv.tune.radio

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.domain.DirectoryCacheState
import com.meowv.tune.domain.PlayerController
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.RadioQueue
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.RadioCountry
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CountryOptions(
    val countries: List<RadioCountry> = emptyList(),
    val isLoading: Boolean = false,
    val failed: Boolean = false,
    val stale: Boolean = false
)

/** 设置页「刷新数据」的一次性反馈状态；Idle 之外的终态由 UI 消费后复位。 */
sealed interface DirectoryRefresh {
    data object Idle : DirectoryRefresh
    data object Running : DirectoryRefresh
    data object Success : DirectoryRefresh
    data class Failed(val error: TuneError?) : DirectoryRefresh
}

class RadioViewModel(
    private val repository: RadioRepository,
    private val player: PlayerController,
    private val countryResolver: CountryResolver,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val cache: DirectoryCache? = null,
    private val queue: RadioQueue? = null,
    private val countryOptions: suspend (Boolean) -> DirectoryValue<List<RadioCountry>> = {
        DirectoryValue(repository.countries(), stale = false)
    }
) : ViewModel(scope) {
    private val directory = MutableStateFlow(RadioUiState(isLoading = true))
    val radioState = directory.asStateFlow()
    private val options = MutableStateFlow(CountryOptions())
    val countries = options.asStateFlow()
    private val refresh = MutableStateFlow<DirectoryRefresh>(DirectoryRefresh.Idle)
    val refreshState = refresh.asStateFlow()
    val playerState = player.state
    private var load: Job? = null
    private var optionsLoad: Job? = null
    private var requestedScope: RadioScope? = null
    private var resolved: ResolvedRadioScope? = null
    private var page = StationPage()

    init {
        // 播放器确认的台站回写为 UI 选中项，媒体键/自动恢复时列表与播放器保持一致。
        scope.launch {
            player.state.collect { state ->
                val station = state.station ?: return@collect
                if (directory.value.selectedStationUuid != station.uuid) {
                    directory.value = directory.value.copy(selectedStationUuid = station.uuid)
                }
            }
        }
    }

    /** 暂停或空闲时滑动只更新目标台，不触发播放。 */
    @MainThread
    fun selectTarget(station: Station) {
        directory.value = directory.value.copy(selectedStationUuid = station.uuid)
    }

    @MainThread
    fun setScope(scope: RadioScope) {
        if (requestedScope != scope) refresh(scope, force = false)
    }

    @MainThread
    fun loadCountries(force: Boolean = false) {
        if (optionsLoad?.isActive == true) return
        options.value = options.value.copy(isLoading = true, failed = false)
        optionsLoad = viewModelScope.launch(Dispatchers.Default) {
            options.value = try {
                val result = countryOptions(force)
                ensureActive()
                CountryOptions(result.value, failed = result.error != null, stale = result.stale)
            } catch (_: TuneFailure) {
                ensureActive()
                options.value.copy(isLoading = false, failed = true)
            }
        }
    }

    @MainThread
    fun refresh(scope: RadioScope, force: Boolean = true) {
        requestedScope = scope
        load?.cancel()
        if (directory.value.scope != scope) {
            queue?.clearScope()
            viewModelScope.launch {
                runCatching { player.dispatch(PlayerCommand.CancelPending) }
            }
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

    /**
     * 设置页触发的刷新：复用 [refresh] 的加载链路，额外给出「进行中 / 完成 / 失败」反馈。
     * 进行中重复点击直接忽略，避免叠加请求。
     */
    @MainThread
    fun refreshManually(scope: RadioScope) {
        if (refresh.value == DirectoryRefresh.Running) return
        refresh.value = DirectoryRefresh.Running
        refresh(scope, force = true)
        val job = load
        viewModelScope.launch {
            job?.join()
            // 期间又被新的加载取代时，结果由那一次负责，这里不覆盖。
            if (directory.value.isLoading) return@launch
            val error = directory.value.error
            refresh.value = if (error == null) DirectoryRefresh.Success else DirectoryRefresh.Failed(error)
        }
    }

    @MainThread
    fun acknowledgeRefresh() {
        if (refresh.value != DirectoryRefresh.Running) refresh.value = DirectoryRefresh.Idle
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
        // 目录请求的 JSON 解析与列表映射都在这条协程里，放到 Default 避免占用主线程。
        load = viewModelScope.launch(Dispatchers.Default) {
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
        // 同范围刷新时保留仍存在的选中台，避免暂停状态下目标被清空。
        val selected = directory.value.selectedStationUuid
            ?.takeIf { id -> result.stations.any { it.uuid == id } }
        directory.value = RadioUiState(
            scope = candidate.scope,
            resolvedCountryCode = candidate.country,
            stations = result.stations,
            selectedStationUuid = selected,
            hasMore = result.nextOffset != null,
            cacheState = cacheState,
            isLoading = isLoading
        )
    }
}
