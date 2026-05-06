package com.waage.data

import android.content.Context
import android.content.SharedPreferences

// WeightSample speichert jetzt alle vier Gewichtswerte
data class WeightSample(
    val weightG:  Float,
    val weightR:  Float = 0f,   // Kanal hinten
    val weightM:  Float = 0f,   // Kanal mitte
    val weightF:  Float = 0f,   // Kanal vorne
    val timestampMs: Long,
    val synced: Boolean = true
)

enum class TimeRange(val label: String, val seconds: Int) {
    TEN_SEC("10s", 10),
    THIRTY_SEC("30s", 30),
    ONE_MIN("1m", 60),
    FIVE_MIN("5m", 300),
    TEN_MIN("10m", 600),
    SIXTY_MIN("60m", 3600)
}

class WeightBuffer {
    companion object {
        const val MAX_SAMPLES = 7200
    }

    private val buffer          = ArrayDeque<WeightSample>(MAX_SAMPLES)
    // Duplikat-Key = Timestamp + Gewicht (gerundet auf 0.1g)
    private val knownSamples = HashSet<Long>(MAX_SAMPLES * 2)

    private fun sampleKey(sample: WeightSample): Long {
        // Timestamp als Basis, Gewicht in Zehnteln eingebacken
        val wInt = (sample.weightG * 10f).toLong().coerceIn(-99999, 99999)
        return sample.timestampMs * 100000L + wInt + 99999L
    }

    fun add(sample: WeightSample) {
        val key = sampleKey(sample)
        if (key in knownSamples) return
        if (buffer.size >= MAX_SAMPLES) {
            val removed = buffer.removeFirst()
            knownSamples.remove(sampleKey(removed))
        }
        buffer.addLast(sample)
        knownSamples.add(key)
    }

    fun addAll(samples: List<WeightSample>) = samples.forEach { add(it) }

    fun getSamples(range: TimeRange): List<WeightSample> {
        if (buffer.isEmpty()) return emptyList()
        val cutoff = System.currentTimeMillis() - range.seconds * 1000L
        val filtered = buffer.filter { it.timestampMs >= cutoff }
        // Fallback: Wenn der Zeitfilter alle Samples herausfiltert (z.B. ESP-millis()
        // statt Unix-Zeit), die jüngsten Samples relativ zum neuesten Timestamp zeigen
        if (filtered.isEmpty()) {
            val newestTs = buffer.maxOf { it.timestampMs }
            val relativeCutoff = newestTs - range.seconds * 1000L
            return buffer.filter { it.timestampMs >= relativeCutoff }.sortedBy { it.timestampMs }
        }
        return filtered.sortedBy { it.timestampMs }
    }

    fun getLatest(n: Int): List<WeightSample> = buffer.takeLast(n).sortedBy { it.timestampMs }

    data class Stats(
        val min:   Float,
        val max:   Float,
        val avg:   Float,
        val count: Int
    )

    fun getStats(range: TimeRange): Stats? {
        val samples = getSamples(range)
        if (samples.isEmpty()) return null
        val weights = samples.map { it.weightG }
        return Stats(
            min   = weights.minOrNull() ?: 0f,
            max   = weights.maxOrNull() ?: 0f,
            avg   = weights.average().toFloat(),
            count = weights.size
        )
    }

    // Letzte Einzelkanalwerte (aus dem jüngsten Sample)
    fun getLatestChannels(): Triple<Float, Float, Float>? {
        val last = buffer.lastOrNull() ?: return null
        return Triple(last.weightR, last.weightM, last.weightF)
    }

    fun clear() {
        buffer.clear()
        knownSamples.clear()
    }

    fun size() = buffer.size
}

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("waage_settings", Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get()    = prefs.getString("last_device", null)
        set(v)   = prefs.edit().putString("last_device", v).apply()

    var selectedTimeRange: TimeRange
        get()    = TimeRange.valueOf(prefs.getString("time_range", TimeRange.ONE_MIN.name) ?: TimeRange.ONE_MIN.name)
        set(v)   = prefs.edit().putString("time_range", v.name).apply()

    var alarmUpperG: Float
        get()    = prefs.getFloat("alarm_upper", Float.NaN)
        set(v)   = prefs.edit().putFloat("alarm_upper", v).apply()

    var alarmLowerG: Float
        get()    = prefs.getFloat("alarm_lower", Float.NaN)
        set(v)   = prefs.edit().putFloat("alarm_lower", v).apply()

    var alarmMuted: Boolean
        get()    = prefs.getBoolean("alarm_muted", false)
        set(v)   = prefs.edit().putBoolean("alarm_muted", v).apply()

    var measurementName: String
        get()    = prefs.getString("meas_name", "Messung") ?: "Messung"
        set(v)   = prefs.edit().putString("meas_name", v).apply()
}
