package com.farminos.print.driver

import android.content.Context
import com.farminos.print.BinaryImage
import com.farminos.print.PrinterSettings
import com.farminos.print.cmToPixels
import com.farminos.print.pixelsToCm
import java.io.ByteArrayOutputStream
import java.lang.Thread.sleep

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
    bitmap: BinaryImage,
    settings: PrinterSettings,
): ByteArray {
    val dpi = settings.dpi
    val horizontalOffset = 0
    val labelHeightCm = settings.height
    val labelHeightMarginCm = 0.15f
    val labelHeightPx = if (settings.cut) cmToPixels(labelHeightCm - labelHeightMarginCm, dpi) else bitmap.height
    val count = 1
    val output = ByteArrayOutputStream()
    output.write("! $horizontalOffset $dpi $dpi $labelHeightPx $count\r\n".toByteArray())
    if (!settings.cut) {
        output.write("JOURNAL\r\n".toByteArray())
    }
    output.write("CG ${bitmap.bytesPerLine} ${bitmap.height} 0 0 ".toByteArray())
    output.write(bitmap.pack(inverted = true))
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

    override fun printBitmap(bitmap: BinaryImage) {
        delayForLength(0f)
        waitUntilReady()
        socket.write(cpclBitmapToBytes(bitmap, settings))
        delayForLength(pixelsToCm(bitmap.height, settings.dpi))
        if (settings.cut && settings.cutDelay > 0) {
            sleep((settings.cutDelay * 1000).toLong())
            // Reset speed limit timer
            lastTime = System.currentTimeMillis()
        }
        // Wait until ready again as the Citizen CMP-30 connected through Bluetooth will cancel the print if the connection is closed
        waitUntilReady()
    }
}
