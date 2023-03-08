package com.farminos.print

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

const val BLUETOOTH_ENABLE_REQUEST = 0
const val BLUETOOTH_PERMISSIONS_REQUEST = 1
@RequiresApi(Build.VERSION_CODES.S)
val PERMISSIONS =
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

object SettingsSerializer : Serializer<Settings> {
    override val defaultValue: Settings = Settings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): Settings {
        try {
            return Settings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: Settings,
        output: OutputStream
    ) = t.writeTo(output)
}

val Context.settingsDataStore: DataStore<Settings> by dataStore(
    fileName = "settings.pb",
    serializer = SettingsSerializer
)

//val DEFAULT_PRINTER_SETTINGS = PrinterSettings(
//    enabled = false,
//    driver = "escpos",
//    dpi = 203,
//    width = 5.1f,
//    height = 8.0f,
//    marginMils = 0,
//    cut = false,
//)

data class Printer(val address: String, val name: String)

class Activity : ComponentActivity() {
    private val receiver = BluetoothBroadcastReceiver(this)
    private val appCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var bluetoothEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)
    var printers: MutableStateFlow<List<Printer>> = MutableStateFlow(listOf())

    fun updateDefaultPrinter(address: String) {
        appCoroutineScope.launch {
            this@Activity.settingsDataStore.updateData { currentSettings ->
                currentSettings.toBuilder()
                    .setDefaultPrinter(address)
                    .build()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun updatePrinterSetting(address: String, updater: (ps: PrinterSettings.Builder) -> PrinterSettings.Builder) {
        appCoroutineScope.launch {
            this@Activity.settingsDataStore.updateData { currentSettings ->
                val builder = currentSettings.toBuilder()
                val printerBuilder = builder.printersMap.getOrDefault(address, PrinterSettings.getDefaultInstance()).toBuilder()
                builder.putPrinters(address, updater(printerBuilder).build())
                return@updateData builder.build()
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.M)
    private fun updatePrintersList() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothEnabled.update {
            bluetoothAdapter.isEnabled
        }
        printers.update {
            bluetoothAdapter.bondedDevices
                .filter { it.bluetoothClass.deviceClass == 1664 }  // 1664 is major 0x600 (IMAGING) + minor 0x80 (PRINTER)
                .map { Printer(address = it.address, name = it.name) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePrintersList();

        // requesting bluetooth permissions
        if (!bluetoothAllowed()) {
            requestBluetoothPermissions()
        }


        val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(receiver, intentFilter)

        setContent {
            SettingsScreen(context = this)
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSIONS_REQUEST) {
            var granted = true
            grantResults.forEach { granted = granted && it == PackageManager.PERMISSION_GRANTED }

            if (granted) {
                Toast.makeText(this, "permissions granted.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "permissions denied.", Toast.LENGTH_SHORT).show()
            }

        }

    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun bluetoothAllowed(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermissions() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            )
        ) {
            val builder = AlertDialog.Builder(this)

            builder
                .setTitle("bluetooth permissions needed")
                .setMessage("bluetooth is needed to read paired devices.")

            builder.setPositiveButton(
                "ok"
            ) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS.toTypedArray(),
                    BLUETOOTH_PERMISSIONS_REQUEST
                )
            }

            builder.setNegativeButton("deny") { dialog, _ ->
                dialog.dismiss()
            }

            builder
                .create()
                .show()

        } else {
            ActivityCompat.requestPermissions(
                this,
                PERMISSIONS.toTypedArray(),
                BLUETOOTH_PERMISSIONS_REQUEST
            )
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun enableBluetooth() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(intent, BLUETOOTH_ENABLE_REQUEST)
    }

    private class BluetoothBroadcastReceiver(_context: Activity) : BroadcastReceiver() {
        val activity = _context
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

                if (state == BluetoothAdapter.STATE_ON) {
                    activity.bluetoothEnabled.update {
                        true
                    }
                    Toast.makeText(context, "bluetooth is on.", Toast.LENGTH_SHORT).show()
                }
                if (state == BluetoothAdapter.STATE_OFF) {
                    activity.bluetoothEnabled.update {
                        false
                    }
                    Toast.makeText(context, "bluetooth is off.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}