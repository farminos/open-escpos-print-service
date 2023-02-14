package print.farminos.com

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@SuppressLint("MissingPermission")
@Composable
fun BluetoothComposable(
    context: Activity
) {
    val bluetoothState by context.bluetoothState.collectAsState()
    val printersState by context.printersState.collectAsState()

    if (context.bluetoothAdapter != null) {
        if (bluetoothState) {
            context.bluetoothAdapter.bondedDevices.forEach {
                val added = printersState.any() { printer ->
                    it.address.equals(printer.address)
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column() {
                        Text(text = it.name)
                        Text(text = it.address, color = Color.Gray)
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
        return
    }
    Text(text = "bluetooth is not available on this device.")
}
