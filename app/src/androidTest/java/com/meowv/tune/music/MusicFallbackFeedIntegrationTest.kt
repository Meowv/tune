package com.meowv.tune.music

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.BuildConfig
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.music.MusicException
import com.meowv.tune.domain.music.MusicFailure
import com.meowv.tune.domain.music.MusicPage
import com.meowv.tune.domain.music.MusicRepository
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.MusicTrackMatch
import com.meowv.tune.domain.music.PlayableAudio
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test

class MusicFallbackFeedIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun automaticFallbackAppearsInTheFeedAndSurvivesUntilRecommendationsChange() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        val original = MusicTrack("feed-base", "Base", listOf("qix"))
        val fallback = MusicTrack("feed-fallback", "Fallback", listOf("qix"),
            matches = listOf(MusicTrackMatch("test", "fallback-source")))
        val recovered = fallback.copy(id = "feed-fallback-fresh")
        val replacement = MusicTrack("feed-replacement", "Replacement", listOf("qix"))
        val recommendations = MutableStateFlow(MusicPage(listOf(original)))
        val sourceExpired = AtomicBoolean()
        val tracks = listOf(original, fallback, recovered, replacement)
        val catalog = FakeMusicRepository(tracks, tracks.associate {
            it.id to PlayableAudio(it.id, "test", it.id, 1.0,
                "https://test.example/radio.mp3", "audio/mpeg", 12000, seekable = true)
        })
        app.testMusicRepository = object : MusicRepository by catalog {
            override suspend fun recommendations(limit: Int, cursor: String?) = recommendations.value
            override suspend fun radioRecommendations() = listOf(fallback)
            override suspend fun search(query: String, limit: Int, cursor: String?) =
                MusicPage(listOf(recovered).filter { sourceExpired.get() && query == "Fallback qix" })
            override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
                if (trackId == fallback.id && sourceExpired.get()) throw MusicException(MusicFailure.TRACK_NOT_FOUND)
                return catalog.resolve(trackId, preferredProviderId)
            }
        }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = PlaybackOrder.SEQUENTIAL) }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val controller = app.playerController
                withTimeout(5000) { controller.connected.first { it } }
                try {
                    compose.onNodeWithTag("nav_music").performClick()
                    awaitRow(original.id)
                    compose.onNodeWithTag("music_feed_track_${original.id}").performClick()
                    withTimeout(6000) {
                        controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == original.id }
                    }
                    controller.dispatch(PlayerCommand.Seek(11_500))
                    withTimeout(6000) {
                        controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == fallback.id }
                    }
                    awaitRow(fallback.id)
                    compose.onNodeWithTag("music_feed_track_${fallback.id}").assertIsDisplayed()

                    scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
                    awaitRow(fallback.id)

                    controller.dispatch(PlayerCommand.Stop)
                    withTimeout(6000) { controller.state.first { it.phase == PlaybackPhase.IDLE } }
                    sourceExpired.set(true)
                    compose.onNodeWithTag("music_feed_track_${fallback.id}").performClick()
                    withTimeout(6000) {
                        controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == recovered.id }
                    }
                    awaitRow(recovered.id)
                    compose.onNodeWithTag("music_feed_track_${fallback.id}").assertDoesNotExist()

                    recommendations.value = MusicPage(listOf(replacement))
                    scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
                    awaitRow(replacement.id)
                    compose.onNodeWithTag("music_feed_track_${recovered.id}").assertDoesNotExist()
                    assertEquals(recovered.id, controller.state.value.music?.id)
                    assertEquals(PlaybackPhase.PLAYING, controller.state.value.phase)
                    controller.dispatch(PlayerCommand.Seek(11_500))
                    withTimeout(6000) {
                        controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == replacement.id }
                    }
                } finally {
                    controller.dispatch(PlayerCommand.Stop)
                }
            }
        } finally {
            app.testMusicRepository = null
        }
    }

    @Test fun tappingAHomeTrackKeepsPagingAcrossAnEmptyPage() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        val first = (1..20).map { MusicTrack("rolling-$it", "Rolling $it", listOf("Artist $it"), durationMs = 12_000) }
        val extra = MusicTrack("rolling-next", "Rolling next", listOf("Fresh artist"), durationMs = 12_000)
        val pages = AtomicInteger()
        val all = first + extra
        val catalog = FakeMusicRepository(all, all.associate {
            it.id to PlayableAudio(it.id, "test", it.id, 1.0, "https://test.example/radio.mp3", "audio/mpeg", 12_000, seekable = true)
        })
        app.testMusicRepository = object : MusicRepository by catalog {
            override suspend fun candidates(limit: Int, cursor: String?): MusicPage {
                assertEquals(30, limit)
                return when (cursor) {
                    null -> MusicPage(first, "filtered", strategy = "rolling_candidates",
                        discoveryTrackIds = first.takeLast(8).map { it.id }.toSet())
                    "filtered" -> {
                        pages.incrementAndGet()
                        MusicPage(emptyList(), "more", strategy = "rolling_candidates")
                    }
                    "more" -> {
                        pages.incrementAndGet()
                        MusicPage(listOf(extra), strategy = "rolling_candidates", discoveryTrackIds = setOf(extra.id))
                    }
                    else -> error("Unexpected fixture cursor")
                }
            }
        }
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = PlaybackOrder.SEQUENTIAL) }

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.playerController
                withTimeout(5000) { controller.connected.first { it } }
                try {
                    compose.onNodeWithTag("nav_music").performClick()
                    awaitRow(first.first().id)
                    compose.onNodeWithTag("music_start_listening").assertDoesNotExist()
                    compose.onNodeWithTag("music_feed_track_${first.first().id}").performClick()
                    withTimeout(6000) {
                        controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == first.first().id }
                    }
                    val last = first.last()
                    compose.onNodeWithTag("music_feed").performScrollToIndex(19)
                    compose.onNodeWithTag("music_feed_track_${last.id}").performClick()
                    withTimeout(6000) {
                        controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == last.id }
                    }
                    controller.dispatch(PlayerCommand.Next)
                    withTimeout(6000) {
                        controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == extra.id }
                    }
                    assertEquals(2, pages.get())
                } finally {
                    controller.dispatch(PlayerCommand.Stop)
                }
            }
        } finally {
            app.testMusicRepository = null
        }
    }

    @Test fun developmentCandidateEndpointPlaysAndAdvancesWithoutOpeningSearch() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("musicRollingLive") == "true")
        assumeTrue(BuildConfig.MUSIC_API_BASE_URL.startsWith("http://10.0.2.2:5000/"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        val repository = requireNotNull(app.musicRepository)
        val candidates = repository.candidates()
        assertEquals("rolling_candidates", candidates.strategy)
        assertTrue(candidates.items.size >= 2)
        app.userSettings.update { it.copy(autoPlay = false, playbackOrder = PlaybackOrder.SEQUENTIAL) }

        ActivityScenario.launch(MainActivity::class.java).use {
            val controller = app.playerController
            withTimeout(5000) { controller.connected.first { it } }
            try {
                compose.onNodeWithTag("nav_music").performClick()
                val trackRow = SemanticsMatcher("音乐推荐曲目") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("music_feed_track_") == true
                }
                compose.waitUntil(12000) {
                    compose.onAllNodes(trackRow).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodes(trackRow)[0].performClick()
                val first = withTimeout(20000) {
                    controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music != null }
                }
                controller.dispatch(PlayerCommand.Next)
                val next = withTimeout(20000) {
                    controller.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id != first.music?.id }
                }
                assertTrue((first.durationMs ?: 0) > 0 && (next.durationMs ?: 0) > 0)
            } finally {
                controller.dispatch(PlayerCommand.Stop)
            }
        }
    }

    private fun awaitRow(id: String) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("music_feed_track_$id").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
