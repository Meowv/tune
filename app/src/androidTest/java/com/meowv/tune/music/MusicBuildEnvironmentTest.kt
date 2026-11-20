package com.meowv.tune.music

import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.BuildConfig
import com.meowv.tune.domain.music.MusicException
import com.meowv.tune.domain.music.MusicFailure
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicBuildEnvironmentTest {
    @Test fun debugBuildUsesTheLocalApiOrigin() {
        val base = BuildConfig.MUSIC_API_BASE_URL.toHttpUrl()
        assertTrue(BuildConfig.DEBUG)
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicLocalBuild") == "true")
        assertTrue(isDebugMusicHost(base.host))
    }

    @Test fun registersAndClaimsOnTheLocalApi() = runBlocking {
        val (url, file) = activationFixture()
        val storage = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = storage) { file }
        val activation = DeviceActivation(prefs, MusicActivationClient(url, allowLocalHttp = true))
        val foreground = launch { activation.runWhileForeground() }
        try {
            withTimeout(90_000) {
                activation.state.first { it.phase == MusicDevicePhase.PENDING }
                assertNull(activation.readToken())
                activation.state.first { it.phase == MusicDevicePhase.ACTIVE }
            }
            assertNotNull(activation.readToken())
        } finally {
            foreground.cancelAndJoin()
            storage.cancel()
        }
    }

    @Test fun restoresReactivatesAndRegistersAgainAfterDeletion() = runBlocking {
        val (url, file) = activationFixture()
        assertTrue(file.isFile)
        val storage = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = storage) { file }
        val activation = DeviceActivation(prefs, MusicActivationClient(url, allowLocalHttp = true))
        val foreground = launch(start = CoroutineStart.LAZY) { activation.runWhileForeground() }
        try {
            assertNotNull(activation.readToken())
            foreground.start()
            withTimeout(30_000) { activation.state.first { it.phase == MusicDevicePhase.ACTIVE } }
            val repository = MusicApiClient(url, activation::readToken, allowLocalHttp = true, onUnauthorized = activation::onUnauthorized)
            // 用已鉴权请求的来源检查确认令牌有效，避免访问真实曲源。
            val original = activation.readToken()
            assertNotNull(original)
            assertEquals(MusicFailure.FORBIDDEN, probe(repository))
            waitForRejection(repository)
            withTimeout(30_000) { activation.state.first { it.phase == MusicDevicePhase.REVOKED } }
            assertNull(activation.readToken())
            assertEquals(MusicFailure.NOT_CONFIGURED, probe(repository))
            report("Device confirmed revocation")

            withTimeout(75_000) { activation.state.first { it.phase == MusicDevicePhase.ACTIVE } }
            val renewed = activation.readToken()
            assertNotNull(renewed)
            assertTrue(renewed != original)
            assertEquals(MusicFailure.FORBIDDEN, probe(repository))
            report("Device claimed a replacement credential")

            waitForRejection(repository)
            val pending = withTimeout(75_000) {
                activation.state.first { it.phase == MusicDevicePhase.PENDING }
            }
            assertNotNull(pending.shortCode)
            assertNull(activation.readToken())
            assertEquals(MusicFailure.NOT_CONFIGURED, probe(repository))
            report("Device registered again and is waiting for approval")

            withTimeout(30_000) { activation.state.first { it.phase == MusicDevicePhase.ACTIVE } }
            assertEquals(MusicFailure.FORBIDDEN, probe(repository))
        } finally {
            foreground.cancelAndJoin()
            storage.cancel()
        }
    }

    private fun activationFixture(): Pair<HttpUrl, File> {
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("musicActivationBaseUrl")
        val fixtureId = arguments.getString("musicActivationFixtureId")
        assumeTrue(origin != null && fixtureId != null)
        val url = checkNotNull(origin).toHttpUrl()
        assertTrue(isDebugMusicHost(url.host))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.fromString(fixtureId)
        return url to File(context.cacheDir, "activation-$id.preferences_pb")
    }

    private suspend fun waitForRejection(repository: MusicApiClient) {
        withTimeout(45_000) {
            while (probe(repository) != MusicFailure.UNAUTHORIZED) delay(1_000)
        }
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", message) })
    }

    private suspend fun probe(repository: MusicApiClient): MusicFailure? = try {
        repository.resolve("device-activation-probe", preferredProviderId = "activation-fixture-disabled")
        null
    } catch (failure: MusicException) {
        failure.failure
    }
}
