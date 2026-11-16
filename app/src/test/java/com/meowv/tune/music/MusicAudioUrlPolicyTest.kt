package com.meowv.tune.music

import com.meowv.tune.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import org.junit.Assert.*
import org.junit.Test

class MusicAudioUrlPolicyTest {
    @Test fun debugExceptionIsLimitedToTwoFilesOnTheConfiguredOrigin() {
        val base = BuildConfig.MUSIC_API_BASE_URL.toHttpUrl()
        if (base.isHttps || !isDebugMusicHost(base.host)) return
        val url = base.newBuilder().addPathSegments("demo-audio/original-001.mp3").build()
        assertTrue(MusicAudioUrlPolicy.isLocalDemo(url))
        assertFalse(MusicAudioUrlPolicy.isLocalDemo(url.newBuilder().host("public.example.org").build()))
        assertFalse(MusicAudioUrlPolicy.isLocalDemo(url.newBuilder().port(base.port + 1).build()))
        assertFalse(MusicAudioUrlPolicy.isLocalDemo(url.newBuilder().addQueryParameter("token", "forbidden").build()))
        assertFalse(MusicAudioUrlPolicy.isLocalDemo(url.newBuilder().encodedPath("/api/music/setup/devices").build()))
        var delegated = false
        val normal = Call.Factory { delegated = true; throw IllegalStateException("Normal transport selected") }
        try { MusicAudioUrlPolicy.calls(normal).newCall(okhttp3.Request.Builder().url("https://example.org/audio.mp3").build()) } catch (_: IllegalStateException) { }
        assertTrue(delegated)
    }
}
