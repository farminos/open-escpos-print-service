package com.farminos.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsplRasterTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun pixels(
        width: Int,
        height: Int,
        vararg dark: Pair<Int, Int>,
    ): IntArray {
        val p = IntArray(width * height) { white }
        for ((x, y) in dark) p[y * width + x] = black
        return p
    }

    @Test
    fun blackPixelClearsItsOwnBitMsbFirst() {
        val raster = packTsplRaster(pixels(16, 1, 0 to 0, 9 to 0), 16, 1, false)

        assertEquals(0x7F.toByte(), raster.bytes[0])
        assertEquals(0xBF.toByte(), raster.bytes[1])
    }

    @Test
    fun oddWidthsArePaddedBlankNotClipped() {
        val raster = packTsplRaster(pixels(12, 1, 11 to 0), 12, 1, false)

        assertEquals(2, raster.widthBytes)
        assertEquals(0xEF.toByte(), raster.bytes[1])
    }

    @Test
    fun rasterIsWidthBytesTimesHeight() {
        val raster = packTsplRaster(pixels(12, 5), 12, 5, false)

        assertEquals(2, raster.widthBytes)
        assertEquals(10, raster.bytes.size)
    }

    @Test
    fun ditheringBreaksUpAFlatMidGreyThatThresholdLeavesBlank() {
        val grey = IntArray(64 * 8) { 0xFF808080.toInt() }

        val plain = packTsplRaster(grey, 64, 8, false)
        val dithered = packTsplRaster(grey, 64, 8, true)

        assertTrue(plain.bytes.all { it == (-1).toByte() })
        assertTrue(dithered.bytes.any { it != (-1).toByte() })
    }
}
