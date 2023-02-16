package print.farminos.com

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Build
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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.citizen.jpos.command.CPCLConst
import com.citizen.jpos.printer.CPCLPrinter
import com.citizen.port.android.BluetoothPort
import com.citizen.request.android.RequestHandler
import com.dantsu.escposprinter.EscPosPrinterCommands
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import kotlinx.coroutines.*
import java.io.*
import java.util.*
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

private fun pdfToBitmap(document: ParcelFileDescriptor, dpi: Int, w: Double, h: Double) = sequence<Bitmap> {
    val renderer = PdfRenderer(document)
    val pageCount = renderer.pageCount
    for (i in 0 until pageCount) {
        val width = cmToDots(w, dpi)
        val height = cmToDots(h, dpi)
        val page = renderer.openPage(i)
        val transform = Matrix()
        val ratio = width.toFloat() / page.width
        transform.postScale(ratio, ratio)
        Log.d("WOLOLO", "w=$w h=$h dpi=$dpi width=$width height=$height page.width=${page.width} page.height=${page.height}")
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
    private val printer: PrinterInfo,
    private val info: PrintJobInfo,
    private val document: ParcelFileDescriptor
) : Thread() {
    override fun run() {
        // TODO: !!
        val w = milsToCm(info.attributes.mediaSize!!.widthMils)
        val h = milsToCm(info.attributes.mediaSize!!.heightMils)
        val dpi = info.attributes.resolution!!.horizontalDpi
        val connection = BluetoothPrintersConnections.selectFirstPaired()
        val printerCommands = EscPosPrinterCommands(connection)
        printerCommands.connect()
        printerCommands.reset()
        val pages = pdfToBitmap(document, dpi, w, h)
        pages.forEach { page ->
            bitmapSlices(page, 128).forEach {
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(it))
            }
            printerCommands.cutPaper()
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
    private val printer: PrinterInfo,
    private val document: ParcelFileDescriptor
) : Thread() {
    private lateinit var bluetoothPort: BluetoothPort
    private lateinit var thread: Thread

    override fun run() {
        bluetoothPort = BluetoothPort.getInstance()
        thread = Thread(RequestHandler())

        try {
            Log.d("CITIZENPrintJobThread", printer.id.localId)
            bluetoothPort.connect(printer.id.localId)
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
            // TODO: hardcoded sizes
            val pages = pdfToBitmap(document, 203, 7.0, 9.0)
            pages.forEach {
                // TODO: labelHeight is hardcoded
                cpclPrinter.setForm(0, 1, 1, 900, 1)
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
    return mils / 1000 * 2.54;
}

class FarminOSPrinterDiscoverySession(private val context: FarminOSPrintService) :
    PrinterDiscoverySession() {
    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        addPrinters(context.printers)
    }

    override fun onStopPrinterDiscovery() {}

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        // TODO: this is very hardcoded
        val dpi = 203
        val width = 5.1
        val height = 8.0
        val marginMils = 0
        context.printers = printers.map {
            if (it.id == printerId) {
                PrinterInfo.Builder(it).setCapabilities(
                    PrinterCapabilitiesInfo.Builder(it.id)
                        .addMediaSize(
                            PrintAttributes.MediaSize("${width}x${height}cm", "${width}x${height}cm", cmToMils(width), cmToMils(height)),
                            true
                        )
                        .addResolution(Resolution("${dpi}dpi", "${dpi}dpi", dpi, dpi), true)
                        .setColorModes(
                            PrintAttributes.COLOR_MODE_COLOR,
                            PrintAttributes.COLOR_MODE_COLOR
                        )
                        .setMinMargins(Margins(marginMils, marginMils, marginMils, marginMils))
                        .build()
                ).build()
            } else {
                it
            }
        }
        addPrinters(context.printers)
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

class FarminOSPrintService : PrintService() {
    private lateinit var preferences: SharedPreferences
    lateinit var printers: List<PrinterInfo>

    override fun onCreate() {
        super.onCreate()

        preferences = this.getSharedPreferences("FarminOSPrintService", Context.MODE_PRIVATE)

        val preferencesPrinters = preferences.all

        printers = preferencesPrinters.map {
            PrinterInfo.Builder(
                this.generatePrinterId(it.key),
                it.value as String,
                PrinterInfo.STATUS_IDLE
            ).build()
        }
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return FarminOSPrinterDiscoverySession(this)
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val printer = printers.find { it.id == printJob.info.printerId }
        val document = printJob.document.data

        if (printer != null && document != null) {
            // we copy the document in the main thread, otherwise you get: java.lang.IllegalAccessError
            val copy = copyToTmpFile(this.cacheDir, document.fileDescriptor)
            // TODO
            //val thread = CITIZENPrintJobThread(this, printer, ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE))
            val thread = ESCPOSPrintJobThread(this, printer, printJob.info, copy)
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