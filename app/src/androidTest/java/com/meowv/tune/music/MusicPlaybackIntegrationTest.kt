package com.meowv.tune.music

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.ActivityManager
import android.os.PowerManager
import java.io.FileInputStream
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.*
import com.meowv.tune.playback.RadioPlaybackService
import com.meowv.tune.playback.AudioFocusEvent
import com.meowv.tune.playback.SleepTimerController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val tracks = (1..3).map { MusicTrack("original-$it", "Original song $it", listOf("qix"), durationMs = 12000) }
    private var catalog = tracks
    private val resolves = ConcurrentHashMap<String, AtomicInteger>()
    private var resolveOverride: (suspend (String) -> Unit)? = null
    private var radioOverride: (() -> List<MusicTrack>)? = null
    private var recommendationsOverride: (suspend (Int, String?) -> MusicPage)? = null
    private var audioOverride: (PlayableAudio, Int, String?) -> PlayableAudio = { audio, _, _ -> audio }
    private var alternateOverride: (suspend (String, String) -> PlayableAudio)? = null
    private var media: MediaController? = null

    @Test fun endOfOrdinaryQueueContinuesWithRadioFallback() = runBlocking {
        val calls = AtomicInteger()
        recommendationsOverride = { _, _ -> MusicPage(listOf(tracks[0])) }
        radioOverride = {
            calls.incrementAndGet()
            listOf(tracks[0], tracks[1])
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            assertEquals(0, calls.get())
            connection.dispatch(PlayerCommand.Seek(11_500))
            awaitMusic(2)
            assertEquals(1, calls.get())
        }
    }

    @Test fun musicSeeksContinuesAtEofAndPreviousUsesConfirmedHistory() = runBlocking {
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val controller = connect()
            val token = main { controller.connectedToken }
            val manager = app.getSystemService(NotificationManager::class.java)
            val notification = withTimeout(4000) {
                var result: Notification? = null
                while (result == null) {
                    result = manager.activeNotifications.firstOrNull {
                        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == tracks[0].title
                    }?.notification
                    if (result == null) delay(50)
                }
                result
            }
            assertEquals(listOf("上一曲", "暂停", "下一曲"), notification.actions.map { it.title.toString() })
            assertTrue(main { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
            main { controller.pause() }
            awaitPhase(PlaybackPhase.PAUSED)
            main { controller.seekTo(4000) }
            withTimeout(3000) { connection.state.first { (it.positionMs ?: 0) >= 3500 } }
            main { controller.play() }
            awaitMusic(1)
            // 让Media3实际读到EOF，而不是直接调用业务Next模拟歌曲结束。
            main { controller.seekTo(11000) }
            awaitMusic(2)
            assertEquals(token, main { controller.connectedToken })
            assertEquals(1, resolves[tracks[1].id]?.get())
            assertTrue(connection.state.value.canPrevious)
            main { controller.seekToPreviousMediaItem() }
            awaitMusic(1)
        }
    }

    @Test fun radioAndMusicShareOneSessionWithSourceSpecificSeek() = runBlocking {
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val controller = connect()
            val token = main { controller.connectedToken }
            connection.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
            withTimeout(6000) { connection.state.first { it.source == PlaybackSource.RADIO && it.phase == PlaybackPhase.PLAYING } }
            assertNull(connection.state.value.music)
            assertFalse(main { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
            connection.dispatch(PlayerCommand.PlayMusic(tracks[1]))
            awaitMusic(2)
            assertNull(connection.state.value.station)
            assertEquals(token, main { controller.connectedToken })
        }
    }

    @Test fun rapidNextRejectsLateUncancellableResolveResults() = runBlocking {
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val started = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        resolveOverride = { id ->
            val index = tracks.indexOfFirst { it.id == id }
            if (index < 2) { started[index].complete(Unit); withContext(NonCancellable) { gates[index].await() } }
        }
        try {
            withMusic {
                connection.dispatch(PlayerCommand.PlayMusic())
                withTimeout(4000) { started[0].await() }
                connection.dispatch(PlayerCommand.Next)
                withTimeout(4000) { started[1].await() }
                connection.dispatch(PlayerCommand.Next)
                awaitMusic(3)
                gates.forEach { it.complete(Unit) }
                delay(400)
                assertEquals(tracks[2].id, connection.state.value.music?.id)
                assertFalse(connection.state.value.canPrevious)
            }
        } finally { gates.forEach { it.complete(Unit) } }
    }

    @Test fun switchingToRadioWhileResolvingCannotBeOverwritten() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        resolveOverride = { started.complete(Unit); withContext(NonCancellable) { gate.await() } }
        try {
            withMusic {
                connection.dispatch(PlayerCommand.PlayMusic())
                withTimeout(4000) { started.await() }
                connection.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
                withTimeout(6000) { connection.state.first { it.source == PlaybackSource.RADIO && it.phase == PlaybackPhase.PLAYING } }
                gate.complete(Unit); delay(400)
                assertEquals(PlaybackSource.RADIO, connection.state.value.source)
                assertNull(connection.state.value.music)
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun userPauseDuringNetworkLossPreventsRecoveryAndSleepStopsWithoutNext() = runBlocking {
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val controller = connect()
            app.mockNetwork.value = NetworkState()
            withTimeout(4000) { connection.state.first { it.error == TuneError.NETWORK_OFFLINE } }
            main { controller.pause() }
            app.mockNetwork.value = NetworkState(NetworkType.WIFI)
            delay(400)
            assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
            main { controller.play() }
            awaitMusic(1)
            val timer = SleepTimerController(onExpired = { connection.stopActive() }, tickMillis = 5, minutesToMillis = { 100 })
            try {
                timer.start(1)
                awaitPhase(PlaybackPhase.IDLE)
                delay(300)
                assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
                assertEquals(null, resolves[tracks[1].id])
                connection.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
                withTimeout(6000) { connection.state.first { it.source == PlaybackSource.RADIO && it.phase == PlaybackPhase.PLAYING } }
                timer.start(1)
                awaitPhase(PlaybackPhase.IDLE)
                assertEquals(PlaybackSource.RADIO, connection.state.value.source)
            } finally { timer.close() }
        }
    }

    @Test fun failedPreviousKeepsCurrentAudioAndHistory() = runBlocking {
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Next)
            awaitMusic(2)
            resolveOverride = { id -> if (id == tracks[0].id) throw MusicException(MusicFailure.FORBIDDEN) }
            connection.dispatch(PlayerCommand.Previous)
            withTimeout(4000) { connection.state.first { it.operationFailure != null } }
            assertEquals(tracks[1].id, connection.state.value.music?.id)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            assertTrue(connection.state.value.canPrevious)
            connection.dispatch(PlayerCommand.TogglePause)
            awaitPhase(PlaybackPhase.PAUSED)
            connection.dispatch(PlayerCommand.Previous)
            withTimeout(4000) { connection.state.first { it.operationFailure != null } }
            assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
            connection.dispatch(PlayerCommand.TogglePause)
            awaitMusic(2)
        }
    }

    @Test fun musicKeepsPlayingAcrossActivityRecreation() = runBlocking {
        withMusic { scenario ->
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val controller = connect()
            val token = main { controller.connectedToken }
            scenario.recreate()
            withTimeout(4000) { connection.connected.first { it } }
            assertEquals(token, main { controller.connectedToken })
            assertEquals(1, resolves[tracks[0].id]?.get())
            assertEquals(PlaybackSource.MUSIC, connection.state.value.source)
        }
    }

    @Test fun consecutiveFailuresStopAtFiveAndRateLimitsNeverSkipAroundTheLimit() = runBlocking {
        catalog = (1..20).map { MusicTrack("failed-$it", "Failed $it", listOf("qix")) }
        resolveOverride = { throw MusicException(MusicFailure.SOURCE_UNAVAILABLE) }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(5, resolves.values.sumOf { it.get() })
            delay(300)
            assertEquals(5, resolves.values.sumOf { it.get() })
        }
        resolves.clear()
        resolveOverride = { throw MusicException(MusicFailure.RATE_LIMITED, 1) }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(MusicFailure.RATE_LIMITED, connection.state.value.musicFailure)
            assertEquals(1, resolves.values.sumOf { it.get() })
            repeat(3) {
                connection.dispatch(PlayerCommand.Next)
                connection.dispatch(PlayerCommand.TogglePause)
            }
            delay(200)
            assertEquals(1, resolves.values.sumOf { it.get() })
            delay(1100) // 等待本用例的一秒冷却自然结束，避免污染其他服务集成用例。
        }
    }

    @Test fun focusAndNetworkRecoverOnlyAfterBothConditionsAreReady() = runBlocking {
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val engine = requireNotNull(connection.coordinator).music
            for (networkFirst in listOf(true, false)) {
                main { engine.onAudioFocusEvent(AudioFocusEvent.TRANSIENT_LOSS) }
                awaitPhase(PlaybackPhase.PAUSED)
                app.mockNetwork.value = NetworkState()
                withTimeout(3000) { connection.state.first { it.error == TuneError.NETWORK_OFFLINE } }
                if (networkFirst) app.mockNetwork.value = NetworkState(NetworkType.WIFI)
                else main { engine.onAudioFocusEvent(AudioFocusEvent.GAIN) }
                delay(200)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
                if (networkFirst) main { engine.onAudioFocusEvent(AudioFocusEvent.GAIN) }
                else app.mockNetwork.value = NetworkState(NetworkType.WIFI)
                awaitMusic(1)
            }
        }
    }

    @Test fun userAndOutputInterruptionsCancelCombinedRecovery() = runBlocking {
        withMusic {
            val engine = requireNotNull(connection.coordinator).music
            for (output in listOf(false, true)) {
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitMusic(1)
                main { engine.onAudioFocusEvent(AudioFocusEvent.TRANSIENT_LOSS) }
                awaitPhase(PlaybackPhase.PAUSED)
                app.mockNetwork.value = NetworkState()
                withTimeout(3000) { connection.state.first { it.error == TuneError.NETWORK_OFFLINE } }
                if (output) main { engine.onExternalOutputRemoved() } else main { engine.pause() }
                app.mockNetwork.value = NetworkState(NetworkType.WIFI)
                main { engine.onAudioFocusEvent(AudioFocusEvent.GAIN) }
                delay(250)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
            }
        }
    }

    @Test fun musicContinuesInBackgroundWithoutCreatingAnotherSession() = runBlocking {
        withMusic { scenario ->
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val controller = connect()
            val token = main { controller.connectedToken }
            scenario.moveToState(Lifecycle.State.CREATED)
            delay(300)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(4000) { connection.connected.first { it } }
            assertEquals(token, main { controller.connectedToken })
            assertEquals(1, resolves[tracks[0].id]?.get())
        }
    }

    @Test fun rotatingMusicActivityKeepsTheSamePlayingSession() = runBlocking {
        withMusic { scenario ->
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val controller = connect()
            val token = main { controller.connectedToken }
            var original = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            scenario.onActivity { original = it.requestedOrientation }
            try {
                for ((requested, expected) in listOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT
                )) {
                    var previousActivity: MainActivity? = null
                    scenario.onActivity { previousActivity = it; it.requestedOrientation = requested }
                    withTimeout(4000) {
                        var recreated = false
                        while (!recreated) {
                            scenario.onActivity {
                                recreated = it !== previousActivity && it.resources.configuration.orientation == expected
                            }
                            if (!recreated) delay(25)
                        }
                        connection.connected.first { it }
                    }
                    awaitMusic(1)
                    assertEquals(token, main { controller.connectedToken })
                    assertEquals(1, resolves[tracks[0].id]?.get())
                }
            } finally {
                scenario.onActivity { it.requestedOrientation = original }
                withTimeout(4000) { connection.connected.first { it } }
            }
        }
    }

    @Test fun screenOffKeepsMusicAndSystemControlsOnTheSameSession() = runBlocking {
        withMusic { scenario ->
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            val controller = connect()
            val token = main { controller.connectedToken }
            val power = app.getSystemService(PowerManager::class.java)
            try {
                shell("input keyevent KEYCODE_SLEEP")
                withTimeout(4000) { while (power.isInteractive) delay(25) }
                val position = main { controller.currentPosition }
                delay(1000)
                assertTrue(main { controller.isPlaying })
                assertTrue(main { controller.currentPosition } > position + 500)
                main { controller.pause() }
                awaitPhase(PlaybackPhase.PAUSED)
                main { controller.play() }
                awaitMusic(1)
                assertEquals(token, main { controller.connectedToken })
                assertEquals(1, resolves[tracks[0].id]?.get())
            } finally {
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                scenario.moveToState(Lifecycle.State.RESUMED)
                withTimeout(4000) { connection.connected.first { it } }
            }
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test fun newMusicServiceRestoresPausedTrackWithoutStartingIt() = runBlocking {
        withMusic { scenario ->
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Seek(3500))
            withTimeout(4000) { connection.state.first { (it.positionMs ?: 0) >= 3500 } }
            connection.dispatch(PlayerCommand.TogglePause)
            awaitPhase(PlaybackPhase.PAUSED)
            rebuildService(scenario)
            val restored = awaitPhase(PlaybackPhase.PAUSED)
            assertEquals(tracks[0].id, restored.music?.id)
            assertTrue((restored.positionMs ?: 0) >= 3500)
            assertFalse(restored.canPrevious)
            assertEquals(1, resolves.values.sumOf { it.get() })
            connection.dispatch(PlayerCommand.TogglePause)
            assertTrue((awaitMusic(1).positionMs ?: 0) >= 3500)
            assertEquals(2, resolves[tracks[0].id]?.get())
        }
    }

    @Test fun missingRememberedTrackStartsTheNextCandidateFromZero() = runBlocking {
        withMusic { scenario ->
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Seek(3500))
            withTimeout(4000) { connection.state.first { (it.positionMs ?: 0) >= 3500 } }
            connection.dispatch(PlayerCommand.TogglePause)
            awaitPhase(PlaybackPhase.PAUSED)
            rebuildService(scenario)
            awaitPhase(PlaybackPhase.PAUSED)
            resolveOverride = { id ->
                if (id == tracks[0].id) throw MusicException(MusicFailure.TRACK_NOT_FOUND)
            }
            connection.dispatch(PlayerCommand.TogglePause)
            val next = awaitMusic(2)
            assertTrue((next.positionMs ?: -1) in 0..2000)
            assertEquals(tracks[1].id, withContext(Dispatchers.Main) { app.musicResume.read()?.track?.id })
        }
    }

    @Test fun finishedLastTrackClearsTheResumePoint() = runBlocking {
        recommendationsOverride = { _, _ -> MusicPage(listOf(tracks[0])) }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Seek(11_500))
            awaitPhase(PlaybackPhase.ERROR)
            assertNull(withContext(Dispatchers.Main) { app.musicResume.read() })
        }
    }

    @Test fun destroyedServiceRejectsOldUncancellableResolveAfterReconnection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        resolveOverride = { started.complete(Unit); withContext(NonCancellable) { gate.await() } }
        try {
            withMusic { scenario ->
                connection.dispatch(PlayerCommand.PlayMusic())
                withTimeout(4000) { started.await() }
                rebuildService(scenario)
                resolveOverride = null
                connection.dispatch(PlayerCommand.PlayMusic(tracks[1]))
                awaitMusic(2)
                gate.complete(Unit)
                delay(300)
                assertEquals(tracks[1].id, connection.state.value.music?.id)
                assertFalse(connection.state.value.canPrevious)
            }
        } finally { gate.complete(Unit) }
    }

    private suspend fun rebuildService(scenario: ActivityScenario<MainActivity>) {
        val previous = main { connection.coordinator }
        main { media?.release() }; media = null
        scenario.moveToState(Lifecycle.State.CREATED)
        withTimeout(4000) { connection.connected.first { !it } }
        main { app.stopService(Intent(app, RadioPlaybackService::class.java)) }
        withTimeout(5000) { while (main { connection.coordinator } != null) delay(25) }
        assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
        scenario.moveToState(Lifecycle.State.RESUMED)
        withTimeout(5000) { connection.connected.first { it } }
        assertNotSame(previous, main { connection.coordinator })
        val manager = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        assertEquals(1, manager.getRunningServices(Int.MAX_VALUE).count {
            it.service.className == RadioPlaybackService::class.java.name
        })
    }

    @Test fun expiredUrlRefreshesCurrentProviderOnlyOnceBeforePlaying() = runBlocking {
        audioOverride = { audio, attempt, provider ->
            if (attempt == 1) audio.copy(expiresAt = Instant.EPOCH)
            else { assertEquals("test", provider); audio }
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            assertEquals(2, resolves[tracks[0].id]?.get())
            assertFalse(connection.state.value.canPrevious)
        }
    }

    @Test fun repeatedlyExpiredUrlStopsWithoutUnboundedResolve() = runBlocking {
        audioOverride = { audio, _, _ -> audio.copy(expiresAt = Instant.EPOCH) }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(MusicFailure.INVALID_RESPONSE, connection.state.value.musicFailure)
            delay(250)
            assertEquals(2, resolves.values.sumOf { it.get() })
        }
    }

    @Test fun nearlyExpiredUrlRefreshesBeforeAnyMediaRequest() = runBlocking {
        audioOverride = { audio, attempt, provider ->
            if (attempt == 1) audio.copy(
                expiresAt = Instant.now().plusSeconds(3),
                url = "https://test.example/missing"
            ) else {
                assertEquals("test", provider)
                audio
            }
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            assertEquals(2, resolves[tracks[0].id]?.get())
            assertNull(app.musicMediaRequests["missing"])
        }
    }

    @Test fun missingMediaRefreshesSameTrackBeforeAdvancing() = runBlocking {
        audioOverride = { audio, attempt, provider ->
            if (attempt == 1) audio.copy(url = "https://test.example/missing")
            else { assertEquals("test", provider); audio }
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            assertEquals(2, resolves[tracks[0].id]?.get())
            assertNull(resolves[tracks[1].id])
            assertFalse(connection.state.value.canPrevious)
        }
    }

    @Test fun repeatedlyMissingMediaHasOneRefreshPerCandidateAndStops() = runBlocking {
        audioOverride = { audio, _, _ -> audio.copy(url = "https://test.example/missing") }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitPhase(PlaybackPhase.ERROR)
            delay(250)
            assertEquals(6, resolves.values.sumOf { it.get() })
            assertEquals(6, app.musicMediaRequests["missing"]?.get())
            assertTrue(resolves.values.all { it.get() == 2 })
            assertFalse(connection.state.value.canPrevious)
        }
    }

    @Test fun goneMediaRefreshesThenPlaysOneMatchingAlternate() = runBlocking {
        val track = tracks[0].copy(matches = listOf(MusicTrackMatch("test", "primary"), MusicTrackMatch("alternate", "match")))
        audioOverride = { audio, _, _ -> audio.copy(url = "https://test.example/music-gone") }
        alternateOverride = { id, excluded ->
            assertEquals(track.id, id)
            assertEquals("test", excluded)
            assertEquals(2, app.musicMediaRequests["music-gone"]?.get())
            PlayableAudio(id, "alternate", "match", 1.0, "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic(track))
            awaitMusic(1)
            assertEquals(2, resolves[track.id]?.get())
            assertFalse(connection.state.value.canPrevious)
        }
    }

    @Test fun missingAlternateCannotStartAnotherRefreshOrFallback() = runBlocking {
        val attempts = AtomicInteger()
        val track = tracks[0].copy(matches = listOf(MusicTrackMatch("test", "primary"), MusicTrackMatch("alternate", "match")))
        catalog = listOf(track)
        audioOverride = { audio, _, _ -> audio.copy(url = "https://test.example/missing") }
        alternateOverride = { id, _ ->
            attempts.incrementAndGet()
            PlayableAudio(id, "alternate", "match", 1.0, "https://test.example/missing", "audio/mpeg", 12000, seekable = true)
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic(track))
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(1, attempts.get())
            assertEquals(2, resolves[track.id]?.get())
            assertEquals(3, app.musicMediaRequests["missing"]?.get())
        }
    }

    @Test fun forbiddenMediaStopsWithoutRefreshingOrSkipping() = runBlocking {
        audioOverride = { audio, _, _ -> audio.copy(url = "https://test.example/music-forbidden") }
        withMusic {
            val track = tracks[0].copy(matches = listOf(MusicTrackMatch("alternate", "match")))
            alternateOverride = { _, _ -> error("Forbidden media must not request an alternate") }
            connection.dispatch(PlayerCommand.PlayMusic(track))
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(MusicFailure.FORBIDDEN, connection.state.value.musicFailure)
            assertEquals(1, app.musicMediaRequests["music-forbidden"]?.get())
            delay(200)
            assertEquals(1, resolves.values.sumOf { it.get() })
        }
    }

    @Test fun mediaRateLimitBlocksManualRetriesUntilCooldownEnds() = runBlocking {
        audioOverride = { audio, _, _ -> audio.copy(url = "https://test.example/music-limited") }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(MusicFailure.RATE_LIMITED, connection.state.value.musicFailure)
            repeat(3) {
                connection.dispatch(PlayerCommand.Next)
                connection.dispatch(PlayerCommand.TogglePause)
            }
            delay(200)
            assertEquals(1, app.musicMediaRequests["music-limited"]?.get())
            assertEquals(1, resolves.values.sumOf { it.get() })
            delay(1100)
            audioOverride = { audio, _, _ -> audio }
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            assertEquals(2, resolves.values.sumOf { it.get() })
        }
    }

    @Test fun pausedExpiredAudioResolvesAgainOnlyAfterExplicitResume() = runBlocking {
        var expiresAt = Instant.MAX
        audioOverride = { audio, attempt, provider ->
            if (attempt == 1) expiresAt = Instant.now().plusSeconds(6)
            else assertEquals("test", provider)
            audio.copy(expiresAt = expiresAt)
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.TogglePause)
            awaitPhase(PlaybackPhase.PAUSED)
            // 等原资源确实过期，避免仅更改后续解析结果却未覆盖播放器缓存。
            delay((expiresAt.toEpochMilli() - Instant.now().toEpochMilli()).coerceAtLeast(0) + 100)
            assertEquals(1, resolves[tracks[0].id]?.get())
            expiresAt = Instant.now().plusSeconds(60)
            connection.dispatch(PlayerCommand.TogglePause)
            awaitMusic(1)
            assertEquals(2, resolves[tracks[0].id]?.get())
        }
    }

    @Test fun sourceBackoffStopsSkippingAndKeepsItsFailureReasonUntilManualRecovery() = runBlocking {
        withMusic {
            for (reason in listOf(MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.SOURCE_TIMEOUT)) {
                resolves.clear()
                resolveOverride = { throw MusicException(reason, 1) }
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitPhase(PlaybackPhase.ERROR)
                assertEquals(reason, connection.state.value.musicFailure)
                repeat(3) {
                    connection.dispatch(PlayerCommand.Next)
                    connection.dispatch(PlayerCommand.TogglePause)
                }
                delay(100)
                assertEquals(1, resolves.values.sumOf { it.get() })
                assertEquals(reason, connection.state.value.musicFailure)
                delay(1100)
                assertEquals(PlaybackPhase.ERROR, connection.state.value.phase)
                resolveOverride = null
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitMusic(1)
            }
        }
    }

    @Test fun blockedPreviousKeepsCurrentAudioAndDoesNotIssueAnotherResolve() = runBlocking {
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Next)
            awaitMusic(2)
            resolveOverride = { throw MusicException(MusicFailure.SOURCE_UNAVAILABLE, 1) }
            connection.dispatch(PlayerCommand.Previous)
            withTimeout(4000) { connection.state.first { it.operationFailure != null } }
            val first = connection.state.value.operationFailure!!.id
            connection.dispatch(PlayerCommand.Previous)
            withTimeout(4000) { connection.state.first { it.operationFailure?.id != first } }
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            assertEquals(tracks[1].id, connection.state.value.music?.id)
            assertEquals(2, resolves[tracks[0].id]?.get())
            delay(1100)
            resolveOverride = null
            connection.dispatch(PlayerCommand.Previous)
            awaitMusic(1)
        }
    }

    @Test fun nearEndPreparationIsReusedByNextWithoutRecordingHistoryEarly() = runBlocking {
        val started = CompletableDeferred<Unit>()
        resolveOverride = { id ->
            if (id == tracks[1].id) { started.complete(Unit); delay(600) }
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Seek(6000))
            withTimeout(4000) { started.await() }
            delay(700)
            assertEquals(tracks[0].id, connection.state.value.music?.id)
            assertFalse(connection.state.value.canPrevious)
            val before = android.os.SystemClock.elapsedRealtime()
            connection.dispatch(PlayerCommand.Next)
            awaitMusic(2)
            val elapsed = android.os.SystemClock.elapsedRealtime() - before
            assertEquals(1, resolves[tracks[1].id]?.get())
            assertTrue(connection.state.value.canPrevious)
            val file = java.io.File(app.filesDir, "music-preparation-latency.json")
            file.writeText("{\"syntheticResolveDelayMs\":600,\"preparedNextMs\":$elapsed}")
        }
    }

    @Test fun nextPromotesAnInFlightPreparationWithoutADuplicateResolve() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        resolveOverride = { id -> if (id == tracks[1].id) { started.complete(Unit); gate.await() } }
        try {
            withMusic {
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitMusic(1)
                connection.dispatch(PlayerCommand.Seek(6000))
                withTimeout(4000) { started.await() }
                connection.dispatch(PlayerCommand.Next)
                delay(100)
                assertEquals(1, resolves[tracks[1].id]?.get())
                gate.complete(Unit)
                awaitMusic(2)
                assertEquals(1, resolves[tracks[1].id]?.get())
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun pausingCancelsPreparationAndLateResultCannotStartAnotherTrack() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        resolveOverride = { id -> if (id == tracks[1].id) { started.complete(Unit); withContext(NonCancellable) { gate.await() } } }
        try {
            withMusic {
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitMusic(1)
                connection.dispatch(PlayerCommand.Seek(6000))
                withTimeout(4000) { started.await() }
                connection.dispatch(PlayerCommand.TogglePause)
                awaitPhase(PlaybackPhase.PAUSED)
                gate.complete(Unit)
                delay(150)
                assertEquals(tracks[0].id, connection.state.value.music?.id)
                assertEquals(PlaybackPhase.PAUSED, connection.state.value.phase)
                assertFalse(connection.state.value.canPrevious)
                resolveOverride = null
                connection.dispatch(PlayerCommand.Next)
                awaitMusic(2)
                assertEquals(2, resolves[tracks[1].id]?.get())
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun expiringPreparationRefreshesOnceAndSpeculativeRateLimitDoesNotPauseCurrent() = runBlocking {
        audioOverride = { value, _, _ ->
            if (value.trackId == tracks[1].id && resolves[value.trackId]?.get() == 1)
                value.copy(expiresAt = Instant.now().plusSeconds(2)) else value
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Seek(6000))
            withTimeout(4000) { while (resolves[tracks[1].id]?.get() != 1) delay(25) }
            connection.dispatch(PlayerCommand.Next)
            awaitMusic(2)
            assertEquals(2, resolves[tracks[1].id]?.get())
        }
        resolves.clear()
        resolveOverride = { id -> if (id == tracks[1].id) throw MusicException(MusicFailure.RATE_LIMITED, 1) }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            connection.dispatch(PlayerCommand.Seek(6000))
            withTimeout(4000) { while (resolves[tracks[1].id]?.get() != 1) delay(25) }
            delay(100)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            assertEquals(tracks[0].id, connection.state.value.music?.id)
            connection.dispatch(PlayerCommand.Next)
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(MusicFailure.RATE_LIMITED, connection.state.value.musicFailure)
            assertEquals(1, resolves[tracks[1].id]?.get())
            delay(1100)
        }
    }

    @Test fun nextKeepsOneBackgroundRefillInsteadOfCancellingAndReloadingThePage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        recommendationsOverride = { _, cursor ->
            requests.incrementAndGet()
            if (cursor == null) MusicPage(tracks, "second")
            else { started.complete(Unit); gate.await(); MusicPage(listOf(MusicTrack("extra", "Extra", listOf("qix")))) }
        }
        try {
            withMusic {
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitMusic(1)
                withTimeout(4000) { started.await() }
                connection.dispatch(PlayerCommand.Next)
                awaitMusic(2)
                assertEquals(2, requests.get())
                gate.complete(Unit)
                delay(100)
                connection.dispatch(PlayerCommand.Next)
                awaitMusic(3)
                assertEquals(2, requests.get())
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun switchingToRadioCancelsSpeculationAndLateRateLimitCannotPoisonMusic() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        resolveOverride = { id ->
            if (id == tracks[1].id) {
                started.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                throw MusicException(MusicFailure.RATE_LIMITED, 60)
            }
        }
        try {
            withMusic {
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitMusic(1)
                connection.dispatch(PlayerCommand.Seek(6000))
                withTimeout(4000) { started.await() }
                connection.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
                withTimeout(6000) { connection.state.first { it.source == PlaybackSource.RADIO && it.phase == PlaybackPhase.PLAYING } }
                gate.complete(Unit)
                delay(150)
                assertEquals(PlaybackSource.RADIO, connection.state.value.source)
                resolveOverride = null
                connection.dispatch(PlayerCommand.PlayMusic(tracks[2]))
                awaitMusic(3)
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun stopCancelsTheActiveResolveAfterImmediateCandidateFailures() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        resolveOverride = { id ->
            if (id != tracks[2].id) throw MusicException(MusicFailure.SOURCE_UNAVAILABLE)
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            withTimeout(4000) { started.await() }
            connection.dispatch(PlayerCommand.Stop)
            withTimeout(4000) { cancelled.await() }
            assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
        }
    }

    @Test fun consecutiveManualSkipsChooseAnotherArtistAndDiscardTheOldPreparation() = runBlocking {
        catalog = tracks.map { it.copy(artists = listOf("Same artist")) } +
            MusicTrack("alternative", "Alternative", listOf("Other artist"), durationMs = 12000)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        resolveOverride = { id -> if (id == tracks[2].id) { started.complete(Unit); withContext(NonCancellable) { gate.await() } } }
        try {
            withMusic {
                connection.dispatch(PlayerCommand.PlayMusic())
                awaitMusic(1)
                connection.dispatch(PlayerCommand.Next)
                awaitMusic(2)
                connection.dispatch(PlayerCommand.Seek(6000))
                withTimeout(4000) { started.await() }
                connection.dispatch(PlayerCommand.Next)
                withTimeout(6000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "alternative" } }
                gate.complete(Unit)
                delay(150)
                assertEquals("alternative", connection.state.value.music?.id)
                assertEquals(1, resolves[tracks[2].id]?.get())
                assertEquals(1, resolves["alternative"]?.get())
                connection.dispatch(PlayerCommand.Previous)
                awaitMusic(2)
                connection.dispatch(PlayerCommand.Next)
                withTimeout(6000) { connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "alternative" } }
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun exhaustedDuplicatePagesDoNotKeepRequestingAfterRepeatedNext() = runBlocking {
        val calls = AtomicInteger()
        recommendationsOverride = { _, cursor ->
            calls.incrementAndGet()
            MusicPage(listOf(tracks[0]), if (cursor == "a") "b" else "a")
        }
        withMusic {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic(1)
            withTimeout(4000) { while (connection.state.value.canNext) delay(25) }
            repeat(5) { connection.dispatch(PlayerCommand.Next) }
            awaitPhase(PlaybackPhase.ERROR)
            assertEquals(MusicFailure.NO_PLAYABLE_MATCH, connection.state.value.musicFailure)
            assertEquals(3, calls.get())
            assertEquals(1, resolves.values.sumOf { it.get() })
        }
    }

    private suspend fun withMusic(block: suspend (ActivityScenario<MainActivity>) -> Unit) {
        app.musicMediaRequests.clear()
        val fake = FakeMusicRepository(catalog)
        app.testMusicRepository = object : MusicRepository {
            override suspend fun search(query: String, limit: Int, cursor: String?) = fake.search(query, limit, cursor)
            override suspend fun radioRecommendations() = radioOverride?.invoke().orEmpty()
            override suspend fun recommendations(limit: Int, cursor: String?) =
                recommendationsOverride?.invoke(limit, cursor) ?: fake.recommendations(limit, cursor)
            override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
                val attempt = resolves.computeIfAbsent(trackId) { AtomicInteger() }.incrementAndGet()
                resolveOverride?.invoke(trackId)
                val originals = InstrumentationRegistry.getArguments().getString("musicOriginals") == "true"
                val file = if (trackId == tracks[1].id) "original-002.mp3" else "original-001.mp3"
                return audioOverride(PlayableAudio(trackId, "test", trackId, 1.0,
                    if (originals) "https://original.test/$file" else "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true), attempt, preferredProviderId)
            }
            override suspend fun resolveAlternate(trackId: String, excludedProviderId: String): PlayableAudio =
                alternateOverride?.invoke(trackId, excludedProviderId) ?: super.resolveAlternate(trackId, excludedProviderId)
        }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = PlaybackOrder.SEQUENTIAL, scope = RadioScope(ScopeMode.COUNTRY, app.country)) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(5000) { connection.connected.first { it } }
            withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }
            try { block(scenario) }
            finally {
                connection.dispatch(PlayerCommand.Stop)
                // 后续电台基线用例从电台空闲态开始，不继承本用例的音乐归属。
                connection.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
                connection.dispatch(PlayerCommand.Stop)
                main { media?.release() }; media = null
                app.testMusicRepository = null
            }
        }
    }

    private suspend fun awaitMusic(number: Int) = withTimeout(6000) {
        connection.state.first { it.source == PlaybackSource.MUSIC && it.phase == PlaybackPhase.PLAYING && it.music?.id == tracks[number - 1].id }
    }
    private suspend fun awaitPhase(phase: PlaybackPhase) = withTimeout(4000) { connection.state.first { it.phase == phase } }
    private fun connect(): MediaController {
        val future = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, RadioPlaybackService::class.java))).buildAsync() }
        return future.get().also { media = it }
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
