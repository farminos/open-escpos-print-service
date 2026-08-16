package com.farminos.print

import android.app.Application
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection

class OpenESCPOSPrintService : Application() {
    val escPosBluetoothSockets: MutableMap<String, BluetoothConnection> = mutableMapOf()
    val escPosUsbSockets: MutableMap<String, UsbConnection> = mutableMapOf()
    val escPosTcpSockets: MutableMap<String, TcpConnection> = mutableMapOf()
}
