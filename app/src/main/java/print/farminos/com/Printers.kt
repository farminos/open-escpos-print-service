package print.farminos.com

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrintersComposable(
    context: Activity
) {
    val state by context.printersState.collectAsState()

    Column() {
        state.forEach { printer: Printer ->
            PrinterComposable(context, printer)
        }
    }
}

data class Printer (val name: String, val address: String)

@Composable
fun PrinterComposable(context: Activity, printer: Printer) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Outlined.Settings,
            contentDescription = "",
            modifier = Modifier.size(48.dp)
        )
        Column() {
            Text(text = printer.name, fontSize = 24.sp, textAlign = TextAlign.Left)
            Text(text = printer.address, color = Color.Gray, fontSize = 18.sp,  textAlign = TextAlign.Left)
        }
        Button(onClick = {
            context.removeDevice(printer)
        }) {
            Text(text = "remove")
        }
    }
}