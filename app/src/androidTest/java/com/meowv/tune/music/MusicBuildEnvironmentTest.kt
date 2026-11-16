package com.meowv.tune.music

import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import com.meowv.tune.BuildConfig
import com.meowv.tune.MainActivity
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import com.meowv.tune.playback.RadioPlaybackService
import java.util.UUID
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.*
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicBuildEnvironmentTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun ordinaryDebugStartsLocalAudioUsingProductionRepositoryAndTransport() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicLocalBuild") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
        app.userSettings.update { it.copy(autoPlay = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                withTimeout(5000) { app.playerController.connected.first { it } }
                compose.onNodeWithTag("nav_music").performClick()
                compose.onNodeWithTag("music_start").performScrollTo().performClick()
                withTimeout(8000) { app.playerController.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "original-001" } }
                app.playerController.dispatch(PlayerCommand.TogglePause)
                app.playerController.dispatch(PlayerCommand.Seek(11000))
                app.playerController.dispatch(PlayerCommand.TogglePause)
                withTimeout(8000) { app.playerController.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "original-002" } }
                assertTrue(app.playerController.state.value.canPrevious)
                app.playerController.dispatch(PlayerCommand.TogglePause)
                val mode = if (app.resources.configuration.screenWidthDp >= 600) "car" else "phone"
                val image = File(app.getExternalFilesDir(null), "music-evidence/$mode-local-debug.png")
                image.parentFile!!.mkdirs()
                image.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                val future = onMain { MediaController.Builder(app, SessionToken(app, ComponentName(app, RadioPlaybackService::class.java))).buildAsync() }
                val controller = future.get()
                try {
                    val token = onMain { controller.connectedToken }
                    app.playerController.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
                    withTimeout(6000) { app.playerController.state.first { it.source == PlaybackSource.RADIO && it.phase == PlaybackPhase.PLAYING } }
                    assertFalse(onMain { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
                    app.playerController.dispatch(PlayerCommand.PlayMusic(com.meowv.tune.domain.music.MusicTrack("original-002", "今天也不错", listOf("qix"))))
                    withTimeout(8000) { app.playerController.state.first { it.phase == PlaybackPhase.PLAYING && it.music?.id == "original-002" } }
                    assertEquals(token, onMain { controller.connectedToken })
                } finally { onMain { controller.release() } }
            } finally { app.playerController.dispatch(PlayerCommand.Stop) }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test fun debugBuildConnectsToTheLocalApiWithItsOwnCredential() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicLocalBuild") == "true")
        assertTrue(BuildConfig.DEBUG)
        val base = BuildConfig.MUSIC_API_BASE_URL.toHttpUrl()
        assertTrue(isDebugMusicHost(base.host))
        val repository = MusicApiClient(base, { BuildConfig.MUSIC_DEVICE_TOKEN }, allowLocalHttp = true)
        assertTrue(repository.search("qix").items.isNotEmpty())
    }
}
