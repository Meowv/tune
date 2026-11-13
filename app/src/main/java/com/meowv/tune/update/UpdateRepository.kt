package com.meowv.tune.update

import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface UpdateRepository {
    suspend fun fetch(): UpdateInfo
}

internal class HttpUpdateRepository(
    private val client: UpdateClient,
    private val manifestUrl: String
) : UpdateRepository {

    override suspend fun fetch(): UpdateInfo {
        // 地址来自构建配置，必须显式是 HTTPS，避免配置写错时静默走明文。
        val url = manifestUrl.trim().toHttpUrlOrNull()?.takeIf { it.isHttps }
            ?: throw UpdateException(UpdateFailure.MALFORMED)
        val body = try {
            client.get(url)
        } catch (failure: TuneFailure) {
            throw UpdateException(failure.toUpdateFailure())
        }
        return UpdateManifest.parse(body)
    }
}

/** 只校验 JSON 结构与类型；包名、渠道、格式与兼容性交给 [UpdateChecker]。 */
internal object UpdateManifest {
    private const val SCHEMA_VERSION = 1L

    fun parse(body: String): UpdateInfo {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw UpdateException(UpdateFailure.MALFORMED)

        val schema = root.number("schemaVersion")
            ?: throw UpdateException(UpdateFailure.MALFORMED)
        if (schema != SCHEMA_VERSION) throw UpdateException(UpdateFailure.UNSUPPORTED_SCHEMA)

        val apk = root.obj("apk") ?: throw UpdateException(UpdateFailure.MALFORMED)
        val notes = root.array("releaseNotes")?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.orEmpty()

        return UpdateInfo(
            packageName = root.text("packageName") ?: throw UpdateException(UpdateFailure.MALFORMED),
            channel = root.text("channel") ?: throw UpdateException(UpdateFailure.MALFORMED),
            versionName = root.text("versionName") ?: throw UpdateException(UpdateFailure.MALFORMED),
            versionCode = root.number("versionCode") ?: throw UpdateException(UpdateFailure.MALFORMED),
            minSdkVersion = root.number("minSdkVersion")?.toInt() ?: throw UpdateException(UpdateFailure.MALFORMED),
            releaseDate = root.text("releaseDate") ?: throw UpdateException(UpdateFailure.MALFORMED),
            title = root.text("title") ?: throw UpdateException(UpdateFailure.MALFORMED),
            releaseNotes = notes,
            apkUrl = apk.text("url") ?: throw UpdateException(UpdateFailure.MALFORMED),
            apkSizeBytes = apk.number("sizeBytes") ?: throw UpdateException(UpdateFailure.MALFORMED),
            apkSha256 = apk.text("sha256") ?: throw UpdateException(UpdateFailure.MALFORMED)
        )
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun JsonObject.number(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

internal fun TuneFailure.toUpdateFailure(): UpdateFailure = when (kind) {
    TuneError.NETWORK_OFFLINE, TuneError.CONNECTION, TuneError.TLS -> UpdateFailure.OFFLINE
    TuneError.TIMEOUT -> UpdateFailure.TIMEOUT
    TuneError.INVALID_RESPONSE -> UpdateFailure.MALFORMED
    else -> UpdateFailure.SERVER
}
