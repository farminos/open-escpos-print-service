package com.farminos.print

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.MODE_READ_ONLY
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import io.github.mddanishansari.html_to_pdf.HtmlToPdfConvertor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext


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

data class Printer(val address: String, val name: String)
data class PrinterWithSettings(val printer: Printer, val settings: PrinterSettings)

suspend fun htmlToPdfCb(
    context: Context,
    content: String,
    callback: (File) -> Unit,
    errback: (Exception) -> Unit,
) {
    val htmlToPdfConvertor = HtmlToPdfConverter(context)
    val tmpFile = File.createTempFile(
        System.currentTimeMillis().toString(),
        null,
        context.cacheDir,
    )
    val result = htmlToPdfConvertor.convert(
        pdfLocation = tmpFile,
        htmlString = content,
        onPdfGenerationFailed = { exception ->
            errback(exception)
        },
        onPdfGenerated = { pdfFile ->
            callback(pdfFile)
        }
    )
}
@UiThread
suspend fun htmlToPdf(
    context: Context,
    content: String,
): File {
    println("htmlToPdf      : I'm working in thread ${Thread.currentThread().name}")
    return suspendCoroutine { continuation ->
        Log.d("WTF", "pdf generation start")
        println("suspendCoroutine      : I'm working in thread ${Thread.currentThread().name}")
        val htmlToPdfConvertor = HtmlToPdfConverter(context)
        val tmpFile = File.createTempFile(
            System.currentTimeMillis().toString(),
            null,
            context.cacheDir,
        )
        println("suspendCoroutine2      : I'm working in thread ${Thread.currentThread().name}")
        val result = htmlToPdfConvertor.convert(
            pdfLocation = tmpFile,
            htmlString = content,
            onPdfGenerationFailed = { exception ->
                Log.d("WTF", "pdf generation failed")
                continuation.resumeWithException(exception)
            },
            onPdfGenerated = { pdfFile ->
                Log.d("WTF", "pdf generation done")
                continuation.resume(pdfFile)
            }
        )
        println("suspendCoroutine after result  : I'm working in thread ${Thread.currentThread().name} $result")
    }
}

class PrintActivity : ComponentActivity() {
    private val bluetoothBroadcastReceiver = BluetoothBroadcastReceiver(this)
    private val appCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePrintersList()
    }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
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

    fun updatePrinterSetting(address: String, updater: (ps: PrinterSettings.Builder) -> PrinterSettings.Builder) {
        appCoroutineScope.launch {
            this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                val builder = currentSettings.toBuilder()
                val printerBuilder = (builder.printersMap[address] ?: PrinterSettings.getDefaultInstance()).toBuilder()
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
                .map { Printer(address = it.address, name = it.name) }
        }
    }

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
                //Log.d("WTF", "else")
                //val htmlToPdfConvertor = HtmlToPdfConverter(this)
                //val tmpFile = File.createTempFile(
                //    System.currentTimeMillis().toString(),
                //    null,
                //    cacheDir,
                //)
                //htmlToPdfConvertor.convert(
                //    pdfLocation = tmpFile,
                //    htmlString = content,
                //    onPdfGenerationFailed = { exception ->
                //        Log.d("WTF", "pdf generation failed")
                //    },
                //    onPdfGenerated = { pdfFile ->
                //        Log.d("WTF", "pdf generation done")
                //    }
                //)
                runBlocking {
                    Log.d("WTF", "runBlocking")
                    Log.d("WTF", "launch")
                    println("runBlocking      : I'm working in thread ${Thread.currentThread().name}")
                    printHtml(content)
                }
            }
            Log.d("WTF", "calling finish")
            finish()
            return
        }

        setContent {
            SettingsScreen(context = this)
        }
    }

    @UiThread
    suspend fun printHtml(content: String) {
        Log.d("WTF", "printHtml")
        println("printHtml      : I'm working in thread ${Thread.currentThread().name}")
        val settings = settingsDataStore.data.first()
        val defaultPrinter = settings.defaultPrinter
        val printerSettings = settings.printersMap[defaultPrinter]
        if (printerSettings == null) {
            // TODO: toast error
            return
        }
        Log.d("WTF", "printHtml has settings")
        val htmlToPdfConvertor = HtmlToPdfConverter(this)
        Log.d("WTF", "printHtml has converter")
        val tmpFile = File.createTempFile(
            System.currentTimeMillis().toString(),
            null,
            cacheDir,
        )
        Log.d("WTF", "printHtml has file")
        htmlToPdfCb(
            this,
            content,
            {
                escPosPrint(
                    context = this,
                    address = defaultPrinter,
                    width = printerSettings.width.toDouble(),
                    height = printerSettings.height.toDouble(),
                    dpi = printerSettings.dpi,
                    cut = printerSettings.cut,
                    document = ParcelFileDescriptor.open(it, MODE_READ_ONLY),
                )
            },
            {
                Log.d("WTF", "boom $it")
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothBroadcastReceiver)
    }

    fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
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