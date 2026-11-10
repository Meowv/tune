package com.meowv.tune.playback

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerControllerTest {
    @Test
    fun timerUsesMonotonicClockAndExpiresOnce() = runBlocking {
        val now = AtomicLong(10_000L)
        val expired = AtomicInteger()
        val timer = SleepTimerController(
            onExpired = { expired.incrementAndGet() },
            nowMillis = now::get,
            tickMillis = 5L,
            minutesToMillis = { it.toLong() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
        try {
            timer.start(20)
            assertTrue(timer.state.value.isActive)
            assertEquals(20L, timer.state.value.remainingMillis)

            now.addAndGet(20L)
            withTimeout(1_000L) {
                timer.state.first { !it.isActive }
            }
            assertEquals(1, expired.get())
        } finally {
            timer.close()
        }
    }

    @Test
    fun cancelAndRangeValidationKeepStateConsistent() {
        val timer = SleepTimerController(
            onExpired = {},
            nowMillis = { 0L },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
        try {
            timer.start(30)
            assertTrue(timer.state.value.isActive)
            timer.cancel()
            assertFalse(timer.state.value.isActive)

            assertFalse(runCatching { timer.start(0) }.isSuccess)
            assertFalse(runCatching { timer.start(481) }.isSuccess)
        } finally {
            timer.close()
        }
    }

    @Test
    fun remainingMinutesRoundUpForDisplay() {
        assertEquals(0, sleepTimerRemainingMinutes(0L))
        assertEquals(1, sleepTimerRemainingMinutes(1L))
        assertEquals(1, sleepTimerRemainingMinutes(60_000L))
        assertEquals(2, sleepTimerRemainingMinutes(60_001L))
        assertEquals(30, sleepTimerRemainingMinutes(29 * 60_000L + 1L))
    }
}
