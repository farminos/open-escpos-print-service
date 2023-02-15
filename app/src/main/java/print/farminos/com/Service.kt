package print.farminos.com

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.citizen.jpos.command.CPCLConst
import com.citizen.jpos.printer.CPCLPrinter
import com.citizen.port.android.BluetoothPort
import com.citizen.request.android.RequestHandler
import com.github.anastaciocintra.escpos.EscPos
import com.github.anastaciocintra.escpos.image.BitImageWrapper
import com.github.anastaciocintra.escpos.image.BitonalOrderedDither
import com.github.anastaciocintra.escpos.image.EscPosImage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream


private fun pdfToBitmap(document: ParcelFileDescriptor): ArrayList<Bitmap> {
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



fun printBitmap(bitmap: Bitmap, printerOutputStream: OutputStream) {
    val algorithm = BitonalOrderedDither()
    val escposImage = EscPosImage(BitmapImage(bitmap), algorithm)
    val escpos = EscPos(printerOutputStream)
    val wrapper = BitImageWrapper()
    escpos.write(wrapper, escposImage)
}

internal class ESCPOSPrintJobThread(
    private val context: FarminOSPrintService,
    private val printer: PrinterInfo,
    private val document: ParcelFileDescriptor
) : Thread() {
    private lateinit var btSocket: BluetoothSocket

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun run() {
        val pages = pdfToBitmap(document)
        val address = printer.id.localId
        val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
        val bluetoothAdapter = bluetoothManager.adapter
        val device = bluetoothAdapter.getRemoteDevice(address)
        val uuids = device.uuids
        Log.d("WOLOLO", "$address $device $uuids")
        val uuid = uuids[0]
        this.btSocket = device.createInsecureRfcommSocketToServiceRecord(uuid.uuid);
        this.btSocket.connect()
        val stream = this.btSocket.outputStream
        pages.forEach{
            printBitmap(it, stream)
        }
        clean()
    }

    private fun clean() {
        if (this.btSocket.isConnected) {
            this.btSocket.close()
        }
        this.document.close()
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
        val pages = pdfToBitmap(document)
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


class FarminOSPrinterDiscoverySession(private val context: FarminOSPrintService) :
    PrinterDiscoverySession() {
    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        addPrinters(context.printers)
    }

    override fun onStopPrinterDiscovery() {}

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        context.printers = printers.map {
            if (it.id == printerId) {
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

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onPrintJobQueued(printJob: PrintJob) {
        val printer = printers.find { it.id == printJob.info.printerId }
        val document = printJob.document.data

        if (printer != null && document != null) {
            // we copy the document in the main thread, otherwise you get: java.lang.IllegalAccessError
            val outputFile = File.createTempFile(System.currentTimeMillis().toString(), null, this.cacheDir)
            val outputStream = FileOutputStream(outputFile)

            val inputStream = FileInputStream(document.fileDescriptor)

            val buffer = ByteArray(8192)

            var length: Int

            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            inputStream.close()
            outputStream.close()

            val thread = CITIZENPrintJobThread(this, printer, ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE))
            thread.start()
        } else {
            printJob.cancel()
        }

    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }
}