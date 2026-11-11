package com.hermesandroid.bridge.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.chat.ChatHistoryStore
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.media.MediaProtocol
import com.hermesandroid.bridge.media.VoiceMemoRecorder
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    // MEDIA-T1: empfangene Agenten-Medien + Upload-Fortschritt je media_id.
    val chatMedia by RelayClient.chatMediaEvents.collectAsState()
    val mediaUploads by RelayClient.mediaUploads.collectAsState()
    // MEDIA-T1: Picker-Lese-Scope (URI-Bytes auf IO, UI-Update auf Main).
    val context = LocalContext.current
    val mediaScope = rememberCoroutineScope()

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

    var selectedAgentId by rememberSaveable { mutableStateOf(-1) }
    var messages by rememberSaveable(stateSaver = ChatMessageListSaver) { mutableStateOf(sampleMessages()) }
    var inputText by rememberSaveable { mutableStateOf("") }

    // PH-FIX-CHAT-A (F1/J1): Bereits in `messages` übernommene chatEvents als
    // SCHLÜSSEL (chatId|ts) — NICHT als Positionszähler. Grund (Jäger J1):
    // (a) der 200er-Cap von chatEvents (RelayClient.MAX_CHAT_EVENTS) lässt
    //     die Listengröße bei 200 einfrieren → ein auf `chatEvents.size`
    //     keyender Effekt startet nie wieder → alle weiteren Antworten
    //     erschienen STILL nicht mehr in der UI;
    // (b) ein positionsbasierter Zähler überlebt den Prozess-Tod via
    //     Saved-State und verwirft die ersten N NEUEN Events der nächsten
    //     Session (drop(N) auf die frische, leere Liste).
    //     Schlüssel sind an die Event-Identität gebunden: neue Session =
    //     neue chatId/ts = neue Schlüssel, nichts wird verworfen.
    //     saveable, damit eine Rotation die Events der Session nicht ein
    //     zweites Mal anhängt; Cap = MAX_CONSUMED_EVENT_KEYS (chatEvents
    //     selbst capped bei 200 — ältere Schlüssel können nicht mehr
    //     referenziert werden).
    var consumedEventKeys by rememberSaveable(stateSaver = ConsumedEventKeysSaver) {
        mutableStateOf(listOf<String>())
    }

    // PH-FIX-CHAT-A (F1): msg_id → elapsed_ms der zugehörigen Agenten-Antwort
    // (für 'Antwort nach X s') — wird direkt beim Übernehmen gefüllt, statt
    // über INCOMING_ID_BASE-Index-Basteln zu rechnen. Der 200er-Cap von
    // chatEvents (RelayClient) verschiebt damit keine Zuordnung mehr.
    var elapsedMsByMsgId by rememberSaveable(stateSaver = ElapsedMsByMsgIdSaver) {
        mutableStateOf(mapOf<Long, Long>())
    }

    // PH-FIX-CHAT-A (F1, Restore-Race): true sobald in dieser Session eine
    // eigene Nachricht angehängt wurde — der Disk-Restore darf die Live-
    // Einträge dann nicht mehr komplett ersetzen (sonst fliegt eine frisch
    // gesendete Nachricht aus der Anzeige, während ihr Persist-Versand noch
    // läuft; s. Restore-Merge im LaunchedEffect(historyRestored) unten).
    var liveMessagesPresent by remember { mutableStateOf(false) }

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
    // W4-FIX-JAG2 (C1): auch der Status-Snapshot der restaurierten Einträge
    // ist saveable — der Saver-Verband der anderen Maps (B8/B7/J1). Vorher
    // nur `remember`: die Rotation zerstörte die Map, während
    // `historyRestored` (saveable) TRUE blieb → kein erneuter Disk-Load →
    // die restaurierten Bubbles downgradeten auf den Leer-Chip (✓✓ → ✓),
    // obwohl die Disk-Zwillinge in `messages` (saveable) übrig blieben.
    var restoredStatusById by rememberSaveable(stateSaver = RestoredStatusSaver) {
        mutableStateOf<Map<Long, String>>(emptyMap())
    }
    LaunchedEffect(historyRestored) {
        if (historyRestored) return@LaunchedEffect
        val entries = withContext(Dispatchers.IO) {
            ChatHistoryStore.loadAll(ChatHistoryStore.root(historyContext.applicationContext))
        }
        if (entries.isNotEmpty()) {
            var nextId = RESTORED_ID_BASE
            val restored = ArrayList<ChatMessage>(entries.size)
            val statusById = HashMap<Long, String>()
            // PH-FIX-CHAT-A (F1, Restore-Race): Dedup-Schlüssel des Disk-
            // Standes — Live-Einträge mit gleichem Schlüssel werden beim
            // Merge unten gedroppt (ihr persistiertes Zwilling liegt schon
            // in `restored`), noch NICHT persistierte bleiben stehen.
            val restoredKeys = HashSet<String>(entries.size)
            for (entry in entries) {
                val id = nextId++
                // W4-B (Zitieren): EIGENE Bubbles persistieren den Zitat-Draht-
                // Text (Platte-Wahrheit, s. submitQuoted) — der Restore faltet
                // ihn zurück in reine Antwort + ChatQuote-Snapshot, damit die
                // restaurierte Bubble wie die Live-Bubble rendern (Zitat-Block
                // über der Antwort). Agenten-Bubbles bleiben Echo (nie gefaltet).
                val restoredWire = if (!entry.fromAgent) {
                    ChatQuoteRules.splitWireText(entry.text)
                } else null
                restored.add(
                    ChatMessage(
                        id = id,
                        agentId = entry.agentId.hashCode(),
                        fromAgent = entry.fromAgent,
                        text = restoredWire?.reply ?: entry.text,
                        time = entry.time,
                        // WELLE-13 P11 (Datum-Chip): die Platte trägt nur HH:mm —
                        // restaurierte Einträge bleiben OHNE Tages-Schlüssel
                        // (kein erfundenes Datum; sie bekommen einfach keinen
                        // Chip, Live- und künftige persistierte Einträge schon).
                        // MEDIA-T1: persistierte Media-Verweise (Pfad kann nach
                        // App-Update/Device-Wechsel weg sein → Text-Label bleibt).
                        media = entry.mediaKind?.let { kind ->
                            ChatMediaInfo(
                                mediaId = entry.mediaId ?: "",
                                kind = kind,
                                name = entry.mediaName ?: entry.text,
                                mime = entry.mediaMime ?: "",
                                size = entry.mediaSize ?: 0L,
                                localPath = entry.mediaPath?.takeIf { java.io.File(it).exists() } ?: "",
                            )
                        },
                        quoted = restoredWire?.let { wire ->
                            ChatQuote(
                                fromAgent = wire.fromAgent,
                                // Restoremögliches Maximum: Ursprung + Zitat-Text
                                // (Draht trägt keinen Namen — Label „Du“/„KI“).
                                senderName = "",
                                text = wire.quoteText,
                            )
                        },
                    ),
                )
                restoredKeys.add(
                    ChatTimelineMerge.dedupeKey(entry.agentId.hashCode(), entry.fromAgent, entry.text, entry.time),
                )
                entry.status?.let { statusById[id] = it }
            }
            // PH-FIX-CHAT-A (F1, Restore-Race): Sendet Sir, bevor der IO-Load
            // fertig ist, flog die Nachricht vorher aus der Anzeige (das
            // `messages = restored` ersetzte die komplette Liste). Jetzt:
            // Disk-Stand + die Live-Einträge dieser Session, die noch NICHT
            // auf der Platte liegen (Dedupe über restoredKeys).
            messages = if (liveMessagesPresent) {
                // W4-B (Zitieren): Live-Bubbles mit Antwort-Verweis dedupen
                // über ihren DRAHT-Text (so steht der Zwilling auf der Platte)
                // — im Restore-Race (Persist-Write schafft es in den Load)
                // würden reply-Text vs. Draht-Text sonst Dubletten bauen.
                restored + messages.filter { live ->
                    val liveKey = if (live.quoted != null) {
                        ChatTimelineMerge.dedupeKey(
                            live.agentId, live.fromAgent,
                            ChatQuoteRules.wireText(live.text, live.quoted),
                            live.time,
                        )
                    } else {
                        ChatTimelineMerge.dedupeKey(live.agentId, live.fromAgent, live.text, live.time)
                    }
                    liveKey !in restoredKeys
                }
            } else {
                restored
            }
            restoredStatusById = statusById
        }
        historyRestored = true
    }

    // PH-FIX-CHAT-A (F1, Sortierung): Agenten-Antworten beim EINTREFFEN in
    // dieselbe Verlaufsliste übernehmen. Die alte Composition-Konkatenation
    // (messages + incomingMessages) hängt jede eigene Nachricht ÜBER alle
    // bis dahin empfangenen Agenten-Antworten (Live-Anzeige falsch sortiert;
    // nach Restart korrekt, weil der Disk-Store nach seq sortiert). Der
    // Relay liefert WS-Frames geordnet → Ankunfts-Reihenfolge = chronologisch.
    // Bewusst NACH dem Disk-Restore (historyRestored-Gate): Frames, die vor
    // dem Restore empfangen wurden, liegen schon persistiert in der Liste —
    // ChatTimelineMerge.freshUnseen filtert diese Dubletten heraus.
    // JÄGER-J1: Key ist die LISTE selbst (Identität ändert sich mit JEDEM
    // Frame — auch wenn die Größe am 200er-Cap eingefroren bleibt), plus
    // historyRestored. Konsumiert wird per Event-Schlüssel (chatId|ts),
    // NICHT per Positions-Zähler — der Cap verschiebt Positionen, und ein
    // stale Zähler nach Prozess-Tod (Saved-State) verwürfe sonst die ersten
    // N NEUEN Events der nächsten Session.
    LaunchedEffect(chatEvents, historyRestored) {
        if (!historyRestored) return@LaunchedEffect
        val consumed = consumedEventKeys.toHashSet()
        val fresh = chatEvents.filterNot { event ->
            ChatTimelineMerge.eventKey(event.chatId, event.ts) in consumed
        }
        if (fresh.isEmpty()) return@LaunchedEffect
        // Restore-Dedupe NUR gegen die restaurierten Disk-Zwillinge
        // (id ≥ RESTORED_ID_BASE): Frames von vor dem Restore. Live
        // übernommene Nachrichten sind bereits über consumedEventKeys
        // abgedeckt — ein legitimer Doppeltext (gleiche Minute) bleibt so
        // erhalten (frühere dedupe-gegen-ALLES-Variante hätte ihn gefressen).
        val restoredTwins = messages.filter { it.id >= RESTORED_ID_BASE }
        val unseen = ChatTimelineMerge.freshUnseen(
            restoredTwins,
            fresh,
            agentIdOf = { it.agentId.hashCode() },
            textOf = { it.text },
            timeOf = { formatTimestamp(it.ts) },
        )
        if (unseen.isNotEmpty()) {
            var nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
            val appended = unseen.map { event ->
                ChatMessage(
                    id = nextId++,
                    agentId = event.agentId.hashCode(),
                    fromAgent = true,
                    text = event.text,
                    time = formatTimestamp(event.ts),
                    // WELLE-13 P11 (Datum-Chip): Tages-Schlüssel der Antwort.
                    date = ChatDateRules.dayKey(event.ts),
                )
            }
            messages = messages + appended
            // WELLE-13 P9 (Vorlesen): je Agent-Pref vorlesen_<agentId> —
            // angeschaltet = jede frisch übernommene Agenten-Antwort wird vom
            // GERÄT vorgelesen (Android-TTS-Singleton, Queue statt Überlappung). Der Prefs-Stand
            // wird bewusst ZUR LAUFZEIT gelesen (nicht gecached): der Regler im
            // ChatModelSheet wirkt sofort auf die nächste Antwort. Medien-Bubbles
            // kommen über den chatMedia-Verband und werden nicht vorgelesen.
            unseen.zip(appended).forEach { (event, msg) ->
                if (ChatSpeechPrefs.enabled(context, event.agentId)) {
                    ChatSpeechSpeaker.enqueue(context, msg.id, event.text)
                }
            }
            // elapsed_ms an die msg_id binden — 'Antwort nach X s' bleibt
            // auch nach dem 200er-Cap von chatEvents richtig zugeordnet.
            elapsedMsByMsgId = elapsedMsByMsgId +
                unseen.zip(appended).mapNotNull { (event, msg) ->
                    event.elapsedMs?.let { msg.id to it }
                }
        }
        // Auch deduplizierte (Restore-Zwillinge) als konsumiert markieren —
        // sonst blieben sie für immer im fresh-Filter (harmlos, aber sinnlos).
        consumedEventKeys = (consumedEventKeys + fresh.map {
            ChatTimelineMerge.eventKey(it.chatId, it.ts)
        }).takeLast(MAX_CONSUMED_EVENT_KEYS)
    }

    // PH-3 Welle 2: rot-Banner (Fingerprint-ABWEICHUNG / Session-Reset).
    val e2eBanner by RelayClient.e2eBanner.collectAsState()

    // ── W4-B (Zitieren): Zitieren-Verband ────────────────────────────────────

    // Aktiver Antwort-Verweis (Snapshot der Bubble, die der Longpress gewählt
    // hat). null = kein Zitier-Modus. Kein saveable: nach einer Rotation ist
    // der Zitier-Modus beendet — konservativ, nie ein Zitat an die falsche
    // Bubble kleben. Rückfold der restaurierten Bubbles unten (splitWireText).
    var replyQuote by remember { mutableStateOf<ChatQuote?>(null) }

    /**
     * W4-B (Zitieren): Antwort mit Zitier-Verband — Ratchet-Vertrag: kein
     * neuer Frame-Typ. Der DRAHT-Text enthält das Zitat im bestehenden Körper
     * (ChatQuoteRules.wireText → `text`-Feld von chat.send / chat.send.enc);
     * die LIVE-Bubble zeigt die reine Antwort + den strukturierten Zitat-
     * Block über ihr. Die Platte-Wahrheit ist der Draht-Text (persistiert
     * RelayClient.sendChat ohnehin mit dem GESAMTEN text-Arg) — der Restore
     * faltet ihn unten mit ChatQuoteRules.splitWireText zurück.
     */
    fun submitQuoted(
        agent: ChatAgent,
        text: String,
        quote: ChatQuote?,
        // Komposer Welle 12: Modell- und Denken-Auswahl reist mit der
        // Nachricht (Transport-Layer); 'Auto'/'aus' = null = kein Feld.
        composerModel: String? = null,
        composerEffort: String? = null,
    ): Long {
        val quoteSnapshot = quote?.takeIf { ChatQuoteRules.quoteSpan(it.text).isNotEmpty() }
        val wire = ChatQuoteRules.wireText(text, quoteSnapshot)
        // W4-FIX-JAG2 (C6): EIN Zeitstempel für UI-Bubble UND Persist-Versand.
        // Vorher tippte die UI nowTime() beim Anhängen und RelayClient.persist
        // beim HTTP-Ergebnis (System.currentTimeMillis() dort) — lag zwischen
        // beidem ein Minuten-Roll (z. B. beim Antwort-Timeout > 1 min),
        // unterschieden sich die dedupeKey-Minuten ("23:59" vs. "00:00") und
        // der Restore-Merge baute Doppel-Bubbles. formatTimestamp(sentAt) ist
        // exakt der Persist-Formatter (HH:mm, Locale.GERMANY) am selben ts.
        val sentAtMs = System.currentTimeMillis()
        val nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        messages = messages + ChatMessage(
            id = nextId,
            agentId = agent.id,
            fromAgent = false,
            text = text,
            time = formatTimestamp(sentAtMs),
            quoted = quoteSnapshot,
            // WELLE-13 P11 (Datum-Chip): Tages-Schlüssel der eigenen Nachricht —
            // Grundlage der Tageswechsel-Gruppierung im Verlauf.
            date = ChatDateRules.dayKey(sentAtMs),
        )
        // PH-FIX-CHAT-A (F1, Restore-Race): ab jetzt darf der Disk-Restore die
        // Liste nicht mehr komplett ersetzen (s. Restore-Merge oben).
        liveMessagesPresent = true
        if (agent.agentId.isBlank()) {
            // Demo-Fallback-Eintrag: kein echter Agent hinterlegt —
            // bewusst KEIN Relay-Versand und KEINE Fake-Antwort.
        } else {
            // PH2-MERGE: echter Versand über den Relay-Broker; bei aktiver
            // E2E-Session verschlüsselt RelayClient.sendChat selbst (chat.send.enc).
            // W4-B (Zitieren): der VERSAND trägt den Draht-Text (Zitat gefaltet
            // in den bestehenden chat.send-Körper) — die Persistenz der Platte
            // folgt dem Draht → der Restore faltet zurück.
            // W4-FIX-JAG2 (C6): der Persist-Timestamp = der UI-Bubble-Ts —
            // eine Uhr, keine Minuten-Roll-Kluft zwischen Anzeige und Platte.
            RelayClient.sendChat(
                agent.agentId, wire, sentAtMs,
                model = composerModel, effort = composerEffort,
            ) { ok, error, msgId ->
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

    /**
     * Lokale Nachricht anhängen + Relay-Versand (von Eingabe und FP-Abgleich
     * gemeinsam genutzt — identischer Sendeweg, identische Fehlerbehandlung).
     * Gibt die local_id der angehängten Nachricht zurück (SIRS-03: die App
     * merkt sich die FP-Abgleich-Nachricht, um ihren Chip mit der Antwort zu
     * heilen).
     */
    fun submitText(agent: ChatAgent, text: String): Long = submitQuoted(agent, text, quote = null)

    // ── MEDIA-T1: Medien-/Datei-Versand (Picker, Voice, Upload) ───────────

    var attachSheetOpen by remember { mutableStateOf(false) }
    // WELLE 13 P8 (Koordinator): der Holen-Verband für eingehende Medien —
    // die bytes liegen bereits app-privat (chat.media.enc-Reassembly);
    // resolveChatMediaPath findet die Datei am media_id-Präfix. null = die
    // Voice-Zeile zeigt ehrlich „Nicht abrufbar — erneut holen“.
    LaunchedEffect(Unit) {
        ChatMediaDownload.handler = { _agentId, mediaId, onDone ->
            onDone(RelayClient.resolveChatMediaPath(mediaId))
        }
    }
    var voiceRecording by remember { mutableStateOf(false) }
    val voiceRecorder = remember { VoiceMemoRecorder(context) }
    // Aufnahme beim Verlassen der Conversation bewusst abbrechen (kein
    // Zombie-Recorder im Hintergrund, kein Mikro-Claim über den Screen weg).
    DisposableEffect(selectedAgentId) {
        onDispose {
            if (voiceRecorder.isRecording) {
                voiceRecorder.cancel()
                voiceRecording = false
            }
        }
    }

    /**
     * MEDIA-T1: Medium als lokale Bubble anhängen + verschlüsselten Upload
     * anstoßen (RelayClient.sendMedia, fail-closed ohne E2E-Session).
     */
    fun submitMedia(agent: ChatAgent, kind: String, name: String, mime: String, bytes: ByteArray) {
        val sentAtMs = System.currentTimeMillis()
        val nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        val label = MediaProtocol.kindLabel(kind)
        val sizeText = MediaProtocol.formatBytes(bytes.size.toLong())
        val bubbleText = "[$label] $name ($sizeText)"
        // WELLE-13 P8: EIGENE Medien persistieren — vorher localPath="" und
        // der Play-Button der eigenen Bubble schlug still fehl (Sir-Befund).
        val ownLocalPath = RelayClient.persistOutboundMedia(name, bytes) ?: ""
        messages = messages + ChatMessage(
            id = nextId,
            agentId = agent.id,
            fromAgent = false,
            text = bubbleText,
            time = nowTime(),
            // WELLE-13 P11 (Datum-Chip): Tages-Schlüssel der eigenen Medien-Nachricht.
            date = ChatDateRules.dayKey(sentAtMs),
            media = ChatMediaInfo(
                mediaId = "",
                kind = kind,
                name = name,
                mime = mime,
                size = bytes.size.toLong(),
                localPath = ownLocalPath,
            ),
        )
        liveMessagesPresent = true
        if (agent.agentId.isBlank()) {
            // Demo-Fallback: kein echter Agent, kein Fake-Upload.
        } else {
            RelayClient.sendMedia(agent.agentId, kind, name, mime, bytes) { ok, error, mediaId, msgId ->
                if (!ok) {
                    failedLocalIds = failedLocalIds + nextId
                    sendErrors = sendErrors + (
                        agent.agentId to "Media: ${error ?: "unbekannter Fehler"}"
                        )
                } else {
                    if (mediaId != null) {
                        // media_id in die Bubble schreiben → Upload-Fortschritt bindet.
                        messages = messages.map { msg ->
                            if (msg.id == nextId && msg.media != null) {
                                msg.copy(media = msg.media.copy(mediaId = mediaId))
                            } else msg
                        }
                    }
                    if (msgId != null) {
                        // W4-01c (App-Lane A, W4-APP-03): msg_id-Spiegel-Verband —
                        // die Media-Bubble hört ab jetzt denselben ChatStatus-
                        // Chip-Verband wie Text: chat.status (Key msg_id) liefert
                        // queued→delivered→read → Chip „✓ Gesendet → ✓✓ Zuge-
                        // stellt → ✓✓ Gelesen (blau)" UNTER der Media-Bubble
                        // (Chip-Zeile in ChatWidgets.MessageBubble rendert
                        // bereits für Medien-Bubbles — die msg_id-Bindung war
                        // die fehlende Faser). B3-Verband (Relay W4-B3, 14:29):
                        // die msg_id kommt aus der FINAL-Chunk-Response je
                        // Chunk-POST (Relay erzeugt je POST eine Broker-msg_id;
                        // der Adapter nimmt die Completing-Chunk-ID für den
                        // chat.read.enc-Receipt). null bei Nutzer-/Alt-Relay
                        // ohne B3-Fix → Chip bleibt ehrlich „✓ Gesendet".
                        // Demo-Eintrag (blank agentId) sendet nie → kein Chip,
                        // unangetastet.
                        msgIdByLocalId = msgIdByLocalId + (nextId to msgId)
                    }
                }
            }
        }
    }

    /** Gewählte Picker-URI einlesen (50-MB-Deckel, Name/Mime via Resolver). */
    fun sendPickedUri(agent: ChatAgent, uri: android.net.Uri, forcedKind: String?) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "datei"
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            }
        }
        val kind = forcedKind ?: when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "voice"
            else -> "file"
        }
        mediaScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bytes == null || bytes.isEmpty()) {
                    sendErrors = sendErrors + (agent.agentId to "Media: Datei nicht lesbar")
                } else if (bytes.size.toLong() > MediaProtocol.MAX_BYTES) {
                    sendErrors = sendErrors + (
                        agent.agentId to "Media: zu groß (max ${MediaProtocol.formatBytes(MediaProtocol.MAX_BYTES)})"
                        )
                } else {
                    submitMedia(agent, kind, name, mime, bytes)
                }
            }
        }
    }

    fun openAgent(): ChatAgent? = agents.firstOrNull { it.id == selectedAgentId }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val agent = openAgent() ?: return@rememberLauncherForActivityResult
        uri?.let { sendPickedUri(agent, it, "image") }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val agent = openAgent() ?: return@rememberLauncherForActivityResult
        uri?.let { sendPickedUri(agent, it, null) }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val agent = openAgent() ?: return@rememberLauncherForActivityResult
        if (granted) {
            runCatching { voiceRecorder.start() }.onSuccess { voiceRecording = true }
        } else {
            sendErrors = sendErrors + (agent.agentId to "Media: Mikrofon-Berechtigung fehlt")
        }
    }

    /** Voice-Record starten/stopen — Stopp sendet die fertige AAC-Datei. */
    fun toggleVoice() {
        val agent = openAgent() ?: return
        if (voiceRecorder.isRecording) {
            val file = voiceRecorder.stop()
            voiceRecording = false
            if (file == null) {
                sendErrors = sendErrors + (agent.agentId to "Media: Sprachnachricht zu kurz")
                return
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                sendErrors = sendErrors + (agent.agentId to "Media: Sprachnachricht unlesbar")
                return
            }
            submitMedia(agent, "voice", file.name, "audio/mp4", bytes)
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                runCatching { voiceRecorder.start() }
                    .onSuccess { voiceRecording = true }
                    .onFailure {
                        sendErrors = sendErrors + (agent.agentId to "Media: Aufnahme fehlgeschlagen")
                    }
            } else {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // MEDIA-T1: empfangene Agenten-Medien in die Verlaufsliste übernehmen
    // (analog chatEvents: Schlüssel = mediaId, Cap gegen Doppel-Übernahme).
    var consumedMediaKeys by rememberSaveable(stateSaver = ConsumedEventKeysSaver) {
        mutableStateOf(listOf<String>())
    }
    // WELLE 13 P10 (Sir): ASSISTENTEN-Start verbrauchen (einmalig) — der
    // Trampoline (AssistEntryActivity) setzte pendingVoice; BridgeScreen
    // startet deshalb in Route 2 und hier springt die Sprachaufnahme an:
    // der Nutzer redet direkt, das Ende sendet die Sprachnachricht.
    LaunchedEffect(Unit) {
        if (com.hermesandroid.bridge.AssistLaunch.pendingVoice) {
            com.hermesandroid.bridge.AssistLaunch.pendingVoice = false
            if (!voiceRecording) {
                toggleVoice()
            }
        }
    }
    LaunchedEffect(chatMedia, historyRestored) {
        if (!historyRestored) return@LaunchedEffect
        val consumed = consumedMediaKeys.toHashSet()
        val fresh = chatMedia.filterNot { it.mediaId in consumed }
        if (fresh.isEmpty()) return@LaunchedEffect
        var nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        val appended = fresh.map { event ->
            val label = MediaProtocol.kindLabel(event.kind)
            ChatMessage(
                id = nextId++,
                agentId = event.agentId.hashCode(),
                fromAgent = true,
                text = "[$label] ${event.name} (${MediaProtocol.formatBytes(event.size)})",
                time = formatTimestamp(event.ts),
                // WELLE-13 P11 (Datum-Chip): Tages-Schlüssel der Medien-Antwort.
                date = ChatDateRules.dayKey(event.ts),
                media = ChatMediaInfo(
                    mediaId = event.mediaId,
                    kind = event.kind,
                    name = event.name,
                    mime = event.mime,
                    size = event.size,
                    localPath = event.localPath,
                ),
            )
        }
        messages = messages + appended
        consumedMediaKeys = (consumedMediaKeys + fresh.map { it.mediaId })
            .takeLast(MAX_CONSUMED_EVENT_KEYS)
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
        // W4-B (Zitieren): der aktive Antwort-Verweis stirbt mit der
        // Conversation — nie ein Zitat aus Verlaufsliste A in Agent B kleben.
        replyQuote = null
    }

    // Back in der Conversation: erst zurück zur Agenten-Liste; die Route-Back-
    // Behandlung (Route → Home, BridgeScreen) greift erst danach, weil dieser
    // neuere BackHandler Vorrang hat, solange er enabled ist.
    // B7-LIVE (03.11.): Offen-Prüfung vorzeichenfest über die Agenten-Liste —
    // `selectedAgentId >= 0` hielt den System-Back in JEDEM Live-Chat deakti-
    // viert (Live-IDs = agentId.hashCode(), z. B. ein Live-Name → -1167182811),
    // Back sprang zur Route statt zur Agenten-Liste.
    BackHandler(enabled = AttachSheetRules.isConversationOpen(selectedAgentId, agents.map { it.id })) {
        closeConversation()
    }

    // PH-FIX-CHAT-A (F1): EINE Verlaufsliste — Agenten-Antworten werden beim
    // Eintreffen in `messages` übernommen (LaunchedEffect oben), nicht per
    // Composition hinten angehängt. Vorschau-Zeile und Conversation lesen
    // denselben chronologischen Stand.
    val allMessages = messages

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
                // W4-B (Zitieren): Zitier-Modus ebenfalls je Conversation neu.
                replyQuote = null
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
                // PH-FIX-CHAT-A (F1): elapsed_ms ist bei der Übernahme an die
                // msg_id gebunden — der 200er-Cap von chatEvents kann keine
                // Zuordnung mehr verschieben (vorher: id − INCOMING_ID_BASE
                // als Index, verschob sich beim Cappen auf ALLE Events).
                elapsedMsByMsgId[messageId]
            },
            inputText = inputText,
            onInputChange = { inputText = it },
            onSend = {
                val text = inputText.trim()
                if (text.isNotEmpty()) {
                    inputText = ""
                    // W4-B (Zitieren): mit aktivem Antwort-Verweis geht die
                    // Antwort in den Zitier-Verband (Draht-Text + Live-Bubble
                    // mit Zitat-Block, s. submitQuoted).
                    // Komposer Welle 12: die Auswahl am Komposer (Modell/
                    // Denken) reist mit JEDER Nachricht mit.
                    submitQuoted(
                        agent, text, replyQuote,
                        composerModel = ChatComposerPrefs.model(context, agent.agentId),
                        composerEffort = ChatComposerPrefs.effort(context, agent.agentId),
                    )
                    replyQuote = null
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
            // MEDIA-T1: Anhang-Button (Picker/Datei/Voice) + Upload-Fortschritt
            // je media_id für die eigenen Medien-Bubbles.
            onAttach = { attachSheetOpen = true },
            voiceRecording = voiceRecording,
            onVoiceToggle = { toggleVoice() },
            uploadOfMedia = { mediaId ->
                mediaId.takeIf { it.isNotBlank() }?.let { mediaUploads[it] }
            },
            // WELLE-13 P8 (UI-Verband): Holen eingehender Medien. Die eigentliche
            // Download-Implementierung (Relay GET /media/{id}, E2E, Chunk-Frame)
            // bindet der KOORDINATOR an ChatMediaDownload.handler an — die Chat-UI
            // ruft hier nur den Hook und refüllt nach onDone(path) die Bubble
            // (media.localPath → normale Voice-Zeile mit Play/Pause/Fortschritt).
            // Ohne Hook / bei Fehlschlag: onDone(null) — die Voice-Zeile zeigt
            // ehrlich „Nicht abrufbar — erneut holen“ (kein Fake-Player).
            onDownloadMedia = { mediaId, onDone ->
                val handler = ChatMediaDownload.handler
                if (handler == null) {
                    onDone(null)
                } else {
                    handler(agent.agentId, mediaId) { path ->
                        if (!path.isNullOrBlank()) {
                            messages = messages.map { m ->
                                val media = m.media
                                if (media != null && media.mediaId == mediaId && media.localPath.isBlank()) {
                                    m.copy(media = media.copy(localPath = path))
                                } else {
                                    m
                                }
                            }
                        }
                        onDone(path)
                    }
                }
            },
            // PH-3 Welle 2: rot-Banner bei Abweichung/Reset unter dem Kopf.
            e2eBanner = e2eBanner,
            onDismissE2eBanner = { RelayClient.clearE2eBanner() },
            // W4-B (Zitieren): Antwort-Overlay (Strip über der Eingabe-Leiste)
            // + Zitat-Block über der gesendeten Bubble; onReply = Longpress.
            replyQuote = replyQuote,
            onSetReply = { replyQuote = it },
            onDismissReply = { replyQuote = null },
            onBack = {
                // PH2-FIX (Jäger B6): Banner des Agenten beim Verlassen clearen.
                closeConversation()
            },
        )
    }

    // MEDIA-T1: Anhang-Auswahl (Foto/Video, Datei, Sprachnachricht). Der
    // Sheet öffnet NUR innerhalb einer geöffneten Conversation (Picker-
    // Launcher brauchen den Agenten-Kontext); Voice startet direkt.
    // FIX-WELLE-2 B7: Trigger-Vertrag in AttachSheetRules gepinnt (JVM-
    // testbar); Rendert-Pfad am Release-Build im Clean-Drill bewiesen.
    // B7-LIVE-RE-DRILL (03.11.): im gepaarten Live-Chat öffnete das Sheet
    // NICHT — Root-Cause war das ID-VORZEICHEN (Live-ID = agentId.hashCode(),
    // B7-Live-Fall → -1167182811), KEIN State-Reset-Race; „offen“ kommt jetzt
    // vorzeichenfest aus der Agenten-Liste, nicht aus `id >= 0`.
    if (AttachSheetRules.shouldRenderSheet(
            attachSheetOpen,
            AttachSheetRules.isConversationOpen(selectedAgentId, agents.map { it.id }),
        )
    ) {
        AttachSheet(
            onPickImage = {
                imagePicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                    )
                )
            },
            onPickFile = { filePicker.launch(arrayOf("*/*")) },
            onVoiceToggle = { toggleVoice() },
            onDismiss = { attachSheetOpen = false },
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
        // WELLE-13 P11b (Sirs Wunschbild): Such-Kapsel + Filter-Chips. Nur
        // ehrliche Filter: Alle / Online / Offline (Ungelesen-Leseschalter
        // existiert datenseitig nicht → der Chip LÜGT, also fehlt er).
        item {
            var listQuery by rememberSaveable { mutableStateOf("") }
            var listFilter by rememberSaveable { mutableStateOf("alle") }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(FlipsiColors.PanelElev)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🔍",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlipsiColors.TextSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = listQuery,
                        onValueChange = { listQuery = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = FlipsiColors.TextPrimary,
                            fontSize = 15.sp,
                        ),
                        cursorBrush = SolidColor(FlipsiColors.Ember),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (listQuery.isEmpty()) {
                                Text(
                                    "Agenten durchsuchen",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = FlipsiColors.TextSecondary,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipFlipsi(
                        label = "Alle",
                        active = listFilter == "alle",
                        onClick = { listFilter = "alle" },
                    )
                    FilterChipFlipsi(
                        label = "Online",
                        active = listFilter == "online",
                        onClick = { listFilter = "online" },
                    )
                    FilterChipFlipsi(
                        label = "Offline",
                        active = listFilter == "offline",
                        onClick = { listFilter = "offline" },
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            // Filterung: Query-Treffer im Namen ODER der Preview + Statusfilter.
            val q = listQuery.trim()
            val filteredAgents = agents.filter { agent ->
                val matchesQuery =
                    q.isBlank() ||
                        agent.name.contains(q, ignoreCase = true) ||
                        (messages.lastOrNull { it.agentId == agent.id }?.text ?: "")
                            .contains(q, ignoreCase = true)
                val matchesFilter = when (listFilter) {
                    "online" -> agent.online
                    "offline" -> !agent.online
                    else -> true
                }
                matchesQuery && matchesFilter
            }
            if (filteredAgents.isEmpty()) {
                Text(
                    "Kein Agent passt zur Suche.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            filteredAgents.forEach { agent ->
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
}

// ── Conversation (Verlauf + Eingabe) ────────────────────────────────────────

/**
 * Eine Conversation: Kopf, scrollender Verlauf und Eingabe-Leiste unten.
 *
 * W4-01 (App-Lane A, W4-01a) Header-Fix-Verband + IME-Verband — Contract
 * EXECUTIONSTATE §343 (verbatim, mit Manifest `windowSoftInputMode=
 * "adjustResize"` als Schiene 1):
 *  - Header (Agent-Name/FP-Button), Divider und Fehler-/E2E-Banner fix OBEN —
 *    außerhalb jeder ime-gepolsterten Streckung (kein imePadding auf dieser
 *    Column!),
 *  - Nachrichten-Liste weight(1f) (wird unterm IME-Fenster geschweigt),
 *  - NUR die ChatInputBar trägt imePadding()+navigationBarsPadding()
 *    (IME-Polster direkt am Box-Wrap der InputBar, darunter im Code).
 * edge-to-edge-Grundstand 09.11. = inaktiv (enableEdgeToEdge = 0 Treffer im
 * App-Tree) → klassischer Decor-fits-Pfad; Insets-Schienen sind dort 0 dp und
 * werden mit späterer edge-to-edge-Aktivierung wirksam. UX-Beweis NUR über
 * Emulator-Drill (RP3-Vertrag, siehe Beweis-Marker am InputBar-Box-Wrap) —
 * Robolectric kennt die IME-Grenze nicht (R6); Stand-PC-Re-Drill = spätere
 * Build-Welle.
 */
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
    // MEDIA-T1: Anhang/Voice + Upload-Fortschritt (Progress-Chips je media_id).
    onAttach: (() -> Unit)? = null,
    voiceRecording: Boolean = false,
    onVoiceToggle: (() -> Unit)? = null,
    uploadOfMedia: (String) -> RelayClient.MediaUploadState? = { null },
    // W4-B (Zitieren): aktiver Antwort-Verweis (null = kein Zitier-Modus) +
    // Abwahl-Binding für das Overlay-Strip.
    replyQuote: ChatQuote? = null,
    onSetReply: (ChatQuote?) -> Unit = {},
    onDismissReply: () -> Unit = {},
    // WELLE-13 P8 (UI-Verband): Holen-Callback für eingehende Medien ohne
    // lokale Datei — null = kein Download-Dienst (Voice-Zeile zeigt einen
    // ehrlichen Hinweis statt eines toten Buttons).
    onDownloadMedia: ((mediaId: String, onDone: (String?) -> Unit) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    // W4-B (Swipe-to-Reply): Reduced-Pass-Faser — EIN Lesen der Anim-Scala
    // je Composition (ChatMotionRules); steuert Geste-Settle UND Sprung-
    // Scroll der Chat-Suche (je Anim-Stelle genau einmal entschieden).
    val compositionContext = LocalContext.current
    val reducedMotion = remember(agent.agentId) {
        ChatMotionRules.reduced(ChatMotionRules.animatorScale(compositionContext.contentResolver))
    }
    // ── Komposer Welle 12: Modell-/Denken-Auswahl am Komposer ────────────────
    // Auswahl lebt PRO AGENT (SharedPreferences via ChatComposerPrefs); die
    // lokalen States spiegeln sie für die Chips. Das Sheet öffnet per Chip.
    var composerModel by remember(agent.agentId) {
        mutableStateOf(ChatComposerPrefs.model(compositionContext, agent.agentId))
    }
    var composerEffort by remember(agent.agentId) {
        mutableStateOf(ChatComposerPrefs.effort(compositionContext, agent.agentId))
    }
    var composerPickerOpen by remember(agent.agentId) { mutableStateOf(false) }    // WELLE-13 P9 (Vorlesen): je Agent (Prefs vorlesen_<agentId>) — der Spiegel
    // füttert den Regler im ChatModelSheet; das Enqueue in ChatScreen liest
    // den Prefs-Stand zur Laufzeit, der Regler wirkt also sofort.
    var composerReadAloud by remember(agent.agentId) {
        mutableStateOf(ChatSpeechPrefs.enabled(compositionContext, agent.agentId))
    }
    // WELLE-13 P9: Speaking-Chips je Bubble — zustandsgesteuert vom TTS-Singleton
    // (IDs der gerade/nächst vorgelesenen Antworten; Tap auf den Chip stoppt).
    val speakingIds by ChatSpeechSpeaker.speakingIds.collectAsState()
    // ── W4-B (Chat-Suche): Magnifier-Verband ─────────────────────────────────
    // Suchleiste + Treffer-Liste über dem VOLLSTÄNDIGEN Verlauf (Disk-Restore
    // via ChatHistoryStore + Live-Ankömmlinge — NIEMALS am chatEvents-200er-
    // Cap hängend, Filter-Verband in ChatSearchRules). Sprung-Anker: Tap in
    // der Trefferliste kehrt zur Timeline zurück und scrollt zur Bubble.
    var searchOpen by remember(agent.agentId) { mutableStateOf(false) }
    var searchQuery by remember(agent.agentId) { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    var searchFocusTarget by remember { mutableStateOf<Long?>(null) }
    var searchHighlightId by remember { mutableStateOf<Long?>(null) }
    val searchMatches = remember(searchQuery, conversation) {
        ChatSearchRules.matches(searchQuery, conversation)
    }
    val searchActive = searchOpen && ChatSearchRules.active(searchQuery)
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            runCatching { searchFocus.requestFocus() }
        }
    }
    // W4-FIX-JAG3 (Suche-Sprung): searchFocusTarget ist ZUGLEICH der Key DIESES
    // Effekts — ein Write auf den State mitten im Lauf ist ein Key-Diff und
    // startet den Effekt neu; der vorige Job stirbt an der nächsten Suspension.
    // Alte Ordnung (Reset AM ANFANG) tötete den Job an der Scroll-Suspension
    // VOR jedem Write: Sprung + Ember starben beide (5× am Emulator bewiesen,
    // Dumps C:/Dev/qa/w4r_*). Deshalb nie vor dem Highlight-Set reseten.
    LaunchedEffect(searchFocusTarget) {
        val target = searchFocusTarget ?: return@LaunchedEffect
        val idx = conversation.indexOfFirst { it.id == target }
        if (idx < 0) {
            // Treffer kam/war nicht im Verlauf (Verlauf zwischendurch neu geladen):
            // Request konsumieren (Key zurück auf null), sonst schluckt der Alt-Wert
            // einen Wiedertap auf denselben Treffer stillschweigend (gleicher Wert =
            // kein Key-Diff = kein Neustart dieses Effekts). Kein Scroll/Ember hier.
            searchFocusTarget = null
            return@LaunchedEffect
        }
        // Anim-Stelle: Sprung-Scroll über die Reduced-Pass-Faser (ChatMotionRules).
        if (reducedMotion) listState.scrollToItem(idx) else listState.animateScrollToItem(idx)
        // Ember VOR dem Key-Reset setzen — nach diesem Write folgt in DIESEM Job
        // keine Suspension mehr: das Reset-Write landet, der Job endet; das an
        // den Key-Diff angesetzte Cancel trifft einen bereits beendeten Job
        // (no-op), der Ablauf bleibt unangetastet.
        searchHighlightId = target
        searchFocusTarget = null
    }
    // Ember-Expiry EIGENER Effekt am Highlight-State (W4-FIX-JAG3): delay + Clear
    // dürfen NICHT im Sprung-Effekt hinter dessen Key-Reset liegen — das Reset-
    // Write ist ein Key-Diff am eigenen Effekt, der schlafende Job stirbt an der
    // delay-Suspension und highlight=null lief NIE (Ember bliebe dauerhaft an).
    // Hier schläft der Timeout-Job unter unverändertem Key und löscht nach ~1,6 s
    // zuverlässig; Sprung auf Treffer 2 innerhalb des Fensters setzt den Wert
    // neu → Fenster startet neu (Ember wandert mit, kein Alt-Rest).
    LaunchedEffect(searchHighlightId) {
        if (searchHighlightId == null) return@LaunchedEffect
        delay(1_600L)
        searchHighlightId = null
    }
    // PH-FIX-CHAT-A (F4): die offene Conversation beim RelayClient melden —
    // Antworten DIESES Agenten erzeugen keine Notification, solange die App
    // im Vordergrund ist (RelayClient prüft beides). Beim Verlassen
    // zurücksetzen; Demo-Eintrag (blank agentId) bleibt null.
    DisposableEffect(agent.agentId) {
        RelayClient.chatVisibleAgentId = agent.agentId.takeIf { it.isNotBlank() }
        onDispose { RelayClient.chatVisibleAgentId = null }
    }
    // Auf die neueste Nachricht scrollen (unten = neueste).
    // W4-FIX-JAG2 (C8): Ende-Gate während der Chat-Suche — der Effekt feuerte
    // je conversation.size-Änderung (auch JE neue Antwort mitten im Such-
    // Sprung) und riss den animateScrollToItem-Sprung zur Treffer-Bubble
    // zum Listen-Ende zurück (Konflikt zweier Scroll-Anims, Sprung verloren).
    // Bei offener Suchleiste folgt der Verlauf dem Nutzer (Suchleiste zu =
    // gewohntes Ende Folgen; ein Rest-Zustand wird beim Schließen nicht
    // nachgezogen — der Nutzer ist eh am Lesen).
    LaunchedEffect(conversation.size) {
        if (searchOpen) return@LaunchedEffect
        if (conversation.isNotEmpty()) {
            listState.animateScrollToItem(conversation.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        ConversationHeader(
            agent = agent,
            working = working,
            onBack = onBack,
            onFpCheck = onFpCheck,
            // W4-B (Chat-Suche): Magnifier-Knopf toggelt die Suchleiste.
            searchOpen = searchOpen,
            onSearchToggle = {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
            },
            onSupport = { supportDialogOpen = true },
        )
        HorizontalDivider(color = FlipsiColors.LineStrong)
        // PH2-FIX (E2E P1): dismissbare Fehler-Zeile direkt unter dem Kopf —
        // der Send-Fehler ist auf der Chat-Route sichtbar, ohne die Route zu
        // verlassen (BridgeStatus rendert hier nicht).
        SendErrorBanner(message = errorBanner, onDismiss = onDismissError)
        // PH-3 Welle 2: rot-Banner bei Fingerprint-ABWEICHUNG / Session-Reset
        // (möglicher Man-in-the-Middle) — gleiche rot-Sprache wie der Send-Fehler.
        SendErrorBanner(message = e2eBanner, onDismiss = onDismissE2eBanner)
        // WELLE 13 (Sir): Herz-Knopf oeffnet den Support/IAP-Dialog (2,99-Perk).
        if (supportDialogOpen) {
            SupporterDialog(activity = chatActivity, onDismiss = { supportDialogOpen = false })
        }
        // ── W4 (App-Lane C): Tab-Verband [Chat | Mediathek] am Chatscreen ────
        // Der Mediathek-Tab lebt NEBEN dem Chat in derselben Conversation
        // (gemeinsamer Kopf). Zustand je Agent: Agent-Wechsel setzt auf Chat
        // zurück (remember(agent.id), bewusst KEIN saveable — Rotation/Wechsel
        // kehren in die Chat-Ansicht zurück, das ist der natürliche Modus).
        var convTab by remember(agent.id) { mutableStateOf(TAB_CHAT) }
        ChatTabsRow(selected = convTab, onSelect = { convTab = it })
        if (convTab == TAB_CHAT) {
            // W4-B (Chat-Suche): Suchleiste unter dem Tab-Verband (offen =
            // Tastatur fokusiert; X leert erst die Query, schließt dann).
            if (searchOpen) {
                ConversationSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    focusRequester = searchFocus,
                    onClose = {
                        if (searchQuery.isNotBlank()) searchQuery = "" else searchOpen = false
                    },
                )
            }
            if (searchActive) {
                // Treffer-Liste ersetzt die Timeline (gleicher Slot); Sprung-
                // Anker: Tap schließt die Liste (Query bleibt) → Timeline +
                // Scroll + Ember-Highlight je Sprung-Effekt oben.
                ConversationSearchResults(
                    matches = searchMatches,
                    agentName = agent.name.takeIf { agent.agentId.isNotBlank() },
                    onJump = { matchId ->
                        searchOpen = false
                        searchFocusTarget = matchId
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                // WELLE-13 P11a (Chat-Hintergrund): Wallpaper UNTER dem Verlauf —
                // Standard = dunkles Eigenmuster (Void-Navy), Nutzer-Foto mit
                // 55-%-Scrim wenn in den Einstellungen gewählt (ChatWallpaperPrefs).
                // WELLE-13 P11 (Datum-Chip): Heute/Gestern-Schlüssel je Composition
                // (Composable-Scope, NICHT im LazyListScope — remember wäre dort
                // unzulässig); Tageswechsel-Regeln rein JVM-getestet (ChatDateRules).
                var todayKey by remember { mutableStateOf(ChatDateRules.dayKey(System.currentTimeMillis())) }
                // Jaeger-2: Tageswechsel zur Laufzeit (Chip zeigt ab Mitternacht wieder Heute)
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(60_000L)
                        todayKey = ChatDateRules.dayKey(System.currentTimeMillis())
                    }
                }
                val yesterdayKey = remember {
                    ChatDateRules.dayKey(java.time.LocalDate.now().minusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                }
                Box(modifier = Modifier.weight(1f)) {
                    ChatWallpaper(modifier = Modifier.fillMaxSize())
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // PH-FIX-CHAT-A (F2): horizontaler Außenabstand auf die LISTE
                        // gelegt — 12 dp hier + 8 dp Zeilen-Padding der MessageBubble
                        // (ChatWidgets) = 20 dp Text↔Bildschirmrand (vorher 16 dp mit
                        // zugleich enger Zeilenbreite: „randnah + gequetscht“).
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
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
                        // WELLE-13 P11: je Nachricht EIN Item — der Datum-Chip
                        // (Tageswechsel) rendert ÜBER der Bubble desselben
                        // Items (kein separater Lazy-Schlüssel, kein Doppelslot).
                        itemsIndexed(conversation, key = { _, m -> m.id }) { index, message ->
                            val prevDate = if (index > 0) conversation[index - 1].date else null
                            if (ChatDateRules.chipVisible(prevDate, message.date)) {
                                ChatDateRules.dayLabel(message.date, todayKey, yesterdayKey)?.let { label ->
                                    ChatDateChip(label = label)
                                }
                            }
                            MessageBubble(
                            message = message,
                            // PH-FIX-CHAT-A (F3): Agent-Name als Hauptlabel neben dem
                            // KI-Badge — Demo-Einträge (blank agentId) bekommen ihn
                            // bewusst nicht (kein echter Agent hinter dem Namen).
                            senderName = agent.name.takeIf { agent.agentId.isNotBlank() },
                            // PH2-T10: Zustell-/Lese-Chips je EIGENER Nachricht.
                            status = if (!message.fromAgent) statusOfMessage(message) else null,
                            // PH2-FIX (Jäger B7): Demo-Eintrag (blank agentId) → KEIN
                            // Chip — '✓ Gesendet' wäre gelogen, es geht nichts raus.
                            showStatusChip = agent.agentId.isNotBlank(),
                            // PH2-T11: 'Antwort nach X s' je Agenten-Antwort.
                            replyAfterMs = if (message.fromAgent) elapsedMsByMessageId(message.id) else null,
                            // MEDIA-T1: Upload-Fortschritt eigener Medien-Bubbles
                            // (x/y Chunks, ✓ beim Agenten-Ack aller Chunks).
                            mediaUpload = message.media?.let { uploadOfMedia(it.mediaId) },
                            // W4-B (Zitieren): Longpress → Antwort-Verweis als Werte-
                            // Snapshot (ChatQuote). senderName nur beim echten Agenten
                            // (Demo-Eintrag blank agentId → Label „KI“ im Strip).
                            onReply = {
                                onSetReply(
                                    ChatQuote(
                                        fromAgent = message.fromAgent,
                                        senderName = if (message.fromAgent && agent.agentId.isNotBlank()) agent.name else "",
                                        text = message.text,
                                    )
                                )
                            },
                            // W4-B (Swipe-to-Reply): Reduced-Pass-Faser der
                            // Geste-Rücklauf-Stelle (Settle je Anim-Scala).
                            chatReducedMotion = reducedMotion,
                            // W4-B (Chat-Suche): Sprung-Highlight (Ember-Rand ~1,6 s).
                            searchHighlight = searchHighlightId == message.id,
                            // WELLE-13 P9: Speaking-Chip — diese Agenten-Bubble
                            // wird gerade vorgelesen; Tap = Stopp (ganze Queue).
                            speaking = message.id in speakingIds,
                            onStopSpeaking = { ChatSpeechSpeaker.stopAll() },
                            // WELLE-13 P8: Holen-Verband eingehender Medien.
                            onDownloadMedia = onDownloadMedia,
                            )
                        }
                    }
                }
            }
            // W4-B (Zitieren): Antwort-Overlay — Longpress auf eine Bubble setzt
            // den Antwort-Verweis, das Strip zeigt Original-Zitat über der
            // Eingabe-Leiste; Senden klebt den Zitat-Block über die neue Bubble.
            // (Chat-spezifisch: der Strip hängt an replyQuote und gehört in den
            // Verlaufszweig — der Mediathek-Tab zeigt ihn nicht.)
            replyQuote?.let { quote ->
                ReplyQuoteStrip(quote = quote, onCancel = onDismissReply)
            }
            HorizontalDivider(color = FlipsiColors.LineStrong)
            // W4-01 (App-Lane A, W4-01a): IME-Verband — NUR die InputBar trägt
            // imePadding()+navigationBarsPadding(); der Header (Agent-Name/
            // FP-Button) und die Divider/Banner leben oberhalb und werden von
            // der Tastatur NIEMALS verdrängt (Contract §343 (2), verbatim:
            // „Header/Fingerprint-Zeile fix OBEN (AUSSERHALB jeder ime-
            // gepolsterten Column), Nachrichten-Liste weight(1f), nur InputBar
            // bekommt imePadding()+navigationBarsPadding()"). Die Liste selbst
            // bekommt KEIN imePadding (Content-Ende bleibt beim Scrollen
            // sichtbar, weight(1f) schweigt sie unterm adjustResize-Fenster).
            // Grundstand edge-to-edge = 0 Treffer (Manifest-Kommentar) → beide
            // Insets liefern heute 0 dp und werden mit späterem edge-to-edge
            // wirksam (keine Doppelpolster-Risiken in beiden Modi).
            // Beweis-Marker W4-01: Emulator-Drill RP3 (Tastatur auf → Header
            // sichtbar, InputBar über Tastatur) mit XML-Dump offen-Tastatur-
            // Stand → qa/screens4/ (Stand-PC-Re-Drill = spätere Build-Welle).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                ChatInputBar(
                    value = inputText,
                    onValueChange = onInputChange,
                    onSend = onSend,
                    onAttach = onAttach,
                    voiceRecording = voiceRecording,
                    onVoiceToggle = onVoiceToggle,
                    modelLabel = composerModel ?: "Auto",
                    effortLabel = effortGermanLabel(composerEffort),
                    onOpenPicker = { composerPickerOpen = true },
                )
            }
            if (composerPickerOpen) {
                ChatModelSheet(
                    agentId = agent.agentId,
                    currentModel = composerModel,
                    currentEffort = composerEffort,
                    onPickModel = { picked ->
                        composerModel = picked
                        composerPickerOpen = false
                    },
                    onPickEffort = { level ->
                        composerEffort = level
                    },
                    onDismiss = { composerPickerOpen = false },
                    // WELLE-13 P9: Vorlesen-Regler — Persistenz passiert im Sheet
                    // (ChatSpeechPrefs), der Spiegel hält die Antwort des Chats.
                    currentReadAloud = composerReadAloud,
                    onToggleReadAloud = { enabled -> composerReadAloud = enabled },
                )
            }
        } else {
            // ── W4 (App-Lane C): Mediathek-Tab — alle Medien DIESES Agenten
            // (Store-Verbind + LIVE-Bubbles, STALE = Platzhalter-Karte).
            MediathekTabBody(
                agent = agent,
                conversation = conversation,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Kopf der Conversation: zurück zur Liste + Agent-Name + Online-Status.
 * PH2-T10 (F2-S3): arbeitet der Agent gerade (chat.activity working=true),
 * zeigt der Kopf einen kleinen Spinner + 'arbeitet … (mm:ss)' — die Zeit
 * läuft sekündlich ab started_at (Empfang beim Agenten) hoch.
 * W4-B (Chat-Suche): Magnifier-Knopf am Chat-Kopf → Suchleiste + Treffer-
 * Liste + Sprung-Anker (ChatSearchRules/ConversationBody); der Knopf toggelt
 * (Suchleiste offen = Schließen-Semantik, A11y je Zustand).
 */
@Composable
private fun ConversationHeader(
    agent: ChatAgent,
    working: RelayClient.AgentActivity?,
    onBack: () -> Unit,
    onFpCheck: () -> Unit,
    // W4-B (Chat-Suche): Magnifier-Toggle (offen/geschlossen wird oben im
    // ConversationBody gehalten — der Kopf reicht nur den Knopf durch).
    searchOpen: Boolean = false,
    onSearchToggle: () -> Unit = {},
    // WELLE 13 (Sir): Herz-Knopf = Support/IAP-Dialog (2,99-Perk, kein Zwang).
    onSupport: () -> Unit = {},
) {
    // mm:ss seit started_at — je Sekunde neu gerechnet (nur bei working).
    // PH-FIX-CHAT-A (F5): 10-Minuten-Deckel statt while(true)-Ewigzähler —
    // working=false kommt in drei Live-Situationen nie an (PIPELINE-BUGS
    // BUG 3: E2E-Verwurfs-Pfad, Agent-Tod ohne Close-Erkennung, App-WS-Tod
    // mittendrin), der Spinner blieb endlos stehen. Der Ticker bricht bei
    // Timeout ab; die Räumung des Flow-Zustands bei Disconnect übernimmt
    // RelayClient (working-States + Agents offline).
    var elapsedSec by remember(working) { mutableStateOf(0L) }
    var timedOut by remember(working) { mutableStateOf(false) }
    LaunchedEffect(working) {
        if (working != null && working.working) {
            while (true) {
                val now = System.currentTimeMillis()
                if (WorkingStatusRules.timedOut(working.startedAt, now)) {
                    timedOut = true
                    break
                }
                elapsedSec = ((now - working.startedAt) / 1000L)
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
        // PH-FIX-CHAT-A (F5): nach dem 10-Minuten-Deckel kein Spinner mehr.
        if (working != null && working.working && !timedOut) {
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
        // W4-B (Chat-Suche): Magnifier-Knopf am Chat-Kopf — toggelt die
        // Suchleiste; A11y-Label je Zustand (nie nur Farbe).
        IconButton(onClick = onSearchToggle) {
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(
                    if (searchOpen) R.string.chat_search_close_a11y else R.string.chat_search_open_a11y,
                ),
                tint = FlipsiColors.TextPrimary,
            )
        }
        // WELLE 13 (Sir 12.11.): Herz = Unterstützer-Knopf (IAP, unaufdringlich).
        IconButton(onClick = onSupport) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = stringResource(R.string.support_a11y),
                tint = FlipsiColors.TextPrimary,
            )
        }
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
 * SIRS-02: Id-Basis restaurierter (persistierter) Nachrichten — liegt ÜBER dem
 * lokalen Zähler, damit (a) LazyColumn-Keys nie mit Live-Nachrichten kollidieren
 * und (b) restaurierte Einträge keine 'Antwort nach X s'-Zeilen erfinden
 * (elapsed_ms wird nur beim Live-Empfang an die msg_id gebunden,
 * PH-FIX-CHAT-A (F1); restaurierte IDs liegen außerhalb dieser Map → null).
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

/**
 * PH-FIX-CHAT-A (F1/J1): Saver für die konsumierten Event-Schlüssel —
 * flache String-Liste, damit die Übernahme-Markierung eine Rotation
 * überlebt (chatEvents dreht sich im StateFlow weiter).
 */
val ConsumedEventKeysSaver = listSaver<List<String>, Any>(
    save = { keys -> keys.toList() },
    restore = { flat -> flat.map { it as String } },
)

/**
 * PH-FIX-CHAT-A (F1): Obergrenze der konsumierten Event-Schlüssel —
 * deckungsgleich mit dem Empfangs-Puffer des RelayClient (200): ältere
 * Events sind aus chatEvents ohnehin evicted und können nicht mehr
 * eintreffen; die Schlüsselliste wächst dadurch nicht ungebunden.
 */
private const val MAX_CONSUMED_EVENT_KEYS = 200

/**
 * PH-FIX-CHAT-A (F1): Saver für die msg_id → elapsed_ms-Zuordnung — flache
 * Pair-Liste wie bei MsgIdByLocalIdSaver, damit die 'Antwort nach X s'-Zeilen
 * der Agenten-Antworten eine Rotation überleben.
 */
val ElapsedMsByMsgIdSaver = listSaver<Map<Long, Long>, Any>(
    save = { map -> map.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) } },
    restore = { flat ->
        flat.chunked(2).associate { (id, ms) -> (id as Long) to (ms as Long) }
    },
)

/**
 * W4-FIX-JAG2 (C1): Saver für den Chip-Snapshot der restaurierten
 * Disk-Einträge (restored_id → status) — flache Pair-Liste wie bei
 * MsgIdByLocalIdSaver, damit die restaurierten Chips eine Rotation
 * überleben (vorher: `remember` → Map leer beim Drehen, Chip downgraded).
 */
val RestoredStatusSaver = listSaver<Map<Long, String>, Any>(
    save = { map -> map.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) } },
    restore = { flat ->
        flat.chunked(2).associate { (id, status) -> (id as Long) to (status as String) }
    },
)

// ── W4 (App-Lane C): Tab-Verband am Chatscreen je Agent ──────────────────────

/** Tab-Wert „Chat“ (Standard-Ansicht der Conversation). */
private const val TAB_CHAT = "chat"

/** Tab-Wert „Mediathek“ (Medien-Sicht, siehe MediathekTabBody). */
private const val TAB_MEDIATHEK = "mediathek"

/**
 * Tab-Zeile unter dem Conversation-Kopf: [Chat | Mediathek] als Filter-Chips
 * (deutsch beschriftet via Strings-Keys, A11y: Text-Label + selektiert-Zustand
 * — nie nur Farbe). Der Chip-Satz bleibt bewusst minimal (kein TabRow-Kohle-
 * Wrapper, keine Anim — Reduced-Pass je Anim-Stelle).
 */
@Composable
private fun ChatTabsRow(selected: String, onSelect: (String) -> Unit) {
    val chatLabel = stringResource(R.string.chat_tab_chat)
    val mediaLabel = stringResource(R.string.chat_tab_mediathek)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == TAB_CHAT,
            onClick = { onSelect(TAB_CHAT) },
            label = { Text(chatLabel) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = FlipsiColors.Panel,
                labelColor = FlipsiColors.TextPrimary,
                selectedContainerColor = FlipsiColors.Ember,
                selectedLabelColor = FlipsiColors.OnEmber,
            ),
        )
        FilterChip(
            selected = selected == TAB_MEDIATHEK,
            onClick = { onSelect(TAB_MEDIATHEK) },
            label = { Text(mediaLabel) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = FlipsiColors.Panel,
                labelColor = FlipsiColors.TextPrimary,
                selectedContainerColor = FlipsiColors.Ember,
                selectedLabelColor = FlipsiColors.OnEmber,
            ),
        )
    }
}

// ── W4-B (Chat-Suche): Suchleiste + Trefferliste ─────────────────────────────

/**
 * W4-B (Chat-Suche): Suchleiste unter dem Tab-Verband — Suchtext-Feld mit
 * Fokus-Anker (Tastatur öffnet je Magnifier-Toggle) und X-Knopf (X = leert
 * erst die Query; leere Query = Leiste zu).
 *
 * ## Drill-Acker (Bau-Welle, Emulator)
 * - Magnifier → Leiste erscheint, Tastatur fokusiert (FocusRequester); Query
 *   tippt → Trefferliste ersetzt die Timeline im selben Slot.
 * - X mit Query → Query leer, Leiste bleibt; X mit leerer Query → Leiste zu,
 *   Timeline unverändert an der Stelle (kein Scroll-Sprung).
 * - Leiste lebt IM Chat-Tab-Zweig (Mediathek-Tab zeigt sie nicht) — Agent-
 *   Wechsel setzt den Suchzustand je Agent zurück (remember(agent.agentId)).
 */
@Composable
private fun ConversationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    stringResource(R.string.chat_search_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.chat_search_clear_a11y),
                tint = FlipsiColors.TextPrimary,
            )
        }
    }
}

/**
 * W4-B (Chat-Suche): Trefferliste im Timeline-Slot — Zähl-Zeile (deutsch je
 * Singular/Plural) + chronologische Trefferzeilen (Zeit · Absender-Label ·
 * Fenster-Ausschnitt). Tap = Sprung-Anker ([onJump] mit Bubble-id; der Sprung-
 * Effekt in ConversationBody schließt die Liste, scrollt zur Bubble und
 * markiert sie ~1,6 s — Reduced-Pass-Faser: sofortiger Sprung-Scroll).
 *
 * ## Drill-Acker (Bau-Welle, Emulator)
 * - >200-Eintrag-Verlauf: Suchbegriff im ÄLTESTEN Eintrag trifft (Filter-
 *   Verband über den Disk-Restore, nie am 200er-chatEvents-Cap hängend).
 * - Treffer im ZITAT einer Antwort-Bubble springt zur Bubble (mitgesucht).
 * - Kein Treffer → „Keine Treffer“-Zeile; Cap 50 Treffer je Anzeige (Scan
 *   läuft im Vollbestand).
 * - TalkBack: Zeit + Label + Fenster-Ausschnitt je Zeile; Ember-Highlight ist
 *   Rand + Text-Verband (nie nur Farbe).
 */
@Composable
private fun ConversationSearchResults(
    matches: List<ChatSearchRules.SearchMatch>,
    agentName: String?,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            ChatSearchRules.countLabel(matches.size),
            style = MaterialTheme.typography.labelSmall,
            color = FlipsiColors.TextSecondary,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
        )
        if (matches.isEmpty()) {
            Text(
                stringResource(R.string.chat_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn {
                items(matches, key = { it.messageId }) { match ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(match.messageId) }
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            match.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = FlipsiColors.TextSecondary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ChatQuoteRules.quoteLabel(match.fromAgent, agentName),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (match.fromAgent) FlipsiColors.Ember else FlipsiColors.LineStrong,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            match.previewText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FlipsiColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}