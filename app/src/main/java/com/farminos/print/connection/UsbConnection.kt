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
) : DeviceConnection() {
    init {
        val usbConnection: UsbDeviceConnection = usbManager.openDevice(device) ?: throw IOException("Unable to open USB connection")
        val usbInterface: UsbInterface = findPrinterInterface(device)
        val usbEndpoint: UsbEndpoint = findEndpointIn(usbInterface)
        outputStream = UsbOutputStream(usbConnection, usbInterface, usbEndpoint)
        // TODO: implement input
        inputStream = UsbInputStream()
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

fun findEndpointIn(usbInterface: UsbInterface): UsbEndpoint {
    val endpointsCount = usbInterface.endpointCount
    for (i in 0..<endpointsCount) {
        val endpoint = usbInterface.getEndpoint(i)
        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
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
        usbConnection.close()
    }
}

class UsbInputStream : InputStream() {
    override fun read(): Int {
        TODO("Not yet implemented")
    }
}
