package com.meowv.tune.music

import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class MusicActivationClient(val baseUrl: HttpUrl, allowLocalHttp: Boolean) {
    private val http = musicHttpClient(baseUrl, allowLocalHttp)

    suspend fun register(id: String, key: ByteArray, model: String, os: String): Reply {
        val body = buildJsonObject {
            put("installationId", id)
            put("publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(key))
            put("model", model)
            put("osVersion", os)
        }.toString()
        val url = baseUrl.newBuilder().addPathSegments("devices/register").build()
        return execute(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())))
    }

    suspend fun status(id: String): Reply {
        val url = baseUrl.newBuilder().addPathSegments("devices/status").addPathSegment(id).build()
        return execute(Request.Builder().url(url))
    }

    private suspend fun execute(builder: Request.Builder): Reply = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(builder.header("Accept", "application/json").header("Cache-Control", "no-store").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val wait = musicRetryAfterSeconds(it.header("Retry-After"), Instant.now())
                        val source = it.body?.source()
                        val body = if (source != null && !source.request(4097)) {
                            val content = source.readUtf8()
                            if (it.isSuccessful) Json.parseToJsonElement(content) as? JsonObject
                            else runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull()
                        } else null
                        Reply(it.code, body, wait)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    class Reply(val code: Int, private val body: JsonObject?, val retryAfterSeconds: Long?) {
        fun text(name: String) = body?.get(name)?.jsonPrimitive?.takeIf { it.isString }?.content
        fun errorCode() = (body?.get("error") as? JsonObject)?.get("code")?.jsonPrimitive?.content
    }
}
