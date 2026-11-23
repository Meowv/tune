package com.meowv.tune.music

import java.security.MessageDigest
import java.util.Locale

/**
 * 稳定设备身份：同一设备、用户、签名下，清数据/卸载重装后保持不变。
 * 前缀与服务端 DeviceCode 派生共用，改动需两端同步。
 */
internal object MusicDeviceIdentity {
    private const val InstallationPrefix = "meowv-tune/device-id/v1|"
    private const val DeviceCodePrefix = "meowv-tune/device-code/v1|"

    fun installationId(androidId: String): String =
        sha256(InstallationPrefix + androidId.trim().lowercase(Locale.ROOT)).take(16)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    fun deviceCode(installationId: String): String {
        val hex = sha256(DeviceCodePrefix + installationId.lowercase(Locale.ROOT)).take(5)
            .joinToString("") { String.format(Locale.ROOT, "%02X", it) }
        return hex.take(5) + "-" + hex.drop(5)
    }

    fun keyFingerprint(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).take(8)
            .joinToString("") { String.format(Locale.ROOT, "%02X", it) }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
