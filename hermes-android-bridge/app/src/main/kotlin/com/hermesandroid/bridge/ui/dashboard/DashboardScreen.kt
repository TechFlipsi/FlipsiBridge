package com.hermesandroid.bridge.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.AgentStatusRules
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.BridgeStatus
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.SectionTitle
import com.hermesandroid.bridge.ui.components.StateBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * W15 (Sir-Fixliste 9): Startseite = STATUS-DASHBOARD.
 *
 * W16 (Fixliste 17, Sir): die Karten unter der Agenten-Liste sind RAUS —
 * Verbindungskarte (Status = SSOT im Banner oben, Trennen lebt in
 * Einstellungen › Server), Kompakt-Fähigkeitsanzeige (Schalter leben im
 * Agenten-Tab) und LAN-Fußzeile. Übrig bleibt NUR das, was Sir am Start
 * sehen will: die Agenten-Slots mit Live-Arbeits-Kontext (Fixliste 14) —
 * Online/Offline/Beschäftigt, was der Agent gerade tut (chat.activity-
 * Working-Zustand mit mm:ss-Ticker), die SESSION-QUELLE + aktives Modell
 * (chat.activity-`session_meta` → [RelayClient.agentMeta]), laufende
 * SUB-AGENTEN (eingerückte Zeilen) und die letzte Aktivität (chat.event).
 *
 * Daten-Verband: ALLE Zustände kommen aus vorhandenen RelayClient-Flows
 * (relay.state → [RelayClient.agents], chat.activity →
 * [RelayClient.agentWorking], chat.session_meta → [RelayClient.agentMeta],
 * chat.event → [RelayClient.chatEvents], E2E-Sitzungen →
 * [RelayClient.e2eSessions]) — KEIN neuer Endpunkt, KEIN neuer Store; die
 * Sichtregeln rechnen in [DashboardRules] (JVM-getestet). Fehlende
 * Meta-Daten = die Zeile entfällt (kein Platzhalter-Geraffel).
 *
 * Live-Ticker: solange ein Agent frisch „beschäftigt“ ist, tickt die Uhr
 * sekündlich (mm:ss wie im Conversation-Kopf), sonst reicht ein 30-s-Raster
 * für die relativen Zeiten — die Schleife endet mit der Komposition.
 */
@Composable
fun DashboardScreen(
    padding: PaddingValues,
    onOpenAgentChat: (String) -> Unit,
) {
    // ── Live-Daten (vorhandene RelayClient-Flows) ───────────────────────────
    val agents by RelayClient.agents.collectAsState()
    val agentWorking by RelayClient.agentWorking.collectAsState()
    val agentMeta by RelayClient.agentMeta.collectAsState()
    // W18-Kette: agent.status-Verband — Labels am Haupt-Slot + Sub-Agenten-Slots
    val agentStatus by RelayClient.agentStatus.collectAsState()
    val agentSubs by RelayClient.agentSubs.collectAsState()
    val chatEvents by RelayClient.chatEvents.collectAsState()
    val e2eSessions by RelayClient.e2eSessions.collectAsState()
    // Verbindungs-Zustand für das Statusbanner (eine Warn-Instanz, oben).
    val connectionState by BridgeStatus.state.collectAsState()
    val connectionServerUrl by BridgeStatus.serverUrl.collectAsState()

    // ── Live-Uhr: 1 s bei Beschäftigung, sonst 30 s (relative Zeiten) ───────
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    // W15c (KLEIN B): Online-Gate auch am Ticker — ein OFFLINE-Agent mit
    // altem working-Frame zeigt nie eine Busy-Zeile (statusOf: Offline
    // schlägt working), darf die 1-s-Uhr also auch nicht am Laufen halten.
    val anyBusy = agents.any { entry ->
        val w = agentWorking[entry.agentId]
        (entry.online && w != null && w.working && !DashboardRules.workingTimedOut(w.startedAt, nowMs)) ||
            // W18-Kette: laufende Sub-Agenten ticken ebenfalls (Timer in
            // der „arbeitet“-Pille der Sub-Slots lebt vom selben Ticker).
            agentSubs[entry.agentId].orEmpty().values.any { s ->
                s.working && !DashboardRules.workingTimedOut(s.startedAt, nowMs)
            }
    }
    LaunchedEffect(anyBusy) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(if (anyBusy) 1_000L else 30_000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            // Karten enden nie hart an der Gesture-Navigationssäule
            bottom = 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Statusbanner: EINE Warn-Instanz, immer ERST ─────────────────────
        item(key = "banner") { DashboardStateBanner(connectionState, connectionServerUrl) }

        // ── Agenten-Slots (W15-Kern: wer arbeitet, woran) ────────────────────
        item(key = "agentsTitle") {
            // W15c (Jäger B5/A): Zusammenfassung als LabelRes-Liste aus den
            // Regeln — Auflösung erst HIER per stringResource/getQuantityString
            // (kein deutsches Literal mehr im Rule-Code), Teile mit „ · “.
            val summary = DashboardRules.summaryLine(
                total = agents.size,
                online = agents.count { it.online },
                // W15c (KLEIN B): Busy-Zähler MIT Online-Gate — Offline schlägt
                // working (statusOf-Regel), sonst zählt die Zeile Beschäftigte,
                // die gar nicht arbeiten können.
                busy = agents.count { entry ->
                    val w = agentWorking[entry.agentId]
                    entry.online && w != null && w.working &&
                        !DashboardRules.workingTimedOut(w.startedAt, nowMs)
                },
            )
            SectionTitle(
                stringResource(R.string.chat_list_title),
                sub = if (summary.isEmpty()) stringResource(R.string.dash_agents_sub)
                else summary.map { labelRes(it) }.joinToString(" · "),
            )
        }
        if (agents.isEmpty()) {
            item(key = "agentsEmpty") { AgentsEmptyCard() }
        } else {
            agents.forEach { entry ->
                item(key = "agent:${entry.agentId}") {
                    AgentSlotCard(
                        agent = entry,
                        working = agentWorking[entry.agentId],
                        meta = agentMeta[entry.agentId],
                        statusSlot = agentStatus[entry.agentId],
                        lastEvent = chatEvents.lastOrNull { it.agentId == entry.agentId },
                        e2eActive = e2eSessions.containsKey(entry.agentId),
                        nowMs = nowMs,
                        onOpen = { onOpenAgentChat(entry.agentId) },
                    )
                }
            }
            // ── W18-Kette: Sub-Agenten-Slots UNTER den Agenten-Slots ─────────
            // SIRS-Design: läuft delegate_task, steht der Sub-Agent als Slot
            // im eigenen Abschnitt — end=true entfernt ihn (agent.status-
            // Verband). Slot-Labels kommen vom Server (Maschinenwort).
            item(key = "subAgents") {
                SubAgentSlotsSection(
                    slots = agents.flatMap { entry -> agentSubs[entry.agentId].orEmpty().values },
                    nowMs = nowMs,
                )
            }
        }
    }
}

// ── Agenten-Slot ─────────────────────────────────────────────────────────────

/**
 * W15c (Jäger B5/A): LabelRes → Text — Strings per stringResource, Plural-
 * Ressourcen (count != null) per getQuantityString (die Zahl ist zugleich
 * die Mengen-Zahl). Auflösung erst bei der Anzeige: ein Sprachwechsel wirkt
 * sofort, kein eingefrorener Text mehr (Muster wie SettingsNav.topBarPath).
 */
@Composable
private fun labelRes(label: DashboardRules.LabelRes): String =
    if (label.count != null)
        LocalContext.current.resources
            .getQuantityString(label.res, label.count, *label.args.toTypedArray())
    else
        stringResource(label.res, *label.args.toTypedArray())

/**
 * EIN Agent-Slot: Avatar mit Anfangsbuchstaben (Zustandsfarbe), Name +
 * E2E-Kleppel, Live-Arbeitszeile („arbeitet … 2:14“ mit Spinner), die
 * SESSION-QUELLE + aktives Modell (W16, Fixliste 14 — fehlende Daten =
 * Zeile weglassen), die laufenden SUB-AGENTEN (eingerückte Zeilen) und
 * die Status-Pille rechts. Tap öffnet die Conversation dieses Agenten
 * (Chat-Tab).
 */
@Composable
private fun AgentSlotCard(
    agent: RelayClient.AgentEntry,
    working: RelayClient.AgentActivity?,
    meta: RelayClient.AgentSessionMeta?,
    // W18-Kette: agent.status-Hauptstatus — Quelle des Arbeits-Labels neben
    // dem Zeit-Timer (kein Frame = kein Label, ehrliches „arbeitet …“).
    statusSlot: AgentStatusRules.AgentStatusSlot?,
    lastEvent: RelayClient.ChatMessageEvent?,
    e2eActive: Boolean,
    nowMs: Long,
    onOpen: () -> Unit,
) {
    val status = DashboardRules.statusOf(
        online = agent.online,
        working = working?.working,
        startedAt = working?.startedAt ?: 0L,
        nowMs = nowMs,
    )
    val tint = when (status) {
        DashboardRules.AgentStatus.BUSY -> FlipsiColors.Ember
        DashboardRules.AgentStatus.ONLINE -> FlipsiColors.SignalGreen
        DashboardRules.AgentStatus.OFFLINE -> FlipsiColors.TextSecondary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.a11y_open_agent_chat, agent.displayName),
            ) { onOpen() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar: Zustandsfarbe getönt, Buchstabe nie leer (Fallback „?“)
            Box(
                Modifier
                    .size(44.dp)
                    .background(tint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    agent.displayName.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        agent.displayName.ifBlank { agent.agentId },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (e2eActive) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.nav_tab_e2e),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FlipsiColors.SignalGreen,
                            modifier = Modifier
                                .background(FlipsiColors.SignalGreen.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                // Live-Arbeitszeile: nur bei frischem working=true (Deckel in
                // den Regeln) — nie ein Ewigzähler (PIPELINE-BUGS BUG 3).
                if (status == DashboardRules.AgentStatus.BUSY) {
                    Spacer(Modifier.height(4.dp))
                    // W18-Kette: Status-Pill-Verband — Server-Maschinenwort
                    // (z. B. „Sucht im Web“) neben dem Zeit-Timer; ohne Label
                    // oder wenn der agent.status-Verband ehrlich working=false
                    // meldet, bleibt das neutrale „arbeitet … (m:ss)“.
                    val label = statusSlot?.label
                        ?.takeIf { it.isNotBlank() && statusSlot.working }
                    val timer = DashboardRules.workingElapsedLabel(working?.startedAt ?: 0L, nowMs)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = FlipsiColors.Ember,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (label != null) "$label · $timer"
                            else stringResource(R.string.dash_working, timer),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.Ember,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // W16 (Fixliste 14): SESSION-QUELLE + aktives Modell — je Teil
                // zeigen, was da ist („Discord · Modell glm-…“), fehlt beides,
                // entfällt die Zeile (kein Platzhalter-Geraffel).
                val metaForAgent = meta
                if (metaForAgent?.platform != null || metaForAgent?.model != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            metaForAgent.platform?.let { platformLabel(it) },
                            metaForAgent.model?.let { stringResource(R.string.dash_model_label, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == DashboardRules.AgentStatus.BUSY) FlipsiColors.Ember
                        else FlipsiColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // W16 (Fixliste 14): SUB-AGENTEN — eingerückte Zeile je Agent
                // („SUB-Agent X am Coden (Modell Y)“); Status/Modell je nach
                // Vorhandensein.
                metaForAgent?.subAgents?.takeIf { it.isNotEmpty() }?.forEach { sub ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(stringResource(R.string.dash_sub_agent, sub.name))
                            if (!sub.status.isNullOrBlank()) append(" ${sub.status}")
                            if (!sub.model.isNullOrBlank())
                                append(" " + stringResource(R.string.dash_sub_model, sub.model))
                        },
                        // Eingerückt — Kind-Ebene klar unter dem Slot-Header
                        // (keine zweite Karten-Ebene, Sir-Layout-Fallwurf).
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlipsiColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Letzte Aktivität: der letzte chat.event-Text des Agenten.
                Spacer(Modifier.height(4.dp))
                Text(
                    lastEvent?.text?.ifBlank { "—" } ?: stringResource(R.string.dash_no_messages),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // W15c: relative Zeit kommt als LabelRes aus den Regeln —
                    // Auflösung hier; ts <= 0 (kein brauchbarer Zeitstempel)
                    // bleibt der ehrliche Em-dash, nie eine Fake-Uhrzeit.
                    if (lastEvent != null)
                        stringResource(
                            R.string.dash_last_active,
                            DashboardRules.relativeTime(lastEvent.ts, nowMs)
                                ?.let { labelRes(it) } ?: "—",
                        )
                    else
                        stringResource(R.string.dash_no_activity),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlipsiColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(
                // W15c: Status-Wort als LabelRes — Auflösung hier (DE/EN live).
                label = labelRes(DashboardRules.statusLabel(status)),
                tint = tint,
            )
        }
    }
}

/**
 * Status-Pille rechts am Slot: Punkt + Text mit konstantem Abstand —
 * W16 (Fixliste 13, Sir): der 6dp-Punkt saß im ONLINE-Zweig direkt am
 * Text (nur der busy-Zweig hatte den Spacer). Jetzt EINFACH: Punkt +
 * Spacer(4dp) IMMER, keine Doppel-Box-Zweige mehr (A11y-Text unverändert).
 */
@Composable
private fun StatusPill(label: String, tint: Color) {
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

/** Leer-Zustand: noch kein Agent sichtbar (kein Relay-Connect / keine Kopplung). */
@Composable
private fun AgentsEmptyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.dash_no_agents), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.dash_no_agents_desc),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ── W18-Kette: Sub-Agenten-Slots (agent.status-Verband) ──────────────────────

/**
 * SIRS-Design: Sub-Agenten der laufenden delegate_task-Ketten als eigene
 * Slots UNTER den Agenten-Slots — Titel „Sub-Agenten“ + 0-n Slots (Name aus
 * sub.name, Status-Pille „arbeitet … (m:ss)“ aus dash_working), Farbe Ember
 * wenn der Sub arbeitet (10-Min-Deckel wie am Haupt-Slot), neutral sonst.
 * end=true entfernt den Slot — ohne laufende Sub-Agenten bleibt der
 * ehrliche Leer-Zustand („keine“).
 */
@Composable
private fun SubAgentSlotsSection(
    slots: List<AgentStatusRules.SubAgentSlot>,
    nowMs: Long,
) {
    SectionTitle(stringResource(R.string.dashboard_sub_agents_title))
    if (slots.isEmpty()) {
        Text(
            stringResource(R.string.dashboard_sub_agents_empty),
            style = MaterialTheme.typography.bodySmall,
            color = FlipsiColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    // Sir-Design-Befund 17.11. (Screenshot img_2f233f93): der Sub-Agenten-
    // Titel klebte am ersten laufenden Slot — paddingFromBaseline wirkt nur
    // NACH OBEN, unter dem Titel fehlte der Rhythmus-Abstand. Einheitlich
    // wie über den Agenten-Karten: 8dp Luft.
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.forEach { slot -> SubAgentSlotRow(slot, nowMs) }
    }
}

/** EIN Sub-Agent-Slot: Mini-Avatar (Initial in Zustandsfarbe), Name, „arbeitet“-Pille. */
@Composable
private fun SubAgentSlotRow(
    slot: AgentStatusRules.SubAgentSlot,
    nowMs: Long,
) {
    // Sir-Befund 17.11. (Screenshot img_a039d5aa): der 10-Min-Deckel ist eine
    // HAUPT-Slot-Absicherung (verlorenes OFF) — am SUB-Slot kippte er laufende
    // Subs nach 10 Min auf „arbeitet nicht mehr", obwohl der Slot lebt. Ein
    // Sub-Slot existiert nur bis end=true (natürliche Begrenzung, der Adapter
    // feuert subagent_stop): busy folgt slot.working OHNE Zeit-Deckel.
    val busy = slot.working
    val tint = if (busy) FlipsiColors.Ember else FlipsiColors.TextSecondary
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(tint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    slot.name.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                slot.name,
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                StatusPill(
                    label = stringResource(
                        R.string.dash_working,
                        DashboardRules.workingElapsedLabel(slot.startedAt, nowMs),
                    ),
                    tint = FlipsiColors.Ember,
                )
            }
        }
    }
}

// ── W16 (Fixliste 14): Plattform-Label der Session-Quelle ────────────────────

/**
 * Plattform-Token (bridge/discord/telegram/cron/…) als Anzeigewort —
 * erster Buchstabe groß, Namen bleiben unverändert (Protokoll-Konstante,
 * wie „Discord“ selbst). Null/falsch = Zeile entfällt in der UI.
 */
private fun platformLabel(platform: String): String =
    platform.trim().replaceFirstChar { c -> c.uppercaseChar() }

// ── Statusbanner (unverändert aus dem alten Home übernommen) ──────────────────

/**
 * Banner-Verkabelung: Zustands-Matching auf ConnectionState — die frühere
 * String-Grep-Route („Verbinde“/„Neuverbindung“ im Message-Text) ist komplett
 * raus. Das Neu-Code-Ereignis überdeckt kurzzeitig die Zustands-Anzeige, wenn
 * weder verbunden noch die Kopplung abgelehnt ist. EINE Warn-Instanz bleibt
 * die Regel — der Banner ist die einzige Stelle, die den Verbindungszustand
 * malt.
 */
@Composable
private fun DashboardStateBanner(
    state: BridgeStatus.ConnectionState,
    serverUrl: String?,
) {
    val codeEvent = PairingManager.getLastCodeEvent()
    // Der REGENERATED-Merker persistiert in den Prefs (beim allerersten
    // Code-Anlegen gesetzt, nie zurückgesetzt) — der Verdeck-Fall darf
    // deshalb KEINE Fehler- oder Wiederverbindungs-Kommunikation ausblenden
    // (FAILED-Ursache-Banner + RECONNECTING-Hinweis), sonst ist die ganze
    // Zustands-Banner-Logik im Normalbetrieb unsichtbar. Verdeckt bleibt nur
    // die Erstnutzer-Führung (IDLE/CONNECTING).
    if (codeEvent == PairingManager.CodeEvent.REGENERATED &&
        state !is BridgeStatus.ConnectionState.CONNECTED &&
        state !is BridgeStatus.ConnectionState.PAIRING_BROKEN &&
        state !is BridgeStatus.ConnectionState.FAILED &&
        state !is BridgeStatus.ConnectionState.RECONNECTING
    ) {
        StateBanner(
            tint = FlipsiColors.SignalAmber,
            title = stringResource(R.string.banner_regen_title),
            text = stringResource(R.string.banner_regen_text),
            badge = stringResource(R.string.banner_regen_badge) to BadgeState.WARN,
        )
        return
    }
    val visual = when (state) {
        is BridgeStatus.ConnectionState.PAIRING_BROKEN -> BannerVisual(
            tint = FlipsiColors.SignalRedText,
            title = stringResource(R.string.banner_broken_title),
            text = stringResource(R.string.banner_broken_text),
            busy = false,
            badge = stringResource(R.string.banner_broken_badge) to BadgeState.ERROR,
        )
        is BridgeStatus.ConnectionState.CONNECTED -> BannerVisual(
            tint = FlipsiColors.SignalGreen,
            title = stringResource(R.string.banner_connected_title),
            text = stringResource(R.string.banner_connected_text),
            busy = false,
            badge = stringResource(R.string.banner_connected_badge) to BadgeState.OK,
        )
        is BridgeStatus.ConnectionState.CONNECTING -> connectingVisual()
        is BridgeStatus.ConnectionState.RECONNECTING -> reconnectingVisual(state.ursache)
        is BridgeStatus.ConnectionState.FAILED -> BannerVisual(
            tint = FlipsiColors.SignalAmber,
            title = stringResource(R.string.banner_failed_title),
            // Der Banner sagt Ursache + Handlung (generisch) — die rohe
            // Ursache bleibt in der Detailzeile der Verbindungskarte sichtbar.
            text = ursacheHinweis(state.ursache),
            busy = false,
            badge = stringResource(R.string.banner_failed_badge) to BadgeState.OFF,
        )
        is BridgeStatus.ConnectionState.IDLE -> BannerVisual(
            tint = FlipsiColors.TextSecondary,
            title = stringResource(R.string.banner_failed_title),
            text = if (!serverUrl.isNullOrBlank())
                stringResource(R.string.banner_idle_url)
            else
                stringResource(R.string.banner_idle_no_url),
            busy = false,
            badge = stringResource(R.string.banner_failed_badge) to BadgeState.OFF,
        )
    }
    StateBanner(
        tint = visual.tint,
        title = visual.title,
        text = visual.text,
        busy = visual.busy,
        badge = visual.badge,
    )
}

/** Sichtwerte des Status-Banners je Zustand. */
private data class BannerVisual(
    val tint: Color,
    val title: String,
    val text: String,
    val busy: Boolean,
    val badge: Pair<String, BadgeState>,
)

/** Verbindungsaufbau (erstmalig oder in der Schleife): Spinner-Visual. */
@Composable
private fun connectingVisual(): BannerVisual = BannerVisual(
    tint = FlipsiColors.SignalAmber,
    title = stringResource(R.string.banner_connecting_title),
    text = stringResource(R.string.banner_connecting_text),
    busy = true,
    badge = stringResource(R.string.banner_connecting_badge) to BadgeState.WARN,
)

/**
 * Wiederverbindungs-Schleife: Spinner + Ursachen-Hinweis — die rohe Ursache
 * (Relay-Text) bleibt in der Statuszeile der Verbindungskarte sichtbar, der
 * Banner sagt, was der Nutzer tun kann (generisch, ohne private Daten).
 */
@Composable
private fun reconnectingVisual(ursache: String): BannerVisual {
    val hint = when {
        // Eigene RelayClient-Meldung (Termux-Revival-Auslösung).
        ursache.startsWith("Stack nicht erreichbar") ->
            stringResource(R.string.hint_stack_unreachable)
        else ->
            stringResource(R.string.hint_retry_generic)
    }
    return BannerVisual(
        tint = FlipsiColors.SignalAmber,
        title = stringResource(R.string.banner_connecting_title),
        text = hint,
        busy = true,
        badge = stringResource(R.string.banner_connecting_badge) to BadgeState.WARN,
    )
}

/**
 * Ursachen-Text des Relais in nutzerseitige Handlungs-Hinweise übersetzen.
 * Die Präfixe stammen aus unseren EIGENEN RelayClient-Meldungen
 * (deterministische Konstanten) — unbekannte Fälle zeigen den generischen
 * Hinweis.
 */
@Composable
private fun ursacheHinweis(ursache: String): String = when {
    ursache.startsWith("Server-Adresse ungültig") ->
        stringResource(R.string.hint_url_invalid)
    ursache.startsWith("Verbindungsfehler") ->
        stringResource(R.string.hint_connect_error)
    ursache.startsWith("Revival") ->
        stringResource(R.string.hint_revival_failed)
    else ->
        stringResource(R.string.hint_connect_failed)
}
