package com.waage.ui

// NACHHER
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues

@Composable
fun CalibrationDialog(
    uiState: com.waage.viewmodel.WaageUiState,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
    onTareChannel: (Char) -> Unit,
    onCalibrateChannel: (Char, Float, (Float) -> Unit) -> Unit,
    onSetFactorManual: (Char, Float) -> Unit        // ← NEU
) {
    LaunchedEffect(Unit) { onLoad() }

    var selectedChannel by remember { mutableStateOf('R') }
    var weightText      by remember { mutableStateOf("") }
    var phase           by remember { mutableStateOf(0) }  // 0=Tara, 1=Gewicht
    var busy            by remember { mutableStateOf(false) }
    var resultMsg       by remember { mutableStateOf("") }

    val channelLabels = mapOf('R' to "Hinten", 'M' to "Mitte", 'F' to "Vorne")
    val weightVal = weightText.replace(',', '.').toFloatOrNull()
    val weightOk  = weightVal != null && weightVal > 0f

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Kalibrierung", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Kanal-Auswahl
                Text("Kanal wählen:", color = Color.Gray, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf('R', 'M', 'F').forEach { ch ->
                        FilterChip(
                            selected = selectedChannel == ch,
                            onClick  = {
                                selectedChannel = ch
                                phase = 0
                                weightText = ""
                                resultMsg = ""
                            },
                            label = { Text(channelLabels[ch]!!) }
                        )
                    }
                }

                // Faktoren-Übersicht + manuelle Eingabe
                HorizontalDivider()

                var showManualInput  by remember { mutableStateOf(false) }
                var manualRearText   by remember { mutableStateOf("") }
                var manualMidText    by remember { mutableStateOf("") }
                var manualFrontText  by remember { mutableStateOf("") }

                listOf(
                    Triple('R', "Hinten", uiState.factorRear),
                    Triple('M', "Mitte",  uiState.factorMid),
                    Triple('F', "Vorne",  uiState.factorFront)
                ).forEach { (ch, label, factor) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            label,
                            fontWeight = if (ch == selectedChannel) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            if (factor > 0f) "%.4f".format(factor) else "—",
                            color = Color(0xFF4CAF50)
                        )
                    }
                }

                // Toggle manuelle Eingabe
                TextButton(
                    onClick = { showManualInput = !showManualInput },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showManualInput) "▲ Manuelle Eingabe schließen"
                        else                 "▼ Faktoren manuell setzen",
                        fontSize = 13.sp, color = Color.Gray
                    )
                }

                if (showManualInput) {
                    listOf(
                        Triple('R', "Hinten", manualRearText),
                        Triple('M', "Mitte",  manualMidText),
                        Triple('F', "Vorne",  manualFrontText)
                    ).forEach { (ch, label, text) ->
                        val v = text.replace(',', '.').toFloatOrNull()
                        val ok = v != null && v > 0f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value         = text,
                                onValueChange = { new ->
                                    val filtered = new.filter { c -> c.isDigit() || c == '.' || c == ',' }
                                    when (ch) {
                                        'R' -> manualRearText  = filtered
                                        'M' -> manualMidText   = filtered
                                        'F' -> manualFrontText = filtered
                                    }
                                },
                                label         = { Text(label) },
                                placeholder   = { Text("%.4f".format(
                                    when (ch) { 'R' -> uiState.factorRear; 'M' -> uiState.factorMid; else -> uiState.factorFront }
                                )) },
                                isError       = text.isNotEmpty() && !ok,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine    = true,
                                modifier      = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick  = { if (ok) onSetFactorManual(ch, v!!) },
                                enabled  = ok
                            ) {
                                Icon(
                                    Icons.Default.Check, "Übernehmen",
                                    tint = if (ok) Color(0xFF4CAF50) else Color.Gray
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Ablaufschritte
                Text(
                    "Kanal: ${channelLabels[selectedChannel]}",
                    fontWeight = FontWeight.SemiBold
                )
                when (phase) {
                    0 -> Text("Schritt 1: Waage entlasten, dann TARA drücken.")
                    1 -> {
                        Text("Schritt 2: Bekanntes Gewicht auf den Bereich '${channelLabels[selectedChannel]}' legen.")
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value         = weightText,
                            onValueChange = { weightText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label         = { Text("Bekanntes Gewicht [g]") },
                            isError       = weightText.isNotEmpty() && !weightOk,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            enabled       = !busy
                        )
                    }
                }

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Kalibriere…", color = Color.Gray, fontSize = 13.sp)
                    }
                }

                if (resultMsg.isNotEmpty()) {
                    Text(resultMsg, color = Color(0xFF4CAF50), fontSize = 13.sp)
                }

                if (!uiState.deviceConfigLoaded) {
                    TextButton(onClick = onLoad) { Text("Aktuelle Faktoren laden") }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Abbrechen
                IconButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Close, "Abbrechen",
                        tint = if (!busy) Color(0xFFF44336) else Color.Gray)
                }
                // Tara / Weiter / Kalibrieren
                when (phase) {
                    0 -> TextButton(
                        onClick  = { onTareChannel(selectedChannel); phase = 1 },
                        enabled  = !busy,
                        modifier = Modifier.weight(2f)
                    ) { Text("Tara") }

                    1 -> IconButton(
                        onClick  = {
                            busy = true
                            onCalibrateChannel(selectedChannel, weightVal!!) { newFactor ->
                                resultMsg = "✓ Faktor: %.4f".format(newFactor)
                                busy  = false
                                phase = 0
                                weightText = ""
                            }
                        },
                        enabled  = weightOk && !busy,
                        modifier = Modifier.weight(2f)
                    ) {
                        Icon(Icons.Default.Check, "Kalibrieren",
                            tint = if (weightOk && !busy) Color(0xFF4CAF50) else Color.Gray)
                    }
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
fun DeviceConfigDialog(
    uiState: com.waage.viewmodel.WaageUiState,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
    onSave: (publishRateHz: Int, avgSamples: Int, offlineBufferSeconds: Int, displayHz: Int) -> Unit,
    onReset: () -> Unit,
    onOpenCalibration: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

    var prateText by remember(uiState.devicePublishRateHz) {
        mutableStateOf(uiState.devicePublishRateHz.toString())
    }
    var avgText by remember(uiState.deviceAvgSamples) {
        mutableStateOf(uiState.deviceAvgSamples.toString())
    }
    var bufsecText by remember(uiState.deviceOfflineBufferSeconds) {
        mutableStateOf(uiState.deviceOfflineBufferSeconds.toString())
    }
    var dispHzText by remember(uiState.deviceDisplayHz) {
        mutableStateOf(uiState.deviceDisplayHz.toString())
    }

    var showResetConfirm by remember { mutableStateOf(false) }

    val prateVal = prateText.toIntOrNull()
    val avgVal = avgText.toIntOrNull()
    val bufsecVal = bufsecText.toIntOrNull()
    val dispHzVal = dispHzText.toIntOrNull()

    val prateOk = prateVal != null && prateVal in 1..20
    val avgOk = avgVal != null && avgVal in 1..4
    val bufsecOk = bufsecVal != null && bufsecVal in 10..180
    val dispHzOk = dispHzVal != null && dispHzVal in 1..10
    val allValid = prateOk && avgOk && bufsecOk && dispHzOk

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Zurücksetzen?") },
            text = { Text("Die Gerätekonfiguration wird auf Werkseinstellungen zurückgesetzt.") },
            confirmButton = {
                Button(
                    onClick = {
                        onReset()
                        showResetConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF9A9A),
                        contentColor = Color.Black
                    )
                ) {
                    Text("Zurücksetzen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gerätekonfiguration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Kalibrierfaktoren [-]", color = Color.Gray, fontSize = 13.sp)
                    Text(
                        text = if (uiState.deviceConfigLoaded) {
                            "R: %.2f  M: %.2f  F: %.2f".format(
                                uiState.factorRear, uiState.factorMid, uiState.factorFront
                            )
                        } else { "—" },
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    TextButton(
                        onClick = onOpenCalibration,
                        modifier = Modifier.padding(start = 0.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("Kalibrieren →", fontSize = 13.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Abtastrate [Hz]", color = Color.Gray, fontSize = 13.sp)
                    Text(
                        text = "20 (fest)",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                HorizontalDivider()

                if (!uiState.deviceConfigLoaded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Lade Gerätekonfiguration…", color = Color.Gray, fontSize = 13.sp)
                    }
                }

                OutlinedTextField(
                    value = prateText,
                    onValueChange = { prateText = it.filter { c -> c.isDigit() } },
                    label = { Text("Senderate [Hz]") },
                    supportingText = { Text("1 – 20 Hz") },
                    isError = prateText.isNotEmpty() && !prateOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.deviceConfigLoaded
                )

                OutlinedTextField(
                    value = avgText,
                    onValueChange = { avgText = it.filter { c -> c.isDigit() } },
                    label = { Text("Werte für Glättung [-]") },
                    supportingText = { Text("1 – 4 (bei 20 Hz Abtastrate)") },
                    isError = avgText.isNotEmpty() && !avgOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.deviceConfigLoaded
                )

                OutlinedTextField(
                    value = bufsecText,
                    onValueChange = { bufsecText = it.filter { c -> c.isDigit() } },
                    label = { Text("Offline-Puffer [s]") },
                    supportingText = { Text("10 – 180 s (20 Hz × 180 s = 3600 Samples max.)") },
                    isError = bufsecText.isNotEmpty() && !bufsecOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.deviceConfigLoaded
                )

                OutlinedTextField(
                    value = dispHzText,
                    onValueChange = { dispHzText = it.filter { c -> c.isDigit() } },
                    label = { Text("Display-Aktualisierung [Hz]") },
                    supportingText = { Text("1 – 10 Hz") },
                    isError = dispHzText.isNotEmpty() && !dispHzOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.deviceConfigLoaded
                )

                if (uiState.deviceConfigLoaded) {
                    Text(
                        "Pufferkapazität: ${uiState.deviceOfflineBufferCapacity} Samples" +
                                " (${bufsecVal ?: uiState.deviceOfflineBufferSeconds} s × 20 Hz)",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Abbrechen — rotes X
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Abbrechen",
                        tint = Color(0xFFF44336)
                    )
                }
                // Reset — roter Text
                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = uiState.deviceConfigLoaded,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "Reset",
                        maxLines = 1,
                        color = if (uiState.deviceConfigLoaded) Color(0xFFEF9A9A) else Color.Gray
                    )
                }
                // Speichern — grüner Haken
                IconButton(
                    onClick = {
                        onSave(prateVal!!, avgVal!!, bufsecVal!!, dispHzVal!!)
                        onDismiss()
                    },
                    enabled = uiState.deviceConfigLoaded && allValid,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Speichern",
                        tint = if (uiState.deviceConfigLoaded && allValid) Color(0xFF4CAF50) else Color.Gray
                    )
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
fun AlarmDialog(
    upperG: Float,
    lowerG: Float,
    onDismiss: () -> Unit,
    onSave: (upper: Float, lower: Float) -> Unit
) {
    var upperText by remember { mutableStateOf(if (upperG.isNaN()) "" else upperG.toString()) }
    var lowerText by remember { mutableStateOf(if (lowerG.isNaN()) "" else lowerG.toString()) }

    val upperVal = upperText.replace(',', '.').let { if (it.isBlank()) null else it.toFloatOrNull() }
    val lowerVal = lowerText.replace(',', '.').let { if (it.isBlank()) null else it.toFloatOrNull() }

    val upperOk = upperText.isBlank() || upperVal != null
    val lowerOk = lowerText.isBlank() || lowerVal != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schwellwert-Alarm", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = upperText,
                    onValueChange = { upperText = it },
                    label = { Text("Oberes Limit [g]") },
                    supportingText = { Text("Leer = kein Limit; negative Werte erlaubt") },
                    isError = !upperOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lowerText,
                    onValueChange = { lowerText = it },
                    label = { Text("Unteres Limit [g]") },
                    supportingText = { Text("Leer = kein Limit; negative Werte erlaubt") },
                    isError = !lowerOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Abbrechen
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Abbrechen",
                        tint = Color(0xFFF44336)
                    )
                }
                // Löschen
                IconButton(
                    onClick = {
                        onSave(Float.NaN, Float.NaN)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Löschen",
                        tint = Color(0xFFFF9800)
                    )
                }
                // Speichern
                IconButton(
                    onClick = {
                        onSave(
                            upperVal ?: Float.NaN,
                            lowerVal ?: Float.NaN
                        )
                        onDismiss()
                    },
                    enabled = upperOk && lowerOk,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Speichern",
                        tint = if (upperOk && lowerOk) Color(0xFF4CAF50) else Color.Gray
                    )
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
fun ExportDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onExport: (String) -> Unit
) {
    var fileName by remember { mutableStateOf(defaultName) }
    val nameOk = fileName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CSV exportieren", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("Messungsname") },
                isError = !nameOk,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Abbrechen
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Abbrechen",
                        tint = Color(0xFFF44336)
                    )
                }
                // Exportieren
                IconButton(
                    onClick = {
                        onExport(fileName)
                        onDismiss()
                    },
                    enabled = nameOk,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Exportieren",
                        tint = if (nameOk) Color(0xFF4CAF50) else Color.Gray
                    )
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
fun DevicePickerDialog(
    devices: List<BluetoothDevice>,
    onDismiss: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bluetooth-Gerät wählen", fontWeight = FontWeight.Bold) },
        text = {
            if (devices.isEmpty()) {
                Text("Keine gekoppelten Geräte gefunden.", color = Color.Gray)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(devices) { device ->
                        val name = try { device.name ?: "Unbekanntes Gerät" } catch (_: SecurityException) { "Unbekanntes Gerät" }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDeviceSelected(device)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(name, fontWeight = FontWeight.Medium)
                            Text(device.address, color = Color.Gray, fontSize = 12.sp)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}
