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
    protected val width: Double,
    protected val height: Double,
    protected val dpi: Int,
    protected val cut: Boolean,
) {
    abstract fun printBitmap(bitmap: Bitmap)

    abstract fun disconnect()

    fun printDocument(document: ParcelFileDescriptor) {
        pdfToBitmaps(document, dpi, width, height).forEach { page ->
            printBitmap(page)
        }
        document.close()
        // TODO
        val speed = 3 // cm/s
        Thread.sleep((height / speed * 1000).toLong())
    }
}

class EscPosDriver(
    context: Context,
    address: String,
    width: Double,
    height: Double,
    dpi: Int,
    cut: Boolean,
): PrinterDriver(context, address, width, height, dpi, cut) {
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
        bitmapSlices(bitmap, 128).forEach {
            Thread.sleep(100) // TODO: Needed on MTP-2 printer
            commands.printImage(EscPosPrinterCommands.bitmapToBytes(it))
        }
        if (cut) {
            // TODO: sleep ?
            commands.cutPaper()
        }
    }

    override fun disconnect() {
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
): PrinterDriver(context, address, width, height, dpi, cut) {
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
