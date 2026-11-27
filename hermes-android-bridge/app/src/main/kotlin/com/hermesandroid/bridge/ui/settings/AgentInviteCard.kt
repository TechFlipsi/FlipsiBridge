package com.hermesandroid.bridge.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.AgentInviteRules
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.FlipsiSpacing
import com.hermesandroid.bridge.ui.FlipsiType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * W26 (Arbeits-Brief TEIL 2 „Agent hinzufügen“; Admin-Gerä-
 * te-Welt): die Einladungs-Karte in Einstellungen —
 *  1. „Einladungscode anlegen“ → agent.invite.create → Code-Anzeige (groß,
 *     kopierbar, je TTL-Hinweis; der Code lebt NUR im create-Ack — PII-Regel
 *     des Relays: nie in Listen/Logs).
 *  2. Liste aktiver Einladungen (agent.invite.list — NUR öffentliche Felder:
 *     hint je maskiertem Code-Blick, TTL, Verwendungen, [AgentInviteRules.
 *     InviteEntry]).
 *  3. Revoke je Zeile (2-stufig: Bestätigungs-Dialog, Parität Geräte-Revoke —
 *     destructive Aktion nennt Folgen, NIE Blind-Widerruf).
 *
 * STATE-VERBAND (die Verdrahtung lebt in SettingsHome — Parität des
 * Geräte-Umbenennen-Verbands): Warten je Op ([invitePending]), EINE Ergebnis-
 * Faser je Lauf ([inviteResult] — op + Urteil; die Snacks hängen an der
 * Verdrahtung), je Erfolg CREATE → Code-Stand ([inviteCreate]), je Erfolg
 * LIST/REVOKE → der frische LIST-Lauf ([invites]) = Sicht-Signal (Parität
 * cron.status Fresh-Feed). KEIN Loop, KEIN Fake-Erfolg.
 *
 * Verwaltung NUR je Admin-Geräte-Welt (die Verdrahtung löst NIE-WRONG via
 * [AgentInviteRules.isAdminDevice] — mehrdeutig/unbekannt ⇒ Karte verborgen).
 */
@Composable
fun AgentInviteCard(
    // Der JE LIST geparste Einladungs-Stand (leer = ehrlich leer).
    invites: List<AgentInviteRules.InviteEntry>,
    // Der CREATE-Erfolg je Lauf (Code-Anzeige; null = kein offener Erfolg).
    createResult: AgentInviteRules.CreateResult?,
    // Warten je Verwaltungs-Lauf (CREATE/LIST/REVOKE) — die Karte disabled
    // ihre Antriebe je Deckel (KEIN Doppel-Senden, Kein-Loop-Verband).
    pending: AgentInviteRules.Op?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    // W26d (F3): END-Zustand des zuletzt angezeigten Codes (LAPSED/REVOKED) —
    // die Karte zeigt das Status-Wort statt eines ewig lebendigen Codes.
    codeEnd: AgentInviteRules.InviteCodeEnd? = null,
    // W26d (F3): die TTL-Räumung je Uhr (die Karte ruft je 30-s-Takt nach
    // [AgentInviteRules.inviteLapsed] — der Client räumt + meldet LAPSED).
    onCodeExpired: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCreate: () -> Unit = {},
    /** W26: Revoke-Wunsch je Zeile (2-stufig — die Karte öffnet den Dialog). */
    onRevoke: (inviteId: String) -> Unit = {},
    onCopy: (code: String) -> Unit = {},
) {
    // 2. Stufe des Revoke-Laufs: erst der Klick auf „Widerrufen“ besetzt das
    // Ziel, erst die Bestätigung im Dialog sendet (destructive Aktion).
    var revokeTarget by remember { mutableStateOf<AgentInviteRules.InviteEntry?>(null) }
    // W26d (F3/F4): die Live-Uhr der TTL-Welt (30-s-Raster, Parität
    // CronScreen/DashboardScreen) — die Code-Ende-Welt und die Zeilen-
    // Restzeiten ticken mit der echten Zeit statt einer Erst-Momentaufname.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(createResult) {
        // je neuem Code-Verband frisht die Uhr (kein Alt-Ablauf-Verband).
        while (isActive) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    // TTL-Räumung je Uhr (der Client räumt NICHT von selbst — die Karte ist
    // die Sicht, die den Ablauf sieht; idempotent je Aufruf).
    LaunchedEffect(createResult, nowMs) {
        if (createResult != null && AgentInviteRules.inviteLapsed(createResult, nowMs)) {
            onCodeExpired()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Kopf: ganze Zeile ist das Touch-Feld (≥ 48 dp, Role.Button —
            // TalkBack meldet den Klappe-Zustand), Muster DevicesCard.
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
                Text(
                    stringResource(R.string.invite_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
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
                        stringResource(R.string.invite_card_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    OutlinedButton(
                        onClick = onCreate,
                        enabled = pending == null,
                        // Touch-Ziel-Zusatz: der Antrieb bleibt kompakt, aber
                        // ≥ 48 dp hoch (Haus-Standard, Parität SettingsCard).
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.invite_create_action))
                    }
                    if (pending != null) {
                        // Warten-Deckel (der Snack je Ausgang hängt an der
                        // Verdrahtung; hier nur der EHRliche Fortschritts-Balken).
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = FlipsiColors.Ember,
                        )
                        Text(
                            stringResource(R.string.invite_pending_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                    }
                    // ── Code-Anzeige je CREATE-Erfolg (groß, kopierbar, je
                    // TTL-Hinweis) — der Code lebt NUR hier + im create-Ack.
                    // W26d (F3): ist der Code ENDET (TTL-Ablauf/Widerruf),
                    // zeigt die Karte das Status-Wort statt des ewig
                    // lebendigen Codes (kein Geheimnis-Träger am Screen).
                    val endWord = when (codeEnd) {
                        AgentInviteRules.InviteCodeEnd.LAPSED ->
                            stringResource(R.string.invite_code_lapsed)
                        AgentInviteRules.InviteCodeEnd.REVOKED ->
                            stringResource(R.string.invite_code_revoked)
                        null -> null
                    }
                    if (endWord != null) {
                        Text(
                            endWord,
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                    } else {
                        createResult?.let { result ->
                            CodeDisplayBox(
                                code = result.inviteCode,
                                ttlMin = result.ttlMin,
                                onCopy = { onCopy(result.inviteCode) },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.invite_list_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (invites.isEmpty()) {
                        Text(
                            stringResource(R.string.invite_list_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            invites.forEachIndexed { index, entry ->
                                if (index > 0) {
                                    // Sichtbarer Trenner (LineStrong, Parität
                                    // der Cron-Zeilen-Welt).
                                    Spacer(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 2.dp)
                                            .height(1.dp)
                                            .background(FlipsiColors.LineStrong),
                                    )
                                }
                                InviteRow(
                                    entry = entry,
                                    nowMs = nowMs,
                                    onRevoke = { revokeTarget = entry },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 2. Stufe: Bestätigungs-Dialog je Revoke (destructive Aktion nennt
    // Folgen — Parität der Geräte-Revoke-Welt; Abbruch = nichts gesendet).
    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(stringResource(R.string.invite_revoke_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.invite_revoke_text,
                        target.hint.ifBlank { target.inviteId },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeTarget = null
                        // Bestätigt → EIN agent.invite.revoke-Frame (das Handle
                        // je list_ok-Zeile ist die PRIMARY-Zielkennung; blank =
                        // verweigert je Verdrahtung — ehrlicher Snack).
                        onRevoke(target.inviteId)
                    },
                ) { Text(stringResource(R.string.invite_revoke_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = FlipsiColors.PanelElev,
        )
    }
}

/**
 * EINE Einladungs-Zeile: hint (maskierter Code-Blick je PII-Regel) + Meta
 * je TTL/Verwendungen je ehrlich vorhandenem Feld + der Widerruf-Knopf
 * (48 dp Touch-Ziel, Parität Zeilen-Icons; kein Doppel-Senden — die Karte
 * disabled je pending).
 *
 * W26d (F4): die Meta-Zeile zeigt die RESTZEIT (je expiresAtMs + [nowMs])
 * statt der Anlagen-TTL — eine 55 Minuten alte Einladung (läuft in 5 Min.
 * aus) liest sich NIE wie „60 Min“ (unter 5 Min Rest: kritisch, rote Faser);
 * ohne expiry (alter Relay) bleibt das Anlage-TTL-Wort unverändert.
 */
@Composable
private fun InviteRow(
    entry: AgentInviteRules.InviteEntry,
    nowMs: Long,
    onRevoke: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.label.ifBlank {
                    entry.hint.ifBlank { stringResource(R.string.invite_hint_unlabeled) }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                inviteMetaText(entry, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (inviteMetaUrgent(entry, nowMs)) FlipsiColors.SignalRedText
                else FlipsiColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRevoke,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.invite_revoke_a11y),
                tint = FlipsiColors.TextPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Meta-Zeile je Zeile — je ehrlich vorhandenem Feld NUR der Teil (kein
 * Platzhalter-Geraffel): W26d (F4) je RESTZEIT („läuft in X Min. ab“, unter
 * 5 Min kritisch, abgelaufen je ehrlich), sonst Anlage-TTL (alter Relay
 * ohne expiry) + Verwendungen.
 */
@Composable
private fun inviteMetaText(
    entry: AgentInviteRules.InviteEntry,
    nowMs: Long,
): String {
    val parts = ArrayList<String>(2)
    AgentInviteRules.invitationTimeWord(
        expiresAtMs = entry.expiresAtMs,
        ttlMin = entry.ttlMin,
        nowMs = nowMs,
        resRemaining = R.string.invite_meta_remaining,
        resRemainingSoon = R.string.invite_meta_remaining_soon,
        resExpired = R.string.invite_meta_expired,
        resTtl = R.string.invite_meta_ttl,
    )?.let { parts += stringResource(it.res, it.minutes) }
    entry.usesLeft?.let { parts += stringResource(R.string.invite_meta_uses, it) }
    return parts.joinToString(" · ")
}

/** Kritisch-Rest (< 5 Min bzw. abgelaufen) — die rote Faser der Zeile. */
private fun inviteMetaUrgent(
    entry: AgentInviteRules.InviteEntry,
    nowMs: Long,
): Boolean = AgentInviteRules.invitationTimeWord(
    expiresAtMs = entry.expiresAtMs,
    ttlMin = entry.ttlMin,
    nowMs = nowMs,
    resRemaining = 0,
    resRemainingSoon = 0,
    resExpired = 0,
    resTtl = 0,
)?.urgent == true

/**
 * Der Code-Block je CREATE-Erfolg: groß, kopierbar ([SelectionContainer] +
 * Kopier-Knopf je Haus-Verband PairingCard), je TTL-Hinweis. Der Code lebt
 * NUR hier + im create-Ack (PII-Regel des Relays — nie in Listen/Logs).
 */
@Composable
private fun CodeDisplayBox(
    code: String,
    ttlMin: Long?,
    onCopy: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(FlipsiColors.PanelElev, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        code,
                        style = FlipsiType.Code,
                        color = FlipsiColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_paste),
                        contentDescription = stringResource(R.string.invite_copy_a11y),
                        tint = FlipsiColors.TextPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                if (ttlMin != null) stringResource(R.string.invite_ttl_hint, ttlMin)
                else stringResource(R.string.invite_code_display_hint),
                style = MaterialTheme.typography.labelSmall,
                color = FlipsiColors.TextSecondary,
            )
        }
    }
}

/**
 * Kopieren je Haus-Verband (BridgeScreen-PairingCard): ClipboardManager +
 * SENSITIVE-Markierung ab API 33 (Clipboard-Vorschau/Klartext-Anzeige
 * unterbleibt) — der Code ist ein Geheimnis-Träger.
 */
internal fun copyInviteCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val data = ClipData.newPlainText(
        context.getString(R.string.invite_card_title),
        code,
    )
    if (Build.VERSION.SDK_INT >= 33) {
        data.description.extras = android.os.PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(data)
}
