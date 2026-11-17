package com.meowv.tune.music

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.playback.RadioPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class MusicNeteaseIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realRecommendationsStartWithoutOpeningSearch() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("musicLiveNetease") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        args.getString("musicNeteaseBase")?.let { base ->
            app.testMusicRepository = MusicApiClient(base.toHttpUrl(),
                { "YOUR_LOCAL_NETEASE_TEST_TOKEN_0000000000" }, allowLocalHttp = true)
        }
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { app.playerController.connected.first { connected -> connected } }
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_start").performScrollTo().performClick()
                val state = withTimeout(20000) { app.playerController.state.first { s ->
                    s.phase == PlaybackPhase.PLAYING && s.music?.id?.startsWith("nt-") == true
                } }
                assertTrue((state.durationMs ?: 0) > 60000)
                assertTrue(state.canNext)
            } finally {
                app.playerController.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
            }
        }
    }

    @Test fun realSourceSearchPlaysHttpsAudioSeeksAndContinuesRecommendations() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("musicLiveNetease") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TuneTestApplication
        val connection = app.playerController
        val base = args.getString("musicNeteaseBase")
        if (base != null) app.testMusicRepository = MusicApiClient(base.toHttpUrl(),
            { "YOUR_LOCAL_NETEASE_TEST_TOKEN_0000000000" }, allowLocalHttp = true)
        app.userSettings.update { it.copy(autoPlay = false) }
        var media: MediaController? = null
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                withTimeout(5000) { connection.connected.first { it } }
                val future = main { MediaController.Builder(app, SessionToken(app,
                    ComponentName(app, RadioPlaybackService::class.java))).buildAsync() }
                val controller = future.get().also { media = it }
                val token = main { controller.connectedToken }
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("Kevin MacLeod")
                compose.onNodeWithTag("music_search_submit").performClick()
                compose.waitUntil(15000) {
                    compose.onAllNodes(hasTestTag("music_search_results")).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("music_search_results")) and hasText("Kevin MacLeod", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                val mode = if (app.resources.configuration.screenWidthDp >= 600) "wide" else "phone"
                val image = File(app.getExternalFilesDir(null), "music-evidence/$mode-netease-search.png")
                image.parentFile!!.mkdirs()
                image.outputStream().use { out ->
                    compose.onNodeWithTag("music_search_page").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                // 固定选第一条当前来源结果，不把外部歌名或ID写入测试代码。
                compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("music_search_results")) and hasText("Kevin MacLeod", substring = true)).onFirst().performClick()
                val first = withTimeout(20000) { connection.state.first { s -> s.phase == PlaybackPhase.PLAYING && s.music?.id?.startsWith("nt-") == true } }
                assertTrue((first.durationMs ?: 0) > 60000)
                val initialPosition = main { controller.currentPosition }
                delay(1200)
                assertTrue(main { controller.isPlaying })
                assertTrue(main { controller.currentPosition } > initialPosition + 500)
                connection.dispatch(PlayerCommand.TogglePause)
                withTimeout(4000) { connection.state.first { s -> s.phase == PlaybackPhase.PAUSED } }
                connection.dispatch(PlayerCommand.Seek(30000))
                withTimeout(4000) { connection.state.first { s -> (s.positionMs ?: 0) >= 29000 } }
                connection.dispatch(PlayerCommand.TogglePause)
                withTimeout(8000) { connection.state.first { s -> s.phase == PlaybackPhase.PLAYING } }
                connection.dispatch(PlayerCommand.Seek(requireNotNull(first.durationMs) - 1000))
                val next = withTimeout(20000) { connection.state.first { s -> s.phase == PlaybackPhase.PLAYING && s.music?.id != first.music?.id } }
                assertTrue(next.music!!.id.startsWith("nt-"))
                assertTrue(connection.state.value.canPrevious)
                assertEquals(token, main { controller.connectedToken })
            } finally {
                main { connection.stopActive(); media?.release() }
                app.testMusicRepository = null
            }
        }
    }
}
