package com.meowv.tune.update

/**
 * 远程版本清单。[UpdateRepository] 只保证 JSON 结构与类型正确，
 * 包名/渠道/格式/兼容性等语义校验统一放在 [UpdateChecker]。
 */
data class UpdateInfo(
    val packageName: String,
    val channel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdkVersion: Int,
    val releaseDate: String,
    val title: String,
    val releaseNotes: List<String>,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String
)

/** 本地安装信息，全部取自 PackageManager / BuildConfig，不参与字符串比较。 */
data class LocalVersion(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sdkInt: Int,
    val channel: String
)

/** 检查失败原因，只用于给用户一句人话，不暴露异常细节。 */
enum class UpdateFailure {
    OFFLINE, TIMEOUT, SERVER, MALFORMED, UNSUPPORTED_SCHEMA,
    PACKAGE_MISMATCH, CHANNEL_MISMATCH, INCOMPATIBLE
}

class UpdateException(val failure: UpdateFailure) : Exception(failure.name)

/** 版本比较结论；降级单独成态，避免被当成「有更新」或「已最新」。 */
sealed interface UpdateVerdict {
    data class Available(val info: UpdateInfo) : UpdateVerdict
    data class UpToDate(val info: UpdateInfo) : UpdateVerdict
    data class Downgrade(val info: UpdateInfo) : UpdateVerdict
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val info: UpdateInfo) : UpdateUiState
    data class UpdateAvailable(val info: UpdateInfo) : UpdateUiState
    data class Downgrade(val info: UpdateInfo) : UpdateUiState
    data class Error(val failure: UpdateFailure) : UpdateUiState

    val isChecking: Boolean get() = this is Checking

    /** 成功拿到的清单；Idle/Checking/Error 时为 null。 */
    val latest: UpdateInfo?
        get() = when (this) {
            is UpToDate -> info
            is UpdateAvailable -> info
            is Downgrade -> info
            else -> null
        }
}
