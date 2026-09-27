package com.farminos.print.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.farminos.print.connection.UsbDeviceHelper.findEndpointIn
import com.farminos.print.connection.UsbDeviceHelper.findPrinterInterface
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

class UsbConnection(
    private val usbManager: UsbManager,
    val device: UsbDevice?,
) : DeviceConnection() {
    override fun connect() {
        if (this.isConnected) {
            return
        }
        try {
            this.outputStream = UsbOutputStream(this.usbManager, this.device)
            this.data = ByteArray(0)
        } catch (e: IOException) {
            e.printStackTrace()
            this.outputStream = null
            throw EscPosConnectionException("Unable to connect to USB device.")
        }
    }

    override fun disconnect() {
        this.data = ByteArray(0)
        if (this.isConnected) {
            return
        }
        try {
            this.outputStream!!.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        this.outputStream = null
    }
}

object UsbDeviceHelper {
    fun findPrinterInterface(usbDevice: UsbDevice?): UsbInterface? {
        if (usbDevice == null) {
            return null
        }
        val interfacesCount = usbDevice.interfaceCount
        for (i in 0..<interfacesCount) {
            val usbInterface = usbDevice.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return usbInterface
            }
        }
        return null
    }

    fun findEndpointIn(usbInterface: UsbInterface?): UsbEndpoint? {
        if (usbInterface != null) {
            val endpointsCount = usbInterface.endpointCount
            for (i in 0..<endpointsCount) {
                val endpoint = usbInterface.getEndpoint(i)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    return endpoint
                }
            }
        }
        return null
    }
}

class UsbOutputStream(
    usbManager: UsbManager,
    usbDevice: UsbDevice?,
) : OutputStream() {
    private var usbConnection: UsbDeviceConnection?
    private var usbInterface: UsbInterface?
    private var usbEndpoint: UsbEndpoint?

    init {
        this.usbInterface = findPrinterInterface(usbDevice)
        if (this.usbInterface == null) {
            throw IOException("Unable to find USB interface.")
        }

        this.usbEndpoint = findEndpointIn(this.usbInterface)
        if (this.usbEndpoint == null) {
            throw IOException("Unable to find USB endpoint.")
        }

        this.usbConnection = usbManager.openDevice(usbDevice)
        if (this.usbConnection == null) {
            throw IOException("Unable to open USB connection.")
        }
    }

    override fun write(i: Int) {
        this.write(byteArrayOf(i.toByte()))
    }

    override fun write(bytes: ByteArray) {
        this.write(bytes, 0, bytes.size)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (this.usbInterface == null || this.usbEndpoint == null || this.usbConnection == null) {
            throw IOException("Unable to connect to USB device.")
        }

        if (!this.usbConnection!!.claimInterface(this.usbInterface, true)) {
            throw IOException("Error during claim USB interface.")
        }

        val buffer = ByteBuffer.wrap(bytes)
        val usbRequest = UsbRequest()
        try {
            usbRequest.initialize(this.usbConnection, this.usbEndpoint)
            if (!usbRequest.queue(buffer, bytes.size)) {
                throw IOException("Error queueing USB request.")
            }
            this.usbConnection!!.requestWait()
        } finally {
            usbRequest.close()
        }
    }

    override fun flush() {
    }

    override fun close() {
        if (this.usbConnection != null) {
            this.usbConnection!!.close()
            this.usbInterface = null
            this.usbEndpoint = null
            this.usbConnection = null
        }
    }
}
