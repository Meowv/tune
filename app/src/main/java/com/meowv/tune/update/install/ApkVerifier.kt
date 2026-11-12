package com.meowv.tune.update.install

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 安装包校验：存在性 → 大小 → SHA-256 → 包名 → 清单版本一致性 → minSdk。
 * 签名兼容性由系统安装器验证，客户端不做额外的签名相等限制。
 * 任意一步失败都抛 [InstallException]，调用方负责删文件和提示，禁止继续安装。
 */
internal class ApkVerifier(private val context: Context) : ApkVerification {

    override suspend fun verify(file: File, expected: ExpectedApk) {
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() <= 0L) throw InstallException(InstallFailure.FILE_MISSING)
            if (file.length() != expected.sizeBytes) throw InstallException(InstallFailure.SIZE_MISMATCH)

            val digest = sha256(file)
            coroutineContext.ensureActive()
            if (!digest.equals(expected.sha256, ignoreCase = true)) {
                throw InstallException(InstallFailure.CHECKSUM_MISMATCH)
            }

            val archive = context.packageManager
                .getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw InstallException(InstallFailure.INCOMPATIBLE)
            if (archive.packageName != expected.packageName) {
                throw InstallException(InstallFailure.PACKAGE_MISMATCH)
            }
            if (archive.longVersionCode != expected.versionCode) {
                throw InstallException(InstallFailure.VERSION_MISMATCH)
            }
            val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
            if (minSdk > Build.VERSION.SDK_INT) throw InstallException(InstallFailure.INCOMPATIBLE)

        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
