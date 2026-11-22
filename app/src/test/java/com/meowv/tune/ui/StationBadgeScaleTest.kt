package com.meowv.tune.ui

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class StationBadgeScaleTest {
    @Test
    fun squareArtworkFillsContainer() {
        val scale = BadgeContentScale.computeScaleFactor(Size(512f, 512f), Size(180f, 180f))
        assertEquals(180f, 512f * scale.scaleX, .01f)
        assertEquals(180f, 512f * scale.scaleY, .01f)
    }

    @Test
    fun nearlySquarePortraitKeepsTheFullHeight() {
        val scale = BadgeContentScale.computeScaleFactor(Size(1100f, 1199f), Size(180f, 180f))
        assertEquals(180f, 1199f * scale.scaleY, .01f)
        assertEquals(scale.scaleX, scale.scaleY, .0001f)
    }

    // 宽幅/竖长图多为带文字的台标，铺满会切掉内容，必须完整展示。
    @Test
    fun landscapeArtworkKeepsTheFullWidth() {
        val scale = BadgeContentScale.computeScaleFactor(Size(640f, 360f), Size(180f, 180f))
        assertEquals(180f, 640f * scale.scaleX, .01f)
        assertEquals(scale.scaleX, scale.scaleY, .0001f)
    }
}
