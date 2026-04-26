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
import com.waage.data.TimeRange
import com.waage.data.WeightBuffer
import com.waage.data.WeightSample
import com.waage.viewmodel.WeightColor
import java.util.Locale

@Composable
fun StatusBar(state: ConnectionState, lastWeight: Float, modifier: Modifier = Modifier) {
    val (color, label) = when (state) {
        is ConnectionState.Connected -> Color(0xFF4CAF50) to "Verbunden"
        is ConnectionState.Connecting -> Color(0xFFFFC107) to "Verbinde... (${state.attempt}/${state.maxAttempts})"
        is ConnectionState.Disconnected -> Color(0xFFF44336) to "Getrennt"
        is ConnectionState.Error -> Color(0xFFF44336) to "Fehler: ${state.message}"
    }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "alpha"
    )
    val dotAlpha = if (state is ConnectionState.Connecting) alpha else 1f
    Row(modifier = modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = color.copy(alpha = dotAlpha), radius = size.minDimension / 2) }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun WeightDisplay(formatted: String, stats: WeightBuffer.Stats?, weightColor: WeightColor, alarmTriggered: Boolean, alarmMuted: Boolean, onMuteAlarm: () -> Unit, modifier: Modifier = Modifier) {
    val targetColor = when (weightColor) {
        WeightColor.RED   -> Color(0xFFF44336)
        WeightColor.GREEN -> Color(0xFF4CAF50)
        WeightColor.WHITE -> Color.White
    }
    val textColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(300), label = "alarm_color")
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = formatted, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = textColor, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        if (alarmTriggered) {
            TextButton(onClick = onMuteAlarm) {
                Text(
                    text = if (alarmMuted) "🔇 Stummgeschaltet" else "🔔 Alarm! Stummschalten",
                    color = Color(0xFFFFC107)
                )
            }
        }
        if (stats != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip("↓ Min", formatG(stats.min))
                StatChip("Ø Avg", formatG(stats.avg))
                StatChip("↑ Max", formatG(stats.max))
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, color = Color.Gray)
        Text(text = value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun WeightGraph(
    samples: List<WeightSample>,
    selectedRange: TimeRange,
    stats: WeightBuffer.Stats?,
    alarmUpperG: Float,
    alarmLowerG: Float,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        TimeRangeSelector(selected = selectedRange, onSelected = onRangeSelected)
        Spacer(modifier = Modifier.height(8.dp))

        if (samples.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 8.dp)
            ) {
                val w = size.width
                val h = size.height

                val leftPad = 72f
                val rightPad = 12f
                val topPad = 12f
                val bottomPad = 28f

                val plotLeft = leftPad
                val plotRight = w - rightPad
                val plotTop = topPad
                val plotBottom = h - bottomPad
                val plotWidth = plotRight - plotLeft
                val plotHeight = plotBottom - plotTop

                val weights = samples.map { it.weightG }
                var minVal = weights.minOrNull() ?: 0f
                var maxVal = weights.maxOrNull() ?: 0f

                if (alarmLowerG > 0f) minVal = minOf(minVal, alarmLowerG)
                if (alarmUpperG > 0f) maxVal = maxOf(maxVal, alarmUpperG)
                stats?.let {
                    minVal = minOf(minVal, it.avg)
                    maxVal = maxOf(maxVal, it.avg)
                }

                val margin = maxOf((maxVal - minVal) * 0.12f, 1f)
                minVal -= margin
                maxVal += margin
                val range = (maxVal - minVal).takeIf { it > 0f } ?: 1f

                fun yFor(value: Float): Float =
                    plotBottom - ((value - minVal) / range) * plotHeight

                fun xFor(index: Int): Float =
                    plotLeft + index.toFloat() / (samples.size - 1).coerceAtLeast(1) * plotWidth

                // Hintergrund Plot
                drawRect(
                    color = Color(0xFF1A1A1A),
                    topLeft = Offset(plotLeft, plotTop),
                    size = androidx.compose.ui.geometry.Size(plotWidth, plotHeight)
                )

                // Alarmbereiche
                if (alarmLowerG > 0f) {
                    val y = yFor(alarmLowerG)
                    drawRect(
                        color = Color(0xFF2196F3).copy(alpha = 0.12f),
                        topLeft = Offset(plotLeft, y),
                        size = androidx.compose.ui.geometry.Size(plotWidth, plotBottom - y)
                    )
                }

                if (alarmUpperG > 0f) {
                    val y = yFor(alarmUpperG)
                    drawRect(
                        color = Color(0xFFF44336).copy(alpha = 0.12f),
                        topLeft = Offset(plotLeft, plotTop),
                        size = androidx.compose.ui.geometry.Size(plotWidth, y - plotTop)
                    )
                }

                // Horizontale Gridlines + Y-Labels
                val gridColor = Color.Gray.copy(alpha = 0.25f)
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.LTGRAY
                    textSize = 28f
                    textAlign = android.graphics.Paint.Align.RIGHT
                    isAntiAlias = true
                }

                val yTicks = listOf(
                    maxVal,
                    maxVal - range * 0.25f,
                    maxVal - range * 0.5f,
                    maxVal - range * 0.75f,
                    minVal
                )

                yTicks.forEach { value ->
                    val y = yFor(value)
                    drawLine(
                        color = gridColor,
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1f
                    )
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            formatG(value),
                            plotLeft - 8f,
                            y + 8f,
                            labelPaint
                        )
                    }
                }

                // Null-Linie
                if (0f in minVal..maxVal) {
                    val y = yFor(0f)
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.5f),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                }

                // Alarm-Linien
                if (alarmUpperG > 0f && alarmUpperG in minVal..maxVal) {
                    val y = yFor(alarmUpperG)
                    drawLine(
                        color = Color(0xFFF44336),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f))
                    )
                }

                if (alarmLowerG > 0f && alarmLowerG in minVal..maxVal) {
                    val y = yFor(alarmLowerG)
                    drawLine(
                        color = Color(0xFF2196F3),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f))
                    )
                }

                // Durchschnittslinie
                stats?.let {
                    if (it.avg in minVal..maxVal) {
                        val y = yFor(it.avg)
                        drawLine(
                            color = Color(0xFFFFC107),
                            start = Offset(plotLeft, y),
                            end = Offset(plotRight, y),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f))
                        )
                    }
                }

                // Gewichtskurve
                val path = Path()
                samples.forEachIndexed { i, sample ->
                    val x = xFor(i)
                    val y = yFor(sample.weightG)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF4CAF50),
                    style = Stroke(width = 2.5f)
                )

                // Letzten Punkt markieren
                val last = samples.last()
                drawCircle(
                    color = Color(0xFF4CAF50),
                    radius = 5f,
                    center = Offset(plotRight, yFor(last.weightG))
                )

                // Plot-Rahmen
                drawRect(
                    color = Color.Gray.copy(alpha = 0.35f),
                    topLeft = Offset(plotLeft, plotTop),
                    size = androidx.compose.ui.geometry.Size(plotWidth, plotHeight),
                    style = Stroke(width = 1f)
                )

                // X-Achsenbeschriftung
                val xPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.LTGRAY
                    textSize = 26f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }

                val totalSec = selectedRange.seconds
                fun secLabel(s: Int): String = if (s >= 60) "${s / 60} min" else "${s}s"
                val leftLabel = "- ${secLabel(totalSec)}"
                val centerLabel = "- ${secLabel(totalSec / 2)}"
                val rightLabel = "jetzt"

                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(leftLabel, plotLeft, h - 4f, xPaint)
                    canvas.nativeCanvas.drawText(centerLabel, plotLeft + plotWidth / 2f, h - 4f, xPaint)
                    canvas.nativeCanvas.drawText(rightLabel, plotRight, h - 4f, xPaint)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Sammle Daten...", color = Color.Gray)
            }
        }
    }
}

@Composable
fun TimeRangeSelector(selected: TimeRange, onSelected: (TimeRange) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TimeRange.values().forEach { range ->
            val isSelected = range == selected
            Button(onClick = { onSelected(range) }, modifier = Modifier.weight(1f).height(32.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color(0xFF4CAF50) else Color(0xFF2E2E2E), contentColor = if (isSelected) Color.Black else Color.White), contentPadding = PaddingValues(0.dp)) {
                Text(text = range.label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun formatG(g: Float): String = if (g >= 1000f || g <= -1000f) String.format(Locale.getDefault(), "%.2f kg", g / 1000f) else String.format(Locale.getDefault(), "%.1f g", g)
