package com.hermesandroid.bridge.ui.components
import com.hermesandroid.bridge.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
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
 * Berechtigungs-Zeile (W15/P6, Sir): Text OBEN volle Breite, darunter
 * ZENTRIERT die Status-Info (an/aus/fehlt) mit der Aktion daneben
 * („Öffnen“-Chip für Settings-Sprünge, Switch-Anzeige für Toggles) —
 * statt Badge + Chip nebeneinander im Weg des Beschreibungs-Texts.
 * Ganze Kachel ≥48dp bleibt Touch-Feld.
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
    Column(
        // W15/P6: Abstand Textblock ↔ Statuszeile.
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FlipsiSpacing.RowMin)
            .minimumInteractiveComponentSize()
            .then(
                when (onAction) {
                    is PermissionAction.SettingsSprung -> Modifier.clickable(onClickLabel = stringResource(R.string.a11y_open_setting)) { onOpen() }
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
        // Zeile 1: Icon + Titel/Beschreibung — volle Breite, AutoWrap.
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        }
        // Zeile 2 (W15/P6, Sir): Status-Info zentriert, Aktion daneben.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(badge.first, badge.second)
            when (onAction) {
                is PermissionAction.SettingsSprung -> AssistChip(
                    onClick = onOpen,
                    label = { Text(stringResource(R.string.common_open)) },
                    modifier = Modifier.padding(start = 8.dp),
                    border = AssistChipDefaults.assistChipBorder(
                        borderColor = FlipsiColors.LineStrong,
                        enabled = true,
                    ),
                )
                is PermissionAction.Toggle -> Switch(
                    checked = onAction.checked,
                    onCheckedChange = null,
                    modifier = Modifier.padding(start = 8.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = FlipsiColors.Ember),
                )
                PermissionAction.None -> {}
            }
        }
    }
}
