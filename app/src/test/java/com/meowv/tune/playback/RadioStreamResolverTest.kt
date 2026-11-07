package com.meowv.tune.playback

import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RadioStreamResolverTest {
    private val station = Station(UUID.randomUUID(), "Test", null)

    @Test fun publicUrlAndDnsChecksRejectPrivateAndSpecialTargets() {
        listOf("file:///tmp/stream", "content://example/stream", "http://user:pass@example.org",
            "http://localhost./", "http://127.1/", "http://[::1]", "http://[::ffff:169.254.1.1]",
            "http://[fc00::1]", "http://[2002:a00:1::1]", "http://192.168.1.1", "http://100.64.0.1",
            "http://169.254.169.254", "http://10.0.0.1", "http://172.31.0.1", "http://224.0.0.1").forEach {
            assertNull(it, MediaUrlPolicy.parse(it))
        }
        assertNotNull(MediaUrlPolicy.parse("http://8.8.8.8/radio"))
        assertNotNull(MediaUrlPolicy.parse("https://[2606:4700:4700::1111]/radio"))
        val public = InetAddress.getByName("8.8.8.8")
        val fake = object : Dns {
            override fun lookup(hostname: String) = if (hostname == "mixed.example") {
                listOf(public, InetAddress.getByName("192.168.1.1"))
            } else listOf(public)
        }
        val dns = MediaUrlPolicy.checkedDns(fake)
        assertEquals(listOf(public), dns.lookup("public.example"))
        assertThrows(UnknownHostException::class.java) { dns.lookup("mixed.example") }
    }

    @Test fun redirectAndNestedPlaylistsReachAValidatedDirectStream() = runBlocking {
        val paths = mutableListOf<String>()
        val resolver = RadioStreamResolver({ """{"ok":"true","url":"https://public.example/start"}""" },
            { error("Unexpected details call") }) { url ->
            paths += url.encodedPath
            when (url.encodedPath) {
                "/start" -> MediaProbeResult.Redirect("/root/list.m3u")
                "/root/list.m3u" -> MediaProbeResult.Playlist(PlaylistFormat.M3U,
                    "#EXTM3U\nhttp://127.0.0.1/hidden\n./nested.pls")
                "/root/nested.pls" -> MediaProbeResult.Playlist(PlaylistFormat.PLS, "[playlist]\nFile1=../list.asx")
                "/list.asx" -> MediaProbeResult.Playlist(PlaylistFormat.ASX,
                    """<ASX><ENTRY><REF HREF="https://public.example/live?token=test&amp;mode=live"/></ENTRY></ASX>""")
                "/live" -> MediaProbeResult.Direct
                else -> error("Unexpected media request")
            }
        }
        val stream = resolver.resolve(PlaybackIntent(station))
        assertEquals(listOf("/start", "/root/list.m3u", "/root/nested.pls", "/list.asx", "/live"), paths)
        assertEquals("live", stream.url.queryParameter("mode"))
        assertFalse(stream.isHls)
        assertFalse(stream.toString().contains("token"))
    }

    @Test fun clickFailureIsNotRetriedAndDetailsSupplyAnHlsManifest() = runBlocking {
        var clicks = 0
        var details = 0
        val resolver = RadioStreamResolver({ clicks++; throw TuneFailure(TuneError.TIMEOUT) }, {
            details++
            station.copy(resolvedStreamUrl = "https://example.org/live.m3u8")
        }) { url ->
            assertEquals("/live.m3u8", url.encodedPath)
            MediaProbeResult.Playlist(PlaylistFormat.M3U, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild.m3u8")
        }
        val intent = PlaybackIntent(station)
        repeat(2) { assertTrue(resolver.resolve(intent).isHls) }
        assertEquals(1, clicks)
        assertEquals(2, details)
    }

    @Test fun privateRedirectIsBlockedAndRedirectBudgetFallsBackToTheKnownUrl() = runBlocking {
        var requests = 0
        val resolver = RadioStreamResolver({ """{"ok":true,"url":"https://example.org/start"}""" },
            { error("Unexpected details call") }) { url ->
            requests++
            assertEquals("example.org", url.host)
            when (url.encodedPath) {
                "/start" -> MediaProbeResult.Redirect("http://169.254.169.254/private")
                "/live" -> MediaProbeResult.Direct
                else -> MediaProbeResult.Redirect("/loop$requests")
            }
        }
        val stream = resolver.resolve(PlaybackIntent(station.copy(
            resolvedStreamUrl = "https://example.org/loop", streamUrl = "http://example.org/live"
        )))
        assertEquals("/live", stream.url.encodedPath)
        assertFalse(stream.url.isHttps)
        assertEquals(7, requests)
    }

    @Test fun cancellationAfterClickStartsDoesNotSendItAgainOnTheSameIntent() = runBlocking {
        withTimeout(5000) {
            val started = CompletableDeferred<Unit>()
            var clicks = 0
            val resolver = RadioStreamResolver({ clicks++; started.complete(Unit); awaitCancellation() },
                { error("Unexpected details call") }) { MediaProbeResult.Direct }
            val intent = PlaybackIntent(station.copy(streamUrl = "https://example.org/live"))
            val pending = launch { resolver.resolve(intent) }
            started.await()
            pending.cancelAndJoin()
            assertEquals("/live", resolver.resolve(intent).url.encodedPath)
            assertEquals(1, clicks)
        }
    }

    @Test fun mediaProbeDistinguishesAudioAndRejectsOversizedPlaylists() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type", "audio/mpeg").setBody("audio"))
            server.enqueue(MockResponse().addHeader("Content-Type", "audio/x-mpegurl").setChunkedBody("#EXTM3U\n" + "a".repeat(65536), 1024))
            server.enqueue(MockResponse().addHeader("Content-Type", "text/plain").setBody("[playlist]\nFile1=/live"))
            val probe = MediaProbe(OkHttpClient())
            assertEquals(MediaProbeResult.Direct, probe.get(server.url("/live")))
            val failure = try {
                probe.get(server.url("/oversized"))
                error("Expected playlist rejection")
            } catch (failure: TuneFailure) { failure }
            assertEquals(TuneError.INVALID_RESPONSE, failure.kind)
            assertEquals(PlaylistFormat.PLS, (probe.get(server.url("/playlist")) as MediaProbeResult.Playlist).format)
        }
    }
}
