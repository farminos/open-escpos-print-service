package print.farminos.com

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType

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
        Box(
            ) {
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
fun LabelledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label)
        TextField(
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            value = value,
            onValueChange = {
                onValueChange(it)
            },
        )
    }
}

@SuppressLint("MissingPermission")
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
            Column {
                LabelledSwitch(
                    label = "Enabled",
                    checked = enabled,
                    onCheckedChange = {
                        if (it) {
                            context.addDevice(Printer(printer.name, printer.address))
                        } else {
                            context.removeDevice(Printer(printer.name, printer.address))
                        }
                    },
                )
                LabelledSwitch(
                    label = "Default printer",
                    checked = false,
                    onCheckedChange = {
                        Log.d("Settings", "Change default $it")
                    },
                )
                MenuSelect(
                    options = arrayOf(
                        Option(value = "escpos", label = "ESC / POS"),
                        Option(value = "cpcl", label = "Citizen CPCL"),
                    ),
                    selectedValue="escpos",
                    onSelect = {
                        Log.d("Settings", "Selected driver $it")
                    }
                )
                LabelledTextField(
                    label = "Paper width (inches)",
                    value = "",
                    onValueChange = {
                    },
                )
                LabelledTextField(
                    label = "Paper height (inches)",
                    value = "",
                    onValueChange = {
                    },
                )
                LabelledSwitch(
                    label = "Cut after each page",
                    checked = false,
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
