package com.farminos.print.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.util.UUID

val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class BluetoothConnection(
    context: Context,
    val device: BluetoothDevice,
) : DeviceConnection() {
    private var socket: BluetoothSocket

    init {
        val bluetoothAdapter =
            ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter
                ?: throw EscPosConnectionException("No bluetooth adapter found")
        val uuid = deviceUUID
        socket = device.createRfcommSocketToServiceRecord(uuid)
        bluetoothAdapter.cancelDiscovery()
        socket.connect()
        outputStream = socket.outputStream
        inputStream = socket.inputStream
    }

    private val deviceUUID: UUID?
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        get() {
            val uuids = device.uuids
            if (uuids != null && uuids.size > 0) {
                if (listOf<ParcelUuid?>(*uuids).contains(ParcelUuid(SPP_UUID))) {
                    return SPP_UUID
                }
                return uuids[0]!!.uuid
            } else {
                return SPP_UUID
            }
        }

    override fun close() {
        super.close()
        try {
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
