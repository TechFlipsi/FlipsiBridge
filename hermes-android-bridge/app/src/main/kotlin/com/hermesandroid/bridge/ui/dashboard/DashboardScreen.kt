package com.hermesandroid.bridge.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.AgentStatusRules
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.client.AgentInfoRules
import com.hermesandroid.bridge.client.RetryCountdown  // W23 Lane U5: Backoff-Countdown-Format
import com.hermesandroid.bridge.ui.BridgeStatus
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.SectionTitle
import com.hermesandroid.bridge.ui.components.StateBanner
import com.hermesandroid.bridge.ui.components.StatusDot
import com.hermesandroid.bridge.ui.rules.LocalWindowSizeClass
import com.hermesandroid.bridge.ui.rules.WindowFoldRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * W15 (Fixliste 9): Startseite = STATUS-DASHBOARD.
 *
 * W16 (Fixliste 17): die Karten unter der Agenten-Liste sind RAUS —
 * Verbindungskarte (Status = SSOT im Banner oben, Trennen lebt in
 * Einstellungen › Server), Kompakt-Fähigkeitsanzeige (Schalter leben im
 * Agenten-Tab) und LAN-Fußzeile. Übrig bleibt NUR das, was Besitzer am Start
 * sehen will: die Agenten-Slots mit Live-Arbeits-Kontext (Fixliste 14) —
 * Online/Offline/Beschäftigt, was der Agent gerade tut (chat.activity-
 * Working-Zustand mit mm:ss-Ticker), die SESSION-QUELLE + aktives Modell
 * (chat.activity-`session_meta` → [RelayClient.agentMeta]) und die letzte
 * Aktivität (chat.event). Laufende SUB-AGENTEN stehen ALLEIN in der
 * Slots-Sektion (W19 KEIN-DOPPEL: EIN Ort, nie zweimal am Screen).
 *
 * F1 (18.11.2026): KARTE „GEPLANTE AUFGABEN“ — Gateway-Cron-Jobs aus dem
 * cron.status(-enc)-Frame des Adapters (APP-GENERISCH: EIN app-globales
 * Gebilde, der Agent-Name gehört nicht in die Karte). KEIN-DOPPEL mit den
 * Sub-Slots: die Cron-Liste teilt keine Elemente/Ebene mit den Sub-Agenten-
 * Slots (jene hängen an agent.status/agentSubs, diese an cron.status —
 * zwei getrennte Verbände). Leerzustand: Sektion bleibt KOMPLETT weg.
 *
 * Daten-Verband: ALLE Zustände kommen aus vorhandenen RelayClient-Flows
 * (relay.state → [RelayClient.agents], chat.activity →
 * [RelayClient.agentWorking], chat.session_meta → [RelayClient.agentMeta],
 * chat.event → [RelayClient.chatEvents], E2E-Sitzungen →
 * [RelayClient.e2eSessions]) — KEIN neuer Endpunkt, KEIN neuer Store; die
 * Sichtregeln rechnen in [DashboardRules] (JVM-getestet). Fehlende
 * Meta-Daten = die Zeile entfällt (kein Platzhalter-Geraffel).
 *
 * STRINGS (MERGE-LANE 18.11.): alle 11 dash_cron_*-Texte leben je Locale in
 * strings.xml (DE = values/ + values-de/, EN = values-en/) — aufgelöst in
 * [cronStatusWord]/[cronNextRunText] per stringResource; die TAGE als
 * Plural-Ressourcen (dash_cron_in_days/dash_cron_ago_days — Singular-Rettung
 * „1 Tag“, dash_days_ago-Muster). EN: Scheduled tasks / running / planned /
 * paused / overdue / in %1$d min / in %1$d h / in %1$d days / %1$d min ago /
 * %1$d h ago / %1$d days ago.
 *
 * Live-Ticker: solange ein Agent frisch „beschäftigt“ ist, tickt die Uhr
 * sekündlich (mm:ss wie im Conversation-Kopf), sonst reicht ein 30-s-Raster
 * für die relativen Zeiten — die Schleife endet mit der Komposition.
 */
@Composable
fun DashboardScreen(
    padding: PaddingValues,
    onOpenAgentChat: (String) -> Unit,
    // W26 (Agenten-Zentrale): Tap auf den Cron-Aufstand der Kachel → der
    // Cron-Tab im Agent-Kontext (der ausgewählte Agent = die cron.remove/
    // pause-Adresse). Default inerter Kanal (kein Pflicht-Param je Bestand).
    onOpenAgentCron: (String) -> Unit = {},
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
    // F1 (Verdrahtung MERGE-LANE 18.11.): cron.status-Jobs aus dem
    // RelayClient-Flow (APP-GENERISCH, je Frame = Gesamt-Stand/REPLACE;
    // Räumung an den 3 Verbindungspunkten — Parität markAgentsOffline).
    val cronJobs by RelayClient.cronJobs.collectAsState()
    // W26 (Agenten-Zentrale, App-Brief §2): der PRO-AGENT-Stand
    // (Cron-Zähler je Kachel) + der agent.info-Cap-Stand (Verwaltungs-Anzeige)
    // — dieselben RelayClient-Flows wie der Cron-Tab, KEIN neuer Endpunkt.
    val cronJobsByAgent by RelayClient.cronJobsByAgent.collectAsState()
    val agentCaps by RelayClient.agentCaps.collectAsState()
    // Verbindungs-Zustand für das Statusbanner (eine Warn-Instanz, oben).
    val connectionState by BridgeStatus.state.collectAsState()
    val connectionServerUrl by BridgeStatus.serverUrl.collectAsState()
    // W23 Lane U5: Rest-Sekunden des Reconnect-Backoffs (je-Tick-Tick der
    // App; null = kein Backoff-Fenster) — der Verbindungs-Banner zeigt sie
    // als „neuer Versuch in N s“.
    val retryCountdownSec by RelayClient.retryCountdownSec.collectAsState()

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

    // ── W24 Lane C (T2): Expanded-Fenster-Verband ────────────────────────────
    // Die Falt-Entscheidung hängt an der LANE-A-SSOT (12-Jäger-Konsens, T1/T7-
    // Befund: KEINE LocalConfiguration-Ableitung — die WindowSizeClass liest
    // MainActivity.calculateWindowSizeClass und wandert via
    // [LocalWindowSizeClass] in den Baum): EXPANDED-Width = useTwoPane → das
    // Raster faltet auf 2 Spalten; compact/medium (und fold = null fail-soft,
    // Preview-/alt-Kontext) bleibt EXAKT der W15-W23-Bestand.
    val fold = LocalWindowSizeClass.current?.let {
        WindowFoldRules.compose(it.widthSizeClass, it.heightSizeClass)
    }

    if (fold?.useTwoPane == true) {
        // Vorlage-Anordnung: MediathekTab LazyVerticalGrid (Z.145) — je
        // Abschnitts-Item full-span ([GridItemSpan.maxLineSpan]), die
        // Agenten-Slot-Karten als 2-Spalten-Raster. Der StateBanner bleibt
        // full-span (T2-Befund: EINE Warn-Instanz über die volle Breite).
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                // W28 (Lane B, Inset-SSOT): EINE Boden-Quelle je Zustand — je
                // TwoPane (Rail-Verband, KEIN Bar-Slot) trägt der Screen die
                // Gesture-Säule: 48 dp Haus-Touch-Raum + navigationBars. Sitze
                // eine Leiste über der Säule, stünde die Säule doppelt. Hier
                // je EXPANDED = immer Rail → identische Geometrie wie vorher.
                // Lane F (Reviewer-Auflage): die Formel lebt NUR noch im
                // SSOT-Helfer ([WindowFoldRules.bottomBarFloorPadding]) —
                // wertidentisch, github-34 = 0-dp-Delta unverändert.
                bottom = WindowFoldRules.bottomBarFloorPadding(fold),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Statusbanner: EINE Warn-Instanz, immer ERST, volle Breite ───
            item(key = "banner", span = { GridItemSpan(maxLineSpan) }) {
                DashboardStateBanner(connectionState, connectionServerUrl, retryCountdownSec)
            }
            // ── Agenten-Slots (W15-Kern: wer arbeitet, woran) → Titel vorn ──
            item(key = "agentsTitle", span = { GridItemSpan(maxLineSpan) }) {
                AgentsSlotsTitle(agents, agentWorking, nowMs)
            }
            if (agents.isEmpty()) {
                item(key = "agentsEmpty", span = { GridItemSpan(maxLineSpan) }) {
                    AgentsEmptyCard()
                }
            } else {
                // items-key je Grid (Brief-Pflicht): derselbe bestehende
                // Verbands-Key wie im LazyColumn-Zweig — "agent:<agent_id>".
                items(agents, key = { "agent:${it.agentId}" }) { entry ->
                    AgentSlotCard(
                        agent = entry,
                        working = agentWorking[entry.agentId],
                        meta = agentMeta[entry.agentId],
                        statusSlot = agentStatus[entry.agentId],
                        lastEvent = chatEvents.lastOrNull { it.agentId == entry.agentId },
                        e2eActive = e2eSessions.containsKey(entry.agentId),
                        // W26: Cron-Zähler je agent_id + Cap-Anzeige je
                        // agent.info + der Sprung in den Agent-Kontext.
                        cronCount = cronJobsByAgent[entry.agentId].orEmpty().size,
                        managesCron = AgentInfoRules.managesCron(agentCaps[entry.agentId]?.caps),
                        nowMs = nowMs,
                        onOpen = { onOpenAgentChat(entry.agentId) },
                        onOpenCron = { onOpenAgentCron(entry.agentId) },
                    )
                }
                // W18-Kette: Sub-Slots als EIN full-span-Item (die Zeilen
                // bleiben ganzbreite Status-Zeilen, KEIN 2-Spalten-Kleinbild).
                val subSlots = agents.flatMap { entry -> agentSubs[entry.agentId].orEmpty().values }
                if (subSlots.isNotEmpty()) {
                    item(key = "subAgents", span = { GridItemSpan(maxLineSpan) }) {
                        SubAgentSlotsSection(slots = subSlots, nowMs = nowMs)
                    }
                }
            }
        }
    } else {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            // W28 (Lane B, Inset-SSOT): EINE Boden-Quelle je Zustand — lebt
            // die Tab-Leiste ÜBER der Gesture-Säule (kompaktes Portrait),
            // trägt SIE die Säule intern und der Screen rechnet NUR den
            // Haus-Touch-Raum (48 dp; vorher: Säule nochmals hier → auf
            // Play-36 doppelter Boden-Abstand, github-34 schlief mit 0 dp).
            // Ohne Leistenslot (Handy-Landschaft/medium = Rail) trägt der
            // Screen die Säule: 48 dp + navigationBars (K4-Bestand,
            // „Karten enden nie hart an der Gesture-Navigationssäule“).
            // Lane F (Reviewer-Auflage): die Formel lebt NUR noch im
            // SSOT-Helfer ([WindowFoldRules.bottomBarFloorPadding]) —
            // wertidentisch, github-34 = 0-dp-Delta unverändert.
            bottom = WindowFoldRules.bottomBarFloorPadding(fold),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Statusbanner: EINE Warn-Instanz, immer ERST ─────────────────────
        item(key = "banner") {
            DashboardStateBanner(connectionState, connectionServerUrl, retryCountdownSec)
        }

        // ── Agenten-Slots (W15-Kern: wer arbeitet, woran) ────────────────────
        item(key = "agentsTitle") {
            AgentsSlotsTitle(agents, agentWorking, nowMs)
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
                        // W26: Cron-Zähler je agent_id + Cap-Anzeige je
                        // agent.info + der Sprung in den Agent-Kontext.
                        cronCount = cronJobsByAgent[entry.agentId].orEmpty().size,
                        managesCron = AgentInfoRules.managesCron(agentCaps[entry.agentId]?.caps),
                        nowMs = nowMs,
                        onOpen = { onOpenAgentChat(entry.agentId) },
                        onOpenCron = { onOpenAgentCron(entry.agentId) },
                    )
                }
            }
            // ── W18-Kette: Sub-Agenten-Slots UNTER den Agenten-Slots ─────────
            // SIRS-Design: läuft delegate_task, steht der Sub-Agent als Slot
            // im eigenen Abschnitt — end=true entfernt ihn (agent.status-
            // Verband). Slot-Labels kommen vom Server (Maschinenwort).
            // W19 KEIN-DOPPEL + Leerzustand: die Sektion erscheint NUR bei
            // mindestens einem laufenden Sub-Agenten — sonst bleibt der
            // Abschnitt (Titel + Zeile) komplett weg.
            val subSlots = agents.flatMap { entry -> agentSubs[entry.agentId].orEmpty().values }
            if (subSlots.isNotEmpty()) {
                item(key = "subAgents") {
                    SubAgentSlotsSection(slots = subSlots, nowMs = nowMs)
                }
            }
        }
        // ── F1: Karte „Geplante Aufgaben“ (cron.status-Verband, app-global) ──
        // W23-11c („den Block raus vom Dashboard“): lebt ab dem 5. Tab
        // (CronScreen) — das Dashboard rendert sie bewusst NICHT mehr, damit
        // „Geplante Aufgaben“ an GENAU EINEM Ort steht. Die Sub-Slot-Sektion
        // (W23-11c „DAFÜR zurück“) bleibt oben am Dashboard-Verband.
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
 * W24 Lane C: Titel-Ebene über den Agenten-Slots — aus dem item-Lambda des
 * LazyColumn-Zweigs EINDOCKT, damit compact/medium UND das 2-Spalten-Raster
 * DENSELBEN W15c-Verband singen (EINE Zusammenfassungs-Rechnung, kein
 * Zweig-Doppel). Auflösung je Anzeige: LabelRes-Liste → stringResource/
 * getQuantityString, Teile mit „ · “.
 */
@Composable
private fun AgentsSlotsTitle(
    agents: List<RelayClient.AgentEntry>,
    agentWorking: Map<String, RelayClient.AgentActivity>,
    nowMs: Long,
) {
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

/**
 * EIN Agent-Slot: Avatar mit Anfangsbuchstaben (Zustandsfarbe), Name +
 * E2E-Kleppel, Live-Arbeitszeile („arbeitet … 2:14“ mit Spinner), die
 * SESSION-QUELLE + aktives Modell (W16, Fixliste 14 — fehlende Daten =
 * Zeile weglassen) und die Status-Pille rechts. Laufen Sub-Agenten, stehen
 * sie AUSSCHLIESSLICH in der Slots-Sektion (W19 KEIN-DOPPEL) — hier nie
 * eingerichtete Sub-Zeilen mehr. Tap öffnet die Conversation dieses
 * Agenten (Chat-Tab).
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
    // W26 (Agenten-Zentrale, App-Brief §2): Cron-Zähler je agent_id
    // (aus cron.status), Cap-Anzeige je agent.info („verwaltet: Cron“ —
    // VERSIONS-fail-soft: kein Frame = ehrlich ohne Anzeige) und der Sprung
    // in den Cron-Tab je Agent-Kontext.
    cronCount: Int = 0,
    managesCron: Boolean = false,
    nowMs: Long,
    onOpen: () -> Unit,
    onOpenCron: () -> Unit = {},
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
                // W19 KEIN-DOPPEL: Sub-Agenten stehen NICHT mehr in der
                // SlotCard (das session_meta-Duplikat ist entfernt) — die
                // SubAgentSlotsSection unter den Karten ist der EINE Ort.
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
                // W26: Cron-Zähler + „verwaltet: Cron“ — je Teilen NUR was da
                // ist (kein Platzhalter-Geraffel): Zähler je cron.status-Stand
                // des Agents, Cap-Anzeige je agent.info, beide „ · “-verbunden;
                // ohne beides bleibt die Zeile WEG (Alter Adapter ohne
                // agent.info UND ohne Jobs = Bestand unverändert).
                if (cronCount > 0 || managesCron) {
                    Spacer(Modifier.height(3.dp))
                    val plural = LocalContext.current.resources
                    val counterText = if (cronCount > 0)
                        plural.getQuantityString(
                            R.plurals.dash_agent_cron_count,
                            cronCount, cronCount,
                        ) else ""
                    val manageText = if (managesCron)
                        stringResource(R.string.dash_cron_managed_suffix) else ""
                    Text(
                        listOf(counterText, manageText)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlipsiColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // W26: der Cron-Sprung — Uhr-Icon NUR je cron.status-Stand des
            // Agents ODER gemeldeter Cron-Cap (VERSIONS-fail-soft: ein Alter
            // Adapter bleibt ehrlich ohne Antrieb, KEIN Fake-Ziel); 48 dp
            // Touch-Ziel (Haus-Standard, Parität Zeilen-Icons).
            if (cronCount > 0 || managesCron) {
                IconButton(
                    onClick = onOpenCron,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_clock),
                        contentDescription = stringResource(
                            R.string.dash_open_cron_a11y, agent.displayName,
                        ),
                        tint = FlipsiColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
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
 * W16 (Fixliste 13): der 6dp-Punkt saß im ONLINE-Zweig direkt am
 * Text (nur der busy-Zweig hatte den Spacer). Jetzt EINFACH: Punkt +
 * Spacer(4dp) IMMER, keine Doppel-Box-Zweige mehr (A11y-Text unverändert).
 * W23-8: der Punkt ist der gemeinsame StatusDot — derselbe Stil wie
 * Chat-Header-/Agenten-Avatar (Farbe hier je Zustand, busy = Ember; der
 * Dot bleibt stumm, das Wort daneben trägt die A11y).
 */
@Composable
private fun StatusPill(label: String, tint: Color) {
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = tint, dotSize = 6.dp)
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
 * Slots UNTER den Agenten-Slots — Titel „Sub-Agenten“ + 1-n Slots (Name aus
 * sub.name, Status-Pille „arbeitet … (m:ss)“ aus dash_working), Farbe Ember
 * wenn der Sub arbeitet, neutral sonst. end=true entfernt den Slot; der
 * 10-Minuten-Deckel bleibt NUR am Haupt-Slot (ein Sub-Slot endet mit
 * end=true, wie SubAgentSlotRow es rechnet).
 *
 * W19 KEIN-DOPPEL (Konsolidierung): DIESE Sektion ist der EINZIGE Ort der
 * Sub-Agenten am Dashboard — die eingerückten Zeilen in der AgentSlotCard
 * (Quelle session_meta, dash_sub_agent/dash_sub_model) sind ENTFERNT.
 * W19 Leerzustand: die Sektion existiert NUR bei ≥1 laufendem Sub-Agenten —
 * der Screen ruft sie bedingungsgerendert (kein „keine“-Geraffel, keine
 * leere Titelzeile).
 */
@Composable
private fun SubAgentSlotsSection(
    slots: List<AgentStatusRules.SubAgentSlot>,
    nowMs: Long,
) {
    SectionTitle(stringResource(R.string.dashboard_sub_agents_title))
    // Besitzer-Design-Befund 17.11. (Screenshot img_2f233f93): der Sub-Agenten-
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
    // Befund 17.11. (Screenshot img_a039d5aa): der 10-Min-Deckel ist eine
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

// ── F1 (18.11.2026): Karte „Geplante Aufgaben“ (cron.status-Verband) ─────────

/**
 * EIN app-globales Karten-Gebilde (APP-GENERISCH): Liste der Gateway-Cron-Jobs
 * mit Status-Punkt (Zustandsfarbe) + Name + relativem Termin. KEIN-DOPPEL mit
 * den Sub-Slots (W19): eigene Sektion, eigene Datenquelle (cron.status), keine
 * Sub-Agenten-Elemente. Leerzustand rendert NIEMALS (der Screen ruft die Karte
 * nur mit cronCardVisible() = ≥1 Job).
 *
 * Strings (MERGE-LANE 18.11.): Titel + Status-/Termin-Worte lösen je Locale
 * aus strings.xml ×3 (dash_cron_*; Tage als Plural-Ressource) — kein DE-Literal
 * mehr im Screen, die Rules liefern nur Zahlen/Enums.
 */
@Composable
private fun CronJobsCard(rows: List<DashboardRules.CronJobView>) {
    SectionTitle(stringResource(R.string.dash_cron_title))
    Spacer(Modifier.height(8.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            rows.forEachIndexed { index, row ->
                CronJobRow(row, divider = index > 0)
            }
        }
    }
}

/** EINE Cron-Zeile: Status-Punkt (Zustandsfarbe), Name, rechts Wort + Term. */
@Composable
private fun CronJobRow(row: DashboardRules.CronJobView, divider: Boolean) {
    // Zustand nie NUR über Farbe (A11y): neben dem Punkt steht immer das Wort.
    val state = row.item.state
    val tint = when {
        state == DashboardRules.CronJobState.RUNNING -> FlipsiColors.Ember
        state == DashboardRules.CronJobState.PLANNED && row.overdue -> FlipsiColors.SignalRedText
        state == DashboardRules.CronJobState.PLANNED -> FlipsiColors.SignalGreen
        else -> FlipsiColors.TextSecondary
    }
    if (divider) {
        // Sichtbarer Trenner — LineStrong (Konvention BridgeTheme: Line ist auf
        // Panel zu schwach, TextSecondary-alpha wäre ein zweites Farb-Etikett).
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(FlipsiColors.LineStrong),
        )
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
 * „vor X“ (rot getönt via Status-Wort), ohne Termin (running/paused) = null →
 * Zeile entfällt. Uhr-Vergleich = nowMs (gleiche Eimer-Logik wie relativeTime;
 * Device-Uhr-Abweichung verschiebt die Eimer). Tage = Plural-Ressource
 * (dash_cron_in_days/dash_cron_ago_days, W15c-Muster: Singular-Rettung
 * „1 Tag“ statt „1 Tagen“ via getQuantityString je Locale).
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
 *
 * W23 Lane U5 (Retry-Backoff sichtbar): im RECONNECTING-Zustand rechnet das
 * Banner das Backoff-Fenster EHRLICH mit — Haupt-Text (Countdown „Neuer
 * Versuch in N s“, je-Sekunden-Tick aus [RelayClient.retryCountdownSec])
 * plus dismissbarer Handlungs-Hint („App komplett schließen + öffnen …“,
 * Debug-Dok-Sofort-Trick). Ohne Countdown-Fenster (Termux-Revival-Pfad)
 * bleibt der ehrliche generische Hinweis — niemals ein auswendiger Rest.
 */
@Composable
private fun DashboardStateBanner(
    state: BridgeStatus.ConnectionState,
    serverUrl: String?,
    retryCountdownSec: Long? = null,
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
    // W23 Lane U5: Handlungs-Hint dismissable — einmal weggeklickt bleibt er
    // weg für DIESEN Backoff-Stau; ein neuer Backoff-Zyklus (Fenster nach
    // Räumen wieder da / Zustand verlässt RECONNECTING) dismissiert zurück.
    var retryHintDismissed by rememberSaveable { mutableStateOf(false) }
    val inReconnect = state is BridgeStatus.ConnectionState.RECONNECTING
    val hasCountdownWindow = retryCountdownSec != null
    LaunchedEffect(inReconnect, hasCountdownWindow) {
        if (!(inReconnect && hasCountdownWindow)) retryHintDismissed = false
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
        is BridgeStatus.ConnectionState.RECONNECTING -> reconnectingVisual(state.ursache, retryCountdownSec)
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
    // W23 Lane U5: Handlungs-Hint nur im RECONNECTING-Fall als Detail-Zeile.
    // Jäger-Hoch-Fix (W23-Fix2): dismissed-State KONSUMIEREN — einmal
    // weggeklickt bleibt die Detail-Zeile für DIESEN Backoff-Stau weg
    // (LaunchedEffect oben resettet je neuem Countdown-Fenster).
    val retryDetailVisible = visual.detail != null && !retryHintDismissed
    StateBanner(
        tint = visual.tint,
        title = visual.title,
        text = visual.text,
        busy = visual.busy,
        badge = visual.badge,
        detail = if (retryDetailVisible) visual.detail else null,
        detailDismiss = if (retryDetailVisible) ({ retryHintDismissed = true }) else null,
    )
}

/** Sichtwerte des Status-Banners je Zustand. */
private data class BannerVisual(
    val tint: Color,
    val title: String,
    val text: String,
    val busy: Boolean,
    val badge: Pair<String, BadgeState>,
    /** W23 Lane U5: optionale Detail-Zeile (Handlungs-Hint, dismissbar). */
    val detail: String? = null,
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
 * Wiederverbindungs-Schleife: Spinner + Countdown — W23 Lane U5 (Besitzer-Punkt
 * „MINUTEN ohne jede Anzeige“): das Backoff-Fenster des Relays rechnet der
 * Banner MIT. Mit Fenster = dynamischer Rest („Neuer Versuch in 42 s“ bzw.
 * „1 Min 30 s“, Sekunden-Tick, generisch — KEINE Agenten-/Servernamen);
 * ohne Fenster (Termux-Revival-Pfad, kein geplantes Backoff) bleibt der
 * bisherige Ursachen-Hinweis — der Banner lügt nie. Der Handlungs-Hint
 * (Sofort-Trick) kommt als dismissbare Detail-Zeile.
 */
@Composable
private fun reconnectingVisual(ursache: String, countdownSec: Long?): BannerVisual {
    // Wichtig gegen Flackern: Chip/Titel/Badge/Spinner bleiben je Tick
    // stabil, nur der Text-Inhalt (Restangabe) wechselt — Rest in Kurzform
    // bleibt einzeilig (kein Höhen-Sprung im Banner).
    val secUnit = stringResource(R.string.retry_unit_seconds)
    val minUnit = stringResource(R.string.retry_unit_minutes)
    val hint = when {
        // Eigene RelayClient-Meldung (Termux-Revival-Auslösung).
        ursache.startsWith("Stack nicht erreichbar") ->
            stringResource(R.string.hint_stack_unreachable)
        else ->
            stringResource(R.string.hint_retry_generic)
    }
    // W23 Lane U5: Countdown-Ehrlichkeit — Restangabe nur mit echtem Fenster
    // (Rest aufgerundet, nie negativ; Format JVM-getestet in
    // RetryCountdownTest, Einheiten je Locale, Platzhalter %1$s).
    val text = if (countdownSec != null) {
        stringResource(
            R.string.reconnect_retry_in,
            RetryCountdown.formatDuration(countdownSec, secUnit, minUnit),
        )
    } else {
        hint
    }
    return BannerVisual(
        tint = FlipsiColors.SignalAmber,
        title = stringResource(R.string.banner_connecting_title),
        text = text,
        busy = true,
        badge = stringResource(R.string.banner_connecting_badge) to BadgeState.WARN,
        detail = stringResource(R.string.reconnect_retry_hint),
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
