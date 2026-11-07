package com.meowv.tune.radio

import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.StationPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioQueueManagerTest {
    private val jp = requireNotNull(CountryCode.parse("JP"))
    private val us = requireNotNull(CountryCode.parse("US"))
    private val japan = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, jp), jp)
    private val america = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, us), us)
    private val global = ResolvedRadioScope(RadioScope(ScopeMode.GLOBAL), null)

    @Test fun countryNextKeepsOrderLoadsAnotherPageAndWraps() = runBlocking {
        val first = testStation(1)
        val second = testStation(2)
        val third = testStation(3)
        val foreign = testStation(4, us)
        val offsets = mutableListOf<Int>()
        val queue = RadioQueueManager(StubRadioRepository(countryPage = { country, offset, limit ->
            assertEquals(jp, country)
            assertEquals(30, limit)
            offsets += offset
            StationPage(listOf(second, foreign, third))
        }))
        queue.updateDirectory(japan, StationPage(listOf(first, foreign, second), 30))
        assertEquals(japan, queue.activeScope.value)
        assertTrue(queue.canNext.value)
        assertNull(queue.select(japan, foreign.uuid))
        assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, first.uuid))))
        assertEquals(second, queue.next(japan, null)?.station)
        assertTrue(offsets.isEmpty())
        assertEquals(third, queue.next(japan, second.uuid)?.station)
        assertEquals(first, queue.next(japan, third.uuid)?.station)
        assertEquals(listOf(30), offsets)
    }

    @Test fun previousOnlyChangesOnConfirmedPlayback() {
        val first = testStation(1)
        val failed = testStation(2)
        val third = testStation(3)
        val queue = RadioQueueManager(StubRadioRepository())
        queue.updateDirectory(japan, StationPage(listOf(first, failed, third)))
        assertNull(queue.previous(japan))
        assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, first.uuid))))
        val unconfirmed = requireNotNull(queue.select(japan, failed.uuid))
        val confirmed = requireNotNull(queue.select(japan, third.uuid))
        assertFalse(queue.canPrevious.value)
        assertFalse(queue.recordSuccessfulPlayback(unconfirmed))
        assertTrue(queue.recordSuccessfulPlayback(confirmed))
        assertFalse(queue.recordSuccessfulPlayback(confirmed))
        val previous = requireNotNull(queue.previous(japan))
        assertEquals(first, previous.station)
        assertTrue(queue.canPrevious.value)
        val retryPrevious = requireNotNull(queue.previous(japan))
        assertFalse(queue.recordSuccessfulPlayback(previous))
        assertTrue(queue.recordSuccessfulPlayback(retryPrevious))
        assertFalse(queue.canPrevious.value)
        assertNull(queue.previous(japan))
    }

    @Test fun globalRefillsAndExcludesTheTenMostRecentSuccesses() = runBlocking {
        val preview = (1L..11L).map { testStation(it) }
        val newStation = testStation(12, us)
        var calls = 0
        val queue = RadioQueueManager(StubRadioRepository(random = {
            calls++
            listOf(preview.last(), preview[1], preview[1], preview.first(), newStation)
        }))
        queue.updateDirectory(global, StationPage(preview))
        preview.forEach { expected ->
            val selection = requireNotNull(queue.next(global, null))
            assertEquals(expected, selection.station)
            assertTrue(queue.recordSuccessfulPlayback(selection))
        }
        assertEquals(0, calls)
        val repeatedOutsideWindow = requireNotNull(queue.next(global, null))
        assertEquals(preview.first(), repeatedOutsideWindow.station)
        assertTrue(queue.recordSuccessfulPlayback(repeatedOutsideWindow))
        assertEquals(newStation, queue.next(global, null)?.station)
        assertEquals(1, calls)
    }

    @Test fun scopeChangeClearsHistoryAndIgnoresLatePageEvenAfterReturningToSameScope() = runBlocking {
        withTimeout(5000) {
            val first = testStation(1)
            val second = testStation(2)
            val foreign = testStation(3, us)
            val start = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queue = RadioQueueManager(StubRadioRepository(countryPage = { _, _, _ ->
                start.complete(Unit)
                release.await()
                StationPage(listOf(testStation(4)))
            }))
            queue.updateDirectory(japan, StationPage(listOf(first, second), 30))
            assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, first.uuid))))
            assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, second.uuid))))
            val oldPrevious = requireNotNull(queue.previous(japan))
            val oldNext = async { queue.next(japan, second.uuid) }
            start.await()
            queue.clearScope()
            assertFalse(queue.canPrevious.value)
            assertFalse(queue.canNext.value)
            assertNull(queue.activeScope.value)
            assertNull(queue.next(japan, second.uuid))
            queue.updateDirectory(america, StationPage(listOf(foreign)))
            assertEquals(america, queue.activeScope.value)
            assertTrue(queue.canNext.value)
            assertEquals(foreign, queue.next(america, second.uuid)?.station)
            queue.updateDirectory(japan, StationPage(listOf(first)))
            release.complete(Unit)
            assertNull(oldNext.await())
            assertFalse(queue.recordSuccessfulPlayback(oldPrevious))
            assertFalse(queue.canPrevious.value)
            assertEquals(first, queue.next(japan, second.uuid)?.station)
        }
    }
}
