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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.SettingsIcons

/**
 * Welle 13 / P7b (Sirs Direktive 11.11.): Rechtliches lebt AUSKLAPPBAR in
 * den Einstellungen — KEINE eigenen Top-Level-Einträge. Karte „Rechtliches“
 * mit drei Expandern: Impressum, Datenschutz (DSGVO, Österreich,
 * Serverbetreiber-Angabe), Nutzungsbedingungen. Die Kurztexte wohnen als
 * legal_*-Keys in strings.xml (de-AT + EN), jederzeit erreichbar — auch im
 * ℹ️-About-Sheet (SettingsHome) über dieselben Keys.
 *
 * Inhalt-Regeln: Österrechtlich korrekt (Anbieter nach §5 Abs 1 ECG, DSGVO-
 * Art-13-Information, ABGB-Haftungsrahmen) und dabei schlichtungs-ehrlich.
 * EINZIGE Impressum-Angabe = die von Sir freigegebene Play-Impressum-Zeile
 * (TechFlipsi / Fabian Kirchweger, Marktplatz 11/3, 4625 Offenhausen) —
 * KEINE privaten Domains/IPs/Mails in den Texten.
 */
@Composable
fun LegalSettingsCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        title = stringResource(R.string.settings_legal_title),
        subtitle = stringResource(R.string.settings_legal_desc),
        iconRes = SettingsIcons.legal,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        LegalExpander(
            titleRes = R.string.legal_imprint_title,
            bodyRes = R.string.legal_imprint_body,
        )
        LegalExpander(
            titleRes = R.string.legal_privacy_title,
            bodyRes = R.string.legal_privacy_body,
        )
        LegalExpander(
            titleRes = R.string.legal_terms_title,
            bodyRes = R.string.legal_terms_body,
        )
    }
}

/**
 * Innerer Expander je Rechts-Dokument (Standard zugeklappt, eigener
 * rememberSaveable-Zustand — überlebt Route-Wechsel). Kopf = ≥48-dp-Touch-
 * feld mit Role.Button; Körper = Fließtext mit AutoWrap (kein Stacking).
 */
@Composable
fun LegalExpander(
    titleRes: Int,
    bodyRes: Int,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(titleRes) { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = open,
                    role = Role.Button,
                    onValueChange = { open = it },
                )
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = FlipsiColors.TextSecondary,
            )
        }
        if (open) {
            // Fließtext bricht um (P2a) — Zeilenabstand 6 dp über/unter, 8 dp
            // Rhythmus kommt vom SettingsCard-Körper.
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
            )
        }
    }
}
