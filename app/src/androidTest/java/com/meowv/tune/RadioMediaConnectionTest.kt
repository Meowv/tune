package com.meowv.tune

import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
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
    @Test fun activitySharesOneConnectionAndReleasesItWhenStopped() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneApplication
        val connection = app.playerController
        val serviceName = RadioPlaybackService::class.java.name
        val activityManager = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun services() = activityManager.getRunningServices(Int.MAX_VALUE).count { it.service.className == serviceName }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            withTimeout(5000) { connection.connected.first { it } }
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
            withTimeout(5000) {
                connection.connected.first { !it }
                while (services() != 0) delay(25)
            }
            activity.moveToState(Lifecycle.State.RESUMED)
            withTimeout(5000) { connection.connected.first { it } }
            assertEquals(1, services())
        }
        withTimeout(5000) {
            connection.connected.first { !it }
            while (services() != 0) delay(25)
        }
    }
}
