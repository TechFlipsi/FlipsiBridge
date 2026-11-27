package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.i18n.AppLanguage
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * W4 (App-Lane C) — Einstellungen-Zeile „Sprache“: App-Sprache DE/EN (plus
 * Systemstandard), unabhängig vom System. Die Wahl schreibt AppLanguage (Prefs
 * „app_language“) — der AppLanguageGate-Verband am Activity-Kompositions-Punkt
 * re-wrapped die App SOFORT (kein Activity-Recreate); alle stringResource-
 * Auflösungen unterhalb des Gates wechseln live. Diese Karte beweist das
 * selbst: ihre Labels + Beschreibung sind stringResource → bei EN-Wahl sofort
 * Englisch.
 *
 * A11y: je Option eine Zeile (RadioButton) mit Role.RadioButton über selectable
 * — TalkBack liest Zustand mit, Touch-Ziel ≥ 48 dp, Zustand nie nur über Farbe.
 */
@Composable
fun LanguageSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selected = AppLanguage.current.value
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.settings_language_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            AppLanguage.OPTIONS.forEach { tag ->
                LanguageOptionRow(
                    label = when (tag) {
                        AppLanguage.SYSTEM -> stringResource(R.string.language_option_system)
                        AppLanguage.DE -> stringResource(R.string.language_option_de)
                        else -> stringResource(R.string.language_option_en)
                    },
                    selected = tag == selected,
                    onSelect = { AppLanguage.set(context, tag) },
                )
            }
        }
    }
}

@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
