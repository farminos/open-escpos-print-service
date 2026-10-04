package com.farminos.print.driver

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.get
import com.farminos.print.PrinterSettings
import com.farminos.print.cmToPixels
import com.farminos.print.getDitheredBitmap
import com.farminos.print.pixelsToCm
import java.io.ByteArrayOutputStream
import java.lang.Thread.sleep
import kotlin.math.ceil

class CpclPrinterStatus(
    private val status: Int,
) {
    val isReady: Boolean get() = ((status shr 0) and 0b1) == 0
    val hasPaper: Boolean get() = ((status shr 1) and 0b1) == 0
    val latchIsClosed: Boolean get() = ((status shr 2) and 0b1) == 0
    val batteryLevelIsOk: Boolean get() = ((status shr 3) and 0b1) == 0
    val contrast: Int get() = ((status shr 8) and 0b1111)
    val isReadyToReceiveData: Boolean get() = isReady && hasPaper && latchIsClosed
}

fun cpclBitmapToBytes(
    bitmap: Bitmap,
    settings: PrinterSettings,
): ByteArray {
    val bitmapWidth = bitmap.width
    val bitmapHeight = bitmap.height
    val dpi = settings.dpi
    val horizontalOffset = 0
    val labelHeightCm = settings.height
    val labelHeightMarginCm = 0.15f
    val labelHeightPx = if (settings.cut) cmToPixels(labelHeightCm - labelHeightMarginCm, dpi) else bitmapHeight
    val count = 1
    val bytesPerLine = ceil(((bitmapWidth.toFloat()) / 8f).toDouble()).toInt()
    val output = ByteArrayOutputStream()
    output.write("! $horizontalOffset $dpi $dpi $labelHeightPx $count\r\n".toByteArray())
    if (!settings.cut) {
        output.write("JOURNAL\r\n".toByteArray())
    }
    output.write("CG $bytesPerLine $bitmapHeight 0 0 ".toByteArray())
    val imageBytes = ByteArray(bytesPerLine * bitmapHeight)
    var i = 0
    for (posY in 0..<bitmapHeight) {
        var j = 0
        while (j < bitmapWidth) {
            var b = 0
            for (k in 0..7) {
                val posX = j + k
                if (posX < bitmapWidth) {
                    val color = bitmap[posX, posY]
                    val red = (color shr 16) and 255
                    val green = (color shr 8) and 255
                    val blue = color and 255
                    if (red < 160 || green < 160 || blue < 160) {
                        b = b or (1 shl (7 - k))
                    }
                }
            }
            imageBytes[i++] = b.toByte()
            j += 8
        }
    }
    output.write(imageBytes)
    output.write("\r\n".toByteArray())
    if (settings.cut) {
        output.write("FORM\r\n".toByteArray())
    }
    output.write("PRINT\r\n".toByteArray())
    return output.toByteArray()
}

class CpclDriver(
    context: Context,
    settings: PrinterSettings,
) : PrinterDriver(context, settings) {
    override fun reset() {
        // noop
    }

    override fun cutPaper() {
        // noop
    }

    private fun getStatus(): CpclPrinterStatus {
        socket.write(byteArrayOf(0x1b, 0x68))
        sleep(20)
        val res = ByteArray(1)
        val n = socket.read(res)
        if (n != 1) {
            throw Exception("Could not read printer status")
        }
        return CpclPrinterStatus(res[0].toInt())
    }

    private fun waitUntilReady() {
        // TODO: add a timeout?
        do {
            val status = getStatus()
            val ready = status.isReadyToReceiveData
        } while (!ready)
    }

    override fun printBitmap(bitmap: Bitmap) {
        val ditheredBitmap = getDitheredBitmap(bitmap, settings.dithering)
        delayForLength(0f)
        disconnectOnError {
            waitUntilReady()
            socket.write(cpclBitmapToBytes(ditheredBitmap, settings))
        }
        delayForLength(pixelsToCm(ditheredBitmap.height, settings.dpi))
        if (settings.cut && settings.cutDelay > 0) {
            sleep((settings.cutDelay * 1000).toLong())
            // Reset speed limit timer
            lastTime = System.currentTimeMillis()
        }
    }
}
