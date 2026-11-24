package com.meowv.tune

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadata as PlatformMetadata
import android.media.session.MediaController as PlatformController
import android.media.session.MediaSession as PlatformSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.legacy.MediaControllerCompat
import androidx.media3.session.legacy.MediaMetadataCompat
import androidx.media3.session.legacy.MediaSessionCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.music.MusicQueueSeed
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.PlayableAudio
import com.meowv.tune.music.FakeMusicRepository
import com.meowv.tune.playback.RadioPlaybackService
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSessionControlTest {
    @Test fun systemPlayRestoresRememberedStationWithoutLaunchingActivity() = runBlocking {
        val remembered = station(2)
        app.userSettings.recordSuccessfulStation(remembered)
        app.userSettings.update { it.copy(autoPlay = false) }
        val lease = onMain { connection.acquire() }
        try {
            withTimeout(5000) { connection.connected.first { it } }
            connection.dispatch(PlayerCommand.Stop)
            app.radioQueue.clearScope()
            val media = connect()
            onMain { media.play() }
            awaitStation(2)
            assertEquals(false, app.userSettings.settings.first().autoPlay)
        } finally {
            connection.dispatch(PlayerCommand.Stop)
            onMain { lease.close() }
        }
    }

    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private var controller: MediaController? = null

    @Before
    fun prepareQueue() = runBlocking<Unit> {
        app.resolveFailures.clear()
        app.playbackDelays.clear()
        app.latePlaybackDelays.clear()
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update {
            it.copy(scope = RadioScope(ScopeMode.COUNTRY, app.country), playbackOrder = PlaybackOrder.SEQUENTIAL)
        }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        onMain { controller?.release() }
        controller = null
        // 测试自建的三台目录会话不能留给后续用例。
        app.radioQueue.clearScope()
    }

    @Test fun sessionPublishesStationMetadataAndQueueButtons() = runBlocking {
        withPlayer { scenario ->
            playStation(1)
            val media = connect()
            assertEquals("Test station 1", onMain { media.mediaMetadata.title.toString() })
            assertEquals("Japan", onMain { media.mediaMetadata.artist.toString() })

            val notification = awaitNotification()
            assertEquals("Test station 1", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("Japan", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(listOf("上一个电台", "暂停", "下一个电台"), actionTitles(notification))
            assertFalse("首次播放没有上一台", app.radioQueue.canPrevious.value)
            assertTrue(app.radioQueue.canNext.value)
        }
    }

    @Test fun systemNextAndPreviousUseTheRangeQueue() = runBlocking {
        withPlayer {
            playStation(1)
            val media = connect()
            onMain { media.seekToNextMediaItem() }
            awaitStation(2)
            assertTrue("播放成功后台后可返回", app.radioQueue.canPrevious.value)
            assertEquals(listOf("上一个电台", "暂停", "下一个电台"), actionTitles(awaitNotification()))

            onMain { media.seekToPreviousMediaItem() }
            awaitStation(1)
        }
    }

    @Test fun systemNextAndPreviousFollowShuffleOrder() = runBlocking {
        app.userSettings.update { it.copy(playbackOrder = PlaybackOrder.SHUFFLE) }
        try {
            withPlayer {
                playStation(1)
                val media = connect()
                // 没有播放历史时，随机模式同样不能返回。
                assertFalse(app.radioQueue.canPrevious.value)

                onMain { media.seekToNextMediaItem() }
                val next = withTimeout(8000) {
                    connection.state.first {
                        it.phase == PlaybackPhase.PLAYING && it.station?.uuid != UUID(0, 1)
                    }
                }
                assertTrue("随机下一台必须留在当前目录", next.station?.uuid == UUID(0, 2) || next.station?.uuid == UUID(0, 3))

                onMain { media.seekToPreviousMediaItem() }
                withTimeout(8000) {
                    connection.state.first {
                        it.phase == PlaybackPhase.PLAYING && it.station?.uuid == UUID(0, 1)
                    }
                }
            }
        } finally {
            app.userSettings.update { it.copy(playbackOrder = PlaybackOrder.SEQUENTIAL) }
        }
    }

    @Test fun systemPlayPauseSharesTheInAppPath() = runBlocking {
        withPlayer {
            playStation(1)
            val media = connect()
            onMain { media.pause() }
            withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PAUSED } }

            // 通知里的播放键必须走同一条命令路径，直接触发通知动作。
            notificationPlayPause(awaitNotification()).send()
            withTimeout(5000) { connection.state.first { it.phase == PlaybackPhase.PLAYING } }
        }
    }

    @Test fun mediaButtonDispatchReachesTheQueue() = runBlocking {
        withPlayer {
            playStation(1)
            connect()
            shell("input keyevent KEYCODE_MEDIA_NEXT")
            awaitStation(2)
            shell("input keyevent KEYCODE_MEDIA_PREVIOUS")
            awaitStation(1)
        }
    }

    @Test fun frameworkControllerKeepsStationMetadataThroughSwitchAndRetry() = runBlocking {
        withPlayer {
            playStation(1)
            val platform = frameworkController(awaitNotification())
            val firstId = station(1).uuid.toString()
            val second = station(2)
            val secondId = second.uuid.toString()
            val changes = mutableListOf(firstId)
            val media = connect()
            val itemTransitions = mutableListOf<String?>()
            val itemListener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    itemTransitions.add(mediaItem?.mediaId)
                }
            }
            val callback = object : PlatformController.Callback() {
                override fun onMetadataChanged(metadata: PlatformMetadata?) {
                    val id = metadata?.getString(PlatformMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
                    if (id != changes.last()) changes.add(id)
                }
            }
            onMain {
                media.addListener(itemListener)
                platform.registerCallback(callback, Handler(Looper.getMainLooper()))
            }
            try {
                awaitFrameworkPlayback(platform, firstId, PlatformPlaybackState.STATE_PLAYING)
                assertTrue(onMain { (requireNotNull(platform.playbackState).actions and PlatformPlaybackState.ACTION_SKIP_TO_NEXT) != 0L })
                app.playbackDelays[second.uuid] = 1_500L

                onMain { platform.transportControls.skipToNext() }
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.RESOLVING && it.station?.uuid == second.uuid } }
                assertEquals(firstId, onMain { platform.metadata?.getString(PlatformMetadata.METADATA_KEY_MEDIA_ID) })
                awaitStation(2)
                awaitFrameworkPlayback(platform, secondId, PlatformPlaybackState.STATE_PLAYING)
                onMain {
                    val metadata = requireNotNull(platform.metadata)
                    assertEquals(second.name, metadata.getString(PlatformMetadata.METADATA_KEY_TITLE))
                    assertEquals(second.name, metadata.description.title.toString())
                    assertEquals("Japan", metadata.description.subtitle.toString())
                    assertTrue((requireNotNull(platform.playbackState).actions and PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L)
                }

                onMain { platform.transportControls.pause() }
                awaitFrameworkPlayback(platform, secondId, PlatformPlaybackState.STATE_PAUSED)
                onMain { platform.transportControls.play() }
                awaitFrameworkPlayback(platform, secondId, PlatformPlaybackState.STATE_PLAYING)

                app.playbackDelays.remove(second.uuid)
                app.resolveFailures[second.uuid] = AtomicInteger(1)
                connection.dispatch(PlayerCommand.Play(second))
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.RETRYING } }
                assertEquals(secondId, onMain { platform.metadata?.getString(PlatformMetadata.METADATA_KEY_MEDIA_ID) })
                awaitStation(2)

                onMain { platform.transportControls.skipToPrevious() }
                awaitStation(1)
                awaitFrameworkPlayback(platform, firstId, PlatformPlaybackState.STATE_PLAYING)
                withTimeout(3000) {
                    while (!onMain { changes.size >= 3 && changes.last() == firstId && itemTransitions.lastOrNull() == firstId }) {
                        delay(25)
                    }
                }
                assertEquals(listOf(firstId, secondId, firstId), onMain { changes.toList() })
                assertEquals(listOf(secondId, firstId), onMain { itemTransitions.toList() })
            } finally {
                app.playbackDelays.remove(second.uuid)
                app.resolveFailures.remove(second.uuid)
                onMain {
                    media.removeListener(itemListener)
                    platform.unregisterCallback(callback)
                }
            }
        }
    }

    @Test fun externalFrameworkBrowserCanReadButCannotControlPlayback() = runBlocking {
        withPlayer {
            playStation(1)
            val trusted = frameworkController(awaitNotification())
            val firstId = station(1).uuid.toString()
            awaitFrameworkPlayback(trusted, firstId, PlatformPlaybackState.STATE_PLAYING)
            withExternalController { external ->
                assertExternalReadOnly(external, station(1))

                onMain { trusted.transportControls.skipToNext() }
                awaitStation(2)
                awaitFrameworkPlayback(trusted, station(2).uuid.toString(), PlatformPlaybackState.STATE_PLAYING)
                // 队列状态刷新后，之前接入的外部控制器仍是只读的。
                assertExternalReadOnly(external, station(2))
            }
        }
    }

    @Test fun frameworkAndLegacyControllersSeeSongsAndRadioOnTheSameSession() = runBlocking {
        val first = MusicTrack("hud-a", "HUD song A", listOf("Artist A"))
        val second = MusicTrack("hud-b", "HUD song B", listOf("Artist B"))
        val tracks = listOf(first, second)
        val audio = tracks.associate { track ->
            track.id to PlayableAudio(track.id, "test", track.id, 1.0,
                "https://test.example/session-handoff.mp3", "audio/mpeg", 96_000, seekable = true)
        }
        app.testMusicRepository = FakeMusicRepository(tracks, audio)
        try {
            withPlayer {
                playStation(1)
                val platform = frameworkController(awaitNotification())
                val legacy = MediaControllerCompat(app, requireNotNull(MediaSessionCompat.Token.fromToken(platform.sessionToken)))
                val station = station(1)
                val stationId = station.uuid.toString()
                awaitSystemMetadata(platform, legacy, stationId, station.name, "Japan")
                val transitions = mutableListOf(stationId)
                val callback = object : MediaControllerCompat.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
                        val id = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                        if (!id.isNullOrEmpty() && id != transitions.lastOrNull()) transitions.add(id)
                    }
                }
                onMain { legacy.registerCallback(callback, Handler(Looper.getMainLooper())) }
                try {
                    connection.dispatch(PlayerCommand.PlayMusic(first, MusicQueueSeed(tracks, nextCursor = null)))
                    awaitSystemMetadata(platform, legacy, "music:${first.id}", first.title, "Artist A")
                    onMain { legacy.transportControls.skipToNext() }
                    awaitSystemMetadata(platform, legacy, "music:${second.id}", second.title, "Artist B")
                    onMain { platform.transportControls.pause() }
                    awaitFrameworkPlayback(platform, "music:${second.id}", PlatformPlaybackState.STATE_PAUSED)
                    onMain { legacy.transportControls.play() }
                    awaitFrameworkPlayback(platform, "music:${second.id}", PlatformPlaybackState.STATE_PLAYING)
                    onMain { platform.transportControls.skipToPrevious() }
                    awaitSystemMetadata(platform, legacy, "music:${first.id}", first.title, "Artist A")
                    connection.dispatch(PlayerCommand.Play(station))
                    awaitSystemMetadata(platform, legacy, stationId, station.name, "Japan")
                    withTimeout(3000) { while (!onMain { transitions.lastOrNull() == stationId }) delay(25) }
                    assertEquals(
                        listOf(stationId, "music:${first.id}", "music:${second.id}", "music:${first.id}", stationId),
                        onMain { transitions.toList() }
                    )
                } finally {
                    onMain { legacy.unregisterCallback(callback) }
                }
            }
        } finally {
            app.testMusicRepository = null
            app.musicResume.save(null)
        }
    }

    @Test fun exhaustedRetryForSameStationClearsTheMediaSession() = runBlocking {
        withPlayer {
            val first = station(1)
            playStation(1)
            val media = connect()
            awaitNotification()
            assertEquals(first.uuid.toString(), onMain { media.currentMediaItem?.mediaId })

            val scope = requireNotNull(app.radioQueue.activeScope.value)
            app.radioQueue.updateDirectory(scope, StationPage(listOf(first)))
            app.resolveFailures[first.uuid] = AtomicInteger(20)
            try {
                connection.dispatch(PlayerCommand.Play(first))
                withTimeout(9000) {
                    connection.state.first { it.phase == PlaybackPhase.ERROR && it.station?.uuid == first.uuid }
                }
                val manager = app.getSystemService(NotificationManager::class.java)
                withTimeout(3000) {
                    while (onMain { media.currentMediaItem != null } || manager.activeNotifications.isNotEmpty()) delay(25)
                }
            } finally {
                app.resolveFailures.remove(first.uuid)
            }
        }
    }

    @Test fun networkLossDuringSwitchClearsTheSupersededStation() = runBlocking {
        withPlayer {
            playStation(1)
            val media = connect()
            val second = station(2)
            app.playbackDelays[second.uuid] = 3_000L
            try {
                connection.dispatch(PlayerCommand.Play(second))
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.RESOLVING && it.station?.uuid == second.uuid } }
                app.mockNetwork.value = NetworkState()
                withTimeout(3000) { connection.state.first { it.error == TuneError.NETWORK_OFFLINE } }
                withTimeout(3000) {
                    while (onMain { media.currentMediaItem != null || media.isPlaying }) delay(25)
                }
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.playbackDelays.remove(second.uuid)
                app.mockNetwork.value = NetworkState(NetworkType.WIFI)
            }
        }
    }

    @Test fun previousWithoutHistoryKeepsPlaying() = runBlocking {
        withPlayer {
            playStation(1)
            val media = connect()
            onMain { media.seekToPreviousMediaItem() }
            delay(500)
            assertEquals(UUID(0, 1), connection.state.value.station?.uuid)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
        }
    }

    @Test fun returningToForegroundKeepsTheSameSession() = runBlocking {
        withPlayer { scenario ->
            // 普通测试音源只有 12 秒，生命周期切换在慢模拟器上可能正好遇到曲终。
            val station = station(901).copy(name = "session-handoff.mp3")
            connection.dispatch(PlayerCommand.Play(station))
            awaitStation(901)
            val media = connect()
            val token = onMain { media.connectedToken }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(5000) { connection.connected.first { it } }
            assertEquals(token, onMain { media.connectedToken })
            assertEquals(station.uuid.toString(), onMain { media.currentMediaItem?.mediaId })
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
        }
    }

    @Test fun removingTaskStopsRadioAndClearsMediaNotification() = runBlocking<Unit> {
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(5000) { connection.connected.first { it } }
            withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }
            val station = station(901).copy(name = "session-handoff.mp3")
            connection.dispatch(PlayerCommand.Play(station))
            awaitStation(901)
            awaitNotification()

            scenario.onActivity { it.finishAndRemoveTask() }
            awaitPlaybackStopped()
        }
    }

    @Test fun removingTaskStopsMusicAndPreservesPausedResumePoint() = runBlocking<Unit> {
        val track = MusicTrack("task-removal", "Task removal test", listOf("Test artist"))
        val audio = PlayableAudio(track.id, "test", track.id, 1.0,
            "https://test.example/session-handoff.mp3", "audio/mpeg", 96000, seekable = true)
        app.testMusicRepository = FakeMusicRepository(listOf(track), mapOf(track.id to audio))
        app.userSettings.update { it.copy(autoPlay = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(5000) { connection.connected.first { it } }
                connection.dispatch(PlayerCommand.PlayMusic(track))
                val playing = withTimeout(8000) {
                    connection.state.first {
                        it.music?.id == track.id && it.phase == PlaybackPhase.PLAYING &&
                            (it.positionMs ?: 0) > 0
                    }
                }
                awaitNotification()

                scenario.onActivity { it.finishAndRemoveTask() }
                awaitPlaybackStopped()
                val point = requireNotNull(app.musicResume.read())
                assertEquals(track.id, point.track.id)
                assertTrue(point.positionMs >= requireNotNull(playing.positionMs))
                assertFalse(point.playOnEntry)
            }
        } finally {
            app.testMusicRepository = null
            app.musicResume.save(null)
        }
    }

    private suspend fun awaitPlaybackStopped() {
        withTimeout(5000) {
            connection.state.first { it.phase == PlaybackPhase.IDLE }
            val manager = app.getSystemService(NotificationManager::class.java)
            while (manager.activeNotifications.isNotEmpty()) delay(50)
        }
    }

    private suspend fun withPlayer(block: suspend (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(5000) { connection.connected.first { it } }
            withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }
            val scope = app.radioQueue.activeScope.value!!
            app.radioQueue.clearScope()
            app.radioQueue.updateDirectory(scope, StationPage((1L..3L).map(::station)))
            try {
                block(scenario)
            } finally {
                onMain { controller?.release() }
                controller = null
                connection.dispatch(PlayerCommand.Stop)
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.IDLE } }
            }
        }
    }

    private suspend fun playStation(ordinal: Long) {
        connection.dispatch(PlayerCommand.Play(station(ordinal)))
        awaitStation(ordinal)
    }

    private suspend fun awaitStation(ordinal: Long) {
        try {
            withTimeout(8000) {
                connection.state.first { it.station?.uuid == UUID(0, ordinal) && it.phase == PlaybackPhase.PLAYING }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("等待台站 $ordinal 播放超时，当前=${connection.state.value}", timeout)
        }
        // 通知与元数据在播放状态稳定后刷新。
        delay(300)
    }

    private suspend fun awaitNotification(): Notification {
        val manager = app.getSystemService(NotificationManager::class.java)
        repeat(40) {
            val active = manager.activeNotifications
            if (active.isNotEmpty()) return active.first().notification
            delay(50)
        }
        throw AssertionError("播放中应存在媒体通知")
    }

    @Suppress("DEPRECATION")
    private fun frameworkController(notification: Notification): PlatformController = PlatformController(
        app, requireNotNull(notification.extras.getParcelable<PlatformSession.Token>(Notification.EXTRA_MEDIA_SESSION))
    )

    private suspend fun awaitFrameworkPlayback(platform: PlatformController, id: String, state: Int) = withTimeout(5000) {
        while (!onMain {
            platform.metadata?.getString(PlatformMetadata.METADATA_KEY_MEDIA_ID) == id && platform.playbackState?.state == state
        }) delay(25)
    }

    private suspend fun awaitSystemMetadata(
        platform: PlatformController,
        legacy: MediaControllerCompat,
        id: String,
        title: String,
        artist: String
    ) {
        awaitFrameworkPlayback(platform, id, PlatformPlaybackState.STATE_PLAYING)
        withTimeout(5000) {
            while (legacy.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) != id) delay(25)
        }
        val platformMetadata = requireNotNull(platform.metadata)
        val legacyMetadata = requireNotNull(legacy.metadata)
        assertEquals(title, platformMetadata.getString(PlatformMetadata.METADATA_KEY_TITLE))
        assertEquals(artist, platformMetadata.getString(PlatformMetadata.METADATA_KEY_ARTIST))
        assertEquals(title, legacyMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals(artist, legacyMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        val skipActions = PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS or PlatformPlaybackState.ACTION_SKIP_TO_NEXT
        assertEquals(skipActions, requireNotNull(platform.playbackState).actions and skipActions)
        assertEquals(skipActions, requireNotNull(legacy.playbackState).actions and skipActions)
    }

    private suspend fun withExternalController(block: suspend (Messenger) -> Unit) {
        val ready = CompletableDeferred<Messenger>()
        val clientConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                ready.complete(Messenger(service))
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val client = ComponentName(testPackage, ExternalMediaControllerService::class.java.name)
        assertTrue(app.bindService(Intent().setComponent(client), clientConnection, Context.BIND_AUTO_CREATE))
        try {
            block(withTimeout(5000) { ready.await() })
        } finally {
            app.unbindService(clientConnection)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun assertExternalReadOnly(external: Messenger, station: Station) {
        val response = CompletableDeferred<Bundle>()
        external.send(Message.obtain().apply {
            data.putParcelable("service", ComponentName(app, RadioPlaybackService::class.java))
            replyTo = Messenger(Handler(Looper.getMainLooper()) { result -> response.complete(result.data) })
        })
        val result = withTimeout(5000) { response.await() }
        check(result.getString("error") == null) { result.getString("error").orEmpty() }
        val uid = result.getInt("uid")
        assertTrue(uid != Process.myUid())
        val caller = MediaSessionManager.RemoteUserInfo(requireNotNull(result.getString("package")), result.getInt("pid"), uid)
        assertFalse(app.getSystemService(MediaSessionManager::class.java).isTrustedForMediaControl(caller))
        val metadata = requireNotNull(result.getParcelable<PlatformMetadata>("metadata"))
        assertEquals(station.name, metadata.getString(PlatformMetadata.METADATA_KEY_TITLE))
        assertEquals(station.uuid.toString(), metadata.getString(PlatformMetadata.METADATA_KEY_MEDIA_ID))
        assertEquals(PlatformPlaybackState.STATE_PLAYING, result.getParcelable<PlatformPlaybackState>("state")?.state)
        // 系统通过 Binder 异步投递控制命令；期间若出现暂停或换台，立即失败。
        val changed = withTimeoutOrNull(750) {
            connection.state.first { it.station?.uuid != station.uuid || it.phase != PlaybackPhase.PLAYING }
        }
        assertEquals(null, changed)
    }

    private suspend fun awaitActionTitles(expected: List<String>) {
        repeat(80) {
            if (actionTitles(awaitNotification()) == expected) return
            delay(50)
        }
        assertEquals(expected, actionTitles(awaitNotification()))
    }

    private fun notificationPlayPause(notification: Notification): android.app.PendingIntent {
        val action = notification.actions.orEmpty().firstOrNull {
            it.title == "播放" || it.title == "暂停"
        } ?: throw AssertionError("通知缺少播放/暂停键：" + actionTitles(notification))
        return requireNotNull(action.actionIntent) { "播放键缺少 PendingIntent" }
    }

    private fun actionTitles(notification: Notification): List<String> =
        notification.actions.orEmpty().map { it.title.toString() }

    private fun connect(): MediaController {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
            ).buildAsync()
        }
        return future.get().also { controller = it }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun shell(command: String) {
        val descriptor: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

    private fun station(ordinal: Long) = Station(UUID(0, ordinal), "Test station $ordinal", app.country)
}
