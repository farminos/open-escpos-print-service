package com.farminos.print.connection

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class TcpConnection(
    private val address: String?,
    private val port: Int,
    private val timeout: Int = 30,
) : DeviceConnection() {
    private var socket: Socket? = null

    override val isConnected: Boolean
        get() = this.socket != null && this.socket!!.isConnected && super.isConnected

    override fun connect(): TcpConnection {
        if (this.isConnected) {
            return this
        }
        try {
            this.socket = Socket()
            this.socket!!.connect(
                InetSocketAddress(InetAddress.getByName(this.address), this.port),
                this.timeout,
            )
            this.outputStream = this.socket!!.getOutputStream()
            this.data = ByteArray(0)
        } catch (e: IOException) {
            e.printStackTrace()
            this.disconnect()
            throw EscPosConnectionException("Unable to connect to TCP device.")
        }
        return this
    }

    override fun disconnect(): TcpConnection {
        this.data = ByteArray(0)
        if (this.outputStream != null) {
            try {
                this.outputStream!!.close()
                this.outputStream = null
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        if (this.socket != null) {
            try {
                this.socket!!.close()
                this.socket = null
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return this
    }
}
