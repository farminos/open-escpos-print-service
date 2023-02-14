package print.farminos.com

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import print.farminos.com.ui.theme.FarminOSCITIZENPrintServiceTheme

data class State(
    val printers: List<Printer>,
    val bluetooth: Boolean,
)

class Activity : ComponentActivity() {
    lateinit var bluetoothAdapter: BluetoothAdapter;
    private lateinit var preferences: SharedPreferences;

    private val receiver = BluetoothBroadcastReceiver(this)
    private val BLUETOOTH_ENABLE_REQUEST = 0;
    private val BLUETOOTH_PERMISSIONS_REQUEST = 1;

    private val PERMISSIONS =
        listOf<String>(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    lateinit var bluetoothState : MutableStateFlow<Boolean>
    lateinit var printersState : MutableStateFlow<List<Printer>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // get shared preferences
        preferences = this.getSharedPreferences("FarminOSPrintService", Context.MODE_PRIVATE)

        // initialize printers state
        printersState = MutableStateFlow(preferences.all.entries.map {
            it -> Printer(it.value as String, it.key)
        })

        // get bluetooth adapter
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        // requesting bluetooth permissions
        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions()
        }

        // initialize bluetooth state
        bluetoothState = MutableStateFlow(bluetoothAdapter.isEnabled)

        val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(receiver, intentFilter)

        val activity = this

        setContent {
            FarminOSCITIZENPrintServiceTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    color = MaterialTheme.colors.background,
                ) {
                    Column(verticalArrangement = Arrangement.Center) {
                        BluetoothComposable(context = activity)
                    }
                }
            }
        }
    }

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

    fun checkBluetoothPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) != PackageManager.PERMISSION_GRANTED
    }

    fun requestBluetoothPermissions() {
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
            ) { dialog, id ->
                ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS.toTypedArray(),
                    BLUETOOTH_PERMISSIONS_REQUEST
                )
            }

            builder.setNegativeButton("deny") { dialog, id ->
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
                    activity.bluetoothState.update {
                        true
                    }
                    Toast.makeText(context, "bluetooth is on.", Toast.LENGTH_SHORT).show()
                }
                if (state == BluetoothAdapter.STATE_OFF) {
                    activity.bluetoothState.update {
                        false
                    }
                    Toast.makeText(context, "bluetooth is off.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun addDevice(printer: Printer) {
        printersState.update {
            if (it.contains(printer)) { it } else { it + printer }
        }
        with (preferences.edit()) {
            putString(printer.address, printer.name)
            apply()
        }
    }

    fun removeDevice(printer: Printer) {
        printersState.update {
            it -> it.filter { it.address != printer.address }
        }
        with (preferences.edit()) {
            remove(printer.address)
            apply()
        }
    }
}