package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Gruppen-Karte der Fähigkeiten-Zentrale (v0.12.0; PH1-T5: aufklappbar):
 * Kopf mit Gruppen-Logo + Titel + Zähler-Badge (+ Klappe-Pfeil, wenn ein
 * Klappe-Zustand übergeben wird), darunter die Fähigkeits-Zeilen mit
 * sichtbaren Trennern (LineStrong). Die invasive Gruppe trägt einen
 * 1dp-roten Rand — Warnstufe über Form, nicht nur über Farbe (Apple-HIG).
 * expanded/onExpandedChange sind optional: null = immer offen (Standard).
 */
@Composable
fun CapabilityGroupCard(
    title: String,
    caps: List<CapabilityUi>,
    enabledIds: Set<String>,
    onToggle: (CapabilityUi, Boolean) -> Unit,
    @androidx.annotation.DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val invasive = caps.any { it.invasive }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        border = if (invasive) {
            BorderStroke(1.dp, FlipsiColors.SignalRed)
        } else null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        // PH1-T5: Kopf = Touch-Feld, wenn klappt (TalkBack
                        // meldet den Zustand via toggleable, Role.Button).
                        if (onExpandedChange != null) {
                            Modifier.toggleable(
                                value = expanded,
                                role = Role.Button,
                                onValueChange = onExpandedChange,
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(
                        start = 16.dp, end = 16.dp, top = 14.dp,
                        bottom = if (expanded) 6.dp else 14.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResourceIcon(iconRes, tint = FlipsiColors.TextSecondary, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                CountBadge(enabled = caps.count { it.id in enabledIds }, total = caps.size, invasive = invasive)
                if (onExpandedChange != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = FlipsiColors.TextSecondary,
                    )
                }
            }
            // PH1-T5: Zeilen nur im ausgeklappten Zustand.
            if (expanded) {
                caps.forEachIndexed { index, cap ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = FlipsiColors.LineStrong,
                            modifier = Modifier.padding(start = 52.dp),
                        )
                    }
                    CapabilityRow(
                        cap = cap,
                        enabled = cap.id in enabledIds,
                        onToggle = { newValue -> onToggle(cap, newValue) },
                    )
                }
            }
        }
    }
}