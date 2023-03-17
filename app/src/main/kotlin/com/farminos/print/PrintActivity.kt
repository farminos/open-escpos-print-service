package com.farminos.print

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.lifecycle.lifecycleScope
import com.google.protobuf.InvalidProtocolBufferException
import com.izettle.html2bitmap.Html2Bitmap
import com.izettle.html2bitmap.content.WebViewContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.*
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds


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

enum class PrinterConnection {
    BLUETOOTH, WIFI
}
data class Printer(val address: String, val name: String, val connection: PrinterConnection)

@Suppress("DEPRECATION")
class PrintActivity : ComponentActivity() {
    private val bluetoothBroadcastReceiver = BluetoothBroadcastReceiver(this)
    private val appCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePrintersList()
    }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePrintersList()
    }
    var bluetoothAllowed: MutableStateFlow<Boolean> = MutableStateFlow(false)
    var bluetoothEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)
    var printers: MutableStateFlow<List<Printer>> = MutableStateFlow(listOf())

    fun updateDefaultPrinter(address: String) {
        appCoroutineScope.launch {
            this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                currentSettings.toBuilder()
                    .setDefaultPrinter(address)
                    .build()
            }
        }
    }

    fun updatePrinterSetting(
        address: String,
        updater: (ps: PrinterSettings.Builder) -> PrinterSettings.Builder
    ) {
        appCoroutineScope.launch {
            this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                val builder = currentSettings.toBuilder()
                val printerBuilder = (builder.printersMap[address]
                    ?: PrinterSettings.getDefaultInstance()).toBuilder()
                builder.putPrinters(address, updater(printerBuilder).build())
                return@updateData builder.build()
            }
        }
    }

    private fun updatePrintersList() {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        bluetoothAllowed.update { allowed }
        if (!allowed) {
            return
        }
        val bluetoothManager = ContextCompat.getSystemService(this, BluetoothManager::class.java)
            ?: return
        val bluetoothAdapter = bluetoothManager.adapter
        bluetoothEnabled.update {
            bluetoothAdapter.isEnabled
        }

        printers.update {
            bluetoothAdapter.bondedDevices
                .filter { it.bluetoothClass.deviceClass == 1664 }  // 1664 is major 0x600 (IMAGING) + minor 0x80 (PRINTER)
                .map { Printer(address = it.address, name = it.name, connection = PrinterConnection.BLUETOOTH) }
        }
        WirelessDiscovery.discoverService(this@PrintActivity)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePrintersList()

        registerReceiver(
            bluetoothBroadcastReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )

        if (intent.action.equals(Intent.ACTION_VIEW)) {
            val content : String? = intent.getStringExtra("content")
            if (content == null) {
                // TODO: toast
            } else {
                val pages = JSONArray(decompress(Base64.decode(content, Base64.DEFAULT)))
                lifecycleScope.launch(Dispatchers.IO) {
                    printHtml(pages)
                }
            }
            finish()
            return
        }

        setContent {
            SettingsScreen(context = this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun printHtml(pages: JSONArray) {
        val settings = settingsDataStore.data.first()
        val defaultPrinter = settings.defaultPrinter
        val printerSettings = settings.printersMap[defaultPrinter]
        if (printerSettings == null) {
            // TODO: toast error
            return
        }
        val width = printerSettings.width.toDouble()
        val dpi = printerSettings.dpi
        val driver = printerSettings.driver
        val ctx = this
        val driverClass = when (driver) {
            Driver.ESC_POS -> ::EscPosDriver
            Driver.CPCL -> ::CpclDriver
            // TODO: handle this gracefully, factorize with print service
            else -> throw java.lang.Exception("Unrecognized driver in settings")
        }
        val instance = driverClass(ctx, defaultPrinter, printerSettings)
        renderPages(ctx, width, dpi, pages).forEach {
            instance.printBitmap(it)
        }
        // TODO: move this somewhere else
        instance.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothBroadcastReceiver)
    }

    fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            )
        }
    }

    fun enableBluetooth() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        activityResultLauncher.launch(intent)
    }

    private class BluetoothBroadcastReceiver(_context: PrintActivity) : BroadcastReceiver() {
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

private fun renderHtml(context: Context, width: Double, dpi: Int, content: String): Bitmap? {
    return Html2Bitmap.Builder()
        .setContext(context)
        .setBitmapWidth(cmToPixels(width, dpi))
        .setContent(WebViewContent.html(content))
        .setScreenshotDelay(0)
        .setMeasureDelay(0)
        .build()
        .bitmap
}

private fun renderPages(context: Context, width: Double, dpi: Int, pages: JSONArray) = sequence {
    for (i in 0 until pages.length()) {
        val page = pages.getString(i)
        val bitmap = renderHtml(context, width, dpi, page)
        if (bitmap != null) {
            yield(bitmap)
        }
    }
}

// TODO: add timeout-ing so that it can be used in Service.listPrinters alongside bluetooth discovery
object WirelessDiscovery {
    private const val SERVICE_TYPE = "_ipp._tcp"
    fun discoverService(context: PrintActivity) {
        val nsdManager = requireNotNull(context.getSystemService<NsdManager>())

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.d("NSD", "Start Discovery Failed")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d("NSD", "Service Discovery Started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d("NSD", "Service Discovery Stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("NSD", "Service Found: ${serviceInfo}")
                if (!isCorrectServiceType(serviceInfo)) {
                    return
                }

                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.d("NSD", "Resolve Failed")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d("NSD", "Service Resolved")
                            val name = serviceInfo.serviceName
                            val address = serviceInfo.host.hostAddress!!
                            val port = serviceInfo.port
                            context.printers.update {
                                it + Printer(address, name, connection = PrinterConnection.WIFI)
                            }
                        }
                    }
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d("NSD", "Service Lost")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    internal fun isCorrectServiceType(serviceInfo: NsdServiceInfo) =
        normalizeServiceName(serviceInfo.serviceType) == SERVICE_TYPE

    private fun normalizeServiceName(serviceName: String) =
        serviceName.trim('.')
}