package com.farminos.print

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.farminos.print.ui.theme.FarminOSCITIZENPrintServiceTheme


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandableCard(
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = 300,
                    easing = LinearOutSlowInEasing,
                )
            ),
        onClick = {
            open = !open
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                header()
                IconButton(
                    modifier = Modifier.rotate(rotation),
                    onClick = {
                        open = ! open
                    }
                ){
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "drop down arrow"
                    )
                }
            }
            if (open) {
                content()
            }
        }
    }
}
data class Option(val value: Int, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSelect(
    options: Array<Option>,
    selectedValue: Int,
    onSelect: (value: Int) -> Unit,
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

@Composable
fun PrinterCard(
    context: PrintActivity,
    printer: Printer,
    settings: PrinterSettings,
    defaultPrinterAddress: String,
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
                    checked = settings.enabled,
                    onCheckedChange = {enabled ->
                        context.updatePrinterSetting(address = printer.address) {
                            it.setEnabled(enabled)
                        }
                        if (!enabled && printer.address === defaultPrinterAddress) {
                            // disabled printer can't be set as default
                            context.updateDefaultPrinter("")
                        }
                    },
                )
                LabelledSwitch(
                    label = "Default printer",
                    checked = printer.address == defaultPrinterAddress,
                    onCheckedChange = {isDefault ->
                        if (isDefault) {
                            // disabled printer can't be set as default
                            context.updatePrinterSetting(address = printer.address) {
                                it.setEnabled(true)
                            }
                        }
                        context.updateDefaultPrinter(address = if (isDefault) printer.address else "")
                    },
                )
                MenuSelect(
                    options = arrayOf(
                        Option(value = Driver.ESC_POS_VALUE, label = "ESC / POS"),
                        Option(value = Driver.CPCL_VALUE, label = "Citizen CPCL"),
                    ),
                    selectedValue = settings.driverValue,
                    onSelect = {value ->
                        context.updatePrinterSetting(address = printer.address) {
                            it.setDriverValue(value)
                        }
                    }
                )
                LabelledIntField(
                    label = "DPI",
                    value = settings.dpi,
                    onValueChange = {dpi ->
                        context.updatePrinterSetting(address = printer.address) {
                            it.setDpi(dpi ?: 203)
                        }
                    },
                )
                LabelledFloatField(
                    label = "Paper width (inches)",
                    value = settings.width,
                    onValueChange = {width ->
                        context.updatePrinterSetting(address = printer.address) {
                            it.setWidth(width ?: 5.1f)
                        }
                    },
                )
                LabelledFloatField(
                    label = "Paper height (inches)",
                    value = settings.height,
                    onValueChange = {height ->
                        context.updatePrinterSetting(address = printer.address) {
                            it.setHeight(height ?: 8.0f)
                        }
                    },
                )
                LabelledIntField(
                    label = "Margins (mils)",
                    value = settings.marginMils,
                    onValueChange = {mils ->
                        context.updatePrinterSetting(address = printer.address) {
                            it.setMarginMils(mils ?: 0)
                        }
                    },
                )
                LabelledSwitch(
                    label = "Cut after each page",
                    checked = settings.cut,
                    onCheckedChange = {cut ->
                        context.updatePrinterSetting(address = printer.address) {
                            it.setCut(cut)
                        }
                    },
                )
            }
        }
    )
}
@Composable
fun SettingsScreen(
    context: PrintActivity,
) {
    val settings: Settings by context.settingsDataStore.data.collectAsState(Settings.getDefaultInstance())
    val bluetoothAllowed by context.bluetoothAllowed.collectAsState()
    val bluetoothEnabled by context.bluetoothEnabled.collectAsState()
    val printers by context.printers.collectAsState()

    FarminOSCITIZENPrintServiceTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.verticalScroll(
                    rememberScrollState(),
                )
            ) {
                if (!bluetoothAllowed) {
                    Button(
                        onClick = {
                            context.requestBluetoothPermissions()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Request bluetooth permissions")
                    }

                } else if (!bluetoothEnabled) {
                    Button(
                        onClick = {
                            context.enableBluetooth()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Enable bluetooth")
                    }
                } else {
                    printers
                        .forEach {
                            val printerSettings = settings.printersMap[it.address] ?: PrinterSettings.getDefaultInstance()
                            PrinterCard(
                                context = context,
                                printer = it,
                                settings = printerSettings,
                                defaultPrinterAddress = settings.defaultPrinter,
                            )
                        }
                }
            }
        }
    }
}