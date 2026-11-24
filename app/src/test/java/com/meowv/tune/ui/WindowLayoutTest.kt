package com.meowv.tune.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowLayoutTest {
    @Test
    fun layoutUsesWindowWidthHeightAndOrientation() {
        val samples = listOf(
            Triple(320f, 512f, false), Triple(432f, 936f, false),
            Triple(599f, 360f, false), Triple(600f, 359f, false),
            Triple(600f, 360f, true), Triple(600f, 500f, false),
            Triple(839f, 600f, true), Triple(840f, 1200f, false),
            Triple(840f, 800f, true), Triple(1280f, 720f, true),
            Triple(0f, 0f, false)
        )
        samples.forEach { (width, height, expected) ->
            assertEquals("$width x $height", expected, isCarWindow(width, height))
        }
    }

    @Test
    fun nowPlayingUsesTheSameWindowBasedWideLayoutDecision() {
        assertEquals(false, isNowPlayingWide(432f, 936f))
        assertEquals(false, isNowPlayingWide(840f, 1200f))
        assertEquals(true, isNowPlayingWide(1280f, 720f))
        assertEquals(true, isNowPlayingWide(600f, 360f))
    }
    @Test
    fun carRailLeavesRoomForLowLandscapeAndRespectsTheLargeWindowCap() {
        assertEquals(104f, carNavigationWidth(600f, 360f))
        assertEquals(120f, carNavigationWidth(800f, 380f))
        assertEquals(153.6f, carNavigationWidth(1024f, 600f), .01f)
        assertEquals(156f, carNavigationWidth(1280f, 720f))
    }

    @Test
    fun directoryUsesAvailableContentSpaceAndFallsBackForShortWindows() {
        assertEquals(1, directoryColumnCount(false, 900f, 600f))
        assertEquals(1, directoryColumnCount(true, 620f, 340f))
        assertEquals(2, directoryColumnCount(true, 660f, 280f))
        assertEquals(2, directoryColumnCount(true, 844f, 400f))
        assertEquals(1, directoryColumnCount(true, 844f, 279f))
    }
}
