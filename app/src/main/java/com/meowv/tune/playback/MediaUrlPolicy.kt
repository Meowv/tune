package com.meowv.tune.playback

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal object MediaUrlPolicy {
    fun parse(value: String?, base: HttpUrl? = null): HttpUrl? {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8192 && it.none(Char::isISOControl) }
            ?: return null
        val url = (if (base == null) clean.toHttpUrlOrNull() else base.resolve(clean)) ?: return null
        return url.takeIf(::allows)?.newBuilder()?.fragment(null)?.build()
    }

    private fun allows(url: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host.trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (':' in host || host.all { it.isDigit() || it == '.' }) {
            return try {
                publicAddress(InetAddress.getByName(host))
            } catch (_: UnknownHostException) {
                false
            }
        }
        return true
    }

    fun checkedDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try {
                delegate.lookup(hostname)
            } catch (_: UnknownHostException) {
                throw UnknownHostException("Media DNS lookup failed")
            }
            // 只把已校验的 DNS 结果交给实际连接，避免校验与连接分别解析。
            if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("Blocked media address")
            return addresses
        }
    }

    fun client(httpsOnly: Boolean = false, playback: Boolean = false): OkHttpClient {
        fun check(url: HttpUrl) {
            if (!allows(url) || httpsOnly && !url.isHttps) throw IOException("Blocked media URL")
        }
        return OkHttpClient.Builder()
            .dns(checkedDns()).proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(if (playback) 15 else 5, TimeUnit.SECONDS)
            .followSslRedirects(!httpsOnly)
            .addInterceptor { chain ->
                check(chain.request().url)
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor { chain ->
                check(chain.request().url)
                chain.proceed(chain.request())
            }
            .build()
    }

    private fun publicAddress(address: InetAddress): Boolean {
        val octets = address.address.map { it.toInt() and 0xff }
        val (a, b, c) = octets
        if (octets.size == 16) {
            if (a !in 0x20..0x3f) return false
            if (a == 0x20 && b == 0x02) return false // 6to4
            if (a == 0x20 && b == 0x01 && (c < 2 || c == 0x0d && octets[3] == 0xb8)) return false
            return a != 0x3f || b != 0xff || c >= 0x10 // 3fff::/20 文档地址
        }
        return when (a) {
            0, 10, 127, in 224..255 -> false
            100 -> b !in 64..127
            169 -> b != 254
            172 -> b !in 16..31
            192 -> b != 168 && !(b == 0 && c in setOf(0, 2)) && !(b == 88 && c == 99)
            198 -> b !in 18..19 && !(b == 51 && c == 100)
            203 -> !(b == 0 && c == 113)
            else -> true
        }
    }
}
