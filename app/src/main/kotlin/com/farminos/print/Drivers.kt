package com.farminos.print

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.citizen.jpos.command.CPCLConst
import com.citizen.jpos.printer.CPCLPrinter
import com.citizen.port.android.BluetoothPort
import com.citizen.request.android.RequestHandler
import com.dantsu.escposprinter.EscPosPrinterCommands
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection

// TODO: make PrinterDriver Closeable
abstract class PrinterDriver(
    context: Context,
    address: String,
    // TODO: pass settings instead
    protected val width: Double,
    protected val height: Double,
    protected val dpi: Int,
    protected val cut: Boolean,
    private val speedLimit: Float, // cm/s
    protected val cutDelay: Float, // s
) {
    protected var lastTime: Long? = null

    protected fun delayForlength(cm: Double) {
        val now = System.currentTimeMillis()
        if (lastTime != null && speedLimit > 0) {
            val elapsed = now - lastTime!!
            val duration = (cm / speedLimit * 1000).toLong()
            Thread.sleep(Math.max(0, duration - elapsed))
        }
        lastTime = now
    }

    abstract fun printBitmap(bitmap: Bitmap)

    abstract fun disconnect()

    fun printDocument(document: ParcelFileDescriptor) {
        pdfToBitmaps(document, dpi, width, height).forEach { page ->
            printBitmap(page)
        }
        document.close()
    }
}

class EscPosDriver(
    context: Context,
    address: String,
    width: Double,
    height: Double,
    dpi: Int,
    cut: Boolean,
    speedLimit: Float,
    cutDelay: Float,
): PrinterDriver(context, address, width, height, dpi, cut, speedLimit, cutDelay) {
    private val commands: EscPosPrinterCommands
    init {
        val bluetoothManager: BluetoothManager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
        val bluetoothAdapter = bluetoothManager.adapter
        val device = bluetoothAdapter.getRemoteDevice(address)
        val connection = BluetoothConnection(device)
        commands = EscPosPrinterCommands(connection)
        commands.connect()
        commands.reset()
    }

    override fun printBitmap(bitmap: Bitmap) {
        val heightPx = 128
        bitmapSlices(bitmap, heightPx).forEach {
            commands.printImage(EscPosPrinterCommands.bitmapToBytes(it))
            delayForlength(pixelsToCm(heightPx, dpi))
        }
        if (cut) {
            commands.cutPaper()
            if (cutDelay > 0) {
               Thread.sleep((cutDelay * 1000).toLong())
                // Reset speed limit timer
               lastTime = System.currentTimeMillis()
            }
        }
        commands.reset()
    }

    override fun disconnect() {
        // TODO: wait before disconnecting
        Thread.sleep(1000)
        commands.disconnect()
    }
}

class CpclDriver(
    context: Context,
    address: String,
    width: Double,
    height: Double,
    dpi: Int,
    cut: Boolean,
    speedLimit: Float,
    cutDelay: Float,
): PrinterDriver(context, address, width, height, dpi, cut, speedLimit, cutDelay) {
    private val bluetoothPort: BluetoothPort = BluetoothPort.getInstance()
    private val requestHandlerThread: Thread
    private val cpclPrinter: CPCLPrinter

    init {
        bluetoothPort.connect(address)
        // TODO: wait until it is actually connected
        requestHandlerThread = Thread(RequestHandler())
        requestHandlerThread.start()
        cpclPrinter = CPCLPrinter()
        val checkStatus = cpclPrinter.printerCheck()
        if (checkStatus != CPCLConst.CMP_SUCCESS) {
            throw Exception("Printer check failed")
        }
        val status = cpclPrinter.status()
        if (status != CPCLConst.CMP_SUCCESS) {
            throw Exception("Printer status failed")
        }
        cpclPrinter.setMedia(CPCLConst.CMP_CPCL_LABEL)
    }

    override fun printBitmap(bitmap: Bitmap) {
        cpclPrinter.setForm(0, dpi, dpi, (height * 100).toInt(), 1)
        cpclPrinter.printBitmap(bitmap, 0, 0)
        cpclPrinter.printForm()
        delayForlength(height)
    }

    override fun disconnect() {
        if (requestHandlerThread.isAlive) {
            requestHandlerThread.interrupt()
        }
        if (bluetoothPort.isConnected) {
            bluetoothPort.disconnect()
        }
    }
}