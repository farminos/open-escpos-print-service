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
import com.farminos.print.ui.theme.OpenESCPOSPrintServiceTheme


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
fun <T> LabelledTextField(
    label: String,
    value: String,
    onValueChange: (T) -> Unit,
    transform: (String) -> T?,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    var valueState by remember { mutableStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label)
        TextField(
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            value = valueState,
            onValueChange = {
                valueState = it
                val transformed = transform(it)
                if (transformed != null) {
                    onValueChange(transformed)
                }
            },
            isError = transform(valueState) == null,
        )
    }
}

@Composable
fun PrinterCard(
    context: PrintActivity,
    uuid: String,
    settings: PrinterSettings,
    defaultPrinterAddress: String,
) {
    ExpandableCard(
        header = {
            Column {
                Text(
                    text = settings.name,
                )
                Text(
                    text = settings.address,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        content = {
            Column {
                LabelledSwitch(
                    label = "Enabled",
                    checked = settings.enabled,
                    onCheckedChange = {enabled ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setEnabled(enabled)
                        }
                        if (!enabled && uuid === defaultPrinterAddress) {
                            // disabled printer can't be set as default
                            context.updateDefaultPrinter("")
                        }
                    },
                )
                LabelledSwitch(
                    label = "Default printer",
                    checked = uuid == defaultPrinterAddress,
                    onCheckedChange = {isDefault ->
                        if (isDefault) {
                            // disabled printer can't be set as default
                            context.updatePrinterSetting(uuid = uuid) {
                                it.setEnabled(true)
                            }
                        }
                        context.updateDefaultPrinter(address = if (isDefault) uuid else "")
                    },
                )
                if (settings.`interface` == Interface.TCP_IP) {
                    LabelledTextField(
                        label = "Name",
                        value = settings.name,
                        transform = { name ->
                            name
                        },
                        onValueChange = { name ->
                            context.updatePrinterSetting(uuid = uuid) {
                                it.setName(name)
                            }
                        },
                        keyboardType = KeyboardType.Text,
                    )
                    LabelledTextField(
                        label = "Address and port",
                        value = settings.address,
                        transform = { address ->
                            address
                        },
                        onValueChange = { address ->
                            context.updatePrinterSetting(uuid = uuid) {
                                it.setAddress(address)
                            }
                        },
                        keyboardType = KeyboardType.Text,
                    )
                }
                Button(
                    onClick = {
                        context.deletePrinterSetting(uuid)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.elevatedButtonColors(),
                ) {
                    Text(text = "Delete this printer")
                }
                MenuSelect(
                    options = arrayOf(
                        Option(value = Driver.ESC_POS_VALUE, label = "ESC / POS"),
                        Option(value = Driver.CPCL_VALUE, label = "Citizen CPCL"),
                    ),
                    selectedValue = settings.driverValue,
                    onSelect = {value ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setDriverValue(value)
                        }
                    }
                )
                LabelledTextField(
                    label = "DPI",
                    value = settings.dpi.toString(),
                    transform = {dpi ->
                        dpi.toIntOrNull()
                    },
                    onValueChange = {dpi ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setDpi(dpi)
                        }
                    },
                )
                LabelledTextField(
                    label = "Paper width (cm)",
                    value = settings.width.toString(),
                    transform = {value ->
                        value.toFloatOrNull()
                    },
                    onValueChange = {width ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setWidth(width)
                        }
                    },
                )
                LabelledTextField(
                    label = "Paper height (cm)",
                    value = settings.height.toString(),
                    transform = {value ->
                        value.toFloatOrNull()
                    },
                    onValueChange = {height ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setHeight(height)
                        }
                    },
                )
                LabelledTextField(
                    label = "Left margin (cm)",
                    value = settings.marginLeft.toString(),
                    transform = {cm ->
                        cm.toFloatOrNull()
                    },
                    onValueChange = {cm ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setMarginLeft(cm)
                        }
                    },
                )
                LabelledTextField(
                    label = "Top margin (cm)",
                    value = settings.marginTop.toString(),
                    transform = {cm ->
                        cm.toFloatOrNull()
                    },
                    onValueChange = {cm ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setMarginTop(cm)
                        }
                    },
                )
                LabelledTextField(
                    label = "Right margin (cm)",
                    value = settings.marginRight.toString(),
                    transform = {cm ->
                        cm.toFloatOrNull()
                    },
                    onValueChange = {cm ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setMarginRight(cm)
                        }
                    },
                )
                LabelledTextField(
                    label = "Bottom margin (cm)",
                    value = settings.marginBottom.toString(),
                    transform = {cm ->
                        cm.toFloatOrNull()
                    },
                    onValueChange = {cm ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setMarginBottom(cm)
                        }
                    },
                )
                LabelledTextField(
                    label = "Speed limit (cm/s)",
                    value = settings.speedLimit.toString(),
                    transform = {speedLimit ->
                        speedLimit.toFloatOrNull()
                    },
                    onValueChange = {speedLimit ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setSpeedLimit(speedLimit)
                        }
                    },
                )
                LabelledTextField(
                    label = "Cut delay (s)",
                    value = settings.cutDelay.toString(),
                    transform = {cutDelay ->
                        cutDelay.toFloatOrNull()
                    },
                    onValueChange = {cutDelay ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setCutDelay(cutDelay)
                        }
                    },
                )
                LabelledSwitch(
                    label = "Cut after each page",
                    checked = settings.cut,
                    onCheckedChange = {cut ->
                        context.updatePrinterSetting(uuid = uuid) {
                            it.setCut(cut)
                        }
                    },
                )
                Button(
                    onClick = {
                        context.printTestPage(uuid)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.elevatedButtonColors(),
                ) {
                    Text(text = "Print test page")
                }
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

    OpenESCPOSPrintServiceTheme {
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
                Text(
                    "Bluetooth printers",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                )
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
                    settings.printersMap.filter { it.value.`interface` == Interface.BLUETOOTH }
                        .forEach { (uuid, printerSettings) ->
                            PrinterCard(
                                context = context,
                                uuid = uuid,
                                settings = printerSettings,
                                defaultPrinterAddress = settings.defaultPrinter,
                            )
                        }
                }
                Text(
                    "Network printers",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                )
                settings.printersMap.filter { it.value.`interface` == Interface.TCP_IP }
                    .forEach { (uuid, printerSettings) ->
                        PrinterCard(
                            context = context,
                            uuid = uuid,
                            settings = printerSettings,
                            defaultPrinterAddress = settings.defaultPrinter,
                        )
                    }
                Button(
                    onClick = {
                        context.addPrinterSetting()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Add a network printer")
                }
                LabelledSwitch(
                    label = "Keep default printer connected",
                    checked = settings.defaultPrinterConnection,
                    onCheckedChange = { checked -> context.updateDefaultPrinterConnectionSetting(checked) }
                )
            }
        }
    }
}

val DEFAULT_PRINTER_SETTINGS: PrinterSettings = PrinterSettings.newBuilder()
    .setEnabled(false)
    .setDriver(Driver.ESC_POS)
    .setDpi(203)
    .setWidth(5.0F)
    .setHeight(8.0F)
    .setMarginLeft(0.0F)
    .setMarginTop(0.0F)
    .setMarginRight(0.0F)
    .setMarginBottom(0.0F)
    .setCut(true)
    .setSpeedLimit(2.0F)
    .setCutDelay(0.0F)
    .build()