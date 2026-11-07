package com.meowv.tune.radio

import com.meowv.tune.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CountryResolverTest {
    private val jp = requireNotNull(CountryCode.parse("JP"))
    private val us = requireNotNull(CountryCode.parse("US"))
    private val directory = CountryDirectory { listOf(RadioCountry(jp, 5), RadioCountry(us, 9)) }

    @Test fun autoUsesNetworkThenLocaleThenGlobal() = runBlocking {
        assertEquals(listOf(jp, us, null), CountryResolver({ "jp" }, { "US" })
            .candidates(RadioScope(), directory).map { it.country })
        assertEquals(listOf(us, null), CountryResolver({ "CN" }, { "US" })
            .candidates(RadioScope(), directory).map { it.country })
        assertEquals(listOf(jp, null), CountryResolver({ "JP" }, { "JP" })
            .candidates(RadioScope(), directory).map { it.country })
    }

    @Test fun missingTelephonyAndEmptyHintsAreSafe() = runBlocking {
        assertEquals(listOf(us, null), CountryResolver({ throw UnsupportedOperationException() }, { "US" })
            .candidates(RadioScope(), directory).map { it.country })
        assertNull(CountryResolver({ "" }, { null }).candidates(RadioScope()) {
            error("No hints to validate")
        }.single().country)
        assertNull(CountryResolver({ "JP" }, { "" }).candidates(RadioScope()) { emptyList() }.single().country)
    }

    @Test fun manualCountryIsNeverReplacedByAnAutomaticHint() = runBlocking {
        val resolver = CountryResolver({ error("Unexpected telephony access") }, { error("Unexpected locale access") })
        val scope = RadioScope(ScopeMode.COUNTRY, jp)
        assertEquals(ResolvedRadioScope(scope, jp), resolver.candidates(scope) { error("Unexpected lookup") }.single())
        assertNull(resolver.candidates(RadioScope(ScopeMode.GLOBAL)) { error("Unexpected lookup") }.single().country)
    }
}
