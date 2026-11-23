package com.meowv.tune.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.domain.SettingsRepository
import com.meowv.tune.domain.TuneSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val error = MutableStateFlow(false)
    val storageError = error.asStateFlow()
    val settings = repository.settings.onEach<TuneSettings?> { error.value = false }
        .retrySettingsRead { error.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun update(transform: (TuneSettings) -> TuneSettings) {
        viewModelScope.launch {
            try {
                repository.update(transform)
                error.value = false
            } catch (_: IOException) {
                error.value = true
            }
        }
    }
}

internal fun <T> Flow<T>.retrySettingsRead(onError: () -> Unit = {}): Flow<T> = retryWhen { cause, _ ->
    if (cause !is IOException) return@retryWhen false
    onError()
    delay(2_000)
    true
}
