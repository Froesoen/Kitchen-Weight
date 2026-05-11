package com.waage.viewmodel

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waage.bluetooth.BluetoothService
import com.waage.bluetooth.ConnectionState
import com.waage.bluetooth.FftResult
import com.waage.bluetooth.WaageMessage
import com.waage.bluetooth.hasBluetoothConnectPermission
import com.waage.data.AppSettings
import com.waage.data.CsvExporter
import com.waage.data.TimeRange
import com.waage.data.WeightBuffer
import com.waage.data.WeightSample
import com.waage.util.formatWeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

private const val TAG = "WaageViewModel"

enum class WeightColor { WHITE, GREEN, RED }

data class WaageUiState(
    val weightG:           Float         = 0f,
    val weightFormatted:   String        = "0.0 g",
    // Einzelkanäle
    val weightRearG:       Float         = 0f,
    val weightMidG:        Float         = 0f,
    val weightFrontG:      Float         = 0f,

    val connectionState:   ConnectionState = ConnectionState.Disconnected,
    val selectedRange:     TimeRange     = TimeRange.ONE_MIN,
    val graphSamples:      List<WeightSample> = emptyList(),
    val stats:             WeightBuffer.Stats? = null,
    val factorRear:        Float         = 1f,
    val factorMid:         Float         = 1f,
    val factorFront:       Float         = 1f,
    val alarmActive:       Boolean       = false,
    val weightColor:       WeightColor   = WeightColor.WHITE,
    val alarmTriggered:    Boolean       = false,
    val alarmMuted:        Boolean       = false,
    val alarmUpperG:       Float         = Float.NaN,
    val alarmLowerG:       Float         = Float.NaN,

    val deviceSampleRateHz:          Int     = 20,
    val devicePublishRateHz:         Int     = 0,
    val deviceAvgSamples:            Int     = 0,
    val deviceOfflineBufferSeconds:  Int     = 0,
    val deviceOfflineBufferCapacity: Int     = 0,
    val deviceDisplayHz:             Int     = 2,
    val deviceDeltaDurationMs:       Int     = 2000,
    val deviceDeltaTolerance:        Float   = 2.0f,
    val deviceConfigLoaded:          Boolean = false,

    val fftResult:         FftResult?    = null
)

class WaageViewModel(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
) : ViewModel() {

    private val _uiState = MutableStateFlow(WaageUiState())
    val uiState: StateFlow<WaageUiState> = _uiState
    val lastReceivedFactor = MutableStateFlow<Pair<String, Float>?>(null)

    private val settings    = AppSettings(context)
    private val buffer      = WeightBuffer()
    private val csvExporter = CsvExporter(context)
    private val vibrator    = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private var btService: BluetoothService? = null

    init {
        _uiState.value = _uiState.value.copy(
            alarmUpperG = settings.alarmUpperG,
            alarmLowerG = settings.alarmLowerG,
            alarmMuted  = settings.alarmMuted
        )
        createBluetoothService()
        autoConnectLastDevice()
    }

    private fun autoConnectLastDevice() {
        val lastAddress = settings.lastDeviceAddress
        if (lastAddress.isNullOrEmpty() || !canUseBluetooth()) return
        try {
            val device = bluetoothAdapter.bondedDevices?.firstOrNull { it.address == lastAddress }
            if (device != null) {
                Log.d(TAG, "Auto-reconnect to $lastAddress")
                service()?.connect(device)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Auto-reconnect failed: missing permission", e)
        }
    }

    private fun createBluetoothService() {
        btService = BluetoothService(
            adapter       = bluetoothAdapter,
            onMessage     = ::handleMessage,
            onStateChange = ::handleStateChange
        )
    }

    private fun canUseBluetooth(): Boolean = hasBluetoothConnectPermission(context)

    private fun service(): BluetoothService? = btService ?: run {
        createBluetoothService(); btService
    }

    fun connect(device: BluetoothDevice) {
        if (!canUseBluetooth()) return
        settings.lastDeviceAddress = device.address
        try { service()?.connect(device) } catch (e: Exception) { Log.e(TAG, "connect", e) }
    }

    fun disconnect() {
        try { service()?.disconnect() } catch (e: Exception) { Log.e(TAG, "disconnect", e) }
    }

    fun reconnectDevice() {
        if (!canUseBluetooth()) return
        try { service()?.disconnect() } catch (e: Exception) { Log.e(TAG, "reconnect/disconnect", e) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000L)
            autoConnectLastDevice()
        }
    }

    fun clearData() { buffer.clear(); recalculateUi() }

    fun sendTare() {
        if (!canUseBluetooth()) return
        try { service()?.sendTare() } catch (e: SecurityException) { Log.e(TAG, "tare", e) }
    }

    fun sendTareChannel(channel: Char) {
        if (!canUseBluetooth()) return
        try { service()?.sendJson("""{"type":"tare_channel","ch":"$channel"}""") }
        catch (e: SecurityException) { Log.e(TAG, "tare_channel", e) }
    }

    fun sendCalibrateChannel(channel: Char, knownWeightG: Float) {
        if (!canUseBluetooth()) return
        try { service()?.sendJson("""{"type":"calibrate_channel","ch":"$channel","weight":$knownWeightG}""") }
        catch (e: SecurityException) { Log.e(TAG, "calibrate_channel", e) }
    }

    fun requestDeviceConfig() {
        if (!canUseBluetooth()) return
        try { service()?.sendGetConfig(); service()?.sendGetFactor() } catch (e: SecurityException) { Log.e(TAG, "getconfig", e) }
    }
    fun setFactorManual(channel: Char, factor: Float) {
        if (!canUseBluetooth()) return
        try { service()?.sendSetFactor(channel, factor) }
        catch (e: SecurityException) { Log.e(TAG, "set_factor", e) }
    }

    fun sendDeviceConfig(
        publishRateHz: Int,
        avgSamples: Int,
        offlineBufferSeconds: Int,
        displayHz: Int,
        deltaDurationMs: Int,
        deltaTolerance: Float
    ) {
        if (!canUseBluetooth()) return
        try {
            service()?.sendDeviceConfig(
                publishRateHz, avgSamples, offlineBufferSeconds,
                displayHz, deltaDurationMs, deltaTolerance
            )
        } catch (e: SecurityException) { Log.e(TAG, "sendDeviceConfig", e) }
    }

    fun resetDeviceConfig() {
        if (!canUseBluetooth()) return
        try { service()?.sendResetConfig() } catch (e: SecurityException) { Log.e(TAG, "resetconfig", e) }
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

    fun clearAlarms() {
        settings.alarmUpperG = Float.NaN
        settings.alarmLowerG = Float.NaN
        _uiState.value = _uiState.value.copy(
            alarmUpperG    = Float.NaN,
            alarmLowerG    = Float.NaN,
            alarmTriggered = false,
            weightColor    = WeightColor.WHITE,
            alarmActive    = false
        )
    }

    fun muteAlarm() {
        settings.alarmMuted = true
        _uiState.value = _uiState.value.copy(alarmMuted = true)
    }

    private fun handleMessage(msg: WaageMessage) {
        viewModelScope.launch {
            when (msg) {
                is WaageMessage.MeasurementBatch -> {
                    val now = System.currentTimeMillis()
                    val newSamples = msg.samples.map { s ->
                        WeightSample(
                            weightG     = s.weightG,
                            weightR     = s.weightR,
                            weightM     = s.weightM,
                            weightF     = s.weightF,
                            timestampMs = s.timestampMs
                        )
                    }
					
                    buffer.addAll(newSamples)

                    val visible = buffer.getSamples(_uiState.value.selectedRange)

                    recalculateUi()
                    newSamples.lastOrNull()?.let { checkAlarm(it.weightG) }
                    // Einzelkanäle aus dem letzten Sample in UI-State übernehmen
                    newSamples.lastOrNull()?.let { last ->
                        _uiState.value = _uiState.value.copy(
                            weightRearG  = last.weightR,
                            weightMidG   = last.weightM,
                            weightFrontG = last.weightF
                        )
                    }
                }

                is WaageMessage.FftData -> {
                    _uiState.value = _uiState.value.copy(fftResult = msg.result)
                }

                is WaageMessage.TareDone -> {
                    Log.d(TAG, "tare_done offset=${msg.offset}")
                }

                is WaageMessage.Factor -> {
                    _uiState.update {
                        when (msg.channel) {
                            "R"  -> it.copy(factorRear   = msg.value)
                            "M"  -> it.copy(factorMid    = msg.value)
                            "F"  -> it.copy(factorFront  = msg.value)
                            else -> it
                        }
                    }
                    lastReceivedFactor.value = msg.channel to msg.value
                }

                is WaageMessage.SyncDone -> {
                    Log.i(TAG, "sync_done empfangen")
                    requestDeviceConfig()   // ← Config erst nach erfolgreichem sync laden
                }

                is WaageMessage.Config -> {
                    _uiState.update { it.copy(
                        deviceSampleRateHz          = msg.sampleRateHz,
                        devicePublishRateHz         = msg.publishRateHz,
                        deviceAvgSamples            = msg.avgSamples,
                        deviceOfflineBufferSeconds  = msg.offlineBufferSeconds,
                        deviceOfflineBufferCapacity = msg.offlineBufferCapacity,
                        deviceDisplayHz             = msg.displayHz,
                        deviceDeltaDurationMs       = msg.deltaDurationMs,
                        deviceDeltaTolerance        = msg.deltaTolerance,
                        deviceConfigLoaded          = true,
                        factorRear  = if (msg.factorRear  > 0f) msg.factorRear  else it.factorRear,
                        factorMid   = if (msg.factorMid   > 0f) msg.factorMid   else it.factorMid,
                        factorFront = if (msg.factorFront > 0f) msg.factorFront else it.factorFront
                    )}
                }

                is WaageMessage.Error -> Log.w(TAG, "ESP32 error: ${msg.message}")
                else -> {}
            }
        }
    }

    private fun handleStateChange(state: ConnectionState) {
        viewModelScope.launch {
            Log.i("WAAGE_BUF", "StateChange → $state | buffer.size=${buffer.size()}")
            val disconnected = state is ConnectionState.Disconnected || state is ConnectionState.Error
            _uiState.value = _uiState.value.copy(
                connectionState    = state,
                deviceConfigLoaded = if (disconnected) false else _uiState.value.deviceConfigLoaded,
                alarmMuted         = if (disconnected) false else _uiState.value.alarmMuted
            )
            // requestDeviceConfig() hier entfernt → wird nach sync_done aufgerufen
        }
    }

    private fun recalculateUi() {
        val samples      = buffer.getSamples(_uiState.value.selectedRange)
        val latestWeight = samples.lastOrNull()?.weightG ?: 0f
        val stats = if (samples.isEmpty()) null else {
            val weights = samples.map { it.weightG }
            WeightBuffer.Stats(
                min   = weights.minOrNull() ?: 0f,
                max   = weights.maxOrNull() ?: 0f,
                avg   = weights.average().toFloat(),
                count = weights.size
            )
        }
        _uiState.value = _uiState.value.copy(
            weightG         = latestWeight,
            weightFormatted = formatWeight(latestWeight),
            graphSamples    = samples,
            stats           = stats
        )
    }

    private fun checkAlarm(weightG: Float) {
        val upper = _uiState.value.alarmUpperG
        val lower = _uiState.value.alarmLowerG
        val hasUpper = !upper.isNaN()
        val hasLower = !lower.isNaN()

        val color = when {
            !hasUpper && !hasLower          -> WeightColor.WHITE
            hasLower && !hasUpper           -> if (weightG < lower) WeightColor.RED else WeightColor.GREEN
            hasUpper && !hasLower           -> if (weightG > upper) WeightColor.RED else WeightColor.GREEN
            weightG in lower..upper         -> WeightColor.GREEN
            else                            -> WeightColor.RED
        }
        val triggered    = color == WeightColor.RED
        val prevTriggered = _uiState.value.alarmTriggered
        _uiState.value = _uiState.value.copy(
            weightColor    = color,
            alarmActive    = hasUpper || hasLower,
            alarmTriggered = triggered
        )
        if (triggered && !prevTriggered && !_uiState.value.alarmMuted) triggerVibration()
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 150, 300), -1)
            }
        } catch (e: Exception) { Log.w(TAG, "vibration failed", e) }
    }

    fun exportCsv(name: String) {
        settings.measurementName = name
        csvExporter.exportAndShare(
            buffer            = buffer,
            range             = _uiState.value.selectedRange,
            measurementName   = name,
            calibrationFactor = (_uiState.value.factorRear + _uiState.value.factorMid + _uiState.value.factorFront) / 3f
        )
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        if (!canUseBluetooth()) return emptyList()
        return try {
            bluetoothAdapter.bondedDevices?.toList()?.sortedBy { device ->
                try { device.name ?: device.address } catch (_: SecurityException) { device.address }
            } ?: emptyList()
        } catch (e: SecurityException) { Log.w(TAG, "getPairedDevices failed", e); emptyList() }
    }

    override fun onCleared() {
        super.onCleared()
        try { btService?.disconnect() } catch (_: Exception) {}
    }
}