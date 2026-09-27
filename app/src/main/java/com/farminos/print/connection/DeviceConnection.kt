package com.farminos.print.connection

import java.io.IOException
import java.io.OutputStream

class EscPosConnectionException(
    errorMessage: String?,
) : Exception(errorMessage)

abstract class DeviceConnection {
    protected var outputStream: OutputStream? = null
    protected var data: ByteArray

    init {
        this.data = ByteArray(0)
    }

    abstract fun connect(): DeviceConnection?

    abstract fun disconnect(): DeviceConnection?

    open val isConnected: Boolean
        get() = this.outputStream != null

    fun write(bytes: ByteArray) {
        val data = ByteArray(bytes.size + this.data.size)
        System.arraycopy(this.data, 0, data, 0, this.data.size)
        System.arraycopy(bytes, 0, data, this.data.size, bytes.size)
        this.data = data
    }

    open fun send() {
        if (!this.isConnected) {
            throw EscPosConnectionException("Unable to send data to device.")
        }
        try {
            this.outputStream!!.write(this.data)
            this.outputStream!!.flush()
            this.data = ByteArray(0)
        } catch (e: IOException) {
            e.printStackTrace()
            throw EscPosConnectionException(e.message)
        } catch (e: InterruptedException) {
            e.printStackTrace()
            throw EscPosConnectionException(e.message)
        }
    }
}
