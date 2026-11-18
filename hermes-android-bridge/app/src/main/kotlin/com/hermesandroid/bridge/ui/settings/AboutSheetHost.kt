package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Welle 16 / P20+P27 (Sir): About/Rechtliches hat GENAU EINEN Ort — das
 * ℹ️-Sheet am DAUERHAFTEN TopBar-Button (BridgeScreen-Action). Die frühere
 * SettingsHead-Kachel (Einstellungen-Liste) und die LegalSettingsCard sind
 * ENTFERNT; Doppel-Anzeigen fliegen ganz (P18-Regelwerk).
 *
 * Verband:
 *  - [AboutSheetHost] rendert das Sheet, WENN [AboutState.sheetOpen] steht —
 *    einmalig am Screen-Level (BridgeScreen), überall aufrufbar (Dashboard/
 *    Chat/Agenten/E2E/Einstellungen), nicht nur in den Einstellungen.
 *  - Version „vX.Y.Z“ ([AboutInfo.versionLabel]) IM Sheet immer sichtbar
 *    (P20: „Version IM ℹ️-Pfad immer sichtbar“).
 *  - Rechtliches: die drei legal_*-Expander (Impressum/Datenschutz/Nutzung)
 *    mit dem GLEICHEN Inhalt wie die ehemalige Karte — die Rechtstexte leben
 *    als legal_*-Keys in strings.xml (eine Quelle, keine zwei Wahrheiten).
 *  - W16.1 (P38, Sir): die Route-3-Vollansicht ist VOLL ENTFERNT („komplett
 *    raus“) — das Sheet ist der EINE Ort für Version + Rechtstexte; der
 *    Tiefen-Sprung samt LegalScreenPlaceholder (nur Platzhalterkarten)
 *    existiert nicht mehr.
 *  - PH8-T7 (W22, Lane E): NEU der Abschnitt „Open-Source-Lizenzen“
 *    ([OssEntryCard]) — er öffnet den OSS-Attribution-Screen
 *    ([OssLicensesHost] in OssLicensesScreen.kt) als Vollbild-Dialog ÜBER
 *    diesem Sheet. EIN Zugang (kein Zweit-Eintrag in den Einstellungen),
 *    beim Sheet-Abbruch wird der Dialog mit abgeräumt.
 */
object AboutState {

    /** true = About-Sheet offen (ℹ️-TopBar-Action setzt das). */
    var sheetOpen by mutableStateOf(false)
        private set

    /** Von der ℹ️-TopBar-Action aufzurufen. */
    fun open() {
        sheetOpen = true
    }

    fun close() {
        sheetOpen = false
    }
}

/**
 * Einmalig am Screen-Level gerendert (BridgeScreen, nach dem Scaffold):
 * das Sheet selbst erscheint nur bei offenem Zustand, sonst kein Overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheetHost() {
    // PH8-T7 (W22, Lane E): der OSS-Attribution-Screen gehört zum selben
    // Rechtliches-Ort — der Host mountet ihn hier einmalig (Vollbild-Dialog
    // über dem Sheet; schließt auf „Zurück“/Abbruch, Sheet bleibt bestehen).
    OssLicensesHost()
    if (!AboutState.sheetOpen) return
    ModalBottomSheet(
        onDismissRequest = {
            // PH8-T7 (W22): der OSS-Dialog (falls über dem Sheet auf) wird
            // beim Sheet-Abbruch mit abgeräumt — kein verwaistes Fenster.
            OssLicensesState.hide()
            AboutState.close()
        },
        containerColor = FlipsiColors.PanelElev,
    ) {
        AboutSheetContent()
    }
}

/**
 * Inhalt des About-Sheets (ehemals SettingsHome.AboutSheetContent): App-Name,
 * Tagline, Version „vX.Y.Z“ (Editions-Suffix fällt weg — AboutInfo) und die
 * drei Rechtliches-Expander. W16.1 (P38): KEIN Tiefe-Sprung mehr — das Sheet
 * ist der einzige Ort (Sir: „Vollansicht komplett raus“; dort standen zudem
 * nur die Phase-8-Platzhalterkarten statt echter Texte).
 */
@Composable
private fun AboutSheetContent() {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.about_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = FlipsiColors.TextSecondary,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.about_version_label),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                AboutInfo.versionLabel(BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // P20: legal_*-Texte im ℹ️-Pfad — dieselbe Quelle wie die Tiefe-Route,
        // EIN dauerhafter Ort, keine zweite Karte in den Einstellungen.
        LegalExpander(titleRes = R.string.legal_imprint_title, bodyRes = R.string.legal_imprint_body)
        LegalExpander(titleRes = R.string.legal_privacy_title, bodyRes = R.string.legal_privacy_body)
        LegalExpander(titleRes = R.string.legal_terms_title, bodyRes = R.string.legal_terms_body)
        // PH8-T7 (W22, Lane E): GENAU EIN neuer Abschnitt „Open-Source-
        // Lizenzen“ — öffnet den OSS-Attribution-Screen ([OssEntryCard] →
        // [OssLicensesHost][OssLicensesScreen.kt]). EIN Zugang (P18/P38):
        // niemals zusätzlich in den Einstellungen oder Tiefe-Routen.
        OssEntryCard()
        // W16.1 (P38): der frühere „Über die App: Rechtliches (Vollansicht)"-
        // Sprung (requestLegalDepth → Route 3) ist ENTFERNT — hinter der Tiefe
        // standen ausschließlich die Phase-8-Platzhalterkarten, während die
        // echten Rechtstexte bereits HIER im Sheet lagen (Doppel + tote Inhalte).
    }
}

/**
 * Versions-Anzeige-Vertrag (P2b/QA A, W15): NUR „vX.Y.Z“ — Editions-Suffixe
 * (-play/-github) werden abgeschnitten; reine Funktion → unit-testbar.
 * W16 P20+27: von SettingsHome hierher gezogen (About-Sheet = EIN Ort).
 */
object AboutInfo {
    fun versionLabel(versionName: String): String =
        "v" + versionName.substringBefore("-play").substringBefore("-github")
}
