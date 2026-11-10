package com.meowv.tune

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.update.install.ApkVerifier
import com.meowv.tune.update.install.ExpectedApk
import com.meowv.tune.update.install.InstallException
import com.meowv.tune.update.install.InstallFailure
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkVerifierTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val verifier = ApkVerifier(context)
    private lateinit var file: File
    private lateinit var installedCopy: File

    @Before
    fun setUp() {
        file = File(context.cacheDir, "verify-test.apk")
        file.writeBytes(ByteArray(1024) { it.toByte() })
        // 用真实安装包跑一遍完整链路：包名、versionCode、minSdk、签名都能对得上。
        installedCopy = File(context.cacheDir, "installed.apk").apply {
            File(installedApkPath()).copyTo(this, overwrite = true)
        }
    }

    @After
    fun tearDown() {
        file.delete()
        installedCopy.delete()
    }

    @Test
    fun installedApkPasses() = runBlocking {
        verifier.verify(installedCopy, expectedFor(installedCopy))
    }

    @Test
    fun packageMismatchFails() = runBlocking {
        assertFailure(
            InstallFailure.PACKAGE_MISMATCH,
            expectedFor(installedCopy, packageName = "com.example.not-meowv"),
            apk = installedCopy
        )
    }

    @Test
    fun versionMismatchFails() = runBlocking {
        assertFailure(
            InstallFailure.VERSION_MISMATCH,
            expectedFor(installedCopy, versionCode = installedVersionCode() + 1),
            apk = installedCopy
        )
    }

    @Test
    fun missingFileFails() = runBlocking {
        file.delete()
        assertFailure(InstallFailure.FILE_MISSING, expected(size = 1024, sha = "0".repeat(64)))
    }

    @Test
    fun sizeMismatchFails() = runBlocking {
        assertFailure(InstallFailure.SIZE_MISMATCH, expected(size = 2048, sha = "0".repeat(64)))
    }

    @Test
    fun checksumMismatchFails() = runBlocking {
        assertFailure(InstallFailure.CHECKSUM_MISMATCH, expected(size = 1024, sha = "0".repeat(64)))
    }

    private suspend fun assertFailure(
        failure: InstallFailure,
        expected: ExpectedApk,
        apk: File = file
    ) {
        val error = runCatching { verifier.verify(apk, expected) }.exceptionOrNull()
        assertTrue("expected InstallException, was $error", error is InstallException)
        assertEquals(failure, (error as InstallException).failure)
    }

    private fun expected(size: Long, sha: String) = ExpectedApk(
        packageName = context.packageName,
        versionCode = 1,
        sizeBytes = size,
        sha256 = sha,
        minSdkVersion = 28
    )

    private fun installedApkPath(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).applicationInfo!!.sourceDir

    private fun installedVersionCode(): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun expectedFor(
        apk: File,
        packageName: String = context.packageName,
        versionCode: Long = installedVersionCode()
    ) = ExpectedApk(
        packageName = packageName,
        versionCode = versionCode,
        sizeBytes = apk.length(),
        sha256 = sha256(apk),
        minSdkVersion = 28
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
