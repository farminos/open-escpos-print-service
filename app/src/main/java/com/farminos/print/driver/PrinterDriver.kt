package com.farminos.print.driver

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.farminos.print.Driver
import com.farminos.print.Interface
import com.farminos.print.OpenESCPOSPrintService
import com.farminos.print.PrinterSettings
import com.farminos.print.bitmapCropWhiteEnd
import com.farminos.print.connection.BluetoothConnection
import com.farminos.print.connection.DeviceConnection
import com.farminos.print.connection.TcpConnection
import com.farminos.print.connection.UsbConnection
import com.farminos.print.pdfToBitmaps
import java.lang.Thread.sleep

private fun getFirstUsbDevice(
    usbManager: UsbManager,
    id: String,
): UsbDevice =
    usbManager.deviceList?.values?.first {
        id == "%04x:%04x".format(it.vendorId, it.productId)
    } ?: error("Usb device $id not found")

// TODO: make PrinterDriver Closeable
abstract class PrinterDriver(
    protected val context: Context,
    protected val settings: PrinterSettings,
) {
    protected var lastTime: Long? = null
    protected val socket: DeviceConnection

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
            reset()
        }
    }

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

    fun disconnect(force: Boolean = false) {
        if (settings.keepAlive && !force) {
            return
        }
        sleep(1000)
        try {
            socket.close()
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
            sleep(Math.max(0, duration - elapsed))
        }
        lastTime = now
    }

    abstract fun printBitmap(bitmap: Bitmap)

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
