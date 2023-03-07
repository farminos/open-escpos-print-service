package print.farminos.com

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class Option(val value: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSelect(
    options: Array<Option>,
    selectedValue: String,
    onSelect: (value: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.value == selectedValue }

    return Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Driver")
        Box {
            TextField(
                value = selectedOption?.label ?: "",
                onValueChange = {
                    // noop
                },
                readOnly = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "expand driver select menu"
                    )
                }
            )
            // This box is rendered on top of the TextField above and catches the clicks as we
            // cannot have clickable TextFields that are not disabled
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        onClick = {
                            expanded = true
                        }
                    )
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {
                options.forEach {
                    DropdownMenuItem(
                        text = {
                            Text(it.label)
                        },
                        onClick = {
                            onSelect(it.value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
@Composable
fun LabelledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelledFloatField(
    label: String,
    value: Float,
    onValueChange: (Float?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label)
        TextField(
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            value = value.toString(),
            onValueChange = {
                onValueChange(it.toFloatOrNull())
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelledIntField(
    label: String,
    value: Int,
    onValueChange: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label)
        TextField(
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            value = value.toString(),
            onValueChange = {
                onValueChange(it.toIntOrNull())
            },
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun PrinterCard(
    context: Activity,
    printer: BluetoothDevice,
    settings: PrinterSettings,
    defaultPrinterAddress: String,
) {
    ExpandableCard(
        header = {
            Column {
                Text(text = printer.name)
                Text(text = printer.address, color = MaterialTheme.colorScheme.secondary)
                //Text(text = printer.bluetoothClass.deviceClass.toString())
            }
        },
        content = {
            Column {
                LabelledSwitch(
                    label = "Enabled",
                    checked = settings.enabled,
                    onCheckedChange = {
                        if (it) {
                            //context.addDevice(Printer(printer.name, printer.address))
                        } else {
                            //context.removeDevice(Printer(printer.name, printer.address))
                        }
                    },
                )
                LabelledSwitch(
                    label = "Default printer",
                    checked = printer.address == defaultPrinterAddress,
                    onCheckedChange = {
                        Log.d("Settings", "Change default $it")
                    },
                )
                MenuSelect(
                    options = arrayOf(
                        Option(value = "escpos", label = "ESC / POS"),
                        Option(value = "cpcl", label = "Citizen CPCL"),
                    ),
                    selectedValue = settings.driver,
                    onSelect = {
                        Log.d("Settings", "Selected driver $it")
                    }
                )
                LabelledIntField(
                    label = "DPI",
                    value = settings.dpi,
                    onValueChange = {
                    },
                )
                LabelledFloatField(
                    label = "Paper width (inches)",
                    value = settings.width,
                    onValueChange = {
                    },
                )
                LabelledFloatField(
                    label = "Paper height (inches)",
                    value = settings.height,
                    onValueChange = {
                    },
                )
                LabelledIntField(
                    label = "Margins (mils)",
                    value = settings.marginMils,
                    onValueChange = {
                    },
                )
                LabelledSwitch(
                    label = "Cut after each page",
                    checked = settings.cut,
                    onCheckedChange = {
                        Log.d("Settings", "Change cut $it")
                    },
                )
            }
        }
    )
}
@SuppressLint("MissingPermission")
@Composable
fun BluetoothComposable(
    context: Activity
) {
    val preferences = context.getPreferences(Context.MODE_PRIVATE)
    val defaultPrinterAddress = preferences.getString("defaultPrinterAddress", "")
    val printers: Map<String, PrinterSettings> = Json.decodeFromString(
        preferences.getString("printers", "{}") ?: "{}"
    )

    val bluetoothState by context.bluetoothState.collectAsState()


    if (bluetoothState) {
        context.bluetoothAdapter.bondedDevices
            .filter { it.bluetoothClass.deviceClass == 1664 }  // 1664 is major 0x600 (IMAGING) + minor 0x80 (PRINTER)
            .forEach {
            val settings = printers[it.address] ?: DEFAULT_PRINTER_SETTINGS

            PrinterCard(
                context = context,
                printer = it,
                settings = settings,
                defaultPrinterAddress = defaultPrinterAddress ?: "",
            )
        }
    }

    Button(onClick = {
        context.enableBluetooth()
    }, modifier = Modifier.fillMaxWidth()) {
        Text(text = "enable bluetooth")
    }
}