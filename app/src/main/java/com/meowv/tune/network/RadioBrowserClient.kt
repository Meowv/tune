package com.meowv.tune.network

import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

class RadioBrowserClient internal constructor(
    private val transport: DirectoryHttp,
    private val mirrors: MirrorDirectory
) {
    // 计数请求可能已送达镜像，网络失败后由调用方使用目录元数据兜底。
    internal suspend fun recordPlaybackClick(uuid: UUID): String {
        val host = mirrors.candidates().firstOrNull() ?: throw TuneFailure(TuneError.CONNECTION)
        return transport.get(host, "/json/url/$uuid", timeoutMillis = 3000)
    }

    suspend fun get(endpoint: String, query: Map<String, String> = emptyMap()): String {
        require(endpoint.matches(ENDPOINT)) { "Expected a Radio Browser JSON endpoint" }
        var lastFailure: TuneFailure? = null
        for (host in mirrors.candidates().take(MAX_ATTEMPTS)) {
            currentCoroutineContext().ensureActive()
            val response = try {
                transport.get(host, endpoint, query)
            } catch (failure: TuneFailure) {
                if (failure.kind !in retryable) throw failure
                mirrors.recordFailure(host)
                lastFailure = failure
                continue
            }
            mirrors.recordSuccess(host)
            return response
        }
        throw lastFailure ?: TuneFailure(TuneError.CONNECTION)
    }

    private companion object {
        val ENDPOINT = Regex("/json(?:/[a-zA-Z0-9_-]+)+")
        val retryable = setOf(TuneError.TIMEOUT, TuneError.CONNECTION, TuneError.TLS, TuneError.SERVER)
        const val MAX_ATTEMPTS = 4
    }
}

internal class DirectoryHttp(
    version: String,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val userAgent = "MeowvTune/$version"
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun get(
        host: MirrorHost,
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null
    ): String = suspendCancellableCoroutine { continuation ->
        val url = HttpUrl.Builder().scheme("https").host(host.name)
            .addPathSegments(endpoint.removePrefix("/")).apply {
                query.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).get().build())
        if (timeoutMillis != null) call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(mapIoFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response.use(::readResponse))
                } catch (failure: TuneFailure) {
                    continuation.resumeWithException(failure)
                } catch (e: IOException) {
                    continuation.resumeWithException(mapIoFailure(e))
                }
            }
        })
    }

    private fun readResponse(response: Response): String {
        if (!response.isSuccessful) {
            val kind = if (response.code in 500..599) TuneError.SERVER else TuneError.HTTP
            throw TuneFailure(kind, response.code)
        }
        val body = response.body ?: throw TuneFailure(TuneError.INVALID_RESPONSE)
        val source = body.source()
        val buffer = Buffer()
        while (buffer.size <= MAX_BODY_BYTES) {
            val read = source.read(buffer, minOf(8192L, MAX_BODY_BYTES + 1 - buffer.size))
            if (read == -1L) return buffer.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        }
        throw TuneFailure(TuneError.INVALID_RESPONSE)
    }

    private fun mapIoFailure(error: IOException) = TuneFailure(when (error) {
        is SSLException -> TuneError.TLS
        is InterruptedIOException -> TuneError.TIMEOUT
        else -> TuneError.CONNECTION
    })

    private companion object {
        const val MAX_BODY_BYTES = 4L * 1024 * 1024
    }
}
