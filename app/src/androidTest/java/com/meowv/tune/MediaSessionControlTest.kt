package com.meowv.tune

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.playback.RadioPlaybackService
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        app.mockNetwork.value = com.meowv.tune.domain.NetworkState(com.meowv.tune.domain.NetworkType.WIFI)
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
            playStation(1)
            val media = connect()
            val itemId = onMain { media.currentMediaItem?.mediaId }
            scenario.moveToState(Lifecycle.State.CREATED)
            delay(500)
            scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(5000) { connection.connected.first { it } }
            assertEquals(itemId, onMain { media.currentMediaItem?.mediaId })
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
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
