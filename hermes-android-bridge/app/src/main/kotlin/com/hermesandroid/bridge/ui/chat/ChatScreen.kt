package com.hermesandroid.bridge.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.chat.ChatHistoryStore
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat-Screen (Route 2): Agenten-Liste mit Online-Punkt und Nachrichten-
 * Vorschau; je Agent eine eigene Conversation (Verlauf + Eingabe-Leiste +
 * Senden-Button).
 *
 * PH2-MERGE: REAL-Daten aus dem Relay — Agenten-Liste aus dem relay.state-
 * Hallo-Frame (RelayClient.agents), eingehende Agenten-Antworten aus den
 * chat.event-Frames (RelayClient.chatEvents), Senden via POST /chat
 * (RelayClient.sendChat). Liefert das Relay keine Agenten (leere Liste),
 * bleibt der Demo-Fallback mit den Beispiel-Einträgen (siehe sampleAgents).
 */
@Composable
fun ChatScreen(padding: PaddingValues) {
    // PH2-MERGE: echte Agenten-Liste vom Relay; leer = Demo-Fallback.
    val relayAgents by RelayClient.agents.collectAsState()
    val chatEvents by RelayClient.chatEvents.collectAsState()
    // PH2-T10: Zustell-/Lese-Status je msg_id + Arbeitsstatus je Agent.
    val chatStatus by RelayClient.chatStatus.collectAsState()
    val agentWorking by RelayClient.agentWorking.collectAsState()

    val demoAgents = remember { sampleAgents() }
    val agents = relayAgents.map { entry ->
        ChatAgent(
            id = entry.agentId.hashCode(),
            name = entry.displayName,
            online = entry.online,
            agentId = entry.agentId,
        )
    }.ifEmpty { demoAgents }

    // PH2-T10: Zuordnung eigene lokale Nachricht → Broker-msg_id (die App
    // bekommt sie aus der POST-/chat-Antwort).
    // PH2-FIX (Jäger B8): rememberSaveable mit Saver — vorher downgradeten
    // die Zustell-Chips nach einer Rotation auf '✓ Gesendet', obwohl der
    // Broker schon delivered/read meldete.
    var msgIdByLocalId by rememberSaveable(stateSaver = MsgIdByLocalIdSaver) {
        mutableStateOf(mapOf<Long, String>())
    }

    // PH2-FIX (Jäger B7): fehlgeschlagene lokale Sends je local_id merken —
    // der Chip zeigt '✕ Fehlgeschlagen' statt des gelogenen '✓ Gesendet'.
    // Bewusst AUCH saveable: nach einer Rotation wäre der Chip sonst wieder
    // grün, obwohl der Send endgültig gescheitert ist.
    var failedLocalIds by rememberSaveable(stateSaver = FailedLocalIdsSaver) {
        mutableStateOf(setOf<Long>())
    }

    // PH2-FIX (Jäger B6): Send-Fehler je Agent (agentId → Meldung) — das
    // Banner zeigt nur den Fehler des AKTIVEN Agenten; onBack/Dismiss cleart
    // genau den Eintrag des Agenten (kein Leak in die nächste Conversation).
    var sendErrors by remember { mutableStateOf(mapOf<String, String>()) }

    // Eingehende chat.event-Frames → Agenten-Antworten (KI-Badge via fromAgent).
    // IDs liegen oberhalb des lokalen Zählers, damit LazyColumn-Keys kollisionsfrei bleiben.
    val incomingMessages = chatEvents.mapIndexed { index, event ->
        ChatMessage(
            id = INCOMING_ID_BASE + index,
            agentId = event.agentId.hashCode(),
            fromAgent = true,
            text = event.text,
            time = formatTimestamp(event.ts),
        )
    }

    var selectedAgentId by rememberSaveable { mutableStateOf(-1) }
    var messages by rememberSaveable(stateSaver = ChatMessageListSaver) { mutableStateOf(sampleMessages()) }
    var inputText by rememberSaveable { mutableStateOf("") }

    // SIRS-03 (Volltest 03.11.2026): Der FP-Abgleich wird vom Agenten
    // beantwortet, aber ohne chat.read blieb der Chip der eigenen Nachricht
    // bei „✓✓ Zugestellt“ — semantisch irreführend (eine erhaltene Antwort
    // BEWEIST, dass die Nachricht gelesen wurde). Merke je Agent die wartende
    // FP-Nachricht (local_id + Sende-Zeit); kommt danach eine Antwort des
    // Agenten, nimmt der Chip den Antwort-Zustand („read“) an. Transient
    // (kein saveable): nach einer Rotation greift der Agenten-seitige
    // Read-Receipt (gleicher Fix-Cluster, adapter.py) über den Broker-Status.
    var fpCheckPending by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var fpAnsweredLocalIds by remember { mutableStateOf(setOf<Long>()) }

    // SIRS-03: Kommt nach dem FP-Abgleich eine Antwort des Agenten (ts ≥
    // Sende-Zeit), nimmt der Chip der FP-Nachricht den Antwort-Zustand an.
    LaunchedEffect(chatEvents) {
        if (fpCheckPending.isEmpty()) return@LaunchedEffect
        val answeredAgents = mutableListOf<String>()
        for ((agentId, pending) in fpCheckPending) {
            val (localId, sentAtMs) = pending
            if (chatEvents.any { it.agentId == agentId && it.ts >= sentAtMs }) {
                fpAnsweredLocalIds = fpAnsweredLocalIds + localId
                answeredAgents.add(agentId)
            }
        }
        if (answeredAgents.isNotEmpty()) {
            fpCheckPending = fpCheckPending - answeredAgents
        }
    }

    // SIRS-02 (P2, Volltest 03.11.2026): Verlauf vom Disk-Store laden — der
    // Chat-Verlauf überlebt App-Restart/force-stop (vorher: „Noch keine
    // Nachrichten“ nach dem Neustart, Bubbles nur im rememberSaveable-Bundle).
    // Genau EINMAL je Prozess: der saveable Flag übersteht die Rotation — ein
    // erneutes Laden würde den In-Session-Verlauf (msgIdByLocalId-Chips,
    // frische Bubbles) durch den Disk-Stand ersetzen. Sichtbar erst hinter dem
    // AppLock-Gate (ChatScreen rendert nur nach Unlock).
    val historyContext = LocalContext.current
    var historyRestored by rememberSaveable { mutableStateOf(false) }
    var restoredStatusById by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(historyRestored) {
        if (historyRestored) return@LaunchedEffect
        val entries = withContext(Dispatchers.IO) {
            ChatHistoryStore.loadAll(ChatHistoryStore.root(historyContext.applicationContext))
        }
        if (entries.isNotEmpty()) {
            var nextId = RESTORED_ID_BASE
            val restored = ArrayList<ChatMessage>(entries.size)
            val statusById = HashMap<Long, String>()
            for (entry in entries) {
                val id = nextId++
                restored.add(
                    ChatMessage(
                        id = id,
                        agentId = entry.agentId.hashCode(),
                        fromAgent = entry.fromAgent,
                        text = entry.text,
                        time = entry.time,
                    ),
                )
                entry.status?.let { statusById[id] = it }
            }
            messages = restored
            restoredStatusById = statusById
        }
        historyRestored = true
    }

    // PH-3 Welle 2: rot-Banner (Fingerprint-ABWEICHUNG / Session-Reset).
    val e2eBanner by RelayClient.e2eBanner.collectAsState()

    /**
     * Lokale Nachricht anhängen + Relay-Versand (von Eingabe und FP-Abgleich
     * gemeinsam genutzt — identischer Sendeweg, identische Fehlerbehandlung).
     * Gibt die local_id der angehängten Nachricht zurück (SIRS-03: die App
     * merkt sich die FP-Abgleich-Nachricht, um ihren Chip mit der Antwort zu
     * heilen).
     */
    fun submitText(agent: ChatAgent, text: String): Long {
        val nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        messages = messages + ChatMessage(
            id = nextId,
            agentId = agent.id,
            fromAgent = false,
            text = text,
            time = nowTime(),
        )
        if (agent.agentId.isBlank()) {
            // Demo-Fallback-Eintrag: kein echter Agent hinterlegt —
            // bewusst KEIN Relay-Versand und KEINE Fake-Antwort.
        } else {
            // PH2-MERGE: echter Versand über den Relay-Broker; bei aktiver
            // E2E-Session verschlüsselt RelayClient.sendChat selbst (chat.send.enc).
            RelayClient.sendChat(agent.agentId, text) { ok, error, msgId ->
                if (!ok) {
                    failedLocalIds = failedLocalIds + nextId
                    sendErrors = sendErrors + (
                        agent.agentId to
                            "Chat: Senden fehlgeschlagen — ${error ?: "unbekannter Fehler"}"
                        )
                } else if (msgId != null) {
                    msgIdByLocalId = msgIdByLocalId + (nextId to msgId)
                }
            }
        }
        return nextId
    }

    // Conversation schließen: zurück zur Liste, Eingabe leeren — und PH2-FIX
    // (Jäger B6): das Fehler-Banner des GESCHLOSSENEN Agenten clearen, damit
    // es nicht in die nächstgeöffnete Conversation (oder zurück in die Liste)
    // leakt. Nur echte Agenten (nicht-blank agentId) haben einen Banner-Eintrag.
    fun closeConversation() {
        val open = agents.firstOrNull { it.id == selectedAgentId }
        if (open != null && open.agentId.isNotBlank()) {
            sendErrors = sendErrors - open.agentId
        }
        selectedAgentId = -1
        inputText = ""
    }

    // Back in der Conversation: erst zurück zur Agenten-Liste; die Route-Back-
    // Behandlung (Route → Home, BridgeScreen) greift erst danach, weil dieser
    // neuere BackHandler Vorrang hat, solange er enabled ist.
    BackHandler(enabled = selectedAgentId >= 0) {
        closeConversation()
    }

    // Vorschau-Zeile und Conversation sehen lokale + eingehende Nachrichten.
    val allMessages = messages + incomingMessages

    val agent = agents.firstOrNull { it.id == selectedAgentId }
    if (agent == null) {
        AgentListBody(
            padding = padding,
            agents = agents,
            messages = allMessages,
            onOpen = { id ->
                selectedAgentId = id
                // Eingabe beginnt je Conversation neu (Entwurf je Agent
                // bewusst nicht — ein Feld reicht, State bleibt minimal).
                inputText = ""
            },
        )
    } else {
        ConversationBody(
            padding = padding,
            agent = agent,
            conversation = allMessages.filter { it.agentId == agent.id },
            working = agentWorking[agent.agentId],
            // PH2-FIX (Jäger B6): nur der Fehler des AKTIVEN Agenten — Demo-
            // Einträge (blank agentId) haben nie einen Banner-Eintrag.
            errorBanner = agent.agentId.takeIf { it.isNotBlank() }?.let { sendErrors[it] },
            onDismissError = { sendErrors = sendErrors - agent.agentId },
            statusOfMessage = { message ->
                // PH2-FIX (Jäger B7): endgültig fehlgeschlagener Send →
                // 'failed'-Status (✕-Chip) statt des gelogenen '✓ Gesendet'.
                if (message.id in failedLocalIds) {
                    "failed"
                } else if (message.id in fpAnsweredLocalIds) {
                    // SIRS-03: Antwort auf den FP-Abgleich ist der Beweis, dass
                    // die Nachricht gelesen wurde → Chip nimmt den Antwort-
                    // Zustand an (statt bei „✓✓ Zugestellt“ zu lügen).
                    "read"
                } else {
                    // PH2-T10: eigene Nachricht → msg_id → Broker-Status.
                    // SIRS-02: restaurierte Nachrichten haben KEINE msg_id-
                    // Zuordnung mehr (Broker-Map ist pro Session) → der
                    // persistierte Status-Snapshot ist der ehrliche Fallback.
                    msgIdByLocalId[message.id]?.let { msgId -> chatStatus[msgId] }
                        ?: restoredStatusById[message.id]
                }
            },
            elapsedMsByMessageId = { messageId ->
                // Antwort-Bubble 'Antwort nach X s': elapsed_ms des zugehörigen
                // chat.event (Index-stabil über INCOMING_ID_BASE-Abstand).
                val index = (messageId - INCOMING_ID_BASE).toInt()
                chatEvents.getOrNull(index)?.elapsedMs
            },
            inputText = inputText,
            onInputChange = { inputText = it },
            onSend = {
                val text = inputText.trim()
                if (text.isNotEmpty()) {
                    inputText = ""
                    submitText(agent, text)
                }
            },
            // PH-3 Welle 2 (Sirs Revision (iii)): FP-Abgleich-Button — sendet
            // EXAKT 'FP-ABGLEICH: <64 Hex des session_fp>' als normale Chat-
            // Nachricht (verschlüsselt wie jede andere); der Agent vergleicht
            // gegen SEINEN session_fp und antwortet automatisch ✓/✗.
            onFpCheck = {
                val fp = RelayClient.sessionFingerprint(agent.agentId)
                if (fp == null) {
                    sendErrors = sendErrors + (
                        agent.agentId to
                            "Chat: keine aktive E2E-Sitzung — Fingerprint-Abgleich erst nach dem Verbinden möglich"
                        )
                } else {
                    val fpLocalId = submitText(agent, "FP-ABGLEICH: $fp")
                    // SIRS-03: FP-Nachricht als wartend merken — ihre Antwort
                    // heilt den Chip auf „✓✓ Gelesen“.
                    fpCheckPending = fpCheckPending +
                        (agent.agentId to (fpLocalId to System.currentTimeMillis()))
                }
            },
            // PH-3 Welle 2: rot-Banner bei Abweichung/Reset unter dem Kopf.
            e2eBanner = e2eBanner,
            onDismissE2eBanner = { RelayClient.clearE2eBanner() },
            onBack = {
                // PH2-FIX (Jäger B6): Banner des Agenten beim Verlassen clearen.
                closeConversation()
            },
        )
    }
}

// ── Agenten-Liste ────────────────────────────────────────────────────────────

/** Liste der gekoppelten Agenten mit Online-Punkt und Vorschau-Zeile. */
@Composable
private fun AgentListBody(
    padding: PaddingValues,
    agents: List<ChatAgent>,
    messages: List<ChatMessage>,
    onOpen: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            SectionTitle(
                "Agenten",
                sub = "Direkt-Chat mit den gekoppelten Agenten — live über das Relay.",
            )
        }
        items(agents, key = { it.id }) { agent ->
            // Vorschau-Zeile = letzte Nachricht dieses Agenten (oder Hinweis).
            val preview = messages.lastOrNull { it.agentId == agent.id }?.text
                ?: "Noch keine Nachrichten"
            AgentRow(
                agent = agent,
                preview = preview,
                onClick = { onOpen(agent.id) },
            )
        }
    }
}

// ── Conversation (Verlauf + Eingabe) ────────────────────────────────────────

/** Eine Conversation: Kopf, scrollender Verlauf und Eingabe-Leiste unten. */
@Composable
private fun ConversationBody(
    padding: PaddingValues,
    agent: ChatAgent,
    conversation: List<ChatMessage>,
    working: RelayClient.AgentActivity?,
    errorBanner: String?,
    onDismissError: () -> Unit,
    statusOfMessage: (ChatMessage) -> String?,
    elapsedMsByMessageId: (Long) -> Long?,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onFpCheck: () -> Unit,
    e2eBanner: String?,
    onDismissE2eBanner: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Auf die neueste Nachricht scrollen (unten = neueste).
    LaunchedEffect(conversation.size) {
        if (conversation.isNotEmpty()) {
            listState.animateScrollToItem(conversation.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        ConversationHeader(agent = agent, working = working, onBack = onBack, onFpCheck = onFpCheck)
        HorizontalDivider(color = FlipsiColors.LineStrong)
        // PH2-FIX (E2E P1): dismissbare Fehler-Zeile direkt unter dem Kopf —
        // der Send-Fehler ist auf der Chat-Route sichtbar, ohne die Route zu
        // verlassen (BridgeStatus rendert hier nicht).
        SendErrorBanner(message = errorBanner, onDismiss = onDismissError)
        // PH-3 Welle 2: rot-Banner bei Fingerprint-ABWEICHUNG / Session-Reset
        // (möglicher Man-in-the-Middle) — gleiche rot-Sprache wie der Send-Fehler.
        SendErrorBanner(message = e2eBanner, onDismiss = onDismissE2eBanner)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        ) {
            if (conversation.isEmpty()) {
                item {
                    Text(
                        "Noch keine Nachrichten — schreibe die erste Nachricht an ${agent.name}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(conversation, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    // PH2-T10: Zustell-/Lese-Chips je EIGENER Nachricht.
                    status = if (!message.fromAgent) statusOfMessage(message) else null,
                    // PH2-FIX (Jäger B7): Demo-Eintrag (blank agentId) → KEIN
                    // Chip — '✓ Gesendet' wäre gelogen, es geht nichts raus.
                    showStatusChip = agent.agentId.isNotBlank(),
                    // PH2-T11: 'Antwort nach X s' je Agenten-Antwort.
                    replyAfterMs = if (message.fromAgent) elapsedMsByMessageId(message.id) else null,
                )
            }
        }
        HorizontalDivider(color = FlipsiColors.LineStrong)
        ChatInputBar(
            value = inputText,
            onValueChange = onInputChange,
            onSend = onSend,
        )
    }
}

/**
 * Kopf der Conversation: zurück zur Liste + Agent-Name + Online-Status.
 * PH2-T10 (F2-S3): arbeitet der Agent gerade (chat.activity working=true),
 * zeigt der Kopf einen kleinen Spinner + 'arbeitet … (mm:ss)' — die Zeit
 * läuft sekündlich ab started_at (Empfang beim Agenten) hoch.
 */
@Composable
private fun ConversationHeader(
    agent: ChatAgent,
    working: RelayClient.AgentActivity?,
    onBack: () -> Unit,
    onFpCheck: () -> Unit,
) {
    // mm:ss seit started_at — je Sekunde neu gerechnet (nur bei working).
    var elapsedSec by remember(working) { mutableStateOf(0L) }
    LaunchedEffect(working) {
        if (working != null && working.working) {
            while (true) {
                elapsedSec = ((System.currentTimeMillis() - working.startedAt) / 1000L)
                    .coerceAtLeast(0L)
                delay(1_000L)
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Zurück zur Agenten-Liste",
                tint = FlipsiColors.TextPrimary,
            )
        }
        Text(
            agent.name,
            style = MaterialTheme.typography.titleMedium,
            color = FlipsiColors.TextPrimary,
        )
        Spacer(Modifier.width(10.dp))
        // Status-Zeichen klein neben dem Namen — Punkt + Text, nie nur Farbe (A11y).
        OnlineDot(online = agent.online)
        Spacer(Modifier.width(6.dp))
        Text(
            if (agent.online) "Online" else "Offline",
            style = MaterialTheme.typography.labelSmall,
            color = FlipsiColors.TextSecondary,
        )
        // PH2-T10 (F2-S3): Arbeitsstatus live im Kopf (Spinner + mm:ss).
        if (working != null && working.working) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = FlipsiColors.Ember,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "arbeitet … (${formatElapsed(elapsedSec)})",
                style = MaterialTheme.typography.labelSmall,
                color = FlipsiColors.Ember,
            )
        }
        // PH-3 Welle 2 (Sirs Revision (iii)): FP-Abgleich-Button im Chat —
        // sendet 'FP-ABGLEICH: <hex>' als normale (verschlüsselte) Nachricht;
        // der Agent antwortet automatisch ✓ gleich / ✗ ABWEICHUNG (rot-Banner).
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onFpCheck,
            modifier = Modifier.padding(end = 4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp,
            ),
        ) {
            Text("FP-Abgleich", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Sekunden als 'mm:ss' (z. B. 83 → '01:23'); >1 h bleibt stundenfrei ehrlich lang. */
private fun formatElapsed(totalSeconds: Long): String {
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

/**
 * PH2-FIX (E2E P1): dismissbare Fehler-Banner-Zeile unter dem Conversation-
 * Kopf. Send-Fehler (429/Netz) aus dem RelayClient-Result waren vorher nur
 * im BridgeStatus-Status — auf der Chat-Route rendert den kein Composable,
 * der Fehler blieb unsichtbar (E2E-Fund e2e-d). Gleiche Farben-Sprache wie
 * der Rest der App: rotes Signal-Paar aus BridgeTheme (Hintergrund abge-
 * dunkelt, Text in der lesestarken Variante — A11y: nie nur Farbe, der
 * Fehlertext selbst bleibt lesbar). null = kein Banner.
 */
@Composable
private fun SendErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) {
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = FlipsiColors.SignalRed.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = FlipsiColors.SignalRedText,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Fehlermeldung schließen",
                tint = FlipsiColors.SignalRedText,
            )
        }
    }
}

// ── Beispiel-Daten — Demo-Fallback für eine leere Relay-Agenten-Liste ─────
// Bewusst generische Demo-Einträge („Agent 1“ …), KEINE privaten Namen.
// PH2-MERGE: echter Fallback, wenn relay.state keine Agenten liefert.

/**
 * Demo-Fallback — die echte Liste kommt vom Relay (relay.state-Hallo-Frame →
 * RelayClient.agents). Bleibt bewusst stehen: leere Agenten-Liste darf die
 * UI nie crashen, sondern zeigt diese generischen Einträge.
 */
private fun sampleAgents() = listOf(
    ChatAgent(id = 1, name = "Agent 1", online = true),
    ChatAgent(id = 2, name = "Agent 2", online = false),
    ChatAgent(id = 3, name = "Agent 3", online = true),
)

/** Beispiel-Verlauf — echte Verläufe kommen mit PH-2-Main. */
private fun sampleMessages() = listOf(
    ChatMessage(
        id = 1L,
        agentId = 1,
        fromAgent = true,
        text = "Beispiel-Antwort des Agenten (KI) — Backend-Anbindung folgt in PH-2-Main.",
        time = "14:02",
    ),
    ChatMessage(
        id = 2L,
        agentId = 1,
        fromAgent = false,
        text = "Beispiel-Eingabe — der Verlauf bleibt lokal gespeichert.",
        time = "14:03",
    ),
    ChatMessage(
        id = 3L,
        agentId = 3,
        fromAgent = true,
        text = "Beispiel-Begrüßung von Agent 3.",
        time = "09:41",
    ),
)

/** Aktuelle Uhrzeit als Anzeige-Zeitstempel „HH:mm“. */
private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())

/**
 * PH2-MERGE: Id-Basis eingehender Relay-Nachrichten — liegt meilenweit über
 * dem lokalen Zähler der gesendeten Nachrichten, damit die LazyColumn-Keys
 * (ChatMessage.id) nie kollidieren.
 */
private const val INCOMING_ID_BASE = 1_000_000_000L

/**
 * SIRS-02: Id-Basis restaurierter (persistierter) Nachrichten — liegt ÜBER
 * INCOMING_ID_BASE, damit (a) LazyColumn-Keys nie mit frischen eingehenden
 * Nachrichten kollidieren und (b) die elapsedMsByMessageId-Index-Rechnung
 * (id − INCOMING_ID_BASE) für restaurierte IDs weit außerhalb liegt → null
 * (keine erfundene „Antwort nach X s“-Zeile nach dem Restart).
 */
private const val RESTORED_ID_BASE = 2_000_000_000L

/** PH2-MERGE: Relay-Zeitstempel (Unix-Millisekunden) als „HH:mm“. */
private fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ts))

/**
 * PH2-FIX (Jäger B8): Saver für die local_id → msg_id-Zuordnung — flache
 * Pair-Liste [localId, msgId, …], damit die Zustell-Chips eine Rotation
 * überleben (vorher: remember → Chips downgradeten auf '✓ Gesendet').
 */
val MsgIdByLocalIdSaver = listSaver<Map<Long, String>, Any>(
    save = { map -> map.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) } },
    restore = { flat ->
        flat.chunked(2).associate { (localId, msgId) -> (localId as Long) to (msgId as String) }
    },
)

/**
 * PH2-FIX (Jäger B7): Saver für die fehlgeschlagenen local_ids — derselbe
 * Rotations-Schutz wie bei MsgIdByLocalIdSaver (sonst zeigt der Chip nach
 * dem Drehen wieder '✓ Gesendet', obwohl der Send scheiterte).
 */
val FailedLocalIdsSaver = listSaver<Set<Long>, Any>(
    save = { ids -> ids.sorted() },
    restore = { flat -> flat.map { it as Long }.toSet() },
)