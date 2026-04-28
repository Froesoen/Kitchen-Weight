package com.waage.util

import java.util.Locale

/**
 * Einheitliche Gewichtsformatierung für die gesamte App.
 * ≥ 1000 g oder ≤ -1000 g → kg mit 3 Dezimalstellen
 * Sonst → g mit 1 Dezimalstelle
 */
fun formatWeight(g: Float): String =
    if (g >= 1000f || g <= -1000f)
        String.format(Locale.getDefault(), "%.3f kg", g / 1000f)
    else
        String.format(Locale.getDefault(), "%.1f g", g)