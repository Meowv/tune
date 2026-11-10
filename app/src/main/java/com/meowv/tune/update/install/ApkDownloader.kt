package com.meowv.tune.update.install

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * DownloadManager 封装：只负责入队、查询、取消与目标文件定位。
 * 下载本身由系统服务完成，进程被杀也会继续。
 */
internal class ApkDownloader(private val context: Context) {

    private val manager: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)

    fun fileFor(fileName: String): File {
        // 外置存储不可用时退回内部目录，避免拼出相对路径。
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(dir, fileName)
    }

    /** 入队前先清掉同名旧文件，避免残留包被当成新下载结果。 */
    fun enqueue(url: String, fileName: String, title: String): Long {
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps) { "APK 地址必须是 HTTPS" }
        fileFor(fileName).delete()
        val request = DownloadManager.Request(Uri.parse(parsed.toString()))
            .setTitle(title)
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        return manager.enqueue(request)
    }

    fun query(id: Long): DownloadSnapshot {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return unknown()
        cursor.use {
            if (!it.moveToFirst()) return unknown()
            fun column(name: String, fallback: Long = -1L): Long {
                val index = it.getColumnIndex(name)
                return if (index >= 0) it.getLong(index) else fallback
            }
            val status = when (column(DownloadManager.COLUMN_STATUS, -1L).toInt()) {
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.SUCCESSFUL
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                else -> DownloadStatus.UNKNOWN
            }
            val localIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            return DownloadSnapshot(
                status = status,
                downloadedBytes = column(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, 0L),
                totalBytes = column(DownloadManager.COLUMN_TOTAL_SIZE_BYTES, -1L),
                localUri = if (localIndex >= 0) it.getString(localIndex) else null,
                reason = column(DownloadManager.COLUMN_REASON, 0L).toInt()
            )
        }
    }

    /** 移除任务并删除已下载文件；下载中的任务由系统一并清理。 */
    fun cancel(id: Long) {
        runCatching { manager.remove(id) }
    }

    private fun unknown() = DownloadSnapshot(DownloadStatus.UNKNOWN, 0L, -1L, null, 0)

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
