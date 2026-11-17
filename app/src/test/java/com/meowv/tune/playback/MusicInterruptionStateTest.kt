package com.meowv.tune.playback

import com.meowv.tune.domain.*
import org.junit.Assert.*
import org.junit.Test

class MusicInterruptionStateTest {
    @Test fun eitherRecoveryOrderWaitsForBothNetworkAndFocus() {
        for (networkFirst in listOf(true, false)) {
            val policy = MusicInterruptionState { 0 }
            val focused = policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.TRANSIENT_FOCUS, true)
            policy.pause(focused, PauseReason.NETWORK, false)
            assertFalse(if (networkFirst) policy.networkAvailable() else policy.focusGained())
            assertTrue(if (networkFirst) policy.focusGained() else policy.networkAvailable())
            assertFalse(policy.networkAvailable())
            assertFalse(policy.focusGained())
        }
    }
    @Test fun networkThenFocusLossAlsoKeepsThePendingIntent() {
        val policy = MusicInterruptionState { 0 }
        val paused = policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.NETWORK, true)
        policy.pause(paused, PauseReason.TRANSIENT_FOCUS, false)
        assertFalse(policy.networkAvailable())
        assertTrue(policy.focusGained())
    }
    @Test fun explicitInterruptionsAndTimeoutCancelAutomaticResume() {
        for (reason in listOf(PauseReason.USER, PauseReason.OUTPUT, PauseReason.PERMANENT_FOCUS)) {
            val policy = MusicInterruptionState { 0 }
            val paused = policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.NETWORK, true)
            policy.pause(paused, PauseReason.TRANSIENT_FOCUS, false)
            policy.pause(paused, reason, false)
            assertFalse(policy.networkAvailable()); assertFalse(policy.focusGained())
        }
        var now = 0L
        val policy = MusicInterruptionState { now }
        policy.pause(PlayerUiState(PlaybackPhase.PLAYING), PauseReason.NETWORK, true)
        now = 30001
        assertFalse(policy.networkAvailable())
    }
    @Test fun pausedContentAndRepeatedCallbacksNeverCreateAResumeIntent() {
        val policy = MusicInterruptionState { 0 }
        policy.pause(PlayerUiState(PlaybackPhase.PAUSED), PauseReason.TRANSIENT_FOCUS, false)
        assertFalse(policy.focusGained())
        policy.pause(PlayerUiState(PlaybackPhase.RESOLVING), PauseReason.NETWORK, false)
        assertTrue(policy.networkAvailable())
        assertFalse(policy.networkAvailable())
    }
}
