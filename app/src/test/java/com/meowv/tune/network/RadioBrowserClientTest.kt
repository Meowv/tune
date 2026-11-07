package com.meowv.tune.network

import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class RadioBrowserClientTest {
    @Test fun playbackClickUsesHttpsAndDoesNotFailOverAfterAnAmbiguousFailure() = runBlocking {
        pair { first, second, http, a, b ->
            first.enqueue(MockResponse().setResponseCode(503))
            val id = UUID.randomUUID()
            val client = browser(http, MemoryMirrorStore(), listOf(a, b))
            assertEquals(TuneError.SERVER, captureFailure { client.recordPlaybackClick(id) }.kind)
            val request = first.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/json/url/$id", request.path)
            assertNotNull(request.handshake)
            assertEquals(0, second.requestCount)
        }
    }
    @Test fun failoverUsesHttpsAndRemembersHealthyNode() = runBlocking {
        pair { first, second, http, a, b ->
            first.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setBody("[]"))
            second.enqueue(MockResponse().setBody("[]"))
            val store = MemoryMirrorStore()
            val browser = browser(http, store, listOf(a, b))
            assertEquals("[]", browser.get("/json/stations/search", mapOf("name" to "a & b")))
            assertEquals(b, store.host)
            val request = second.takeRequest(1, TimeUnit.SECONDS)!!
            assertNotNull(request.handshake)
            assertEquals("MeowvTune/test", request.getHeader("User-Agent"))
            assertEquals("a & b", request.requestUrl!!.queryParameter("name"))
            assertEquals("[]", browser(http, store, listOf(a, b)).get("/json/stations/search"))
            assertEquals(1, first.requestCount)
            assertEquals(2, second.requestCount)
        }
    }

    @Test fun timeoutFallsBackAndAllFailuresFinish() = runBlocking {
        pair { first, second, http, a, b ->
            first.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            second.enqueue(MockResponse().setBody("[]"))
            val browser = browser(http, MemoryMirrorStore(), listOf(a, b))
            assertEquals("[]", withTimeout(4000) { browser.get("/json/servers") })
            first.enqueue(MockResponse().setResponseCode(502))
            second.enqueue(MockResponse().setResponseCode(504))
            val failure = captureFailure { withTimeout(4000) { browser.get("/json/servers") } }
            assertEquals(TuneError.SERVER, failure.kind)
            assertEquals(502, failure.statusCode)
            assertEquals(2, first.requestCount)
            assertEquals(2, second.requestCount)
        }
    }

    @Test fun cancellationDoesNotTryAnotherNodeOrUpdateCache() = runBlocking {
        pair { first, second, http, a, b ->
            first.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val store = MemoryMirrorStore()
            coroutineScope {
                val request = launch { browser(http, store, listOf(a, b)).get("/json/servers") }
                withContext(Dispatchers.IO) { assertNotNull(first.takeRequest(2, TimeUnit.SECONDS)) }
                request.cancelAndJoin()
            }
            assertEquals(0, second.requestCount)
            assertNull(store.host)
        }
    }

    @Test fun redirectAndClientErrorDoNotEscapeHttpsOrFailOver() = runBlocking {
        pair { first, second, http, a, b ->
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://example.invalid/"))
            first.enqueue(MockResponse().setResponseCode(404))
            val browser = browser(http, MemoryMirrorStore(), listOf(a, b))
            assertEquals(302, captureFailure { browser.get("/json/servers") }.statusCode)
            val failure = captureFailure { browser.get("/json/servers") }
            assertEquals(TuneError.HTTP, failure.kind)
            assertEquals(404, failure.statusCode)
            assertEquals(0, second.requestCount)
        }
    }

    @Test fun retriesAreBoundedAndLaterRequestsCanReachOtherCandidates() = runBlocking {
        pair { first, second, http, a, b ->
            val others = listOf("c", "d", "e").map { requireNotNull(MirrorHost.parse("$it.api.radio-browser.info")) }
            val browser = browser(http, MemoryMirrorStore(), listOf(a, b) + others)
            repeat(3) { first.enqueue(MockResponse().setResponseCode(503)) }
            second.enqueue(MockResponse().setResponseCode(503))
            first.enqueue(MockResponse().setBody("[]"))
            assertEquals(TuneError.SERVER, captureFailure { browser.get("/json/servers") }.kind)
            assertEquals(4, first.requestCount + second.requestCount)
            assertEquals("[]", browser.get("/json/servers"))
            assertEquals(5, first.requestCount + second.requestCount)
        }
    }

    @Test fun dnsConnectionFailureFallsBackToAnotherNode() = runBlocking {
        pair { first, second, http, _, b ->
            val missing = requireNotNull(MirrorHost.parse("missing.api.radio-browser.info"))
            assertEquals(TuneError.CONNECTION, captureFailure { http.get(missing, "/json/servers") }.kind)
            second.enqueue(MockResponse().setBody("[]"))
            assertEquals("[]", browser(http, MemoryMirrorStore(), listOf(missing, b)).get("/json/servers"))
            assertEquals(0, first.requestCount)
            assertEquals(1, second.requestCount)
        }
    }

    @Test fun tlsRejectsMismatchedHostnames() = runBlocking {
        pair { first, _, http, _, _ ->
            val host = requireNotNull(MirrorHost.parse("untrusted.api.radio-browser.info"))
            assertEquals(TuneError.TLS, captureFailure { http.get(host, "/json/servers") }.kind)
            assertEquals(0, first.requestCount)
        }
    }

    private fun browser(http: DirectoryHttp, store: MemoryMirrorStore, nodes: List<MirrorHost>) =
        RadioBrowserClient(http, MirrorDirectory({ nodes }, { "[]" }, store, { 0L }, nodes))

    private suspend fun captureFailure(block: suspend () -> Unit): TuneFailure {
        try {
            block()
            error("Expected a TuneFailure")
        } catch (failure: TuneFailure) {
            return failure
        }
    }

    private suspend fun pair(
        block: suspend (MockWebServer, MockWebServer, DirectoryHttp, MirrorHost, MirrorHost) -> Unit
    ) {
        val a = requireNotNull(MirrorHost.parse("a.api.radio-browser.info"))
        val b = requireNotNull(MirrorHost.parse("b.api.radio-browser.info"))
        val certificate = HeldCertificate.Builder().apply {
            listOf("a", "b", "c", "d", "e").forEach { addSubjectAlternativeName("$it.api.radio-browser.info") }
        }.build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.useHttps(serverTls.sslSocketFactory(), false)
                second.useHttps(serverTls.sslSocketFactory(), false)
                first.start()
                second.start()

                val client = OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            if (hostname == "missing.api.radio-browser.info") throw UnknownHostException()
                            return listOf(InetAddress.getLoopbackAddress())
                        }
                    })
                    .addInterceptor { chain ->
                        val request = chain.request()
                        chain.proceed(request.newBuilder()
                            .url(request.url.newBuilder().port(if (request.url.host == b.name) second.port else first.port).build()).build())
                    }
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(750, TimeUnit.MILLISECONDS)
                    .callTimeout(2, TimeUnit.SECONDS)
                    .build()
                block(first, second, DirectoryHttp("test", client), a, b)
            }
        }
    }
}

internal class MemoryMirrorStore(var host: MirrorHost? = null) : LastMirrorStore {
    override suspend fun read() = host
    override suspend fun write(host: MirrorHost) {
        this.host = host
    }
}
