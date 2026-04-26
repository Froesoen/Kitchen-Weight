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
    onCalibrate: (weightG: Float, onSuccess: (Float) -> Unit) -> Unit
) {
    var weightText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var savedFactor by remember { mutableStateOf<Float?>(null) }

    val weightVal = weightText.replace(',', '.').toFloatOrNull()
    val weightOk = weightVal != null && weightVal > 0f

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Kalibrierung", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. Waage entlasten und tarieren.")
                Text("2. Bekanntes Gewicht auflegen.")
                Text("3. Gewicht eingeben und kalibrieren.")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Aktueller Faktor", color = Color.Gray, fontSize = 13.sp)
                    Text("%.4f".format(savedFactor ?: calibrationFactor), fontWeight = FontWeight.Medium)
                }

                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("Bekanntes Gewicht [g]") },
                    supportingText = { Text("z. B. 500") },
                    isError = weightText.isNotEmpty() && !weightOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Kalibriere…", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text("Abbrechen")
                }
                TextButton(onClick = onTare, enabled = !busy) {
                    Text("Tara")
                }
                Button(
                    onClick = {
                        busy = true
                        onCalibrate(weightVal!!) { newFactor ->
                            savedFactor = newFactor
                            busy = false
                            onDismiss()
                        }
                    },
                    enabled = weightOk && !busy
                ) {
                    Text("Kalibrieren")
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Kalibrierungsfaktor [-]", color = Color.Gray, fontSize = 13.sp)
                        Text(
                            text = if (uiState.deviceConfigLoaded) {
                                "%.4f".format(uiState.calibrationFactor)
                            } else {
                                "—"
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                    TextButton(onClick = onOpenCalibration) {
                        Text("Kalibrieren", fontSize = 13.sp)
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { showResetConfirm = true },
                        enabled = uiState.deviceConfigLoaded
                    ) {
                        Text(
                            "Reset",
                            color = if (uiState.deviceConfigLoaded) Color(0xFFEF9A9A) else Color.Gray
                        )
                    }

                    Button(
                        onClick = {
                            onSave(prateVal!!, avgVal!!, bufsecVal!!, dispHzVal!!)
                            onDismiss()
                        },
                        enabled = uiState.deviceConfigLoaded && allValid
                    ) {
                        Text("Speichern")
                    }
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
    var upperText by remember { mutableStateOf(if (upperG == 0f) "" else upperG.toString()) }
    var lowerText by remember { mutableStateOf(if (lowerG == 0f) "" else lowerG.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schwellwert-Alarm", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = upperText,
                    onValueChange = { upperText = it },
                    label = { Text("Oberes Limit [g]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lowerText,
                    onValueChange = { lowerText = it },
                    label = { Text("Unteres Limit [g]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        upperText.replace(',', '.').toFloatOrNull() ?: 0f,
                        lowerText.replace(',', '.').toFloatOrNull() ?: 0f
                    )
                    onDismiss()
                }
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
fun ExportDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onExport: (String) -> Unit
) {
    var fileName by remember { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CSV exportieren", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("Dateiname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onExport(fileName)
                    onDismiss()
                }
            ) {
                Text("Exportieren")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
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