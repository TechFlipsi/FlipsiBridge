package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Die Master-Karte (v0.12.0): EIN Schalter für alle 16 Fähigkeiten.
 * Größtes Touch-Ziel der Fähigkeiten-Sektion; Zustand dreifach kodiert
 * (Switch + Zähler-Badge + Randfarbe). Die Logik (Bestätigungsdialog bei
 * invasiven, Undo bei „aus") lebt im Screen — hier nur Anzeige + Intent.
 */
@Composable
fun MasterCapabilityCard(
    enabledCount: Int,
    totalCount: Int,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allOn = enabledCount == totalCount && totalCount > 0
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.PanelElev),
        border = BorderStroke(
            width = 1.dp,
            color = if (allOn) FlipsiColors.Ember else FlipsiColors.LineStrong,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FlipsiSpacing.RowMin)
                .minimumInteractiveComponentSize()
                .toggleable(value = allOn, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics {
                    stateDescription = if (allOn) "Alle aktiviert" else "$enabledCount von $totalCount aktiviert"
                }
                .padding(16.dp),
        ) {
            ResourceIcon(GroupIcons.master,
                tint = if (allOn) FlipsiColors.Ember else FlipsiColors.TextSecondary,
                size = 28.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Alle Fähigkeiten", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(
                    if (allOn) "Der Agent darf alles, was unten aufgeführt ist."
                    else "Ein Schalter für alle $totalCount Fähigkeiten — invasive vorher bestätigen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                )
            }
            CountBadge(enabled = enabledCount, total = totalCount, invasive = false)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = allOn,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = FlipsiColors.Ember),
            )
        }
    }
}