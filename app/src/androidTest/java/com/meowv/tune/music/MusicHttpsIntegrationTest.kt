package com.meowv.tune.music

import android.graphics.Bitmap
import android.content.ComponentName
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import java.io.File
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.MainActivity
import com.meowv.tune.TuneTestApplication
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.*
import com.meowv.tune.playback.MediaUrlPolicy
import com.meowv.tune.playback.RadioPlaybackService
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Assume.assumeTrue

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicHttpsIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun requireExplicitHttpsRun() { assumeTrue(transport.enabled()) }

    @After fun recordHttpsTraffic() {
        if (!transport.enabled()) return
        val file = File(app.getExternalFilesDir(null), "music-evidence/https-traffic.json")
        file.parentFile!!.mkdirs()
        file.writeText(JsonObject(mapOf("requests" to JsonArray(traffic()))).toString())
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController
    private val transport = MusicHttpsTestTransport
    private val client by lazy { transport.client() }
    private fun repository(token: String = transport.TEST_TOKEN) = MusicApiClient(transport.BASE_URL.toHttpUrl(), { token }, client)

    @Test fun tlsIdentityAuthenticationMimeAndRangeAreVerified() = runBlocking {
        val repo = repository()
        assertEquals(2, repo.search("qix").items.size)
        assertEquals(MusicFailure.UNAUTHORIZED, failure { repository("YOUR_INVALID_SYNTHETIC_TOKEN_FOR_TESTS").recommendations() }.failure)
        val badPin = MusicApiClient(transport.BASE_URL.toHttpUrl(), { transport.TEST_TOKEN }, transport.client("0".repeat(64)))
        assertEquals(MusicFailure.TLS, failure { badPin.recommendations() }.failure)
        val badName = client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }).build()
        assertEquals(MusicFailure.TLS, failure {
            MusicApiClient("https://other.music.test:18443/api/music/".toHttpUrl(), { transport.TEST_TOKEN }, badName).recommendations()
        }.failure)
        val audio = repo.resolve("original-001")
        assertEquals(transport.MEDIA_HOST, audio.url.toHttpUrl().host)
        client.newCall(Request.Builder().url(audio.url).head().build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.header("Content-Type").orEmpty().startsWith("audio/mpeg"))
            assertTrue(it.header("Content-Length")!!.toLong() > 128)
        }
        client.newCall(Request.Builder().url(audio.url).header("Range", "bytes=0-127").build()).execute().use {
            assertEquals(206, it.code)
            assertTrue(it.header("Content-Range").orEmpty().startsWith("bytes 0-127/"))
            assertEquals(128, it.body!!.bytes().size)
        }
        try {
            MediaUrlPolicy.checkedDns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).lookup(transport.MEDIA_HOST)
            fail("Production DNS policy must still reject loopback")
        } catch (_: UnknownHostException) { }
    }

    @Test fun realApiAndTlsAudioPlayTwoCompleteClipsThenStopAtCatalogEnd() = runBlocking {
        val before = traffic().maxOfOrNull { it.getValue("sequence").jsonPrimitive.long } ?: 0
        withPlayer {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic("original-001")
            withTimeout(20000) { awaitMusic("original-002", 19000) }
            withTimeout(20000) { connection.state.first { it.phase == PlaybackPhase.ERROR && it.musicFailure == MusicFailure.NO_PLAYABLE_MATCH } }
            val requests = traffic().filter { it.getValue("sequence").jsonPrimitive.long > before }
            val resolves = requests.filter { it.getValue("path").jsonPrimitive.content == "/api/music/resolve" }
            assertEquals(2, resolves.size)
            val media = requests.filter { it.getValue("path").jsonPrimitive.content.startsWith("/audio/") && it.getValue("method").jsonPrimitive.content == "GET" }
            assertTrue(media.any { it.getValue("path").jsonPrimitive.content.endsWith("original-001.mp3") })
            assertTrue(media.any { it.getValue("path").jsonPrimitive.content.endsWith("original-002.mp3") })
            assertTrue(media.all { !it.getValue("authorization").jsonPrimitive.boolean && it.getValue("https").jsonPrimitive.boolean })
        }
    }

    @Test fun httpsSeekPauseAndRadioSwitchKeepTheSameMediaSession() = runBlocking {
        withPlayer {
            connection.dispatch(PlayerCommand.PlayMusic())
            awaitMusic("original-001")
            val future = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, RadioPlaybackService::class.java))).buildAsync() }
            val controller = future.get()
            try {
                val token = main { controller.connectedToken }
                assertTrue(main { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
                main { controller.pause() }
                withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PAUSED } }
                main { controller.seekTo(4000) }
                withTimeout(3000) { connection.state.first { (it.positionMs ?: 0) >= 3500 } }
                connection.dispatch(PlayerCommand.Play(Station(UUID(0, 1), "Test station 1", app.country)))
                withTimeout(6000) { connection.state.first { it.source == PlaybackSource.RADIO && it.phase == PlaybackPhase.PLAYING } }
                assertFalse(main { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
                connection.dispatch(PlayerCommand.PlayMusic(MusicTrack("original-002", "今天也不错", listOf("qix"))))
                awaitMusic("original-002")
                assertEquals(token, main { controller.connectedToken })
            } finally { main { controller.release() } }
        }
    }

    @Test fun realHttpsMusicUiUsesTheActualApi() = runBlocking {
        withPlayer {
            compose.onNodeWithTag("nav_music").performClick()
            compose.onNodeWithTag("music_start").performScrollTo().performClick()
            awaitMusic("original-001")
            compose.onNodeWithTag("music_toggle").performScrollTo().performClick()
            withTimeout(3000) { connection.state.first { it.phase == PlaybackPhase.PAUSED } }
            compose.onNodeWithTag("music_title").assertTextEquals("睡醒就好了（测试片段）")
            capture("page")
            compose.onNodeWithTag("circular_player_entry").performClick()
            compose.onNode(hasTestTag("music_seek") and hasAnyAncestor(hasTestTag("now_playing_screen"))).performScrollTo().assertIsEnabled()
            capture("immersive")
        }
    }

    private fun capture(name: String) {
        val mode = if (app.resources.configuration.screenWidthDp >= 600) "car" else "phone"
        val file = File(app.getExternalFilesDir(null), "music-evidence/$mode-https-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private suspend fun withPlayer(block: suspend () -> Unit) {
        app.testMusicRepository = repository()
        app.mockNetwork.value = NetworkState(NetworkType.WIFI)
        app.userSettings.update { it.copy(autoPlay = false, scope = RadioScope(ScopeMode.COUNTRY, app.country)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(5000) { connection.connected.first { it } }
            withTimeout(5000) { app.radioQueue.activeScope.first { it != null } }
            try { block() } finally {
                connection.dispatch(PlayerCommand.Stop)
                app.testMusicRepository = null
            }
        }
    }
    private suspend fun awaitMusic(id: String, timeout: Long = 8000) = withTimeout(timeout) {
        connection.state.first { it.source == PlaybackSource.MUSIC && it.phase == PlaybackPhase.PLAYING && it.music?.id == id }
    }
    private fun traffic(): List<JsonObject> = client.newCall(Request.Builder().url("https://${transport.API_HOST}/test/status").build()).execute().use {
        assertEquals(200, it.code)
        Json.parseToJsonElement(it.body!!.string()).jsonObject.getValue("requests").jsonArray.map { item -> item.jsonObject }
    }
    private suspend fun failure(block: suspend () -> Any?): MusicException {
        try { block(); error("Expected MusicException") } catch (failure: MusicException) { return failure }
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
