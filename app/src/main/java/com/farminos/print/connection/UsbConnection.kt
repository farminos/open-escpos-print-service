package com.farminos.print.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

class UsbConnection(
    usbManager: UsbManager,
    device: UsbDevice,
    timeout: Int = 5000,
) : DeviceConnection() {
    private val usbConnection: UsbDeviceConnection = usbManager.openDevice(device) ?: throw IOException("Unable to open USB connection")

    init {
        val usbInterface: UsbInterface = findPrinterInterface(device)
        val usbEndpointOut: UsbEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
        val usbEndpointIn: UsbEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
        // TODO: timeout for outputStream
        outputStream = UsbOutputStream(usbConnection, usbInterface, usbEndpointOut)
        inputStream = UsbInputStream(usbConnection, usbInterface, usbEndpointIn, timeout)
    }

    override fun close() {
        super.close()
        try {
            usbConnection.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}

fun findPrinterInterface(usbDevice: UsbDevice): UsbInterface {
    val interfacesCount = usbDevice.interfaceCount
    for (i in 0..<interfacesCount) {
        val usbInterface = usbDevice.getInterface(i)
        if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
            return usbInterface
        }
    }
    throw IOException("Unable to find USB interface")
}

fun findEndpoint(
    usbInterface: UsbInterface,
    direction: Int,
): UsbEndpoint {
    val endpointsCount = usbInterface.endpointCount
    for (i in 0..<endpointsCount) {
        val endpoint = usbInterface.getEndpoint(i)
        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction) {
            return endpoint
        }
    }
    throw IOException("Unable to find USB endpoint")
}

class UsbOutputStream(
    private val usbConnection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val usbEndpoint: UsbEndpoint,
) : OutputStream() {
    override fun write(i: Int) {
        write(byteArrayOf(i.toByte()))
    }

    override fun write(bytes: ByteArray) {
        write(bytes, 0, bytes.size)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (!usbConnection.claimInterface(usbInterface, true)) {
            throw IOException("Unable to claim USB interface")
        }
        val buffer = ByteBuffer.wrap(bytes)
        val usbRequest = UsbRequest()
        try {
            usbRequest.initialize(usbConnection, usbEndpoint)
            if (!usbRequest.queue(buffer, bytes.size)) {
                throw IOException("Unable to queue USB request")
            }
            usbConnection.requestWait()
        } finally {
            usbRequest.close()
        }
    }

    override fun flush() {
    }

    override fun close() {
        try {
            usbConnection.releaseInterface(usbInterface)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}

class UsbInputStream(
    private val usbConnection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val usbEndpoint: UsbEndpoint,
    private val timeout: Int,
) : InputStream() {
    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (!usbConnection.claimInterface(usbInterface, true)) {
            throw IOException("Unable to claim USB interface")
        }
        return usbConnection
            .bulkTransfer(
                usbEndpoint,
                bytes,
                offset,
                length,
                timeout,
            ).also {
                if (it < 0) {
                    throw IOException("USB read failed: $it")
                }
            }
    }

    override fun read(): Int {
        val buffer = ByteArray(1)
        val count = read(buffer, 0, 1)
        return if (count == -1) -1 else buffer[0].toInt() and 0xFF
    }

    override fun close() {
        try {
            usbConnection.releaseInterface(usbInterface)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
