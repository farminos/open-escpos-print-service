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
    protected val settings: PrinterSettings,
) {
    protected var lastTime: Long? = null

    protected fun disconnectOnError(block: () -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            disconnect(true)
            throw exception
        }
    }

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

    abstract fun disconnect(force: Boolean = false)

    fun printDocument(document: ParcelFileDescriptor) {
        pdfToBitmaps(document, settings.dpi, settings.width, settings.height).forEach { page ->
            val bitmap = if (settings.skipWhiteLinesAtPageEnd) bitmapCropWhiteEnd(page) else page
            printBitmap(bitmap)
        }
        document.close()
    }
}

class EscPosDriver(
    private var context: Context,
    settings: PrinterSettings,
) : PrinterDriver(context, settings) {
    private val commands: EscPosPrinterCommands

    private fun getBluetoothSocket(settings: PrinterSettings): BluetoothConnection {
        val app: OpenESCPOSPrintService = context.applicationContext as OpenESCPOSPrintService
        var socket: BluetoothConnection? = null
        if (settings.keepAlive) {
            socket = app.escPosBluetoothSockets[settings.address]
        }
        if (socket == null) {
            val bluetoothManager: BluetoothManager =
                ContextCompat.getSystemService(
                    context,
                    BluetoothManager::class.java,
                )!!
            val bluetoothAdapter = bluetoothManager.adapter
            val device = bluetoothAdapter.getRemoteDevice(settings.address)
            socket = BluetoothConnection(device)
            app.escPosBluetoothSockets[settings.address] = socket
        }
        return socket
    }

    private fun getTcpSocket(settings: PrinterSettings): TcpConnection {
        val app: OpenESCPOSPrintService = context.applicationContext as OpenESCPOSPrintService
        var socket: TcpConnection? = null
        if (settings.keepAlive) {
            socket = app.escPosTcpSockets[settings.name]
        }
        if (socket == null) {
            val addressAndPort = settings.address.split(":")
            socket = TcpConnection(addressAndPort[0], addressAndPort[1].toInt(), 5000)
            app.escPosTcpSockets[settings.name] = socket
        }
        return socket
    }

    init {
        val socket =
            when (settings.`interface`) {
                Interface.BLUETOOTH -> {
                    getBluetoothSocket(settings)
                }
                Interface.TCP_IP -> {
                    getTcpSocket(settings)
                }
                else -> {
                    throw Exception("Unknown interface")
                }
            }
        if (!socket.isConnected) {
            socket.connect()
        }
        commands = EscPosPrinterCommands(socket)
        disconnectOnError {
            commands.connect()
            commands.reset()
        }
    }

    override fun printBitmap(bitmap: Bitmap) {
        val heightPx = 128
        bitmapSlices(bitmap, heightPx).forEach {
            disconnectOnError {
                commands.printImage(EscPosPrinterCommands.bitmapToBytes(it, true))
            }
            delayForLength(pixelsToCm(heightPx, settings.dpi))
        }
        if (settings.cut) {
            disconnectOnError {
                commands.cutPaper()
            }
            if (settings.cutDelay > 0) {
                Thread.sleep((settings.cutDelay * 1000).toLong())
                // Reset speed limit timer
                lastTime = System.currentTimeMillis()
            }
        }
        disconnectOnError {
            commands.reset()
        }
    }

    override fun disconnect(force: Boolean) {
        if (settings.keepAlive && !force) {
            return
        }
        // TODO: wait before disconnecting
        Thread.sleep(1000)
        try {
            commands.disconnect()
        } finally {
            val app: OpenESCPOSPrintService = context.applicationContext as OpenESCPOSPrintService
            when (settings.`interface`) {
                Interface.BLUETOOTH -> {
                    app.escPosBluetoothSockets.remove(settings.address)
                }

                Interface.TCP_IP -> {
                    app.escPosTcpSockets.remove(settings.name)
                }

                else -> {
                    throw Exception("Unknown interface")
                }
            }
        }
    }
}

class CpclDriver(
    private var context: Context,
    settings: PrinterSettings,
) : PrinterDriver(context, settings) {
    private val socket: PortInterface
    private val requestHandlerThread: Thread
    private val cpclPrinter: CPCLPrinter

    private fun getBluetoothSocket(settings: PrinterSettings): BluetoothPort {
        val app: OpenESCPOSPrintService = context.applicationContext as OpenESCPOSPrintService
        var socket: BluetoothPort? = null
        if (settings.keepAlive) {
            socket = app.cpclBluetoothSockets[settings.address]
        }
        if (socket == null || !socket.isConnected) {
            socket = BluetoothPort.getInstance()
            socket.connect(settings.address)
            app.cpclBluetoothSockets[settings.address] = socket
        }
        return socket!!
    }

    private fun getTcpSocket(settings: PrinterSettings): WiFiPort {
        val app: OpenESCPOSPrintService = context.applicationContext as OpenESCPOSPrintService
        var socket: WiFiPort? = null
        if (settings.keepAlive) {
            socket = app.cpclTcpSockets[settings.name]
        }
        if (socket == null || !socket.isConnected) {
            socket = WiFiPort.getInstance()
            val addressAndPort = settings.address.split(":")
            socket.connect(addressAndPort[0], addressAndPort[1].toInt())
            app.cpclTcpSockets[settings.name] = socket
        }
        return socket!!
    }

    private fun printerCheck() {
        val checkStatus = cpclPrinter.printerCheck(5000)
        if (checkStatus != CPCLConst.CMP_SUCCESS) {
            throw Exception("Printer check failed: $checkStatus, please try again")
        }
        val status = cpclPrinter.status()
        if (status != CPCLConst.CMP_SUCCESS) {
            throw Exception("Printer status failed: $status, please try again")
        }
    }

    init {
        socket =
            when (settings.`interface`) {
                Interface.BLUETOOTH -> {
                    getBluetoothSocket(settings)
                }
                Interface.TCP_IP -> {
                    getTcpSocket(settings)
                }
                else -> {
                    throw Exception("Unknown interface")
                }
            }
        while (!socket.isConnected) {
            Thread.sleep(100)
        }
        requestHandlerThread = Thread(RequestHandler())
        requestHandlerThread.start()
        cpclPrinter = CPCLPrinter()
        disconnectOnError {
            printerCheck()
        }
    }

    override fun printBitmap(bitmap: Bitmap) {
        delayForLength(0F)
        disconnectOnError {
            cpclPrinter.setForm(0, settings.dpi, settings.dpi, (settings.height * 100).toInt(), 1)
            cpclPrinter.setMedia(CPCLConst.CMP_CPCL_LABEL)
        }
        val tileSize = 36
        bitmapNonEmptyTiles(bitmap, tileSize).forEach {
            val tileBitmap = Bitmap.createBitmap(bitmap, it.x, it.y, it.width, it.height)
            disconnectOnError {
                cpclPrinter.printBitmap(tileBitmap, it.x, it.y)
            }
        }
        disconnectOnError {
            cpclPrinter.printForm()
        }
        delayForLength(settings.height)
    }

    override fun disconnect(force: Boolean) {
        if (requestHandlerThread.isAlive) {
            requestHandlerThread.interrupt()
        }
        if (settings.keepAlive && !force) {
            return
        }
        if (socket.isConnected) {
            socket.disconnect()
        }
        val app: OpenESCPOSPrintService = context.applicationContext as OpenESCPOSPrintService
        when (settings.`interface`) {
            Interface.BLUETOOTH -> {
                app.cpclBluetoothSockets.remove(settings.address)
            }
            Interface.TCP_IP -> {
                app.cpclTcpSockets.remove(settings.name)
            }
            else -> {
                throw Exception("Unknown interface")
            }
        }
    }
}

fun createDriver(
    ctx: Context,
    printerSettings: PrinterSettings,
): PrinterDriver {
    val driverClass =
        when (printerSettings.driver) {
            Driver.ESC_POS -> ::EscPosDriver
            Driver.CPCL -> ::CpclDriver
            else -> {
                throw Exception("Unrecognized driver in settings")
            }
        }
    return driverClass(ctx, printerSettings)
}
