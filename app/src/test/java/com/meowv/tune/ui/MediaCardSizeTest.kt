package com.meowv.tune.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardSizeTest {
    @Test
    fun largerPhoneWindowMakesRoomForALargerCardAndNeighbors() {
        val small = mediaCardSize(360.dp, 500.dp, isCar = false)
        val large = mediaCardSize(480.dp, 600.dp, isCar = false)
        assertTrue(large.width > small.width)
        assertTrue(large.height > small.height)
        assertTrue(480.dp - large.width >= 48.dp)
    }

    @Test
    fun shortCarWindowLimitsHeightAndKeepsTheCardReadable() {
        val short = mediaCardSize(700.dp, 175.dp, isCar = true)
        val tall = mediaCardSize(700.dp, 390.dp, isCar = true)
        assertTrue(short.height <= 175.dp)
        assertTrue(short.width >= 230.dp)
        assertTrue(tall.width > short.width)
        assertTrue(tall.height <= 390.dp)
        val veryShort = mediaCardSize(700.dp, 105.dp, isCar = true)
        assertTrue(veryShort.height <= 105.dp)
        assertTrue(veryShort.width < short.width)
    }
}
