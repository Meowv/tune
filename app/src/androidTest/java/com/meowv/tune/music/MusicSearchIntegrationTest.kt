package com.meowv.tune.music

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.meowv.tune.domain.music.*
import com.meowv.tune.playback.RadioPlaybackService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MusicSearchIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val requests = AtomicInteger()
    private val tracks = listOf(
        MusicTrack("original-001", "First original", listOf("qix")),
        MusicTrack("original-002", "Second original", listOf("qix"))
    )

    @Test fun searchSelectionPlaysAndEofReturnsToRecommendationsOnTheSameSession() = runBlocking {
        installFakeUnlessUsingLocalBuild()
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            val future = main { MediaController.Builder(app, SessionToken(app,
                ComponentName(app, RadioPlaybackService::class.java))).buildAsync() }
            val controller = future.get()
            try {
                val token = main { controller.connectedToken }
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                assertEquals(0, requests.get())
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_submit").performClick()
                compose.waitUntil(8000) {
                    compose.onAllNodesWithTag("music_search_result_original-001").fetchSemanticsNodes().isNotEmpty()
                }
                capture("results")
                compose.onNodeWithTag("music_search_result_original-001").performClick()
                awaitTrack("original-001")
                compose.onNodeWithTag("music_search_page").assertDoesNotExist()
                assertEquals(token, main { controller.connectedToken })
                connection.dispatch(PlayerCommand.Seek(11000))
                awaitTrack("original-002")
                assertTrue(connection.state.value.canPrevious)
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_close").performClick()
                assertEquals(token, main { controller.connectedToken })
                assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                main { controller.release() }
                app.testMusicRepository = null
            }
        }
    }

    @Test fun emptySearchCanBeEditedAndClosedWithoutStartingPlayback() = runBlocking {
        installFakeUnlessUsingLocalBuild()
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            connection.dispatch(PlayerCommand.Stop)
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("no-result-for-this-query")
                compose.onNodeWithTag("music_search_query").performImeAction()
                compose.waitUntil(8000) {
                    compose.onAllNodesWithTag("music_search_empty").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("music_search_query").performTextReplacement("qix")
                compose.onNodeWithTag("music_search_submit").performClick()
                compose.waitUntil(8000) {
                    compose.onAllNodesWithTag("music_search_result_original-001").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("music_search_close").performClick()
                delay(100)
                assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            } finally { app.testMusicRepository = null }
        }
    }

    @Test fun metadataOnlySearchResultShowsItsCapabilityAndCannotStartPlayback() = runBlocking {
        installFakeUnlessUsingLocalBuild()
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { connected -> connected } }
            connection.dispatch(PlayerCommand.Stop)
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_submit").performClick()
                compose.waitUntil(4000) { compose.onAllNodesWithTag("music_search_result_metadata").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_search_results").performScrollToNode(hasTestTag("music_search_result_metadata"))
                compose.onNodeWithTag("music_search_result_metadata").assertIsNotEnabled()
                compose.onNodeWithText("仅提供搜索信息").assertIsDisplayed()
                compose.onNodeWithText("酷我音乐").assertIsDisplayed()
                assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            } finally { app.testMusicRepository = null }
        }
    }

    @Test fun laterPlayableSourceUpgradesTheExistingMetadataResult() = runBlocking {
        val metadata = MusicTrack("metadata", "Metadata qix", listOf("qix"), availability = "metadata_only",
            matches = listOf(MusicTrackMatch("kuwo-search", "12")))
        val playable = tracks[0].copy(matches = listOf(MusicTrackMatch("netease", "34"), MusicTrackMatch("kuwo-search", "12", .99, "studio")))
        val fake = FakeMusicRepository(tracks)
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun search(query: String, limit: Int, cursor: String?) =
                if (cursor == null) MusicPage(listOf(metadata), "later", partial = true, warnings = listOf("netease:deferred"))
                else MusicPage(listOf(playable))
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = PlayableAudio(
                trackId, "test", trackId, 1.0, "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
        }
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { connected -> connected } }
            connection.dispatch(PlayerCommand.Stop)
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_submit").performClick()
                compose.waitUntil(4000) { compose.onAllNodesWithTag("music_search_result_metadata").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_search_result_metadata").assertIsNotEnabled()
                compose.onNodeWithText("网易云音乐 响应较慢，加载更多可继续搜索").assertIsDisplayed()
                compose.onNodeWithTag("music_search_more").performScrollTo().performClick()
                compose.waitUntil(4000) { compose.onAllNodesWithTag("music_search_result_original-001").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_search_result_metadata").assertDoesNotExist()
                compose.onNodeWithText("网易云音乐 响应较慢，加载更多可继续搜索").assertDoesNotExist()
                compose.onNodeWithTag("music_search_result_original-001").assertIsEnabled().performClick()
                awaitTrack("original-001")
                Unit
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
            }
        }
    }

    private fun installFakeUnlessUsingLocalBuild() {
        if (InstrumentationRegistry.getArguments().getString("musicLocalBuild") == "true") return
        val fake = FakeMusicRepository(tracks + MusicTrack("metadata", "Metadata qix", listOf("qix"),
            availability = "metadata_only", matches = listOf(MusicTrackMatch("kuwo-search", "12", .99, "studio"))))
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
                requests.incrementAndGet()
                return fake.search(query, limit, cursor)
            }
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = PlayableAudio(
                trackId, "test", trackId, 1.0, "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
        }
    }

    private suspend fun awaitTrack(id: String) = withTimeout(8000) {
        connection.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == id }
    }

    private fun capture(name: String) {
        val mode = if (app.resources.configuration.screenWidthDp >= 600) "wide" else "phone"
        val file = File(app.getExternalFilesDir(null), "music-evidence/$mode-search-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onNodeWithTag("music_search_page").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
