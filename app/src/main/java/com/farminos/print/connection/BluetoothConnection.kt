package com.farminos.print.connection

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

class BluetoothConnection(
    val context: Context,
    val device: BluetoothDevice,
) : DeviceConnection() {
    private var socket: BluetoothSocket? = null

    override val isConnected: Boolean
        get() = this.socket != null && this.socket!!.isConnected && super.isConnected

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun connect() {
        if (this.isConnected) {
            return
        }
        val bluetoothAdapter = ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter ?: return
        val uuid = this.deviceUUID
        try {
            this.socket = this.device.createRfcommSocketToServiceRecord(uuid)
            bluetoothAdapter.cancelDiscovery()
            this.socket!!.connect()
            this.outputStream = this.socket!!.outputStream
        } catch (e: IOException) {
            e.printStackTrace()
            this.disconnect()
            throw EscPosConnectionException("Unable to connect to bluetooth device.")
        }
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

    override fun disconnect() {
        if (this.outputStream != null) {
            try {
                this.outputStream!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            this.outputStream = null
        }
        if (this.socket != null) {
            try {
                this.socket!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            this.socket = null
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
