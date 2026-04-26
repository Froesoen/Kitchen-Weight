package com.waage.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CalibrationDialog(
    calibrationFactor: Float,
    onDismiss: () -> Unit,
    onTare: () -> Unit,
    onCalibrate: (Float, (Float) -> Unit) -> Unit  // Original-Signatur behalten
) {
    var inputText by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) }
    var resultFactor by remember { mutableStateOf(calibrationFactor) }
    var isCalibrating by remember { mutableStateOf(false) }
    var oldFactor by remember { mutableStateOf(calibrationFactor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kalibrierung", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    0 -> {
                        Text("Aktueller Kalibrierfaktor: ${"%.2f".format(calibrationFactor)}")
                        HorizontalDivider()
                        Text(
                            "1. Waage leer\n2. Tara\n3. Gewicht eingeben\n4. Gewicht auflegen\n5. Kalibrieren",
                            fontSize = 13.sp, color = Color.Gray
                        )
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Kalibriergewicht (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    1 -> {
                        Text("Kalibrierung läuft...", fontWeight = FontWeight.Medium)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    2 -> {
                        Text("Kalibrierung erfolgreich!", fontWeight = FontWeight.Bold)
                        Text("Neuer Faktor: ${"%.4f".format(resultFactor)}", color = Color.Gray)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                0 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onTare) { Text("Tara") }
                        Button(
                            onClick = {
                                inputText.toFloatOrNull()?.let { w ->
                                    if (w > 0f) {
                                        oldFactor = calibrationFactor
                                        step = 1
                                        isCalibrating = true
                                        onCalibrate(w) { newFactor ->
                                            resultFactor = newFactor
                                            step = 2
                                            isCalibrating = false
                                        }
                                    }
                                }
                            },
                            enabled = inputText.toFloatOrNull()?.let { it > 0f } == true
                        ) { Text("Kalibrieren") }
                    }
                }
                2 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = 0; inputText = "" }) { Text("Wiederholen") }
                        TextButton(onClick = onDismiss) { Text("OK") }
                    }
                }
            }
        },
        dismissButton = { if (step == 0) TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
fun AlarmDialog(
    upperG: Float,
    lowerG: Float,
    onDismiss: () -> Unit,
    onSave: (upper: Float, lower: Float) -> Unit
) {
    var upperText by remember { mutableStateOf(if (upperG > 0) "%.1f".format(upperG) else "") }
    var lowerText by remember { mutableStateOf(if (lowerG > 0) "%.1f".format(lowerG) else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schwellwert-Alarm", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Leer lassen = deaktiviert", color = Color.Gray, fontSize = 12.sp)

                OutlinedTextField(
                    value = upperText,
                    onValueChange = { upperText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Oberer Grenzwert (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = lowerText,
                    onValueChange = { lowerText = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Unterer Grenzwert (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Grün = im Limit · Rot = außerhalb\n" +
                            "Bei Rot: Vibration (stummschaltbar).",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        upperText.toFloatOrNull() ?: 0f,
                        lowerText.toFloatOrNull() ?: 0f
                    )
                    onDismiss()
                }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
fun ExportDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onExport: (name: String) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CSV Export", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Messungsname für Export und Dateiname:")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Exportiert den aktuell gewählten Zeitraum\n" +
                            "als CSV-Datei.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onExport(name); onDismiss() },
                enabled = name.isNotBlank()
            ) { Text("Exportieren") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
                Text(
                    "Keine gekoppelten Geräte gefunden.\n\n" +
                            "Bitte zuerst in den Android Bluetooth-Einstellungen koppeln.",
                    color = Color.Gray
                )
            } else {
                LazyColumn {
                    items(devices) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device); onDismiss() }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name ?: "Unbekannt",
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = device.address,
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
fun DeviceConfigDialog(
    uiState: com.waage.viewmodel.WaageUiState,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
    onSave: (sampleRateHz: Int, publishRateHz: Int, avgSamples: Int, offlineBufferSeconds: Int, displayHz: Int) -> Unit,
    onReset: () -> Unit,
    onOpenCalibration: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

    var srateText   by remember(uiState.deviceSampleRateHz)          { mutableStateOf(uiState.deviceSampleRateHz.toString()) }
    var prateText   by remember(uiState.devicePublishRateHz)          { mutableStateOf(uiState.devicePublishRateHz.toString()) }
    var avgText     by remember(uiState.deviceAvgSamples)             { mutableStateOf(uiState.deviceAvgSamples.toString()) }
    var bufsecText  by remember(uiState.deviceOfflineBufferSeconds)   { mutableStateOf(uiState.deviceOfflineBufferSeconds.toString()) }
    var dispHzText  by remember(uiState.deviceDisplayHz)              { mutableStateOf(uiState.deviceDisplayHz.toString()) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val srateVal   = srateText.toIntOrNull()
    val prateVal   = prateText.toIntOrNull()
    val avgVal     = avgText.toIntOrNull()
    val bufsecVal  = bufsecText.toIntOrNull()
    val dispHzVal  = dispHzText.toIntOrNull()

    val srateOk   = srateVal  != null && srateVal  in 1..20
    val prateOk   = prateVal  != null && prateVal  in 1..20 && (srateVal == null || prateVal <= srateVal)
    val avgOk     = avgVal    != null && avgVal    in 1..16
    val bufsecOk  = bufsecVal != null && bufsecVal in 10..3600
    val dispHzOk  = dispHzVal != null && dispHzVal in 1..10
    val allValid  = srateOk && prateOk && avgOk && bufsecOk && dispHzOk

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Zurücksetzen?") },
            text = { Text("Die Gerätekonfiguration wird auf Werkseinstellungen zurückgesetzt.") },
            confirmButton = {
                Button(
                    onClick = { onReset(); showResetConfirm = false; onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF9A9A), contentColor = Color.Black)
                ) { Text("Zurücksetzen") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Abbrechen") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gerätekonfiguration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Kalibrierungsfaktor [-]", color = Color.Gray, fontSize = 13.sp)
                        Text(
                            text = if (uiState.deviceConfigLoaded) "%.4f".format(uiState.calibrationFactor) else "—",
                            fontWeight = FontWeight.Medium
                        )
                    }
                    TextButton(onClick = onOpenCalibration) { Text("Kalibrieren", fontSize = 13.sp) }
                }

                HorizontalDivider()

                if (!uiState.deviceConfigLoaded) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Lade Gerätekonfiguration…", color = Color.Gray, fontSize = 13.sp)
                    }
                }

                OutlinedTextField(
                    value = srateText, onValueChange = { srateText = it.filter { c -> c.isDigit() } },
                    label = { Text("Abtastrate [Hz]") }, supportingText = { Text("1 – 20 Hz") },
                    isError = srateText.isNotEmpty() && !srateOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = uiState.deviceConfigLoaded
                )

                OutlinedTextField(
                    value = prateText, onValueChange = { prateText = it.filter { c -> c.isDigit() } },
                    label = { Text("Senderate [Hz]") }, supportingText = { Text("1 – 20 Hz, ≤ Abtastrate") },
                    isError = prateText.isNotEmpty() && !prateOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = uiState.deviceConfigLoaded
                )

                OutlinedTextField(
                    value = avgText, onValueChange = { avgText = it.filter { c -> c.isDigit() } },
                    label = { Text("Werte für Glättung [-]") }, supportingText = { Text("1 – 16") },
                    isError = avgText.isNotEmpty() && !avgOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = uiState.deviceConfigLoaded
                )

                OutlinedTextField(
                    value = bufsecText, onValueChange = { bufsecText = it.filter { c -> c.isDigit() } },
                    label = { Text("Offline-Puffer [s]") }, supportingText = { Text("10 – 3600 s") },
                    isError = bufsecText.isNotEmpty() && !bufsecOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = uiState.deviceConfigLoaded
                )

                OutlinedTextField(
                    value = dispHzText, onValueChange = { dispHzText = it.filter { c -> c.isDigit() } },
                    label = { Text("Anzeige-Aktualisierung [Hz]") }, supportingText = { Text("1 – 10 Hz") },
                    isError = dispHzText.isNotEmpty() && !dispHzOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = uiState.deviceConfigLoaded
                )

                if (uiState.deviceConfigLoaded) {
                    Text("Pufferkapazität: ${uiState.deviceOfflineBufferCapacity} Samples", color = Color.Gray, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                TextButton(onClick = { showResetConfirm = true }, enabled = uiState.deviceConfigLoaded) {
                    Text("Reset", color = if (uiState.deviceConfigLoaded) Color(0xFFEF9A9A) else Color.Gray)
                }
                Button(
                    onClick = { onSave(srateVal!!, prateVal!!, avgVal!!, bufsecVal!!, dispHzVal!!); onDismiss() },
                    enabled = uiState.deviceConfigLoaded && allValid
                ) { Text("Speichern") }
            }
        },
        dismissButton = {}
    )
}