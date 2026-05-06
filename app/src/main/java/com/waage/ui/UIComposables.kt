package com.waage.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waage.bluetooth.ConnectionState
import com.waage.bluetooth.FftResult
import com.waage.data.TimeRange
import com.waage.data.WeightBuffer
import com.waage.data.WeightSample
import com.waage.util.formatWeight
import com.waage.viewmodel.WeightColor
import java.util.Locale
import kotlin.math.roundToInt

// ── KitchenAid-Stufen ─────────────────────────────────────────────────────────
private data class KaSpeed(val label: String, val hz: Float, val color: Color)

private val KA_SPEEDS = listOf(
    KaSpeed("1",  0.97f, Color(0xFF80CBC4)),
    KaSpeed("2",  1.53f, Color(0xFF4DB6AC)),
    KaSpeed("3",  2.01f, Color(0xFF26A69A)),
    KaSpeed("4",  2.58f, Color(0xFF00897B)),
    KaSpeed("6",  3.24f, Color(0xFFFFA726)),
    KaSpeed("8",  3.98f, Color(0xFFFF7043)),
    KaSpeed("10", 5.00f, Color(0xFFEF5350))
)
private const val KA_TOLERANCE_HZ = 0.15f

// Farben für die drei Wägezellen-Kanäle (konsistent in Graph + WeightDisplay)
val ChannelColorRear  = Color(0xFF00E5FF)  // Cyan    – Hinten
val ChannelColorMid   = Color(0xFFFFD740)  // Amber   – Mitte
val ChannelColorFront = Color(0xFFFF40FF)  // Magenta – Vorne
// ── StatusBar ─────────────────────────────────────────────────────────────────
@Composable
fun StatusBar(
    state: ConnectionState,
    lastWeight: Float,
    modifier: Modifier = Modifier
) {
    val (color, label) = when (state) {
        is ConnectionState.Connected    -> Color(0xFF4CAF50) to "Verbunden"
        is ConnectionState.Connecting   -> Color(0xFFFFC107) to "Verbinde… (${state.attempt}/${state.maxAttempts})"
        is ConnectionState.Disconnected -> Color(0xFFF44336) to "Getrennt"
        is ConnectionState.Error        -> Color(0xFFF44336) to "Fehler: ${state.message}"
    }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "alpha"
    )
    val dotAlpha = if (state is ConnectionState.Connecting) alpha else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color.copy(alpha = dotAlpha), radius = size.minDimension / 2)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

// ── WeightDisplay ─────────────────────────────────────────────────────────────
@Composable
fun WeightDisplay(
    formatted:      String,
    stats:          WeightBuffer.Stats?,
    weightColor:    WeightColor,
    alarmTriggered: Boolean,
    alarmMuted:     Boolean,
    onMuteAlarm:    () -> Unit,
    // Einzelkanäle
    weightRearG:    Float,
    weightMidG:     Float,
    weightFrontG:   Float,
    modifier:       Modifier = Modifier
) {
    val targetColor = when (weightColor) {
        WeightColor.RED   -> Color(0xFFF44336)
        WeightColor.GREEN -> Color(0xFF4CAF50)
        WeightColor.WHITE -> Color.White
    }
    val textColor by animateColorAsState(targetColor, tween(300), label = "alarm_color")

    Column(
        modifier            = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gesamtgewicht
        Text(
            text       = formatted,
            fontSize   = 52.sp,
            fontWeight = FontWeight.Bold,
            color      = textColor,
            textAlign  = TextAlign.Center,
            modifier   = Modifier.padding(vertical = 8.dp)
        )

        // Kanalgewichte (hinten / mitte / vorne)
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ChannelChip("Hinten",  weightRearG,  ChannelColorRear)
            ChannelChip("Mitte",   weightMidG,   ChannelColorMid)
            ChannelChip("Vorne",   weightFrontG, ChannelColorFront)
        }

        if (alarmTriggered) {
            TextButton(onClick = onMuteAlarm) {
                Text(
                    text  = if (alarmMuted) "🔇 Stummgeschaltet" else "🔔 Alarm! Stummschalten",
                    color = Color(0xFFFFC107)
                )
            }
        }

        if (stats != null) {
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip("↓ Min", formatWeight(stats.min))
                StatChip("Ø Avg", formatWeight(stats.avg))
                StatChip("↑ Max", formatWeight(stats.max))
            }
        }
    }
}

@Composable
private fun ChannelChip(label: String, valueG: Float, channelColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 9.sp, color = channelColor.copy(alpha = 0.7f))
        Text(
            text       = formatWeight(valueG),
            fontSize   = 12.sp,
            color      = channelColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, color = Color.Gray)
        Text(text = value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

// ── WeightGraph ───────────────────────────────────────────────────────────────
@Composable
fun WeightGraph(
    samples:         List<WeightSample>,
    selectedRange:   TimeRange,
    alarmUpperG:     Float,
    alarmLowerG:     Float,
    stats:           WeightBuffer.Stats?,
    onRangeSelected: (TimeRange) -> Unit,
    modifier:        Modifier = Modifier
) {
    Column(modifier = modifier) {
        TimeRangeSelector(selected = selectedRange, onSelected = onRangeSelected)
        Spacer(modifier = Modifier.height(8.dp))

        if (samples.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                val w           = size.width
                val h           = size.height
                val leftPad     = 52f
                val bottomPad   = 20f
                val rightPad    = 8f
                val plotW       = w - leftPad - rightPad
                val plotH       = h - bottomPad
                val plotLeft    = leftPad
                val plotBottom  = h - bottomPad

                val weights     = samples.map { it.weightG }
                var minVal      = weights.minOrNull() ?: 0f
                var maxVal      = weights.maxOrNull() ?: 0f
                val margin      = maxOf((maxVal - minVal) * 0.1f, 1f)
                minVal -= margin; maxVal += margin
                val range       = (maxVal - minVal).takeIf { it != 0f } ?: 1f

                val nowMs         = System.currentTimeMillis()
                val rangeMs       = selectedRange.seconds * 1000L
                // Wenn die neuesten Samples jünger als "jetzt" sind (korrekte Unix-Zeit),
                // normales Fenster. Sonst: Fenster relativ zum neuesten Sample aufspannen.
                val newestSampleTs = samples.maxOf { it.timestampMs }
                val windowEndMs   = maxOf(nowMs, newestSampleTs)
                val windowStartMs = windowEndMs - rangeMs

                fun yOf(v: Float) = plotBottom - ((v - minVal) / range) * plotH
                fun xOf(tsMs: Long): Float {
                    val frac = ((tsMs - windowStartMs).toFloat() / rangeMs.toFloat()).coerceIn(0f, 1f)
                    return plotLeft + frac * plotW
                }

                // 1. Alarm-Zonen
                val hasUpper = !alarmUpperG.isNaN()
                val hasLower = !alarmLowerG.isNaN()
                if (hasUpper || hasLower) {
                    val yUpper = if (hasUpper) yOf(alarmUpperG).coerceIn(0f, plotBottom) else 0f
                    val yLower = if (hasLower) yOf(alarmLowerG).coerceIn(0f, plotBottom) else plotBottom
                    if (hasUpper && alarmUpperG < maxVal)
                        drawRect(Color(0xFFF44336).copy(alpha = 0.08f), Offset(plotLeft, 0f), Size(plotW, yUpper))
                    val greenTop    = if (hasUpper) yUpper else 0f
                    val greenBottom = if (hasLower) yLower else plotBottom
                    if (greenBottom > greenTop)
                        drawRect(Color(0xFF4CAF50).copy(alpha = 0.07f), Offset(plotLeft, greenTop), Size(plotW, greenBottom - greenTop))
                    if (hasLower && alarmLowerG > minVal)
                        drawRect(Color(0xFFF44336).copy(alpha = 0.08f), Offset(plotLeft, yLower), Size(plotW, plotBottom - yLower))
                }

                // 2. Gridlines + Y-Labels
                val yTickCount  = 4
                val labelPaint  = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY; textSize = 22f
                    textAlign = android.graphics.Paint.Align.RIGHT; isAntiAlias = true
                }
                for (i in 0..yTickCount) {
                    val frac = i.toFloat() / yTickCount
                    val v    = minVal + frac * (maxVal - minVal)
                    val y    = yOf(v)
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(plotLeft, y), Offset(plotLeft + plotW, y), 1f)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            if (kotlin.math.abs(v) < 1000f)
                                String.format(Locale.getDefault(), "%.0f", v)
                            else
                                String.format(Locale.getDefault(), "%.1fk", v / 1000f),
                            plotLeft - 4f, y + 7f, labelPaint
                        )
                    }
                }

                // 3. Alarm-Linien
                if (hasUpper && alarmUpperG in minVal..maxVal) {
                    val y = yOf(alarmUpperG)
                    drawLine(Color(0xFFF44336), Offset(plotLeft, y), Offset(plotLeft + plotW, y), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f)))
                }
                if (hasLower && alarmLowerG in minVal..maxVal) {
                    val y = yOf(alarmLowerG)
                    drawLine(Color(0xFFF44336), Offset(plotLeft, y), Offset(plotLeft + plotW, y), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f)))
                }

                // 4. Kanallinien (R/M/F) – durchgehend, gut sichtbar
                val channelDefs = listOf(
                    ChannelColorRear  to { s: WeightSample -> s.weightR },
                    ChannelColorMid   to { s: WeightSample -> s.weightM },
                    ChannelColorFront to { s: WeightSample -> s.weightF }
                )
                channelDefs.forEach { (color, getter) ->
                    val channelWeights = samples.map { getter(it) }
                    if (channelWeights.any { it != 0f }) {
                        val channelPath = Path()
                        samples.forEachIndexed { i, s ->
                            val x = xOf(s.timestampMs)
                            val y = yOf(getter(s))
                            if (i == 0) channelPath.moveTo(x, y) else channelPath.lineTo(x, y)
                        }
                        drawPath(channelPath, color.copy(alpha = 0.85f),
                            style = Stroke(width = 1.8f))
                    }
                }

                // 5. Avg-Linie
                if (stats != null && stats.avg in minVal..maxVal) {
                    val y = yOf(stats.avg)
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(plotLeft, y), Offset(plotLeft + plotW, y), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                    val avgPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY; textSize = 22f
                        textAlign = android.graphics.Paint.Align.LEFT; isAntiAlias = true
                    }
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText("Ø ${formatWeight(stats.avg)}", plotLeft + 2f, y - 4f, avgPaint)
                    }
                }

                // 6. X-Achse + Ticks
                drawLine(Color.Gray.copy(alpha = 0.4f), Offset(plotLeft, plotBottom), Offset(plotLeft + plotW, plotBottom), 1f)
                val useMinutes = selectedRange.seconds > 60
                val xTickCount = 5
                val xTickPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY; textSize = 22f
                    textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
                }
                drawIntoCanvas { canvas ->
                    for (t in 0 until xTickCount) {
                        val frac  = t.toFloat() / (xTickCount - 1)
                        val tickX = plotLeft + frac * plotW
                        val agoMs = ((1f - frac) * rangeMs).toLong()
                        val lbl   = if (agoMs == 0L) "jetzt"
                        else if (useMinutes) "-${agoMs / 60000}m"
                        else "-${agoMs / 1000}s"
                        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(tickX, plotBottom), Offset(tickX, plotBottom + 4f), 1f)
                        canvas.nativeCanvas.drawText(lbl, tickX, h - 2f, xTickPaint)
                    }
                }

                // 7. Hauptkurve (Gesamt – grün, durchgehend)
                val path = Path()
                samples.forEachIndexed { i, s ->
                    val x = xOf(s.timestampMs); val y = yOf(s.weightG)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF00E676), style = Stroke(width = 2.5f))

                // 8. Peak-Punkt (gelb)
                val peakIdx = weights.indices.maxByOrNull { weights[it] } ?: return@Canvas
                drawCircle(Color(0xFFFFC107), radius = 5f,
                    center = Offset(xOf(samples[peakIdx].timestampMs), yOf(weights[peakIdx])))

                // 9. Aktueller-Wert-Punkt
                val lastIdx = samples.size - 1
                val lastX   = xOf(samples[lastIdx].timestampMs)
                val lastY   = yOf(weights[lastIdx])
                drawLine(Color.White.copy(alpha = 0.3f), Offset(lastX, 0f), Offset(lastX, plotBottom), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                drawCircle(Color.White, radius = 5f, center = Offset(lastX, lastY))
                drawCircle(Color(0xFF121212), radius = 3f, center = Offset(lastX, lastY))

                // 10. Aktueller-Wert-Label
                val curPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE; textSize = 26f
                    textAlign = android.graphics.Paint.Align.RIGHT; isAntiAlias = true; isFakeBoldText = true
                }
                drawIntoCanvas { canvas ->
                    val labelY = (lastY - 10f).coerceAtLeast(28f)
                    canvas.nativeCanvas.drawText(formatWeight(weights[lastIdx]), lastX - 6f, labelY, curPaint)
                }
            }
        } else {
            Box(
                modifier         = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) { Text("Sammle Daten…", color = Color.Gray) }
        }
    }
}

// ── TimeRangeSelector ─────────────────────────────────────────────────────────
@Composable
fun TimeRangeSelector(selected: TimeRange, onSelected: (TimeRange) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TimeRange.values().forEach { range ->
            val isSelected = range == selected
            Button(
                onClick  = { onSelected(range) },
                modifier = Modifier.weight(1f).height(32.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) Color(0xFF4CAF50) else Color(0xFF2E2E2E),
                    contentColor   = Color.White
                ),
                contentPadding = PaddingValues(0.dp)
            ) { Text(text = range.label, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

// ── FftSpectrumCard ───────────────────────────────────────────────────────────
@Composable
fun FftSpectrumCard(fft: FftResult, modifier: Modifier = Modifier) {
    val detectedSpeed = if (fft.machineOff) null else detectKaSpeed(fft)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column {
                Text("FFT-Spektrum", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (fft.machineOff) {
                    Text("Maschine aus", color = Color(0xFF888888), fontSize = 12.sp)
                } else {
                    Text(
                        "Peak: ${String.format(Locale.getDefault(), "%.2f", fft.peakHz)} Hz",
                        color = Color(0xFFFFC107), fontSize = 12.sp
                    )
                }
            }
            if (fft.machineOff) {
                Text("⏸", fontSize = 20.sp, color = Color(0xFF555555))
            } else if (detectedSpeed != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("KitchenAid", color = Color.Gray, fontSize = 10.sp)
                    Text("Stufe ${detectedSpeed.label}", color = detectedSpeed.color,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Spektrum-Canvas — leer wenn Maschine aus
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val w          = size.width
            val h          = size.height
            val axisH      = 18f
            val labelTopH  = 14f
            val barAreaH   = h - axisH - labelTopH
            val barAreaTop = labelTopH

            if (fft.machineOff) {
                // Leeres Spektrum
                drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, barAreaTop + barAreaH), Offset(w, barAreaTop + barAreaH), 1f)
                return@Canvas
            }

            val displayBins = fft.bins.drop(1)
            val numBins     = displayBins.size
            if (numBins == 0) return@Canvas

            val maxAmp = displayBins.maxOrNull()?.takeIf { it > 0 } ?: 1
            val barW   = w / numBins

            for (level in listOf(0.25f, 0.5f, 0.75f)) {
                val y = barAreaTop + barAreaH * (1f - level)
                drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y), 1f)
            }

            displayBins.forEachIndexed { i, amp ->
                val barH     = (amp.toFloat() / maxAmp) * barAreaH
                val left     = i * barW
                val top      = barAreaTop + barAreaH - barH
                val ratio    = amp.toFloat() / maxAmp
                val barColor = lerpColor(Color(0xFF1B5E20), Color(0xFF76FF03), ratio)
                    .let { if (ratio > 0.75f) lerpColor(it, Color(0xFFFFC107), (ratio - 0.75f) * 4f) else it }
                drawRect(barColor, topLeft = Offset(left + 1f, top), size = Size(maxOf(barW - 2f, 1f), barH))
            }

            val peakBin = displayBins.indices.maxByOrNull { displayBins[it] }
            if (peakBin != null) {
                val left = peakBin * barW
                val barH = (displayBins[peakBin].toFloat() / maxAmp) * barAreaH
                drawRect(Color(0xFFFFC107).copy(alpha = 0.35f),
                    topLeft = Offset(left, barAreaTop), size = Size(barW, barAreaH))
            }

            val binResHz  = fft.binResHz.takeIf { it > 0f } ?: 0.1563f
            val maxFreqHz = binResHz * numBins

            KA_SPEEDS.forEach { ka ->
                if (ka.hz > maxFreqHz) return@forEach
                val binIdx = (ka.hz / binResHz).roundToInt() - 1
                if (binIdx < 0 || binIdx >= numBins) return@forEach
                val x = (binIdx + 0.5f) * barW
                drawLine(ka.color.copy(alpha = 0.80f), Offset(x, barAreaTop), Offset(x, barAreaTop + barAreaH),
                    1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
                val labelPaint = android.graphics.Paint().apply {
                    color = ka.color.toArgb(); textSize = 22f
                    textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true
                }
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(ka.label, x, labelTopH - 2f, labelPaint) }
            }

            drawLine(Color.Gray.copy(alpha = 0.5f), Offset(0f, barAreaTop + barAreaH), Offset(w, barAreaTop + barAreaH), 1f)

            val axisPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.GRAY; textSize = 20f
                textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }
            val tickCount = 5
            drawIntoCanvas { canvas ->
                for (t in 0 until tickCount) {
                    val frac   = t.toFloat() / (tickCount - 1)
                    val tickHz = frac * maxFreqHz
                    val tickX  = frac * w
                    val lbl    = if (tickHz < 1f) String.format(Locale.getDefault(), "%.1f", tickHz)
                    else String.format(Locale.getDefault(), "%.0f", tickHz) + "Hz"
                    canvas.nativeCanvas.drawText(lbl, tickX, h - 2f, axisPaint)
                }
            }
        }
    }
}

// ── Hilfsfunktionen ───────────────────────────────────────────────────────────
private fun detectKaSpeed(fft: FftResult): KaSpeed? =
    KA_SPEEDS.firstOrNull { kotlin.math.abs(it.hz - fft.peakHz) <= KA_TOLERANCE_HZ }

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val ct = t.coerceIn(0f, 1f)
    return Color(
        red   = a.red   + (b.red   - a.red)   * ct,
        green = a.green + (b.green - a.green)  * ct,
        blue  = a.blue  + (b.blue  - a.blue)   * ct,
        alpha = a.alpha + (b.alpha - a.alpha)  * ct
    )
}

private fun Color.toArgb(): Int {
    val r = (red   * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue  * 255).roundToInt()
    val a = (alpha * 255).roundToInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
