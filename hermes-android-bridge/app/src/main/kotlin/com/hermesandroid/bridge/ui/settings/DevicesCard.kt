package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.DeviceAliasRules
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.FlipsiSpacing
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import kotlinx.coroutines.delay

/**
 * B7 (Multi-Gerät, Plan docs/Multigeräte-Konzept Kap. 4.2): die Geräte-
 * Verwaltung in „Einstellungen“ — Gerätekarte (Liste aus relay.state
 * devices[]), Kopplungs-Anfrage-Panel (pairing.request-Tor) und der 2-stufige
 * Entfernen-Lauf. Folgt dem Kartengewebe des Settings-Redesigns: Kopfhöhe
 * ≥ 48 dp, Zeilen-Rhythmus 8 dp, Sektions-Abstand 16 dp (SettingsHome),
 * Touch-Ziele ≥ 48 dp, Kontraste ≥ 4,5:1 auf Panel (verifiziert:
 * SignalGreen 7,08 · TextSecondary 6,63 · SignalAmber/EmberDim 5,77 ·
 * SignalRedText 6,18), Zustand nie NUR über Farbe (Online/Offline steht als
 * Text daneben; der Punkt ist Deko, contentDescription = null).
 *
 * STATE-VERBAND (APP-ANSCHLUSS-Verdrahtung, Multi-Gerät):
 *  - devices: relay.state devices[] — StateFlow aus RelayClient
 *    (parseRelayStateDevices, B4-Kontrakt HEAD 733504c: {device_id =
 *    NUR Maske "<masked:N>", alias — Backend setzt keinen, admin, online,
 *    last_seen (Unix-ms, 0 = unbekannt), request_id = B4-Revoke-Handle}).
 *    Offline-Geräte bleiben sichtbar (Registry ∪ Slots); ohne die
 *    devices[]-Liste im Frame (alter Relay vor B4) zeigt die Karte den
 *    ehrlichen Leer-Hinweis.
 *  - pendingPairing: StateFlow der B5-Lane über den pairing.request-Frame
 *    (TTL 600 s), APP-ANSCHLUSS: SettingsHome mappt PairingFrameRules.
 *    PairingFrame → DeviceRules.PairingRequest. Diese Komponente nimmt die
 *    Entscheidung nur als [onPairingDecision]-Callback entgegen — die
 *    Verdrahtung sendet pairing.approve/pairing.deny mit der request_id
 *    (Relay löst device_id AUCH über request_id auf) und räumt den
 *    StateFlow bei erfolgreichem Senden (dismissPairingFrame).
 *  - ownMaskedDeviceId: maskierte Form der EIGENEN Geräte-Kennung — von
 *    SettingsHome NIE-WRONG über DeviceRules.ownMaskedDeviceId aufgelöst
 *    (Backend-Maske = "<masked:{len}>"; bei MEHRDEUTIGER Maske — je zwei
 *    App-Geräte tragen dieselbe Länge-Maske — bewusst null ⇒ KEIN Chip,
 *    never-wrong vor always-right).
 *  - REVOKE 2-STUFIG: „Entfernen“ öffnet den Bestätigungs-Dialog (Warnung:
 *    Gerät wird abgemeldet + E2E-Sitzung endet), der Lauf ruft erst nach
 *    der Bestätigung [onRevokeDevice] — die Verdrahtung sendet das
 *    device_revoke-Frame mit dem EPHEMEREN B4-Handle (request_id aus dem
 *    DeviceEntry; OHNE Ziel = Self-Revoke am Relay). Das Backend löst das
 *    Ziel über das Handle auf (die App hält NIEMALS rohen Kennungen), kickt
 *    das Ziel mit Close-Code 4404 ("device-revoked") und broadcastet den
 *    neuen relay.state-Stand; Freigabe für FREMDE Geräte = Admin-only
 *    (fail-closed am Relay, kein App-Fallback — HEAD 733504c).
 *  - W23-9 UMBENENNEN (W23-9): je Kachel ein Edit-Button (Zeile 1 rechts)
 *    → Namens-Dialog (leeres Vorschlagsfeld, „Speichern“ nur bei wirksamem
 *    Alias) → [onRenameDevice] (Kachel + Alias). DIE VERDRAHTUNG
 *    (SettingsHome) sendet das pairing.device_alias-Frame nach dem
 *    VERTRAGS-KORREKTUR-Verband: das EINDEUTIGE request_id-Handle der
 *    Kachel ist die PRIMARY-Zielkennung (die Maske "<masked:N>" allein ist
 *    am Relay NICHT eindeutig auflösbar — zwei 36-Zeichen-UUIDs kollidieren
 *    auf dieselbe Länge-Maske), die Maske wandert NUR als Info-Feld mit;
 *    Antwort = pairing.device_alias_ok {ok, error?, request_id, alias,
 *    device_id_masked} (Snack ok/error/„später“, kein Wiederhol-Senden —
 *    kein Loop). Blank-Handle (alter Relay) = KEIN Senden, ehrliche
 *    „später“-Meldung (fail-soft, SettingsHome-Verdrahtung).
 *
 * KARTEN-LAYOUT (TEIL d, Befund „Admin-Zeile bricht das Wort“): die
 * Badges stehen NICHT mehr gepresst neben den Namen —
 *   Zeile 1: Alias (einzeilig, ellipsisiert — NIE mitten-Wort-Umbruch) +
 *            „Dieses Gerät“-Badge + Umbenennen-Edit rechts,
 *   Zeile 2: „Admin“-Badge (eigene Zeile, softWrap=false, maxLines=1),
 *   Zeile 3: Online-Status (Dot + Text, softWrap=false, maxLines=1) +
 *            last_seen-Alter (dash_*-Altersfaser).
 * Entfernen bleibt das rechte ≥ 48-dp-Text-Ziel.
 *
 * STRING-BEDARF (bewusst als Literale im Code, KEINE Eingriffe in strings.xml
 * — die Strings-Lane trägt die im Report B7 notierten dev_*-Keys nach und
 * tauscht diese Literale; REUSE ohne neue Keys: chat_filter_online/offline,
 * common_cancel, dash_*-Altersfaser über DeviceRules.lastSeenLabelRes):
 *  dev_card_title, dev_card_hint, dev_empty, dev_this_device,
 *  dev_admin_badge, dev_remove_action, dev_revoke_title, dev_revoke_text,
 *  dev_revoke_confirm, dev_pairing_title, dev_pairing_alias_suggest,
 *  dev_pairing_meta, dev_pairing_meta_noip, dev_pairing_ttl,
 *  dev_pairing_accept, dev_pairing_deny
 *  — W23-9 (Strings-Lane hat geliefert): device_rename, device_alias_label,
 *  device_alias_save (Dialog); chat_filter_online/offline (Status-Zeile),
 *  common_cancel (Dialog).
 */
@Composable
fun DevicesCard(
    devices: List<DeviceRules.DeviceEntry>,
    pendingPairing: DeviceRules.PairingRequest?,
    ownMaskedDeviceId: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // APP-ANSCHLUSS: beide Callbacks sind REAL verdrahtet (SettingsHome) —
    // approve/deny → sendPairingApprove/sendPairingDeny; Revoke →
    // sendDeviceRevoke mit dem B4-Handle. Keine Platzhalter-Defaults mehr.
    onPairingDecision: (requestId: String, approve: Boolean) -> Unit,
    /**
     * APP-ANSCHLUSS: trägt das EPHEMERE B4-Revoke-Handle (relay.state
     * devices[].request_id) des bestätigten Eintrags — der Zielträger des
     * device_revoke-Frames (die App hält KEINE rohen Kennungen, PII-Regel).
     * Leer = alter Relay vor B4 → die Verdrahtung verweigert das Senden
     * (fail-closed statt versehentlichem Self-Revoke ohne Ziel).
     */
    onRevokeDevice: (requestId: String) -> Unit,
    /**
     * W23-9: „Speichern“ des Namens-Dialogs — trägt die KACHEL (mit ihrem
     * eindeutigen request_id-Handle = PRIMARY-Zielkennung des
     * pairing.device_alias-Frames, VERTRAGS-KORREKTUR) und den gewünschten
     * Alias zur Verdrahtung (SettingsHome sendet fail-soft + Snack).
     */
    onRenameDevice: (entry: DeviceRules.DeviceEntry, alias: String) -> Unit,
    /**
     * W23-9-Verband: Feedback-Kanal für Alias-Aktionen (Snr-Snack der
     * SettingsHome-Verdrahtung — "später"-Hinweis bei fehlendem Handle,
     * Erfolg/Fehler nach device_alias_ok-Ack). Default = inerter Kanal,
     * damit Bestands-Aufrufer ohne Regression weiterlaufen.
     */
    onFeedback: (String) -> Unit = {},
    /**
     * W26d (R5): der ENV-Stand der Räum-Frist je relay.state-Frame
     * (`device_idle_days`) — null = Stand UNBEKANNT (alter Relay, Bestand-
     * Wort „14 Tage“), 0 = Sweep AUS (KEIN Hinweis statt falscher
     * Behauptung), n > 0 = das Hinweis-Wort mit der ECHTEN Zahl.
     */
    idleDays: Long? = null,
) {
    val ordered = remember(devices) { DeviceRules.sortedDevices(devices) }
    // 2. Stufe des Revoke-Laufs: erst der Klick auf „Entfernen“ besetzt das
    // Ziel, erst die Bestätigung im Dialog sendet (APP-ANSCHLUSS-Verdrahtung).
    var revokeTarget by remember { mutableStateOf<DeviceRules.DeviceEntry?>(null) }
    // W23-9: Namens-Dialog der Kachel (Kopfhöhe: nur Klick auf den Edit
    // besetzt das Ziel, erst „Speichern“ im Dialog schickt es an die
    // Verdrahtung — kein Senden bei Dialog-Abbruch).
    var renameTarget by remember { mutableStateOf<DeviceRules.DeviceEntry?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Kopf: ganze Zeile ist das Touch-Feld (≥ 48 dp, Role.Button —
            // TalkBack meldet den Klappe-Zustand), Muster SettingsCard.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = FlipsiSpacing.RowMin)
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = onExpandedChange,
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dev_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
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
                    Text(
                        stringResource(R.string.dev_card_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    PairingRequestPanel(
                        pending = pendingPairing,
                        existingAliases = ordered.map { it.alias },
                        onPairingDecision = onPairingDecision,
                    )
                    if (ordered.isEmpty()) {
                        // Ehrlichkeit statt Fake-Inhalt: das relay.state-Gerätebein
                        // listet Registry ∪ LIVE-Slots (KDoc oben — offline Geräte
                        // bleiben sichtbar, online=false + last_seen). Eine LEERE
                        // Liste ist also ehrlich: weder bekanntes (Registry-)Gerät
                        // noch Live-Verbindung — der dev_empty-Text sagt das.
                        Text(
                            stringResource(R.string.dev_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                    } else {
                        ordered.forEachIndexed { index, entry ->
                            DeviceRow(
                                entry = entry,
                                fallbackIndex = index,
                                isOwn = DeviceRules.isOwnDevice(entry, ownMaskedDeviceId),
                                // W26d (R5): der ENV-Stand der Räum-Frist — der
                                // Hinweis folgt dem Stand, nie der Form allein.
                                idleDays = idleDays,
                                onRemove = { revokeTarget = entry },
                                onRename = { renameTarget = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    revokeTarget?.let { target ->
        RevokeConfirmDialog(
            entry = target,
            // TEIL c + Jäger-Fix (W23-Fix2): Fallback-Index = Kachel-Position
            // über die Objekt-Identität (Position in ordered), NICHT über die
            // device_id-Maske — die Maske lautet bei allen UUID-Geräten
            // '<masked:36>' und wäre NICHT eindeutig (indexOfFirst hätte
            // immer 0 geliefert → Dialog hätte das falsche Gerät benannt).
            fallbackIndex = ordered.indexOfFirst { it === target }
                .coerceAtLeast(0),
            onConfirm = {
                // APP-ANSCHLUSS: das EPHEMERE B4-Revoke-Handle (request_id)
                // ist der Zielträger — das Relay löst es auf und kickt das
                // Ziel mit Close 4404 ("device-revoked"). Leerer Handle
                // (alter Relay vor B4) wird fail-closed oben verworfen,
                // statt als Frame OHNE Ziel = versehentlichem Self-Revoke
                // zu wirken.
                onRevokeDevice(target.requestId)
                revokeTarget = null
            },
            onDismiss = { revokeTarget = null },
        )
    }

    // W23-9: Namens-Dialog — „Speichern“ reicht Kachel + Alias an die
    // Verdrahtung (onRenameDevice → pairing.device_alias mit dem Kachel-
    // Handle als PRIMARY-Ziel); Abbruch räumt ohne Senden.
    renameTarget?.let { target ->
        RenameDialog(
            entry = target,
            onConfirm = { value ->
                onRenameDevice(target, value)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/**
 * EINE Geräte-Zeile — W23-9-KARTEN-LAYOUT (Befund: „Admin ist nicht in
 * einer Zeile, das n in der zweiten — nicht schön“): die Badges quetschen
 * sich NICHT mehr neben den Namen, sie stehen GESTAPELT —
 *   Zeile 1: Alias (softWrap=false, maxLines=1, ellipsis — NIE mitten-Wort-
 *            Umbruch) + „Dieses Gerät“-Badge + Umbenennen-Edit-Icon rechts
 *            (48 dp Touch-Ziel, contentDescription = device_rename),
 *   Zeile 2: „Admin“-Badge für sich (softWrap=false, maxLines=1),
 *   Zeile 3: Online-Status (Dot + Text, softWrap=false, maxLines=1) — der
 *            Punkt wandert hierher (Zustand bleibt IMMER Text neben ihm,
 *            nie Farb-Only) und trägt die last_seen-Altersfaser.
 * Entfernen bleibt das rechte Warnrot-Text-Ziel (≥ 48 dp; Text-Kontrast
 * 6,18:1, SignalRed selbst bleibt der 4,31:1-Grafikton im Text verboten).
 */
@Composable
private fun DeviceRow(
    entry: DeviceRules.DeviceEntry,
    fallbackIndex: Int,
    isOwn: Boolean,
    // W26d (R5): der ENV-Stand der Räum-Frist (null = alter Relay).
    idleDays: Long? = null,
    onRemove: () -> Unit,
    onRename: () -> Unit,
) {
    val alias = DeviceRules.sanitizeAlias(entry.alias, fallbackIndex)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FlipsiSpacing.RowMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Zeile 1: Name + „Dieses Gerät“ + Umbenennen rechts.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    alias,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    // KEIN mitten-Wort-Umbruch: eine Zeile, sonst (…).
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isOwn) {
                    Spacer(Modifier.width(8.dp))
                    OwnDeviceChip()
                }
                Spacer(Modifier.width(4.dp))
                // W23-9: Umbenennen-Edit je Kachel (Dialog, TEIL a) —
                // Standard-IconButton (48 dp Touch-Ziel, a11y-Regel).
                IconButton(onClick = onRename) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.device_rename),
                        tint = FlipsiColors.TextSecondary,
                    )
                }
            }
            // Zeile 2: Admin-Badge EIGENE Zeile — kein gepresster Umbruch.
            if (entry.admin) {
                Spacer(Modifier.height(4.dp))
                AdminBadge()
            }
            // Zeile 3: Online-Status (Dot + Text, ehrlich, nie Farb-Only).
            val status = deviceStatusLine(entry)
            if (status.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(start = 2.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                // Online grün (7,08:1 auf Panel), offline grau-
                                // Textton (6,63:1) — beides ≥ 3:1 Grafik UND
                                // ≥ 4,5:1; der Zustand steht zusätzlich IMMER
                                // als Text daneben (keine Farb-Only-Info).
                                if (entry.online) FlipsiColors.SignalGreen else FlipsiColors.TextSecondary,
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                        // Auch der Status bricht nie mitten im Wort.
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // W26 (14-Tage-Räumung ehrlich sichtbar): der Backend-Sweep
            // (_registry_sweep, W23-Besitzer-Regel) löscht Geräte >N Tage ohne
            // Verbindung — ausgenommen Admin (Selbst-Lockout-Schutz). Der
            // Hinweis steht NUR dort, wo die Frist tatsächlich läuft
            // (Gate: DeviceRules.idleRemovalHintApplies — offline,
            // non-admin, last_seen bekannt).
            //
            // W26d (R5): der ENV-Stand (`device_idle_days` je relay.state)
            // macht das Wort ehrlich — 0 (Sweep AUS) = KEIN Hinweis statt
            // einer Behauptung, die nie wahr wird; n > 0 = die ECHTE Zahl;
            // null (alter Relay) = der Bestand „14 Tage" (Stand unbekannt).
            if (DeviceRules.idleRemovalHintApplies(entry, idleDays)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (idleDays != null && idleDays > 0L) {
                        stringResource(R.string.dev_idle_removal_hint_days, idleDays)
                    } else {
                        stringResource(R.string.dev_idle_removal_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                )
            }
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
        ) {
            Text(
                stringResource(R.string.dev_remove_action),
                color = FlipsiColors.SignalRedText,
            )
        }
    }
}

/**
 * Status-Zeile (ehrlich kurz): Online (chat_filter_online — REUSE, KEIN-
 * DOPPEL), Offline (chat_filter_offline) + relative last_seen-Zeit
 * (dash_*-Altersfaser; lastSeenMs 0 = unbekannt → KEIN Alter, siehe
 * Frame-Fehlstelle).
 */
@Composable
private fun deviceStatusLine(entry: DeviceRules.DeviceEntry): String {
    val age = DeviceRules.lastSeenLabelRes(entry.lastSeenMs, System.currentTimeMillis())
    return when {
        entry.online -> stringResource(R.string.chat_filter_online) // reuse: „Online“
        age != null -> stringResource(R.string.chat_filter_offline) + // reuse: „Offline“
            " · " + resolvedLabel(age)
        else -> stringResource(R.string.chat_filter_offline)
    }
}

/**
 * W23-9 (TEIL a): Namens-Dialog der Kachel — Vorschlagsfeld mit DEM Kachel-
 * Alias als Startwert (leere Kachel = leeres Feld: der generische „Gerät N“-
 * Fallback gehört NICHT ins Eingabfeld), „Speichern“ nur bei wirksamem Alias
 * (normalisiert nichtleer), Abbruch = common_cancel (REUSE, KEIN-DOPPEL).
 * Der Sendelauf liegt bei der Verdrahtung (SettingsHome →
 * RelayClient.sendDeviceAlias mit dem Kachel-Handle als PRIMARY-Ziel).
 */
@Composable
private fun RenameDialog(
    entry: DeviceRules.DeviceEntry,
    onConfirm: (alias: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Start = Kachel-Alias, wie er gemeldet ist (nicht der Anzeige-Fallback).
    var text by remember { mutableStateOf(entry.alias.trim().take(DeviceRules.ALIAS_MAX_CHARS)) }
    // Speichern nur bei wirksamem Alias (Frame-Hygiene, DeviceAliasRules).
    val confirmEnabled = remember(text) {
        DeviceAliasRules.normalizeAliasInput(text).isNotEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.device_rename),
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.device_alias_label)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = confirmEnabled,
                onClick = { onConfirm(text) },
                modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
            ) {
                Text(stringResource(R.string.device_alias_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
            ) {
                Text(stringResource(R.string.common_cancel)) // reuse „Abbrechen“
            }
        },
    )
}

/**
 * LabelRes-Auflösung — W15c-Faser (Muster DashboardScreen.labelRes): Strings
 * per stringResource, Plural-Ressourcen per getQuantityString (Mengen-Zahl
 * zugleich count). Auflösung erst bei der Anzeige — Sprachwechsel wirkt
 * sofort, kein eingefrorener Text.
 */
@Composable
private fun resolvedLabel(label: DashboardRules.LabelRes): String =
    if (label.count != null) {
        LocalContext.current.resources
            .getQuantityString(label.res, label.count, *label.args.toTypedArray())
    } else {
        stringResource(label.res, *label.args.toTypedArray())
    }

/** „Dieses Gerät“-Chip: Kontur + Text in SignalGreen (≥ 6,3:1 auf Panel/Elev).
 *  W23-9 (Karten-Layout): Badge bricht NIE mitten im Wort (softWrap=false,
 *  maxLines=1) — mehr Zeilen-Raum bekommt es über die eigene Zeile darunter.
 */
@Composable
private fun OwnDeviceChip() {
    Text(
        stringResource(R.string.dev_this_device),
        style = MaterialTheme.typography.labelSmall,
        color = FlipsiColors.SignalGreen,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .border(1.dp, FlipsiColors.SignalGreen, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Admin-Vermerk: SignalAmber-Text auf EmberDim (5,77:1 ≥ 4,5:1) — der
 * Zustand steht als WORT, die Farbe ist nur Tracht (nie Farb-Only-Info).
 * W23-9 (Karten-Layout): EIGENE Zeile, Badge bricht NIE mitten im Wort
 * (softWrap=false, maxLines=1).
 */
@Composable
private fun AdminBadge() {
    Text(
        stringResource(R.string.dev_admin_badge),
        style = MaterialTheme.typography.labelSmall,
        color = FlipsiColors.SignalAmber,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(FlipsiColors.EmberDim, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Kopplungs-Anfrage-Panel (ANMELDE-TOR): alias-Vorschlag APP-GENERISCH
 * („Gerät N“, der Frame trägt keinen), maskierte Kennung + IP als Kontext,
 * Restlaufzeit sekündlich (TTL 600 s — abgelaufen verschwindet die Anfrage
 * ehrlich, kein Fake-Button), Annehmen/Ablehnen je ≥ 48 dp. Beide Aktionen
 * laufen als [onPairingDecision] mit request_id — die APP-ANSCHLUSS-
 * Verdrahtung (SettingsHome) sendet (pairing.approve / pairing.deny an das
 * Relay; device_id wird dort AUCH über request_id aufgelöst, die App sieht
 * nur die Maske) und räumt den StateFlow bei erfolgreichem Senden.
 *
 * Bewusst PUBLIC (nicht privat): die B5/B6-Lane kann das Tor-Banner auch
 * UNABHÄNGIG von der Geräte-Karte montieren (z. B. am Screen-Rand), weil die
 * TTL nur 10 min beträgt und die Karte zugeklappt sein kann — gleiche
 * Signatur, gleiche Verdrahtung.
 */
@Composable
fun PairingRequestPanel(
    pending: DeviceRules.PairingRequest?,
    existingAliases: List<String>,
    onPairingDecision: (requestId: String, approve: Boolean) -> Unit,
) {
    if (pending == null) return
    // TTL-Takt: sekündlicher now-Takt, Restart je NEUER Anfrage (Kondition
    // = request_id, kein Takt-Leak über Anfrage-Grenzen).
    var nowMs by remember(pending.requestId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pending.requestId) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    if (DeviceRules.pairingExpired(pending, nowMs)) return
    val suggestion = remember(pending.requestId, existingAliases) {
        DeviceRules.nextAliasSuggestion(existingAliases)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(FlipsiColors.PanelElev, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.dev_pairing_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.dev_pairing_alias_suggest, suggestion),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (pending.ip.isBlank()) {
                stringResource(R.string.dev_pairing_meta_noip, pending.maskedDeviceId)
            } else {
                stringResource(R.string.dev_pairing_meta, pending.maskedDeviceId, pending.ip)
            },
            style = MaterialTheme.typography.bodySmall,
            color = FlipsiColors.TextSecondary,
        )
        Text(
            stringResource(R.string.dev_pairing_ttl, DeviceRules.pairingCountdown(DeviceRules.pairingRemainingMs(pending, nowMs))),
            style = MaterialTheme.typography.bodySmall,
            color = FlipsiColors.TextSecondary,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onPairingDecision(pending.requestId, true) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = FlipsiSpacing.RowMin),
            ) {
                Text(
                    stringResource(R.string.dev_pairing_accept),
                )
            }
            OutlinedButton(
                onClick = { onPairingDecision(pending.requestId, false) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = FlipsiSpacing.RowMin),
            ) {
                Text(
                    stringResource(R.string.dev_pairing_deny),
                )
            }
        }
    }
}

/**
 * 2. REVOKE-STUFE: der Bestätigungs-Dialog trägt die Warnung (Abmeldung +
 * E2E-Sitzung endet), Abbrechen ist das bestehende common_cancel (KEIN-
 * DOPPEL), Bestätigen ist Text-Ziel ≥ 48 dp in Warnrot.
 */
@Composable
private fun RevokeConfirmDialog(
    entry: DeviceRules.DeviceEntry,
    fallbackIndex: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.dev_revoke_title),
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                stringResource(
                    R.string.dev_revoke_text,
                    DeviceRules.sanitizeAlias(entry.alias, fallbackIndex),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
            ) {
                Text(
                    stringResource(R.string.dev_revoke_confirm),
                    color = FlipsiColors.SignalRedText,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
            ) {
                Text(stringResource(R.string.common_cancel)) // reuse „Abbrechen“
            }
        },
    )
}
