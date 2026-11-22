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
import kotlinx.coroutines.CompletableDeferred
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
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = com.meowv.tune.domain.PlaybackOrder.SEQUENTIAL) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            val future = main { MediaController.Builder(app, SessionToken(app,
                ComponentName(app, RadioPlaybackService::class.java))).buildAsync() }
            val controller = future.get()
            try {
                val token = main { controller.connectedToken }
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                capture("input")
                assertEquals(0, requests.get())
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_query").performImeAction()
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
                val searchToggle = compose.onNode(hasTestTag("mini_player_toggle") and
                    hasAnyAncestor(hasTestTag("music_search_player")))
                searchToggle.assertIsDisplayed().performClick()
                withTimeout(5000) { connection.state.first { it.phase == PlaybackPhase.PAUSED } }
                compose.onNodeWithTag("music_search_result_original-001").assertIsDisplayed()
                searchToggle.performClick()
                awaitTrack("original-002")
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
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = com.meowv.tune.domain.PlaybackOrder.SEQUENTIAL) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            connection.dispatch(PlayerCommand.Stop)
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_close").performClick()
                compose.onNodeWithTag("music_search_page").assertDoesNotExist()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("no-result-for-this-query")
                compose.onNodeWithTag("music_search_query").performImeAction()
                compose.waitUntil(8000) {
                    compose.onAllNodesWithTag("music_search_empty").fetchSemanticsNodes().isNotEmpty()
                }
                capture("empty")
                compose.onNodeWithTag("music_search_query").performTextReplacement("qix")
                compose.onNodeWithTag("music_search_query").performImeAction()
                compose.waitUntil(8000) {
                    compose.onAllNodesWithTag("music_search_result_original-001").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("music_search_close").performClick()
                delay(100)
                assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            } finally { app.testMusicRepository = null }
        }
    }

    @Test fun historyCanBeReusedAndClearedWhileCarNavigationStaysReachable() = runBlocking {
        installFakeUnlessUsingLocalBuild()
        app.musicSearchHistory.clear()
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_query").performImeAction()
                compose.waitUntil(8000) {
                    compose.onAllNodesWithTag("music_search_result_original-001").fetchSemanticsNodes().isNotEmpty()
                }
                val car = app.resources.configuration.screenWidthDp >= 600
                if (car) {
                    compose.onNodeWithTag("nav_settings").assertIsDisplayed().performClick()
                    compose.onNodeWithTag("music_search_page").assertDoesNotExist()
                    compose.onNodeWithTag("page_settings").assertIsDisplayed()
                    compose.onNodeWithTag("nav_music").performClick()
                    compose.onNodeWithTag("music_search_open").performClick()
                }
                compose.onNodeWithTag("music_search_query").performTextClearance()
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("music_search_history_0").fetchSemanticsNodes().isNotEmpty()
                }
                capture("history")
                compose.onNodeWithTag("music_search_history_0").performClick()
                compose.waitUntil(8000) {
                    compose.onAllNodesWithTag("music_search_result_original-001").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("music_search_query").performTextClearance()
                compose.onNodeWithTag("music_search_history_clear").performClick()
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("music_search_history").fetchSemanticsNodes().isEmpty()
                }
                scenario.recreate()
                assertEquals(emptyList<String>(), app.musicSearchHistory.read())
            } finally {
                app.testMusicRepository = null
            }
        }
    }

    @Test fun metadataOnlySearchResultIsHidden() = runBlocking {
        installFakeUnlessUsingLocalBuild()
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = com.meowv.tune.domain.PlaybackOrder.SEQUENTIAL) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { connected -> connected } }
            connection.dispatch(PlayerCommand.Stop)
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("Metadata")
                compose.onNodeWithTag("music_search_query").performImeAction()
                compose.waitUntil(4000) { compose.onAllNodesWithTag("music_search_empty").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_search_result_metadata").assertDoesNotExist()
                compose.onNodeWithText(app.getString(com.meowv.tune.R.string.music_search_no_playable_results))
                    .assertIsDisplayed()
                assertEquals(PlaybackPhase.IDLE, connection.state.value.phase)
            } finally { app.testMusicRepository = null }
        }
    }

    @Test fun laterPlayableSourceUpgradesTheExistingMetadataResult() = runBlocking {
        val metadata = MusicTrack("metadata", "Metadata qix", listOf("qix"), availability = "metadata_only",
            matches = listOf(MusicTrackMatch("qqmusic", "12")))
        val playable = tracks[0].copy(matches = listOf(MusicTrackMatch("netease", "34"), MusicTrackMatch("qqmusic", "12", .99, "studio")))
        val fake = FakeMusicRepository(tracks)
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun search(query: String, limit: Int, cursor: String?) =
                if (cursor == null) MusicPage(listOf(metadata), "later", partial = true, warnings = listOf("netease:deferred"))
                else MusicPage(listOf(playable), partial = true, warnings = listOf("qqmusic:source_timeout"))
            override suspend fun resolve(trackId: String, preferredProviderId: String?) = PlayableAudio(
                trackId, "test", trackId, 1.0, "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
        }
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = com.meowv.tune.domain.PlaybackOrder.SEQUENTIAL) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { connected -> connected } }
            connection.dispatch(PlayerCommand.Stop)
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_query").performImeAction()
                compose.waitUntil(4000) { compose.onAllNodesWithTag("music_search_more").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_search_result_metadata").assertDoesNotExist()
                compose.onNodeWithText("QQ 音乐 响应超时").assertDoesNotExist()
                compose.onNodeWithTag("music_search_more").performScrollTo().performClick()
                compose.waitUntil(4000) { compose.onAllNodesWithTag("music_search_result_original-001").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("music_search_result_metadata").assertDoesNotExist()
                compose.onNodeWithText("QQ 音乐 响应超时").assertDoesNotExist()
                compose.onNodeWithText("部分来源未能提供结果，已显示可用内容").assertDoesNotExist()
                compose.onNodeWithTag("music_search_result_original-001").assertIsEnabled().performClick()
                awaitTrack("original-001")
                Unit
            } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
            }
        }
    }

    @Test fun searchRetriesKeepResultsCursorAndScrollPosition() = runBlocking<Unit> {
        val first = AtomicInteger()
        val failInitialRequest = CompletableDeferred<Unit>()
        val append = AtomicInteger()
        val page = (1..20).map { MusicTrack("page-$it", "Search result $it", listOf("qix")) }
        val second = MusicTrack("page-21", "Search result 21", listOf("qix"))
        val fake = FakeMusicRepository(tracks)
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage =
                if (cursor == null) {
                    if (first.incrementAndGet() == 1) {
                        failInitialRequest.await()
                        throw MusicException(MusicFailure.CONNECTION)
                    }
                    MusicPage(page, "next")
                } else {
                    assertEquals("next", cursor)
                    if (append.incrementAndGet() == 1) throw MusicException(MusicFailure.SOURCE_TIMEOUT)
                    MusicPage(listOf(second))
                }
        }
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_query").performImeAction()
                try {
                    compose.onNodeWithTag("music_search_loading").assertIsDisplayed()
                    capture("loading")
                } finally {
                    failInitialRequest.complete(Unit)
                }
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("music_search_retry").fetchSemanticsNodes().isNotEmpty()
                }
                capture("error")
                compose.onNodeWithTag("music_search_retry").performClick()
                val list = compose.onNodeWithTag("music_search_results")
                list.performScrollToNode(hasTestTag("music_search_more"))
                val more = compose.onNodeWithTag("music_search_more")
                more.performClick()
                more.assertTextEquals("重试")
                compose.onNodeWithTag("music_search_result_page-20").assertIsDisplayed()
                compose.onNodeWithTag("music_search_retry").assertDoesNotExist()
                capture("append-error")

                more.performClick()
                compose.onNodeWithTag("music_search_more").assertDoesNotExist()
                compose.onNodeWithTag("music_search_result_page-20").assertIsDisplayed()
                compose.onNodeWithTag("music_search_close").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").assertTextContains("qix")
                compose.onNodeWithTag("music_search_result_page-20").assertIsDisplayed()
                assertEquals(2, first.get())
                assertEquals(2, append.get())
            } finally {
                app.testMusicRepository = null
            }
        }
    }

    @Test fun invalidContinuationOffersFirstPageRestartAndPreservesRowsUntilSuccess() = runBlocking<Unit> {
        val first = AtomicInteger()
        val append = AtomicInteger()
        val restart = CompletableDeferred<Unit>()
        val initialPage = (1..20).map { MusicTrack("initial-$it", "Existing result $it", listOf("qix")) }
        val refreshedPage = (1..20).map { MusicTrack("fresh-$it", "New result $it", listOf("qix")) }
        val fake = FakeMusicRepository(tracks)
        app.testMusicRepository = object : MusicRepository by fake {
            override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
                assertEquals("qix", query)
                if (cursor != null) {
                    append.incrementAndGet()
                    assertEquals("expired", cursor)
                    throw MusicException(MusicFailure.INVALID_REQUEST)
                }
                return if (first.incrementAndGet() == 1) MusicPage(initialPage, "expired") else {
                    restart.await()
                    MusicPage(refreshedPage)
                }
            }
        }
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_search_open").performClick()
                compose.onNodeWithTag("music_search_query").performTextInput("qix")
                compose.onNodeWithTag("music_search_query").performImeAction()
                compose.onNodeWithTag("music_search_results")
                    .performScrollToNode(hasTestTag("music_search_more"))
                compose.onNodeWithTag("music_search_more").performClick()
                compose.onNodeWithTag("music_search_more").assertDoesNotExist()
                compose.onNodeWithTag("music_search_restart").performClick()
                compose.onNodeWithTag("music_search_loading").assertIsDisplayed()
                compose.onNodeWithTag("music_search_result_initial-20").assertIsDisplayed()
                restart.complete(Unit)
                compose.waitUntil(4000) {
                    compose.onAllNodesWithTag("music_search_result_fresh-1").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("music_search_result_fresh-1").assertIsDisplayed()
                compose.onNodeWithTag("music_search_result_initial-20").assertDoesNotExist()
                compose.onNodeWithTag("music_search_restart").assertDoesNotExist()
                compose.onNodeWithTag("music_search_query").assertTextContains("qix")
                assertEquals(2, first.get())
                assertEquals(1, append.get())
            } finally {
                restart.complete(Unit)
                app.testMusicRepository = null
            }
        }
    }

    private fun installFakeUnlessUsingLocalBuild() {
        if (InstrumentationRegistry.getArguments().getString("musicLocalBuild") == "true") return
        val fake = FakeMusicRepository(tracks + MusicTrack("metadata", "Metadata qix", listOf("qix"),
            availability = "metadata_only", matches = listOf(MusicTrackMatch("qqmusic", "12", .99, "studio"))))
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
        val file = File(app.cacheDir, "music-evidence/$mode-search-$name.png")
        file.parentFile!!.mkdirs()
        val screen = if (mode == "wide") compose.onRoot() else compose.onNodeWithTag("music_search_page")
        file.outputStream().use { screen.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
