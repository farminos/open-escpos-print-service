package com.farminos.print

import android.Manifest
import android.bluetooth.BluetoothManager
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
import android.util.Log
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

internal class ESCPOSPrintJobThread(
    private val context: FarminOSPrintService,
    private val printer: PrinterWithSettingsAndInfo,
    private val info: PrintJobInfo,
    private val document: ParcelFileDescriptor
) : Thread() {
    override fun run() {
        val mediaSize = info.attributes.mediaSize
        val resolution = info.attributes.resolution
        if (mediaSize == null || resolution == null) {
            // TODO: report error
            return
        }
        val w = milsToCm(mediaSize.widthMils)
        val h = milsToCm(mediaSize.heightMils)
        val dpi = resolution.horizontalDpi
        val bluetoothManager: BluetoothManager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
        val bluetoothAdapter = bluetoothManager.adapter
        val device = bluetoothAdapter.getRemoteDevice(printer.printer.address)
        val connection = BluetoothConnection(device)
        val printerCommands = EscPosPrinterCommands(connection)
        printerCommands.connect()
        printerCommands.reset()
        val pages = pdfToBitmaps(document, dpi, w, h)
        pages.forEach { page ->
            bitmapSlices(page, 128).forEach {
                sleep(100) // TODO: Needed on MTP-2 printer
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(it))
            }
            if (printer.settings.cut) {
                // TODO: sleep ?
                printerCommands.cutPaper()
            }
        }
        this.document.close()
        // TODO
        val speed = 3 // cm/s
        sleep((h / speed * 1000).toLong())
        printerCommands.disconnect()
    }
}

internal class CITIZENPrintJobThread(
    private val context: FarminOSPrintService,
    private val printer: PrinterWithSettingsAndInfo,
    private val info: PrintJobInfo,
    private val document: ParcelFileDescriptor
) : Thread() {
    private lateinit var bluetoothPort: BluetoothPort
    private lateinit var thread: Thread

    override fun run() {
        bluetoothPort = BluetoothPort.getInstance()
        thread = Thread(RequestHandler())

        try {
            Log.d("CITIZENPrintJobThread", printer.printer.address)
            bluetoothPort.connect(printer.printer.address)
        } catch (exception: Exception) {
            Log.d("CITIZENPrintJobThread", "bluetooth error")
            ContextCompat.getMainExecutor(context).execute {
                Toast.makeText(context, "bluetooth error.", Toast.LENGTH_SHORT).show()
            }
            clean()
            return
        }

        thread.start()

        try {
            val cpclPrinter = CPCLPrinter()

            var status: Int = cpclPrinter.printerCheck()
            if (status != CPCLConst.CMP_SUCCESS) {
                Log.d("CITIZENPrintJobThread", "printer check failed.")
                ContextCompat.getMainExecutor(context).execute {
                    Toast.makeText(context, "printer check failed.", Toast.LENGTH_SHORT).show()
                }
                clean()
                return
            }

            status = cpclPrinter.status()
            if (status != CPCLConst.CMP_SUCCESS) {
                Log.d("CITIZENPrintJobThread", "printer status failed.")
                ContextCompat.getMainExecutor(context).execute {
                    Toast.makeText(context, "printer status failed.", Toast.LENGTH_SHORT).show()
                }
                clean()
                return
            }

            cpclPrinter.setMedia(CPCLConst.CMP_CPCL_LABEL)
            val mediaSize = info.attributes.mediaSize
            val resolution = info.attributes.resolution
            if (mediaSize == null || resolution == null) {
                clean()
                return
            }
            val w = milsToCm(mediaSize.widthMils)
            val h = milsToCm(mediaSize.heightMils)
            val dpi = resolution.horizontalDpi
            val pages = pdfToBitmaps(document, dpi, w, h)
            pages.forEach {
                cpclPrinter.setForm(0, 1, 1, (h * 100).toInt(), 1)
                cpclPrinter.printBitmap(it, 0, 0)
                cpclPrinter.printForm()
            }

        } catch (exception: Exception) {
            Log.d("CITIZENPrintJobThread", "error.", exception)
            clean()
        }
//        clean()
    }

    private fun clean() {
        if (this.bluetoothPort.isConnected) {
            this.bluetoothPort.disconnect()
        }
        this.document.close()
        if (thread.isAlive) {
            thread.interrupt()
        }
    }
}

private fun cmToMils(cm: Double): Int {
    return ceil(cm / 2.54 * 1000).toInt()
}

private fun milsToCm(mils: Int): Double {
    return mils / 1000 * 2.54
}

class FarminOSPrinterDiscoverySession(private val context: FarminOSPrintService) : PrinterDiscoverySession() {
    val printersMap: MutableMap<PrinterId, PrinterWithSettingsAndInfo> = mutableMapOf()

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        // TODO: observe settings
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
            val thread = if (printer.settings.driver == Driver.ESC_POS) {
                ESCPOSPrintJobThread(this, printer, printJob.info, copy)
            } else {
                CITIZENPrintJobThread(this, printer, printJob.info, copy)
            }
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