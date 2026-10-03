package com.farminos.print.connection

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

class EscPosConnectionException(
    errorMessage: String?,
) : Exception(errorMessage)

abstract class DeviceConnection : Closeable {
    protected lateinit var outputStream: OutputStream
    protected lateinit var inputStream: InputStream

    override fun close() {
        try {
            outputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun read(bytes: ByteArray): Int = inputStream.read(bytes)

    fun write(bytes: ByteArray) {
        outputStream.write(bytes)
        outputStream.flush()
    }
}
