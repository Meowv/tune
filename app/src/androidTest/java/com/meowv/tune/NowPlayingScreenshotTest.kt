package com.meowv.tune

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.ui.isCarWindow
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 人工视觉验收工具。仅在显式传入 captureNowPlaying=true 时运行，
 * 输出真实 Compose 页面到系统图片目录，不参与常规全量测试。
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TuneTestApplication

    @Before
    fun prepare() = runBlocking<Unit> {
        assumeTrue(
            InstrumentationRegistry.getArguments()
                .getString("captureNowPlaying") == "true"
        )
        app.mockNetwork.value = NetworkState(NetworkType.WIFI, metered = false)
        runCatching { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
        app.sleepTimer.cancel()
        app.userSettings.update {
            TuneSettings(
                scope = RadioScope(ScopeMode.GLOBAL),
                playbackOrder = PlaybackOrder.SHUFFLE
            )
        }
    }

    @Test
    fun capture() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        compose.waitUntil(5000) { app.playerController.connected.value }
        val stationTag = "station_${UUID(0, 1)}"
        compose.onNodeWithTag("station_list").performScrollToNode(hasTestTag(stationTag))
        compose.onNodeWithTag(stationTag).performClick()
        compose.waitUntil(8000) {
            app.playerController.state.value.phase == PlaybackPhase.PLAYING
        }
        compose.onNodeWithTag("circular_player_entry").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("now_playing_screen").fetchSemanticsNodes().isNotEmpty()
        }
        app.sleepTimer.start(30)
        compose.waitUntil(3000) { app.sleepTimer.state.value.remainingMillis > 0L }
        Thread.sleep(400)

        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        val titleBounds = compose.onNodeWithTag("now_playing_title").getUnclippedBoundsInRoot()
        val controlBounds = compose.onNodeWithTag("now_playing_play").getUnclippedBoundsInRoot()
        val toolBounds = compose.onNodeWithTag("now_playing_tools").getUnclippedBoundsInRoot()
        val height = (rootBounds.bottom - rootBounds.top).value
        fun ratio(value: androidx.compose.ui.unit.Dp) =
            (value - rootBounds.top).value / height
        println(
            "NOWPLAYING_LAYOUT title=${ratio(titleBounds.top)} " +
                "controls=${ratio(controlBounds.top)} tools=${ratio(toolBounds.top)}"
        )
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val config = compose.activity.resources.configuration
        val form = if (
            isCarWindow(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
        ) "car" else "phone"
        val phase = InstrumentationRegistry.getArguments()
            .getString("captureName")
            .orEmpty()
            .ifBlank { "phase-current" }
            .replace(Regex("[^a-zA-Z0-9_-]"), "-")
        val name = "$phase-$form.png"
        val resolver = compose.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/MeowvTune"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        )
        resolver.openOutputStream(uri).use { output ->
            requireNotNull(output)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        println(
            "NOWPLAYING_SCREENSHOT=/sdcard/${Environment.DIRECTORY_PICTURES}/MeowvTune/$name"
        )

        app.sleepTimer.cancel()
        runBlocking { app.playerController.dispatch(com.meowv.tune.domain.PlayerCommand.Stop) }
    }
}
