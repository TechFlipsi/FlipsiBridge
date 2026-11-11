package com.hermesandroid.bridge.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.ResourceIcon

/**
 * Welle 13 / P2 (Settings-Redesign): die ausklappbare Settings-Karte.
 *
 * Spacing-Regelwerk des Redesigns (QA F — konstante Abstände):
 *  - SEKTION-Abstand = 16 dp (steht im LazyColumn von SettingsHome).
 *  - ZEILEN-Abstand IM Kartenkörper = 8 dp (hier: Arrangement.spacedBy).
 *  - Kopf = ganzes Touch-Feld, ≥ 48 dp, toggleable mit Role.Button —
 *    TalkBack meldet den Klappe-Zustand; nie nur Farbe als Info-Träger.
 *  - AutoWrap: Titel/Untertitel brechen mehrzeilig um (kein Buchstaben-
 *    Stacking — Sirs Befund P2a), kein fixed-height.
 *
 * Standard-Verhalten (P2c): Sektionen starten ZUGEKLAPPT — die offen-
 * Klapp-Zustände liegen beim Aufrufer (rememberSaveable).
 */
@Composable
fun SettingsCard(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes iconRes: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Kopf: ≥48 dp Touchhöhe, ganze Zeile klappt (Apple-HIG ≥44 pt).
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = onExpandedChange,
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (iconRes != null) {
                    ResourceIcon(iconRes, tint = FlipsiColors.TextSecondary, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = FlipsiColors.TextSecondary,
                )
            }
            if (expanded) {
                HorizontalDivider(color = FlipsiColors.LineStrong)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                    // Zeilen-Rhythmus der Karte: 8 dp (Redesign-Regelwerk).
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        }
    }
}
