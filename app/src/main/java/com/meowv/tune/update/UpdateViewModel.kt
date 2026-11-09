package com.meowv.tune.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(
    private val repository: UpdateRepository,
    private val checker: UpdateChecker,
    val local: LocalVersion
) : ViewModel() {

    private val current = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = current.asStateFlow()
    private var inFlight: Job? = null

    /**
     * 发起一次远程检查。进行中重复调用直接忽略，保证按钮与自动检查不会叠加请求；
     * 请求失败只落到 Error 状态，不影响其他页面。
     */
    fun check() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            current.value = UpdateUiState.Checking
            current.value = try {
                val info = repository.fetch()
                when (val verdict = checker.evaluate(local, info)) {
                    is UpdateVerdict.Available -> UpdateUiState.UpdateAvailable(verdict.info)
                    is UpdateVerdict.UpToDate -> UpdateUiState.UpToDate(verdict.info)
                    is UpdateVerdict.Downgrade -> UpdateUiState.Downgrade(verdict.info)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateException) {
                UpdateUiState.Error(failure.failure)
            } catch (_: Exception) {
                UpdateUiState.Error(UpdateFailure.SERVER)
            }
        }
    }
}
