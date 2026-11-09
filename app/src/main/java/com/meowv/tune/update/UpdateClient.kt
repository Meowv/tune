package com.meowv.tune.update

import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * 版本清单专用 GET：只接受 HTTPS、不跟随跳转、限制响应体大小。
 * 与目录请求同源（OkHttp），失败映射沿用 [TuneFailure]，不引入新的网络框架。
 */
internal class UpdateClient(
    version: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        // 清单是幂等 GET：允许 OkHttp 在连接被服务端回收时换一条连接重试，
        // 否则偶发的连接复用失败会被误报成「网络不可用」。
        .retryOnConnectionFailure(true)
        .build()
) {
    private val userAgent = "MeowvTune/$version"

    suspend fun get(url: HttpUrl): String {
        require(url.isHttps) { "Update manifest must be HTTPS" }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent).get().build()
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(mapIoFailure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        continuation.resume(response.use(::readBody))
                    } catch (failure: TuneFailure) {
                        continuation.resumeWithException(failure)
                    } catch (e: IOException) {
                        continuation.resumeWithException(mapIoFailure(e))
                    }
                }
            })
        }
    }

    private fun readBody(response: Response): String {
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
        const val MAX_BODY_BYTES = 256L * 1024
    }
}
