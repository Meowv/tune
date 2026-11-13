package com.meowv.tune.radio

import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.PlaybackOrder
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioQueueManagerTest {
    @Test fun globalSequentialUsesOrderedPagesInsteadOfRandomPoolAndWraps() = runBlocking {
        val stations = (1L..3L).map { testStation(it) }
        val offsets = mutableListOf<Int>()
        val queue = RadioQueueManager(StubRadioRepository(globalPage = { offset, _ ->
            offsets += offset
            if (offset == 0) StationPage(stations.take(2), 30) else StationPage(stations.drop(2))
        }))
        queue.updateDirectory(global, StationPage(listOf(testStation(99))))
        repeat(4) { index ->
            val selection = requireNotNull(queue.next(global, null))
            assertEquals(stations[index % 3], selection.station)
            queue.recordSuccessfulPlayback(selection)
        }
        assertEquals(listOf(0, 30), offsets)
    }

    @Test fun countryShuffleLoadsFollowingPageWithoutDirectoryScrolling() = runBlocking {
        val first = testStation(1)
        val second = testStation(2)
        val queue = RadioQueueManager(StubRadioRepository(countryPage = { _, offset, _ ->
            assertEquals(30, offset)
            StationPage(listOf(second))
        }))
        queue.setPlaybackOrder(PlaybackOrder.SHUFFLE)
        queue.updateDirectory(japan, StationPage(listOf(first), 30))
        queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, first)))
        assertEquals(second, queue.next(japan, null)?.station)
    }

    private val jp = requireNotNull(CountryCode.parse("JP"))
    private val us = requireNotNull(CountryCode.parse("US"))
    private val japan = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, jp), jp)
    private val america = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, us), us)
    private val global = ResolvedRadioScope(RadioScope(ScopeMode.GLOBAL), null)

    @Test fun resumeSelectAdoptsSameScopeStationOutsidePage() = runBlocking {
        val listed = testStation(1)
        val outside = testStation(5)
        val foreign = testStation(6, us)
        val queue = RadioQueueManager(StubRadioRepository())
        queue.updateDirectory(japan, StationPage(listOf(listed)))

        assertNull("跨国家恢复必须拒绝", queue.select(japan, foreign))
        val adopted = requireNotNull(queue.select(japan, outside))
        assertEquals(outside, adopted.station)
        assertTrue(queue.recordSuccessfulPlayback(adopted))
        assertTrue("恢复不写入可返回历史", queue.canPrevious.value.not())
    }

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
        queue.setPlaybackOrder(PlaybackOrder.SHUFFLE)
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

    @Test fun shuffleNextUsesHistoryForPrevious() = runBlocking {
        val stations = (1L..6L).map { testStation(it) }
        val queue = RadioQueueManager(StubRadioRepository())
        queue.updateDirectory(japan, StationPage(stations))
        assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, stations.first().uuid))))
        assertFalse("顺序模式没有历史时不可返回", queue.canPrevious.value)

        queue.setPlaybackOrder(PlaybackOrder.SHUFFLE)
        assertFalse("没有历史时不能返回", queue.canPrevious.value)

        val next = requireNotNull(queue.next(japan, null))
        assertNotEquals(stations.first(), next.station)
        assertTrue(queue.recordSuccessfulPlayback(next))

        val previous = requireNotNull(queue.previous(japan))
        assertEquals(stations.first(), previous.station)
        assertTrue(queue.recordSuccessfulPlayback(previous))

        queue.setPlaybackOrder(PlaybackOrder.SEQUENTIAL)
        assertNull("返回首台后历史耗尽", queue.previous(japan))
    }

    @Test fun shuffleModeWithSingleStationReplaysIt() = runBlocking {
        val only = testStation(1)
        val queue = RadioQueueManager(StubRadioRepository())
        queue.setPlaybackOrder(PlaybackOrder.SHUFFLE)
        queue.updateDirectory(japan, StationPage(listOf(only)))
        assertTrue(queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, only.uuid))))
        assertFalse(queue.canPrevious.value)
        assertEquals(only, requireNotNull(queue.next(japan, null)).station)
        assertNull(queue.previous(japan))
    }

    @Test fun successfulPlaybackHistoryIsBounded() {
        val stations = (1L..150L).map { testStation(it) }
        val queue = RadioQueueManager(StubRadioRepository())
        queue.updateDirectory(japan, StationPage(stations))
        stations.forEach { queue.recordSuccessfulPlayback(requireNotNull(queue.select(japan, it.uuid))) }
        var previousCount = 0
        while (true) {
            val previous = queue.previous(japan) ?: break
            assertTrue(queue.recordSuccessfulPlayback(previous))
            previousCount++
        }
        assertEquals(100, previousCount)
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
