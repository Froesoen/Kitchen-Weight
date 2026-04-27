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

data class BatchSample(val weightG: Float, val timestampMs: Long)

data class FftResult(
    val peakHz: Float,
    val peakAmp: Float,
    val binResHz: Float,
    val fs: Int,
    val bins: List<Int>
)

sealed class WaageMessage {
    data class MeasurementBatch(val samples: List<BatchSample>) : WaageMessage()
    data class TareDone(val offset: Float) : WaageMessage()
    data class Factor(val value: Float) : WaageMessage()
    data class FftData(val result: FftResult) : WaageMessage()
    object SyncDone : WaageMessage()
    object NeedSync : WaageMessage()
    data class Error(val message: String) : WaageMessage()
    data class Config(
        val sampleRateHz: Int,
        val publishRateHz: Int,
        val avgSamples: Int,
        val offlineBufferSeconds: Int,
        val offlineBufferCapacity: Int,
        val displayHz: Int,
        val calibrationFactor: Float = -1f   // ← NEU; -1 = nicht enthalten
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
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Socket close: ${e.message}")
        }
        socket = null
        onStateChange(ConnectionState.Disconnected)
    }

    private suspend fun attemptConnect(maxAttempts: Int, delayMs: Long) {
        val device = targetDevice ?: return
        for (attempt in 1..maxAttempts) {
            onStateChange(ConnectionState.Connecting(attempt, maxAttempts))
            try {
                try {
                    adapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    Log.w(TAG, "cancelDiscovery: ${e.message}")
                }

                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                socket = newSocket
                onStateChange(ConnectionState.Connected)
                sendSync()
                startReader(newSocket)
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "permission error", e)
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                onStateChange(ConnectionState.Error("Bluetooth-Berechtigung fehlt"))
                return
            } catch (e: IOException) {
                Log.w(TAG, "connect failed attempt $attempt: ${e.message}")
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                if (attempt < maxAttempts) delay(delayMs)
                else onStateChange(ConnectionState.Error("Verbindung fehlgeschlagen nach $maxAttempts Versuchen"))
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error", e)
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
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
                            weightG = s.getDouble("w").toFloat(),
                            timestampMs = s.getLong("ts")
                        )
                    }
                    onMessage(WaageMessage.MeasurementBatch(samples))
                }

                "fft_result" -> {
                    val binsArr = obj.getJSONArray("bins")
                    val bins = (0 until binsArr.length()).map { binsArr.getInt(it) }
                    val result = FftResult(
                        peakHz = obj.getDouble("peakHz").toFloat(),
                        peakAmp = obj.getDouble("peakAmp").toFloat(),
                        binResHz = obj.getDouble("binRes").toFloat(),
                        fs = obj.optInt("fs", 20),
                        bins = bins
                    )
                    Log.d(TAG, "fft_result peakHz=${result.peakHz} bins=${bins.size}")
                    onMessage(WaageMessage.FftData(result))
                }

                "tare_done" -> {
                    onMessage(WaageMessage.TareDone(obj.optDouble("offset", 0.0).toFloat()))
                }

                "factor" -> {
                    onMessage(WaageMessage.Factor(obj.getDouble("value").toFloat()))
                }

                "sync_done" -> {
                    onMessage(WaageMessage.SyncDone)
                }

                "need_sync" -> {
                    onMessage(WaageMessage.NeedSync)
                }

                "config", "config_saved" -> {
                    onMessage(
                        WaageMessage.Config(
                            sampleRateHz = obj.optInt("sampleRateHz", 20),
                            publishRateHz = obj.optInt("publishRateHz", 2),
                            avgSamples = obj.optInt("avgSamples", 2),
                            offlineBufferSeconds = obj.optInt("offlineBufferSeconds", 60),
                            offlineBufferCapacity = obj.optInt("offlineBufferCapacity", 1200),
                            displayHz = obj.optInt("displayHz", 2),
                            calibrationFactor = obj.optDouble("calibrationFactor", -1.0).toFloat()  // ← NEU
                        )
                }

                "error" -> {
                    onMessage(WaageMessage.Error(obj.optString("msg", "Unbekannter Fehler")))
                }

                else -> {
                    Log.w(TAG, "unknown type=${obj.optString("type")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse-Fehler: ${e.message} | json=$json")
        }
    }

    fun send(json: JSONObject) {
        scope.launch {
            try {
                val s = socket ?: return@launch
                val payload = json.toString() + "\n"
                s.outputStream.write(payload.toByteArray())
                s.outputStream.flush()
                Log.d(TAG, "TX type=${json.optString("type")}")
            } catch (e: SecurityException) {
                Log.e(TAG, "send permission error", e)
            } catch (e: IOException) {
                Log.w(TAG, "send IO error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "send error", e)
            }
        }
    }

    fun sendTare() = send(JSONObject().put("type", "tare"))
    fun sendGetFactor() = send(JSONObject().put("type", "get_factor"))
    fun sendGetConfig() = send(JSONObject().put("type", "getconfig"))
    fun sendResetConfig() = send(JSONObject().put("type", "resetconfig"))

    fun sendCalibrate(knownWeightG: Float) =
        send(
            JSONObject()
                .put("type", "calibrate")
                .put("weight", knownWeightG)
        )

    fun sendSync() {
        val unix = System.currentTimeMillis()
        send(
            JSONObject()
                .put("type", "sync")
                .put("unix", unix)
        )
        Log.d(TAG, "sendSync unix=$unix")
    }

    fun sendDeviceConfig(
        publishRateHz: Int,
        avgSamples: Int,
        offlineBufferSeconds: Int,
        displayHz: Int
    ) = send(
        JSONObject()
            .put("type", "setconfig")
            .put("publishRateHz", publishRateHz)
            .put("avgSamples", avgSamples)
            .put("offlineBufferSeconds", offlineBufferSeconds)
            .put("displayHz", displayHz)
    )
}
