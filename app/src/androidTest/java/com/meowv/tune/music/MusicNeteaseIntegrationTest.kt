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
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.music.MusicRepository
import com.meowv.tune.domain.music.PlayableAudio
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

    @Test fun realDualSourcePlaybackShowsArtworkAndSyncedLyrics() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("musicDualBase")
        assumeTrue(base != null)
        val app = instrumentation.targetContext.applicationContext as TuneTestApplication
        val client = MusicApiClient(requireNotNull(base).toHttpUrl(),
            { "YOUR_LOCAL_NETEASE_TEST_TOKEN_0000000000" }, allowLocalHttp = true)
        val sources = setOf("netease", "qqmusic")
        val page = client.search("哪里都是你 队长")
        val track = page.items.first { it.matches.map { match -> match.providerId }.toSet() == sources }
        assertNotNull(track.coverUrl)
        app.userSettings.update { it.copy(autoPlay = false) }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        var preferred = "qqmusic"
        var resolved: PlayableAudio? = null
        app.testMusicRepository = object : MusicRepository by client {
            override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio =
                client.resolve(trackId, preferredProviderId ?: preferred).also { resolved = it }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val player = app.playerController
            try {
                withTimeout(8000) { player.connected.first { ready -> ready } }
                compose.onNodeWithTag("nav_music").performClick()
                for (provider in sources) {
                    preferred = provider
                    val alternate = client.resolveAlternate(track.id, if (provider == "qqmusic") "netease" else "qqmusic")
                    assertEquals(provider, alternate.providerId)
                    player.dispatch(PlayerCommand.PlayMusic(track))
                    val playing = withTimeout(20000) { player.state.first { state ->
                        state.phase == PlaybackPhase.PLAYING && state.music?.id == track.id && resolved?.providerId == provider
                    } }
                    assertTrue((playing.durationMs ?: 0) in 220_000..225_000)
                    player.dispatch(PlayerCommand.Seek(60_000))
                    val seeked = withTimeout(15000) { player.state.first { state ->
                        state.phase == PlaybackPhase.PLAYING && (state.positionMs ?: 0) >= 59_000 && state.musicLyrics.isNotEmpty()
                    } }
                    val index = MusicLyrics.activeLine(seeked.musicLyrics, seeked.positionMs!!)
                    assertTrue(index >= 0)
                    compose.onNodeWithTag("circular_player_entry").performClick()
                    compose.onNodeWithTag("music_artwork").assertExists()
                    compose.onNodeWithTag("music_lyrics").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("now_playing_collapse").performClick()
                    player.dispatch(PlayerCommand.Stop)
                    withTimeout(5000) { player.state.first { state -> state.music == null } }
                }
            } finally {
                player.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
            }
        }
    }

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
                compose.waitUntil(12000) {
                    compose.onAllNodes(hasTestTag("music_feed") and hasAnyDescendant(hasClickAction()))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("music_feed"))).onFirst().performClick()
                val state = withTimeout(20000) { app.playerController.state.first { s ->
                    s.phase == PlaybackPhase.PLAYING && s.music?.id?.startsWith("nt-") == true
                } }
                args.getString("musicExpectedFirstTitle")?.let { assertEquals(it, state.music?.title) }
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
