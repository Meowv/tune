package com.meowv.tune.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.update.install.ApkDownloads
import com.meowv.tune.update.install.ApkInstallation
import com.meowv.tune.update.install.ApkInstaller
import com.meowv.tune.update.install.ApkVerification
import com.meowv.tune.update.install.DownloadStatus
import com.meowv.tune.update.install.ExpectedApk
import com.meowv.tune.update.install.InstallException
import com.meowv.tune.update.install.InstallFailure
import com.meowv.tune.update.install.InstallPersistence
import com.meowv.tune.update.install.InstallState
import com.meowv.tune.update.install.PendingInstall
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel internal constructor(
    private val repository: UpdateRepository,
    private val checker: UpdateChecker,
    private val localVersion: () -> LocalVersion,
    private val downloader: ApkDownloads,
    private val verifier: ApkVerification,
    private val installer: ApkInstallation,
    private val store: InstallPersistence,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ViewModel(scope) {

    private val current = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = current.asStateFlow()

    // 版本号只能现读：安装器完成后进程可能没被杀，缓存值会让页面继续显示旧版本。
    private val localState = MutableStateFlow(localVersion())
    val local: StateFlow<LocalVersion> = localState.asStateFlow()

    private val install = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = install.asStateFlow()

    /** 安装成功后由页面消费一次的提示，避免只靠「已是最新」表达升级结果。 */
    private val upgraded = MutableStateFlow<String?>(null)
    val upgradedTo: StateFlow<String?> = upgraded.asStateFlow()

    private var inFlight: Job? = null
    private var installJob: Job? = null
    private var pending: PendingInstall? = null

    init {
        installJob = launchInstall { restore() }
    }

    // ---- 版本检查 ----

    fun check() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            current.value = UpdateUiState.Checking
            current.value = try {
                val info = repository.fetch()
                when (val verdict = checker.evaluate(localState.value, info)) {
                    is UpdateVerdict.Available -> UpdateUiState.UpdateAvailable(verdict.info)
                    is UpdateVerdict.UpToDate -> UpdateUiState.UpToDate(verdict.info)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateException) {
                UpdateUiState.Error(failure.failure)
            } catch (_: Exception) {
                UpdateUiState.Error(UpdateFailure.SERVER)
            }
        }
    }

    // ---- 下载 ----

    fun download() {
        val info = (current.value as? UpdateUiState.UpdateAvailable)?.info ?: return
        if (installJob?.isActive == true || install.value is InstallState.Downloading ||
            install.value is InstallState.Verifying ||
            install.value is InstallState.Installing
        ) {
            return
        }
        upgraded.value = null
        install.value = InstallState.Downloading(0L, info.apkSizeBytes)
        installJob = launchInstall {
            pending?.let { downloader.cancel(it.downloadId) }
            val fileName = "MeowvTune-${info.versionName}.apk"
            val downloadId = try {
                downloader.enqueue(info.apkUrl, fileName, "MeowvTune ${info.versionName}")
            } catch (_: Exception) {
                install.value = InstallState.Failed(InstallFailure.DOWNLOAD_FAILED)
                return@launchInstall
            }
            val record = PendingInstall(
                downloadId = downloadId,
                versionCode = info.versionCode,
                versionName = info.versionName,
                fileName = fileName,
                expected = ExpectedApk(
                    packageName = info.packageName,
                    versionCode = info.versionCode,
                    sizeBytes = info.apkSizeBytes,
                    sha256 = info.apkSha256,
                    minSdkVersion = info.minSdkVersion
                )
            )
            pending = record
            store.save(record)
            coroutineContext.ensureActive()
            poll(record)
        }
    }

    fun cancelDownload() {
        val record = pending ?: return
        val previous = installJob
        previous?.cancel()
        pending = null
        downloader.cancel(record.downloadId)
        install.value = InstallState.Cancelled
        // 等保存/校验完全退出，再清理记录；新下载会等待这一步结束。
        installJob = launchInstall {
            previous?.join()
            downloader.fileFor(record.fileName).delete()
            store.clear()
        }
    }

    // ---- 安装 ----

    fun installApk() {
        val record = pending ?: return
        if (installJob?.isActive == true ||
            install.value !in setOf(InstallState.Downloaded, InstallState.InstallPermissionRequired)
        ) return
        if (!installer.canRequestInstall()) {
            install.value = InstallState.InstallPermissionRequired
            return
        }
        install.value = InstallState.Installing
        installJob = launchInstall {
            val file = downloader.fileFor(record.fileName)
            if (!file.isFile) {
                install.value = InstallState.Failed(InstallFailure.FILE_MISSING)
                return@launchInstall
            }
            installer.install(file) { outcome ->
                launchInstall outcome@{
                    if (pending !== record) return@outcome
                    when (outcome) {
                        ApkInstaller.Outcome.UserActionRequired -> Unit
                        ApkInstaller.Outcome.Success -> finishInstalled(record)
                        ApkInstaller.Outcome.Cancelled ->
                            install.value = InstallState.Failed(InstallFailure.INSTALL_CANCELLED)
                        is ApkInstaller.Outcome.Failed ->
                            install.value = InstallState.Failed(InstallFailure.INSTALL_FAILED)
                    }
                }
            }
        }
    }

    /** 回到前台时调用：确认安装结果、补查下载进度、复查安装权限。 */
    fun onResume() {
        refreshLocal()
        if (installJob?.isActive == true) return
        launchInstall {
            val record = pending
            when {
                install.value is InstallState.Installing && record != null ->
                    if (targetInstalled(record)) {
                        finishInstalled(record)
                    } else {
                        // 用户取消了系统确认框：保留已校验的包，允许再装一次。
                        install.value = InstallState.Downloaded
                    }
                install.value is InstallState.InstallPermissionRequired ->
                    if (installer.canRequestInstall()) installApk()
            }
        }
    }

    // ---- 内部 ----

    /** 安装结果只有 PackageManager 说了算，这里每次都重新读一遍。 */
    private fun refreshLocal() {
        runCatching { localVersion() }.getOrNull()?.let { localState.value = it }
    }

    // 同码换名称、低码版本都可能成为目标，不能用 >= 判断安装成功。
    private fun targetInstalled(record: PendingInstall): Boolean {
        val installed = localVersion()
        return installed.versionCode == record.versionCode && installed.versionName == record.versionName
    }

    private suspend fun restore() {
        val record = store.pending() ?: return
        if (targetInstalled(record)) {
            // 自更新会杀掉进程，下次启动靠这里把「刚装完」认回来。
            store.clear()
            upgraded.value = record.versionName
            return
        }
        pending = record
        val snapshot = downloader.query(record.downloadId)
        when (snapshot.status) {
            DownloadStatus.PENDING, DownloadStatus.RUNNING, DownloadStatus.PAUSED -> {
                install.value = InstallState.Downloading(
                    snapshot.downloadedBytes,
                    if (snapshot.totalBytes > 0) snapshot.totalBytes else record.expected.sizeBytes
                )
                poll(record)
            }
            DownloadStatus.SUCCESSFUL -> verify(record)
            DownloadStatus.FAILED -> {
                store.clear()
                pending = null
                install.value = InstallState.Failed(InstallFailure.DOWNLOAD_FAILED)
            }
            DownloadStatus.UNKNOWN -> {
                store.clear()
                pending = null
            }
        }
    }

    private suspend fun poll(record: PendingInstall) {
        while (pending === record) {
            coroutineContext.ensureActive()
            val snapshot = downloader.query(record.downloadId)
            when (snapshot.status) {
                DownloadStatus.PENDING, DownloadStatus.RUNNING, DownloadStatus.PAUSED -> {
                    val total = if (snapshot.totalBytes > 0) {
                        snapshot.totalBytes
                    } else {
                        record.expected.sizeBytes
                    }
                    install.value = InstallState.Downloading(snapshot.downloadedBytes, total)
                }
                DownloadStatus.SUCCESSFUL -> {
                    verify(record)
                    return
                }
                DownloadStatus.FAILED -> {
                    store.clear()
                    pending = null
                    install.value = InstallState.Failed(InstallFailure.DOWNLOAD_FAILED)
                    return
                }
                DownloadStatus.UNKNOWN -> {
                    store.clear()
                    pending = null
                    install.value = InstallState.Idle
                    return
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun verify(record: PendingInstall) {
        install.value = InstallState.Verifying
        val file = downloader.fileFor(record.fileName)
        try {
            verifier.verify(file, record.expected)
            install.value = InstallState.Downloaded
        } catch (failure: InstallException) {
            // 校验不过的包必须删掉，避免下次被当成可安装文件。
            file.delete()
            store.clear()
            pending = null
            install.value = InstallState.Failed(failure.failure)
        }
    }

    private suspend fun finishInstalled(record: PendingInstall) {
        store.clear()
        pending = null
        install.value = InstallState.Idle
        upgraded.value = record.versionName
        downloader.fileFor(record.fileName).delete()
        refreshLocal()
        check()
    }

    private fun launchInstall(block: suspend () -> Unit): Job = viewModelScope.launch {
        guardInstall(block)
    }

    private suspend fun guardInstall(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            coroutineContext.ensureActive()
            pending?.let { downloader.cancel(it.downloadId) }
            install.value = InstallState.Failed(InstallFailure.STORAGE)
        } catch (_: SecurityException) {
            coroutineContext.ensureActive()
            install.value = InstallState.Failed(InstallFailure.INSTALL_FAILED)
        }
    }

    override fun onCleared() {
        installer.close()
        super.onCleared()
    }

    private companion object {
        // 进度条不需要 800ms 粒度；放慢一半减少 DownloadManager 的跨进程查询与重组。
        const val POLL_INTERVAL_MS = 1500L
    }
}
