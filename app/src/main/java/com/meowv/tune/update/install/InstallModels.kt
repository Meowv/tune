package com.meowv.tune.update.install

/** 下载 / 校验 / 安装链路的统一状态；页面只按这个状态渲染。 */
sealed interface InstallState {
    data object Idle : InstallState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : InstallState {
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

        val indeterminate: Boolean get() = totalBytes <= 0
    }

    data object Verifying : InstallState
    data object Downloaded : InstallState
    data object InstallPermissionRequired : InstallState
    data object Installing : InstallState
    data class Failed(val failure: InstallFailure) : InstallState
    data object Cancelled : InstallState
}

enum class InstallFailure {
    DOWNLOAD_FAILED,
    FILE_MISSING,
    STORAGE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    SIGNATURE_MISMATCH,
    INCOMPATIBLE,
    INSTALL_FAILED,
    INSTALL_CANCELLED
}

class InstallException(val failure: InstallFailure) : Exception(failure.name)

/** DownloadManager 状态归一化，避免把系统常量散落到业务层。 */
internal enum class DownloadStatus { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED, UNKNOWN }

internal data class DownloadSnapshot(
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localUri: String?,
    val reason: Int
)

/** 待安装目标：downloadId 与版本信息一起落盘，进程被杀后仍能接着走。 */
internal data class PendingInstall(
    val downloadId: Long,
    val versionCode: Long,
    val versionName: String,
    val fileName: String,
    val expected: ExpectedApk
)

/** 校验期望值，全部来自已通过 UpdateChecker 的远程清单。 */
internal data class ExpectedApk(
    val packageName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val sha256: String,
    val minSdkVersion: Int
)
