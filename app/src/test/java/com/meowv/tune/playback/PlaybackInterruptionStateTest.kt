package com.meowv.tune.playback

import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.TuneError
import org.junit.Assert.*
import org.junit.Test

class PlaybackInterruptionStateTest {
    @Test fun briefNetworkLossResumesOnceButLongLossDoesNot() {
        var now = 0L
        val policy = PlaybackInterruptionState { now }
        val playing = PlayerUiState(PlaybackPhase.PLAYING)
        val paused = policy.pause(playing, PauseReason.NETWORK, true)
        now = 20_000
        policy.pause(paused, PauseReason.NETWORK, false)
        now = 30_000
        assertTrue(policy.consumeNetworkResume())
        assertFalse(policy.consumeNetworkResume())
        policy.pause(playing, PauseReason.NETWORK, true)
        now += 30_001
        assertFalse(policy.consumeNetworkResume())
    }

    @Test fun explicitInterruptionsCancelNetworkResumeAndFocusGainDoesNotConsumeIt() {
        listOf(PauseReason.USER, PauseReason.OUTPUT, PauseReason.PERMANENT_FOCUS).forEach { reason ->
            val policy = PlaybackInterruptionState { 0L }
            val paused = policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.NETWORK, true)
            policy.pause(paused, reason, false)
            assertFalse(policy.consumeNetworkResume())
        }
        val policy = PlaybackInterruptionState { 0L }
        policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.NETWORK, true)
        assertFalse(policy.consumeFocusResume())
        assertTrue(policy.consumeNetworkResume())
        policy.pause(PlayerUiState(PlaybackPhase.PAUSED), PauseReason.NETWORK, false)
        assertFalse(policy.consumeNetworkResume())
    }

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
