package com.meowv.tune.music

import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicBuildEnvironmentTest {
    @Test fun debugBuildConnectsToTheLocalApiWithItsOwnCredential() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicLocalBuild") == "true")
        assertTrue(BuildConfig.DEBUG)
        val base = BuildConfig.MUSIC_API_BASE_URL.toHttpUrl()
        assertTrue(isDebugMusicHost(base.host))
        val repository = MusicApiClient(base, { BuildConfig.MUSIC_DEVICE_TOKEN }, allowLocalHttp = true)
        assertTrue(repository.search("Kevin MacLeod").items.any { track -> track.matches.any { it.providerId == "netease" } })
    }
}
