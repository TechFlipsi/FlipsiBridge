package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.chat.CallHistoryPrefs
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.ResourceIcon

/**
 * W22 A2 (P5): Karte „Anrufe“ in den Einstellungen — EIN Aufenthaltsort des
 * Reglers „Anruf-Verlauf aufzeichnen“ (default an). Optik/Verhalten spiegeln
 * die SettingsCard des Redesigns (Kopf ≥ 48 dp klappt die ganze Karte,
 * Zeilen-Rhythmus 8 dp, Standard zu); der Titel läuft als STRING-Marker-
 * Literal, bis der i18n-Merge ihn je Locale in strings.xml legt.
 *
 * Wirkung des Reglers: CallRules.callRecordFor liest ihn VOR jedem Anruf-
 * Verlaufs-Eintrag — AUS heißt: beendete UND verpasste Anrufe hinterlassen
 * keine Spur im Chat-Verlauf (der laufende Call selbst ist davon unberührt).
 */
@Composable
fun CallSettingsCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var recordEnabled by remember { mutableStateOf(CallHistoryPrefs.recordEnabled(context)) }
    // STRING: settings_call_record_a11y — de "Anruf-Verlauf-Aufzeichnung umschalten", en "Toggle call history recording"
    val recordA11y = "Anruf-Verlauf-Aufzeichnung umschalten"
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Kopf: ≥ 48 dp Touchhöhe, ganze Zeile klappt (SettingsCard-Muster).
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = { onExpandedChange(it) },
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResourceIcon(R.drawable.ic_call, tint = FlipsiColors.TextSecondary, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                // STRING: settings_call_title — de "Anrufe", en "Calls"
                Text(
                    "Anrufe",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // STRING: settings_call_record_desc — de "Beendete und verpasste Anrufe erscheinen im Chat-Verlauf. Aus: Anrufe hinterlassen keine Spur.", en "Ended and missed calls appear in the chat history. Off: calls leave no trace."
                    Text(
                        "Beendete und verpasste Anrufe erscheinen im Chat-Verlauf. " +
                            "Aus: Anrufe hinterlassen keine Spur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = recordEnabled,
                                role = Role.Switch,
                                onValueChange = { target ->
                                    recordEnabled = target
                                    CallHistoryPrefs.setRecordEnabled(context, target)
                                },
                            )
                            .semantics { contentDescription = recordA11y },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // STRING: settings_call_record_title — de "Anruf-Verlauf aufzeichnen", en "Record call history"
                        Text(
                            "Anruf-Verlauf aufzeichnen",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        // Schalter OHNE eigenen Handler — die GANZE Zeile ist
                        // das Touch-Ziel (toggleable, Role.Switch); ein
                        // doppelter Handler am Switch würde je Tap zweimal
                        // feuern (gleicher Wert, aber unnötiges Doppeln).
                        Switch(
                            checked = recordEnabled,
                            onCheckedChange = null,
                        )
                    }
                }
            }
        }
    }
}
