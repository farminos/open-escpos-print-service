package com.farminos.print

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import com.farminos.print.connection.BluetoothConnection
import com.farminos.print.connection.DeviceConnection
import com.farminos.print.connection.TcpConnection
import com.farminos.print.connection.UsbConnection
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

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

// TODO: make PrinterDriver Closeable
abstract class PrinterDriver(
    context: Context,
    protected val settings: PrinterSettings,
) {
    protected var lastTime: Long? = null

    protected fun getDitheredBitmap(bitmap: Bitmap): Bitmap =
        when (settings.dithering) {
            Dithering.NONE -> {
                bitmap
            }

            Dithering.GRADIENT -> {
                ditherGradient(bitmap)
            }

            Dithering.FLOYD_STEINBERG -> {
                ditherFloydSteinberg(bitmap)
            }

            Dithering.ATKINSON -> {
                ditherAtkinson(bitmap)
            }

            else -> {
                throw Exception("Unknown dithering algorithm")
            }
        }

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

    abstract fun reset()

    abstract fun cutPaper()
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
    protected val socket: DeviceConnection

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
            socket = BluetoothConnection(context, device)
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
        socket =
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
        disconnectOnError {
            this.reset()
        }
    }

    override fun reset() {
        this.socket.write(byteArrayOf(0x1b, 0x40))
    }

    override fun cutPaper() {
        this.socket.write(byteArrayOf(0x1d, 0x56, 0x01))
    }

    override fun printBitmap(bitmap: Bitmap) {
        val ditheredBitmap = getDitheredBitmap(bitmap)
        val heightPx = 128
        delayForLength(0f)
        bitmapSlices(ditheredBitmap, heightPx).forEach {
            disconnectOnError {
                socket.write(escPosBitmapToBytes(it))
            }
            delayForLength(pixelsToCm(heightPx, settings.dpi))
        }
        if (settings.cut) {
            disconnectOnError {
                this.cutPaper()
            }
            if (settings.cutDelay > 0) {
                Thread.sleep((settings.cutDelay * 1000).toLong())
                // Reset speed limit timer
                lastTime = System.currentTimeMillis()
            }
        }
        disconnectOnError {
            this.reset()
        }
    }

    override fun disconnect(force: Boolean) {
        if (settings.keepAlive && !force) {
            return
        }
        // TODO: wait before disconnecting
        Thread.sleep(1000)
        try {
            this.socket.close()
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

class CpclDriver(
    private var context: Context,
    settings: PrinterSettings,
) : EscPosDriver(context, settings) {
    override fun reset() {
        // noop
    }

    override fun cutPaper() {
        // noop
    }

    private fun getStatus(): CpclPrinterStatus {
        socket.write(byteArrayOf(0x1b, 0x68))
        val res = ByteArray(1)
        val n = socket.read(res)
        if (n != 1) {
            throw Exception("Could not read printer status")
        }
        return CpclPrinterStatus(res[0].toInt())
    }

    private fun waitUntilReady() {
        // TODO: add a timeout?
        // TODO: sleep a bit between checks?
        do {
            val status = getStatus()
            val ready = status.isReadyToReceiveData
        } while (!ready)
    }

    override fun printBitmap(bitmap: Bitmap) {
        val ditheredBitmap = getDitheredBitmap(bitmap)
        delayForLength(0f)
        disconnectOnError {
            waitUntilReady()
            socket.write(cpclBitmapToBytes(ditheredBitmap, settings))
        }
        delayForLength(pixelsToCm(ditheredBitmap.height, settings.dpi))
        if (settings.cut && settings.cutDelay > 0) {
            Thread.sleep((settings.cutDelay * 1000).toLong())
            // Reset speed limit timer
            lastTime = System.currentTimeMillis()
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
