package com.meowv.tune.playback

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SleepTimerState(
    val durationMinutes: Int? = null,
    val remainingMillis: Long = 0L
) {
    val isActive: Boolean get() = durationMinutes != null
}

/**
 * 使用单调时钟计时。状态由应用进程持有，播放页收起不会中断，
 * 播放服务结束后由服务清理，不额外创建常驻后台组件。
 */
internal class SleepTimerController(
    private val onExpired: suspend () -> Unit,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val tickMillis: Long = 10_000L,
    private val minutesToMillis: (Int) -> Long = { it * 60_000L },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val mutableState = MutableStateFlow(SleepTimerState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var ticker: Job? = null
    private var generation = 0L

    fun start(minutes: Int) {
        require(minutes in MIN_MINUTES..MAX_MINUTES)
        val durationMillis = minutesToMillis(minutes)
        require(durationMillis > 0L)
        val deadline = nowMillis() + durationMillis
        val id = synchronized(lock) {
            ticker?.cancel()
            generation++
            mutableState.value = SleepTimerState(minutes, durationMillis)
            generation
        }
        ticker = scope.launch {
            while (true) {
                val remaining = (deadline - nowMillis()).coerceAtLeast(0L)
                val current = synchronized(lock) {
                    if (generation != id) return@launch
                    mutableState.value = SleepTimerState(minutes, remaining)
                    remaining
                }
                if (current == 0L) {
                    synchronized(lock) {
                        if (generation != id) return@launch
                        mutableState.value = SleepTimerState()
                    }
                    try {
                        onExpired()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 播放服务已结束时定时仍应正常完成。
                    }
                    return@launch
                }
                delay(minOf(tickMillis, current))
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            generation++
            mutableState.value = SleepTimerState()
        }
        ticker?.cancel()
        ticker = null
    }

    fun close() {
        cancel()
        scope.cancel()
    }

    private companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 480
    }
}

/** 底部文案按剩余整分钟展示，最后一分钟内仍显示 1 分钟。 */
internal fun sleepTimerRemainingMinutes(remainingMillis: Long): Int {
    if (remainingMillis <= 0L) return 0
    return ((remainingMillis + 59_999L) / 60_000L).toInt()
}
