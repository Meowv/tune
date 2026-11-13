package com.meowv.tune.playback

import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.TuneError

internal enum class PauseReason(val error: TuneError?) {
    USER(null),
    NETWORK(TuneError.NETWORK_OFFLINE),
    OUTPUT(TuneError.AUDIO_DISCONNECTED),
    TRANSIENT_FOCUS(TuneError.AUDIO_FOCUS_LOST),
    PERMANENT_FOCUS(TuneError.AUDIO_FOCUS_LOST)
}

/** 暂时丢焦点和短时断网可恢复；用户暂停及输出断开会撤销恢复意图。 */
internal class PlaybackInterruptionState(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var resumeOnFocusGain = false
    private var networkPauseAt: Long? = null
    val hasNetworkResume: Boolean get() = networkPauseAt != null

    fun pause(state: PlayerUiState, reason: PauseReason, playWhenReady: Boolean): PlayerUiState {
        networkPauseAt = if (reason == PauseReason.NETWORK) {
            networkPauseAt ?: nowMillis().takeIf {
                playWhenReady || state.phase in ResumablePhases || resumeOnFocusGain
            }
        } else null
        resumeOnFocusGain = reason == PauseReason.TRANSIENT_FOCUS &&
            (resumeOnFocusGain || playWhenReady || state.phase in ResumablePhases)
        return state.copy(phase = PlaybackPhase.PAUSED, error = reason.error, statusCode = null)
    }

    fun consumeFocusResume(): Boolean = resumeOnFocusGain.also { resumeOnFocusGain = false }

    fun consumeNetworkResume(): Boolean {
        val since = networkPauseAt ?: return false
        networkPauseAt = null
        return nowMillis() - since in 0..30_000L
    }

    fun reset() {
        resumeOnFocusGain = false
        networkPauseAt = null
    }
}

private val ResumablePhases = setOf(PlaybackPhase.PLAYING, PlaybackPhase.RESOLVING,
    PlaybackPhase.BUFFERING, PlaybackPhase.RETRYING)
