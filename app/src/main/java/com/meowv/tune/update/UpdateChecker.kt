package com.meowv.tune.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 版本比较与清单语义校验。
 *
 * 只按 versionCode 判断新旧，不做字符串比较；包名、渠道、清单格式与
 * minSdk 兼容性都在这里一次性把关，任何一条不满足都视为检查失败。
 */
class UpdateChecker {
    fun evaluate(local: LocalVersion, remote: UpdateInfo): UpdateVerdict {
        if (remote.packageName != local.packageName) throw UpdateException(UpdateFailure.PACKAGE_MISMATCH)
        if (remote.channel != local.channel) throw UpdateException(UpdateFailure.CHANNEL_MISMATCH)
        if (remote.versionName.isBlank() || remote.versionName.length > MAX_VERSION_NAME) {
            throw UpdateException(UpdateFailure.MALFORMED)
        }
        if (remote.versionCode !in 1..Int.MAX_VALUE.toLong()) throw UpdateException(UpdateFailure.MALFORMED)
        if (remote.apkUrl.toHttpUrlOrNull()?.isHttps != true) throw UpdateException(UpdateFailure.MALFORMED)
        if (!SHA256.matches(remote.apkSha256)) throw UpdateException(UpdateFailure.MALFORMED)
        if (remote.minSdkVersion <= 0) throw UpdateException(UpdateFailure.MALFORMED)
        if (remote.minSdkVersion > local.sdkInt) throw UpdateException(UpdateFailure.INCOMPATIBLE)

        return when {
            remote.versionCode > local.versionCode -> UpdateVerdict.Available(remote)
            remote.versionCode == local.versionCode -> UpdateVerdict.UpToDate(remote)
            else -> UpdateVerdict.Downgrade(remote)
        }
    }

    private companion object {
        const val MAX_VERSION_NAME = 64
        val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}
