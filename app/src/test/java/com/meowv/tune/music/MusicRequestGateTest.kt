package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import org.junit.Assert.*
import org.junit.Test

class MusicRequestGateTest {
    @Test fun cooldownCannotBeBypassedAndExpiresWithoutAnAutomaticRequest() {
        var now = 0L
        val gate = MusicRequestGate { now }
        gate.check()
        gate.block(30)
        repeat(3) { assertEquals(30L, blocked(gate).retryAfterSeconds) }
        now = 29500
        assertEquals(1L, blocked(gate).retryAfterSeconds)
        now = 30000
        gate.check()
    }
    @Test fun longerLimitsWinAndOverflowNeverReopensTheGate() {
        var now = 0L
        val gate = MusicRequestGate { now }
        gate.block(30); now = 1000; gate.block(1)
        assertEquals(29L, blocked(gate).retryAfterSeconds)
        gate.block(Long.MAX_VALUE)
        assertEquals(MusicFailure.RATE_LIMITED, blocked(gate).failure)
    }
    @Test fun retryAfterSupportsHttpDateAndSeconds() {
        val now = java.time.Instant.parse("2026-11-16T00:00:00Z")
        assertEquals(30L, musicRetryAfterSeconds("30", now))
        assertEquals(30L, musicRetryAfterSeconds("Mon, 16 Nov 2026 00:00:30 GMT", now))
        assertNull(musicRetryAfterSeconds("invalid", now))
    }
    @Test fun unavailableCooldownRetainsItsClassificationAndDoesNotShortenAnExistingWindow() {
        var now = 0L
        val gate = MusicRequestGate { now }
        gate.block(15, MusicFailure.SOURCE_UNAVAILABLE)
        gate.block(1)
        assertEquals(MusicFailure.SOURCE_UNAVAILABLE, blocked(gate).failure)
        now = 15000
        gate.check()
        gate.block(15, MusicFailure.SOURCE_TIMEOUT)
        assertEquals(MusicFailure.SOURCE_TIMEOUT, blocked(gate).failure)
    }
    private fun blocked(gate: MusicRequestGate): MusicException {
        try { gate.check(); error("Expected cooldown") } catch (failure: MusicException) { return failure }
    }
}
