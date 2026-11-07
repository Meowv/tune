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
    fun demoStationKeysAreUniqueAndStable() {
        assertEquals(6, DemoStations.size)
        assertEquals(DemoStations.size, DemoStations.map { it.id }.distinct().size)
        assertEquals("CN", DemoStations.first().id)
    }
}
