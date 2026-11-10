package com.meowv.tune.update.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStateTest {

    @Test
    fun percentRoundsDownAndClamps() {
        assertEquals(0, InstallState.Downloading(0, 1000).percent)
        assertEquals(50, InstallState.Downloading(500, 1000).percent)
        assertEquals(99, InstallState.Downloading(999, 1000).percent)
        assertEquals(100, InstallState.Downloading(1000, 1000).percent)
        assertEquals(100, InstallState.Downloading(2000, 1000).percent)
    }

    @Test
    fun unknownTotalIsIndeterminate() {
        val state = InstallState.Downloading(1024, -1)
        assertTrue(state.indeterminate)
        assertEquals(0, state.percent)
        assertFalse(InstallState.Downloading(1024, 4096).indeterminate)
    }
}
