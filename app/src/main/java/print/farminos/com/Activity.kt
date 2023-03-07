package print.farminos.com

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import print.farminos.com.ui.theme.FarminOSCITIZENPrintServiceTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val BLUETOOTH_ENABLE_REQUEST = 0
const val BLUETOOTH_PERMISSIONS_REQUEST = 1
@RequiresApi(Build.VERSION_CODES.S)
val PERMISSIONS =
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

@Serializable
data class PrinterSettings(
    val enabled: Boolean,
    val driver: String,
    val dpi: Int,
    val width: Float,
    val height: Float,
    val marginMils: Int,
    val cut: Boolean,
)

val DEFAULT_PRINTER_SETTINGS = PrinterSettings(
    enabled = false,
    driver = "escpos",
    dpi = 203,
    width = 5.1f,
    height = 8.0f,
    marginMils = 0,
    cut = false,
)

class Activity : ComponentActivity() {
    lateinit var bluetoothAdapter: BluetoothAdapter

    private val receiver = BluetoothBroadcastReceiver(this)

    lateinit var bluetoothState : MutableStateFlow<Boolean>
    //lateinit var printersState : MutableStateFlow<List<Printer>>

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // initialize printers state
        //printersState = MutableStateFlow(preferences.all.entries.map {
        //    Printer(it.value as String, it.key)
        //})

        // get bluetooth adapter
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        // requesting bluetooth permissions
        if (!bluetoothAllowed()) {
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
                ) {
                    Column(verticalArrangement = Arrangement.Center, modifier = Modifier.verticalScroll(
                        rememberScrollState(),
                    )) {
                        BluetoothComposable(context = activity)
                    }
                }
            }
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

    //fun addDevice(printer: Printer) {
    //    printersState.update {
    //        if (it.contains(printer)) { it } else { it + printer }
    //    }
    //    with (preferences.edit()) {
    //        putString(printer.address, printer.name)
    //        apply()
    //    }
    //}

    //fun removeDevice(printer: Printer) {
    //    printersState.update {
    //        it -> it.filter { it.address != printer.address }
    //    }
    //    with (preferences.edit()) {
    //        remove(printer.address)
    //        apply()
    //    }
    //}
}