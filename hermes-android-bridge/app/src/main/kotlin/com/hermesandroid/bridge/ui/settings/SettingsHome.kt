package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.i18n.AppLanguage
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Welle 13 / P2 (Settings-Redesign, Sirs Direktive 11.11.): der neue
 * Einstellungen-Screen.
 *
 * Kopf = NUR ℹ️-Info-Button (W15/P7: KEIN Titel — der Standort-Pfad
 * „Einstellungen › <Sektion>“ lebt in der Top-Bar; KEINE dauerhafte
 * Versionszeile, die Version lebt NUR im About-Sheet). Body = ausklappbare Karten, Standard
 * ZUGEKLAPPT, Sektions-Abstand 16 dp / Zeilen-Abstand 8 dp (SettingsCard),
 * Touch-Ziele ≥ 48 dp.
 *
 * Karten: Darstellung (P12 Color-Customizer), Berechtigungen (P4),
 * Standard-Assistent (P10), Rechtliches (P7b). Der [legacyGroups]-Slot nimmt
 * die BESTEHENDEN Gruppen (Server/App/Agenten/App-Sperre/Verschlüsselung/
 * Sprache) auf — so bleibt beim Umbau nichts auf der Strecke.
 *
 * Integration (für die Verdrahtungs-Route in ui/BridgeScreen.kt — Datei ist
 * hier bewusst unangetastet, andere Wellen-Lane besitzt sie):
 *   1 -> SettingsHome(padding) { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { /* bisherige AccordionCards */ } }
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    legacyGroups: @Composable () -> Unit = {},
) {
    // P2c: Sektionen starten ZUGEKLAPPT; jeder Zustand einzeln (überlebt
    // Route-Wechsel + Prozess-Restore).
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var assistantOpen by rememberSaveable { mutableStateOf(false) }
    var legalOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    // P10: die ACTIVITY baut den RoleManager-Koordinator (W4-CompositionLocal —
    // der wrapped Sprach-Context ist KEIN Activity-Context).
    val activity = AppLanguage.LocalAppActivity.current
    val assistantCoordinator = remember(activity) {
        activity?.let { DefaultAssistantCoordinator(it) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            // Karten enden nie hart an der Gesture-Navigationssäule.
            bottom = 40.dp + WindowInsets.navigationBars
                .asPaddingValues().calculateBottomPadding(),
        ),
        // Redesign-Regelwerk: SEKTION-Abstand = 16 dp (P2a).
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "settingsHead") {
            SettingsHead(onOpenAbout = { aboutOpen = true })
        }
        // Bestehende Gruppen (Slot, 16 dp Rhythmus innerhalb).
        item(key = "legacyGroups") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { legacyGroups() }
        }
        item(key = "appearance") {
            AppearanceSettingsCard(
                expanded = appearanceOpen,
                onExpandedChange = { appearanceOpen = it },
            )
        }
        item(key = "permissions") {
            PermissionsSettingsCard(
                expanded = permissionsOpen,
                onExpandedChange = { permissionsOpen = it },
            )
        }
        item(key = "assistant") {
            AssistantRoleCard(
                coordinator = assistantCoordinator,
                expanded = assistantOpen,
                onExpandedChange = { assistantOpen = it },
            )
        }
        item(key = "legal") {
            LegalSettingsCard(
                expanded = legalOpen,
                onExpandedChange = { legalOpen = it },
            )
        }
    }

    // P2b: About-Sheet — Version + Rechtliches-Kurzeinträge, NUR hier sichtbar.
    if (aboutOpen) {
        ModalBottomSheet(
            onDismissRequest = { aboutOpen = false },
            containerColor = FlipsiColors.PanelElev,
        ) {
            AboutSheetContent()
        }
    }
}

/**
 * Kopf des Settings-Screens (W15/P7, Sir): NUR der ℹ️-About-Button — KEIN
 * Titel/Untertitel mehr. Der Standort-Titel („Einstellungen › <Sektion>“)
 * lebt ausschließlich in der Top-Bar, doppelter Titel war Sirs Befund.
 */
@Composable
private fun SettingsHead(onOpenAbout: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(onClick = onOpenAbout) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.settings_info_a11y),
                tint = FlipsiColors.TextPrimary,
            )
        }
    }
}

/**
 * About-Sheet-Inhalt (P2b + H): App-Name, Version im Format „vX.Y.Z“ — der
 * Editions-Suffix (-play/-github) fällt WEG (QA A: kein Flavor-Leak in der
 * UI) — plus die drei Rechtliches-Kurzeinträge als Expander.
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
        // Rechtliches-Kurzeinträge (P7b): dieselben legal_*-Texte wie in der
        // Karte — About-Sheet und Einstellungen zeigen niemals zwei Wahrheiten.
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
 * Versions-Anzeige-Vertrag (P2b/QA A): NUR „vX.Y.Z“ — Editions-Suffixe
 * (-play/-github) werden abgeschnitten; reine Funktion → unit-testbar.
 */
object AboutInfo {
    fun versionLabel(versionName: String): String =
        "v" + versionName.substringBefore("-play").substringBefore("-github")
}
