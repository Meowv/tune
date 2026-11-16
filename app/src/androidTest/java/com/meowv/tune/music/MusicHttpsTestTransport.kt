package com.meowv.tune.music

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol

// 仅仪器测试可见：固定测试域名与本轮临时公钥，不修改系统信任或正式媒体策略。
internal object MusicHttpsTestTransport {
    const val API_HOST = "api.music.test"
    const val MEDIA_HOST = "media.music.test"
    const val DEVICE_PORT = 18443
    const val BASE_URL = "https://api.music.test/api/music/"
    const val TEST_TOKEN = "YOUR_SYNTHETIC_MUSIC_HTTPS_TOKEN_FOR_TESTS_ONLY"

    fun enabled() = InstrumentationRegistry.getArguments().getString("musicHttps") == "true"

    fun client(expectedPin: String = pin()): OkHttpClient {
        require(expectedPin.matches(Regex("[a-fA-F0-9]{64}")))
        val expected = expectedPin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not used by this test")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.singleOrNull() ?: throw CertificateException("Expected one ephemeral test certificate")
                leaf.checkValidity()
                leaf.verify(leaf.publicKey)
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("Untrusted test certificate")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            // 模拟器不开放低端口；仅把两个测试域名的443映射到专用调试端口。
            val request = if (original.url.host in setOf(API_HOST, MEDIA_HOST) && original.url.port == 443) {
                original.newBuilder().url(original.url.newBuilder().port(DEVICE_PORT).build()).build()
            } else original
            chain.proceed(request)
        }.proxy(Proxy.NO_PROXY).sslSocketFactory(ssl.socketFactory, trust)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname !in setOf(API_HOST, MEDIA_HOST)) throw UnknownHostException("Unknown test host")
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }).protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }

    private fun pin(): String {
        check(enabled()) { "HTTPS test transport requires explicit instrumentation opt-in" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val config = File(app.getExternalFilesDir(null), "music-https.json")
        return Json.parseToJsonElement(config.readText()).jsonObject.getValue("spkiSha256").jsonPrimitive.content
    }
}
