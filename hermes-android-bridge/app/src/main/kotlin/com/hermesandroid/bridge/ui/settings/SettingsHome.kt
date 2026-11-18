package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.i18n.AppLanguage

/**
 * Welle 13 / P2 (Settings-Redesign, Sirs Direktive 11.11.): der neue
 * Einstellungen-Screen.
 *
 * Kopf = TITELLOS (W15/P7: der Standort-Pfad „Einstellungen › <Sektion>“ lebt
 * in der Top-Bar; KEINE dauerhafte Versionszeile, die Version lebt NUR im
 * About-Sheet). Body = ausklappbare Karten, Standard
 * ZUGEKLAPPT, Sektions-Abstand 16 dp / Zeilen-Abstand 8 dp (SettingsCard),
 * Touch-Ziele ≥ 48 dp.
 *
 * W19 (Apple-Audit): die Karte „Darstellung“ (P12 Color-Customizer) ist
 * ENTFERNT — die 4 Regler wirkten NUR in der eigenen Preview (kein Screen
 * konsumierte das ThemeEngine-Schema, App-Root blieb statisch
 * FlipsiDarkScheme). Toter Regler = SIRS VERBOT (KEIN-GERAFFEL). Übrig:
 * Berechtigungen (P4), Standard-Assistent (P10). Der [legacyGroups]-Slot nimmt
 * die BESTEHENDEN Gruppen (Server/App/Agenten/App-Sperre/Verschlüsselung/
 * Sprache) auf — so bleibt beim Umbau nichts auf der Strecke.
 *
 * Welle 16 / P19 (Sir, cleane Optik): die Untertitel-Texte der Kacheln sind
 * ENTFERNT (subtitle-Parameter nicht mehr belegt) — die Beschreibung lebt in
 * der GEÖFFNETEN Karte (je Karte erster Körper-Text).
 * Welle 16 / P20+P27 (Sir): die Karten „Rechtliches“ und das SettingsHead-ℹ️
 * sind ENTFERNT — Rechtliches + Version leben AUSSCHLIESSLICH im About-Sheet
 * am dauerhaften TopBar-ℹ️ (ui/settings/AboutSheetHost.kt).
 *
 * Integration (für die Verdrahtungs-Route in ui/BridgeScreen.kt — Datei ist
 * hier bewusst unangetastet, andere Wellen-Lane besitzt sie):
 *   1 -> SettingsHome(padding) { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { /* bisherige AccordionCards */ } }
 */
@Composable
fun SettingsHome(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    legacyGroups: @Composable () -> Unit = {},
) {
    // P2c: Sektionen starten ZUGEKLAPPT; jeder Zustand einzeln (überlebt
    // Route-Wechsel + Prozess-Restore). W16: „legal“ entfällt (P20+) — der
    // Inhalt ist im About-Sheet.
    // W19: „Darstellung“-Zustand entfällt mit der entfernten Karte.
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var assistantOpen by rememberSaveable { mutableStateOf(false) }
    // B7 (Multi-Gerät): die Geräte-Karte startet ebenfalls zugeklappt.
    var devicesOpen by rememberSaveable { mutableStateOf(false) }

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
        // Bestehende Gruppen (Slot, 16 dp Rhythmus innerhalb). W16: OHNE
        // SettingsHead-Zeile davor (P27 — das ℹ️ lebt dauerhaft an der TopBar).
        item(key = "legacyGroups") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { legacyGroups() }
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
        // B7 (Multi-Gerät): Geräte-Verwaltung — Liste aus relay.state
        // devices[], Kopplungs-Anfrage (pairing.request), Entfernen 2-stufig.
        //
        // VERDRAHTUNG (bewusst noch nicht verbunden): der Zustand kommt mit
        // der B5/B6-Lane — TODO(B5): `devices` aus dem relay.state-Flow von
        // RelayClient (handleRelayStateFrame → devices[]) via collectAsState,
        // TODO(B5): `pendingPairing` aus dem pairing.request-StateFlow,
        // TODO(B5): `ownMaskedDeviceId` = maskierte Eigene-Kennung, und
        // TODO(B5): onPairingDecision → sende pairing.approve/pairing.deny
        // (request_id) + Revoke-Senden, sobald der Backend-Frame existiert
        // (Platzhalter-Callbacks im Card-Aufruf bleiben bis dahin No-Op).
        item(key = "devices") {
            DevicesCard(
                devices = emptyList(),
                pendingPairing = null,
                ownMaskedDeviceId = null,
                expanded = devicesOpen,
                onExpandedChange = { devicesOpen = it },
            )
        }
    }
}
