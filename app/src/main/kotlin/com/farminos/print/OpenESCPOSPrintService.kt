package com.farminos.print

import android.app.Application
import com.citizen.port.android.BluetoothPort
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection

class OpenESCPOSPrintService: Application() {
    val escPosBluetoothSockets: MutableMap<String, BluetoothConnection> = mutableMapOf()
    val cpclBluetoothSockets: MutableMap<String, BluetoothPort> = mutableMapOf()
}