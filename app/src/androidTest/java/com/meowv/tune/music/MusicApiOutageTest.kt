package com.meowv.tune.music

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.R
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.playback.RadioPlaybackService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class MusicApiOutageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val phase get() = File(app.filesDir, "music-outage-phase")
    private val hostState get() = File(app.filesDir, "music-outage-host")

    @Test fun stoppedApiFailsBoundedlyAndOnlyExplicitRetryResumesAfterRestart() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("musicOutageBase")
        assumeTrue(base != null)
        val calls = AtomicInteger()
        val repository = MusicApiClient(base!!.toHttpUrl(), {
            calls.incrementAndGet()
            // 仅供隔离宿主使用的公开测试占位值，不是设备凭据。
            "YOUR_LOCAL_OUTAGE_TEST_TOKEN_0000000000"
        }, allowLocalHttp = true)
        app.testMusicRepository = repository
        app.userSettings.update { it.copy(autoPlay = false) }
        var media: MediaController? = null
        try {
            assertTrue(repository.search("qix").items.isNotEmpty())
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(5000) { connection.connected.first { it } }
                compose.onNodeWithTag("nav_music").performClick()
                val future = main {
                    MediaController.Builder(app, SessionToken(app,
                        ComponentName(app, RadioPlaybackService::class.java))).buildAsync()
                }
                val controller = future.get().also { media = it }
                val token = main { controller.connectedToken }
                phase.writeText("ready-to-stop")
                awaitHost("offline")
                compose.onNodeWithTag("music_start").performScrollTo().performClick()
                awaitConnectionError()
                compose.onNodeWithText(app.getString(R.string.music_failed)).assertExists()
                val failedCalls = calls.get()
                delay(300)
                assertEquals(failedCalls, calls.get())
                phase.writeText("failed-offline")
                awaitHost("online")
                delay(500)
                assertEquals(PlaybackPhase.ERROR, connection.state.value.phase)
                assertEquals(failedCalls, calls.get())
                compose.onNodeWithTag("music_toggle").performScrollTo().performClick()
                awaitTrack("original-001")
                assertEquals(token, main { controller.connectedToken })
                phase.writeText("ready-to-stop-again")
                awaitHost("offline-again")
                connection.dispatch(PlayerCommand.Next)
                awaitConnectionError()
                val nextFailedCalls = calls.get()
                phase.writeText("failed-offline-again")
                awaitHost("online-again")
                delay(500)
                assertEquals(PlaybackPhase.ERROR, connection.state.value.phase)
                assertEquals(nextFailedCalls, calls.get())
                compose.onNodeWithTag("music_toggle").performScrollTo().performClick()
                awaitTrack("original-002")
                assertEquals(token, main { controller.connectedToken })
                phase.writeText("complete")
                connection.dispatch(PlayerCommand.Stop)
                main { media?.release() }; media = null
            }
        } finally {
            main { connection.stopActive(); media?.release() }
            app.testMusicRepository = null
        }
    }

    private suspend fun awaitConnectionError() = withTimeout(9000) {
        connection.state.first { it.phase == PlaybackPhase.ERROR }.also {
            assertEquals(MusicFailure.CONNECTION, it.musicFailure)
        }
    }

    private suspend fun awaitTrack(id: String) = withTimeout(8000) {
        connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == id }
    }

    private suspend fun awaitHost(value: String) = withTimeout(30_000) {
        while (!hostState.exists() || hostState.readText() != value) delay(50)
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
