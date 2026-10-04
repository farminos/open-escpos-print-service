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
import com.farminos.print.OpenEscPosPrintServiceApplication
import com.farminos.print.PrinterSettings
import com.farminos.print.bitmapCropWhiteEnd
import com.farminos.print.connection.BluetoothConnection
import com.farminos.print.connection.DeviceConnection
import com.farminos.print.connection.TcpConnection
import com.farminos.print.connection.UsbConnection
import com.farminos.print.pdfToBitmaps
import java.io.Closeable
import java.lang.Thread.sleep

private fun getFirstUsbDevice(
    usbManager: UsbManager,
    id: String,
): UsbDevice =
    usbManager.deviceList?.values?.first {
        id == "%04x:%04x".format(it.vendorId, it.productId)
    } ?: error("Usb device $id not found")

abstract class PrinterDriver(
    protected val context: Context,
    protected val settings: PrinterSettings,
) : Closeable {
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
        reset()
    }

    private fun getBluetoothSocket(settings: PrinterSettings): BluetoothConnection {
        val bluetoothManager: BluetoothManager =
            ContextCompat.getSystemService(
                context,
                BluetoothManager::class.java,
            ) ?: error("Can't get BluetoothManager")
        val bluetoothAdapter = bluetoothManager.adapter
        val device = bluetoothAdapter.getRemoteDevice(settings.address)
        return BluetoothConnection(context, device)
    }

    private fun getUsbSocket(settings: PrinterSettings): UsbConnection {
        val usbManager = ContextCompat.getSystemService(context, UsbManager::class.java) ?: error("Can't get UsbManager")
        val usbDevice = getFirstUsbDevice(usbManager, settings.address)
        return UsbConnection(usbManager, usbDevice)
    }

    private fun getTcpSocket(settings: PrinterSettings): TcpConnection {
        val addressAndPort = settings.address.split(":")
        // TODO: configurable timeout?
        return TcpConnection(addressAndPort[0], addressAndPort[1].toInt(), 5000)
    }

    override fun close() {
        sleep(1000)
        // TODO: try / catch
        socket.close()
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

private fun createDriver(
    context: Context,
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
    return driverClass(context, printerSettings)
}

private fun getDriver(
    context: Context,
    uuid: String,
    printerSettings: PrinterSettings,
): PrinterDriver {
    val app = context.applicationContext as OpenEscPosPrintServiceApplication
    var driver: PrinterDriver? = null
    if (printerSettings.keepAlive) {
        driver = app.connectedDrivers[uuid]
    }
    if (driver == null) {
        driver = createDriver(context, printerSettings)
    }
    if (printerSettings.keepAlive) {
        app.connectedDrivers[uuid] = driver
    }
    return driver
}

private fun closeDriver(
    context: Context,
    uuid: String,
    driver: PrinterDriver,
) {
    driver.close()
    val app = context.applicationContext as OpenEscPosPrintServiceApplication
    app.connectedDrivers.remove(uuid)
}

fun useDriver(
    context: Context,
    uuid: String,
    printerSettings: PrinterSettings,
    block: (driver: PrinterDriver) -> Unit,
) {
    val driver = getDriver(context, uuid, printerSettings)
    try {
        block(driver)
    } catch (exception: Exception) {
        closeDriver(context, uuid, driver)
        throw exception
    } finally {
        if (!printerSettings.keepAlive) {
            closeDriver(context, uuid, driver)
        }
    }
}
