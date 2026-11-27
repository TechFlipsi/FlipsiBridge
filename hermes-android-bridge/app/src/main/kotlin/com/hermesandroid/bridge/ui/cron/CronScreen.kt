package com.hermesandroid.bridge.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.AgentInfoRules
import com.hermesandroid.bridge.client.CronAddRules
import com.hermesandroid.bridge.client.CronPauseResumeRules
import com.hermesandroid.bridge.client.CronRemoveRules
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.SectionTitle
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import com.hermesandroid.bridge.ui.rules.ErrorWordsRules
import com.hermesandroid.bridge.ui.rules.LocalWindowSizeClass
import com.hermesandroid.bridge.ui.rules.WindowFoldRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive


/**
 * W26d (F1): Klartext-Wort je Fehl-Familie ([ErrorWordsRules.plainWordRes])
 * oder — je UNBEKANNTEM/leerem Code — der rohe Code selbst (ehrlich, nie ein
 * erfundenes Klartext-Wort; leer = "?" wie im Bestand).
 */
private fun errorWordOrDefault(context: android.content.Context, error: String?): String =
    ErrorWordsRules.plainWordRes(error)?.let { context.getString(it) } ?: (error ?: "?")

/**
 * W23-11-App (Arbeits-Brief) — der 5. Tab
 * „Geplante Aufgaben“ (Uhr-Symbol): PRO AGENT gruppierte Gateway-Cron-Jobs
 * aus dem cron.status(-enc)-Verband. Die Gruppen-Namen kommen NIE hartkodiert
 * — Presence-lookup (relay.state → [RelayClient.agents]), dann die agent_id
 * (Frame-Daten), dann die generische Fallback-Ressource [
 * CronScreenRules]. Die Zeilen rechnen DIESELBE Sortier- und Zeit-Eimer-Faser
 * wie die Dashboard-Karte ([DashboardRules.cronSortedRows]) — kein zweites
 * Zeit-Mechanik-Doppel; die Status-/Termin-Worte teilen die dash_cron_*-
 * Ressourcen (KEIN Doppel-Keys-Verstoß).
 *
 * Zeilen-Verband je Agent (Brief): Name/Ziel + nächste Laufzeit (+ in
 * TEIL 2 das Lösch-Element). Leerzustand: EIN ehrlicher Hinweis (Parität
 * dash_no_agents-Muster).
 *
 * Live-Ticker: 30-s-Raster reicht (die Termin-Eimer in
 * [DashboardRules.cronJobView] ticken in Minuten-Grenzen; die Schleife endet
 * mit der Komposition — Dashboard-Muster).
 *
 * W24 Lane C (T5): Content-Cap je expanded — die Kappe liest die Lane-A-SSOT
 * ([WindowFoldRules.cronContentCapDp], 640 dp in der Brief-Bandbreite und
 * [LocalWindowSizeClass]); compact/medium (und fold = null fail-soft) bleibt
 * der oben beschriebene Bestand.
 *
 * W26 (Arbeits-Brief): Cron-VERWALTUNG KOMPLETT —
 *  1. Pause/Resume-Toggle je Zeile (je cron_id, Deckel je pending OHNE Retry —
 *     Parität remove; Snacks je remove-Muster) — NUR sichtbar, wenn der Agent
 *     die Caps cron.pause + cron.resume je agent.info meldet (VERSIONS-
 *     fail-soft: Alter Adapter = ehrlich ohne den Toggle, nie ein Fake).
 *  2. Anlegen-Dialog (FAB „+“): Name, Zeitplan (Wiederholung = Cron-Expr ODER
 *     einmalig = Datum/Uhrzeit → NAIVE ISO ohne Offset; die Zone, die den
 *     Zeitpunkt wahr macht, ist die GATEWAY-Konfiguration — CronAddRules-
 *     KDoc, die App erfindet KEINE Device-Zone), optional Prompt +
 *     Zustellen-Schalter → cron.add → add_ok-Snack + die Liste frisht je
 *     Fresh-Feed. Deckel je pending ohne Retry (Kein-Loop-Verband).
 *     W26c (Jäger-Blocker 3): JE Steuer-Frame trägt die Agent-Adresse —
 *     der Toggle je Gruppen-Adresse, das Löschen je DeleteTarget-Adresse,
 *     die Anlage je GEWÄHLTEM Ziel-Agent (FilterChips der Cap-Träger; ein
 *     einziger Träger braucht keine Auswahl-Faser, die Adresse trägt
 *     trotzdem); der Relay routet je GENANNTEM Agenten (fail-closed NACK
 *     bei unverortbarer Adresse — die erfundene frischeste-Session-Welt ist
 *     weg).
 *  3. Agent-Kontext je Dashboard-Sprung: [agentFocusId] öffnet die Gruppe
 *     DIESES Agents UND preselectiert ihn im Anlegen-Dialog (die
 *     cron-Adresse der Zeilen kam aus DER Gruppe — da ist der Agent
 *     bekannt, NICHT erfunden).
 */
@Composable
fun CronScreen(
    padding: PaddingValues,
    // W23-11-App TEIL 2: Feedback über DIE eine W19-Snackbar (Parität
    // SettingsHome.onFeedback) — die Löschen-Ausgänge melden sich dort.
    onSnack: (String) -> Unit = {},
    // W26: Agent-Kontext je Dashboard-Sprung (Agenten-Zentrale) — der
    // gesprungene Agent öffnet seine Gruppe; die Wahl konsumiert die UI
    // EINMAL ([onAgentFocusConsumed]), kein Hänge-Zustand je Route-Wechsel.
    agentFocusId: String? = null,
    onAgentFocusConsumed: () -> Unit = {},
) {
    // ── Live-Daten (vorhandene RelayClient-Flows, kein neuer Endpunkt) ──────
    val jobsByAgent by RelayClient.cronJobsByAgent.collectAsState()
    val agents by RelayClient.agents.collectAsState()
    // W23-11-App (TEIL 2): cron.remove-Verband — ausstehende Lösch-Läufe
    // (der Zeilen-Knopf liest sie als aus), letzte Adapter-Antwort (Snack).
    val removePending by RelayClient.cronRemovePending.collectAsState()
    val removeAck by RelayClient.cronRemoveAck.collectAsState()
    // W26: Toggle-/Anlegen-Verbände + agent.info-Caps je Agent (VERSIONS-
    // fail-soft — null = Alter Adapter, die UI bleibt ehrlich ohne Elemente).
    // W26c: die Toggle-Merker tragen die RICHTUNG (Ziel-Status-Sweep).
    val togglePending by RelayClient.cronTogglePending.collectAsState()
    val toggleAck by RelayClient.cronToggleAck.collectAsState()
    val addPending by RelayClient.cronAddPending.collectAsState()
    val addAck by RelayClient.cronAddAck.collectAsState()
    val agentCaps by RelayClient.agentCaps.collectAsState()
    val context = LocalContext.current

    // ── Live-Uhr der Termin-Eimer (30-s-Raster, Parität DashboardScreen) ────
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    // ── W26-Zustände (VOR den Staffeln — Kotlin verlangt Deklaration vor
    // Referenz): Anlegen-Dialog + der OFFEN-Zustand überlebt die Rotation
    // je saveable (Bestand-Muster des Lösch-Sprungs). ────────────────────────
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    // ── W23-11-App (TEIL 2) Ack-Staffel — JEDE Antwort snackt GENAU EINMAL
    // (ok = Bestätigung; not-supported/Fehl = ehrlicher Snack fail-soft, KEIN
    // Crash, KEIN Loop, keine Wiederholung). ─────────────────────────────────
    var ackSeen by remember { mutableStateOf(RelayClient.cronRemoveAck.value) }
    LaunchedEffect(removeAck) {
        val ack = removeAck ?: return@LaunchedEffect
        if (ack === ackSeen) return@LaunchedEffect
        ackSeen = ack
        when (CronRemoveRules.outcome(ack)) {
            CronRemoveRules.Outcome.SUCCESS ->
                onSnack(context.getString(R.string.cron_removed_snack))
            CronRemoveRules.Outcome.NOT_SUPPORTED ->
                onSnack(context.getString(R.string.cron_remove_not_supported_snack))
            CronRemoveRules.Outcome.ERROR ->
                // W26d (F1): Klartext je Fehl-Familie statt Maschinen-Code.
                onSnack(
                    context.getString(
                        R.string.cron_remove_error_snack,
                        errorWordOrDefault(context, ack.error),
                    ),
                )
        }
    }

    // ── W26: Toggle-Ack-Staffel (Parität remove-Staffel) — Pause/Resume
    // snackt je Ausgang GENAU EINMAL: ok = Bestätigung (Wort je Richtung),
    // not-supported/Fehl = ehrlicher Snack, KEIN Crash, KEIN Loop. ────────────
    var toggleAckSeen by remember { mutableStateOf(RelayClient.cronToggleAck.value) }
    LaunchedEffect(toggleAck) {
        val ack = toggleAck ?: return@LaunchedEffect
        if (ack === toggleAckSeen) return@LaunchedEffect
        toggleAckSeen = ack
        when (CronPauseResumeRules.outcome(ack)) {
            CronPauseResumeRules.Outcome.SUCCESS ->
                onSnack(
                    context.getString(
                        if (ack.op == CronPauseResumeRules.Op.PAUSE) R.string.cron_paused_snack
                        else R.string.cron_resumed_snack,
                    ),
                )
            CronPauseResumeRules.Outcome.NOT_SUPPORTED ->
                onSnack(context.getString(R.string.cron_toggle_not_supported_snack))
            CronPauseResumeRules.Outcome.ERROR ->
                // W26d (F1): Klartext je Fehl-Familie statt Maschinen-Code.
                onSnack(
                    context.getString(
                        R.string.cron_toggle_error_snack,
                        errorWordOrDefault(context, ack.error),
                    ),
                )
        }
    }

    // ── W26: Anlegen-Ack-Staffel — Erfolg = Dialog zu + Snack; Fehl/not-
    // supported = Dialog bleibt offen, ehrlicher Snack statt Blind-Neustart
    // (KEIN Loop). ────────────────────────────────────────────────────────────
    var addAckSeen by remember { mutableStateOf(RelayClient.cronAddAck.value) }
    LaunchedEffect(addAck) {
        val ack = addAck ?: return@LaunchedEffect
        if (ack === addAckSeen) return@LaunchedEffect
        addAckSeen = ack
        when (CronAddRules.outcome(ack)) {
            CronAddRules.Outcome.SUCCESS -> {
                showAddDialog = false
                onSnack(context.getString(R.string.cron_add_created_snack))
            }
            CronAddRules.Outcome.NOT_SUPPORTED ->
                onSnack(context.getString(R.string.cron_add_not_supported_snack))
            CronAddRules.Outcome.ERROR ->
                // W26d (F1): Klartext je Fehl-Familie statt Maschinen-Code.
                onSnack(
                    context.getString(
                        R.string.cron_add_error_snack,
                        errorWordOrDefault(context, ack.error),
                    ),
                )
        }
    }

    // ── W23-11-App (TEIL 2) + W26: Warten-Deckel je Lauf — keine Antwort
    // (Ack ODER frischer cron.status) bis dahin = das Element wird wieder frei
    // (ehrlich, KEIN Fake-Fehlertext, KEIN Wiederhol-Senden — Kein-Loop). ─────
    LaunchedEffect(removePending) {
        val outstanding = removePending
        if (outstanding.isEmpty()) return@LaunchedEffect
        delay(CronRemoveRules.ACK_TIMEOUT_MS)
        // Nach dem Deckel: unverändert keine Antwort? → Warten räumen, EIN
        // Lauf. (Der spätere frische cron.status zieht die Zeile doch noch.)
        // W26d (R3): Deckel-Parität je Verband — der LÖSCH-Lauf snackt wie
        // Pause/Add (im selben Screen sagte der Toggle-Deckel ehrlich
        // „Keine Antwort vom Gateway …", der Lösch-Lauf verlief still):
        // die Zeile bleibt, aber JETZT erklären alle drei Deckel denselben
        // ehrlichen Stand.
        if (RelayClient.cronRemovePending.value == outstanding) {
            RelayClient.clearCronRemoveWait()
            onSnack(context.getString(R.string.cron_no_answer_snack))
        }
    }
    LaunchedEffect(togglePending) {
        val outstanding = togglePending
        if (outstanding.isEmpty()) return@LaunchedEffect
        delay(CronPauseResumeRules.ACK_TIMEOUT_MS)
        if (RelayClient.cronTogglePending.value == outstanding) {
            RelayClient.clearCronToggleWait()
            onSnack(context.getString(R.string.cron_no_answer_snack))
        }
    }
    LaunchedEffect(addPending) {
        if (!addPending) return@LaunchedEffect
        delay(CronAddRules.ACK_TIMEOUT_MS)
        if (RelayClient.cronAddPending.value) {
            RelayClient.clearCronAddWait()
            onSnack(context.getString(R.string.cron_no_answer_snack))
        }
    }

    // W23-11-App (TEIL 2): Lösch-Wünsche der Zeilen — die Bestätigung folgt
    // im Dialog (Bestätigungs-Verband des Briefs), NIE Blind-Löschen.
    // W24 (Lane D): saveable — der offene Lösch-Spruch überlebt die Rotation
    // (Verbriefungs-Name/Id bleiben; die Abbruch-/Bestätigungs-Verbande
    // bleiben unverändert) statt den Dialog still zu schließen. W26c: der
    // Agent-Adresse-Kontext wandert mit (je GENANNTEM Agenten geroutet).
    var deleteTarget by rememberSaveable(stateSaver = DeleteTargetSaver) {
        mutableStateOf<DeleteTarget?>(null)
    }

    // W26: Agent-Kontext je Dashboard-Sprung — DIE Gruppe des gesprungenen
    // Agents öffnet (je agentKey eigener Stand), die Wahl konsumiert EINMAL
    // (Kein-Hängen je Route-Wechsel; ohne Sprung bleibt der Bestand exakt).
    // W26d (F5): der Fokus bleibt in der SICHT je Route — die (leere)
    // Gruppe des gewählten Agenten überlebt den KONSUM der Wahl (der
    // Kopf + Leerzustand stehen solange, bis der erste cron.status die
    // Gruppe füllt); die Wahl konsumiert (BridgeScreen räumt pending),
    // die Sicht der Adresse konsumiert die UI NICHT mehr mit.
    var focusSeen by rememberSaveable { mutableStateOf<String?>(null) }
    if (!agentFocusId.isNullOrBlank()) focusSeen = agentFocusId
    val effectiveFocus: String? = if (!agentFocusId.isNullOrBlank()) agentFocusId else focusSeen

    val groups = CronScreenRules.groups(
        jobsByAgent = jobsByAgent,
        agents = agents.map {
            CronScreenRules.AgentRef(it.agentId, it.displayName, it.online)
        },
        nowMs = nowMs,
        // W26d (F5): die pendente Sprung-Adresse ist NIE tot — die Faser
        // erzeugt je Fokus (auch je 0-Jobs-Agent) genau eine (leere)-Gruppe
        // (Kopf + Leerzustand der UI statt toter Adresse).
        pendingFocusAgentId = effectiveFocus,
    )

    // ── PRO-AGENT-Dropdowns: je Agent eigener Klappe-Zustand; EINMAL die
    // erste Gruppe auf (der Haupt-Agent ist der übliche Blick), sonst zu —
    // jeder Stand eigener Merker, bewusst KEIN gemeinsames Zwiebeltal.
    // W24 (Lane D): die Klappe-Map JE Saveable-Verband — die Auf-/Zu-Wahl
    // überlebt die Rotation (je agentKey eigener Stand; das "Erste-gruppe-
    // auf"-Default gilt je Stand ohne Eintrag, unverändert). Result-Overload
    // mit `saver` (die map ist KEIN MutableState — der `stateSaver`-Overload
    // verlangt einen State-Wickel; K2-Compile 1.7.3-Quellstand bewies den
    // Signatur-Unterschied am Stub-Compile-Fund).
    val expanded = rememberSaveable(
        saver = listSaver<SnapshotStateMap<String, Boolean>, Any>(
            save = { map -> map.entries.flatMap { listOf<Any>(it.key, it.value) } },
            restore = { flat ->
                val restored = mutableStateMapOf<String, Boolean>()
                flat.chunked(2).forEach { pair ->
                    (pair.getOrNull(0) as? String)?.let { key ->
                        restored[key] = pair.getOrNull(1) == true
                    }
                }
                restored
            },
        ),
    ) { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(agentFocusId, agents, jobsByAgent) {
        val focus = agentFocusId ?: return@LaunchedEffect
        if (focus.isBlank()) {
            onAgentFocusConsumed()
            return@LaunchedEffect
        }
        // Der Sprung wartet auf die Gruppe (der erste cron.status-Frame des
        // Agents baut sie — frisches App-Fenster hat sie noch nicht): das
        // Öffnen setzt je agentKey ein, sobald die Gruppe existiert.
        // W26d (F5): die pending-Faser baut die Fokus-Gruppe AUCH je
        // 0-Jobs-Agent (die Adresse ist nie tot) — der known-Check greift
        // je jedem Sprung, der Konsum JEDERSEITS (kein ewiges pending auf
        // Agenten ohne Jobs mehr, Report F5).
        val known = groups.any { it.agentKey == focus } || jobsByAgent.containsKey(focus)
        if (known) {
            expanded[focus] = true
            onAgentFocusConsumed()
        }
    }

    // ── W24 Lane C (T5): Content-Cap je expanded ────────────────────────────
    // Die Kappe liest die LANE-A-SSOT ([WindowFoldRules.cronContentCapDp] —
    // 640 dp, Mitte der Brief-Bandbreite 600–720): KEINE eigene Falt-Kaskade
    // und KEINE LocalConfiguration-Ableitung (T1/T7-Befund — die
    // WindowSizeClass stammt von MainActivity via [LocalWindowSizeClass]).
    // null (fold fehlt/Preview oder compact/medium) = offene Breite = der
    // W23-Bestand exakt; der einspaltige Charakter bleibt KEIN Spalten-Raster.
    val fold = LocalWindowSizeClass.current?.let {
        WindowFoldRules.compose(it.widthSizeClass, it.heightSizeClass)
    }
    val contentCapDp = fold?.let { WindowFoldRules.cronContentCapDp(it) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            // W28 (Lane F, Reviewer-B-Auflage P2): Boden NUR je Zustand OHNE
            // Leistenslot — dort hebt das Scaffold den Boden NICHT
            // (contentWindowInsets = 0) und die letzte Cron-Karte endete hart
            // am Fensterboden IN der Gesture-Säule (K4-Verstoß; play-36
            // Rail-Boden = 48 dp + navigationBars via SSOT-Helfer). Sitzt die
            // Leiste (kompaktes Portrait), trägt SIE den Boden — github-34 =
            // 0-dp-Delta bleibt.
            .then(
                if (WindowFoldRules.bottomBarHost(fold)) {
                    Modifier
                } else {
                    Modifier.padding(
                        bottom = WindowFoldRules.bottomBarFloorPadding(fold),
                    )
                }
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .then(
                    if (contentCapDp != null) Modifier.widthIn(max = contentCapDp.dp)
                    else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.dash_cron_title))

            if (groups.isEmpty()) {
                EmptyCronCard()
            } else {
                groups.forEachIndexed { index, group ->
                    // W26: Cap-Gate je Gruppe aus agent.info (VERSIONS-fail-soft —
                    // null = agent.info fehlt ⇒ ehrlich OHNE Toggle, nie ein Fake).
                    val groupCaps = agentCaps[group.agentKey]?.caps
                    AgentCronGroupCard(
                        group = group,
                        expanded = expanded[group.agentKey] ?: (index == 0),
                        onExpandedChange = { open -> expanded[group.agentKey] = open },
                        removePending = removePending,
                        togglePending = togglePending,
                        toggleAllowed = AgentInfoRules.hasCap(groupCaps, AgentInfoRules.CAP_CRON_PAUSE) &&
                            AgentInfoRules.hasCap(groupCaps, AgentInfoRules.CAP_CRON_RESUME),
                        onDeleteRequest = { row ->
                            // W26c: die Gruppen-Adresse = die Agent-Adresse des
                            // Laufs (je GENANNTEM Agenten geroutet — Alt-Stand
                            // ohne agent_id schickt bewusst "" → Relay-Fallback).
                            deleteTarget = DeleteTarget(
                                row.item.cronId, row.item.name, group.agentKey)
                        },
                        onToggleRequest = { row, op ->
                            val cid = row.item.cronId ?: return@AgentCronGroupCard
                            // EIN Lauf je Moment: der Klick sendet DIREKT —
                            // Pause/Resume sind NICHT destruktiv (kein
                            // Bestätigungs-Dialog, Parität Deckel-Verband).
                            // W26c: die Gruppen-Adresse wandert an den Frame.
                            val sent = when (op) {
                                CronPauseResumeRules.Op.PAUSE ->
                                    RelayClient.sendCronTogglePause(cid, group.agentKey)
                                CronPauseResumeRules.Op.RESUME ->
                                    RelayClient.sendCronToggleResume(cid, group.agentKey)
                            }
                            if (!sent) {
                                onSnack(context.getString(R.string.cron_toggle_send_failed_snack))
                            }
                        },
                    )
                }
            }
        }

        // ── W26: FAB „+" (Anlegen) — NUR sichtbar, wenn IRGENDEIN Agent die
        // cron.add-Cap je agent.info meldet (VERSIONS-fail-soft; ein Alter
        // Adapter bleibt ehrlich ohne das Angebot, KEIN Fake-Verwaltungs-Angebot).
        val addCapable = agents.any { agent ->
            AgentInfoRules.hasCap(agentCaps[agent.agentId]?.caps, AgentInfoRules.CAP_CRON_ADD)
        }
        if (addCapable) {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = FlipsiColors.Ember,
                contentColor = FlipsiColors.OnEmber,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cron_add_a11y),
                    tint = FlipsiColors.OnEmber,
                )
            }
        }
    }

    // W26: Anlegen-Dialog (Namens-/Zeitplan-/Pflicht-Prompt-Felder je
    // Frame-Vertrag → cron.add; je Fresh-Feed frisht die Liste selbst, KEIN
    // Retry). Der Dialog bleibt im Warten-Deckel offen (dismiss gesperrt je
    // pending — kein Halbfertig-Lauch), Abbruch = nichts gesendet. W26c: der
    // Ziel-Agent wird im Dialog GEWÄHLT (Cap-Träger je agent.info) und als
    // agent_id-Adresse am Frame getragen (je GENANNTEM Agenten geroutet).
    if (showAddDialog) {
        val addCapableAgents = agents
            .filter { agent ->
                AgentInfoRules.hasCap(agentCaps[agent.agentId]?.caps, AgentInfoRules.CAP_CRON_ADD)
            }
            .map { agent ->
                CronScreenRules.AgentRef(agent.agentId, agent.displayName, agent.online)
            }
        AddCronDialog(
            addPending = addPending,
            capableAgents = addCapableAgents,
            preselectedAgentId = agentFocusId
                ?.takeIf { focus -> addCapableAgents.any { it.agentId == focus } },
            onDismiss = { if (!addPending) showAddDialog = false },
            onSend = { spec, agentId ->
                if (!RelayClient.sendCronAdd(spec, agentId)) {
                    // Fail-soft je Deckel (kein WS-Kanal / Caps verletzt): der
                    // Dialog bleibt offen, der Snack sagt ehrlich warum.
                    onSnack(context.getString(R.string.cron_add_send_failed_snack))
                }
            },
        )
    }

    // ── W23-11-App (TEIL 2): Bestätigungs-Dialog vor DEM Frame senden
    // (destructive Aktion nennt Folgen; Bestand-Muster Regenerate-/Master-
    // Dialog). Abbruch = nichts gesendet, KEIN Frame, KEIN Loop. ─────────────
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.cron_delete_dialog_title)) },
            text = {
                Text(stringResource(R.string.cron_delete_dialog_text, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        // Bestätigt → EIN cron.remove-Frame (Verbriefung ist
                        // Pflicht — sendCronRemove verweigert ohne cron_id,
                        // fail-soft: ehrlicher Snack statt stiller Antwort).
                        // Jäger-Fix2: cronId ist String? — null = sendCronRemove
                        // verweigert EHR (false → Snack), kein Crash im Dialog.
                        // W26c: die Agent-Adresse wandert an den Frame (je
                        // GENANNTEM Agenten geroutet).
                        val sent = target.cronId?.let { RelayClient.sendCronRemove(it, target.agentId) } ?: false
                        if (!sent) {
                            onSnack(context.getString(R.string.cron_remove_send_failed_snack))
                        }
                    },
                ) { Text(stringResource(R.string.cron_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = FlipsiColors.PanelElev,
        )
    }
}

/**
 * EIN Lösch-Wunsch: [cronId] = Verbriefung aus der cron.status-Zeile
 * (PRIMARY-Zielkennung des cron.remove-Frames, [CronRemoveRules]),
 * [name] der Dialog-Text (Folgen-Benennung), [agentId] = die Gruppen-
 * Adresse des Laufs (W26c — der Frame trägt sie, je GENANNTEM Agenten
 * geroutet). cronId-null existiert HIER nicht (die UI verbirgt den Lösch-
 * Knopf ohne Verbriefung) — der Guard bleibt doppelt (rules +
 * sendCronRemove), kein Blind-Frame möglich.
 */
private data class DeleteTarget(val cronId: String?, val name: String,
                                val agentId: String)

/**
 * W24 (Lane D): Saver des Lösch-Dialog-Sprungs — flach [t-Marker, cronId,
 * name, agentId]; cronId null/"" → ""-Slot und zurück (die UI setzt den
 * Knoten NUR bei Verbriefung, ein ""-Ziel existiert nie am Weg — der Guard
 * bleibt doppelt). W26c: die Agent-Adresse als 4. Slot (""-Eimer = Alt-Stand
 * ohne agent_id — der Relay-Fallback mit LOUD-Log bleibt legitim).
 */
private val DeleteTargetSaver = listSaver<DeleteTarget?, Any>(
    save = { target ->
        target?.let {
            listOf<Any>("t", it.cronId ?: "", it.name, it.agentId)
        } ?: emptyList()
    },
    restore = { flat ->
        if (flat.size < 3 || flat[0] != "t") null
        else DeleteTarget(
            cronId = (flat[1] as? String)?.takeIf(String::isNotBlank),
            name = (flat[2] as? String).orEmpty(),
            agentId = (flat.getOrNull(3) as? String).orEmpty(),
        )
    },
)

/** Live-Uhr: 30-s-Raster wie der Bestand. */
@Composable
private fun nowMs(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return now
}

/** Leerzustand: die Sektion bleibt ehrlich erklärend (dash_no_agents-Muster). */
@Composable
private fun EmptyCronCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.cron_screen_empty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.cron_screen_empty_sub),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * EINE PRO-AGENT-Gruppe (Dropdown): Kopf (Presence-Dot + Name + Zähler +
 * Pfeil) ist das ganze Touch-Feld (AccordionCard-Muster — Role.Button,
 * TalkBack meldet den Klappe-Zustand), der Inhalt nur im aufgeklappten Stand.
 */
@Composable
private fun AgentCronGroupCard(
    group: CronScreenRules.AgentCronGroup,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    // W23-11-App (TEIL 2): ausstehende Lösch-Läufe (cron_id-Merkel je Lauf)
    // + der Dialog-Wunsch der Zeilen.
    removePending: Set<String>,
    // W26c: ausstehende Toggle-Läufe JE RICHTUNG (cron_id → Op — der
    // Ziel-Status-Sweep braucht die Richtung, Parität remove-Warten) + das
    // Cap-Gate der Gruppe (je agent.info).
    togglePending: Map<String, CronPauseResumeRules.Op>,
    toggleAllowed: Boolean,
    onDeleteRequest: (DashboardRules.CronJobView) -> Unit,
    onToggleRequest: (DashboardRules.CronJobView, CronPauseResumeRules.Op) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = onExpandedChange,
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Presence-Dot am Gruppennamen (Parität W23-8 Status-Dot):
                // Grün = online, grau = offline/unbekannt — SignalRed NIE
                // (W23-8-Regel: Redness ist NICHT der Offline-Träger).
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (group.known && group.online) FlipsiColors.SignalGreen
                            else FlipsiColors.TextSecondary,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    groupDisplayName(group),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    group.rows.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlipsiColors.TextSecondary,
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = FlipsiColors.TextSecondary,
                )
            }
            if (expanded) {
                CronRowGroup(
                    rows = group.rows,
                    removePending = removePending,
                    togglePending = togglePending,
                    toggleAllowed = toggleAllowed,
                    onDeleteRequest = onDeleteRequest,
                    onToggleRequest = onToggleRequest,
                )
            }
        }
    }
}

/**
 * Anzeige-Name der Gruppe — GENERIK-Verband (Brief: „Agenten-Namen NIE
 * hartkodieren“): Presence-Treffer = displayName, sonst die agent_id
 * (Frame-Daten), beides fehl = generische Fallback-Ressource.
 */
@Composable
private fun groupDisplayName(group: CronScreenRules.AgentCronGroup): String =
    if (group.displayName.isNotBlank()) group.displayName
    else stringResource(R.string.cron_group_unknown)

/** Die Aufgabe-Zeilen einer Gruppe (Trenner-Verband wie CronJobsCard). */
@Composable
private fun CronRowGroup(
    rows: List<DashboardRules.CronJobView>,
    removePending: Set<String>,
    togglePending: Map<String, CronPauseResumeRules.Op>,
    toggleAllowed: Boolean,
    onDeleteRequest: (DashboardRules.CronJobView) -> Unit,
    onToggleRequest: (DashboardRules.CronJobView, CronPauseResumeRules.Op) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (rows.isEmpty()) {
            // W26d (F5): der ehrliche Leerzustand je (leerer) Gruppe — eine
            // (leere)-Fokus-Gruppe des Sprungs (Agent MIT Cap, NULL Aufgaben)
            // spricht den Nutzer an: der Kopf steht, diese Zeile sagt, dass
            // es keine geplanten Aufgaben gibt — die Adresse ist nie tot.
            Text(
                stringResource(R.string.cron_group_empty),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
        } else {
            rows.forEachIndexed { index, row ->
            if (index > 0) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(FlipsiColors.LineStrong),
                )
            }
            // Smart-cast-Faden gegen den Null-Verband (cronId null = nie aus):
            val cid = row.item.cronId
            CronJobRow(
                row = row,
                deleting = cid != null && cid in removePending,
                toggling = cid != null && cid in togglePending,
                toggleAllowed = toggleAllowed && cid != null,
                onToggleRequest = onToggleRequest,
                onDeleteRequest = onDeleteRequest,
            )
            }
        }
    }
}

/**
 * EINE Cron-Zeile: Status-Dot (Zustandsfarbe), Name/Ziel, rechts Wort +
 * nächste Laufzeit + (TEIL 2) das Lösch-Element — PARITÄT zur Dashboard-
 * CronJobRow (gleiche Farben, Typografie, Ressourcen; bewusst KEIN Umbau des
 * Dashboard-Privats — zwei Renderer, EINE Regel-Faser in DashboardRules).
 *
 * Lösch-Verband (Brief TEIL 2): Icon NUR mit Verbriefung (cron_id != null —
 * ein Alt-Stand ohne Verbriefung ist NICHT löschbar, ehrlich verborgen);
 * während eines laufenden Lösch-Laufs disabled (kein Doppel-Senden, Kein-
 * Loop); Icon-Klick = NUR der Dialog-Wunsch, der Frame sendet erst NACH der
 * Bestätigung.
 *
 * W26: Pause/Resume-Toggle NUR je gemeldeter Caps (VERSIONS-fail-soft — der
 * Deckel je pending OHNE Retry, Parität remove; Icon je Richtung: PAUSED =
 * Play (Resume), sonst Pause); während Remove- UND Toggle-Läufen disabled
 * (keine Doppel-/Konflikt-Sende-Spur, Kein-Loop). Ohne Verbriefung bleibt
 * der Toggle verborgen (das Ziel ist nicht adressierbar, ehrlich).
 */
@Composable
private fun CronJobRow(
    row: DashboardRules.CronJobView,
    deleting: Boolean,
    toggling: Boolean,
    toggleAllowed: Boolean,
    onToggleRequest: (DashboardRules.CronJobView, CronPauseResumeRules.Op) -> Unit,
    onDeleteRequest: (DashboardRules.CronJobView) -> Unit,
) {
    val state = row.item.state
    val tint = when {
        state == DashboardRules.CronJobState.RUNNING -> FlipsiColors.Ember
        state == DashboardRules.CronJobState.PLANNED && row.overdue -> FlipsiColors.SignalRedText
        state == DashboardRules.CronJobState.PLANNED -> FlipsiColors.SignalGreen
        else -> FlipsiColors.TextSecondary
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(tint, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            row.item.name,
            style = MaterialTheme.typography.bodyMedium,
            color = FlipsiColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                cronStatusWord(row),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            val term = cronNextRunText(row)
            if (term != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    term,
                    style = MaterialTheme.typography.labelSmall,
                    color = FlipsiColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // W26: das Toggle-Element — pause/resume je Richtung (PAUSED → Play),
        // NUR mit Verbriefung + gemeldeten Caps; Deckel je pending OHNE Retry.
        if (toggleAllowed && row.item.cronId != null) {
            Spacer(Modifier.width(4.dp))
            val busy = toggling || deleting
            IconButton(
                onClick = {
                    onToggleRequest(
                        row,
                        if (state == DashboardRules.CronJobState.PAUSED) {
                            CronPauseResumeRules.Op.RESUME
                        } else {
                            CronPauseResumeRules.Op.PAUSE
                        },
                    )
                },
                enabled = !busy,
                // Jäger-Fix (W23-Fix2): 48 dp Touch-Ziel (Haus-Standard,
                // Parität Lösch-Element) —destructives Steuer-Element braucht
                // mindestens den Material3-Touch-Norm-Raum.
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painterResource(
                        if (state == DashboardRules.CronJobState.PAUSED) {
                            R.drawable.ic_play
                        } else {
                            R.drawable.ic_pause
                        },
                    ),
                    contentDescription = stringResource(
                        if (state == DashboardRules.CronJobState.PAUSED) R.string.cron_resume_a11y
                        else R.string.cron_pause_a11y,
                    ),
                    tint = if (busy) FlipsiColors.TextSecondary else FlipsiColors.TextPrimary,
                    // W27 (K3, Audit S4 „Papierkorb-Icons zu klein"): das Icon
                    // lief im 18-dp-Abdimm-Format — ab jetzt der IconButton-
                    // Standard 24 dp (Parität DevicesCard/Haus-Verband); das
                    // 48-dp Touch-Ziel bleibt, kein Layout-Bruch (die Zeile
                    // misst weiter den Button-Raum, nicht das Glyph).
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        // W23-11-App (TEIL 2): das Lösch-Element — Icon NUR mit Verbriefung
        // (cron_id), während des Laufs disabled (kein Doppel-Senden, Kein-
        // Loop); Klick = NUR der Dialog-Wunsch, senden erst NACH Bestätigung.
        val cronId = row.item.cronId
        if (cronId != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { onDeleteRequest(row) },
                enabled = !deleting && !toggling,
                // Jäger-Fix (W23-Fix2): 48 dp Touch-Ziel (Haus-Standard
                // ≥48 dp, Parität DevicesCard/BridgeScreen) statt
                // vorheriger 28 dp — destructives Element braucht
                // mindestens den Material3-Touch-Norm-Raum.
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.cron_delete_a11y),
                    tint = if (deleting) FlipsiColors.TextSecondary else FlipsiColors.TextPrimary,
                    // W27 (K3, Audit S4): Papierkorb-Glyph auf IconButton-
                    // Standard 24 dp (Parität der Pause/Resume-Faser im
                    // selben Verband; 48-dp Touch bleibt, kein Layout-Bruch).
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Status-Wort je Zeile (Ressourcen ×3 — stringResource löst je Locale). */
@Composable
private fun cronStatusWord(row: DashboardRules.CronJobView): String =
    when {
        row.item.state == DashboardRules.CronJobState.RUNNING ->
            stringResource(R.string.dash_cron_running)
        row.item.state == DashboardRules.CronJobState.PLANNED && row.overdue ->
            stringResource(R.string.dash_cron_overdue)
        row.item.state == DashboardRules.CronJobState.PLANNED ->
            stringResource(R.string.dash_cron_planned)
        else -> stringResource(R.string.dash_cron_paused)
    }

/**
 * Termin-Text (relativ, Ressourcen ×3): future = „in X“, verpasst = ehrliches
 * „vor X“; ohne Termin (running/paused) = null → Zeile entfällt. Tage =
 * Plural-Ressource (dash_cron_in_days/dash_cron_ago_days — Singular-Rettung
 * „1 Tag“, Dashboard-Muster).
 */
@Composable
private fun cronNextRunText(row: DashboardRules.CronJobView): String? {
    if (row.item.state != DashboardRules.CronJobState.PLANNED) return null
    val minutes = row.minutesFromNow ?: return null
    val hours = row.hoursFromNow ?: (minutes / 60L)
    val days = hours / 24L
    return when {
        days >= 1L -> {
            val pluralRes = if (row.overdue) R.plurals.dash_cron_ago_days
            else R.plurals.dash_cron_in_days
            LocalContext.current.resources
                .getQuantityString(pluralRes, days.toInt(), days)
        }
        hours >= 1L -> {
            if (row.overdue) stringResource(R.string.dash_cron_ago_hours, hours)
            else stringResource(R.string.dash_cron_in_hours, hours)
        }
        else -> {
            if (row.overdue) stringResource(R.string.dash_cron_ago_minutes, minutes)
            else stringResource(R.string.dash_cron_in_minutes, minutes)
        }
    }
}

// ══════ W26: Anlegen-Dialog (FAB „+“ → cron.add, Frame-Vertrag EXAKT) ════════

/**
 * Der Anlegen-Dialog je Brief: Felder Name, Zeitplan (zwei Modi — EINFACHE
 * WIEDERHOLUNG = Cron-Expr ODER EINMALIG = Datum/Uhrzeit → naive ISO je
 * Gateway-Konvention, Adapter-Lane 5aa0498) und der PFLICHT-Prompt (leer =
 * Gateway-'invalid' — die Form-Faser verlangt ihn je Verband). Ein deliver-
 * Feld bleibt bewusst WEG (STRING-Pass-through ohne erfundenen Kanal-Namen).
 * Senden
 * baut den Frame STRICT je [CronAddRules] (Backend-Caps je Deckel VOR dem
 * Senden — Form-Abweisung = ehrlicher Feld-Hinweis, KEIN stiller Stau).
 * Warten-Deckel je pending (disable + Progress-Zeile) OHNE Retry (Parität
 * remove); Erfolg = Dialog zu (die Staffel snackt, der Fresh-Feed frisht).
 */
@Composable
private fun AddCronDialog(
    addPending: Boolean,
    // W26c: die Cap-Träger (cron.add je agent.info) als Ziel-Auswahl — der
    // gewählte Agent wandert als agent_id am Frame (je GENANNTEM Agenten
    // geroutet). EIN Träger = EIN ehrliches Ziel (keine Auswahl-Zeile, die
    // Adresse trägt trotzdem); der Dashboard-Sprung preselectiert.
    capableAgents: List<CronScreenRules.AgentRef>,
    preselectedAgentId: String? = null,
    onDismiss: () -> Unit,
    onSend: (CronAddRules.JobSpec, String) -> Unit,
) {
    var nameText by rememberSaveable { mutableStateOf("") }
    var repeatMode by rememberSaveable { mutableStateOf(true) } // true = Wiederholung (Cron), false = Einmalig
    var exprText by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf("") }
    var timeText by rememberSaveable { mutableStateOf("") }
    var promptText by rememberSaveable { mutableStateOf("") }
    var agentText by rememberSaveable {
        mutableStateOf(
            preselectedAgentId
                ?.takeIf { chosen -> capableAgents.any { it.agentId == chosen } }
                ?: capableAgents.firstOrNull()?.agentId.orEmpty(),
        )
    }

    val nameOk = nameText.trim().isNotEmpty() && nameText.trim().length <= CronAddRules.NAME_MAX_CHARS
    val onceParts = if (repeatMode) null else {
        val date = CronAddRules.parseOnceDatePart(dateText)
        val time = CronAddRules.parseOnceTimePart(timeText)
        if (date == null || time == null) null
        else CronAddRules.onceScheduleIso(
            year = date.first, month = date.second, dayOfMonth = date.third,
            hour = time.first, minute = time.second,
        )
    }
    val scheduleOk = if (repeatMode) {
        CronAddRules.validRepeatExpr(exprText)
    } else {
        onceParts != null
    }
    // Prompt PFLICHT je Gateway-Welt (Adapter-Lane 5aa0498 — leer wäre die
    // stille 'invalid'-Abweisung; die UI verlangt ihn je Form-Faser).
    val promptOk = promptText.trim().isNotEmpty() &&
        promptText.trim().length <= CronAddRules.PROMPT_MAX_CHARS
    // W26c: OHNE gewählten Ziel-Agent sendet die UI BEWUSST NICHT (die Anlage
    // braucht eine Agent-Adresse — kein wirksames Senden ohne Ziel).
    val agentOk = agentText.isNotBlank()
    val canSend = nameOk && scheduleOk && promptOk && agentOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cron_add_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cron_add_name_label)) },
                    supportingText = {
                        if (!nameOk) {
                            Text(stringResource(R.string.cron_add_name_error, CronAddRules.NAME_MAX_CHARS))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                // W26c: Ziel-Agent je Auswahl (FilterChips je Cap-Träger, Muster der
                // Zeitplan-Modi) — NUR bei MEHR als einem Träger (ein Ziel braucht
                // keine Auswahl-Faser; die Adresse wandert trotzdem an den Frame).
                if (capableAgents.size > 1) {
                    Text(
                        stringResource(R.string.cron_add_agent_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlipsiColors.TextSecondary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        capableAgents.forEach { agent ->
                            FilterChip(
                                selected = agentText == agent.agentId,
                                onClick = { agentText = agent.agentId },
                                label = {
                                    Text(
                                        (agent.displayName.ifBlank { agent.agentId })
                                            .take(48),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                // Zeitplan-Modi (zwei Filter-Chips, je Brief EIN Modus aktiv)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = repeatMode,
                        onClick = { repeatMode = true },
                        label = { Text(stringResource(R.string.cron_add_mode_repeat)) },
                    )
                    FilterChip(
                        selected = !repeatMode,
                        onClick = { repeatMode = false },
                        label = { Text(stringResource(R.string.cron_add_mode_once)) },
                    )
                }
                Text(
                    stringResource(R.string.cron_add_schedule_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlipsiColors.TextSecondary,
                )
                if (repeatMode) {
                    OutlinedTextField(
                        value = exprText,
                        onValueChange = { exprText = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.cron_add_cronexpr_label)) },
                        supportingText = {
                            Text(stringResource(R.string.cron_add_cronexpr_hint))
                        },
                    )
                    if (!scheduleOk && exprText.isNotBlank()) {
                        Text(
                            stringResource(R.string.cron_add_expr_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.SignalRedText,
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = dateText,
                            // W26d (F2): der Eingabe-Helfer formt den Strich
                            // selbst ein („202611“ → „2026-11“) — die Ziffern-
                            // Tastatur braucht keine „-“-Taste mehr (P1-Falle
                            // des Reports: roter Hinweis, aber kein Weg rein).
                            onValueChange = { dateText = CronAddRules.formatOnceDateInput(it) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.cron_add_date_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = timeText,
                            // W26d (F2): derselbe Verband — „900“ → „9:00“ (der
                            // Doppelpunkt setzt die Faser, der Nutzer tippt NUR
                            // Ziffern).
                            onValueChange = { timeText = CronAddRules.formatOnceTimeInput(it) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.cron_add_time_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Ehrlich: die Vorschau = exakt der NAIVE ISO-Strand, den
                    // das Backend liest — KEIN Device-Offset in der App; die
                    // Zone, die ihn wahr macht, ist die GATEWAY-Konfiguration
                    // (CronAddRules.onceScheduleIso-KDoc). Stimmt die Geräte-
                    // Wand-Uhr NICHT mit der Gateway-Zone überein, läuft der
                    // Job zu anderer Uhrzeit — die Anzeige bleibt der Draht.
                    if (onceParts != null) {
                        Text(
                            stringResource(R.string.cron_add_once_hint, onceParts),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                    } else if (dateText.isNotBlank() || timeText.isNotBlank()) {
                        Text(
                            stringResource(
                                if (CronAddRules.parseOnceDatePart(dateText) == null) {
                                    R.string.cron_add_date_error
                                } else {
                                    R.string.cron_add_time_error
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.SignalRedText,
                        )
                    }
                }
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    minLines = 2,
                    label = { Text(stringResource(R.string.cron_add_prompt_label)) },
                    supportingText = {
                        if (!promptOk) {
                            Text(stringResource(R.string.cron_add_prompt_required))
                        }
                    },
                )
                // Warten-Deckel (ehelicher Fortschritt, KEIN Retry — Parität
                // remove; die UI räumt je Deckel + Snack).
                if (addPending) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = FlipsiColors.Ember,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!canSend || addPending) return@TextButton
                    // Send-Datenträger je Form-Faser (der Backend-Caps-Deckel
                    // läuft in addFrame NOCHMAL — doppelt nie falsch am Draht).
                    val schedule = if (repeatMode) exprText else onceParts.orEmpty()
                    onSend(
                        CronAddRules.JobSpec(
                            name = nameText,
                            schedule = schedule,
                            mode = if (repeatMode) CronAddRules.ScheduleMode.REPEAT
                            else CronAddRules.ScheduleMode.ONCE,
                            prompt = promptText,
                        ),
                        agentText,
                    )
                },
                enabled = canSend && !addPending,
            ) { Text(stringResource(R.string.cron_add_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !addPending) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        containerColor = FlipsiColors.PanelElev,
    )
}
