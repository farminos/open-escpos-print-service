package com.farminos.print.driver

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.get
import com.farminos.print.PrinterSettings
import com.farminos.print.bitmapSlices
import com.farminos.print.getDitheredBitmap
import com.farminos.print.pixelsToCm
import java.lang.Thread.sleep
import kotlin.math.ceil
import kotlin.sequences.forEach

fun initGSv0Command(
    bytesByLine: Int,
    bitmapHeight: Int,
): ByteArray {
    val xH = bytesByLine / 256
    val xL = bytesByLine - (xH * 256)
    val yH = bitmapHeight / 256
    val yL = bitmapHeight - (yH * 256)
    val imageBytes = ByteArray(8 + bytesByLine * bitmapHeight)
    imageBytes[0] = 0x1d
    imageBytes[1] = 0x76
    imageBytes[2] = 0x30
    imageBytes[3] = 0x00
    imageBytes[4] = xL.toByte()
    imageBytes[5] = xH.toByte()
    imageBytes[6] = yL.toByte()
    imageBytes[7] = yH.toByte()
    return imageBytes
}

fun escPosBitmapToBytes(bitmap: Bitmap): ByteArray {
    val bitmapWidth = bitmap.getWidth()
    val bitmapHeight = bitmap.getHeight()
    val bytesByLine = ceil(((bitmapWidth.toFloat()) / 8f).toDouble()).toInt()
    val imageBytes = initGSv0Command(bytesByLine, bitmapHeight)
    var i = 8
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
    return imageBytes
}

class EscPosDriver(
    context: Context,
    settings: PrinterSettings,
) : PrinterDriver(context, settings) {
    override fun reset() {
        socket.write(byteArrayOf(0x1b, 0x40))
    }

    override fun cutPaper() {
        socket.write(byteArrayOf(0x1d, 0x56, 0x01))
    }

    override fun printBitmap(bitmap: Bitmap) {
        val ditheredBitmap = getDitheredBitmap(bitmap, settings.dithering)
        val heightPx = 128
        delayForLength(0f)
        bitmapSlices(ditheredBitmap, heightPx).forEach {
            socket.write(escPosBitmapToBytes(it))
            delayForLength(pixelsToCm(heightPx, settings.dpi))
        }
        if (settings.cut) {
            cutPaper()
            if (settings.cutDelay > 0) {
                sleep((settings.cutDelay * 1000).toLong())
                // Reset speed limit timer
                lastTime = System.currentTimeMillis()
            }
        }
        reset()
    }
}
