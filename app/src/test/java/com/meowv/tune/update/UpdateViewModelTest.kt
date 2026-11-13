package com.meowv.tune.update

import com.meowv.tune.update.install.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class UpdateViewModelTest {
    private val local = LocalVersion("com.meowv.tune", "1.0", 1, 28, "stable")
    private val remote = UpdateInfo("com.meowv.tune", "stable", "2.0", 2, 28, "2026-11-12",
        "Update", emptyList(), "https://example.com/app.apk", 10, "a".repeat(64))
    private val record = PendingInstall(1, 2, "2.0", "update.apk",
        ExpectedApk(local.packageName, 2, 10, "a".repeat(64), 28))

    @Test fun storageReadFailureIsReportedWithoutEscaping() = runBlocking {
        withModel(store = FakeStore(read = { throw IOException() })) { model, _ ->
            assertEquals(InstallState.Failed(InstallFailure.STORAGE),
                withTimeout(2000) { model.installState.first { it is InstallState.Failed } })
        }
    }

    @Test fun saveFailureCancelsTheEnqueuedDownload() = runBlocking {
        val downloads = FakeDownloads()
        withModel(downloads, FakeStore(saveAction = { throw IOException() })) { model, _ ->
            model.check()
            withTimeout(2000) { model.state.first { it is UpdateUiState.UpdateAvailable } }
            model.download()
            withTimeout(2000) { model.installState.first { it is InstallState.Failed } }
            assertEquals(listOf(1L), downloads.cancelled)
            assertEquals(0, downloads.queries)
        }
    }

    @Test fun cancellingDuringSaveCannotStartAnOldPollOrClearANewDownload() = runBlocking {
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleared = CompletableDeferred<Unit>()
        val downloads = FakeDownloads()
        val store = FakeStore(saveAction = {
            saving.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }, clearAction = { cleared.complete(Unit) })
        withModel(downloads, store) { model, _ ->
            model.check()
            withTimeout(2000) { model.state.first { it is UpdateUiState.UpdateAvailable } }
            model.download()
            withTimeout(2000) { saving.await() }
            model.cancelDownload()
            model.download()
            release.complete(Unit)
            withTimeout(2000) { cleared.await() }
            yield()
            assertEquals(1, downloads.enqueued)
            assertEquals(0, downloads.queries)
            assertEquals(InstallState.Cancelled, model.installState.value)
            model.download()
            yield()
            assertEquals(2, downloads.enqueued)
            assertEquals(1, downloads.queries)
            assertTrue(model.installState.value is InstallState.Downloading)
        }
    }

    @Test fun fileReadFailureDuringVerificationIsReported() = runBlocking {
        val downloads = FakeDownloads(DownloadStatus.SUCCESSFUL)
        withModel(downloads, FakeStore(read = { record }), ApkVerification { _, _ -> throw IOException() }) { model, _ ->
            assertEquals(InstallState.Failed(InstallFailure.STORAGE),
                withTimeout(2000) { model.installState.first { it is InstallState.Failed } })
        }
    }

    @Test fun storageClearFailureIsReportedWithoutEscaping() = runBlocking {
        withModel(FakeDownloads(DownloadStatus.FAILED),
            FakeStore(read = { record }, clearAction = { throw IOException() })) { model, _ ->
            assertEquals(InstallState.Failed(InstallFailure.STORAGE),
                withTimeout(2000) { model.installState.first { it is InstallState.Failed } })
        }
    }

    @Test fun returningToForegroundDoesNotStartDuplicateVerification() = runBlocking {
        var verifications = 0
        val started = CompletableDeferred<Unit>()
        withModel(FakeDownloads(DownloadStatus.SUCCESSFUL), FakeStore(read = { record }),
            ApkVerification { _, _ ->
                verifications++
                started.complete(Unit)
                awaitCancellation()
            }) { model, _ ->
            withTimeout(2000) { started.await() }
            model.onResume()
            yield()
            assertEquals(1, verifications)
            model.cancelDownload()
            yield()
            assertEquals(InstallState.Cancelled, model.installState.value)
        }
    }

    @Test fun changedNameWithSameCodeAndLowerCodeBothAllowDownload() = runBlocking {
        val installed = local.copy(versionCode = 3)
        for (target in listOf(remote.copy(versionCode = 3), remote.copy(versionCode = 2, versionName = installed.versionName))) {
            val downloads = FakeDownloads()
            withModel(downloads, currentVersion = { installed }, manifest = target) { model, _ ->
                model.check()
                withTimeout(2000) { model.state.first { it is UpdateUiState.UpdateAvailable } }
                model.download()
                yield()
                assertEquals(1, downloads.enqueued)
                assertTrue(model.installState.value is InstallState.Downloading)
            }
        }
    }

    @Test fun restoringLowerCodeDoesNotMistakePendingDownloadForInstalled() = runBlocking {
        val downloads = FakeDownloads(DownloadStatus.SUCCESSFUL)
        withModel(downloads, FakeStore(read = { record }), currentVersion = { local.copy(versionCode = 3) }) { model, _ ->
            assertEquals(InstallState.Downloaded,
                withTimeout(2000) { model.installState.first { it is InstallState.Downloaded } })
            assertNull(model.upgradedTo.value)
            assertEquals(1, downloads.queries)
        }
    }

    @Test fun restoringSameCodeWithDifferentNameStillVerifiesDownload() = runBlocking {
        val target = record.copy(versionCode = local.versionCode)
        val downloads = FakeDownloads(DownloadStatus.SUCCESSFUL)
        withModel(downloads, FakeStore(read = { target })) { model, _ ->
            withTimeout(2000) { model.installState.first { it is InstallState.Downloaded } }
            assertNull(model.upgradedTo.value)
            assertEquals(1, downloads.queries)
        }
    }

    @Test fun restoredInstallCompletesOnlyWhenBothVersionFieldsMatch() = runBlocking {
        val downloads = FakeDownloads()
        var cleared = false
        withModel(downloads, FakeStore(read = { record }, clearAction = { cleared = true }),
            currentVersion = { local.copy(versionCode = record.versionCode, versionName = record.versionName) }) { model, _ ->
            assertEquals(record.versionName, withTimeout(2000) { model.upgradedTo.first { it != null } })
            assertTrue(cleared)
            assertEquals(0, downloads.queries)
        }
    }

    @Test fun cancellingSameCodeOrLowerCodeInstallDoesNotReportSuccess() = runBlocking {
        val installed = local.copy(versionCode = 3)
        val apk = File.createTempFile("tune-policy-", ".apk")
        val installer = object : ApkInstallation {
            override fun canRequestInstall() = true
            override suspend fun install(apk: File, onOutcome: (ApkInstaller.Outcome) -> Unit) {
                onOutcome(ApkInstaller.Outcome.UserActionRequired)
            }
            override fun close() = Unit
        }
        try {
            for (targetCode in listOf(2L, 3L)) {
                val target = record.copy(versionCode = targetCode,
                    expected = record.expected.copy(versionCode = targetCode))
                withModel(FakeDownloads(DownloadStatus.SUCCESSFUL, apk), FakeStore(read = { target }),
                    currentVersion = { installed }, installation = installer) { model, _ ->
                    withTimeout(2000) { model.installState.first { it is InstallState.Downloaded } }
                    yield()
                    model.installApk()
                    yield()
                    assertEquals(InstallState.Installing, model.installState.value)
                    model.onResume()
                    yield()
                    assertEquals(InstallState.Failed(InstallFailure.INSTALL_CANCELLED), model.installState.value)
                    model.installApk()
                    yield()
                    assertEquals(InstallState.Installing, model.installState.value)
                    assertNull(model.upgradedTo.value)
                }
            }
        } finally {
            apk.delete()
        }
    }

    private suspend fun withModel(
        downloads: FakeDownloads = FakeDownloads(),
        store: InstallPersistence = FakeStore(),
        verifier: ApkVerification = ApkVerification { _, _ -> },
        currentVersion: () -> LocalVersion = { local },
        manifest: UpdateInfo = remote,
        installation: ApkInstallation? = null,
        block: suspend (UpdateViewModel, CoroutineScope) -> Unit
    ) = coroutineScope {
        val lifetime = CoroutineScope(coroutineContext + SupervisorJob())
        val installer = installation ?: object : ApkInstallation {
            override fun canRequestInstall() = true
            override suspend fun install(apk: File, onOutcome: (ApkInstaller.Outcome) -> Unit) = Unit
            override fun close() = Unit
        }
        try {
            val model = UpdateViewModel(object : UpdateRepository {
                override suspend fun fetch() = manifest
            }, UpdateChecker(), currentVersion, downloads, verifier, installer, store, lifetime)
            yield()
            block(model, lifetime)
        } finally {
            lifetime.cancel()
        }
    }

    private class FakeDownloads(
        private val status: DownloadStatus = DownloadStatus.RUNNING,
        private val apk: File? = null
    ) : ApkDownloads {
        var enqueued = 0
        var queries = 0
        val cancelled = mutableListOf<Long>()
        override fun enqueue(url: String, fileName: String, title: String) = (++enqueued).toLong()
        override fun query(id: Long): DownloadSnapshot {
            queries++
            return DownloadSnapshot(status, 0, 10, null, 0)
        }
        override fun cancel(id: Long) { cancelled += id }
        override fun fileFor(fileName: String) = apk ?: File(System.getProperty("java.io.tmpdir"), "tune-fake-missing.apk")
    }

    private class FakeStore(
        val read: suspend () -> PendingInstall? = { null },
        val saveAction: suspend () -> Unit = {},
        val clearAction: suspend () -> Unit = {}
    ) : InstallPersistence {
        override suspend fun pending() = read()
        override suspend fun save(pending: PendingInstall) = saveAction()
        override suspend fun clear() = clearAction()
    }
}
