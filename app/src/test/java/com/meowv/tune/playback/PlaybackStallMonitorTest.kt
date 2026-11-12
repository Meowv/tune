package com.meowv.tune.playback

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackStallMonitorTest {
    @Test fun pausedMonitorStopsPollingAndNewSessionGetsFreshBudget() = runBlocking {
        withTimeout(3000) {
            var now = 1L
            var reads = 0
            var recoveries = 0
            val monitor = PlaybackStallMonitor(this, { now }, {
                reads++
                PlaybackProgress(100, true, true)
            }, { recoveries++ }, intervalMillis = 1)
            try {
                monitor.sync(true)
                while (reads == 0) yield()
                now = 60_000L
                while (recoveries == 0) yield()
                monitor.sync(false)
                val pausedReads = reads
                delay(10)
                assertEquals(pausedReads, reads)
                now = 120_000L
                monitor.sync(true)
                while (reads == pausedReads) yield()
                assertEquals(1, recoveries)
                monitor.reset()
            } finally {
                monitor.reset()
            }
        }
    }
}
