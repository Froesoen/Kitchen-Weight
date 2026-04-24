package com.waage.data

import android.content.Context
import android.content.SharedPreferences

data class WeightSample(
    val weightG: Float,
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

    private val buffer = ArrayDeque<WeightSample>(MAX_SAMPLES)

    fun add(sample: WeightSample) {
        if (buffer.size >= MAX_SAMPLES) buffer.removeFirst()
        buffer.addLast(sample)
    }

    fun addAll(samples: List<WeightSample>) {
        samples.forEach { add(it) }
    }

    fun getSamples(range: TimeRange): List<WeightSample> {
        if (buffer.isEmpty()) return emptyList()
        val cutoff = System.currentTimeMillis() - range.seconds * 1000L
        return buffer.filter { it.timestampMs >= cutoff }
    }

    fun getLatest(n: Int): List<WeightSample> = buffer.takeLast(n)

    data class Stats(
        val min: Float,
        val max: Float,
        val avg: Float,
        val count: Int
    )

    fun getStats(range: TimeRange): Stats? {
        val samples = getSamples(range)
        if (samples.isEmpty()) return null
        val weights = samples.map { it.weightG }
        return Stats(
            min = weights.minOrNull() ?: 0f,
            max = weights.maxOrNull() ?: 0f,
            avg = weights.average().toFloat(),
            count = weights.size
        )
    }

    fun clear() = buffer.clear()
    fun size() = buffer.size
}

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("waage_settings", Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString("last_device", null)
        set(v) = prefs.edit().putString("last_device", v).apply()

    var selectedTimeRange: TimeRange
        get() = TimeRange.valueOf(
            prefs.getString("time_range", TimeRange.ONE_MIN.name) ?: TimeRange.ONE_MIN.name
        )
        set(v) = prefs.edit().putString("time_range", v.name).apply()

    var alarmUpperG: Float
        get() = prefs.getFloat("alarm_upper", 0f)
        set(v) = prefs.edit().putFloat("alarm_upper", v).apply()

    var alarmLowerG: Float
        get() = prefs.getFloat("alarm_lower", 0f)
        set(v) = prefs.edit().putFloat("alarm_lower", v).apply()

    var alarmMuted: Boolean
        get() = prefs.getBoolean("alarm_muted", false)
        set(v) = prefs.edit().putBoolean("alarm_muted", v).apply()

    var measurementName: String
        get() = prefs.getString("meas_name", "Messung") ?: "Messung"
        set(v) = prefs.edit().putString("meas_name", v).apply()
}
