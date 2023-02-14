package print.farminos.com

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(10.dp)
            ) {
                Column {
                    Text(text = it.name)
                    Text(text = it.address, color = MaterialTheme.colorScheme.secondary)
                }
                Button(
                    onClick = {
                        if (added) {
                            context.removeDevice(Printer(it.name, it.address))
                        } else {
                            context.addDevice(Printer(it.name, it.address))
                        }
                    },
                ) {
                    Text(text = if (added) "remove" else "add")
                }
            }
        }
        return
    }

    Button(onClick = {
        context.enableBluetooth()
    }, modifier = Modifier.fillMaxWidth()) {
        Text(text = "enable bluetooth")
    }
}
