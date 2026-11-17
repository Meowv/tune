package com.meowv.tune.playback

import com.meowv.tune.domain.*

// 音乐的焦点和网络是两个独立阻塞条件，只有全部恢复后才能消费续播意图。
internal class MusicInterruptionState(private val nowMillis: () -> Long) {
    private var focusBlocked = false
    private var networkBlocked = false
    private var resumeIntent = false
    private var networkSince: Long? = null
    val hasResumeIntent: Boolean get() = resumeIntent

    fun pause(state: PlayerUiState, reason: PauseReason, playWhenReady: Boolean): PlayerUiState {
        when (reason) {
            PauseReason.USER, PauseReason.OUTPUT, PauseReason.PERMANENT_FOCUS -> reset()
            PauseReason.NETWORK, PauseReason.TRANSIENT_FOCUS -> {
                resumeIntent = resumeIntent || playWhenReady || state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING)
                if (reason == PauseReason.NETWORK) {
                    networkBlocked = true
                    if (resumeIntent && networkSince == null) networkSince = nowMillis()
                } else focusBlocked = true
            }
        }
        return state.copy(phase = PlaybackPhase.PAUSED, error = reason.error, statusCode = null)
    }
    fun focusGained(): Boolean { focusBlocked = false; return consume() }
    fun networkAvailable(): Boolean { networkBlocked = false; return consume() }
    private fun consume(): Boolean {
        if (networkSince?.let { nowMillis() - it !in 0..30_000 } == true) { reset(); return false }
        if (focusBlocked || networkBlocked || !resumeIntent) return false
        reset()
        return true
    }
    fun reset() { focusBlocked = false; networkBlocked = false; resumeIntent = false; networkSince = null }
}
