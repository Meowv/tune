package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import java.net.InetAddress
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class MusicRepositoryTest {
    private val fixture get() = Json.parseToJsonElement(javaClass.getResource("/music-v1.json")!!.readText()).jsonObject
    private val token = "YOUR_SYNTHETIC_MUSIC_DEVICE_TOKEN_FOR_TESTS_ONLY"

    @Test fun goldenWireFieldsMapToDomainWithoutInventingConfidence() {
        val page = MusicJson.page(fixture.getValue("search").toString())
        assertEquals(12000L, page.items.single().durationMs)
        assertNull(page.items.single().matches.single().confidence)
        val resolved = MusicJson.resolved(fixture.getValue("resolved").toString())
        assertEquals(page.items.single().id, resolved.trackId)
        assertTrue(resolved.seekable)
    }

    @Test fun candidatePagesSendOnlyPaginationQueryAndKeepDiscoveryIds() = runBlocking {
        server { server, repo ->
            val search = fixture.getValue("search").jsonObject
            val response = JsonObject(search + mapOf(
                "strategy" to JsonPrimitive("rolling_candidates"),
                "generatedAt" to JsonPrimitive("2026-11-20T00:00:00Z"),
                "nextCursor" to JsonPrimitive("next-page"),
                "discoveryTrackIds" to JsonArray(listOf(JsonPrimitive("original-001")))
            ))
            server.enqueue(MockResponse().setBody(response.toString()))
            val page = repo.candidates(cursor = "first-page")
            assertEquals("next-page", page.nextCursor)
            assertEquals(setOf("original-001"), page.discoveryTrackIds)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(setOf("limit", "cursor"), request.requestUrl!!.queryParameterNames)
            assertTrue(request.requestUrl!!.encodedPath.endsWith("/recommendations/candidates"))
            assertEquals(0L, request.bodySize)
        }
    }

    @Test fun alternateResolveSendsExclusionAndRejectsThatProviderInTheResponse() = runBlocking {
        server { server, repository ->
            val response = fixture.getValue("resolved").jsonObject
            val provider = response.getValue("providerId").jsonPrimitive.content
            server.enqueue(MockResponse().setBody(response.toString()))
            assertEquals(MusicFailure.INVALID_RESPONSE,
                failureSuspend { repository.resolveAlternate("original-001", provider) }.failure)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(provider, body.getValue("excludedProviderId").jsonPrimitive.content)
            assertFalse("preferredProviderId" in body)
            server.enqueue(MockResponse().setBody(JsonObject(response + ("providerId" to JsonPrimitive("alternate"))).toString()))
            assertEquals("alternate", repository.resolveAlternate("original-001", provider).providerId)
        }
    }

    @Test fun unknownFieldsAreIgnoredButMissingOrWrongRequiredFieldsFail() {
        val page = fixture.getValue("search").jsonObject
        assertEquals(1, MusicJson.page(JsonObject(page + ("future" to JsonPrimitive(true))).toString()).items.size)
        assertEquals(MusicFailure.INVALID_RESPONSE, failure { MusicJson.page("{}") }.failure)
        val track = page.getValue("items").jsonArray.single().jsonObject
        for (field in listOf("id", "title", "artists", "sources", "availability")) {
            val body = JsonObject(page + ("items" to JsonArray(listOf(JsonObject(track - field)))))
            assertEquals(MusicFailure.INVALID_RESPONSE, failure { MusicJson.page(body.toString()) }.failure)
        }
        assertEquals(MusicFailure.INVALID_RESPONSE, failure { MusicJson.resolved("{}") }.failure)
        val resolved = fixture.getValue("resolved").jsonObject
        val audio = resolved.getValue("audio").jsonObject
        for (url in listOf("http://media.example.org/a.mp3", "https://127.0.0.1/a.mp3", "https://user:password@media.example.org/a.mp3")) {
            val body = JsonObject(resolved + ("audio" to JsonObject(audio + ("url" to JsonPrimitive(url)))))
            assertEquals(MusicFailure.INVALID_RESPONSE, failure { MusicJson.resolved(body.toString()) }.failure)
        }
    }

    @Test fun optionalSourceConfidenceAndEditionAreValidatedWithoutInventingPlaybackCapability() {
        val page = fixture.getValue("search").jsonObject
        val track = page.getValue("items").jsonArray.single().jsonObject
        val source = track.getValue("sources").jsonArray.single().jsonObject
        fun body(confidence: JsonElement) = JsonObject(page + ("items" to JsonArray(listOf(JsonObject(track +
            ("availability" to JsonPrimitive("metadata_only")) + ("sources" to JsonArray(listOf(JsonObject(source +
                ("confidence" to confidence) + ("edition" to JsonPrimitive("studio")))))))))))
        val mapped = MusicJson.page(body(JsonPrimitive(.99)).toString()).items.single()
        assertEquals(.99, mapped.matches.single().confidence!!, 0.0)
        assertEquals("studio", mapped.matches.single().edition)
        assertFalse(mapped.canStartPlayback)
        assertEquals(MusicFailure.INVALID_RESPONSE, failure { MusicJson.page(body(JsonPrimitive(2)).toString()) }.failure)
    }

    @Test fun httpsRequestsEncodeQueriesAndResolveThroughTheSameContract() = runBlocking {
        server { server, repository ->
            server.enqueue(MockResponse().setBody(fixture.getValue("search").toString()))
            val page = repository.search("睡醒 & qix")
            assertEquals("original-001", page.items.single().id)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/music/search", request.requestUrl!!.encodedPath)
            assertEquals("睡醒 & qix", request.requestUrl!!.queryParameter("q"))
            assertNotNull(request.handshake)
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody(fixture.getValue("resolved").toString()))
            assertEquals("original-001", repository.resolve("original-001").trackId)
            val resolve = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", resolve.method)
            assertEquals("original-001", Json.parseToJsonElement(resolve.body.readUtf8()).jsonObject["trackId"]!!.jsonPrimitive.content)
        }
    }

    @Test fun recommendationRequestDoesNotSendPaginationParameters() = runBlocking {
        server { server, repository ->
            val track = fixture.getValue("search").jsonObject.getValue("items").jsonArray.single().jsonObject
            val items = (1..75).map { JsonObject(track + ("id" to JsonPrimitive("track-$it"))) }
            val body = buildJsonObject {
                put("items", JsonArray(items))
                put("nextCursor", JsonNull)
                put("strategy", "public_daily_music")
                put("generatedAt", "2026-11-19T01:00:00Z")
            }
            server.enqueue(MockResponse().setBody(body.toString()))
            val page = repository.recommendations(limit = 1, cursor = "legacy")
            assertEquals(75, page.items.size)
            assertNull(page.nextCursor)
            assertEquals("/api/music/recommendations", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        }
    }

    @Test fun limitedAndUnauthorizedRequestsAreNotRetried() = runBlocking {
        server { server, repository ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody(fixture.getValue("error").toString()))
            val limited = failureSuspend { repository.recommendations() }
            assertEquals(MusicFailure.RATE_LIMITED, limited.failure)
            assertEquals(30L, limited.retryAfterSeconds)
            assertEquals(MusicFailure.RATE_LIMITED, failureSuspend { repository.resolve("original-001") }.failure)
            assertEquals(1, server.requestCount)
        }
        server { server, repository ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private upstream details"))
            val unauthorized = failureSuspend { repository.resolve("original-001") }
            assertEquals(MusicFailure.UNAUTHORIZED, unauthorized.failure)
            assertEquals("UNAUTHORIZED", unauthorized.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectsAndOversizedResponsesAreRejectedWithoutFollowing() = runBlocking {
        server { server, repository ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.example.org/private"))
            assertEquals(MusicFailure.INTERNAL_ERROR, failureSuspend { repository.recommendations() }.failure)
            server.enqueue(MockResponse().setBody("x".repeat(256 * 1024 + 1)))
            assertEquals(MusicFailure.INVALID_RESPONSE, failureSuspend { repository.recommendations() }.failure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun sourceOutageWithRetryAfterBlocksAllRequestsWithoutCallingItRateLimited() = runBlocking {
        for ((status, reason) in listOf(503 to MusicFailure.SOURCE_UNAVAILABLE, 504 to MusicFailure.SOURCE_TIMEOUT)) {
            server { server, repository ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "15").setBody("{broken"))
                assertEquals(reason, failureSuspend { repository.recommendations() }.failure)
                assertEquals(reason, failureSuspend { repository.search("qix") }.failure)
                assertEquals(reason, failureSuspend { repository.resolve("original-001") }.failure)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun cancellationCancelsTheInFlightRequest() = runBlocking {
        server { server, repository ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = launch { repository.recommendations() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(1, TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun fakeCatalogExhaustsAndNeverInventsAudio() = runBlocking {
        val tracks = listOf(MusicTrack("a", "A", listOf("qix")), MusicTrack("b", "B", listOf("qix")))
        val repository = FakeMusicRepository(tracks)
        val first = repository.recommendations(limit = 1)
        assertEquals("a", first.items.single().id)
        val second = repository.recommendations(limit = 1, cursor = first.nextCursor)
        assertEquals("b", second.items.single().id)
        assertNull(second.nextCursor)
        assertEquals(MusicFailure.SOURCE_UNAVAILABLE, failureSuspend { repository.resolve("a") }.failure)
    }

    @Test fun debugHttpUsesOnlyExplicitLocalOrigins() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/api/music/")
            assertThrows(IllegalArgumentException::class.java) { MusicApiClient(base, { token }) }
            server.enqueue(MockResponse().setBody(fixture.getValue("search").toString()))
            val local = MusicApiClient(base, { token }, allowLocalHttp = true)
            assertEquals(1, local.search("qix").items.size)
            assertEquals("/api/music/search", server.takeRequest().requestUrl!!.encodedPath)
            assertThrows(IllegalArgumentException::class.java) {
                MusicApiClient(base.newBuilder().host("public.example.org").build(), { token }, allowLocalHttp = true)
            }
        }
        assertTrue(isDebugMusicHost("192.168.1.2"))
        assertTrue(isDebugMusicHost("172.31.0.2"))
        assertFalse(isDebugMusicHost("172.32.0.2"))
    }

    private fun failure(block: () -> Any?): MusicException {
        try { block(); error("Expected MusicException") } catch (error: MusicException) { return error }
    }
    private suspend fun failureSuspend(block: suspend () -> Any?): MusicException {
        try { block(); error("Expected MusicException") } catch (error: MusicException) { return error }
    }
    private suspend fun server(block: suspend (MockWebServer, MusicRepository) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }).build()
            block(server, MusicApiClient(server.url("/api/music/"), { token }, http, { Instant.parse("2026-11-16T00:00:00Z") }))
        }
    }
}
