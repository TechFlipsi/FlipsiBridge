package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Zähler-Badge „n / m" (v0.12.0) — Struktur transportiert den Gruppenzustand
 * zusätzlich zu Farbe/Schalter: invasive Gruppe mit aktivem Eintrag = roter Ton.
 */
@Composable
fun CountBadge(enabled: Int, total: Int, invasive: Boolean, modifier: Modifier = Modifier) {
    val all = enabled == total && total > 0
    val fg = when {
        invasive && enabled > 0 -> FlipsiColors.SignalRedText
        all -> FlipsiColors.SignalGreen
        else -> FlipsiColors.TextSecondary
    }
    Text(
        "$enabled / $total",
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier
            .background(fg.copy(alpha = 0.16f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .semantics { contentDescription = "$enabled von $total Fähigkeiten aktiviert" },
    )
}