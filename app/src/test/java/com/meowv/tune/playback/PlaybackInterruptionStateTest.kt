package com.meowv.tune.playback

import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.TuneError
import org.junit.Assert.*
import org.junit.Test

class PlaybackInterruptionStateTest {
    @Test fun everyInterruptionEndsResolutionAndClearsHttpStatus() {
        PauseReason.entries.forEach { reason ->
            val policy = PlaybackInterruptionState()
            val paused = policy.pause(PlayerUiState(phase = PlaybackPhase.RESOLVING,
                error = TuneError.HTTP, statusCode = 503), reason, false)
            assertEquals(PlaybackPhase.PAUSED, paused.phase)
            assertEquals(reason.error, paused.error)
            assertNull(paused.statusCode)
            assertEquals(reason == PauseReason.TRANSIENT_FOCUS, policy.consumeFocusResume())
            assertFalse(policy.consumeFocusResume())
        }
    }

    @Test fun manualPauseNetworkAndOutputLossOverrideTransientFocusResume() {
        listOf(PauseReason.USER, PauseReason.NETWORK, PauseReason.OUTPUT, PauseReason.PERMANENT_FOCUS).forEach { reason ->
            val policy = PlaybackInterruptionState()
            val paused = policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.TRANSIENT_FOCUS, true)
            policy.pause(paused, reason, false)
            assertFalse(policy.consumeFocusResume())
        }
    }

    @Test fun repeatedTransientLossPreservesPendingResumeButResetClearsIt() {
        val policy = PlaybackInterruptionState()
        val paused = policy.pause(PlayerUiState(PlaybackPhase.RETRYING), PauseReason.TRANSIENT_FOCUS, false)
        policy.pause(paused, PauseReason.TRANSIENT_FOCUS, false)
        assertTrue(policy.consumeFocusResume())
        policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.TRANSIENT_FOCUS, true)
        policy.reset()
        assertFalse(policy.consumeFocusResume())
    }

    @Test fun transientLossDoesNotResumeAnAlreadyPausedStation() {
        val policy = PlaybackInterruptionState()
        policy.pause(PlayerUiState(PlaybackPhase.PAUSED), PauseReason.TRANSIENT_FOCUS, false)
        assertFalse(policy.consumeFocusResume())
    }
}
