package com.meowv.tune.music

import com.meowv.tune.BuildConfig
import com.meowv.tune.playback.MediaUrlPolicy
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal object MusicAudioUrlPolicy {
    private val base get() = BuildConfig.MUSIC_API_BASE_URL.toHttpUrlOrNull()
    fun isLocalDemo(url: HttpUrl): Boolean {
        val origin = base ?: return false
        return BuildConfig.DEBUG && origin.scheme == "http" && isDebugMusicHost(origin.host) &&
            url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath in setOf(origin.encodedPath + "demo-audio/original-001.mp3", origin.encodedPath + "demo-audio/original-002.mp3")
    }
    fun allows(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return isLocalDemo(url) || url.isHttps && MediaUrlPolicy.parse(value) != null
    }
    fun calls(normal: Call.Factory): Call.Factory {
        if (!BuildConfig.DEBUG || base?.scheme != "http") return normal
        val local = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(3, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (!isLocalDemo(chain.request().url)) throw java.io.IOException("Blocked local music path")
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer " + BuildConfig.MUSIC_DEVICE_TOKEN).build())
            }.build()
        return Call.Factory { request -> if (isLocalDemo(request.url)) local.newCall(request) else normal.newCall(request) }
    }
}
