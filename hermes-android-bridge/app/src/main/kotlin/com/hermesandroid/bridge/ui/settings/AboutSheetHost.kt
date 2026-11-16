package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
 *  - Route 3 (LegalScreenPlaceholder) bleibt als TIEFE vom Sheet erreichbar
 *    ([AboutState.requestLegalDepth] → BridgeScreen konsumiert und setzt
 *    Route 3); die Tiefe zeigt zusätzlich die Version (P20).
 */
object AboutState {

    /** true = About-Sheet offen (ℹ️-TopBar-Action setzt das). */
    var sheetOpen by mutableStateOf(false)
        private set

    /** Einmal-Merker: Volltext-Tiefe (Route 3) aus dem Sheet anfordern. */
    var legalDepthRequest by mutableStateOf(false)
        private set

    /** Von der ℹ️-TopBar-Action aufzurufen. */
    fun open() {
        sheetOpen = true
    }

    /** Vom „Rechtliches“-Eintrag im Sheet aufzurufen (Tiefe Route 3). */
    fun close() {
        sheetOpen = false
    }

    fun requestLegalDepth() {
        sheetOpen = false
        legalDepthRequest = true
    }

    /** BridgeScreen konsumiert den Merker direkt nach dem Routen-Wechsel. */
    fun consumeLegalDepth() {
        legalDepthRequest = false
    }
}

/**
 * Einmalig am Screen-Level gerendert (BridgeScreen, nach dem Scaffold):
 * das Sheet selbst erscheint nur bei offenem Zustand, sonst kein Overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheetHost() {
    if (!AboutState.sheetOpen) return
    ModalBottomSheet(
        onDismissRequest = { AboutState.close() },
        containerColor = FlipsiColors.PanelElev,
    ) {
        AboutSheetContent()
    }
}

/**
 * Inhalt des About-Sheets (ehemals SettingsHome.AboutSheetContent): App-Name,
 * Tagline, Version „vX.Y.Z“ (Editions-Suffix fällt weg — AboutInfo), die drei
 * Rechtliches-Expander und der Tiefe-Sprung zur Vollansicht (Route 3).
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
        // P27-Tiefe: Route 3 (Vollansicht mit Version). Kein toter Code-Faden:
        // der Merker wird im BridgeScreen konsumiert (LaunchedEffect → route=3).
        TextButton(onClick = { AboutState.requestLegalDepth() }) {
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = FlipsiColors.Ember,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.about_open_legal_full),
                color = FlipsiColors.Ember,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
