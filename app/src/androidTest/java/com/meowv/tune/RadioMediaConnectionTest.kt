package com.meowv.tune

import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.Station
import java.util.UUID
import com.meowv.tune.playback.RadioPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioMediaConnectionTest {
    @Test fun initialConnectionFailureRetriesAndConnects() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TuneApplication
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        lateinit var connection: com.meowv.tune.playback.RadioMediaConnection
        lateinit var lease: AutoCloseable
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync {
                connection = com.meowv.tune.playback.RadioMediaConnection(app) { context, token, listener ->
                    if (attempts.incrementAndGet() == 1) {
                        com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>().apply {
                            setException(java.io.IOException("Injected connection failure"))
                        }
                    } else {
                        androidx.media3.session.MediaController.Builder(context, token).setListener(listener).buildAsync()
                    }
                }
                lease = connection.acquire()
            }
            try {
                withTimeout(5000) { connection.connected.first { it } }
                assertEquals(2, attempts.get())
            } finally {
                instrumentation.runOnMainSync { lease.close() }
            }
        }
    }

    @Test fun releasingLastLeaseCancelsConnectionRetry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        instrumentation.runOnMainSync {
            val connection = com.meowv.tune.playback.RadioMediaConnection(instrumentation.targetContext) { _, _, _ ->
                attempts.incrementAndGet()
                com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>().apply {
                    setException(java.io.IOException("Injected connection failure"))
                }
            }
            connection.acquire().close()
        }
        delay(1000)
        assertEquals(1, attempts.get())
    }

    @Test fun playsMp3AacAndHlsAndCanPauseResumeAndStop() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneApplication
        val connection = app.playerController
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10_000) { connection.connected.first { it } }
            withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }
            for ((ordinal, file) in listOf(21L to "radio.mp3", 22L to "radio.aac", 23L to "radio.m3u8")) {
                val station = Station(UUID(0, ordinal), file, null)
                connection.dispatch(PlayerCommand.Play(station))
                withTimeout(8000) { connection.state.first { it.station?.uuid == station.uuid && it.phase == PlaybackPhase.PLAYING } }
                connection.dispatch(PlayerCommand.TogglePause)
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PAUSED } }
                connection.dispatch(PlayerCommand.TogglePause)
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PLAYING } }
                connection.dispatch(PlayerCommand.Stop)
                val stopped = withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.IDLE } }
                assertEquals(null, stopped.station)
            }
        }
    }

    @Test fun activitySharesOneConnectionAndReleasesItWhenStopped() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneApplication
        val connection = app.playerController
        val serviceName = RadioPlaybackService::class.java.name
        val activityManager = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun services() = activityManager.getRunningServices(Int.MAX_VALUE).count { it.service.className == serviceName }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            withTimeout(10_000) { connection.connected.first { it } }
            // 同一进程内的其他用例可能留下播放中的服务，先归零再验证租约行为。
            connection.dispatch(PlayerCommand.Stop)
            withTimeout(10_000) { connection.state.first { it.phase == PlaybackPhase.IDLE } }
            assertEquals(1, services())
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            val lease = arrayOfNulls<AutoCloseable>(1)
            activity.onActivity { lease[0] = connection.acquire() }
            try {
                activity.recreate()
                assertTrue(connection.connected.value)
                assertEquals(1, services())
                connection.dispatch(PlayerCommand.Stop)
            } finally {
                activity.onActivity { lease[0]?.close() }
            }
            assertTrue(connection.connected.value)
            activity.moveToState(Lifecycle.State.CREATED)
            // 最后租约释放后控制器必须断开；ServiceRecord 的回收由系统调度，
            // 这里只要求不出现重复实例，避免把系统回收延迟当成泄漏。
            withTimeout(10_000) {
                connection.connected.first { !it }
                while (services() > 1) delay(25)
            }
            activity.moveToState(Lifecycle.State.RESUMED)
            withTimeout(10_000) { connection.connected.first { it } }
            assertEquals(1, services())
        }
        // 场景关闭后控制器必须断开，且不应留下第二个服务实例。
        withTimeout(10_000) {
            connection.connected.first { !it }
            while (services() > 1) delay(25)
        }
    }
}
