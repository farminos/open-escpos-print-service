package com.farminos.print

import android.app.Application
import com.farminos.print.connection.BluetoothConnection
import com.farminos.print.connection.TcpConnection
import com.farminos.print.connection.UsbConnection

class OpenESCPOSPrintService : Application() {
    val escPosBluetoothSockets: MutableMap<String, BluetoothConnection> = mutableMapOf()
    val escPosUsbSockets: MutableMap<String, UsbConnection> = mutableMapOf()
    val escPosTcpSockets: MutableMap<String, TcpConnection> = mutableMapOf()
}
