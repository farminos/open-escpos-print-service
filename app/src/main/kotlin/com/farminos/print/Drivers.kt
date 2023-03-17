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
import com.dantsu.escposprinter.connection.DeviceConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.tcp.TcpConnection

// TODO: make PrinterDriver Closeable
abstract class PrinterDriver(
    context: Context,
    connection: PrinterConnection,
    address: String,
    protected val settings: PrinterSettings,
) {
    protected var lastTime: Long? = null

    protected fun delayForLength(cm: Double) {
        val now = System.currentTimeMillis()
        if (lastTime != null && settings.speedLimit > 0) {
            val elapsed = now - lastTime!!
            val duration = (cm / settings.speedLimit * 1000).toLong()
            Thread.sleep(Math.max(0, duration - elapsed))
        }
        lastTime = now
    }

    abstract fun printBitmap(bitmap: Bitmap)

    abstract fun disconnect()

    fun printDocument(document: ParcelFileDescriptor) {
        pdfToBitmaps(document, settings.dpi, settings.width.toDouble(), settings.height.toDouble() ).forEach { page ->
            printBitmap(page)
        }
        document.close()
    }
}

class EscPosDriver(
    context: Context,
    connection: PrinterConnection,
    address: String,
    settings: PrinterSettings,
): PrinterDriver(context, address, settings) {
    private val commands: EscPosPrinterCommands
    init {
        val deviceConnection = when (connection) {
            PrinterConnection.BLUETOOTH -> {
                val bluetoothManager: BluetoothManager = ContextCompat.getSystemService(
                    context,
                    BluetoothManager::class.java
                )!!
                val bluetoothAdapter = bluetoothManager.adapter
                val device = bluetoothAdapter.getRemoteDevice(address)
                BluetoothConnection(device)
            }
            PrinterConnection.WIFI -> {
                TcpConnection(address, 9300)
            }
        }
        commands = EscPosPrinterCommands(deviceConnection)
        commands.connect()
        commands.reset()
    }

    override fun printBitmap(bitmap: Bitmap) {
        val heightPx = 128
        bitmapSlices(bitmap, heightPx).forEach {
            commands.printImage(EscPosPrinterCommands.bitmapToBytes(it))
            delayForLength(pixelsToCm(heightPx, settings.dpi))
        }
        if (settings.cut) {
            commands.cutPaper()
            if (settings.cutDelay > 0) {
               Thread.sleep((settings.cutDelay * 1000).toLong())
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
    settings: PrinterSettings,
): PrinterDriver(context, address, settings) {
    private val bluetoothPort: BluetoothPort = BluetoothPort.getInstance()
    private val requestHandlerThread: Thread
    private val cpclPrinter: CPCLPrinter

    init {
        bluetoothPort.connect(address)
        while (!bluetoothPort.isConnected) {
            Thread.sleep(100)
        }
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
        cpclPrinter.setForm(0, settings.dpi, settings.dpi, (settings.height * 100).toInt(), 1)
        cpclPrinter.printBitmap(bitmap, 0, 0)
        cpclPrinter.printForm()
        delayForLength(settings.height.toDouble())
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