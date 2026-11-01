package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors

/** Arten der Zeilen-Aktion: System-Settings-Sprung, In-App-Toggle oder keine. */
sealed interface PermissionAction {
    data object SettingsSprung : PermissionAction
    data class Toggle(val checked: Boolean) : PermissionAction
    data object None : PermissionAction
}

/**
 * Berechtigungs-Zeile (v0.12.0): Icon + Titel/Beschreibung + StatusBadge +
 * klare Aktion („Öffnen"-Chip für Settings-Sprünge, Switch-Anzeige für
 * Toggles) — statt der 8-Parameter-Farbzeile der v0.11.0. Ganze Zeile ≥48dp.
 */
@Composable
fun PermissionRow(
    @androidx.annotation.DrawableRes iconRes: Int,
    title: String,
    description: String,
    badge: Pair<String, BadgeState>,
    onAction: PermissionAction,
    onToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    // LETZTER Parameter — trailing-lambda der Aufrufe bindet hier (onOpen).
    onOpen: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FlipsiSpacing.RowMin)
            .minimumInteractiveComponentSize()
            .then(
                when (onAction) {
                    is PermissionAction.SettingsSprung -> Modifier.clickable(onClickLabel = "Einstellung öffnen") { onOpen() }
                    is PermissionAction.Toggle -> Modifier.toggleable(
                        value = onAction.checked,
                        role = Role.Switch,
                        onValueChange = onToggle,
                    )
                    PermissionAction.None -> Modifier
                }
            )
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        ResourceIcon(iconRes, tint = FlipsiColors.TextSecondary, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusBadge(badge.first, badge.second)
        when (onAction) {
            is PermissionAction.SettingsSprung -> AssistChip(
                onClick = onOpen,
                label = { Text("Öffnen") },
                modifier = Modifier.padding(start = 8.dp),
                border = AssistChipDefaults.assistChipBorder(
                    borderColor = FlipsiColors.LineStrong,
                    enabled = true,
                ),
            )
            is PermissionAction.Toggle -> Switch(
                checked = onAction.checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = FlipsiColors.Ember),
            )
            PermissionAction.None -> {}
        }
    }
}