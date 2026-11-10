package com.meowv.tune.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.update.install.ApkDownloader
import com.meowv.tune.update.install.ApkInstaller
import com.meowv.tune.update.install.ApkVerifier
import com.meowv.tune.update.install.DownloadStatus
import com.meowv.tune.update.install.ExpectedApk
import com.meowv.tune.update.install.InstallException
import com.meowv.tune.update.install.InstallFailure
import com.meowv.tune.update.install.InstallState
import com.meowv.tune.update.install.InstallStore
import com.meowv.tune.update.install.PendingInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UpdateViewModel internal constructor(
    private val repository: UpdateRepository,
    private val checker: UpdateChecker,
    private val localVersion: () -> LocalVersion,
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installer: ApkInstaller,
    private val store: InstallStore,
    private val installedVersionCode: () -> Long
) : ViewModel() {

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
    private var pollJob: Job? = null
    private var pending: PendingInstall? = null

    init {
        viewModelScope.launch { restore() }
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
                    is UpdateVerdict.Downgrade -> UpdateUiState.Downgrade(verdict.info)
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
        if (install.value is InstallState.Downloading ||
            install.value is InstallState.Verifying ||
            install.value is InstallState.Installing
        ) {
            return
        }
        upgraded.value = null
        viewModelScope.launch {
            install.value = InstallState.Downloading(0L, info.apkSizeBytes)
            val fileName = "MeowvTune-${info.versionName}.apk"
            val downloadId = try {
                downloader.enqueue(info.apkUrl, fileName, "MeowvTune ${info.versionName}")
            } catch (_: Exception) {
                install.value = InstallState.Failed(InstallFailure.DOWNLOAD_FAILED)
                return@launch
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
            poll(record)
        }
    }

    fun cancelDownload() {
        val record = pending ?: return
        pollJob?.cancel()
        pollJob = null
        downloader.cancel(record.downloadId)
        downloader.fileFor(record.fileName).delete()
        pending = null
        viewModelScope.launch { store.clear() }
        install.value = InstallState.Cancelled
    }

    // ---- 安装 ----

    fun installApk() {
        val record = pending ?: return
        if (install.value is InstallState.Installing) return
        if (!installer.canRequestInstall()) {
            install.value = InstallState.InstallPermissionRequired
            return
        }
        val file = downloader.fileFor(record.fileName)
        if (!file.isFile) {
            install.value = InstallState.Failed(InstallFailure.FILE_MISSING)
            return
        }
        install.value = InstallState.Installing
        installer.install(file) { outcome ->
            viewModelScope.launch {
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

    /** 回到前台时调用：确认安装结果、补查下载进度、复查安装权限。 */
    fun onResume() {
        refreshLocal()
        viewModelScope.launch {
            val record = pending
            when {
                install.value is InstallState.Installing && record != null ->
                    if (installedVersionCode() >= record.versionCode) {
                        finishInstalled(record)
                    } else {
                        // 用户取消了系统确认框：保留已校验的包，允许再装一次。
                        install.value = InstallState.Downloaded
                    }
                install.value is InstallState.InstallPermissionRequired ->
                    if (installer.canRequestInstall()) installApk()
                install.value is InstallState.Downloading && record != null -> {
                    val snapshot = downloader.query(record.downloadId)
                    if (snapshot.status == DownloadStatus.SUCCESSFUL) verify(record)
                }
            }
        }
    }

    // ---- 内部 ----

    /** 安装结果只有 PackageManager 说了算，这里每次都重新读一遍。 */
    private fun refreshLocal() {
        runCatching { localVersion() }.getOrNull()?.let { localState.value = it }
    }

    private suspend fun restore() {
        val record = store.pending() ?: return
        if (installedVersionCode() >= record.versionCode) {
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

    private fun poll(record: PendingInstall) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
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
                        return@launch
                    }
                    DownloadStatus.FAILED -> {
                        store.clear()
                        pending = null
                        install.value = InstallState.Failed(InstallFailure.DOWNLOAD_FAILED)
                        return@launch
                    }
                    DownloadStatus.UNKNOWN -> {
                        store.clear()
                        pending = null
                        install.value = InstallState.Idle
                        return@launch
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
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
        pollJob?.cancel()
        store.clear()
        pending = null
        install.value = InstallState.Idle
        upgraded.value = record.versionName
        downloader.fileFor(record.fileName).delete()
        refreshLocal()
        check()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 800L
    }
}
