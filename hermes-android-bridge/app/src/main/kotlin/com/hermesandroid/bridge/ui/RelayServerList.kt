package com.hermesandroid.bridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.client.PinSource
import com.hermesandroid.bridge.server.RelayProfile
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.server.ServerProfileRules
import com.hermesandroid.bridge.ui.onboarding.OnboardingRules
import com.hermesandroid.bridge.ui.onboarding.PassCodeField
import java.text.DateFormat
import java.util.Date

/**
 * Welle 4 / App-Lane D — Multi-Server-Liste in Einstellungen → Server
 * (Sir-Spec verbatim: „Relay-Liste in Einstellungen (+Server hinzufügen),
 * je Server: URL+Code+Status+Letzt-Verbunden, Umschalten bewusst (Verbindung
 * trennen/wechseln mit Bestätigung)“).
 *
 * Verband je Zeile (Singletons-Faser): der AKTIVE Eintrag zeigt den
 * Live-Zustand der EINEN WebSocket-Kette (BridgeStatus), alle anderen sind
 * „Getrennt“ (getragene Daten: URL + Letzt-Verbunden + Code je Vault).
 * Umschalten = Bestätigungs-Dialog → ServerProfiles.connectViaProfile →
 * bewusstes disconnect/connect im RelayClient — kein Multi-WS, die
 * OkHttpClient-Fabrik (Lane-A-Pin-Verband) bleibt unangetastet.
 *
 * Code je Profil: Anzeige bewusst REDUCED-PASS (PassCodeField verborgen,
 * Einblenden pro Tipp) — der Code wird beim Bearbeiten aus dem Vault gelesen
 * (ServerProfiles.codeOf) und NIE geloggt.
 *
 * DRILL-ACKER (Profil-Wechsel-Beweis-Stellen, Emulator):
 *  1. „+ Server hinzufügen“ → Dialog erscheint; URL-Plausi (leer → Fehler),
 *     Code verborgen → Einblenden-Schalter zeigt Klartext.
 *  2. Umschalten → Bestätigung nennt ALT- und NEU-Label; bestätigt →
 *     logcat `Connecting to ws://<neu…>` + Statuszeile wechselt auf
 *     „Verbinde …“/„Verbunden“ NUR am neuen Eintrag.
 *  3. Relay-Log: genau EIN `Phone connected` je Wechsel (kein Doppel-WS).
 *  4. Nach onOpen: „Letzt-Verbunden“ des aktiven Eintrags = „Gerade eben“.
 *  5. Bearbeiten des aktiven Codes → nach Speichern logcat-Adoption
 *     (`Code adoptiert (Quelle: profil:<id>)`) beim nächsten Umschalten.
 *  6. Aktives Profil löschen → Banner „Verbindung geschlossen“, Zeilen bleiben,
 *     kein Auto-Connect (erfolgtes Umschalten erzeugt ihn wieder).
 *  7. Kaltstart: Liste gezeichnet aus dem JSON; aktiver Eintrag = letzter
 *     bestätigter Wechsel (serverUrl-Prefs).
 */
@Composable
fun RelayServerListCard() {
    val context = LocalContext.current
    val profiles by ServerProfiles.profiles.collectAsState()
    val activeId by ServerProfiles.activeId.collectAsState()
    val status by BridgeStatus.state.collectAsState()

    var editTarget by remember { mutableStateOf<RelayProfile?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RelayProfile?>(null) }
    var switchTarget by remember { mutableStateOf<RelayProfile?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ms_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.ms_hint),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            profiles.forEach { profile ->
                RelayProfileRow(
                    profile = profile,
                    active = profile.id == activeId,
                    liveStatus = status,
                    onSwitchRequest = { switchTarget = profile },
                    onEditRequest = { editTarget = profile },
                    onDeleteRequest = { deleteTarget = profile },
                )
            }
            OutlinedButton(
                onClick = { addOpen = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
            ) {
                Text(stringResource(R.string.ms_add), fontWeight = FontWeight.Medium)
            }
            // Welle 12b (Sir): übersprungener Assistent — Wieder-Einstieg
            // über die Karte (Skip zurücknehmen → Wizard öffnet sofort).
            if (ServerProfiles.isOnboardingSkipped() && profiles.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = FlipsiColors.PanelElev),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            stringResource(R.string.ms_resume_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.ms_resume_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                        TextButton(onClick = { ServerProfiles.resumeOnboarding() }) {
                            Text(stringResource(R.string.ms_resume_cta), color = FlipsiColors.Ember)
                        }
                    }
                }
            }
        }
    }

    // ── Umschalten BEWUSST: Bestätigung mit Alt/Neu-Benennung, dann disconnect/
    //    connect über den Store (Singletons-Faser) ──
    switchTarget?.let { target ->
        val targetLabel = remember(target.id, profiles) {
            profiles.firstOrNull { it.id == target.id }?.label ?: target.label
        }
        AlertDialog(
            onDismissRequest = { switchTarget = null },
            title = { Text(stringResource(R.string.ms_switch_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.ms_switch_text,
                            profiles.firstOrNull { it.id == activeId }?.label ?: "—",
                            targetLabel,
                        ),
                    )
                    // W4-FIX-JAG2 (S7): Profilwechsel auf einen Host OHNE Pin-
                    // Verband → sichtbare Warn im Bestätigungs-Dialog (kein
                    // Verband-Mute, kein Zusatz-Dialogschritt — der Wechsel selbst
                    // bleibt die eine Bestätigungs-Aktion). dashboard.lan (NSC-
                    // Klartext-Ausnahme) ist ebensowenig gepinnt → Warn trifft zu.
                    // hostOf statt URI-Parsing: scheme-los gespeicherte URLs
                    // ("relay.example:8766") wären sonst false-negative Warn.
                    val targetHost = ServerProfileRules.hostOf(target.url).trim().lowercase()
                    if (targetHost.isNotBlank() && PinSource.pinsFor(targetHost) == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.ms_switch_nopin_warn),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.SignalAmber,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        switchTarget = null
                        // bewusstes Trennen des Alten + Connect aufs Neue —
                        // Code adOption + FGS + RelayClient.connect im Store.
                        ServerProfiles.connectViaProfile(context, target.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = FlipsiColors.SignalRedText),
                ) {
                    Text(stringResource(R.string.ms_switch_confirm), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { switchTarget = null }) {
                    Text(stringResource(R.string.ms_cancel))
                }
            },
        )
    }

    // ── Löschen mit Bestätigung (Code je Vault geht mit) ──
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.ms_remove_title)) },
            text = {
                Text(stringResource(R.string.ms_remove_text, target.label))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        ServerProfiles.remove(target.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = FlipsiColors.SignalRedText),
                ) {
                    Text(stringResource(R.string.ms_remove_confirm), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.ms_cancel))
                }
            },
        )
    }

    // ── Hinzufügen / Bearbeiten (ein Dialog, zwei Modi — gleiche Feld-Regeln) ──
    if (addOpen) {
        RelayProfileEditDialog(
            title = stringResource(R.string.ms_add_title),
            initial = null,
            onDismiss = { addOpen = false },
            onSave = { label, url, code ->
                addOpen = false
                ServerProfiles.add(label, url, code)
            },
        )
    }
    editTarget?.let { target ->
        RelayProfileEditDialog(
            title = stringResource(R.string.ms_edit_title),
            initial = target,
            onDismiss = { editTarget = null },
            onSave = { label, url, code ->
                editTarget = null
                // Code-Feld leer gelassen = UNVERÄNDERT (kein Vault-Löschen).
                ServerProfiles.update(target.id, label, url, code.takeIf { it.isNotBlank() })
            },
        )
    }
}

@Composable
private fun RelayProfileRow(
    profile: RelayProfile,
    active: Boolean,
    liveStatus: BridgeStatus.ConnectionState,
    onSwitchRequest: () -> Unit,
    onEditRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) FlipsiColors.PanelElev else FlipsiColors.Panel,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dot, chip) = when {
                    active && liveStatus is BridgeStatus.ConnectionState.CONNECTED ->
                        FlipsiColors.SignalGreen to stringResource(R.string.ms_active_chip)
                    active -> FlipsiColors.SignalAmber to stringResource(R.string.ms_active_chip)
                    else -> FlipsiColors.TextSecondary to stringResource(R.string.ms_inactive_chip)
                }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Text(
                    profile.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp, end = 8.dp),
                )
                Text(
                    chip,
                    style = MaterialTheme.typography.labelSmall,
                    color = FlipsiColors.OnEmber,
                    modifier = Modifier
                        .background(
                            if (active) FlipsiColors.Ember else FlipsiColors.PanelElev,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                Spacer(Modifier.weight(1f))
            }
            Text(
                profile.url,
                style = FlipsiType.Code,
                modifier = Modifier.padding(top = 8.dp),
            )
            LetztVerbundenRow(profile.lastConnectedMs)
            // Code je Profil NUR reduziert sichtbar (Reduced-Pass-Faser) —
            // der Bearbeiten-Dialog liest ihn aus dem Vault, hier bleibt
            // die Zeile bewusst klartext-frei.
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!active) {
                    OutlinedButton(
                        onClick = onSwitchRequest,
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
                    ) {
                        Text(stringResource(R.string.ms_switch), fontWeight = FontWeight.Medium)
                    }
                }
                OutlinedButton(
                    onClick = onEditRequest,
                    modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
                ) {
                    Text(stringResource(R.string.ms_edit))
                }
                OutlinedButton(
                    onClick = onDeleteRequest,
                    modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.SignalRedText),
                ) {
                    Text(stringResource(R.string.ms_remove))
                }
            }
        }
    }
}

/** „Letzt-Verbunden“-Zeile je Profil (Bucket-Regel im reinen Store). */
@Composable
private fun LetztVerbundenRow(lastConnectedMs: Long) {
    val bucket = remember(lastConnectedMs) {
        ServerProfileRules.lastConnectedBucket(lastConnectedMs, System.currentTimeMillis())
    }
    val label = when (bucket) {
        ServerProfileRules.LastConnectedBucket.NEVER ->
            stringResource(R.string.ms_last_never)
        ServerProfileRules.LastConnectedBucket.NOW ->
            stringResource(R.string.ms_last_now)
        ServerProfileRules.LastConnectedBucket.MINUTES ->
            // stringResource-Platzhalter %1$d verlangt Int (Long würde werfen).
            stringResource(
                R.string.ms_last_minutes,
                ((System.currentTimeMillis() - lastConnectedMs) / 60_000L).toInt(),
            )
        ServerProfileRules.LastConnectedBucket.HOURS ->
            stringResource(
                R.string.ms_last_hours,
                ((System.currentTimeMillis() - lastConnectedMs) / 3_600_000L).toInt(),
            )
        ServerProfileRules.LastConnectedBucket.HISTORY ->
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(lastConnectedMs))
    }
    Text(
        stringResource(R.string.ms_last_connected) + ": " + label,
        style = MaterialTheme.typography.labelSmall,
        color = FlipsiColors.TextSecondary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * Ein Dialog für +Server und Bearbeiten: Name (freiwillig), URL, Code
 * (verborgen mit Einblenden). Code-Regeln wiederverwenden die Wizard-Regeln
 * (OnboardingRules) — keine Zweit-Taxonomie.
 */
@Composable
private fun RelayProfileEditDialog(
    title: String,
    initial: RelayProfile?,
    onDismiss: () -> Unit,
    onSave: (label: String, url: String, code: String) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var urlText by remember(initial?.id) { mutableStateOf(initial?.url.orEmpty()) }
    // Code: beim Bearbeiten aus dem JE-Profil-Vault (nie aus Logs), beim
    // +Server leer starten. Leer-beim-Speichern = unverändert (Bearbeiten).
    var codeText by remember(initial?.id) {
        mutableStateOf(initial?.let { ServerProfiles.codeOf(it.id) }.orEmpty())
    }
    var urlError by remember { mutableStateOf(false) }
    var codeBlankNote by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.ms_label_label)) },
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        urlError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = urlError,
                    label = { Text(stringResource(R.string.ms_url_label)) },
                    placeholder = { Text(stringResource(R.string.onb_url_hint)) },
                )
                if (urlError) {
                    Text(
                        stringResource(R.string.ms_url_blank_error),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.SignalRedText,
                    )
                }
                PassCodeField(
                    value = codeText,
                    onValueChange = {
                        codeText = it
                        codeBlankNote = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.ms_code_label),
                )
                if (codeBlankNote) {
                    Text(
                        stringResource(R.string.ms_code_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.TextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val urlOk = !OnboardingRules.relayUrlProblem(urlText, BuildConfig.DEBUG)
                    if (!urlOk) {
                        urlError = true
                        return@TextButton
                    }
                    if (codeText.isBlank() && initial == null) {
                        // +Server ohne Code: bewusst erlaubt (Server ohne Token
                        // koppelt später), aber sichtbar bemerkt — kein Speichern.
                        codeBlankNote = true
                        return@TextButton
                    }
                    onSave(
                        label.trim(),
                        urlText.trim().trimEnd('/'),
                        codeText.trim(),
                    )
                },
            ) {
                Text(stringResource(R.string.ms_save), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ms_cancel))
            }
        },
    )
}
