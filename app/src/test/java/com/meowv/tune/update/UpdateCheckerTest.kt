package com.meowv.tune.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val checker = UpdateChecker()
    private val local = LocalVersion(
        packageName = "com.meowv.tune",
        versionName = "1.0.0",
        versionCode = 5,
        sdkInt = 30,
        channel = "stable"
    )

    @Test
    fun higherVersionCodeIsAnUpdate() {
        val verdict = checker.evaluate(local, info(versionCode = 6))
        assertTrue(verdict is UpdateVerdict.Available)
    }

    @Test
    fun equalVersionCodeAndNameIsUpToDate() {
        val verdict = checker.evaluate(local, info(versionCode = 5, versionName = local.versionName))
        assertTrue(verdict is UpdateVerdict.UpToDate)
    }

    @Test
    fun lowerVersionCodeIsAvailable() {
        val verdict = checker.evaluate(local, info(versionCode = 4))
        assertTrue(verdict is UpdateVerdict.Available)
    }

    @Test
    fun changingOnlyVersionNameIsAvailable() {
        val verdict = checker.evaluate(local, info(versionCode = 101, versionName = "0.9.0"))
        assertTrue(verdict is UpdateVerdict.Available)
        val sameCode = checker.evaluate(local, info(versionCode = 5, versionName = "9.9.9"))
        assertTrue(sameCode is UpdateVerdict.Available)
    }

    @Test
    fun changingOnlyVersionCodeIsAvailableEvenWhenLower() {
        for (code in listOf(4L, 6L)) {
            assertTrue(checker.evaluate(local, info(versionCode = code, versionName = local.versionName))
                is UpdateVerdict.Available)
        }
    }

    @Test
    fun packageMismatchFails() {
        assertFailure(UpdateFailure.PACKAGE_MISMATCH, info(packageName = "com.example.other"))
    }

    @Test
    fun channelMismatchFails() {
        assertFailure(UpdateFailure.CHANNEL_MISMATCH, info(channel = "beta"))
    }

    @Test
    fun blankVersionNameFails() {
        assertFailure(UpdateFailure.MALFORMED, info(versionName = "  "))
    }

    @Test
    fun invalidVersionCodeFails() {
        assertFailure(UpdateFailure.MALFORMED, info(versionCode = 0))
        assertFailure(UpdateFailure.MALFORMED, info(versionCode = Int.MAX_VALUE.toLong() + 1))
    }

    @Test
    fun insecureApkUrlFails() {
        assertFailure(UpdateFailure.MALFORMED, info(apkUrl = "http://studio.meowv.com/app.apk"))
        assertFailure(UpdateFailure.MALFORMED, info(apkUrl = "not a url"))
    }

    @Test
    fun malformedSha256Fails() {
        assertFailure(UpdateFailure.MALFORMED, info(sha256 = "abc"))
        assertFailure(UpdateFailure.MALFORMED, info(sha256 = "z".repeat(64)))
    }

    @Test
    fun newerMinSdkFails() {
        assertFailure(UpdateFailure.INCOMPATIBLE, info(minSdk = 31))
        assertFailure(UpdateFailure.MALFORMED, info(minSdk = 0))
    }

    @Test
    fun compatibleMinSdkPasses() {
        assertTrue(checker.evaluate(local, info(minSdk = 28)) is UpdateVerdict.Available)
    }

    private fun assertFailure(expected: UpdateFailure, remote: UpdateInfo) {
        val failure = runCatching { checker.evaluate(local, remote) }.exceptionOrNull()
        assertTrue("expected UpdateException, was $failure", failure is UpdateException)
        assertEquals(expected, (failure as UpdateException).failure)
    }

    private fun info(
        packageName: String = "com.meowv.tune",
        channel: String = "stable",
        versionName: String = "1.1.0",
        versionCode: Long = 101,
        minSdk: Int = 28,
        apkUrl: String = "https://studio.meowv.com/data/meowvtune/MeowvTune-1.1.0.apk",
        sha256: String = "a".repeat(64)
    ) = UpdateInfo(
        packageName = packageName,
        channel = channel,
        versionName = versionName,
        versionCode = versionCode,
        minSdkVersion = minSdk,
        releaseDate = "2026-11-15",
        title = "MeowvTune 1.1.0",
        releaseNotes = listOf("优化电台播放体验"),
        apkUrl = apkUrl,
        apkSizeBytes = 25_600_000,
        apkSha256 = sha256
    )
}
