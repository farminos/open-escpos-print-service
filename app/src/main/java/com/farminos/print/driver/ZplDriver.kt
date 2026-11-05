package com.farminos.print.driver

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.get
import com.farminos.print.PrinterSettings
import com.farminos.print.getDitheredBitmap
import com.farminos.print.pixelsToCm
import java.lang.Thread.sleep
import kotlin.math.ceil

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

// ZPL repeat counts: 'G'..'Y' = 1..19, 'g'..'z' = 20..400 (multiples of 20).
private const val MAX_REPEAT = 419

private fun StringBuilder.appendRun(
    c: Char,
    count: Int,
) {
    var remaining = count
    while (remaining > 0) {
        val chunk = minOf(remaining, MAX_REPEAT)
        if (chunk <= 2) {
            repeat(chunk) { append(c) }
        } else {
            val twenties = chunk / 20
            val units = chunk % 20
            if (twenties > 0) append('g' + (twenties - 1))
            if (units > 0) append('G' + (units - 1))
            append(c)
        }
        remaining -= chunk
    }
}

private fun StringBuilder.appendRunLength(hex: CharSequence) {
    var i = 0
    while (i < hex.length) {
        val c = hex[i]
        var j = i + 1
        while (j < hex.length && hex[j] == c) j++
        appendRun(c, j - i)
        i = j
    }
}

/**
 * Encodes a 1-bit-per-pixel image (1 = black, MSB first, [bytesPerRow] bytes per row)
 * as ZPL "compressed ASCII hex" graphic data, suitable for ^GFA.
 *
 * Uses the ZPL compression scheme: ':' repeats the previous row, ',' fills the rest
 * of the row with zeros, and run-length counts (G-Y, g-z) before repeated hex digits.
 */
fun zplCompressGraphic(
    data: ByteArray,
    bytesPerRow: Int,
): String {
    require(bytesPerRow > 0) { "bytesPerRow must be > 0" }
    require(data.size % bytesPerRow == 0) { "data size must be a multiple of bytesPerRow" }
    val rows = data.size / bytesPerRow
    val out = StringBuilder()
    val row = StringBuilder(bytesPerRow * 2)
    var previousRow: String? = null
    for (r in 0 until rows) {
        row.setLength(0)
        for (b in 0 until bytesPerRow) {
            val v = data[r * bytesPerRow + b].toInt() and 0xff
            row.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0x0f])
        }
        val rowString = row.toString()
        if (rowString == previousRow) {
            out.append(':')
            continue
        }
        previousRow = rowString
        var end = rowString.length
        while (end > 0 && rowString[end - 1] == '0') end--
        out.appendRunLength(rowString.subSequence(0, end))
        if (end < rowString.length) {
            out.append(',')
        }
    }
    return out.toString()
}

fun zplBitmapToBits(bitmap: Bitmap): Pair<ByteArray, Int> {
    val width = bitmap.width
    val height = bitmap.height
    val bytesPerRow = ceil(width / 8.0).toInt()
    val data = ByteArray(bytesPerRow * height)
    var i = 0
    for (posY in 0 until height) {
        var x = 0
        while (x < width) {
            var b = 0
            for (k in 0..7) {
                val posX = x + k
                if (posX < width) {
                    val color = bitmap[posX, posY]
                    val red = (color shr 16) and 255
                    val green = (color shr 8) and 255
                    val blue = color and 255
                    if (red < 160 || green < 160 || blue < 160) {
                        b = b or (1 shl (7 - k))
                    }
                }
            }
            data[i++] = b.toByte()
            x += 8
        }
    }
    return Pair(data, bytesPerRow)
}

/** Builds a complete ZPL label (^XA ... ^XZ) printing [data] as a single graphic at the origin. */
fun zplGraphicLabel(
    data: ByteArray,
    bytesPerRow: Int,
    widthDots: Int,
    heightDots: Int,
): ByteArray {
    val total = data.size
    val graphic = zplCompressGraphic(data, bytesPerRow)
    val zpl =
        buildString {
            append("^XA\r\n")
            append("^PW").append(widthDots).append("\r\n")
            append("^LL").append(heightDots).append("\r\n")
            append("^LH0,0\r\n")
            append("^FO0,0^GFA,$total,$total,$bytesPerRow,")
            append(graphic)
            append("^FS\r\n")
            append("^PQ1\r\n")
            append("^XZ\r\n")
        }
    return zpl.toByteArray(Charsets.US_ASCII)
}

class ZplDriver(
    context: Context,
    settings: PrinterSettings,
) : PrinterDriver(context, settings) {
    override fun reset() {
        // noop: every label is a self-contained ^XA ... ^XZ format
    }

    override fun cutPaper() {
        // noop: label/gap handling is done by the printer's media settings
    }

    override fun printBitmap(bitmap: Bitmap) {
        val ditheredBitmap = getDitheredBitmap(bitmap, settings.dithering)
        val (data, bytesPerRow) = zplBitmapToBits(ditheredBitmap)
        delayForLength(0f)
        socket.write(zplGraphicLabel(data, bytesPerRow, ditheredBitmap.width, ditheredBitmap.height))
        delayForLength(pixelsToCm(ditheredBitmap.height, settings.dpi))
        if (settings.cutDelay > 0) {
            sleep((settings.cutDelay * 1000).toLong())
            // Reset speed limit timer
            lastTime = System.currentTimeMillis()
        }
    }

    override fun close() {
        // Give the printer a moment to drain its receive buffer before closing the connection.
        sleep(500)
        super.close()
    }
}
