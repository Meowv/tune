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

    private suspend fun withModel(
        downloads: FakeDownloads = FakeDownloads(),
        store: InstallPersistence = FakeStore(),
        verifier: ApkVerification = ApkVerification { _, _ -> },
        block: suspend (UpdateViewModel, CoroutineScope) -> Unit
    ) = coroutineScope {
        val lifetime = CoroutineScope(coroutineContext + SupervisorJob())
        val installer = object : ApkInstallation {
            override fun canRequestInstall() = true
            override suspend fun install(apk: File, onOutcome: (ApkInstaller.Outcome) -> Unit) = Unit
            override fun close() = Unit
        }
        try {
            val model = UpdateViewModel(object : UpdateRepository {
                override suspend fun fetch() = remote
            }, UpdateChecker(), { local }, downloads, verifier, installer, store, { 1 }, lifetime)
            yield()
            block(model, lifetime)
        } finally {
            lifetime.cancel()
        }
    }

    private class FakeDownloads(private val status: DownloadStatus = DownloadStatus.RUNNING) : ApkDownloads {
        var enqueued = 0
        var queries = 0
        val cancelled = mutableListOf<Long>()
        override fun enqueue(url: String, fileName: String, title: String) = (++enqueued).toLong()
        override fun query(id: Long): DownloadSnapshot {
            queries++
            return DownloadSnapshot(status, 0, 10, null, 0)
        }
        override fun cancel(id: Long) { cancelled += id }
        override fun fileFor(fileName: String) = File(System.getProperty("java.io.tmpdir"), "tune-fake-missing.apk")
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
