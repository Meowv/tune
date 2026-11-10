package com.meowv.tune.update.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 安装包校验：存在性 → 大小 → SHA-256 → 包名 → versionCode → minSdk → 签名。
 * 任意一步失败都抛 [InstallException]，调用方负责删文件和提示，禁止继续安装。
 */
internal class ApkVerifier(private val context: Context) {

    suspend fun verify(file: File, expected: ExpectedApk) {
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() <= 0L) throw InstallException(InstallFailure.FILE_MISSING)
            if (file.length() != expected.sizeBytes) throw InstallException(InstallFailure.SIZE_MISMATCH)

            val digest = sha256(file)
            coroutineContext.ensureActive()
            if (!digest.equals(expected.sha256, ignoreCase = true)) {
                throw InstallException(InstallFailure.CHECKSUM_MISMATCH)
            }

            val archive = context.packageManager
                .getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw InstallException(InstallFailure.INCOMPATIBLE)
            if (archive.packageName != expected.packageName) {
                throw InstallException(InstallFailure.PACKAGE_MISMATCH)
            }
            if (archive.longVersionCode != expected.versionCode) {
                throw InstallException(InstallFailure.VERSION_MISMATCH)
            }
            val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
            if (minSdk > Build.VERSION.SDK_INT) throw InstallException(InstallFailure.INCOMPATIBLE)

            // 签名必须与已安装版本一致，否则系统会拒绝覆盖，提前拦下来给出明确原因。
            val archiveSigner = archive.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            val installedSigner = installedSigner()
            if (archiveSigner == null || installedSigner == null ||
                !MessageDigest.isEqual(archiveSigner, installedSigner)
            ) {
                throw InstallException(InstallFailure.SIGNATURE_MISMATCH)
            }
        }
    }

    private fun installedSigner(): ByteArray? = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
