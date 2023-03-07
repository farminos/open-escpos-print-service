package print.farminos.com

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun PrinterCard(
    context: Activity,
    printer: BluetoothDevice,
    enabled: Boolean,
) {
    ExpandableCard(
        header = {
            Column {
                Text(text = printer.name)
                Text(text = printer.address, color = MaterialTheme.colorScheme.secondary)
            }
        },
        content = {
            Column() {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Enabled")
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            if (it) {
                                context.addDevice(Printer(printer.name, printer.address))
                            } else {
                                context.removeDevice(Printer(printer.name, printer.address))
                            }
                        }
                    )
                }
                Text(text = "wololo")
            }
        }
    )
}
@SuppressLint("MissingPermission")
@Composable
fun BluetoothComposable(
    context: Activity
) {
    val bluetoothState by context.bluetoothState.collectAsState()
    val printersState by context.printersState.collectAsState()


    if (bluetoothState) {
        context.bluetoothAdapter.bondedDevices.forEach {
            val added = printersState.any { printer ->
                it.address.equals(printer.address)
            }

            PrinterCard(
                context = context,
                printer = it,
                enabled = added,
            )
        }
    }

    Button(onClick = {
        context.enableBluetooth()
    }, modifier = Modifier.fillMaxWidth()) {
        Text(text = "enable bluetooth")
    }
}
