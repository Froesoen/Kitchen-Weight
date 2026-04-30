package com.waage.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CsvExporter(private val context: Context) {

    private val dateFormat  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileFormat  = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())

    // CSV erstellen und Share-Sheet öffnen
    fun exportAndShare(
        buffer: WeightBuffer,
        range: TimeRange,
        measurementName: String,
        calibrationFactor: Float
    ) {
        val samples = buffer.getSamples(range)
        if (samples.isEmpty()) return

        val stats = buffer.getStats(range)
        val now   = Date()

        val csv = buildString {
            // Header-Block
            appendLine("Messung;$measurementName")
            appendLine("Exportiert;${dateFormat.format(now)}")
            appendLine("Zeitraum;${range.label}")
            appendLine("Kalibrierfaktor;$calibrationFactor")
            appendLine("Anzahl Samples;${samples.size}")
            if (stats != null) {
                appendLine("Min;${"%.2f".format(stats.min)} g")
                appendLine("Max;${"%.2f".format(stats.max)} g")
                appendLine("Durchschnitt;${"%.2f".format(stats.avg)} g")
            }
            appendLine()

            // Daten
            appendLine("Zeitstempel;Gewicht_g;Gewicht_kg")
            samples.forEach { s ->
                val ts = dateFormat.format(Date(s.timestampMs))
                val kg = s.weightG / 1000f
                appendLine("$ts;${"%.2f".format(s.weightG)};${"%.4f".format(kg)}")
            }
        }

        // Datei schreiben
        val filename = "${measurementName.replace(" ", "_")}_${fileFormat.format(now)}.csv"
        val file = File(context.cacheDir, filename)
        file.writeText(csv)

        // Share-Sheet öffnen
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type     = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, measurementName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "CSV exportieren").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
