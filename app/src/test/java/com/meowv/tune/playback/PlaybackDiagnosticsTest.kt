package com.meowv.tune.playback

import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.TuneError
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDiagnosticsTest {
    private var now = 0L
    private val events = mutableListOf<String>()
    private val diagnostics = PlaybackDiagnostics(PlaybackSource.RADIO, { now }) { events.add(it) }

    @Test fun retryAccumulatesWorkButDoesNotCountBackoffAsResolution() {
        diagnostics.start(PlaybackTrigger.NEXT)
        now = 10L
        diagnostics.resolving()
        now = 30L
        diagnostics.retrying(TuneError.TIMEOUT)
        now = 230L
        diagnostics.resolving()
        now = 260L
        diagnostics.preparing()
        now = 300L
        diagnostics.playing()
        diagnostics.playing()

        assertEquals(listOf(
            "event=retry source=RADIO trigger=NEXT retries=1 reason=TIMEOUT",
            "event=attempt source=RADIO trigger=NEXT outcome=PLAYING reason=NONE retries=1 " +
                "total_ms=300 selection_ms=10 resolve_ms=50 prepare_ms=40"
        ), events)
    }

    @Test fun playingOldMediaDoesNotCompleteAnInFlightSwitch() {
        diagnostics.start(PlaybackTrigger.PREVIOUS)
        now = 10L
        diagnostics.playing(completeAttempt = false)
        diagnostics.buffering()
        now = 22L
        diagnostics.failed(TuneError.CONNECTION)

        assertEquals(listOf(
            "event=attempt source=RADIO trigger=PREVIOUS outcome=FAILED reason=CONNECTION retries=0 " +
                "total_ms=22 selection_ms=22 resolve_ms=0 prepare_ms=0"
        ), events)
    }

    @Test fun repeatedBufferingCallbacksOnlyRecordOneInterruption() {
        diagnostics.start(PlaybackTrigger.PLAY)
        diagnostics.preparing()
        diagnostics.buffering()
        assertEquals(emptyList<String>(), events)
        now = 100L
        diagnostics.playing()
        now = 150L
        diagnostics.buffering()
        now = 165L
        diagnostics.buffering()
        now = 180L
        diagnostics.playing()

        assertEquals(3, events.size)
        assertEquals("event=rebuffer_start source=RADIO", events[1])
        assertEquals("event=rebuffer source=RADIO outcome=PLAYING duration_ms=30", events.last())
    }

    @Test fun replacingAnAttemptReportsCancellationAndStartsAFreshClock() {
        diagnostics.start(PlaybackTrigger.PLAY)
        now = 200L
        diagnostics.resolving()
        now = 240L
        diagnostics.start(PlaybackTrigger.NEXT)
        now = 300L
        diagnostics.playing()

        assertEquals(listOf(
            "event=attempt source=RADIO trigger=PLAY outcome=CANCELLED reason=NONE retries=0 " +
                "total_ms=240 selection_ms=200 resolve_ms=40 prepare_ms=0",
            "event=attempt source=RADIO trigger=NEXT outcome=PLAYING reason=NONE retries=0 " +
                "total_ms=60 selection_ms=60 resolve_ms=0 prepare_ms=0"
        ), events)
    }
}
