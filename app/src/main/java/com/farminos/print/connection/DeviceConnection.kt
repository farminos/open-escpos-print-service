package com.farminos.print.connection

import java.io.InputStream
import java.io.OutputStream

class EscPosConnectionException(
    errorMessage: String?,
) : Exception(errorMessage)

abstract class DeviceConnection {
    protected var outputStream: OutputStream? = null
    protected var inputStream: InputStream? = null

    abstract fun connect()

    abstract fun disconnect()

    open val isConnected: Boolean
        get() = this.outputStream != null

    fun read(bytes: ByteArray): Int {
        return (inputStream ?: throw EscPosConnectionException("Not connected")).read(bytes)
    }

    fun write(bytes: ByteArray) {
        (outputStream ?: throw EscPosConnectionException("Not connected")).write(bytes)
        (outputStream ?: throw EscPosConnectionException("Not connected")).flush()
    }
}
