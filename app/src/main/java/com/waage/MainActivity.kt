package com.waage

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waage.bluetooth.hasBluetoothConnectPermission
import com.waage.bluetooth.requiredBluetoothPermissions
import com.waage.ui.*
import com.waage.viewmodel.WaageViewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.waage.R

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: WaageViewModel
    private var bluetoothPermissionsGranted by mutableStateOf(false)

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            bluetoothPermissionsGranted = result.values.all { it }
        }

    private fun requestBluetoothPermissions() {
        val permissions = requiredBluetoothPermissions()
        if (permissions.isEmpty()) bluetoothPermissionsGranted = true
        else bluetoothPermissionLauncher.launch(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        bluetoothPermissionsGranted = hasBluetoothConnectPermission(this)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                WaageViewModel(applicationContext, btAdapter) as T
        })[WaageViewModel::class.java]
        setContent {
            WaageTheme {
                WaageScreen(
                    viewModel                    = viewModel,
                    bluetoothPermissionsGranted  = bluetoothPermissionsGranted,
                    onRequestBluetoothPermissions = { requestBluetoothPermissions() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaageScreen(
    viewModel:                    WaageViewModel,
    bluetoothPermissionsGranted:  Boolean,
    onRequestBluetoothPermissions: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showCalibration             by remember { mutableStateOf(false) }
    var showAlarm                   by remember { mutableStateOf(false) }
    var showExport                  by remember { mutableStateOf(false) }
    var showDevicePicker            by remember { mutableStateOf(false) }
    var showMenu                    by remember { mutableStateOf(false) }
    var showBluetoothPermissionDialog by remember { mutableStateOf(false) }
    var showDeviceConfig            by remember { mutableStateOf(false) }

    // Kalibrier-Callback-Handling
    var pendingCalibrateCallback by remember { mutableStateOf<((Float) -> Unit)?>(null) }
    LaunchedEffect(uiState.calibrationFactor) {
        pendingCalibrateCallback?.invoke(uiState.calibrationFactor)
        pendingCalibrateCallback = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Image(
							painter = painterResource(id = R.mipmap.ic_launcher_foreground),
							contentDescription = "Kitchen Weight Logo",
							modifier = Modifier.size(28.dp),
							contentScale = ContentScale.Fit
						)
						Text(
							text = "Kitchen Weight",
							color = Color.White,
							fontWeight = FontWeight.SemiBold
						)
					}
				},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                actions = {
                    IconButton(onClick = { viewModel.clearData() }) {
                        Icon(Icons.Default.Refresh, "Daten zurücksetzen", tint = Color.White)
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Menü", tint = Color.White)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text        = { Text("Kalibrierung") },
                            leadingIcon = { Icon(Icons.Default.Tune, null) },
                            onClick     = { showCalibration = true; showMenu = false }
                        )
                        DropdownMenuItem(
                            text        = { Text("Gerätekonfiguration") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick     = { showDeviceConfig = true; showMenu = false }
                        )
                        DropdownMenuItem(
                            text        = { Text("Schwellwert-Alarm") },
                            leadingIcon = { Icon(Icons.Default.Notifications, null) },
                            onClick     = { showAlarm = true; showMenu = false }
                        )
                        DropdownMenuItem(
                            text        = { Text("CSV exportieren") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick     = { showExport = true; showMenu = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text        = { Text("Bluetooth-Gerät wählen") },
                            leadingIcon = { Icon(Icons.Default.Bluetooth, null) },
                            onClick     = {
                                showMenu = false
                                if (bluetoothPermissionsGranted) showDevicePicker = true
                                else showBluetoothPermissionDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text        = { Text("Verbindung aktualisieren") },
                            leadingIcon = { Icon(Icons.Default.Sync, null) },
                            onClick     = {
                                showMenu = false
                                if (bluetoothPermissionsGranted) viewModel.reconnectDevice()
                                else showBluetoothPermissionDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text        = { Text("Verbindung trennen") },
                            leadingIcon = { Icon(Icons.Default.BluetoothDisabled, null) },
                            onClick     = {
                                showMenu = false
                                if (bluetoothPermissionsGranted) viewModel.disconnect()
                                else showBluetoothPermissionDialog = true
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            // ── StatusBar (fest oben, kein Scroll) ────────────────────────
            StatusBar(state = uiState.connectionState, lastWeight = uiState.weightG)

            // ── Scrollbarer Inhalt ─────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Gewichtsanzeige
                WeightDisplay(
                    formatted      = uiState.weightFormatted,
                    stats          = uiState.stats,
                    weightColor    = uiState.weightColor,
                    alarmTriggered = uiState.alarmTriggered,
                    alarmMuted     = uiState.alarmMuted,
                    onMuteAlarm    = { viewModel.muteAlarm(true) },
                    modifier       = Modifier.padding(horizontal = 16.dp)
                )

                HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 1.dp)

                // Gewichts-Zeitverlauf
                WeightGraph(
                    samples         = uiState.graphSamples,
                    selectedRange   = uiState.selectedRange,
                    alarmUpperG     = uiState.alarmUpperG,
                    alarmLowerG     = uiState.alarmLowerG,
                    stats           = uiState.stats,
                    onRangeSelected = { viewModel.setTimeRange(it) },
                    modifier        = Modifier
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(220.dp)
                )

                // FFT-Spektrum (nur anzeigen wenn Daten vorhanden)
                val fft = uiState.fftResult
                if (fft != null) {
                    HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 1.dp)
                    FftSpectrumCard(
                        fft      = fft,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }

                // Puffer am Ende damit Tara-Button den Inhalt nicht verdeckt
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Tara-Button (fest unten, kein Scroll) ─────────────────────
            HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 1.dp)
            Button(
                onClick = {
                    if (bluetoothPermissionsGranted) viewModel.sendTare()
                    else showBluetoothPermissionDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E2E2E),
                    contentColor   = Color.White
                )
            ) {
                Icon(Icons.Default.Refresh, null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("TARA", fontSize = 16.sp, color = Color.White)
            }
        }
    }

    // ── Dialoge ───────────────────────────────────────────────────────────────
    if (showBluetoothPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showBluetoothPermissionDialog = false },
            title = { Text("Bluetooth-Berechtigung erforderlich") },
            text  = { Text("Für das Verbinden mit der Waage muss die Bluetooth-Berechtigung erteilt werden.") },
            confirmButton = {
                Button(onClick = {
                    showBluetoothPermissionDialog = false
                    onRequestBluetoothPermissions()
                }) { Text("Berechtigung anfordern") }
            },
            dismissButton = {
                TextButton(onClick = { showBluetoothPermissionDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    if (showDeviceConfig) {
        DeviceConfigDialog(
            uiState  = uiState,
            onDismiss = { showDeviceConfig = false },
            onLoad   = {
                if (bluetoothPermissionsGranted) viewModel.requestDeviceConfig()
                else showBluetoothPermissionDialog = true
            },
            // sampleRateHz entfernt — Signatur jetzt (prate, avg, bufsec, disphz)
            onSave = { prate, avg, bufsec, disphz ->
                if (bluetoothPermissionsGranted) viewModel.sendDeviceConfig(prate, avg, bufsec, disphz)
                else showBluetoothPermissionDialog = true
            },
            onReset = {
                if (bluetoothPermissionsGranted) viewModel.resetDeviceConfig()
                else showBluetoothPermissionDialog = true
            },
            onOpenCalibration = {
                showDeviceConfig = false
                showCalibration  = true
            }
        )
    }

    if (showCalibration) {
        CalibrationDialog(
            calibrationFactor = uiState.calibrationFactor,
            deviceConfigLoaded = uiState.deviceConfigLoaded,
            onDismiss         = { showCalibration = false },
            onLoad            = { viewModel.requestDeviceConfig() },
            onTare            = {
                if (bluetoothPermissionsGranted) viewModel.sendTare()
                else showBluetoothPermissionDialog = true
            },
            onCalibrate = { weightG: Float, onSuccess: (Float) -> Unit ->
                pendingCalibrateCallback = onSuccess
                if (bluetoothPermissionsGranted) viewModel.sendCalibrate(weightG)
                else showBluetoothPermissionDialog = true
            }
        )
    }

    if (showAlarm) {
        AlarmDialog(
            upperG    = uiState.alarmUpperG,
            lowerG    = uiState.alarmLowerG,
            onDismiss = { showAlarm = false },
            onSave    = { upper, lower ->
                viewModel.setAlarmUpper(upper)
                viewModel.setAlarmLower(lower)
                viewModel.muteAlarm(false)
            }
        )
    }

    if (showExport) {
        ExportDialog(
            defaultName = "Messung",
            onDismiss   = { showExport = false },
            onExport    = { name -> viewModel.exportCsv(name) }
        )
    }

    if (showDevicePicker && bluetoothPermissionsGranted) {
        DevicePickerDialog(
            devices          = viewModel.getPairedDevices(),
            onDismiss        = { showDevicePicker = false },
            onDeviceSelected = { device -> viewModel.connect(device) }
        )
    }
}

@Composable
fun WaageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary    = Color(0xFF4CAF50),
            secondary  = Color(0xFF2196F3),
            surface    = Color(0xFF1E1E1E),
            background = Color(0xFF121212)
        ),
        content = content
    )
}
