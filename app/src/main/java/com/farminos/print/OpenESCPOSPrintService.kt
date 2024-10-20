package com.farminos.print

import android.app.Application
import com.citizen.port.android.BluetoothPort
import com.citizen.port.android.WiFiPort
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.tcp.TcpConnection

class OpenESCPOSPrintService : Application() {
    val escPosBluetoothSockets: MutableMap<String, BluetoothConnection> = mutableMapOf()
    val escPosTcpSockets: MutableMap<String, TcpConnection> = mutableMapOf()
    val cpclBluetoothSockets: MutableMap<String, BluetoothPort> = mutableMapOf()
    val cpclTcpSockets: MutableMap<String, WiFiPort> = mutableMapOf()
}
