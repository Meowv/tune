package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import com.meowv.tune.BuildConfig
import java.net.Proxy
import com.meowv.tune.playback.MediaUrlPolicy
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

internal class MusicApiClient(
    private val baseUrl: HttpUrl,
    private val accessToken: suspend () -> String?,
    client: OkHttpClient? = null,
    private val now: () -> Instant = Instant::now,
    allowLocalHttp: Boolean = false,
    private val requestGate: MusicRequestGate = MusicRequestGate()
) : MusicRepository {
    private val localHttp = allowLocalHttp && BuildConfig.DEBUG && isDebugMusicHost(baseUrl.host)
    private val transport = client ?: if (localHttp && !baseUrl.isHttps) OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (url.scheme != baseUrl.scheme || url.host != baseUrl.host || url.port != baseUrl.port) throw IOException("Unexpected music API origin")
            chain.proceed(chain.request())
        }.build() else MediaUrlPolicy.client(httpsOnly = true)
    private val http = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(8, TimeUnit.SECONDS).cache(null).build()

    init {
        require((baseUrl.isHttps || localHttp) && baseUrl.username.isEmpty() && baseUrl.password.isEmpty() && baseUrl.query == null && baseUrl.fragment == null)
        require(baseUrl.encodedPath.endsWith('/'))
    }

    override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
        if (query.trim().length !in 1..100 || query.any(Char::isISOControl)) throw MusicException(MusicFailure.INVALID_REQUEST)
        return MusicJson.page(request("search", limit, cursor, query))
    }
    override suspend fun recommendations(limit: Int, cursor: String?): MusicPage {
        val url = baseUrl.newBuilder().addPathSegment("recommendations").build()
        return MusicJson.page(execute(Request.Builder().url(url).get(), maxBytes = 1024L * 1024),
            recommendation = true).copy(nextCursor = null)
    }
    override suspend fun candidates(limit: Int, cursor: String?): MusicPage {
        val url = baseUrl.newBuilder().addPathSegment("recommendations").addPathSegment("candidates")
            .addQueryParameter("limit", limit.toString()).apply {
                if (cursor != null) addQueryParameter("cursor", cursor)
            }.build()
        val body = try {
            execute(Request.Builder().url(url).get(), blockOnFailure = false)
        } catch (failure: MusicException) {
            if (failure.failure == MusicFailure.RATE_LIMITED) requestGate.block(failure.retryAfterSeconds)
            throw failure
        }
        return MusicJson.page(body, recommendation = true)
    }

    override suspend fun radioRecommendations(): List<MusicTrack> = try {
        MusicJson.page(request("recommendations/radio", 20, null), recommendation = true).items
    } catch (failure: MusicException) {
        if (failure.failure == MusicFailure.TRACK_NOT_FOUND) emptyList() else throw failure
    }

    override suspend fun lyrics(trackId: String, providerId: String): List<MusicLyricLine> {
        val url = baseUrl.newBuilder().addPathSegment("lyrics").addQueryParameter("trackId", trackId)
            .addQueryParameter("providerId", providerId).build()
        return MusicJson.lyrics(execute(Request.Builder().url(url).get(), blockOnFailure = false), trackId)
    }

    override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio =
        resolve(trackId, preferredProviderId, excludedProviderId = null)

    override suspend fun resolveAlternate(trackId: String, excludedProviderId: String): PlayableAudio =
        resolve(trackId, preferredProviderId = null, excludedProviderId = excludedProviderId)

    private suspend fun resolve(trackId: String, preferredProviderId: String?, excludedProviderId: String?): PlayableAudio {
        if (trackId.isBlank() || trackId.length > 128) throw MusicException(MusicFailure.INVALID_REQUEST)
        val body = buildJsonObject {
            put("trackId", trackId); put("quality", "auto")
            preferredProviderId?.let { put("preferredProviderId", it) }
            excludedProviderId?.let { put("excludedProviderId", it) }
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return MusicJson.resolved(execute(Request.Builder().url(baseUrl.newBuilder().addPathSegment("resolve").build()).post(body)))
            .also {
                if (it.trackId != trackId || it.providerId == excludedProviderId ||
                    preferredProviderId != null && it.providerId != preferredProviderId) {
                    throw MusicException(MusicFailure.INVALID_RESPONSE)
                }
            }
    }

    private suspend fun request(path: String, limit: Int, cursor: String?, query: String? = null): String {
        if (limit !in 1..50 || cursor != null && cursor.length !in 1..128) throw MusicException(MusicFailure.INVALID_REQUEST)
        val url = baseUrl.newBuilder().addPathSegments(path).addQueryParameter("limit", limit.toString()).apply {
            query?.let { addQueryParameter("q", it) }; cursor?.let { addQueryParameter("cursor", it) }
        }.build()
        return execute(Request.Builder().url(url).get())
    }

    private suspend fun execute(builder: Request.Builder, blockOnFailure: Boolean = true, maxBytes: Long = MAX_BYTES): String {
        requestGate.check()
        val token = accessToken()?.takeIf { it.length in 32..1024 && it.none(Char::isISOControl) }
            ?: throw MusicException(MusicFailure.NOT_CONFIGURED)
        val request = builder.header("Authorization", "Bearer $token").header("Accept", "application/json")
            .header("Cache-Control", "no-store").build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ioFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { value ->
                            if (blockOnFailure && value.code == 429) retryAfter(value.header("Retry-After"))?.let { requestGate.block(it) }
                            if (blockOnFailure && value.code in setOf(503, 504)) retryAfter(value.header("Retry-After"))?.let {
                                requestGate.block(it, if (value.code == 503) MusicFailure.SOURCE_UNAVAILABLE else MusicFailure.SOURCE_TIMEOUT)
                            }
                            val source = value.body?.source() ?: throw MusicException(MusicFailure.INVALID_RESPONSE)
                            val buffer = Buffer()
                            while (buffer.size <= maxBytes) {
                                if (source.read(buffer, minOf(8192, maxBytes + 1 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > maxBytes) throw MusicException(MusicFailure.INVALID_RESPONSE)
                            val body = buffer.readUtf8()
                            if (!value.isSuccessful) {
                                val failure = MusicJson.error(body, value.code, retryAfter(value.header("Retry-After")))
                                if (blockOnFailure && value.code == 429) requestGate.block(failure.retryAfterSeconds)
                                if (blockOnFailure && value.code in setOf(503, 504) && failure.retryAfterSeconds != null)
                                    requestGate.block(failure.retryAfterSeconds, failure.failure)
                                throw failure
                            }
                            body
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: MusicException) {
                        if (blockOnFailure && response.code == 429 && failure.failure != MusicFailure.RATE_LIMITED) requestGate.block(retryAfter(response.header("Retry-After")))
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } catch (error: IOException) {
                        if (blockOnFailure && response.code == 429) requestGate.block(retryAfter(response.header("Retry-After")))
                        if (continuation.isActive) continuation.resumeWithException(ioFailure(error))
                    }
                }
            })
        }
    }

    private fun ioFailure(error: IOException) = MusicException(when (error) {
        is SSLException -> MusicFailure.TLS
        is InterruptedIOException -> MusicFailure.SOURCE_TIMEOUT
        else -> MusicFailure.CONNECTION
    })

    private fun retryAfter(value: String?): Long? = musicRetryAfterSeconds(value, now())

    private companion object { const val MAX_BYTES = 256L * 1024 }
}

internal fun isDebugMusicHost(host: String): Boolean = host in setOf("localhost", "127.0.0.1", "10.0.2.2", "10.0.3.2") ||
    host.matches(Regex("10\\.[0-9.]+|192\\.168\\.[0-9.]+|172\\.(1[6-9]|2[0-9]|3[01])\\.[0-9.]+"))

internal fun musicRetryAfterSeconds(value: String?, now: Instant = Instant.now()): Long? =
    value?.toLongOrNull()?.takeIf { it >= 0 }
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().epochSecond - now.epochSecond }
            .getOrNull()?.coerceAtLeast(1)
