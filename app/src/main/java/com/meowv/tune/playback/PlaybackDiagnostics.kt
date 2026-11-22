package com.meowv.tune.playback

import android.os.SystemClock
import android.util.Log
import com.meowv.tune.BuildConfig
import com.meowv.tune.domain.PlaybackSource

internal enum class PlaybackTrigger { PLAY, NEXT, PREVIOUS, RESUME, AUTO_ADVANCE, RECOVERY }

// 仅在 Debug 实例化。事件只含来源、阶段、结果、枚举原因和单调时钟耗时。
internal class PlaybackDiagnostics(
    private val source: PlaybackSource,
    private val nowMillis: () -> Long,
    private val emit: (String) -> Unit
) {
    private enum class Stage { SELECTION, RESOLUTION, PREPARATION, RETRY_WAIT }
    private enum class Outcome { PLAYING, FAILED, CANCELLED, RECOVERING }

    private class Attempt(val trigger: PlaybackTrigger, val startedAt: Long) {
        var stage = Stage.SELECTION
        var stageStartedAt = startedAt
        var selectionMs = 0L
        var resolutionMs = 0L
        var preparationMs = 0L
        var retries = 0

        fun enter(next: Stage, now: Long) {
            val elapsed = now - stageStartedAt
            when (stage) {
                Stage.SELECTION -> selectionMs += elapsed
                Stage.RESOLUTION -> resolutionMs += elapsed
                Stage.PREPARATION -> preparationMs += elapsed
                Stage.RETRY_WAIT -> Unit
            }
            stage = next
            stageStartedAt = now
        }
    }

    private var attempt: Attempt? = null
    private var hasPlayed = false
    private var bufferingSince: Long? = null

    fun start(trigger: PlaybackTrigger) {
        cancelAttempt()
        finishBuffering(if (trigger == PlaybackTrigger.RECOVERY) Outcome.RECOVERING else Outcome.CANCELLED)
        hasPlayed = false
        attempt = Attempt(trigger, nowMillis())
    }

    fun resolving() { attempt?.enter(Stage.RESOLUTION, nowMillis()) }

    fun preparing() { attempt?.enter(Stage.PREPARATION, nowMillis()) }

    fun retrying(reason: Enum<*>) {
        finishBuffering(Outcome.RECOVERING)
        attempt?.let {
            it.retries++
            it.enter(Stage.RETRY_WAIT, nowMillis())
            emit("event=retry source=$source trigger=${it.trigger} retries=${it.retries} reason=${reason.name}")
        }
    }

    // isPlaying 是媒体引擎进入播放态的时间点，不能测量扬声器的实际出声时间。
    fun playing(completeAttempt: Boolean = true) {
        if (completeAttempt) finishAttempt(Outcome.PLAYING)
        finishBuffering(Outcome.PLAYING)
        hasPlayed = true
    }

    fun buffering() {
        if (!hasPlayed || attempt != null || bufferingSince != null) return
        bufferingSince = nowMillis()
        emit("event=rebuffer_start source=$source")
    }

    fun stalled() { emit("event=stall source=$source") }

    fun failed(reason: Enum<*>) {
        if (attempt == null) emit("event=playback_error source=$source reason=${reason.name}")
        finishAttempt(Outcome.FAILED, reason)
        finishBuffering(Outcome.FAILED)
        hasPlayed = false
    }

    fun cancelAttempt() { finishAttempt(Outcome.CANCELLED) }

    fun reset(reason: Enum<*>? = null) {
        finishAttempt(Outcome.CANCELLED, reason)
        finishBuffering(Outcome.CANCELLED)
        hasPlayed = false
    }

    private fun finishAttempt(outcome: Outcome, reason: Enum<*>? = null) {
        val completed = attempt ?: return
        attempt = null
        val now = nowMillis()
        completed.enter(Stage.RETRY_WAIT, now)
        emit("event=attempt source=$source trigger=${completed.trigger} outcome=$outcome " +
            "reason=${reason?.name ?: "NONE"} retries=${completed.retries} total_ms=${now - completed.startedAt} " +
            "selection_ms=${completed.selectionMs} resolve_ms=${completed.resolutionMs} prepare_ms=${completed.preparationMs}")
    }

    private fun finishBuffering(outcome: Outcome) {
        val startedAt = bufferingSince ?: return
        bufferingSince = null
        emit("event=rebuffer source=$source outcome=$outcome duration_ms=${nowMillis() - startedAt}")
    }

    companion object {
        fun forDebug(source: PlaybackSource): PlaybackDiagnostics? =
            if (BuildConfig.DEBUG) PlaybackDiagnostics(source, SystemClock::elapsedRealtime) { message ->
                Log.d("TunePlayback", message)
            } else null
    }
}
