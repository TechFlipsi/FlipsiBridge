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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.i18n.AppLanguage
import com.hermesandroid.bridge.ui.assistant.AssistantCardTexts
import com.hermesandroid.bridge.ui.assistant.AssistantPackageCard

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
    // W21 ASSISTENT-PAKET (G1/G2/G5-Überblickskarte): eigene Klappe — der
    // Anfrage-Weg der Standard-Assistent-Rolle bleibt in AssistantRoleCard
    // (KDoc-Verband: „deren Anfrage-Weg für die Rolle bestehen bleibt“).
    var assistantPkgOpen by rememberSaveable { mutableStateOf(false) }

    // P10: die ACTIVITY baut den RoleManager-Koordinator (W4-CompositionLocal —
    // der wrapped Sprach-Context ist KEIN Activity-Context).
    val activity = AppLanguage.LocalAppActivity.current
    val assistantCoordinator = remember(activity) {
        activity?.let { DefaultAssistantCoordinator(it) }
    }

    // W22 A2 (P5): Anruf-Einstellungen — EIN Aufenthaltsort des Reglers
    // „Anruf-Verlauf aufzeichnen“ (default an). Karte startet zugeklappt wie
    // jede Sektion; nur dieser Call/Verlauf-Block ist W22, der Rest der
    // SettingsHome bleibt unberührt.
    var callsOpen by rememberSaveable { mutableStateOf(false) }

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
        // W21 ASSISTENT-PAKET (Verdrahtung MERGE-LANE 18.11., nach KDoc):
        // G1 rendert die Karte aus den BESTEHENDEN Assistenten-Strings (Rolle/
        // Overlay-Stand liest sie je Kartenöffnung selbst); G2/G5 nehmen die
        // Texte als Parameter ([AssistantCardTexts]) — hier je Locale aus
        // strings.xml ×3 gelöst. G5 ehrlich „ohne Session“: der Session-
        // Epoch-Verband lebt noch nicht im Client (das GATE im screen-
        // Capability-Pfad ist laut KDoc Anschluss-Lane) — KEIN Fake-Schlüssel,
        // kein stiller Schalter; die Zeile trägt den noSession-Text und der
        // Schalter bleibt disabled, bis die Anschluss-Lane den Schlüssel
        // anstöpselt (dann consent/grant/revoke an Prefs/Persistenz hängen).
        item(key = "assistantPkg") {
            val pkgTexts = AssistantCardTexts(
                bubbleTitle = stringResource(R.string.assistant_pkg_bubble_title),
                bubbleReadyDesc = stringResource(R.string.assistant_pkg_bubble_ready_desc),
                bubbleNeedPermDesc = stringResource(R.string.assistant_pkg_bubble_need_perm_desc),
                bubbleGrantAction = stringResource(R.string.assistant_pkg_bubble_grant_action),
                bubbleGrantA11y = stringResource(R.string.assistant_pkg_bubble_grant_a11y),
                bubbleGrantUnavailable = stringResource(R.string.assistant_pkg_bubble_grant_unavailable),
                screenContextTitle = stringResource(R.string.assistant_pkg_screen_context_title),
                screenContextDesc = stringResource(R.string.assistant_pkg_screen_context_desc),
                screenContextNoSession = stringResource(R.string.assistant_pkg_screen_context_no_session),
                screenContextGrantA11y = stringResource(R.string.assistant_pkg_screen_context_grant_a11y),
                screenContextAskA11y = stringResource(R.string.assistant_pkg_screen_context_ask_a11y),
            )
            AssistantPackageCard(
                expanded = assistantPkgOpen,
                onExpandedChange = { assistantPkgOpen = it },
                texts = pkgTexts,
            )
        }
        // W22 A2 (P5): Anrufe — Verlaufs-Regler (CallSettingsCard, s. KDoc).
        item(key = "calls") {
            CallSettingsCard(
                expanded = callsOpen,
                onExpandedChange = { callsOpen = it },
            )
        }
        // B7 (Multi-Gerät): Geräte-Verwaltung — Liste aus relay.state
        // devices[], Kopplungs-Anfrage (pairing.request), Entfernen 2-stufig.
        //
        // APP-ANSCHLUSS-VERDRAHTUNG (real, Multi-Gerät B4-Kontrakt HEAD
        // 733504c): alle vier Signale sind gebunden —
        //  1. devices: RelayClient.devices (parseRelayStateDevices liest
        //     devices[] je relay.state-Hallo/Presence-Frame),
        //  2. pendingPairing: RelayClient.pairingFrame (B5-Lane) → hier auf
        //     DeviceRules.PairingRequest abgebildet (Feld-Parität) — das
        //     Panel ist ehrlich: abgelaufene Anfragen verschwinden (TTL),
        //  3. ownMaskedDeviceId: NIE-WRONG über DeviceRules.ownMaskedDeviceId
        //     (Backend-Maske "<masked:{len}>"; MEHRDEUTIG = kein Chip),
        //  4. onPairingDecision → sendPairingApprove/sendPairingDeny
        //     (request_id-Parität: das Relay löst die Maske selbst auf) mit
        //     StateFlow-Räumung nur bei erfolgreichem Senden (fehlge-
        //     schlagenes WS-Send lässt die Anfrage sichtbar = wiederholbar);
        //     onRevokeDevice → sendDeviceRevoke mit dem EPHEMEREN B4-Handle
        //     — NUR mit Handle (fail-closed: je Frame OHNE Ziel würde das
        //     Backend SELBST abmelden) und NUR bei offener Verbindung
        //     (sendRawFrame gibt false ohne Socket).
        item(key = "devices") {
            val devices by RelayClient.devices.collectAsState()
            val pairingFrame by RelayClient.pairingFrame.collectAsState()
            val pendingPairing = pairingFrame?.let { frame ->
                DeviceRules.PairingRequest(
                    requestId = frame.requestId,
                    maskedDeviceId = frame.deviceMasked,
                    ip = frame.ip,
                    tsMs = frame.ts,
                    ttlS = frame.ttlS,
                )
            }
            DevicesCard(
                devices = devices,
                pendingPairing = pendingPairing,
                // EIGENE Maske NIE-WRONG abgeleitet (nie raten): Backend-Masken-
                // Regel "<masked:{len(raw)}>" + Form-Gate + Einträger-GATE
                // (Beweis in DeviceRules.ownMaskedDeviceId); mehrdeutig = null.
                ownMaskedDeviceId = DeviceRules.ownMaskedDeviceId(
                    devices, PairingManager.deviceId(),
                ),
                expanded = devicesOpen,
                onExpandedChange = { devicesOpen = it },
                onPairingDecision = { requestId, approve ->
                    val sent = if (approve) RelayClient.sendPairingApprove(requestId)
                    else RelayClient.sendPairingDeny(requestId)
                    // Räumen erst nach wirksamem Senden — eine fehlgeschlagene
                    // WS-Send-Spur lässt das Tor sichtbar (erneuter Versuch).
                    if (sent) RelayClient.dismissPairingFrame()
                },
                onRevokeDevice = { requestId ->
                    // Fail-closed: OHNE Handle senden wir NICHT — der Frame
                    // würde am Backend als Self-Revoke (ohne Ziel = eigenes
                    // Gerät) wirken, niemals das gewünschte fremde Gerät
                    // entfernen. Mit Handle löst das Relay das Ziel selbst
                    // auf (Admin-only fail-closed dort; 4404-Kick am Ziel).
                    if (requestId.isNotBlank()) RelayClient.sendDeviceRevoke(requestId)
                },
            )
        }
    }
}
