package com.farminos.print

import android.app.Application
import com.farminos.print.driver.PrinterDriver

class OpenEscPosPrintServiceApplication : Application() {
    val connectedDrivers: MutableMap<String, PrinterDriver> = mutableMapOf()
}
