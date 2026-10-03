package com.farminos.print.connection

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class TcpConnection(
    address: String?,
    port: Int,
    timeout: Int = 30,
) : DeviceConnection() {
    private var socket: Socket = Socket()

    init {
        socket.connect(
            InetSocketAddress(InetAddress.getByName(address), port),
            timeout,
        )
        outputStream = socket.getOutputStream()
        inputStream = socket.getInputStream()
    }

    override fun close() {
        super.close()
        try {
            socket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
