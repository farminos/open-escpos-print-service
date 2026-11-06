package com.farminos.print.driver

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.get
import com.farminos.print.Dithering
import com.farminos.print.PrinterSettings
import com.farminos.print.TsplMediaType
import com.farminos.print.cmToInches
import com.farminos.print.getDitheredBitmap
import com.farminos.print.getLuminanceData
import com.farminos.print.luminance
import java.io.ByteArrayOutputStream
import java.lang.Thread.sleep
import java.util.Locale

data class TsplRaster(
    val bytes: ByteArray,
    val widthBytes: Int,
    val height: Int,
)

/**
 * Packs a bitmap for a TSPL BITMAP command.
 *
 * TSPL is inverted relative to ESC/POS: a 0 bit burns and a 1 bit leaves the
 * label blank. Widths are whole bytes, so rows are padded blank rather than
 * clipped, which keeps the last few dots of an odd width.
 */
fun packTsplRaster(bitmap: Bitmap): TsplRaster {
    val width = bitmap.width
    val height = bitmap.height

    val widthBytes = (width + 7) / 8
    val out = ByteArray(widthBytes * height) { -1 } // 0xFF, so padding stays blank

    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = bitmap[x, y]
            val red = (color shr 16) and 255
            val green = (color shr 8) and 255
            val blue = color and 255
            if (red < 160 || green < 160 || blue < 160) {
                val j = y * widthBytes + (x shr 3)
                out[j] = (out[j].toInt() and (0x80 shr (x and 7)).inv()).toByte()
            }
        }
    }
    return TsplRaster(out, widthBytes, height)
}

const val MIN_DENSITY = 1
const val MAX_DENSITY = 15

class TsplDriver(
    context: Context,
    settings: PrinterSettings,
) : PrinterDriver(context, settings) {
    override fun reset() {
        // noop
    }

    override fun cutPaper() {
        // noop
    }

    private fun label(): String {
        val size =
            "SIZE %.2f,%.2f".format(
                Locale.US,
                cmToInches(settings.width),
                cmToInches(settings.height),
            )
        val media =
            when (settings.tsplMediaType) {
                TsplMediaType.BLACK_MARK -> "BLINE %.2f,0".format(Locale.US, cmToInches(settings.tsplGap))
                TsplMediaType.CONTINUOUS -> "GAP 0,0"
                else -> "GAP %.2f,0".format(Locale.US, cmToInches(settings.tsplGap))
            }
        // 1-15; 0 leaves whatever density the printer is already set to.
        val density = "DENSITY ${settings.tsplDensity.coerceIn(MIN_DENSITY, MAX_DENSITY)}"
        return listOf(size, media, density, "DIRECTION 0", "REFERENCE 0,0", "CLS").joinToString("\r\n", postfix = "\r\n")
    }

    override fun printBitmap(bitmap: Bitmap) {
        val ditheredBitmap = getDitheredBitmap(bitmap, settings.dithering)
        val raster = packTsplRaster(ditheredBitmap)
        val job = ByteArrayOutputStream()
        job.write(label().toByteArray(Charsets.US_ASCII))
        job.write(
            "BITMAP 0,0,${raster.widthBytes},${raster.height},0,".toByteArray(Charsets.US_ASCII),
        )
        job.write(raster.bytes)
        job.write("\r\nPRINT 1,1\r\n".toByteArray(Charsets.US_ASCII))
        socket.write(job.toByteArray())
        delayForLength(settings.height)
    }

    override fun close() {
        // Some printers will stop printing as soon as the connection is closed.
        // As a workaround and to preserve <= v1.4.1 behavior, we wait a second before disconnecting.
        sleep(1000)
        super.close()
    }
}
