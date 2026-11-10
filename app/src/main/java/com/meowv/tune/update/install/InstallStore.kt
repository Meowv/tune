package com.meowv.tune.update.install

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 把 downloadId 与目标版本（含校验期望值）落到 DataStore：
 * 页面重进、进程被杀后都还能接着查任务状态并按原清单校验。
 */
internal class InstallStore(private val dataStore: DataStore<Preferences>) {

    suspend fun pending(): PendingInstall? {
        val prefs = dataStore.data.first()
        val id = prefs[DownloadId] ?: return null
        val code = prefs[TargetVersionCode] ?: return null
        val name = prefs[TargetVersionName] ?: return null
        val file = prefs[TargetFileName] ?: return null
        val packageName = prefs[PackageName] ?: return null
        val size = prefs[SizeBytes] ?: return null
        val sha = prefs[Sha256] ?: return null
        val minSdk = prefs[MinSdk] ?: return null
        return PendingInstall(
            downloadId = id,
            versionCode = code,
            versionName = name,
            fileName = file,
            expected = ExpectedApk(packageName, code, size, sha, minSdk)
        )
    }

    suspend fun save(pending: PendingInstall) {
        dataStore.edit {
            it[DownloadId] = pending.downloadId
            it[TargetVersionCode] = pending.versionCode
            it[TargetVersionName] = pending.versionName
            it[TargetFileName] = pending.fileName
            it[PackageName] = pending.expected.packageName
            it[SizeBytes] = pending.expected.sizeBytes
            it[Sha256] = pending.expected.sha256
            it[MinSdk] = pending.expected.minSdkVersion
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(DownloadId)
            it.remove(TargetVersionCode)
            it.remove(TargetVersionName)
            it.remove(TargetFileName)
            it.remove(PackageName)
            it.remove(SizeBytes)
            it.remove(Sha256)
            it.remove(MinSdk)
        }
    }

    private companion object {
        val DownloadId = longPreferencesKey("download_id")
        val TargetVersionCode = longPreferencesKey("target_version_code")
        val TargetVersionName = stringPreferencesKey("target_version_name")
        val TargetFileName = stringPreferencesKey("target_file_name")
        val PackageName = stringPreferencesKey("package_name")
        val SizeBytes = longPreferencesKey("size_bytes")
        val Sha256 = stringPreferencesKey("sha256")
        val MinSdk = intPreferencesKey("min_sdk")
    }
}
