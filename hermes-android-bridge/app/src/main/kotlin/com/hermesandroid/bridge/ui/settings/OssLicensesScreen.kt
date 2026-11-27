package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.legal.OssComponent
import com.hermesandroid.bridge.legal.OssLicenseRules
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * PH8-T7 (W22, Lane E) — OSS-Attribution-Screen.
 *
 * EIN Ort (P18/P20-Regelwerk): das ℹ️-About-Sheet (AboutSheetHost) erhält
 * GENAU EINEN neuen Abschnitt „Open-Source-Lizenzen“ ([OssEntryCard]); er
 * öffnet DIESEN Screen als Vollbild-Dialog ([OssLicensesState]). Von woanders
 * (Einstellungen, Tiefe-Routen) gibt es bewusst KEINEN zweiten Zugang — der
 * P38-Entschluss „das Sheet ist der eine Ort für Version + Rechtstexte“
 * bleibt bestehen, der OSS-Screen ist dessen Unterseite.
 *
 * Inhalt (Brief PH8-T7):
 *  - kurzer MIT-Attributionstext der Basis (hermes-android) MIT Verweis auf
 *    die vollständige LICENSE im Quell-Repository (Brief-Preference statt
 *    raw-Einbettung; Urheber laut /LICENSE gepflegt).
 *  - kuratierte Liste der wesentlichen Abhängigkeiten je Zeile
 *    „Name — Version — Lizenz“ (Eigenamen, nicht übersetzt) mit Copyright-
 *    Zeile darunter ([OssLicenseRules], Quelle libs.versions.toml +
 *    app/build.gradle.kts, nur lesend).
 *
 * Der Dialog ist modal: Zurück/„Zurück“ schließt ihn ([OssLicensesState.hide])
 * und lässt das About-Sheet (offen) bestehen — KEIN Wizard-/Screen-Wechsel.
 */
object OssLicensesState {

    /** true = OSS-Attribution-Screen offen (Vollbild-Dialog). */
    var isOpen by mutableStateOf(false)
        private set

    /** Vom OSS-Abschnitt im About-Sheet aufzurufen ([OssEntryCard]). */
    fun show() {
        isOpen = true
    }

    fun hide() {
        isOpen = false
    }
}

/**
 * Einmalig über dem About-Sheet gerendert (AboutSheetHost ruft den Host VOR
 * dem ModalBottomSheet — Dialog-Fenster liegen ohnehin über allem). Schließt
 * der Aufrufer das About-Sheet, räumt derselbe Pfad den OSS-Dialog mit auf
 * (kein verwaistes Fenster beim nächsten Sheet-Öffnen).
 */
@Composable
fun OssLicensesHost() {
    if (!OssLicensesState.isOpen) return
    Dialog(
        onDismissRequest = { OssLicensesState.hide() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        OssLicensesScreenContent()
    }
}

/** Inhalt des OSS-Screens (Brief: Liste MIT Lizenz + Copyright-Hinweis). */
@Composable
private fun OssLicensesScreenContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FlipsiColors.Void)
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Kopf: Zurück (TextButton ≥48dp-Mindestziel) + Titel.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { OssLicensesState.hide() },
                // 48-dp-Regel: Mindest-Touch-Ziel für den Zurück-Weg.
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                // onb_back ist der bestehende „Zurück“-Key des Wizards —
                // bewusst wiederverwendet (STRINGS-PARITÄT, kein Zweit-Key).
                Text(stringResource(R.string.onb_back), color = FlipsiColors.TextSecondary)
            }
            Text(
                stringResource(R.string.about_oss_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OssBaseAttributionCard()
            Text(
                stringResource(R.string.oss_intro),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            val entries = OssLicenseRules.sortedDisplay(BuildConfig.EDITION_PLAY)
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.oss_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
            }
            entries.forEach { component ->
                OssRow(component)
                HorizontalDivider(color = FlipsiColors.Line, thickness = 1.dp)
            }
        }
    }
}

/**
 * Basis-Attribution (MIT): eigener kurzer Text MIT Verweis auf die
 * vollständige LICENSE (Brief-Preference). Lizenzattributions-Texte sind
 * Rechtstext-Zitierweise — de/en gleichlautend (Lizenznamen = Eigenamen).
 * Version wie im About-Sheet ([AboutInfo.versionLabel]); der Version-Teil
 * steht als %1$s-Platzhalter in strings.xml (oss_license_ref).
 */
@Composable
private fun OssBaseAttributionCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.oss_base_attribution),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextPrimary,
            )
            Text(
                stringResource(
                    R.string.oss_license_ref,
                    AboutInfo.versionLabel(BuildConfig.VERSION_NAME),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
        }
    }
}

/** Je Abhängigkeit: „Name — Version — Lizenz“ (Eigenamen) + Copyright unten. */
@Composable
private fun OssRow(component: OssComponent) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            OssLicenseRules.lineLabel(component),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            component.copyright,
            style = MaterialTheme.typography.bodySmall,
            color = FlipsiColors.TextSecondary,
        )
    }
}

/**
 * OSS-Abschnitt IM About-Sheet (EIN Zugang, P18): ganze Kopfzeile als
 * Fläche (48 dp+ inkl. Text-Padding, wie OnbLegalExpander), Chevron ▸ als
 * Richtungs-Anzeige — die Wirkung (Screen öffnet) ist AKTIONEN-MIT-ERGEBNIS.
 */
@Composable
fun OssEntryCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { OssLicensesState.show() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.about_oss_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.about_oss_entry_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                )
            }
            Text("▸", style = MaterialTheme.typography.titleSmall, color = FlipsiColors.TextSecondary)
        }
    }
}
