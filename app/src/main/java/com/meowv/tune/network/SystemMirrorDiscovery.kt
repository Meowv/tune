package com.meowv.tune.network

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

internal suspend fun discoverMirrors(): List<MirrorHost> = coroutineScope {
    val addresses = async { withTimeoutOrNull(1800) { reverseLookup() }.orEmpty() }
    val service = if (Build.VERSION.SDK_INT >= 29) {
        withTimeoutOrNull(1800) { lookupService() }.orEmpty()
    } else emptyList()
    (service + addresses.await()).distinct().shuffled()
}

private suspend fun reverseLookup(): List<MirrorHost> = suspendCancellableCoroutine { continuation ->
    // API 28 的系统 A/PTR 查询使用后台线程，调用方取消后不必等待阻塞解析完成。
    Dispatchers.IO.asExecutor().execute {
        val mirrors = try {
            InetAddress.getAllByName("all.api.radio-browser.info").take(8).mapNotNull { address ->
                if (!continuation.isActive) return@execute
                MirrorHost.parse(address.canonicalHostName)
            }
        } catch (_: IOException) {
            emptyList()
        }
        continuation.resume(mirrors)
    }
}

@RequiresApi(29)
private suspend fun lookupService(): List<MirrorHost> = suspendCancellableCoroutine { continuation ->
    val cancellation = CancellationSignal()
    continuation.invokeOnCancellation { cancellation.cancel() }
    DnsResolver.getInstance().rawQuery(
        null, radioBrowserSrvQuery(),
        DnsResolver.FLAG_NO_RETRY, Executor(Runnable::run), cancellation,
        object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                continuation.resume(if (rcode == 0) parseSrvMirrors(answer) else emptyList())
            }

            override fun onError(error: DnsResolver.DnsException) {
                continuation.resume(emptyList())
            }
        }
    )
}

internal fun radioBrowserSrvQuery(): ByteArray =
    Message.newQuery(Record.newRecord(Name.fromString("_api._tcp.radio-browser.info."), Type.SRV, DClass.IN))
        .toWire()

internal fun parseSrvMirrors(answer: ByteArray): List<MirrorHost> = try {
    Message(answer).getSection(Section.ANSWER).filterIsInstance<SRVRecord>()
        .filter { it.port == 443 }.mapNotNull { MirrorHost.parse(it.target.toString()) }
} catch (_: IOException) {
    emptyList()
}
