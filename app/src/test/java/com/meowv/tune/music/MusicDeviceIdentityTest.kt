package com.meowv.tune.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDeviceIdentityTest {
    @Test fun installationIdAndDeviceCodeUseStableVectors() {
        val id = MusicDeviceIdentity.installationId("0123456789abcdef")
        assertEquals("a11c874b27122d3a43e3c8eef2fec1e3", id)
        assertEquals("4412B-802DF", MusicDeviceIdentity.deviceCode(id))
        assertEquals(id, MusicDeviceIdentity.installationId(" 0123456789ABCDEF "))
        assertTrue(MusicDeviceIdentity.deviceCode(id).matches(Regex("^[0-9A-F]{5}-[0-9A-F]{5}$")))
    }

    @Test fun differentAndroidIdsProduceDifferentDeviceCodes() {
        val first = MusicDeviceIdentity.installationId("0123456789abcdef")
        val second = MusicDeviceIdentity.installationId("ABCDEF0123456789")
        assertEquals("5eaca41ec18c0aec1cf4bec94a48ce75", second)
        assertEquals("B67AF-BEB0C", MusicDeviceIdentity.deviceCode(second))
        assertNotEquals(MusicDeviceIdentity.deviceCode(first), MusicDeviceIdentity.deviceCode(second))
    }
}
