package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.FlipsiSpacing
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import kotlinx.coroutines.delay

/**
 * B7 (Multi-Gerät, Plan docs/roadmap-multigeraete.md Kap. 4.2): die Geräte-
 * Verwaltung in „Einstellungen“ — Gerätekarte (Liste aus relay.state
 * devices[]), Kopplungs-Anfrage-Panel (pairing.request-Tor) und der 2-stufige
 * Entfernen-Lauf. Folgt dem Kartengewebe des Settings-Redesigns: Kopfhöhe
 * ≥ 48 dp, Zeilen-Rhythmus 8 dp, Sektions-Abstand 16 dp (SettingsHome),
 * Touch-Ziele ≥ 48 dp, Kontraste ≥ 4,5:1 auf Panel (verifiziert:
 * SignalGreen 7,08 · TextSecondary 6,63 · SignalAmber/EmberDim 5,77 ·
 * SignalRedText 6,18), Zustand nie NUR über Farbe (Online/Offline steht als
 * Text daneben; der Punkt ist Deko, contentDescription = null).
 *
 * STATE-VERBAND (bewusst entkoppelt für die B5/B6-Lane):
 *  - devices: relay.state devices[] (maskierte Kennung "<masked:N>", alias —
 *    Backend setzt heute keinen, admin, online). Die B5-Lane bindet den
 *    StateFlow aus RelayClient.handleRelayStateFrame an; bis dahin zeigt
 *    die Karte den ehrlichen Leer-Hinweis.
 *  - pendingPairing: StateFlow der B5-Lane über den pairing.request-Frame
 *    (TTL 600 s). Diese Komponente nimmt die Entscheidung nur als
 *    [onPairingDecision]-Callback entgegen (Platzhalter-Default: bewusst
 *    No-Op, die Verdrahtung sendet pairing.approve/pairing.deny mit der
 *    request_id — Relay löst device_id AUCH über request_id auf).
 *  - ownMaskedDeviceId: maskierte Form der EIGENEN Geräte-Kennung. Das
 *    Backend-Signal erlaubt heute KEIN sicheres Eigen-Matching ("<masked:N>"
 *    trägt nur die Länge — Fehlstelle Report B7): ohne exakte Überein-
 *    stimmung wird NICHT als „Dieses Gerät“ markiert (never-wrong).
 *  - REVOKE 2-STUFIG: „Entfernen“ öffnet den Bestätigungs-Dialog (Warnung:
 *    Gerät wird abgemeldet + E2E-Sitzung endet), der Lauf ruft erst nach
 *    der Bestätigung [onRevokeDevice] (Platzhalter-Default: No-Op).
 *    TODO(B-Lane/Backend): Es gibt NOCH KEINEN Revoke-Frame im Relay/Adapter
 *    („device-revoked“, Close-Code 4403-Familie, steht nur im Plan Kap. 4.2;
 *    HEAD 24acda0) — State + UI-Kette sind fertig, das reale Senden
 *    verdrahtet die B-Lane, sobald der Backend-Vertrag existiert.
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
 */
@Composable
fun DevicesCard(
    devices: List<DeviceRules.DeviceEntry>,
    pendingPairing: DeviceRules.PairingRequest?,
    ownMaskedDeviceId: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onPairingDecision: (requestId: String, approve: Boolean) -> Unit = { _, _ -> },
    onRevokeDevice: (deviceId: String) -> Unit = {},
) {
    val ordered = remember(devices) { DeviceRules.sortedDevices(devices) }
    // 2. Stufe des Revoke-Laufs: erst der Klick auf „Entfernen“ besetzt das
    // Ziel, erst die Bestätigung im Dialog sendet (Platzhalter-Callback).
    var revokeTarget by remember { mutableStateOf<DeviceRules.DeviceEntry?>(null) }

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
                        // listet NUR LIVE verbundene Slots — leer bleibt leer gemeldet.
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
                                onRemove = { revokeTarget = entry },
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
            onConfirm = {
                // TODO(B-Lane/Backend): Revoke-Frame existiert am HEAD 24acda0
                // NOCH NICHT — hier sendet die verdrahtete B-Lane später
                // (device_revoke o. /cert_revoke-Verband, Plan Kap. 4.2).
                onRevokeDevice(target.deviceId)
                revokeTarget = null
            },
            onDismiss = { revokeTarget = null },
        )
    }
}

/**
 * EINE Geräte-Zeile: Online-Punkt (grün/grau — Deko, Text trägt den Zustand),
 * generischer Alias, „Dieses Gerät“-Chip, Admin-Vermerk, last_seen relativ
 * (Altersfaser aus DeviceRules → bestehende dash_*-Keys), Entfernen als
 * Text-Ziel ≥ 48 dp in Warnrot (Text-Kontrast 6,18:1, SignalRed selbst
 * bleibt der 4,31:1-Grafikton und wandert deshalb NICHT in den Text).
 */
@Composable
private fun DeviceRow(
    entry: DeviceRules.DeviceEntry,
    fallbackIndex: Int,
    isOwn: Boolean,
    onRemove: () -> Unit,
) {
    val alias = DeviceRules.sanitizeAlias(entry.alias, fallbackIndex)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FlipsiSpacing.RowMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    // Online grün (7,08:1 auf Panel), offline grau-Textton
                    // (6,63:1) — beides ≥ 3:1 Grafik UND ≥ 4,5:1; der Zustand
                    // steht zusätzlich IMMER als Text daneben (keine
                    // Farb-Only-Info).
                    if (entry.online) FlipsiColors.SignalGreen else FlipsiColors.TextSecondary,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    alias,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isOwn) {
                    Spacer(Modifier.width(8.dp))
                    OwnDeviceChip()
                }
                if (entry.admin) {
                    Spacer(Modifier.width(8.dp))
                    AdminBadge()
                }
            }
            val meta = deviceMetaLine(entry)
            if (meta.isNotBlank()) {
                Text(
                    meta,
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
 * Meta-Linie: Offline-Zustand + relative last_seen-Zeit (ehrlich —
 * lastSeenMs 0 = „unbekannt“ zeigt KEIN Alter, siehe Frame-Fehlstelle).
 * Online trägt das bestehende chat_filter_online (KEIN-DOPPEL), offline das
 * bestehende chat_filter_offline + das dash_*-Alter.
 */
@Composable
private fun deviceMetaLine(entry: DeviceRules.DeviceEntry): String {
    val age = DeviceRules.lastSeenLabelRes(entry.lastSeenMs, System.currentTimeMillis())
    return when {
        entry.online -> stringResource(R.string.chat_filter_online) // reuse: „Online“
        age != null -> stringResource(R.string.chat_filter_offline) + // reuse: „Offline“
            " · " + resolvedLabel(age)
        else -> stringResource(R.string.chat_filter_offline)
    }
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

/** „Dieses Gerät“-Chip: Kontur + Text in SignalGreen (≥ 6,3:1 auf Panel/Elev). */
@Composable
private fun OwnDeviceChip() {
    Text(
        stringResource(R.string.dev_this_device),
        style = MaterialTheme.typography.labelSmall,
        color = FlipsiColors.SignalGreen,
        modifier = Modifier
            .border(1.dp, FlipsiColors.SignalGreen, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Admin-Vermerk: SignalAmber-Text auf EmberDim (5,77:1 ≥ 4,5:1) — der
 * Zustand steht als WORT, die Farbe ist nur Tracht (nie Farb-Only-Info).
 */
@Composable
private fun AdminBadge() {
    Text(
        stringResource(R.string.dev_admin_badge),
        style = MaterialTheme.typography.labelSmall,
        color = FlipsiColors.SignalAmber,
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
 * laufen als [onPairingDecision] mit request_id — der Platzhalter-Default in
 * [DevicesCard] ist bewusst No-Op, die B5/B6-Lane verdrahtet das Senden
 * (pairing.approve / pairing.deny an das Relay; device_id wird dort AUCH
 * über request_id aufgelöst, die App sieht nur die Maske).
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
                    DeviceRules.sanitizeAlias(entry.alias, 0),
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
