package com.farminos.print

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.Resolution
import android.print.PrintJobInfo
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.citizen.jpos.command.CPCLConst
import com.citizen.jpos.printer.CPCLPrinter
import com.citizen.port.android.BluetoothPort
import com.citizen.request.android.RequestHandler
import com.dantsu.escposprinter.EscPosPrinterCommands
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.*
import java.text.DecimalFormat
import kotlin.math.ceil

private fun cmToDots(cm: Double, dpi: Int): Int {
    return ceil((cm / 2.54) * dpi).toInt()
}

private fun convertTransparentToWhite(bitmap: Bitmap) {
    val pixels = IntArray(bitmap.height * bitmap.width)
    bitmap.getPixels(
        pixels,
        0,
        bitmap.width,
        0,
        0,
        bitmap.width,
        bitmap.height
    )
    for (j in pixels.indices) {
        if (pixels[j] == Color.TRANSPARENT) {
            pixels[j] = Color.WHITE
        }
    }
    bitmap.setPixels(
        pixels,
        0,
        bitmap.width,
        0,
        0,
        bitmap.width,
        bitmap.height
    )
}

private fun pdfToBitmaps(document: ParcelFileDescriptor, dpi: Int, w: Double, h: Double) = sequence<Bitmap> {
    val renderer = PdfRenderer(document)
    val pageCount = renderer.pageCount
    for (i in 0 until pageCount) {
        val width = cmToDots(w, dpi)
        val height = cmToDots(h, dpi)
        val page = renderer.openPage(i)
        val transform = Matrix()
        val ratio = width.toFloat() / page.width
        transform.postScale(ratio, ratio)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        convertTransparentToWhite(bitmap)
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        yield(bitmap)
        page.close()
    }
    renderer.close()
}

private fun bitmapSlices(bitmap: Bitmap, step: Int) = sequence<Bitmap> {
    val width: Int = bitmap.width
    val height: Int = bitmap.height
    for (y in 0 until height step step) {
        val slice = Bitmap.createBitmap(
            bitmap,
            0,
            y,
            width,
            if (y + step >= height) height - y else step
        )
        yield(slice)
    }
}

internal class PrintJobThread(
    private val context: FarminOSPrintService,
    private val printer: PrinterWithSettingsAndInfo,
    private val info: PrintJobInfo,
    private val document: ParcelFileDescriptor
) : Thread() {
    override fun run() {
        // TODO: maybe this does not need to be a thread
        val mediaSize = info.attributes.mediaSize
        val resolution = info.attributes.resolution
        if (mediaSize == null || resolution == null) {
            // TODO: handle this gracefully
            throw java.lang.Exception("No media size or resolution in print job info")
        }
        val printFn = when (printer.settings.driver) {
            Driver.ESC_POS -> ::escPosPrint
            Driver.CPCL -> ::cpclPrint
            // TODO: handle this gracefully
            Driver.UNRECOGNIZED -> throw java.lang.Exception("Unrecognized driver in settings")
        }
        try {
            printFn(
                context,
                printer.printer.address,
                milsToCm(mediaSize.widthMils),
                milsToCm(mediaSize.heightMils),
                resolution.horizontalDpi,
                printer.settings.cut,
                document,
            )
        } catch (exception: Exception) {
            ContextCompat.getMainExecutor(context).execute {
                Toast.makeText(context, exception.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun cpclPrint(
    context: Context,
    address: String,
    width: Double,
    height: Double,
    dpi: Int,
    cut: Boolean,
    document: ParcelFileDescriptor,
) {
    // TODO: quantity
    val bluetoothPort = BluetoothPort.getInstance()
    val thread = Thread(RequestHandler())
    thread.start()
    try {
        bluetoothPort.connect(address)
        val cpclPrinter = CPCLPrinter()
        val checkStatus = cpclPrinter.printerCheck()
        if (checkStatus != CPCLConst.CMP_SUCCESS) {
            throw Exception("Printer check failed")
        }
        val status = cpclPrinter.status()
        if (status != CPCLConst.CMP_SUCCESS) {
            throw Exception("Printer status failed")
        }
        cpclPrinter.setMedia(CPCLConst.CMP_CPCL_LABEL)
        val pages = pdfToBitmaps(document, dpi, width, height)
        pages.forEach {
            cpclPrinter.setForm(0, dpi, dpi, (height * 100).toInt(), 1)
            cpclPrinter.printBitmap(it, 0, 0)
            cpclPrinter.printForm()
        }
    } finally {
        if (bluetoothPort.isConnected) {
            bluetoothPort.disconnect()
        }
        document.close()
        if (thread.isAlive) {
            thread.interrupt()
        }
    }
}

fun escPosPrint(
    context: Context,
    address: String,
    width: Double,
    height: Double,
    dpi: Int,
    cut: Boolean,
    document: ParcelFileDescriptor,
) {
    // TODO: On receipt printers: truncate each page once only white or transparent pixels remain.
    // TODO: !!
    val bluetoothManager: BluetoothManager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
    val bluetoothAdapter = bluetoothManager.adapter
    val device = bluetoothAdapter.getRemoteDevice(address)
    val connection = BluetoothConnection(device)
    val printerCommands = EscPosPrinterCommands(connection)
    printerCommands.connect()
    printerCommands.reset()
    val pages = pdfToBitmaps(document, dpi, width, height)
    pages.forEach { page ->
        bitmapSlices(page, 128).forEach {
            Thread.sleep(100) // TODO: Needed on MTP-2 printer
            printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(it))
        }
        if (cut) {
            // TODO: sleep ?
            printerCommands.cutPaper()
        }
    }
    document.close()
    // TODO
    val speed = 3 // cm/s
    Thread.sleep((height / speed * 1000).toLong())
    printerCommands.disconnect()
}


fun cmToMils(cm: Double): Int {
    return ceil(cm / 2.54 * 1000).toInt()
}

private fun milsToCm(mils: Int): Double {
    return mils / 1000 * 2.54
}

class FarminOSPrinterDiscoverySession(private val context: FarminOSPrintService) : PrinterDiscoverySession() {
    val printersMap: MutableMap<PrinterId, PrinterWithSettingsAndInfo> = mutableMapOf()

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        // TODO: observe settings, add / remove / update printers
        listPrinters().forEach {
            printersMap[it.info.id] = it
            addPrinters(listOf(it.info))
        }
    }

    private fun listPrinters(): List<PrinterWithSettingsAndInfo> {
        val bluetoothManager = ContextCompat.getSystemService(context, BluetoothManager::class.java)
            ?: return listOf()
        val bluetoothAdapter = bluetoothManager.adapter
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return listOf()
        }
        val settings = runBlocking { context.settingsDataStore.data.first() }
        val printers = bluetoothAdapter.bondedDevices
            .filter { it.bluetoothClass.deviceClass == 1664 }  // 1664 is major 0x600 (IMAGING) + minor 0x80 (PRINTER)
            .sortedBy { if (it.address == settings.defaultPrinter) 0 else 1 }
            .mapNotNull {
                val printerSettings = settings.printersMap[it.address]
                if (printerSettings == null || !printerSettings.enabled) {
                    null
                } else {
                    val id = context.generatePrinterId(it.address)
                    PrinterWithSettingsAndInfo(
                        printer = Printer(address = it.address, name = it.name),
                        settings = printerSettings,
                        info = buildPrinterInfo(id, it.name, printerSettings)
                    )
                }
            }
        return printers
    }
    override fun onStopPrinterDiscovery() {}

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        //addPrinters(getPrinters())
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}

    override fun onDestroy() {}
}

private fun copyToTmpFile(cacheDir: File, fd: FileDescriptor): ParcelFileDescriptor {
    val outputFile = File.createTempFile(System.currentTimeMillis().toString(), null, cacheDir)
    val outputStream = FileOutputStream(outputFile)
    val inputStream = FileInputStream(fd)
    val buffer = ByteArray(8192)
    var length: Int
    while (inputStream.read(buffer).also { length = it } > 0) {
        outputStream.write(buffer, 0, length)
    }
    inputStream.close()
    outputStream.close()
    return ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY)
}

data class PrinterWithSettingsAndInfo(val printer: Printer, val settings: PrinterSettings, val info: PrinterInfo)

fun buildPrinterInfo(id: PrinterId, name: String, settings: PrinterSettings): PrinterInfo {
    val dpi = settings.dpi
    val width = settings.width.toDouble()
    val height = settings.height.toDouble()
    val marginMils = settings.marginMils
    val df = DecimalFormat("#.#")
    val mediaSizeLabel = "${df.format(width)}x${df.format(height)}cm"
    return PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
        .setCapabilities(
            PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(
                    PrintAttributes.MediaSize(
                        mediaSizeLabel,
                        mediaSizeLabel,
                        cmToMils(width),
                        cmToMils(height),
                    ),
                    true
                )
                .addResolution(
                    Resolution("${dpi}dpi", "${dpi}dpi", dpi, dpi),
                    true
                )
                .setColorModes(
                    PrintAttributes.COLOR_MODE_COLOR,
                    PrintAttributes.COLOR_MODE_COLOR
                )
                .setMinMargins(
                    Margins(marginMils, marginMils, marginMils, marginMils)
                )
                .build()
        ).build()
}

class FarminOSPrintService : PrintService() {
    private lateinit var session: FarminOSPrinterDiscoverySession

    override fun onCreate() {
        super.onCreate()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        session = FarminOSPrinterDiscoverySession(this)
        return session
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val printerId = printJob.info.printerId
        val printer = session.printersMap[printerId]
        val document = printJob.document.data

        if (printer != null && document != null) {
            // we copy the document in the main thread, otherwise you get: java.lang.IllegalAccessError
            val copy = copyToTmpFile(this.cacheDir, document.fileDescriptor)
            val thread = PrintJobThread(this, printer, printJob.info, copy)
            printJob.start()
            thread.start()
            thread.join()
            printJob.complete()
        } else {
            printJob.cancel()
        }

    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }
}