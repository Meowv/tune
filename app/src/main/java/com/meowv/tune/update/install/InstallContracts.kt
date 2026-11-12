package com.meowv.tune.update.install

import java.io.File

internal interface ApkDownloads {
    fun enqueue(url: String, fileName: String, title: String): Long
    fun query(id: Long): DownloadSnapshot
    fun cancel(id: Long)
    fun fileFor(fileName: String): File
}

internal fun interface ApkVerification {
    suspend fun verify(file: File, expected: ExpectedApk)
}

internal interface ApkInstallation {
    fun canRequestInstall(): Boolean
    suspend fun install(apk: File, onOutcome: (ApkInstaller.Outcome) -> Unit)
    fun close()
}

internal interface InstallPersistence {
    suspend fun pending(): PendingInstall?
    suspend fun save(pending: PendingInstall)
    suspend fun clear()
}
