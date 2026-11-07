package com.farminos.print.driver

import android.content.Context
import com.farminos.print.BinaryImage
import com.farminos.print.PrinterSettings
import com.farminos.print.pixelsToCm
import java.io.ByteArrayOutputStream
import java.lang.Thread.sleep
import kotlin.sequences.forEach

fun initGSv0Command(
    bytesByLine: Int,
    bitmapHeight: Int,
): ByteArray {
    val xH = bytesByLine / 256
    val xL = bytesByLine - (xH * 256)
    val yH = bitmapHeight / 256
    val yL = bitmapHeight - (yH * 256)
    val command = ByteArray(8)
    command[0] = 0x1d
    command[1] = 0x76
    command[2] = 0x30
    command[3] = 0x00
    command[4] = xL.toByte()
    command[5] = xH.toByte()
    command[6] = yL.toByte()
    command[7] = yH.toByte()
    return command
}

fun escPosBitmapToBytes(bitmap: BinaryImage): ByteArray {
    val output = ByteArrayOutputStream()
    output.write(initGSv0Command(bitmap.bytesPerLine, bitmap.height))
    output.write(bitmap.pack(inverted = true))
    return output.toByteArray()
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

    override fun printBitmap(bitmap: BinaryImage) {
        val heightPx = 128
        delayForLength(0f)
        bitmap.slices(heightPx).forEach {
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

    override fun close() {
        // Some printers will stop printing as soon as the connection is closed.
        // We should wait for printer status before closing, but different printers use different commands to get their status; this would need to be implemented in subclasses.
        // As a workaround and to preserve <= v1.4.1 behavior, we wait a second before disconnecting.
        sleep(1000)
        super.close()
    }
}
