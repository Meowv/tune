package com.meowv.tune.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    @Test
    fun parsesCompleteManifest() {
        val info = UpdateManifest.parse(validJson())
        assertEquals("com.meowv.tune", info.packageName)
        assertEquals("stable", info.channel)
        assertEquals("1.1.0", info.versionName)
        assertEquals(101L, info.versionCode)
        assertEquals(28, info.minSdkVersion)
        assertEquals("2026-11-15", info.releaseDate)
        assertEquals("MeowvTune 1.1.0", info.title)
        assertEquals(listOf("优化电台播放体验", "修复部分问题"), info.releaseNotes)
        assertEquals("https://studio.meowv.com/data/meowvtune/MeowvTune-1.1.0.apk", info.apkUrl)
        assertEquals(25_600_000L, info.apkSizeBytes)
        assertEquals("a".repeat(64), info.apkSha256)
    }

    @Test
    fun unsupportedSchemaFails() {
        val failure = runCatching { UpdateManifest.parse(validJson(schema = 2)) }.exceptionOrNull()
        assertEquals(UpdateFailure.UNSUPPORTED_SCHEMA, (failure as UpdateException).failure)
    }

    @Test
    fun missingFieldFails() {
        val json = validJson().replace("\"channel\": \"stable\",", "")
        val failure = runCatching { UpdateManifest.parse(json) }.exceptionOrNull()
        assertEquals(UpdateFailure.MALFORMED, (failure as UpdateException).failure)
    }

    @Test
    fun brokenJsonFails() {
        val failure = runCatching { UpdateManifest.parse("{ not json") }.exceptionOrNull()
        assertEquals(UpdateFailure.MALFORMED, (failure as UpdateException).failure)
    }

    @Test
    fun nonStringVersionCodeFails() {
        val json = validJson().replace("\"versionCode\": 101", "\"versionCode\": \"101\"")
        val failure = runCatching { UpdateManifest.parse(json) }.exceptionOrNull()
        assertEquals(UpdateFailure.MALFORMED, (failure as UpdateException).failure)
    }

    @Test
    fun releaseNotesPreserveOriginalTextAndAllEntries() {
        val notes = listOf("  🆕 **原文**\n- 条目  \n", "", "  ", "长日志".repeat(200)) +
            (1..32).map { "日志 $it" }
        val json = validJson().replace(
            "\"releaseNotes\": [\"优化电台播放体验\", \"修复部分问题\"]",
            "\"releaseNotes\": ${JsonArray(notes.map(::JsonPrimitive) + JsonPrimitive(42))}"
        )
        assertEquals(notes, UpdateManifest.parse(json).releaseNotes)
    }

    @Test
    fun missingReleaseNotesIsAllowed() {
        val json = validJson().replace(",\n  \"releaseNotes\": [\"优化电台播放体验\", \"修复部分问题\"]", "")
        assertTrue(UpdateManifest.parse(json).releaseNotes.isEmpty())
    }

    private fun validJson(schema: Int = 1) = """
        {
          "schemaVersion": $schema,
          "packageName": "com.meowv.tune",
          "channel": "stable",
          "versionName": "1.1.0",
          "versionCode": 101,
          "minSdkVersion": 28,
          "releaseDate": "2026-11-15",
          "title": "MeowvTune 1.1.0",
          "releaseNotes": ["优化电台播放体验", "修复部分问题"],
          "apk": {
            "url": "https://studio.meowv.com/data/meowvtune/MeowvTune-1.1.0.apk",
            "sizeBytes": 25600000,
            "sha256": "${"a".repeat(64)}"
          }
        }
    """.trimIndent()
}
