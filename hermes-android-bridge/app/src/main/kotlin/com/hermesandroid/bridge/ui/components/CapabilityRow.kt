package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import com.hermesandroid.bridge.ui.FlipsiSpacing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.ResourceIcon
import com.hermesandroid.bridge.ui.components.StatusIcons

/**
 * Fähigkeits-Zeile (v0.12.0): GANZE Zeile = Touch-Feld (≥48dp, M3-Muster
 * `Switch(onCheckedChange = null)` + `toggleable` auf der Zeile = EIN
 * Toggle-Semantik-Knoten für TalkBack). Invasive Fähigkeiten tragen das
 * ⚠-Logo neben dem Titel.
 */
@Composable
fun CapabilityRow(
    cap: CapabilityUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FlipsiSpacing.RowMin)
            .minimumInteractiveComponentSize()
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .semantics { stateDescription = if (enabled) "Aktiviert" else "Aus" }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        CapIcon(cap.id, tint = FlipsiColors.TextSecondary, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cap.label, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                if (cap.invasive) {
                    Spacer(Modifier.width(6.dp))
                    ResourceIcon(StatusIcons.error, tint = FlipsiColors.SignalAmber, size = 14.dp)
                }
            }
            Text(
                cap.description,
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Switch ist ANZEIGE: onCheckedChange = null, die Zeile ist das Touch-Ziel.
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = FlipsiColors.Ember),
        )
    }
}