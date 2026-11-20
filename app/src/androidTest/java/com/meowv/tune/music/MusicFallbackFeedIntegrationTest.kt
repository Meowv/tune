package com.meowv.tune.music

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test fun queuedFallbackAppearsInTheFeedAndSurvivesUntilRecommendationsChange() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        val original = MusicTrack("feed-base", "Base", listOf("qix"))
        val fallback = MusicTrack("feed-fallback", "Fallback", listOf("qix"),
            matches = listOf(MusicTrackMatch("test", "fallback-source")))
        val recovered = fallback.copy(id = "feed-fallback-fresh")
        val replacement = MusicTrack("feed-replacement", "Replacement", listOf("qix"))
        val recommendations = MutableStateFlow(MusicPage(listOf(original)))
        val sourceExpired = AtomicBoolean()
        val catalog = FakeMusicRepository(listOf(original, fallback, recovered, replacement), listOf(original, fallback, recovered).associate {
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
                    controller.dispatch(PlayerCommand.Next)
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
                } finally {
                    controller.dispatch(PlayerCommand.Stop)
                }
            }
        } finally {
            app.testMusicRepository = null
        }
    }

    private fun awaitRow(id: String) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("music_feed_track_$id").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
