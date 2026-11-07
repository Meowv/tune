package com.meowv.tune.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

class MirrorDirectoryTest {
    @Test fun ttlRefreshDeduplicatesAndRejectsInvalidHosts() = runBlocking {
        val a = host("a")
        val b = host("b")
        val c = host("c")
        val seed = host("seed")
        var now = 0L
        var discoveries = 0
        var serverRequests = 0
        val mirrors = MirrorDirectory(
            discover = {
                discoveries++
                if (discoveries == 1) listOf(a, a) else listOf(c)
            },
            serverList = {
                serverRequests++
                if (serverRequests == 1) """[{"name":"b.api.radio-browser.info."},{"name":"https://evil.test"},{"name":42},null]"""
                else """[{"name":"c.api.radio-browser.info"}]"""
            },
            store = MemoryMirrorStore(),
            nowMillis = { now },
            seeds = listOf(seed)
        )
        assertEquals(listOf(a, b, seed), mirrors.candidates())
        assertEquals(listOf(a, b, seed), mirrors.candidates())
        assertEquals(1, discoveries)
        assertEquals(1, serverRequests)
        now += 15 * 60 * 1000
        assertEquals(listOf(c, seed), mirrors.candidates())
        assertEquals(2, discoveries)
        assertEquals(2, serverRequests)
        assertNull(MirrorHost.parse("a.api.radio-browser.info.evil.test"))
        assertNull(MirrorHost.parse("a.api.radio-browser.info:80"))
        assertNull(MirrorHost.parse("91.98.4.78"))
    }

    @Test fun srvDiscoveryOnlyAcceptsHttpsRadioBrowserHosts() {
        val owner = Name.fromString("_api._tcp.radio-browser.info.")
        val question = Message(radioBrowserSrvQuery()).question
        assertEquals(owner, question.name)
        assertEquals(Type.SRV, question.type)
        val packet = Message().apply {
            addRecord(SRVRecord(owner, DClass.IN, 60, 1, 1, 443, Name.fromString("a.api.radio-browser.info.")), Section.ANSWER)
            addRecord(SRVRecord(owner, DClass.IN, 60, 1, 1, 80, Name.fromString("b.api.radio-browser.info.")), Section.ANSWER)
            addRecord(SRVRecord(owner, DClass.IN, 60, 1, 1, 443, Name.fromString("invalid.example.")), Section.ANSWER)
        }
        assertEquals(listOf(host("a")), parseSrvMirrors(packet.toWire()))
        assertEquals(emptyList<MirrorHost>(), parseSrvMirrors(byteArrayOf(0x01)))
    }

    private fun host(label: String) = requireNotNull(MirrorHost.parse("$label.api.radio-browser.info"))
}
