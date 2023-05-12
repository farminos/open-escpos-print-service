package com.farminos.print

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.protobuf.InvalidProtocolBufferException
import com.izettle.html2bitmap.Html2Bitmap
import com.izettle.html2bitmap.content.WebViewContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

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
        output: OutputStream,
    ) = t.writeTo(output)
}

val Context.settingsDataStore: DataStore<Settings> by dataStore(
    fileName = "settings.pb",
    serializer = SettingsSerializer,
)

class PrintActivity : ComponentActivity() {
    private val bluetoothBroadcastReceiver = BluetoothBroadcastReceiver(this)
    private val appCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updatePrintersList()
    }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updatePrintersList()
    }
    var bluetoothAllowed: MutableStateFlow<Boolean> = MutableStateFlow(false)
    var bluetoothEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)

    fun updateDefaultPrinter(address: String) {
        appCoroutineScope.launch {
            this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                currentSettings.toBuilder()
                    .setDefaultPrinter(address)
                    .build()
            }
        }
    }

    fun updatePrinterSetting(uuid: String, updater: (ps: PrinterSettings.Builder) -> PrinterSettings.Builder) {
        appCoroutineScope.launch {
            this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                val builder = currentSettings.toBuilder()
                val printerBuilder = (builder.printersMap[uuid] ?: PrinterSettings.getDefaultInstance()).toBuilder()
                builder.putPrinters(uuid, updater(printerBuilder).build())
                return@updateData builder.build()
            }
        }
    }

    fun addPrinterSetting() {
        appCoroutineScope.launch {
            this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                val uuid = UUID.randomUUID().toString()
                val builder = currentSettings.toBuilder()
                builder.putPrinters(
                    uuid,
                    DEFAULT_PRINTER_SETTINGS.toBuilder().setInterface(Interface.TCP_IP).build(),
                )
                return@updateData builder.build()
            }
        }
    }

    fun deletePrinterSetting(uuid: String) {
        appCoroutineScope.launch {
            this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                val builder = currentSettings.toBuilder()
                builder.removePrinters(uuid)
                if (uuid == builder.defaultPrinter) {
                    builder.defaultPrinter = ""
                }
                return@updateData builder.build()
            }
        }
    }

    fun printTestPage(uuid: String) {
        val pages = JSONArray()
        pages.put("<html><body><div style=\"font-size: 70vw; margin: 0 auto\">\uD83D\uDDA8️</div></body></html>")
        lifecycleScope.launch(Dispatchers.IO) {
            runOrToast {
                printHtml(pages, uuid)
            }
        }
    }

    private fun updatePrintersList() {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT,
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
        val bluetoothAdapter = bluetoothManager.adapter ?: return
        bluetoothEnabled.update {
            bluetoothAdapter.isEnabled
        }
        lifecycleScope.launch {
            bluetoothAdapter.bondedDevices
                .filter { it.bluetoothClass.deviceClass == 1664 } // 1664 is major 0x600 (IMAGING) + minor 0x80 (PRINTER)
                .forEach {
                    this@PrintActivity.settingsDataStore.updateData { currentSettings ->
                        val builder = currentSettings.toBuilder()
                        if (!currentSettings.printersMap.contains(it.address)) {
                            val newPrinter = DEFAULT_PRINTER_SETTINGS
                                .toBuilder()
                                .setInterface(Interface.BLUETOOTH)
                                .setAddress(it.address)
                                .setName(it.name)
                                .setDriver(if (it.name.startsWith("CMP_")) Driver.CPCL else Driver.ESC_POS)
                                .setKeepAlive(it.name.startsWith("CMP_")) // Keep connections alive by default for Citizen printers
                                .build()
                            builder.putPrinters(it.address, newPrinter)
                        }
                        return@updateData builder.build()
                    }
                }
        }
    }

    private suspend fun runOrToast(block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            exception.printStackTrace()
            this@PrintActivity.runOnUiThread {
                Toast.makeText(this@PrintActivity, exception.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePrintersList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePrintersList()

        registerReceiver(
            bluetoothBroadcastReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )

        when {
            intent.action.equals(Intent.ACTION_VIEW) -> {
                val content: String? = intent.getStringExtra("content")
                if (content == null) {
                    Toast.makeText(this, "No content provided for printing", Toast.LENGTH_SHORT)
                        .show()
                    finish()
                } else {
                    val pages = JSONArray(decompress(Base64.decode(content, Base64.DEFAULT)))
                    lifecycleScope.launch(Dispatchers.IO) {
                        runOrToast {
                            printHtml(pages)
                        }
                        finish()
                    }
                }
            }
            intent.action.equals(Intent.ACTION_SEND) && intent.type?.startsWith("image/") == true -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    handleSendImage(intent)
                    finish()
                }
            }
            intent.action.equals(Intent.ACTION_SEND_MULTIPLE) && intent.type?.startsWith("image/") == true -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    handleSendMultipleImages(intent)
                    finish()
                }
            }
            else -> {
                setContent {
                    SettingsScreen(context = this)
                }
            }
        }
    }

    private suspend fun handleSendImage(intent: Intent) {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
            runOrToast {
                printBitmaps(arrayListOf(it))
            }
        }
    }

    private suspend fun handleSendMultipleImages(intent: Intent) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
            runOrToast {
                printBitmaps(it)
            }
        }
    }

    private suspend fun printBitmaps(uris: ArrayList<Uri>) {
        val settings = settingsDataStore.data.first()
        val uuid = settings.defaultPrinter
        if (uuid == "" || uuid == null) {
            throw Exception("Please configure a default printer.")
        }
        val printerSettings = settings.printersMap[uuid]
        if (printerSettings == null) {
            throw Exception("Could not find printer settings.")
        }
        val instance = createDriver(this, printerSettings)
        try {
            uris.forEach {
                val orientation = contentResolver.openInputStream(it)?.use { inputStream ->
                    ExifInterface(inputStream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                } ?: ExifInterface.ORIENTATION_UNDEFINED
                contentResolver.openInputStream(it)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val scaledBitmap = scaleBitmap(rotateBitmap(bitmap, orientation), printerSettings)
                    instance.printBitmap(scaledBitmap)
                }
            }
        } finally {
            instance.disconnect()
        }
    }

    private suspend fun printHtml(pages: JSONArray, printerUuid: String? = null) {
        val settings = settingsDataStore.data.first()
        val uuid = printerUuid ?: settings.defaultPrinter
        if (uuid == "" || uuid == null) {
            throw Exception("Please configure a default printer.")
        }
        val printerSettings = settings.printersMap[uuid]
        if (printerSettings == null) {
            throw Exception("Could not find printer settings.")
        }
        val width = printerSettings.width
        val marginLeft = printerSettings.marginLeft
        val marginTop = printerSettings.marginTop
        val marginRight = printerSettings.marginRight
        val marginBottom = printerSettings.marginBottom
        val dpi = printerSettings.dpi
        val instance = createDriver(this, printerSettings)
        try {
            renderPages(
                this,
                width,
                dpi,
                pages,
                marginLeft,
                marginTop,
                marginRight,
                marginBottom,
            ).forEach {
                instance.printBitmap(it)
            }
        } finally {
            instance.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothBroadcastReceiver)
    }

    fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
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

private fun renderHtml(
    context: Context,
    widthPixels: Int,
    content: String,
): Bitmap? {
    return Html2Bitmap.Builder()
        .setContext(context)
        .setBitmapWidth(widthPixels)
        .setContent(WebViewContent.html(content))
        .setScreenshotDelay(0)
        .setMeasureDelay(0)
        .build()
        .bitmap
}

private fun renderPages(
    context: Context,
    width: Float,
    dpi: Int,
    pages: JSONArray,
    marginLeft: Float,
    marginTop: Float,
    marginRight: Float,
    marginBottom: Float,
) = sequence {
    val widthPx = cmToPixels(width, dpi)
    val marginLeftPx = cmToPixels(marginLeft, dpi)
    val marginTopPx = cmToPixels(marginTop, dpi)
    val marginRightPx = cmToPixels(marginRight, dpi)
    val marginBottomPx = cmToPixels(marginBottom, dpi)
    val renderWidthPx = widthPx - marginLeftPx - marginRightPx
    for (i in 0 until pages.length()) {
        val page = pages.getString(i)
        val bitmap = renderHtml(context, renderWidthPx, page)
        if (bitmap != null) {
            if (marginLeftPx == 0 && marginTopPx == 0 && marginRightPx == 0 && marginBottomPx == 0) {
                yield(bitmap)
            } else {
                yield(addMargins(bitmap, marginLeftPx, marginTopPx, marginRightPx, marginBottomPx))
            }
        }
    }
}
