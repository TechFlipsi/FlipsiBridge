package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.AgentInviteRules
import com.hermesandroid.bridge.client.DeviceAliasRules
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.i18n.AppLanguage
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.assistant.AssistantCardTexts
import com.hermesandroid.bridge.ui.assistant.AssistantPackageCard
import com.hermesandroid.bridge.ui.assistant.DefaultAssistantCoordinator
import com.hermesandroid.bridge.ui.rules.ErrorWordsRules
import com.hermesandroid.bridge.ui.rules.LocalWindowSizeClass
import com.hermesandroid.bridge.ui.rules.WindowFoldRules

/**
 * Welle 13 / P2 (Settings-Redesign, Besitzers Direktive 11.11.): der neue
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
 * Berechtigungen (P4), Assistent (P10/W21).
 *
 * W23-7 (Besitzer-KEIN-DOPPEL, Lane U7): „Standard-Assistent“ stand ZWEIMAL im
 * Menü — die W13-Einzel-Karte (item „assistant“, AssistantRoleCard) und die
 * W21-Assistent-Paket-Karte (item „assistantPkg“) trugen denselben Titel
 * (settings_assistant_title ×2). BEHEBEN: die Einzel-Karte ist entgangen,
 * ihr Inhalt (Beschreibung + Rollen-Anfrage: Einrichten-Knopf, GATE,
 * Fallback) ist Teil der Paket-Karte (G1-Verband, AssistantCard.kt). EIN
 * Eintrag. Der [legacyGroups]-Slot nimmt die BESTEHENDEN Gruppen
 * (Server/App/Agenten/App-Sperre/Verschlüsselung/Sprache) auf — so bleibt
 * beim Umbau nichts auf der Strecke.
 *
 * Welle 16 / P19 (cleane Optik): die Untertitel-Texte der Kacheln sind
 * ENTFERNT (subtitle-Parameter nicht mehr belegt) — die Beschreibung lebt in
 * der GEÖFFNETEN Karte (je Karte erster Körper-Text).
 * Welle 16 / P20+P27: die Karten „Rechtliches“ und das SettingsHead-ℹ️
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
    // W23-9 (Lane U9): ehrliches Feedback über DIE eine Snackbar
    // (BridgeScreen-Verband, W19) — die Umbenennen-Antworten (ok/„später“/error)
    // melden sich dort; leerer Default = still (kein Pflicht-Param an der
    // Signatur für bestehende Aufrufer).
    onFeedback: (String) -> Unit = {},
) {
    // P2c: Sektionen starten ZUGEKLAPPT; jeder Zustand einzeln (überlebt
    // Route-Wechsel + Prozess-Restore). W16: „legal“ entfällt (P20+) — der
    // Inhalt ist im About-Sheet.
    // W19: „Darstellung“-Zustand entfällt mit der entfernten Karte.
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    // W23-7 (U7): „assistant"-Klappe entfällt mit der Einzel-Karte — die
    // Rollen-Anfrage lebt in der Paket-Karte (EIN Eintrag, s. KDoc oben).
    // B7 (Multi-Gerät): die Geräte-Karte startet ebenfalls zugeklappt.
    var devicesOpen by rememberSaveable { mutableStateOf(false) }
    // W21 ASSISTENT-PAKET (G1/G2/G5-Überblickskarte): eigene Klappe — seit
    // W23-7 trägt sie UND die Rollen-Anfrage (G1-GATE/Anfrage-Weg aus der
    // entfallenen W13-Einzel-Karte, s. AssistantCard-KDoc).
    var assistantPkgOpen by rememberSaveable { mutableStateOf(false) }

    // P10: die ACTIVITY baut den RoleManager-Koordinator (W4-CompositionLocal —
    // der wrapped Sprach-Context ist KEIN Activity-Context).
    val activity = AppLanguage.LocalAppActivity.current
    val assistantCoordinator = remember(activity) {
        activity?.let { DefaultAssistantCoordinator(it) }
    }

    // ── W26 (PAAR-TOR-BANNER, 2. Montage-Ort je KDoc PairingRequestPanel): ──
    // Der Freigabe-/Abweisungs-Verband lebt EINE STELLE (oben) — beide Montagen
    // (Screen-Rand-Banner + Geräte-Karte) nutzen ihn. Das Heben hier ist die
    // Zugeklappt-Fallen-Antwort: die Geräte-Karte startet STD zu zugeklappt,
    // eine offene pairing.request (TTL 600 s) dürfte deswegen NIE unsichtbar
    // bleiben.
    val pairingFrame by RelayClient.pairingFrame.collectAsState()
    // W26: devices GEHOBEN (Paar-Tor-Banner + Geräte-Karte + Agent-Einladungs-
    // Karte lesen denselben Stand; kein doppeltes collectAsState je item).
    val devices by RelayClient.devices.collectAsState()
    // W26d (R5): der ENV-Stand der Räum-Frist je relay.state-Frame
    // (device_idle_days — null = Stand UNBEKANNT, alter Relay).
    val deviceIdleDays by RelayClient.deviceIdleDays.collectAsState()
    val pendingPairing = pairingFrame?.let { frame ->
        DeviceRules.PairingRequest(
            requestId = frame.requestId,
            maskedDeviceId = frame.deviceMasked,
            ip = frame.ip,
            tsMs = frame.ts,
            ttlS = frame.ttlS,
        )
    }
    val onPairingDecision: (String, Boolean) -> Unit = { requestId, approve ->
        val sent = if (approve) RelayClient.sendPairingApprove(requestId)
        else RelayClient.sendPairingDeny(requestId)
        // Räumen erst nach wirksamem Senden — eine fehlgeschlagene
        // WS-Send-Spur lässt das Tor sichtbar (erneuter Versuch).
        if (sent) RelayClient.dismissPairingFrame()
    }

    // W22 A2 (P5): Anruf-Einstellungen — EIN Aufenthaltsort des Reglers
    // „Anruf-Verlauf aufzeichnen“ (default an). Karte startet zugeklappt wie
    // jede Sektion; nur dieser Call/Verlauf-Block ist W22, der Rest der
    // SettingsHome bleibt unberührt.
    var callsOpen by rememberSaveable { mutableStateOf(false) }

    // ── W24 Lane C (T5): Content-Cap je expanded — NUR Container-Faltung ────
    // Die Kappe liest die LANE-A-SSOT ([WindowFoldRules.settingsContentCapDp]
    // via [LocalWindowSizeClass] — 640 dp in der Brief-Bandbreite ≤ 720):
    // KEINE eigene Falt-Kaskade, KEINE LocalConfiguration-Ableitung (T1/T7-
    // Befund). null (fold fehlt/Preview oder compact/medium) = offene Breite
    // = Bestand exakt. KEIN Karten-Struktur-Touch: das SettingsCard-Regelwerk
    // und je item bleiben unangetastet.
    val fold = LocalWindowSizeClass.current?.let {
        WindowFoldRules.compose(it.widthSizeClass, it.heightSizeClass)
    }
    val contentCapDp = fold?.let { WindowFoldRules.settingsContentCapDp(it) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
    LazyColumn(
        modifier = Modifier
            .then(
                if (contentCapDp != null) Modifier.widthIn(max = contentCapDp.dp)
                else Modifier
            )
            .fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            // W28 (Lane B, Inset-SSOT): EINE Boden-Quelle je Zustand. K4-Bestand
            // (48 dp Haus-Touch-Raum, Audit S1 „Screen-Ende knapp“) bleibt;
            // NEU: die Gesture-Säule zählt NUR EINMAL — lebt die Tab-Leiste
            // ÜBER der Säule (kompaktes Portrait), trägt SIE die Säule intern
            // und der Screen rechnet NUR die 48 dp (vorher: Säule nochmals
            // hier → auf Play-36 — targetSdk 36 = erzwungenes edge-to-edge —
            // doppelter Boden-Abstand; auf github-34 schliefen die Insets mit
            // 0 dp). Je Rail-Verband (Tablet/Handy-Landschaft, kein Bar-Slot)
            // trägt der SCREEN die Säule: 48 dp + navigationBars. Der
            // Gesture-Säulen-Schutzwortlaut bleibt unverändert.
            // Lane F (Reviewer-Auflage): die Formel lebt NUR noch im
            // SSOT-Helfer ([WindowFoldRules.bottomBarFloorPadding]) —
            // wertidentisch, github-34 = 0-dp-Delta unverändert.
            bottom = WindowFoldRules.bottomBarFloorPadding(fold),
        ),
        // Redesign-Regelwerk: SEKTION-Abstand = 16 dp (P2a).
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Bestehende Gruppen (Slot, 16 dp Rhythmus innerhalb). W16: OHNE
        // SettingsHead-Zeile davor (P27 — das ℹ️ lebt dauerhaft an der TopBar).
        // ── W26: PAAR-TOR-BANNER am SCREEN-RAND (2. Montage-Ort, KDoc
        // PairingRequestPanel Z.532-535) — ERSTES item, sichtbar OHNE Rollen:
        // die Geräte-Karte ist STD zugeklappt; eine offene pairing.request
        // (TTL 600 s) verschwindet ehrlich nach Ablauf (pairingExpired) bzw.
        // nach wirksamem Senden (dismissPairingFrame — derselbe Verband wie
        // in der Karte; KEIN Doppel-Behandeln, die TTL-Uhr läuft je Mount
        // eigenständig aus dem GLEICHEN Frame).
        item(key = "pairingBanner") {
            if (pendingPairing != null) {
                PairingRequestPanel(
                    pending = pendingPairing,
                    existingAliases = devices.map { it.alias },
                    onPairingDecision = onPairingDecision,
                )
            }
        }
        item(key = "legacyGroups") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { legacyGroups() }
        }
        item(key = "permissions") {
            PermissionsSettingsCard(
                expanded = permissionsOpen,
                onExpandedChange = { permissionsOpen = it },
            )
        }
        // W21 ASSISTENT-PAKET (Verdrahtung MERGE-LANE 18.11., W23-7 U7-MERGE:
        // EIN Eintrag — die frühere ZWEITE Einzel-Karte (alt item „assistant“,
        // W13 AssistantRoleCard) ist entgangen, ihr Rollen-Anfrage-Weg lebt in
        // dieser Karte, ihr item-Key übernimmt die Position) — G1 rendert die
        // Karte aus
        // den BESTEHENDEN Assistenten-Strings (Rolle/Overlay-Stand liest sie
        // je Kartenöffnung selbst); G2/G5 nehmen die Texte als Parameter
        // ([AssistantCardTexts]) — hier je Locale aus strings.xml ×3 gelöst.
        // G5 ehrlich „ohne Session“: der Session-Epoch-Verband lebt noch
        // nicht im Client (das GATE im screen-Capability-Pfad ist laut KDoc
        // Anschluss-Lane) — KEIN Fake-Schlüssel, kein stiller Schalter; die
        // Zeile trägt den noSession-Text und der Schalter bleibt disabled,
        // bis die Anschluss-Lane den Schlüssel anstöpselt (dann
        // consent/grant/revoke an Prefs/Persistenz hängen).
        item(key = "assistant") {
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
                coordinator = assistantCoordinator,
                // W28 (Lane B): die Assistenten-Ausgänge (Rollen-Anfrage
                // gescheitert/Fallback-Sprung verwehrt) melden sich wie jedes
                // andere Feedback bei DIE einer W19-Zentral-Snackbar — die
                // System-Toasts dieser Karte waren der letzte W20-Rest.
                onFeedback = onFeedback,
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
            // W26: devices + die Paar-Tor-Verdrahtung (pairingFrame/onPairing-
            // Decision) sind GEHOBEN nach oben (2. Montage-Ort des
            // PairingRequestPanel am Screen-Rand) — dieses item liest sie;
            // die Karte teilt denselben Verband (kein Doppel-Verhalten).
            // W23-9 (TEAM): Umbenennen-Lauf — Warten-Handle + Ack aus den
            // RelayClient-StateFlows; Snack je Antwort, Warten-Deckel
            // (ACK_TIMEOUT_MS) mit EINER „später“-Zeile, KEIN Wiederhol-Senden
            // (kein Loop), kein Crash, wenn der Koordinator-Frame noch fehlt
            // (alter Relay: Senden verweigert, ehrliche Zeile).
            val renamePending by RelayClient.deviceAliasTarget.collectAsState()
            val renameAck by RelayClient.deviceAliasAck.collectAsState()
            // Ack-„einmal“-Merker: per Einstellungs-Öffnung gesehene Acks
            // (Identität des Ack-Objekts) melden sich NICHT erneut — der
            // frische Senden-Verband räumt das Ack, also zählt nur der neue.
            var ackSeen by remember { mutableStateOf(RelayClient.deviceAliasAck.value) }
            val context = LocalContext.current
            LaunchedEffect(renamePending) {
                val handle = renamePending ?: return@LaunchedEffect
                kotlinx.coroutines.delay(DeviceAliasRules.ACK_TIMEOUT_MS)
                // Nach dem Deckel: unverändert kein Ack passend? → EINE
                // „später“-Zeile + Warten räumen (kein erneuter Ticker).
                if (RelayClient.deviceAliasTarget.value == handle &&
                    RelayClient.deviceAliasAck.value?.requestId != handle
                ) {
                    onFeedback(context.getString(R.string.device_alias_prompt))
                    RelayClient.clearDeviceAliasWait()
                }
            }
            LaunchedEffect(renameAck) {
                val ack = renameAck ?: return@LaunchedEffect
                if (ack === ackSeen) return@LaunchedEffect  // schon gemeldet (auch über Einstellungs-Öffnungen hinweg)
                ackSeen = ack
                when {
                    ack.ok -> onFeedback(context.getString(R.string.device_alias_saved))
                    ack.error != null -> onFeedback(context.getString(R.string.device_alias_error))
                    // ok=false OHNE Grund = unvollständiges Frame — ehrlich
                    // „später“ (kein Fake-Error, nie Fake-Erfolg).
                    else -> onFeedback(context.getString(R.string.device_alias_prompt))
                }
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
                // W26d (R5): der ENV-Stand der Räum-Frist je relay.state
                // (Frame-Kopf device_idle_days — null = alter Relay).
                idleDays = deviceIdleDays,
                expanded = devicesOpen,
                onExpandedChange = { devicesOpen = it },
                // W23-9 (VERTRAGS-KORREKTUR): Umbenennen der Kachel →
                // pairing.device_alias; das Kachel-Handle (entry.requestId)
                // ist die PRIMARY-Zielkennung (die device_id-Maske ist am Relay
                // NICHT eindeutig auflösbar — nur Länge) und wandert NUR als
                // Info-Feld mit. Fail-soft: Blank-Handle (alter Relay) = KEIN
                // Senden, ehrliche „später“-Zeile; fehlgeschlagener WS-Send =
                // dieselbe Zeile; KEIN Wiederhol-Senden (kein Loop). Das Ack
                // (pairing.device_alias_ok: ok/error/request_id/alias/…) wird
                // oben aus dem StateFlow gemeldet (Snack).
                onRenameDevice = { entry, alias ->
                    val handle = entry.requestId.trim()
                    when {
                        handle.isBlank() ->
                            onFeedback(context.getString(R.string.device_alias_prompt))
                        RelayClient.sendDeviceAlias(handle, alias, entry.deviceId) -> Unit
                        else -> onFeedback(context.getString(R.string.device_alias_prompt))
                    }
                },
                // W23-9: dieselbe Snackbar (einheitlich Feedback, W19-Verband).
                onFeedback = onFeedback,
                // W26: GEHOBENE Faser (oben — 2. Montage-Ort Paar-Tor-Banner):
                // beide Montagen teilen denselben Freigabe-/Abweisungs-Verband.
                onPairingDecision = onPairingDecision,
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
        // ── W26 („Agent hinzufügen", Admin-Gerät-Welt je Anmelde-Tor) ──────────
        // Die Karte existiert NUR je NIE-WRONG aufgelöster Admin-Welt
        // (AgentInviteRules.isAdminDevice: eindeutig aufgelöste EIGENE Maske
        // MUSS admin tragen — mehrdeutig/unbekannt ⇒ verborgen, never-wrong).
        // W26d (F6): die MEHRDEUTIGE Maske (≥ 2 Geräte derselben Kennungs-
        // Länge — der Normalfall je zweier App-Phones mit 36-Zeichen-UUIDs)
        // versteckt die Karte NICHT mehr STUMM: der ehrliche Hinweis-Text
        // bleibt sichtbar (die Verwaltungs-Welt erfindet die Admin-Wahrheit
        // NIE — je Mehrdeutigkeit kein Antrieb, aber KEINE stumme Funktions-
        // Lücke mehr).
        item(key = "agentInvites") {
            // W26: devices ist GEHOBEN (Paar-Tor-Banner oben) — dieses item
            // liest denselben Stand (kein zweites collectAsState).
            val ownMask = DeviceRules.ownMaskedDeviceId(
                devices, PairingManager.deviceId(),
            )
            val isAdmin = AgentInviteRules.isAdminDevice(
                devices.map { AgentInviteRules.AdminDeviceRef(it.deviceId, it.admin) },
                ownMask,
            )
            val maskAmbiguous = !isAdmin && DeviceRules.ownMaskAmbiguous(
                devices, PairingManager.deviceId(),
            )
            if (isAdmin) {
                val invites by RelayClient.invites.collectAsState()
                val createResult by RelayClient.inviteCreate.collectAsState()
                val invitePending by RelayClient.invitePending.collectAsState()
                val inviteResult by RelayClient.inviteResult.collectAsState()
                // W26d (F3): der End-Stand des zuletzt angezeigten Codes
                // (LAPSED/REVOKED je TTL-Ablauf/Widerruf).
                val inviteCodeEnd by RelayClient.inviteCodeEnd.collectAsState()
                // Klappe je Saveable-Verband (Parität der Geräte-Karte).
                var invitesOpen by rememberSaveable { mutableStateOf(false) }
                val context = LocalContext.current
                // Ack-Staffel (Parität des Umbenennen-Verbands): je Antwort EIN
                // Snack — CREATE ok = Erfolgs-Wort je TTL, REVOKE ok =
                // Widerrufs-Wort, LIST ok = still (die Liste selbst ist das
                // Sicht-Signal), Fehl/not-supported je ehrlicher Snack, KEIN
                // Crash. CREATE ohne Code-Feld snackt ehrlich als Fehler.
                var inviteAckSeen by remember { mutableStateOf(RelayClient.inviteResult.value) }
                LaunchedEffect(inviteResult) {
                    val result = inviteResult ?: return@LaunchedEffect
                    if (result === inviteAckSeen) return@LaunchedEffect
                    inviteAckSeen = result
                    when {
                        !result.ok ->
                            // W26d (F1): Klartext je Fehl-Familie — „(too-many)“
                            // wird „(zu viele offene Einladungen …)“; der rohe
                            // Code bleibt nur bei UNBEKANNTEM Code sichtbar.
                            onFeedback(
                                context.getString(
                                    R.string.invite_error_snack,
                                    errorWordOrDefault(context, result.error),
                                ),
                            )
                        result.op == AgentInviteRules.Op.CREATE -> {
                            // TTL je Code-Stand (der Ack trägt sie am Ergebnis,
                            // nie am Faser-Fehler-Feld — KEIN Fake-„60“).
                            val ttl = createResult?.ttlMin
                            onFeedback(
                                if (ttl != null && ttl > 0L) {
                                    context.getString(R.string.invite_created_snack, ttl)
                                } else {
                                    context.getString(R.string.invite_created_snack_open)
                                },
                            )
                        }
                        result.op == AgentInviteRules.Op.REVOKE ->
                            onFeedback(context.getString(R.string.invite_revoked_snack))
                        else -> Unit // LIST ok = still (Sicht-Signal = die Liste)
                    }
                }
                // Warten-Deckel je Lauf (Kein-Loop-Verband, Parität Umbenennen).
                LaunchedEffect(invitePending) {
                    val pending = invitePending ?: return@LaunchedEffect
                    kotlinx.coroutines.delay(DeviceAliasRules.ACK_TIMEOUT_MS)
                    if (RelayClient.invitePending.value == pending) {
                        RelayClient.clearInviteWait()
                        onFeedback(context.getString(R.string.invite_timeout_snack))
                    }
                }
                // Liste frisch je Kartenöffnung (der admin-Blick = der einzige
                // Sendeträger der LIST-Welt; je Erfolg frisht die Karte ohnehin).
                LaunchedEffect(invitesOpen) {
                    if (invitesOpen) RelayClient.sendInviteList()
                }
                AgentInviteCard(
                    invites = invites,
                    createResult = createResult,
                    pending = invitePending,
                    expanded = invitesOpen,
                    onExpandedChange = { invitesOpen = it },
                    // W26d (F3): End-Stand + TTL-Räumung je Uhr — der tote Code
                    // zeigt sein Wort statt eines ewig lebendigen Codes.
                    codeEnd = inviteCodeEnd,
                    onCodeExpired = { RelayClient.clearLapsedInviteCode() },
                    // Senden fail-soft je Deckel: kein WS-Kanal = ehrlicher Snack,
                    // KEIN Crash, KEIN Wiederhol-Senden (Kein-Loop).
                    onCreate = {
                        if (!RelayClient.sendInviteCreate()) {
                            onFeedback(context.getString(R.string.invite_send_failed_snack))
                        }
                    },
                    onRevoke = { inviteId ->
                        if (!RelayClient.sendInviteRevoke(inviteId)) {
                            onFeedback(context.getString(R.string.invite_send_failed_snack))
                        }
                    },
                    // Kopieren je Haus-Verband (PairingCard): SENSITIVE-Marked
                    // Clipboard + eigener Snack (W26d F7: der Code ist ein
                    // AGENT-EINLADUNGSCODE — nicht der Kopplungscode-Wortlaut).
                    onCopy = { code ->
                        copyInviteCode(context, code)
                        onFeedback(context.getString(R.string.invite_copy_snack))
                    },
                )
            } else if (maskAmbiguous) {
                // W26d (F6): der ehrliche Hinweis statt der stummen Karte —
                // der Nutzer erfährt, WARUM die Verwaltung fehlt und WAS zu
                // tun ist (Geräte eindeutig benennen, Einstellungen › Geräte).
                Card(
                    modifier = modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
                ) {
                    Text(
                        stringResource(R.string.invite_mask_ambiguous_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
    // Box (Content-Cap-Zentrierung) schließt — oben.
    }
}

/**
 * W26d (F1): Klartext-Wort je Fehl-Familie ([ErrorWordsRules.plainWordRes])
 * oder — je UNBEKANNTEM/leerem Code — der rohe Code selbst (ehrlich, nie ein
 * erfundenes Klartext-Wort; leer = "?" wie im Bestand).
 */
private fun errorWordOrDefault(context: android.content.Context, error: String?): String =
    ErrorWordsRules.plainWordRes(error)?.let { context.getString(it) } ?: (error ?: "?")

