package com.farminos.print

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import com.dantsu.escposprinter.EscPosPrinterCommands
import com.dantsu.escposprinter.connection.DeviceConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

class CPCLPrinterCommands(
    private val printerConnection: DeviceConnection,
) : EscPosPrinterCommands(printerConnection) {
    override fun cutPaper(): CPCLPrinterCommands {
        if (!this.printerConnection.isConnected) {
            return this
        }
        this.printerConnection.write("CUT\r\n".toByteArray())
        this.printerConnection.send(100)
        return this
    }

    override fun reset(): CPCLPrinterCommands = this
}

fun cpclBitmapToBytes(
    bitmap: Bitmap,
    settings: PrinterSettings,
): ByteArray {
    val output = ByteArrayOutputStream()
    // The dithering is from com.dantsu.escposprinter.EscPosPrinterCommands, only the header changes
    val bitmapWidth = bitmap.width
    val bitmapHeight = bitmap.height
    val dpi = settings.dpi
    val horizontalOffset = 0
    val labelHeightCm = settings.height
    val labelHeightMarginCm = 0.15f
    val labelHeightPx = cmToPixels(labelHeightCm - labelHeightMarginCm, dpi)
    val count = 1
    val bytesPerLine = ceil(bitmapWidth / 8f).toInt()
    val header = "! $horizontalOffset $dpi $dpi $labelHeightPx $count\r\nCG $bytesPerLine $bitmapHeight 0 0 ".toByteArray()
    output.write(header)
    val imageBytes = ByteArray(bytesPerLine * bitmapHeight)
    var i = 0
    var greyscaleCoefficientInit = 0
    val gradientStep = 6
    val colorLevelStep = 765.0 / (15 * gradientStep + gradientStep - 1)
    for (posY in 0..<bitmapHeight) {
        var greyscaleCoefficient = greyscaleCoefficientInit
        val greyscaleLine = posY % gradientStep
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
                    if ((red + green + blue) < ((greyscaleCoefficient * gradientStep + greyscaleLine) * colorLevelStep)) {
                        b = b or (1 shl (7 - k))
                    }
                    greyscaleCoefficient += 5
                    if (greyscaleCoefficient > 15) {
                        greyscaleCoefficient -= 16
                    }
                }
            }
            imageBytes[i++] = b.toByte()
            j += 8
        }
        greyscaleCoefficientInit += 2
        if (greyscaleCoefficientInit > 15) {
            greyscaleCoefficientInit = 0
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

private fun getFirstUsbDevice(
    usbManager: UsbManager,
    id: String,
): UsbDevice =
    usbManager.deviceList?.values?.first {
        id == "%04x:%04x".format(it.vendorId, it.productId)
    } ?: error("Usb device $id not found")

open class EscPosDriver(
    private var context: Context,
    settings: PrinterSettings,
) : PrinterDriver(context, settings) {
    protected val commands: EscPosPrinterCommands

    protected open fun createCommands(socket: DeviceConnection): EscPosPrinterCommands = EscPosPrinterCommands(socket)

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
                ) ?: error("Can't get BluetoothManager")
            val bluetoothAdapter = bluetoothManager.adapter
            val device = bluetoothAdapter.getRemoteDevice(settings.address)
            socket = BluetoothConnection(device)
            app.escPosBluetoothSockets[settings.address] = socket
        }
        return socket
    }

    private fun getUsbSocket(settings: PrinterSettings): UsbConnection {
        val app: OpenESCPOSPrintService = context.applicationContext as OpenESCPOSPrintService
        var socket: UsbConnection? = null
        if (settings.keepAlive) {
            socket = app.escPosUsbSockets[settings.address]
        }
        if (socket == null) {
            val usbManager = ContextCompat.getSystemService(context, UsbManager::class.java) ?: error("Can't get UsbManager")
            val usbDevice = getFirstUsbDevice(usbManager, settings.address)
            socket = UsbConnection(usbManager, usbDevice)
            app.escPosUsbSockets[settings.address] = socket
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

                Interface.USB -> {
                    getUsbSocket(settings)
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
        commands = this.createCommands(socket)
        disconnectOnError {
            commands.connect()
            commands.reset()
        }
    }

    override fun printBitmap(bitmap: Bitmap) {
        val heightPx = 128
        delayForLength(0f)
        bitmapSlices(bitmap, heightPx).forEach {
            disconnectOnError {
                commands.printImage(EscPosPrinterCommands.bitmapToBytes(it, settings.dithering == Dithering.GRADIENT))
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

                Interface.USB -> {
                    app.escPosUsbSockets.remove(settings.address)
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
) : EscPosDriver(context, settings) {
    override fun createCommands(socket: DeviceConnection): EscPosPrinterCommands = CPCLPrinterCommands(socket)

    override fun printBitmap(bitmap: Bitmap) {
        delayForLength(0f)
        disconnectOnError {
            commands.printImage(cpclBitmapToBytes(bitmap, settings))
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
}

fun createDriver(
    ctx: Context,
    printerSettings: PrinterSettings,
): PrinterDriver {
    val driverClass =
        when (printerSettings.driver) {
            Driver.ESC_POS -> {
                ::EscPosDriver
            }

            Driver.CPCL -> {
                ::CpclDriver
            }

            else -> {
                throw Exception("Unrecognized driver in settings")
            }
        }
    return driverClass(ctx, printerSettings)
}
