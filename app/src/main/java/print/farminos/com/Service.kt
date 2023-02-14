package print.farminos.com

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.Resolution
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.citizen.jpos.command.CPCLConst
import com.citizen.jpos.printer.CPCLPrinter
import com.citizen.port.android.BluetoothPort
import com.citizen.request.android.RequestHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private fun pdfToBitmap(context: Context, document: ParcelFileDescriptor): ArrayList<Bitmap>? {
    val bitmaps: ArrayList<Bitmap> = ArrayList()
    try {
        val renderer =
            PdfRenderer(document)
        var bitmap: Bitmap
        val pageCount = renderer.pageCount
        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            // the cmp-30ii has 203 dpi (dots per inch) / 2.54 (inch to cm) results dpc (dots per cm) * how many cm we want (based on label size). hardcoded.
            val width: Double = 203 / 2.54 * 7
            val height: Double = 203 / 2.54 * 9
            bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val allpixels = IntArray(bitmap.height * bitmap.width)

            bitmap.getPixels(
                allpixels,
                0,
                bitmap.getWidth(),
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight()
            )

            for (i in allpixels.indices) {
                if (allpixels[i] == Color.TRANSPARENT) {
                    allpixels[i] = Color.WHITE
                }
            }

            bitmap.setPixels(
                allpixels,
                0,
                bitmap.getWidth(),
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight()
            )
            bitmaps.add(bitmap)
            // close the page
            page.close()
        }

        // close the renderer
        renderer.close()
    } catch (ex: java.lang.Exception) {
        ex.printStackTrace()
    }
    return bitmaps
}


internal class CITIZENPrintJobThread(
    private val context: FarminOSPrintService,
    private val printer: PrinterInfo,
    private val document: ParcelFileDescriptor
) : Thread() {
    private lateinit var bluetoothPort: BluetoothPort;
    private lateinit var thread: Thread;

    override fun run() {
        val pages = pdfToBitmap(context, document)

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
            pages?.forEach {
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
        if (thread != null && thread.isAlive) {
            thread.interrupt()
        }
    }
}


class FarminOSPrinterDiscoverySession(private val context: FarminOSPrintService) :
    PrinterDiscoverySession() {
    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        addPrinters(context.printers)
    }

    override fun onStopPrinterDiscovery() {}

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        context.printers = printers.map {
            if (it.id.equals(printerId)) {
                // TODO: this is very hardcoded
                PrinterInfo.Builder(it).setCapabilities(
                    PrinterCapabilitiesInfo.Builder(it.id)
                        .addMediaSize(
                            PrintAttributes.MediaSize("70x90mm", "70x90mm", 2750, 3540),
                            true
                        )
                        .addResolution(Resolution("300x300", "300x300", 200, 200), true)
                        .setColorModes(
                            PrintAttributes.COLOR_MODE_COLOR,
                            PrintAttributes.COLOR_MODE_COLOR
                        )
                        .setMinMargins(Margins(10, 10, 10, 10))
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

class FarminOSPrintService : PrintService() {
    private lateinit var preferences: SharedPreferences;
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
        val printer = printers.find { it -> it.id.equals(printJob.info.printerId) }
        val document = printJob.document.data

        if (printer != null && document != null) {
            // we copy the document in the main thread, otherwise you get: java.lang.IllegalAccessError
            val outputFile = File.createTempFile(System.currentTimeMillis().toString(), null, this.cacheDir);
            val outputStream = FileOutputStream(outputFile)

            val inputStream = FileInputStream(document.fileDescriptor)

            val buffer = ByteArray(8192)

            var length: Int

            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            inputStream.close()
            outputStream.close()

            val document = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)

            val thread = CITIZENPrintJobThread(this, printer, document)
            thread.start()
        } else {
            printJob.cancel()
        }

    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }
}