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

/** 所有中断共用暂停转换，只有暂时丢焦点允许随后自动恢复。 */
internal class PlaybackInterruptionState {
    private var resumeOnFocusGain = false

    fun pause(state: PlayerUiState, reason: PauseReason, playWhenReady: Boolean): PlayerUiState {
        resumeOnFocusGain = reason == PauseReason.TRANSIENT_FOCUS &&
            (resumeOnFocusGain || playWhenReady || state.phase in ResumablePhases)
        return state.copy(phase = PlaybackPhase.PAUSED, error = reason.error, statusCode = null)
    }

    fun consumeFocusResume(): Boolean = resumeOnFocusGain.also { reset() }

    fun reset() {
        resumeOnFocusGain = false
    }
}

private val ResumablePhases = setOf(PlaybackPhase.PLAYING, PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING)
