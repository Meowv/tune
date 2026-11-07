package com.meowv.tune.playback

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

internal sealed interface MediaProbeResult {
    data class Redirect(val location: String) : MediaProbeResult
    data object Direct : MediaProbeResult
    data class Playlist(val format: PlaylistFormat, val text: String) : MediaProbeResult
}

internal enum class PlaylistFormat { M3U, PLS, ASX }

internal class MediaProbe(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(4, TimeUnit.SECONDS).build()

    suspend fun get(url: HttpUrl): MediaProbeResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", "MeowvTune").build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(ioFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response.use { read(it) })
                } catch (failure: TuneFailure) {
                    continuation.resumeWithException(failure)
                } catch (error: IOException) {
                    continuation.resumeWithException(ioFailure(error))
                }
            }
        })
    }

    private fun read(response: Response): MediaProbeResult {
        if (response.code in setOf(301, 302, 303, 307, 308)) {
            return MediaProbeResult.Redirect(response.header("Location") ?: throw TuneFailure(TuneError.INVALID_RESPONSE))
        }
        if (!response.isSuccessful) throw TuneFailure(if (response.code >= 500) TuneError.SERVER else TuneError.HTTP, response.code)
        val body = response.body ?: throw TuneFailure(TuneError.INVALID_RESPONSE)
        val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
        val path = response.request.url.encodedPath.lowercase()
        val format = when {
            mime.contains("mpegurl") || path.endsWith(".m3u") || path.endsWith(".m3u8") -> PlaylistFormat.M3U
            mime.contains("scpls") || path.endsWith(".pls") -> PlaylistFormat.PLS
            mime.contains("asx") || path.endsWith(".asx") || path.endsWith(".wax") -> PlaylistFormat.ASX
            else -> null
        }
        if (format == null && (mime.startsWith("audio/") || mime.startsWith("video/")) && mime != "video/x-ms-asf") {
            return MediaProbeResult.Direct
        }
        val source = body.source()
        source.request(SNIFF_BYTES)
        val prefix = source.buffer.snapshot(minOf(SNIFF_BYTES, source.buffer.size).toInt()).utf8().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val inferred = format ?: when {
            prefix.startsWith("#EXTM3U", ignoreCase = true) -> PlaylistFormat.M3U
            prefix.startsWith("[playlist]", ignoreCase = true) -> PlaylistFormat.PLS
            prefix.startsWith("<asx", ignoreCase = true) || prefix.startsWith("<?xml") && prefix.contains("<asx", true) -> PlaylistFormat.ASX
            prefix.startsWith("http://") || prefix.startsWith("https://") -> PlaylistFormat.M3U
            mime.startsWith("text/") || prefix.isBlank() || prefix.startsWith("<") -> throw TuneFailure(TuneError.INVALID_RESPONSE)
            else -> return MediaProbeResult.Direct
        }
        if (body.contentLength() > MAX_PLAYLIST_BYTES) throw TuneFailure(TuneError.INVALID_RESPONSE)
        val data = Buffer()
        while (data.size <= MAX_PLAYLIST_BYTES) {
            if (source.read(data, minOf(8192L, MAX_PLAYLIST_BYTES + 1 - data.size)) == -1L) {
                return MediaProbeResult.Playlist(inferred, data.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
            }
        }
        throw TuneFailure(TuneError.INVALID_RESPONSE)
    }

    private fun ioFailure(error: IOException) = TuneFailure(when (error) {
        is SSLException -> TuneError.TLS
        is InterruptedIOException -> TuneError.TIMEOUT
        else -> TuneError.CONNECTION
    })

    private companion object {
        const val SNIFF_BYTES = 512L
        const val MAX_PLAYLIST_BYTES = 64L * 1024
    }
}
