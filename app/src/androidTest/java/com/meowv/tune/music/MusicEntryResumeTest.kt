package com.meowv.tune.music

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.music.MusicRepository
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.PlayableAudio
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 「自动播放」是全局开关：关闭时启动只恢复音乐上下文，不解析音源、不自动发声。
class MusicEntryResumeTest {
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication

    @Test
    fun autoPlayOffRestoresPausedContextWithoutStarting() = runBlocking<Unit> {
        val track = MusicTrack("entry-1", "入口恢复测试", listOf("qix"), durationMs = 12_000)
        val resolves = AtomicInteger()
        val fake = FakeMusicRepository(listOf(track))
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
                resolves.incrementAndGet()
                return PlayableAudio(trackId, "test", trackId, 1.0,
                    "https://test.example/radio.mp3", "audio/mpeg", 12_000, seekable = true)
            }
        }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        app.userSettings.update { it.copy(autoPlay = false) }
        withContext(Dispatchers.Main) {
            app.musicResume.save(MusicResumePoint(track, 4_000, playOnEntry = true))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val connection = app.playerController
                withTimeout(6_000) { connection.connected.first { it } }
                val paused = withTimeout(6_000) {
                    connection.state.first {
                        it.phase == PlaybackPhase.PAUSED && it.music?.id == track.id
                    }
                }
                assertTrue("恢复进度应为 4 秒，实际 ${paused.positionMs}",
                    (paused.positionMs ?: 0) >= 4_000)
                assertEquals("关闭自动播放时不应解析音源", 0, resolves.get())

                // 用户手动继续后才真正解析并播放。
                connection.dispatch(PlayerCommand.TogglePause)
                withTimeout(6_000) {
                    connection.state.first {
                        it.phase == PlaybackPhase.PLAYING && it.music?.id == track.id
                    }
                }
                assertTrue("手动继续应触发音源解析", resolves.get() > 0)
                connection.dispatch(PlayerCommand.Stop)
            }
        } finally {
            app.testMusicRepository = null
            withContext(Dispatchers.Main) { app.musicResume.save(null) }
        }
    }
}
