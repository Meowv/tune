package com.meowv.tune.update.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 系统安装器封装：优先走 PackageInstaller.Session，失败时退回 ACTION_VIEW 交给系统安装界面。
 * 安装动作始终由系统完成，应用只负责写入已校验的 APK 并接收结果。
 */
internal class ApkInstaller(private val context: Context) {

    internal sealed interface Outcome {
        data object UserActionRequired : Outcome
        data object Success : Outcome
        data object Cancelled : Outcome
        data class Failed(val status: Int) : Outcome
    }

    private var receiver: BroadcastReceiver? = null

    fun canRequestInstall(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    fun install(apk: File, onOutcome: (Outcome) -> Unit) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (_: Exception) {
            launchSystemInstaller(apk, onOutcome)
            return
        }
        registerResultReceiver(onOutcome)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(resultIntent(sessionId).intentSender)
            }
        } catch (_: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            unregister()
            launchSystemInstaller(apk, onOutcome)
        }
    }

    fun close() = unregister()

    private fun resultIntent(sessionId: Int): android.app.PendingIntent {
        val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return android.app.PendingIntent.getBroadcast(
            context, sessionId, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }

    private fun registerResultReceiver(onOutcome: (Outcome) -> Unit) {
        unregister()
        val registered = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != ACTION_INSTALL_RESULT) return
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // 系统需要用户在确认框上点“安装”，把 Intent 交回去。
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(confirm) }
                        }
                        onOutcome(Outcome.UserActionRequired)
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        unregister()
                        onOutcome(Outcome.Success)
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        unregister()
                        onOutcome(Outcome.Cancelled)
                    }
                    else -> {
                        unregister()
                        onOutcome(Outcome.Failed(status))
                    }
                }
            }
        }
        receiver = registered
        ContextCompat.registerReceiver(
            context, registered, IntentFilter(ACTION_INSTALL_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** OEM 不允许创建安装会话时的兜底：用系统安装界面打开已校验的 APK。 */
    private fun launchSystemInstaller(apk: File, onOutcome: (Outcome) -> Unit) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrNull()
        if (uri == null) {
            onOutcome(Outcome.Failed(PackageInstaller.STATUS_FAILURE))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { context.startActivity(intent) }.isSuccess
        onOutcome(if (started) Outcome.UserActionRequired else Outcome.Failed(PackageInstaller.STATUS_FAILURE))
    }

    private fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private companion object {
        const val ACTION_INSTALL_RESULT = "com.meowv.tune.action.INSTALL_RESULT"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
