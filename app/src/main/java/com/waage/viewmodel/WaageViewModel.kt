package com.waage.viewmodel

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waage.bluetooth.BluetoothService
import com.waage.bluetooth.ConnectionState
import com.waage.bluetooth.WaageMessage
import com.waage.bluetooth.hasBluetoothConnectPermission
import com.waage.data.AppSettings
import com.waage.data.CsvExporter
import com.waage.data.TimeRange
import com.waage.data.WeightBuffer
import com.waage.data.WeightSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.Manifest

private const val TAG = "WaageViewModel"

enum class WeightColor { WHITE, GREEN, RED }
data class WaageUiState(
    val weightG: Float = 0f,
    val weightFormatted: String = "0.0 g",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val selectedRange: TimeRange = TimeRange.ONE_MIN,
    val graphSamples: List<WeightSample> = emptyList(),
    val stats: WeightBuffer.Stats? = null,
    val calibrationFactor: Float = 1f,
    val alarmActive: Boolean = false,
    val weightColor: WeightColor = WeightColor.WHITE,
    val alarmTriggered: Boolean = false,   // steuert Vibration
    val alarmMuted: Boolean = false,
    val alarmUpperG: Float = 0f,
    val alarmLowerG: Float = 0f,
    // Gerätekonfiguration
    val deviceSampleRateHz: Int = 0,
    val devicePublishRateHz: Int = 0,
    val deviceAvgSamples: Int = 0,
    val deviceOfflineBufferSeconds: Int = 0,
    val deviceOfflineBufferCapacity: Int = 0,
    val deviceDisplayHz: Int = 2,
    val deviceConfigLoaded: Boolean = false
)

class WaageViewModel(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
) : ViewModel() {

    private val _uiState = MutableStateFlow(WaageUiState())
    val uiState: StateFlow<WaageUiState> = _uiState

    private val settings = AppSettings(context)
    private val buffer = WeightBuffer()
    private val csvExporter = CsvExporter(context)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private var btService: BluetoothService? = null

    init {
        _uiState.value = _uiState.value.copy(
            selectedRange = settings.selectedTimeRange,
            alarmUpperG   = settings.alarmUpperG,
            alarmLowerG   = settings.alarmLowerG,
            alarmMuted    = settings.alarmMuted
        )
        createBluetoothService()
        autoConnectLastDevice()
    }

    private fun autoConnectLastDevice() {
        val lastAddress = settings.lastDeviceAddress
        if (lastAddress.isNullOrEmpty() || !canUseBluetooth()) return
        try {
            val device = bluetoothAdapter.bondedDevices
                ?.firstOrNull { it.address == lastAddress }
            if (device != null) {
                Log.d(TAG, "Auto-reconnect to last device: $lastAddress")
                service()?.connect(device)
            } else {
                Log.d(TAG, "Last device $lastAddress not in bonded devices, skipping auto-connect")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Auto-reconnect failed: missing permission", e)
        }
    }

    private fun createBluetoothService() {
        btService = BluetoothService(
            adapter = bluetoothAdapter,
            onMessage = ::handleMessage,
            onStateChange = ::handleStateChange
        )
    }

    private fun canUseBluetooth(): Boolean = hasBluetoothConnectPermission(context)

    private fun service(): BluetoothService? = btService ?: run {
        createBluetoothService()
        btService
    }

    private fun safeDeviceLabel(device: BluetoothDevice): String {
        if (!canUseBluetooth()) return "unknown / ${device.address}"
        return try {
            val name = device.name ?: "unknown"
            "$name / ${device.address}"
        } catch (_: SecurityException) {
            "unknown / ${device.address}"
        }
    }

    fun connect(device: BluetoothDevice) {
        if (!canUseBluetooth()) {
            Log.w(TAG, "connect aborted: missing Bluetooth permission")
            return
        }
        settings.lastDeviceAddress = device.address
        Log.d(TAG, "connect -> ${safeDeviceLabel(device)}")
        try {
            service()?.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth connect security error", e)
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth connect failed", e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        try {
            service()?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth disconnect failed", e)
        }
    }

    fun clearData() {
        Log.d(TAG, "clearData()")
        buffer.clear()
        recalculateUi()
    }

    fun sendTare() {
        if (!canUseBluetooth()) return
        Log.d(TAG, "sendTare()")
        try {
            service()?.sendTare()
        } catch (e: SecurityException) {
            Log.e(TAG, "sendTare failed", e)
        }
    }

    fun sendCalibrate(knownWeightG: Float) {
        if (!canUseBluetooth()) return
        Log.d(TAG, "sendCalibrate($knownWeightG)")
        try {
            service()?.sendCalibrate(knownWeightG)
        } catch (e: SecurityException) {
            Log.e(TAG, "sendCalibrate failed", e)
        }
    }

    private fun handleMessage(msg: WaageMessage) {
        viewModelScope.launch {
            when (msg) {
                is WaageMessage.MeasurementBatch -> {
                    msg.samples.forEach { sample ->
                        buffer.add(WeightSample(sample.weightG, sample.timestampMs, synced = true))
                    }
                    recalculateUi()
                    msg.samples.lastOrNull()?.let { checkAlarm(it.weightG) }
                }

                is WaageMessage.TareDone -> {
                    Log.d(TAG, "tare_done offset=${msg.offset}")
                }

                is WaageMessage.Factor -> {
                    _uiState.value = _uiState.value.copy(calibrationFactor = msg.value)
                    Log.d(TAG, "factor=${msg.value}")
                }

                is WaageMessage.NeedSync -> {
                    if (canUseBluetooth()) {
                        try {
                            service()?.sendSync()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "sendSync failed", e)
                        }
                    }
                }

                is WaageMessage.SyncDone -> {
                    Log.d(TAG, "sync_done")
                }

                is WaageMessage.Config -> {
                    _uiState.value = _uiState.value.copy(
                        deviceSampleRateHz          = msg.sampleRateHz,
                        devicePublishRateHz         = msg.publishRateHz,
                        deviceAvgSamples            = msg.avgSamples,
                        deviceOfflineBufferSeconds  = msg.offlineBufferSeconds,
                        deviceOfflineBufferCapacity = msg.offlineBufferCapacity,
                        deviceDisplayHz             = msg.displayHz,
                        deviceConfigLoaded          = true
                    )
                    Log.d(TAG, "config received srate=${msg.sampleRateHz} prate=${msg.publishRateHz} disphz=${msg.displayHz}")
                }

                is WaageMessage.Error -> {
                    Log.w(TAG, "ESP32 error: ${msg.message}")
                }
            }
        }
    }


    private fun handleStateChange(state: ConnectionState) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(connectionState = state)
        }
    }

    private fun recalculateUi() {
        val samples = buffer.getSamples(_uiState.value.selectedRange)
        val latestWeight = samples.lastOrNull()?.weightG ?: 0f

        val stats = if (samples.isEmpty()) null else {
            val weights = samples.map { it.weightG }
            WeightBuffer.Stats(
                min = weights.minOrNull() ?: 0f,
                max = weights.maxOrNull() ?: 0f,
                avg = weights.average().toFloat(),
                count = weights.size
            )
        }

        _uiState.value = _uiState.value.copy(
            weightG = latestWeight,
            weightFormatted = formatWeight(latestWeight),
            graphSamples = samples,
            stats = stats
        )
    }

    fun setTimeRange(range: TimeRange) {
        settings.selectedTimeRange = range
        _uiState.value = _uiState.value.copy(selectedRange = range)
        recalculateUi()
    }

    fun setAlarmUpper(g: Float) {
        settings.alarmUpperG = g
        _uiState.value = _uiState.value.copy(alarmUpperG = g)
    }

    fun setAlarmLower(g: Float) {
        settings.alarmLowerG = g
        _uiState.value = _uiState.value.copy(alarmLowerG = g)
    }

    fun muteAlarm(muted: Boolean) {
        settings.alarmMuted = muted
        _uiState.value = _uiState.value.copy(alarmMuted = muted)
        // Farbe bleibt unverändert – nur Vibration wird unterdrückt
    }

    private fun checkAlarm(weightG: Float) {
        val upper = _uiState.value.alarmUpperG
        val lower = _uiState.value.alarmLowerG
        val hasUpper = upper != 0f
        val hasLower = lower != 0f

        val color = when {
            // Kein Limit gesetzt
            !hasUpper && !hasLower -> WeightColor.WHITE
            // Nur unteres Limit
            hasLower && !hasUpper -> if (weightG < lower) WeightColor.RED else WeightColor.GREEN
            // Nur oberes Limit
            hasUpper && !hasLower -> if (weightG > upper) WeightColor.RED else WeightColor.GREEN
            // Beide Limits: grün wenn im Bereich, sonst rot
            else -> if (weightG in lower..upper) WeightColor.GREEN else WeightColor.RED
        }

        val triggered = color == WeightColor.RED
        if (triggered && !_uiState.value.alarmMuted) vibrate()
        _uiState.value = _uiState.value.copy(
            weightColor = color,
            alarmTriggered = triggered
        )
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
        }
    }

    fun exportCsv(name: String) {
        settings.measurementName = name
        csvExporter.exportAndShare(
            buffer = buffer,
            range = _uiState.value.selectedRange,
            measurementName = name,
            calibrationFactor = _uiState.value.calibrationFactor
        )
    }

    private fun formatWeight(g: Float): String {
        return if (g >= 1000f || g <= -1000f) {
            "${"%.3f".format(g / 1000f)} kg"
        } else {
            "${"%.1f".format(g)} g"
        }
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        if (!canUseBluetooth()) return emptyList()
        return try {
            bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "getPairedDevices failed", e)
            emptyList()
        }
    }

    fun requestDeviceConfig() {
        if (!canUseBluetooth()) return
        Log.d(TAG, "requestDeviceConfig()")
        try {
            service()?.sendGetConfig()
            service()?.sendGetFactor()
        } catch (e: SecurityException) {
            Log.e(TAG, "requestDeviceConfig failed", e)
        }
    }

    fun sendDeviceConfig(
        sampleRateHz: Int,
        publishRateHz: Int,
        avgSamples: Int,
        offlineBufferSeconds: Int,
        displayHz: Int
    ) {
        if (!canUseBluetooth()) return
        Log.d(TAG, "sendDeviceConfig($sampleRateHz, $publishRateHz, $avgSamples, $offlineBufferSeconds, $displayHz)")
        try {
            service()?.sendSetConfig(sampleRateHz, publishRateHz, avgSamples, offlineBufferSeconds, displayHz)
        } catch (e: SecurityException) {
            Log.e(TAG, "sendDeviceConfig failed", e)
        }
    }

    fun resetDeviceConfig() {
        if (!canUseBluetooth()) return
        Log.d(TAG, "resetDeviceConfig()")
        try {
            service()?.sendResetConfig()
        } catch (e: SecurityException) {
            Log.e(TAG, "resetDeviceConfig failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            btService?.disconnect()
        } catch (_: Exception) {
        }
    }
}