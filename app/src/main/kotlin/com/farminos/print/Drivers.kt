package com.farminos.print

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.citizen.jpos.command.CPCLConst
import com.citizen.jpos.printer.CPCLPrinter
import com.citizen.port.android.BluetoothPort
import com.citizen.port.android.PortInterface
import com.citizen.port.android.WiFiPort
import com.citizen.request.android.RequestHandler
import com.dantsu.escposprinter.EscPosPrinterCommands
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.tcp.TcpConnection

// TODO: make PrinterDriver Closeable
abstract class PrinterDriver(
    context: Context,
    address: String,
    protected val settings: PrinterSettings,
) {
    protected var lastTime: Long? = null

    protected fun delayForLength(cm: Float) {
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
        pdfToBitmaps(document, settings.dpi, settings.width, settings.height ).forEach { page ->
            printBitmap(page)
        }
        document.close()
    }
}

class EscPosDriver(
    context: Context,
    address: String,
    settings: PrinterSettings,
): PrinterDriver(context, address, settings) {
    private val commands: EscPosPrinterCommands
    init {
        val connection = when (settings.`interface`) {
            Interface.BLUETOOTH -> {
                val bluetoothManager: BluetoothManager = ContextCompat.getSystemService(
                    context,
                    BluetoothManager::class.java
                )!!
                val bluetoothAdapter = bluetoothManager.adapter
                val device = bluetoothAdapter.getRemoteDevice(address)
                BluetoothConnection(device)
            }
            Interface.TCP_IP -> {
                val addressAndPort = settings.address.split(":")
                TcpConnection(addressAndPort[0], addressAndPort[1].toInt())
            }
            else -> {
                throw Exception("Unknown interface")
            }
        }
        commands = EscPosPrinterCommands(connection)
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
    private val port: PortInterface
    private val requestHandlerThread: Thread
    private val cpclPrinter: CPCLPrinter

    init {
        port = when (settings.`interface`) {
            Interface.BLUETOOTH -> {
                BluetoothPort.getInstance()
            }
            Interface.TCP_IP -> {
                WiFiPort.getInstance()
            }
            else -> {
                throw Exception("Unknown interface")
            }
        }
        port.connect(address) // TODO: settings.address for wifi
        while (!port.isConnected) {
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
        delayForLength(settings.height)
    }

    override fun disconnect() {
        if (requestHandlerThread.isAlive) {
            requestHandlerThread.interrupt()
        }
        if (port.isConnected) {
            port.disconnect()
        }
    }
}