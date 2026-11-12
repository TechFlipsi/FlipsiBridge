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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.BridgeStatus
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.CountBadge
import com.hermesandroid.bridge.ui.components.GroupIcons
import com.hermesandroid.bridge.ui.components.ResourceIcon
import com.hermesandroid.bridge.ui.components.SectionTitle
import com.hermesandroid.bridge.ui.components.StateBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * W15 (Sir-Fixliste 9): Startseite = STATUS-DASHBOARD.
 *
 * VORNE steht neu, was Sir am Start sehen will: die Agenten-Slots mit
 * Live-Status (Online/Offline/Beschäftigt), was der Agent gerade tut
 * (chat.activity-Working-Zustand mit mm:ss-Ticker) und seine letzte
 * Aktivität (letzter chat.event-Text + relative Zeit). Die alten Start-
 * Elemente wandern NICHT weg — sie leben weiter unten als „Verbindung“-
 * Karte (URL-Feld + Verbinden/Trennen, gleiche SSOT wie das Einstellungs-
 * Feld) plus Fähigkeiten-Kompakt-Ansicht und LAN-Fußzeile.
 *
 * Daten-Verband: ALLE Zustände kommen aus vorhandenen RelayClient-Flows
 * (relay.state → [RelayClient.agents], chat.activity →
 * [RelayClient.agentWorking], chat.event → [RelayClient.chatEvents],
 * E2E-Sitzungen → [RelayClient.e2eSessions]) — KEIN neuer Endpunkt, KEIN
 * neuer Store; die Sichtregeln rechnen in [DashboardRules] (JVM-getestet).
 *
 * Live-Ticker: solange ein Agent frisch „beschäftigt“ ist, tickt die Uhr
 * sekündlich (mm:ss wie im Conversation-Kopf), sonst reicht ein 30-s-Raster
 * für die relativen Zeiten — die Schleife endet mit der Komposition.
 */
@Composable
fun DashboardScreen(
    padding: PaddingValues,
    serverUrlText: String,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    enabledCapabilityCount: Int,
    totalCapabilityCount: Int,
    onOpenAgentChat: (String) -> Unit,
    onSnack: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // ── Live-Daten (vorhandene RelayClient-Flows) ───────────────────────────
    val agents by RelayClient.agents.collectAsState()
    val agentWorking by RelayClient.agentWorking.collectAsState()
    val chatEvents by RelayClient.chatEvents.collectAsState()
    val e2eSessions by RelayClient.e2eSessions.collectAsState()
    // Verbindungs-Zustand für das Statusbanner (eine Warn-Instanz, oben).
    val connectionState by BridgeStatus.state.collectAsState()
    val connectionServerUrl by BridgeStatus.serverUrl.collectAsState()

    // ── Live-Uhr: 1 s bei Beschäftigung, sonst 30 s (relative Zeiten) ───────
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val anyBusy = agents.any { entry ->
        val w = agentWorking[entry.agentId]
        w != null && w.working && !DashboardRules.workingTimedOut(w.startedAt, nowMs)
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
            SectionTitle(
                stringResource(R.string.chat_list_title),
                sub = DashboardRules.summaryLine(
                    total = agents.size,
                    online = agents.count { it.online },
                    busy = agents.count { entry ->
                        val w = agentWorking[entry.agentId]
                        w != null && w.working && !DashboardRules.workingTimedOut(w.startedAt, nowMs)
                    },
                ).ifEmpty { stringResource(R.string.dash_agents_sub) },
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
                        lastEvent = chatEvents.lastOrNull { it.agentId == entry.agentId },
                        e2eActive = e2eSessions.containsKey(entry.agentId),
                        nowMs = nowMs,
                        onOpen = { onOpenAgentChat(entry.agentId) },
                    )
                }
            }
        }

        // ── Verbindung: die alten Start-Elemente als eigene Karte ──────────
        item(key = "verbindung") {
            VerbindungCard(
                serverUrlText = serverUrlText,
                onUrlChange = onUrlChange,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onBlankUrl = { onSnack(context.getString(R.string.snack_url_missing)) },
            )
        }

        // ── Fähigkeiten kompakt (reine Ansicht — Schalter im Agenten-Tab) ──
        item(key = "masterCompact") {
            MasterCompactCard(
                enabledCount = enabledCapabilityCount,
                totalCount = totalCapabilityCount,
            )
        }

        // ── Fußzeile: lokale LAN-Steuerung ─────────────────────────────────
        item(key = "footer") { FooterCard() }
    }
}

// ── Agenten-Slot ─────────────────────────────────────────────────────────────

/**
 * EIN Agent-Slot: Avatar mit Anfangsbuchstaben (Zustandsfarbe), Name +
 * E2E-Kleppel, Live-Arbeitszeile („arbeitet … 2:14“ mit Spinner), letzte
 * Aktivität (chat.event-Text, 1 Zeile) mit relativer Zeit und die Status-
 * Pille rechts. Tap öffnet die Conversation dieses Agenten (Chat-Tab).
 */
@Composable
private fun AgentSlotCard(
    agent: RelayClient.AgentEntry,
    working: RelayClient.AgentActivity?,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = FlipsiColors.Ember,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            stringResource(
                                R.string.dash_working,
                                DashboardRules.workingElapsedLabel(working?.startedAt ?: 0L, nowMs),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.Ember,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                    if (lastEvent != null)
                        stringResource(R.string.dash_last_active, DashboardRules.relativeTime(lastEvent.ts, nowMs))
                    else
                        stringResource(R.string.dash_no_activity),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlipsiColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(label = DashboardRules.statusLabel(status), tint = tint, busy = status == DashboardRules.AgentStatus.BUSY)
        }
    }
}

/** Status-Pille rechts am Slot: Text + Farbe, nie nur Farbe (A11y). */
@Composable
private fun StatusPill(label: String, tint: Color, busy: Boolean) {
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            Box(
                Modifier.size(6.dp).background(tint, CircleShape),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Box(Modifier.size(6.dp).background(tint, CircleShape))
        }
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

// ── Verbindung (alte Start-Elemente, gleiche SSOT wie das Settings-Feld) ─────

/**
 * W15: die frühere Home-ConnectionCard als „Verbindung“-Karte — Statuspunkt
 * + Zustandswort, letzte Relay-Meldung (nur im getrennten Zustand), URL-Feld
 * (gleicher serverUrlText-SSOT wie die Einstellungen) und Verbinden/Trennen.
 */
@Composable
private fun VerbindungCard(
    serverUrlText: String,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBlankUrl: () -> Unit,
) {
    val status by BridgeStatus.state.collectAsState()
    val message by BridgeStatus.message.collectAsState()
    // Zustands-Matching auf ConnectionState (exhaustive, ohne else-Grep).
    val (dotColor, stateLabel) = when (status) {
        is BridgeStatus.ConnectionState.CONNECTED ->
            FlipsiColors.SignalGreen to stringResource(R.string.home_state_connected)
        is BridgeStatus.ConnectionState.PAIRING_BROKEN ->
            FlipsiColors.SignalRed to stringResource(R.string.home_state_pairing_needed)
        is BridgeStatus.ConnectionState.CONNECTING ->
            FlipsiColors.SignalAmber to stringResource(R.string.home_state_connecting)
        is BridgeStatus.ConnectionState.RECONNECTING ->
            FlipsiColors.SignalAmber to stringResource(R.string.home_state_connecting)
        is BridgeStatus.ConnectionState.FAILED ->
            FlipsiColors.SignalAmber to stringResource(R.string.home_state_disconnected)
        is BridgeStatus.ConnectionState.IDLE ->
            FlipsiColors.SignalAmber to stringResource(R.string.home_state_disconnected)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                Text(
                    stateLabel,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            // Detail-Zeile (letzte Relay-Meldung) — OHNE URL-Wiederholung:
            // die URL lebt NUR im Eingabefeld. Nur im getrennten Zustand.
            if (status !is BridgeStatus.ConnectionState.CONNECTED) {
                Text(
                    message.ifBlank { stringResource(R.string.home_state_fallback) },
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = FlipsiColors.LineStrong)
            OutlinedTextField(
                value = serverUrlText,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.conn_url_label_short)) },
                // Bewusst generisch — keine private Domain im Quellcode (GitHub-Hygiene)!
                placeholder = { Text(stringResource(R.string.conn_url_placeholder)) },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (serverUrlText.trim().isBlank()) onBlankUrl() else onConnect()
                    },
                    enabled = status !is BridgeStatus.ConnectionState.CONNECTED,
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlipsiColors.Ember,
                        contentColor = FlipsiColors.OnEmber,
                        disabledContainerColor = FlipsiColors.PanelElev,
                        disabledContentColor = FlipsiColors.TextSecondary,
                    ),
                ) { Text(stringResource(R.string.conn_connect)) }
                if (status is BridgeStatus.ConnectionState.CONNECTED) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.height(48.dp),
                        // Destruktive Aktion = kenntlich dezent rot
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.SignalRedText),
                    ) { Text(stringResource(R.string.conn_disconnect), fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/** Master-Status kompakt: reine Ansicht — Schalter leben im Agenten-Tab. */
@Composable
private fun MasterCompactCard(enabledCount: Int, totalCount: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResourceIcon(GroupIcons.master, tint = FlipsiColors.TextSecondary, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.caps_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                CountBadge(enabled = enabledCount, total = totalCount, invasive = false)
            }
            Text(
                stringResource(R.string.caps_compact_desc, enabledCount, totalCount),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Fußzeile: lokale LAN-Steuerung (USB/LAN) — Regelbetrieb läuft übers Relay. */
@Composable
private fun FooterCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ip = remember { localIpOf(context) }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.footer_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.footer_url, ip),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.footer_desc),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun localIpOf(context: android.content.Context): String {
    return runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "—"
    }.getOrNull() ?: "—"
}

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
