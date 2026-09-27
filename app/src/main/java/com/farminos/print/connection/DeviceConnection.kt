package com.farminos.print.connection

import java.io.OutputStream

class EscPosConnectionException(
    errorMessage: String?,
) : Exception(errorMessage)

abstract class DeviceConnection {
    protected var outputStream: OutputStream? = null

    abstract fun connect()

    abstract fun disconnect()

    open val isConnected: Boolean
        get() = this.outputStream != null

    fun write(bytes: ByteArray) {
        (outputStream ?: throw EscPosConnectionException("Not connected")).write(bytes)
        (outputStream ?: throw EscPosConnectionException("Not connected")).flush()
    }
}
