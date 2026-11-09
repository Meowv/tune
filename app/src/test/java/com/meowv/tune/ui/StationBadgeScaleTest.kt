package com.meowv.tune.ui

import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertEquals
import org.junit.Test

class StationBadgeScaleTest {
    @Test
    fun squareArtworkFillsContainer() {
        assertEquals(ContentScale.Crop, badgeScale(512f, 512f))
    }

    @Test
    fun nearlySquareArtworkFillsContainer() {
        assertEquals(ContentScale.Crop, badgeScale(300f, 280f))
        assertEquals(ContentScale.Crop, badgeScale(280f, 300f))
    }

    // 宽幅/竖长图多为带文字的台标，铺满会切掉内容，必须完整展示。
    @Test
    fun nonSquareArtworkKeepsFullContent() {
        assertEquals(ContentScale.Fit, badgeScale(640f, 360f))
        assertEquals(ContentScale.Fit, badgeScale(360f, 640f))
        assertEquals(ContentScale.Fit, badgeScale(300f, 200f))
    }

    @Test
    fun unknownSizeFallsBackToFit() {
        assertEquals(ContentScale.Fit, badgeScale(0f, 0f))
        assertEquals(ContentScale.Fit, badgeScale(512f, 0f))
        assertEquals(ContentScale.Fit, badgeScale(Float.NaN, Float.NaN))
    }
}
