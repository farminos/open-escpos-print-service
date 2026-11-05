package com.farminos.print

import com.farminos.print.driver.zplCompressGraphic
import com.farminos.print.driver.zplGraphicLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZplCompressionTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun allWhiteRowIsSingleComma() {
        assertEquals(",", zplCompressGraphic(bytes(0x00, 0x00), 2))
    }

    @Test
    fun trailingZerosAreReplacedByComma() {
        assertEquals("FF,", zplCompressGraphic(bytes(0xFF, 0x00), 2))
    }

    @Test
    fun rowWithoutTrailingZerosHasNoComma() {
        assertEquals("0F", zplCompressGraphic(bytes(0x0F), 1))
    }

    @Test
    fun repeatedRowUsesColon() {
        assertEquals("JF:", zplCompressGraphic(bytes(0xFF, 0xFF, 0xFF, 0xFF), 2))
    }

    @Test
    fun runLengthUsesTwentiesAndUnits() {
        // 24 bytes of 0xFF = 48 'F' = 40 ('h') + 8 ('N')
        assertEquals("hNF", zplCompressGraphic(ByteArray(24) { 0xFF.toByte() }, 24))
        // 30 bytes of 0x11 = 60 '1' = 'i'
        assertEquals("i1", zplCompressGraphic(ByteArray(30) { 0x11 }, 30))
    }

    @Test
    fun runsLongerThanMaxRepeatAreSplit() {
        // 250 bytes of 0xFF = 500 'F' = 419 ('z' + 'Y') + 81 ('j' + 'G')
        assertEquals("zYFjGF", zplCompressGraphic(ByteArray(250) { 0xFF.toByte() }, 250))
    }

    @Test
    fun labelContainsHeaderAndGraphic() {
        val zpl = String(zplGraphicLabel(bytes(0xFF, 0x00, 0xFF, 0x00), 2, 16, 2), Charsets.US_ASCII)
        assertTrue(zpl.startsWith("^XA"))
        assertTrue(zpl.contains("^PW16"))
        assertTrue(zpl.contains("^LL2"))
        assertTrue(zpl.contains("^FO0,0^GFA,4,4,2,FF,:^FS"))
        assertTrue(zpl.trimEnd().endsWith("^XZ"))
    }
}
