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
    allowLocalHttp: Boolean = false
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
    override suspend fun recommendations(limit: Int, cursor: String?) = MusicJson.page(request("recommendations", limit, cursor), recommendation = true)
    override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
        if (trackId.isBlank() || trackId.length > 128) throw MusicException(MusicFailure.INVALID_REQUEST)
        val body = buildJsonObject {
            put("trackId", trackId); put("quality", "auto")
            preferredProviderId?.let { put("preferredProviderId", it) }
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return MusicJson.resolved(execute(Request.Builder().url(baseUrl.newBuilder().addPathSegment("resolve").build()).post(body)))
            .also { if (it.trackId != trackId) throw MusicException(MusicFailure.INVALID_RESPONSE) }
    }

    private suspend fun request(path: String, limit: Int, cursor: String?, query: String? = null): String {
        if (limit !in 1..50 || cursor != null && cursor.length !in 1..128) throw MusicException(MusicFailure.INVALID_REQUEST)
        val url = baseUrl.newBuilder().addPathSegment(path).addQueryParameter("limit", limit.toString()).apply {
            query?.let { addQueryParameter("q", it) }; cursor?.let { addQueryParameter("cursor", it) }
        }.build()
        return execute(Request.Builder().url(url).get())
    }

    private suspend fun execute(builder: Request.Builder): String {
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
                            val source = value.body?.source() ?: throw MusicException(MusicFailure.INVALID_RESPONSE)
                            val buffer = Buffer()
                            while (buffer.size <= MAX_BYTES) {
                                if (source.read(buffer, minOf(8192, MAX_BYTES + 1 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > MAX_BYTES) throw MusicException(MusicFailure.INVALID_RESPONSE)
                            val body = buffer.readUtf8()
                            if (!value.isSuccessful) throw MusicJson.error(body, value.code, retryAfter(value.header("Retry-After")))
                            body
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: MusicException) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } catch (error: IOException) {
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

    private fun retryAfter(value: String?): Long? = value?.toLongOrNull()?.takeIf { it >= 0 }
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().epochSecond - now().epochSecond }.getOrNull()?.coerceAtLeast(1)

    private companion object { const val MAX_BYTES = 256L * 1024 }
}

internal fun isDebugMusicHost(host: String): Boolean = host in setOf("localhost", "127.0.0.1", "10.0.2.2", "10.0.3.2") ||
    host.matches(Regex("10\\.[0-9.]+|192\\.168\\.[0-9.]+|172\\.(1[6-9]|2[0-9]|3[01])\\.[0-9.]+"))
