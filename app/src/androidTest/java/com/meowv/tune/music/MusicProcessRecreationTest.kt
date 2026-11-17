package com.meowv.tune.music

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.music.MusicRepository
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.PlayableAudio
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicProcessRecreationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val marker get() = File(app.filesDir, "music-process-test.pid")
    private val track = MusicTrack("process-original", "Process lifecycle test", listOf("qix"))
    private val resolves = AtomicInteger()

    @Test fun preparePlayingProcess() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicProcessPhase") == "prepare")
        installRepository()
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            connection.dispatch(PlayerCommand.PlayMusic(track))
            awaitPlaying()
            marker.writeText(Process.myPid().toString())
            // 保持播放直到本轮仪器进程退出，仅供独立的两阶段进程测试。
            instrumentation.runOnMainSync { connection.acquire() }
        }
        assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
    }

    @Test fun newProcessStartsIdleUntilExplicitMusicRequest() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicProcessPhase") == "verify")
        assertTrue("Run prepare phase before terminating the app process", marker.exists())
        assertNotEquals(marker.readText().toInt(), Process.myPid())
        installRepository()
        assertFalse(app.userSettings.settings.first().autoPlay)
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            delay(500)
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            assertNull(connection.state.value.music)
            assertNull(connection.state.value.station)
            assertEquals(0, resolves.get())
            assertFalse(connection.state.value.canPrevious)
            try {
                connection.dispatch(PlayerCommand.PlayMusic(track))
                awaitPlaying()
                assertEquals(1, resolves.get())
            } finally { connection.dispatch(PlayerCommand.Stop) }
        }
        assertTrue(marker.delete())
        app.testMusicRepository = null
    }

    private fun installRepository() {
        val fake = FakeMusicRepository(listOf(track))
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
                resolves.incrementAndGet()
                return PlayableAudio(trackId, "test", trackId, 1.0,
                    "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
            }
        }
    }

    private suspend fun awaitPlaying() = withTimeout(6000) {
        connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == track.id }
    }
}
