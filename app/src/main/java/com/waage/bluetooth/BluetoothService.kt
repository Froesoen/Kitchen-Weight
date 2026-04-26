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

sealed class WaageMessage {
    data class MeasurementBatch(val samples: List<BatchSample>) : WaageMessage()
    data class TareDone(val offset: Float) : WaageMessage()
    data class Factor(val value: Float) : WaageMessage()
    object SyncDone : WaageMessage()
    object NeedSync : WaageMessage()
    data class Error(val message: String) : WaageMessage()
    data class Config(
        val sampleRateHz: Int,
        val publishRateHz: Int,
        val avgSamples: Int,
        val offlineBufferSeconds: Int,
        val offlineBufferCapacity: Int,
        val displayHz: Int
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
            Log.w(TAG, "Socket close failed: ${e.message}")
        }
        socket = null
        onStateChange(ConnectionState.Disconnected)
    }

    private suspend fun attemptConnect(maxAttempts: Int, delayMs: Long) {
        val device = targetDevice ?: return
        for (attempt in 1..maxAttempts) {
            onStateChange(ConnectionState.Connecting(attempt, maxAttempts))
            Log.d(TAG, "connect attempt $attempt/$maxAttempts to ${device.address}")
            try {
                try {
                    adapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    Log.w(TAG, "cancelDiscovery denied: ${e.message}")
                }

                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                Log.d(TAG, "socket created")
                newSocket.connect()
                Log.d(TAG, "socket connected")
                socket = newSocket
                onStateChange(ConnectionState.Connected)
                Log.d(TAG, "connected to ${device.address}")

                sendSync()
                startReader(newSocket)
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "permission error during connect", e)
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                onStateChange(ConnectionState.Error("Bluetooth-Berechtigung fehlt oder wurde entzogen"))
                return
            } catch (e: IOException) {
                Log.w(TAG, "connect failed: ${e.message}")
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                if (attempt < maxAttempts) delay(delayMs)
                else onStateChange(ConnectionState.Error("Verbindung fehlgeschlagen nach $maxAttempts Versuchen"))
            } catch (e: Exception) {
                Log.e(TAG, "unexpected connect error", e)
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
        Log.d(TAG, "reader started")
        readerJob = scope.launch {
            try {
                val reader = s.inputStream.bufferedReader()
                while (isActive) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "RX line=$line")
                    parseMessage(line)
                }
                Log.d(TAG, "reader EOF")
                socket = null
                onStateChange(ConnectionState.Disconnected)
                scheduleReconnect()
            } catch (e: SecurityException) {
                Log.e(TAG, "reader permission error", e)
                socket = null
                onStateChange(ConnectionState.Error("Bluetooth-Berechtigung im Reader verloren"))
            } catch (e: IOException) {
                Log.w(TAG, "reader disconnected: ${e.message}")
                socket = null
                onStateChange(ConnectionState.Disconnected)
                scheduleReconnect()
            } catch (e: Exception) {
                Log.e(TAG, "reader unexpected error", e)
                socket = null
                onStateChange(ConnectionState.Error("Lesefehler in Bluetooth-Verbindung"))
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        targetDevice?.let {
            Log.d(TAG, "scheduleReconnect()")
            reconnectJob = scope.launch {
                delay(2000L)
                attemptConnect(maxAttempts = 12, delayMs = 5000L)
            }
        }
    }

    private fun parseMessage(json: String) {
        try {
            Log.d(TAG, "parseMessage raw=$json")
            val obj = JSONObject(json)
            when (obj.getString("type")) {

                "measurement_batch" -> {
                    val arr = obj.getJSONArray("samples")
                    val samples = mutableListOf<BatchSample>()
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        samples.add(BatchSample(
                            weightG     = s.getDouble("w").toFloat(),
                            timestampMs = s.getLong("ts")
                        ))
                    }
                    Log.d(TAG, "parsed measurement_batch count=${samples.size}")
                    onMessage(WaageMessage.MeasurementBatch(samples))
                }

                "tare_done" -> {
                    val msg = WaageMessage.TareDone(obj.optDouble("offset", 0.0).toFloat())
                    Log.d(TAG, "parsed tare_done offset=${msg.offset}")
                    onMessage(msg)
                }

                "factor" -> {
                    val msg = WaageMessage.Factor(obj.getDouble("value").toFloat())
                    Log.d(TAG, "parsed factor value=${msg.value}")
                    onMessage(msg)
                }

                "sync_done" -> {
                    Log.d(TAG, "parsed sync_done")
                    onMessage(WaageMessage.SyncDone)
                }

                "need_sync" -> {
                    Log.d(TAG, "parsed need_sync")
                    onMessage(WaageMessage.NeedSync)
                }

                "config", "config_saved" -> {
                    val msg = WaageMessage.Config(
                        sampleRateHz         = obj.optInt("sampleRateHz", 0),
                        publishRateHz        = obj.optInt("publishRateHz", 0),
                        avgSamples           = obj.optInt("avgSamples", 0),
                        offlineBufferSeconds = obj.optInt("offlineBufferSeconds", 0),
                        offlineBufferCapacity= obj.optInt("offlineBufferCapacity", 0),
                        displayHz            = obj.optInt("displayHz", 2)
                    )
                    Log.d(TAG, "parsed config srate=${msg.sampleRateHz} prate=${msg.publishRateHz}")
                    onMessage(msg)
                }

                "error" -> {
                    val msg = WaageMessage.Error(obj.optString("msg", "Unbekannter Fehler"))
                    Log.w(TAG, "parsed error msg=${msg.message}")
                    onMessage(msg)
                }

                else -> {
                    Log.w(TAG, "unknown type=${obj.optString("type", "")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse-Fehler: $json -> ${e.message}")
        }
    }

    fun send(json: JSONObject) {
        scope.launch {
            try {
                val currentSocket = socket
                if (currentSocket == null || !currentSocket.isConnected) {
                    Log.w(TAG, "send skipped: not connected json=$json")
                    return@launch
                }
                val payload = json.toString() + "\n"
                Log.d(TAG, "TX payload=$payload")
                currentSocket.outputStream.write(payload.toByteArray())
                currentSocket.outputStream.flush()
                Log.d(TAG, "TX ok type=${json.optString("type", "<missing>")}")
            } catch (e: SecurityException) {
                Log.e(TAG, "send permission error", e)
                onStateChange(ConnectionState.Error("Bluetooth-Berechtigung beim Senden verloren"))
            } catch (e: IOException) {
                Log.w(TAG, "send error: ${e.message}")
                onStateChange(ConnectionState.Disconnected)
                scheduleReconnect()
            } catch (e: Exception) {
                Log.e(TAG, "unexpected send error", e)
            }
        }
    }

    fun sendTare() = send(JSONObject().put("type", "tare"))
    fun sendCalibrate(knownWeightG: Float) = send(JSONObject().put("type", "calibrate").put("weight", knownWeightG))
    fun sendGetFactor() = send(JSONObject().put("type", "get_factor"))
    fun sendGetBuffer() = send(JSONObject().put("type", "get_buffer"))
    fun sendSync() = send(JSONObject().put("type", "sync").put("unix", System.currentTimeMillis()))
    fun sendGetConfig() = send(JSONObject().put("type", "getconfig"))
    fun sendSetConfig(sampleRateHz: Int, publishRateHz: Int, avgSamples: Int, offlineBufferSeconds: Int, displayHz: Int) =
        send(JSONObject()
            .put("type", "setconfig")
            .put("sampleRateHz", sampleRateHz)
            .put("publishRateHz", publishRateHz)
            .put("avgSamples", avgSamples)
            .put("offlineBufferSeconds", offlineBufferSeconds)
            .put("displayHz", displayHz))
    fun sendResetConfig() = send(JSONObject().put("type", "resetconfig"))
    fun isConnected(): Boolean = socket?.isConnected == true
}