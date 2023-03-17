package com.farminos.print

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.print.PrintAttributes
import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.Resolution
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import java.text.DecimalFormat

class FarminOSPrinterDiscoverySession(private val context: FarminOSPrintService) : PrinterDiscoverySession() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    private suspend fun settingsObserver() {
        context.settingsDataStore.data.map { settings ->
            val oldIds = printers.map { it.id }
            val newPrinters = listPrinters(settings)
            val newPrinterIds = newPrinters.map { it.info.id }
            // remove no longer present printers
            oldIds.forEach {
                if (!newPrinterIds.contains(it)) {
                    context.printersMap.remove(it)
                    removePrinters(listOf(it))
                }
            }
            // add or update printers
            newPrinters.forEach {
                context.printersMap[it.info.id] = it
                addPrinters(listOf(it.info))
            }
        }.collect()
    }

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        job = scope.launch {
            settingsObserver()
        }
    }

    private fun listPrinters(settings: Settings): List<PrinterWithSettingsAndInfo> {
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
        val printers = bluetoothAdapter.bondedDevices
            .filter { it.bluetoothClass.deviceClass == 1664 }  // 1664 is major 0x600 (IMAGING) + minor 0x80 (PRINTER)
            .sortedBy { if (it.address == settings.defaultPrinter) 0 else 1 }
            .mapNotNull {
                val printerSettings = settings.printersMap[it.address]
                if (printerSettings == null || !printerSettings.enabled || printerSettings.dpi <= 0) {
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
    override fun onStopPrinterDiscovery() {
        job?.cancel("Printer discovery stopped")
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {}

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}

    override fun onDestroy() {}
}

data class PrinterWithSettingsAndInfo(val printer: Printer, val settings: PrinterSettings, val info: PrinterInfo)

fun buildPrinterInfo(id: PrinterId, name: String, settings: PrinterSettings): PrinterInfo {
    val dpi = settings.dpi
    val width = settings.width
    val height = settings.height
    val marginCm = settings.marginCm
    val marginMils = cmToMils(marginCm)
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
    val printersMap: MutableMap<PrinterId, PrinterWithSettingsAndInfo> = mutableMapOf()
    private lateinit var session: FarminOSPrinterDiscoverySession

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        session = FarminOSPrinterDiscoverySession(this)
        return session
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        // TODO: actual queue
        printJob.start()
        try{
            printDocument(printJob)
            printJob.complete()
        } catch (exception: Exception) {
            printJob.fail(exception.message)
        }
    }

    private fun printDocument(printJob: PrintJob) {
        val printerId = printJob.info.printerId
        val printer = printersMap[printerId]
        val document = printJob.document.data
        if (printer == null) {
            throw java.lang.Exception("No printer found")
        }
        if (document == null) {
            throw java.lang.Exception("No document found")
        }
        // we copy the document in the main thread, otherwise you get: java.lang.IllegalAccessError
        val copy = copyToTmpFile(this.cacheDir, document.fileDescriptor)
        val mediaSize = printJob.info.attributes.mediaSize
        val resolution = printJob.info.attributes.resolution
        if (mediaSize == null || resolution == null) {
            throw java.lang.Exception("No media size or resolution in print job info")
        }
        val driverClass = when (printer.settings.driver) {
            Driver.ESC_POS -> ::EscPosDriver
            Driver.CPCL -> ::CpclDriver
            else -> throw java.lang.Exception("Unrecognized driver in settings")
        }
        val instance = driverClass(this, printer.printer.address, printer.settings)
        instance.printDocument(copy)
        // TODO: move this somewhere else
        instance.disconnect()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        // TODO: remove from queue or cancel if running
        printJob.cancel()
    }
}