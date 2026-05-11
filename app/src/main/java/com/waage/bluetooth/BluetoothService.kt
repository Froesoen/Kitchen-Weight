package com.waage.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val TAG = "BluetoothService"

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val attempt: Int, val maxAttempts: Int) : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

// Einzelnes Sample im Batch – jetzt mit allen 4 Gewichtswerten
data class BatchSample(
    val weightG:  Float,
    val weightR:  Float,   // Kanal hinten
    val weightM:  Float,   // Kanal mitte
    val weightF:  Float,   // Kanal vorne
    val timestampMs: Long
)

data class FftResult(
    val peakHz:   Float,
    val peakAmp:  Float,
    val binResHz: Float,
    val fs:       Int,
    val bins:     List<Int>,
    val machineOff: Boolean = false   // true wenn Span < 5g
)

sealed class WaageMessage {
    data class MeasurementBatch(val samples: List<BatchSample>) : WaageMessage()
    data class TareDone(val channel: String, val offset: Float) : WaageMessage()
    data class Factor(val channel: String, val value: Float) : WaageMessage()
    data class FftData(val result: FftResult) : WaageMessage()
    object SyncDone : WaageMessage()
    data class Error(val message: String) : WaageMessage()
    data class Config(
        val sampleRateHz: Int,
        val publishRateHz: Int,
        val avgSamples: Int,
        val offlineBufferSeconds: Int,
        val offlineBufferCapacity: Int,
        val displayHz: Int,
        val deltaDurationMs: Int = 2000,
        val deltaTolerance: Float = 2.0f,
        val factorRear:  Float = -1f,
        val factorMid:   Float = -1f,
        val factorFront: Float = -1f
    ) : WaageMessage()
}

class BluetoothService(
    private val adapter: BluetoothAdapter,
    private val onMessage: (WaageMessage) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit
) {
    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var targetDevice: BluetoothDevice? = null

    fun connect(device: BluetoothDevice) {
        targetDevice = device
        reconnectJob?.cancel()
        Log.d(TAG, "connect() -> ${device.name ?: "unknown"} / ${device.address}")
        reconnectJob = scope.launch { attemptConnect(maxAttempts = 12, delayMs = 5000L) }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        reconnectJob?.cancel()
        readerJob?.cancel()
        try { socket?.close() } catch (e: Exception) { Log.w(TAG, "Socket close: ${e.message}") }
        socket = null
        onStateChange(ConnectionState.Disconnected)
    }

    private suspend fun attemptConnect(maxAttempts: Int, delayMs: Long) {
        val device = targetDevice ?: return
        for (attempt in 1..maxAttempts) {
            onStateChange(ConnectionState.Connecting(attempt, maxAttempts))
            try {
                try { adapter.cancelDiscovery() } catch (e: SecurityException) { Log.w(TAG, "cancelDiscovery: ${e.message}") }
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                socket = newSocket
                onStateChange(ConnectionState.Connected)
                startReader(newSocket)          // Reader zuerst starten
                delay(300)                      // kurz warten bis Reader bereit ist
                sendSync()                      // erst dann sync senden
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "permission error", e)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                onStateChange(ConnectionState.Error("Bluetooth-Berechtigung fehlt"))
                return
            } catch (e: IOException) {
                Log.w(TAG, "connect failed attempt $attempt: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                if (attempt < maxAttempts) delay(delayMs)
                else onStateChange(ConnectionState.Error("Verbindung fehlgeschlagen nach $maxAttempts Versuchen"))
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error", e)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                onStateChange(ConnectionState.Error("Unerwarteter Bluetooth-Fehler"))
                return
            }
        }
    }

    private fun startReader(s: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val reader = s.inputStream.bufferedReader()
                while (isActive) {
                    val line = reader.readLine() ?: break
                    parseMessage(line)
                }
                socket = null
                onStateChange(ConnectionState.Disconnected)
                scheduleReconnect()
            } catch (e: SecurityException) {
                socket = null
                onStateChange(ConnectionState.Error("Bluetooth-Berechtigung im Reader verloren"))
            } catch (e: IOException) {
                socket = null
                onStateChange(ConnectionState.Disconnected)
                scheduleReconnect()
            } catch (e: Exception) {
                Log.e(TAG, "reader error", e)
                socket = null
                onStateChange(ConnectionState.Error("Lesefehler"))
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        targetDevice?.let {
            reconnectJob = scope.launch {
                delay(2000L)
                attemptConnect(maxAttempts = 12, delayMs = 5000L)
            }
        }
    }

    private fun parseMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.getString("type")) {

                "measurement_batch" -> {
                    val arr = obj.getJSONArray("samples")
                    val samples = (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        BatchSample(
                            weightG      = s.getDouble("w").toFloat(),
                            weightR      = s.optDouble("wR", 0.0).toFloat(),
                            weightM      = s.optDouble("wM", 0.0).toFloat(),
                            weightF      = s.optDouble("wF", 0.0).toFloat(),
                            timestampMs  = s.getLong("ts")
                        )
                    }
                    onMessage(WaageMessage.MeasurementBatch(samples))
                }

                "fft_result" -> {
                    val machineOff = obj.optBoolean("machineOff", false)
                    if (machineOff) {
                        onMessage(WaageMessage.FftData(
                            FftResult(0f, 0f, 0.156f, 20, emptyList(), machineOff = true)
                        ))
                    } else {
                        val binsArr = obj.getJSONArray("bins")
                        val bins = (0 until binsArr.length()).map { binsArr.getInt(it) }
                        onMessage(WaageMessage.FftData(FftResult(
                            peakHz   = obj.getDouble("peakHz").toFloat(),
                            peakAmp  = obj.getDouble("peakAmp").toFloat(),
                            binResHz = obj.getDouble("binRes").toFloat(),
                            fs       = obj.optInt("fs", 20),
                            bins     = bins
                        )))
                    }
                }

                "tare_done"     -> onMessage(WaageMessage.TareDone(
                    channel = obj.optString("ch", ""),
                    offset  = obj.optDouble("offset", 0.0).toFloat()
                ))
                "factor"        -> {
                    val ch = obj.optString("ch", "")
                    if (ch == "ALL") {
                        // Antwort auf get_factor: alle drei Kanäle auf einmal
                        val r = obj.optDouble("factorRear",  -1.0).toFloat()
                        val m = obj.optDouble("factorMid",   -1.0).toFloat()
                        val f = obj.optDouble("factorFront", -1.0).toFloat()
                        if (r > 0f) onMessage(WaageMessage.Factor("R", r))
                        if (m > 0f) onMessage(WaageMessage.Factor("M", m))
                        if (f > 0f) onMessage(WaageMessage.Factor("F", f))
                    } else {
                        onMessage(WaageMessage.Factor(
                            channel = ch,
                            value   = obj.optDouble("value", 0.0).toFloat()
                        ))
                    }
                }
                "sync_done"     -> onMessage(WaageMessage.SyncDone)

                "config", "config_saved" -> onMessage(WaageMessage.Config(
                    sampleRateHz          = obj.optInt("sampleRateHz", 20),
                    publishRateHz         = obj.optInt("publishRateHz", 2),
                    avgSamples            = obj.optInt("avgSamples", 2),
                    offlineBufferSeconds  = obj.optInt("offlineBufferSeconds", 60),
                    offlineBufferCapacity = obj.optInt("offlineBufferCapacity", 1200),
                    displayHz             = obj.optInt("displayHz", 2),
                    deltaDurationMs       = obj.optInt("deltaDurationMs", 2000),
                    deltaTolerance        = obj.optDouble("deltaTolerance", 2.0).toFloat(),
                    factorRear            = obj.optDouble("factorRear",  -1.0).toFloat(),
                    factorMid             = obj.optDouble("factorMid",   -1.0).toFloat(),
                    factorFront           = obj.optDouble("factorFront", -1.0).toFloat()
                ))

                "error" -> onMessage(WaageMessage.Error(obj.optString("msg", "Unbekannter Fehler")))

                else -> Log.w(TAG, "unknown type=${obj.optString("type")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse-Fehler: ${e.message} | json=$json")
        }
    }

    fun sendJson(json: String) {
        scope.launch {
            try {
                socket?.outputStream?.write((json + "\n").toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
            }
        }
    }

    fun sendTare()           = sendJson("""{"type":"tare"}""")
    fun sendCalibrate(g: Float) = sendJson("""{"type":"calibrate","weight":$g}""")
    fun sendGetConfig()      = sendJson("""{"type":"get_config"}""")
    fun sendGetFactor()      = sendJson("""{"type":"get_factor"}""")


    fun sendSetFactor(ch: Char, factor: Float) =
        sendJson("""{"type":"set_factor","ch":"$ch","factor":$factor}""")  // ← NEU
    fun sendSync()           = sendJson("""{"type":"sync","unix":${System.currentTimeMillis()}}""")
    fun sendResetConfig()    = sendJson("""{"type":"reset_config"}""")
    fun sendDeviceConfig(
        publishRateHz: Int,
        avgSamples: Int,
        offlineBufferSeconds: Int,
        displayHz: Int,
        deltaDurationMs: Int,
        deltaTolerance: Float
    ) = sendJson("""{"type":"setconfig","publishRateHz":$publishRateHz,"avgSamples":$avgSamples,"offlineBufferSeconds":$offlineBufferSeconds,"displayHz":$displayHz,"deltaDurationMs":$deltaDurationMs,"deltaTolerance":$deltaTolerance}""")
}