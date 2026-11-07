package com.meowv.tune.radio

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.PlayerController
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.ScopeMode
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
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ViewModel(scope) {
    private val directory = MutableStateFlow(RadioUiState())
    val radioState = directory.asStateFlow()
    val playerState = player.state
    private var load: Job? = null

    @MainThread
    fun refresh(scope: RadioScope, automaticCountry: CountryCode? = null) {
        val country = when (scope.mode) {
            ScopeMode.AUTO -> automaticCountry
            ScopeMode.COUNTRY -> scope.countryCode
            ScopeMode.GLOBAL -> null
        }
        load?.cancel()
        val previous = directory.value
        val initial = if (previous.scope == scope && previous.resolvedCountryCode == country) {
            previous.copy(isLoading = true, error = null)
        } else {
            RadioUiState(scope = scope, resolvedCountryCode = country, isLoading = true)
        }
        directory.value = initial
        load = viewModelScope.launch {
            try {
                val stations = if (country != null) repository.stationsForCountry(country, 0, PAGE_SIZE)
                else repository.randomGlobalStations(PAGE_SIZE)
                ensureActive()
                directory.value = initial.copy(
                    stations = stations.distinctBy { it.uuid },
                    isLoading = false,
                    hasMore = country != null && stations.size >= PAGE_SIZE
                )
            } catch (failure: TuneFailure) {
                ensureActive()
                directory.value = initial.copy(isLoading = false, error = failure.kind)
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
