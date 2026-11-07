package com.farminos.print.driver

import android.content.Context
import com.farminos.print.BinaryImage
import com.farminos.print.PrinterSettings
import com.farminos.print.TsplMediaType
import com.farminos.print.cmToInches
import java.io.ByteArrayOutputStream
import java.lang.Thread.sleep
import java.util.Locale

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
        val density = "DENSITY ${settings.tsplDensity.coerceIn(MIN_DENSITY, MAX_DENSITY)}"
        return listOf(size, media, density, "DIRECTION 0", "REFERENCE 0,0", "CLS").joinToString("\r\n", postfix = "\r\n")
    }

    override fun printBitmap(bitmap: BinaryImage) {
        val job = ByteArrayOutputStream()
        job.write(label().toByteArray(Charsets.US_ASCII))
        job.write(
            "BITMAP 0,0,${bitmap.bytesPerLine},${bitmap.height},0,".toByteArray(Charsets.US_ASCII),
        )
        job.write(bitmap.pack())
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
