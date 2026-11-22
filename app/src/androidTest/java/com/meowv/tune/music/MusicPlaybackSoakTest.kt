package com.meowv.tune.music

import android.app.ActivityManager
import android.content.ComponentName
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.*
import com.meowv.tune.playback.RadioPlaybackService
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackSoakTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController

    @Test fun rollingRecommendationsContinueAcrossThirtyTrackEnds() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicSoak") == "true")
        val tracks = (1..45).map { MusicTrack("rolling-soak-$it", "Rolling soak $it", listOf("test")) }
        val audio = tracks.associate { track ->
            track.id to PlayableAudio(track.id, "test", track.id, 1.0,
                "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
        }
        val fake = FakeMusicRepository(tracks, audio)
        val rollingPages = AtomicInteger()
        val resolves = AtomicInteger()
        app.testMusicRepository = null
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun candidates(limit: Int, cursor: String?): MusicPage {
                rollingPages.incrementAndGet()
                return fake.recommendations(minOf(5, limit), cursor)
            }

            override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
                resolves.incrementAndGet()
                return fake.resolve(trackId, preferredProviderId)
            }
        }
        app.musicResume.save(null)
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = PlaybackOrder.SEQUENTIAL) }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        val samples = JSONArray()
        val transitions = mutableListOf<Long>()
        val played = linkedSetOf<String>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(5000) { connection.connected.first { it } }
                val seed = MusicQueueSeed(tracks.take(5), "fake:5", rolling = true)
                connection.dispatch(PlayerCommand.PlayMusic(seed = seed))
                var playing = withTimeout(8000) {
                    connection.state.first { state ->
                        state.phase == PlaybackPhase.PLAYING && seed.tracks.any { it.id == state.music?.id }
                    }
                }
                played.add(requireNotNull(playing.music).id)
                repeat(30) { index ->
                    val previous = requireNotNull(playing.music).id
                    assertTrue("Fixture must be seekable", playing.canSeek)
                    val before = SystemClock.elapsedRealtime()
                    // 快进到末尾，等播放器实际读到 EOF 并自动选下一首。
                    connection.dispatch(PlayerCommand.Seek(11_000L))
                    playing = withTimeout(8000) {
                        connection.state.first { state ->
                            state.source == PlaybackSource.MUSIC && state.phase == PlaybackPhase.PLAYING &&
                                state.music != null && state.music.id != previous
                        }
                    }
                    val trackId = requireNotNull(playing.music).id
                    assertTrue("Rolling feed must select a fixture track", audio.containsKey(trackId))
                    assertTrue("Rolling feed repeated a track at transition ${index + 1}", played.add(trackId))
                    assertNull(playing.musicFailure)
                    transitions += SystemClock.elapsedRealtime() - before
                    if ((index + 1) % 10 == 0) sample(samples, "eof-${index + 1}", resolves.get())
                }
                assertTrue("Rolling feed must replenish beyond the seed page", rollingPages.get() >= 6)
                val result = JSONObject().put("transitions", transitions.size).put("uniqueTracks", played.size)
                    .put("rollingPages", rollingPages.get()).put("resolves", resolves.get())
                    .put("eofSeekToNextMs", JSONArray(transitions))
                    .put("samples", samples)
                File(app.filesDir, "music-rolling-soak-result.json").writeText(result.toString(2))
            }
        } finally {
            main { connection.stopActive() }
            app.testMusicRepository = null
        }
    }

    @Test fun repeatedSourceSwitchesAndBackgroundKeepOneSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicSoak") == "true")
        val tracks = (1..200).map { MusicTrack("soak-$it", "Soak $it", listOf("test")) }
        val fake = FakeMusicRepository(tracks)
        val resolves = AtomicInteger()
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
                resolves.incrementAndGet()
                return PlayableAudio(trackId, "test", trackId, 1.0,
                    "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
            }
        }
        app.userSettings.update { it.copy(autoPlay = false, scope = RadioScope(ScopeMode.COUNTRY, app.country)) }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        val samples = JSONArray()
        val latencies = mutableListOf<Long>()
        val started = SystemClock.elapsedRealtime()
        var media: MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(5000) { connection.connected.first { it } }
                withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }
                val future = main {
                    MediaController.Builder(app, SessionToken(app,
                        ComponentName(app, RadioPlaybackService::class.java))).buildAsync()
                }
                val controller = future.get().also { media = it }
                val token = main { controller.connectedToken }
                // 预热不计入100次切源，单独保留首次播放耗时。
                val coldStart = SystemClock.elapsedRealtime()
                connection.dispatch(PlayerCommand.PlayMusic(tracks[0]))
                awaitSource(PlaybackSource.MUSIC)
                val firstPlayMs = SystemClock.elapsedRealtime() - coldStart
                sample(samples, "warm", resolves.get())
                repeat(100) { index ->
                    val source = if (index % 2 == 0) PlaybackSource.RADIO else PlaybackSource.MUSIC
                    val before = SystemClock.elapsedRealtime()
                    if (source == PlaybackSource.RADIO) {
                        connection.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
                    } else connection.dispatch(PlayerCommand.PlayMusic(tracks[index / 2 + 1]))
                    awaitSource(source)
                    latencies += SystemClock.elapsedRealtime() - before
                    assertEquals(token, main { controller.connectedToken })
                    if ((index + 1) % 20 == 0) sample(samples, "switch-${index + 1}", resolves.get())
                }
                // 关闭额外控制器并释放Activity租约，后台仅靠产品前台播放服务存活。
                main { controller.release() }; media = null
                scenario.moveToState(Lifecycle.State.CREATED)
                withTimeout(5000) { connection.connected.first { !it } }
                val beforeBackground = resolves.get()
                repeat(12) { index ->
                    delay(10_000)
                    val state = connection.state.value
                    assertEquals(PlaybackSource.MUSIC, state.source)
                    assertTrue("Background playback must remain active", state.phase in
                        listOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.RESOLVING))
                    assertNull(state.musicFailure)
                    sample(samples, "background-${(index + 1) * 10}s", resolves.get())
                }
                assertTrue("Background EOF must continue to another track", resolves.get() > beforeBackground)
                scenario.moveToState(Lifecycle.State.RESUMED)
                withTimeout(5000) { connection.connected.first { it } }
                awaitSource(PlaybackSource.MUSIC)
                val reconnect = main {
                    MediaController.Builder(app, SessionToken(app,
                        ComponentName(app, RadioPlaybackService::class.java))).buildAsync()
                }.get().also { media = it }
                assertEquals(token, main { reconnect.connectedToken })
                sample(samples, "foreground", resolves.get())
                val sorted = latencies.sorted()
                val result = JSONObject().put("pid", Process.myPid()).put("switches", latencies.size)
                    .put("firstPlayMs", firstPlayMs).put("switchP95Ms", sorted[94])
                    .put("switchMaxMs", sorted.last()).put("backgroundSeconds", 120)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("samples", samples).put("switchLatencyMs", JSONArray(latencies))
                File(app.filesDir, "music-soak-result.json").writeText(result.toString(2))
                connection.dispatch(PlayerCommand.Stop)
                main { media?.release() }; media = null
            }
        } finally {
            main { connection.stopActive(); media?.release() }
            app.testMusicRepository = null
        }
    }

    private suspend fun awaitSource(source: PlaybackSource) = withTimeout(6000) {
        connection.state.first { it.source == source && it.phase == PlaybackPhase.PLAYING }
    }

    private fun sample(samples: JSONArray, phase: String, resolves: Int) {
        // 观察值不是泄漏判定；不强制GC，避免把测试干预后的堆大小当作实际占用。
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val manager = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val services = manager.getRunningServices(Int.MAX_VALUE).count {
            it.service.className == RadioPlaybackService::class.java.name
        }
        assertEquals(1, services)
        samples.put(JSONObject().put("phase", phase).put("elapsedMs", SystemClock.elapsedRealtime())
            .put("pssKb", info.totalPss).put("javaUsedKb", (runtime.totalMemory() - runtime.freeMemory()) / 1024)
            .put("nativeAllocatedKb", Debug.getNativeHeapAllocatedSize() / 1024)
            .put("fds", File("/proc/self/fd").list()?.size)
            .put("threads", File("/proc/self/task").list()?.size)
            .put("services", services).put("resolves", resolves))
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
