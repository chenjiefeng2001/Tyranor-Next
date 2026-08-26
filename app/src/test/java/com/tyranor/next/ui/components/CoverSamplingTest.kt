package com.tyranor.next.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSamplingTest {

    @Test
    fun smallCoversKeepFullResolution() {
        assertEquals(1, computeCoverInSampleSize(512, 683))
        assertEquals(1, computeCoverInSampleSize(300, 400))
    }

    @Test
    fun largeCoversStepDownByPowersOfTwo() {
        // 4096×3000：1→2000×1500 仍超限 → 2→1000×750 高度不足 → 停在 4? 逐步推演：
        // s=1: 2048≥512 && 1500≥683 ✓ → s=2
        // s=2: 1024≥512 && 750≥683 ✓ → s=4
        // s=4: 512≥512 && 375≥683 ✗ → 停
        assertEquals(4, computeCoverInSampleSize(4096, 3000))
    }

    @Test
    fun squareHugeCoverStopsOnHeightBoundary() {
        // 8192×8192：s=8 时 512≥512 但 512<683 → 停在 8
        assertEquals(8, computeCoverInSampleSize(8192, 8192))
    }

    @Test
    fun zeroOrNegativeDimensionsReturnUnity() {
        assertEquals(1, computeCoverInSampleSize(0, 0))
        assertEquals(1, computeCoverInSampleSize(-100, 500))
    }

    @Test
    fun customTargetsAreHonoured() {
        // 目标 128×128：1024×1024 → 512✓→2, 256✓→4, 128✓→8, 64✗ 停在 8
        assertEquals(8, computeCoverInSampleSize(1024, 1024, maxWidthPx = 128, maxHeightPx = 128))
        // 4096×4096 → …128✓→32, 64✗ 停在 32
        assertEquals(32, computeCoverInSampleSize(4096, 4096, maxWidthPx = 128, maxHeightPx = 128))
    }
}
