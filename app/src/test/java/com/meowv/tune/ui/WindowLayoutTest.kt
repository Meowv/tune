package com.meowv.tune.ui

import com.meowv.tune.domain.LayoutMode
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
    fun layoutPreferenceOverridesShellWithSafeFallback() {
        // AUTO 跟随窗口；PHONE 强制手机；CAR 强制车机，窗口过窄时回退手机。
        assertEquals(true, resolveShell(LayoutMode.AUTO, 1280f, 720f))
        assertEquals(false, resolveShell(LayoutMode.AUTO, 432f, 936f))
        assertEquals(false, resolveShell(LayoutMode.PHONE, 1280f, 720f))
        assertEquals(false, resolveShell(LayoutMode.PHONE, 1920f, 1080f))
        assertEquals(true, resolveShell(LayoutMode.CAR, 1280f, 720f))
        assertEquals(false, resolveShell(LayoutMode.CAR, 432f, 936f))
        assertEquals(false, resolveShell(LayoutMode.CAR, 599f, 400f))
        assertEquals(false, resolveShell(LayoutMode.CAR, 700f, 320f))
    }
}
