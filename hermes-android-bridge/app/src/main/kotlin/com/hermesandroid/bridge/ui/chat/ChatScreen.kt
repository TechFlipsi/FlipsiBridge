@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.chat.CallHistoryPrefs
import com.hermesandroid.bridge.chat.ChatHistoryStore
import com.hermesandroid.bridge.chat.HistorySeal  // P24: SealBreach-Fang (Restore/Backfill)
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.client.CallRules
import com.hermesandroid.bridge.client.ChatBackfillRules
import com.hermesandroid.bridge.client.ChatStatusRank
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.media.MediaProtocol
import com.hermesandroid.bridge.media.VoiceMemoRecorder
import com.hermesandroid.bridge.security.CapabilityGate
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.call.CallActiveStrip
import com.hermesandroid.bridge.ui.call.CallRingingOverlay
import com.hermesandroid.bridge.ui.call.CallSpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
 * (RelayClient.sendChat). Liefert das Relay keine Agenten, zeigt die Liste
 * den ehrlichen Leerzustand (HIG-W19: kein Demo-Fallback mehr).
 */
@Composable
fun ChatScreen(
    padding: PaddingValues,
    // W16 (Fixliste 34): onOpenSettings-Thread ENTFERNT — der Herz-Tap öffnet
    // DIREKT das Unterstützen-Sheet im Chat (ConversationBody), kein Umweg
    // Einstellungen.
    // W15 (Sir-Fixliste 9): optionaler Öffnen-Wunsch aus dem Dashboard —
    // ein Tap auf einen Agenten-Slot startet hier DIREKT in der Conversation
    // dieses Agenten (statt der Liste). Einmalig: der Konsum-Callback räumt
    // den Pending-Merker in BridgeScreen ab, ein späterer Chat-Tab-Besuch
    // landet wieder auf der Agenten-Liste.
    openAgentId: String? = null,
    onOpenedAgent: () -> Unit = {},
    // W16 (Sir-Fixliste 15): „Conversation offen“ nach oben melden —
    // BridgeScreen versteckt die untere NavigationBar, solange eine offene
    // Conversation den Chat beansprucht (Platz für Bubbles + Composer), und
    // holt sie beim Verlassen (Liste/Tab-Wechsel) sofort zurück.
    onConversationOpen: (Boolean) -> Unit = {},
) {
    // PH2-MERGE: echte Agenten-Liste vom Relay; leer = Demo-Fallback.
    val relayAgents by RelayClient.agents.collectAsState()
    val chatEvents by RelayClient.chatEvents.collectAsState()
    // B5 (Multi-Gerät): Sync-Echo fremder eigener Nachrichten + Anmelde-Tor-Frame.
    val chatSyncEvents by RelayClient.chatSyncEvents.collectAsState()
    // B6 (Multi-Gerät): Backfill-Verband (dezent, nicht blockierend).
    val backfillSyncing by RelayClient.backfillSyncing.collectAsState()
    val backfillEpochs by RelayClient.backfillEpochs.collectAsState()
    val statusSnapshot by RelayClient.statusSnapshot.collectAsState()
    // PH2-T10: Zustell-/Lese-Status je msg_id + Arbeitsstatus je Agent.
    val chatStatus by RelayClient.chatStatus.collectAsState()
    val agentWorking by RelayClient.agentWorking.collectAsState()
    // W19 (17.11.26): Gateway-Modell-Katalog je agent (agent.models-Frames) —
    // die Sendeklammer (submitQuoted) bindet die Auswahl an die Server-Liste:
    // sendbar ist ALLEIN ein exakter Listen-Wert → getippte Namen (stale
    // Prefs alter Versionen) erreichen den Relay-Weg technisch nicht mehr.
    val agentModelsForSend by RelayClient.agentModels.collectAsState()
    // MEDIA-T1: empfangene Agenten-Medien + Upload-Fortschritt je media_id.
    val chatMedia by RelayClient.chatMediaEvents.collectAsState()
    val mediaUploads by RelayClient.mediaUploads.collectAsState()
    // MEDIA-T1: Picker-Lese-Scope (URI-Bytes auf IO, UI-Update auf Main).
    val context = LocalContext.current
    val mediaScope = rememberCoroutineScope()

    // HIG-W19 (Demo-Befund): Demo-Fallback ENTFERNT — erfundene „Agent 1-3“-
    // Einträge und Beispiel-Verläufe lügen über den Kopplungsstand. Ohne
    // Relay-Agenten zeigt die Liste den ehrlichen Leerzustand (siehe
    // AgentListBody), der Verlauf startet leer.
    val agents = relayAgents.map { entry ->
        ChatAgent(
            id = entry.agentId.hashCode(),
            name = entry.displayName,
            online = entry.online,
            agentId = entry.agentId,
        )
    }

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
    // W15/W19: Verlauf startet leer — echte Nachrichten kommen aus dem Relay
    // (chatEvents/chatMedia-Einpflege unten). Kein Beispiel-Verlauf mehr.
    var messages by rememberSaveable(stateSaver = ChatMessageListSaver) { mutableStateOf(listOf<ChatMessage>()) }
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

    // ── B5/B6 (Multi-Gerät) — Konsum-/Prepend-Merker ─────────────────────────
    // B5: Sync-Echo-Konsum (eigene Merker-Kette — ein Echo und ein event können
    // je chatId+ts gleiche Schlüssel teilen? Nein: unterschiedliche ts; trotzdem
    // bewusst EIGENE Kette, der Loop-Schutz bleibt ablesbar).
    var consumedSyncKeys by rememberSaveable(stateSaver = ConsumedEventKeysSaver) {
        mutableStateOf(listOf<String>())
    }
    // B6: Prepend-Zähler je Agent-Hash — ConversationBody hält den Blickpunkt
    // (Index-Kompensation), statt ans Ende zu zerrn („Scroll-Position nicht
    // springen lassen"). Konsumiert je Zähreff, sonst stale (kein Saveable:
    // nach Rotation ist die Conversation ohnehin neu zusammengesetzt).
    var historyPrependCounts by remember { mutableStateOf(mapOf<Int, Int>()) }
    // B6: konsumierte Backfill-Epochen (Prepend je gelandeter Batch genau EINMAL).
    var backfillEpochsConsumed by remember { mutableStateOf(mapOf<String, Long>()) }

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

    // SIRS-02 (P2, Volltest 03.11.2026): Verlauf vom Disk-Store laden — der
    // Chat-Verlauf überlebt App-Restart/force-stop (vorher: „Noch keine
    // Nachrichten“ nach dem Neustart, Bubbles nur im rememberSaveable-Bundle).
    // Genau EINMAL je Prozess: der saveable Flag übersteht die Rotation — ein
    // erneutes Laden würde den In-Session-Verlauf (msgIdByLocalId-Chips,
    // frische Bubbles) durch den Disk-Stand ersetzen. Sichtbar erst hinter dem
    // AppLock-Gate (ChatScreen rendert nur nach Unlock).
    val historyContext = LocalContext.current
    var historyRestored by rememberSaveable { mutableStateOf(false) }
    // P24 (MERGE-LANE 18.11.): SealBreach-Fang — ein Siegel-Verstoß beim
    // Verlauf-Restore zeigt den FEHLERZUSTAND (Banner + Verlauf-löschen-Weg)
    // statt Coroutine-Crash. Saveable wie historyRestored (Konfig-Wechsel);
    // nach Prozess-Neustart läuft der Load erneut und meldet frisch.
    var historySealBreach by rememberSaveable { mutableStateOf(false) }
    // PH-6: dezenter Suchindex-Aufbau-Hinweis (kein Blockieren; Fenster-Deckel
    // in der Hinweis-Effekt-Schleife unten — nie ein Ewigzähler).
    var searchIndexBuilding by remember { mutableStateOf(false) }
    // W4-FIX-JAG2 (C1): auch der Status-Snapshot der restaurierten Einträge
    // ist saveable — der Saver-Verband der anderen Maps (B8/B7/J1). Vorher
    // nur `remember`: die Rotation zerstörte die Map, während
    // `historyRestored` (saveable) TRUE blieb → kein erneuter Disk-Load →
    // die restaurierten Bubbles downgradeten auf den Leer-Chip (✓✓ → ✓),
    // obwohl die Disk-Zwillinge in `messages` (saveable) übrig blieben.
    var restoredStatusById by rememberSaveable(stateSaver = RestoredStatusSaver) {
        mutableStateOf<Map<Long, String>>(emptyMap())
    }
    // HAKEN-FIX (2): persistenter High-Water je local_id (saveable, Rotation) —
    // der HÖCHSTE je beobachtete Stand jeder msg_id-Bindung. Füllt die
    // Lücke, die die Live-Map-Eviction (>500 msg_ids, z. B. Medien-Chunks)
    // reißt: chatStatus[msg_id] fehlt danach (null), der Chip bleibt trotzdem
    // auf dem höchsten Stand (Fusion unten, Nie-unter-Höchststand).
    var statusHighWaterByLocalId by rememberSaveable(stateSaver = RestoredStatusSaver) {
        mutableStateOf<Map<Long, String>>(emptyMap())
    }
    // HAKEN-FIX (2): High-Water-Sammler — je chatStatus-Änderung (und je neuer
    // msg_id-Bindung, egal in welcher Reihenfolge) werden ALLE Bindungen
    // nachgezogen (nicht nur gerenderte: die Conversation kann zu sein, wenn
    // der Read-Frame eintrifft). Fuse = rang-monotone Anhebung, failed
    // (Sweep) blockt wie live, read heilt.
    LaunchedEffect(chatStatus, msgIdByLocalId) {
        if (msgIdByLocalId.isEmpty()) return@LaunchedEffect
        var raised: MutableMap<Long, String>? = null
        for ((localId, msgId) in msgIdByLocalId) {
            val live = chatStatus[msgId] ?: continue
            val current = statusHighWaterByLocalId[localId]
            val fused = ChatStatusRank.fuse(current, live)
            if (fused != null && fused != current) {
                (raised ?: HashMap<Long, String>()).also { it[localId] = fused }
                    .let { raised = it }
            }
        }
        raised?.let { statusHighWaterByLocalId = statusHighWaterByLocalId + it }
    }
    LaunchedEffect(historyRestored) {
        if (historyRestored) return@LaunchedEffect
        // P24 (MERGE-LANE 18.11.): SealBreach-FANG — loadAll/loadStatuses sind
        // fail-closed (Verlauf-Laden verweigert laut HistorySeal.readGuard
        // ehrlich LAUT statt Stilltode). Der Fang hält den Screen lebendig:
        // FEHLERZUSTAND (Banner + Verlauf-löschen-Weg) statt Coroutine-Crash,
        // der Live-Verband läuft verlaufslos weiter. historyRestored wird
        // trotzdem gesetzt → kein Reload-Loop je Rekombposition.
        val (entries, statusByMsgId) = try {
            // HAKEN-FIX (1): der Side-Car (Platte = höchster bekannter Chip-Stand
            // je msg_id) wandert in denselben IO-Sprung wie der Verlaus-Load.
            withContext(Dispatchers.IO) {
                val root = ChatHistoryStore.root(historyContext.applicationContext)
                ChatHistoryStore.loadAll(root) to ChatHistoryStore.loadStatuses(root)
            }
        } catch (breach: HistorySeal.SealBreach) {
            historySealBreach = true
            null to emptyMap()
        }
        if (entries != null && entries.isNotEmpty()) {
            var nextId = RESTORED_ID_BASE
            val restored = ArrayList<ChatMessage>(entries.size)
            val statusById = HashMap<Long, String>()
            // HAKEN-FIX (1): msg_id-Bindungen der Disk-Zwillinge — der Restore
            // baut die Platte↔Broker-Kette wieder auf: restaurierte Chips
            // hören ab JETZT auf dieselben chat.status-Frames wie
            // Live-Bubbles (msgIdByLocalId-Verband; Spool-Re-Flush nach
            // Reconnect trifft sie live).
            val restoredMsgIdByLocalId = HashMap<Long, String>()
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
                // W16.3 (F3, Literal-Falle): die Platte trägt den Bubble-Text
                // von Media-Verweisen mit PERSIST-Sprache-Label („Foto“/„Video“)
                // — der Restore baut die Zeile aus mediaKindRes je LOCALE neu,
                // damit ein Sprachwechsel nicht den eingefrorenen Literal zeigt
                // (ohne Persist-Metadaten: ehrlich der Disk-Text).
                val mediaLabelText = entry.mediaKind?.let { kind ->
                    entry.mediaName?.let { name ->
                        val label = historyContext.getString(mediaKindRes(kind))
                        "[$label] $name" +
                            (entry.mediaSize?.let { " (${MediaProtocol.formatBytes(it)})" } ?: "")
                    }
                }
                restored.add(
                    ChatMessage(
                        id = id,
                        agentId = entry.agentId.hashCode(),
                        fromAgent = entry.fromAgent,
                        text = restoredWire?.reply ?: mediaLabelText ?: entry.text,
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
                        // W22 A2 (P5): Call-Einträge (kind=call) restaurieren
                        // als Anruf-Bubble/Verpasst-Zeile — Anzeige baut das
                        // Widget je Locale aus den Feldern.
                        call = entry.callInfo?.let { info ->
                            ChatCallInfo(
                                direction = info.direction,
                                missed = info.missed,
                                durationSec = info.durationSec,
                            )
                        },
                    ),
                )
                restoredKeys.add(
                    // W22 A2: Call-Einträge dedupen über ihre Call-Felder —
                    // leerer Text würde zwei Anrufe derselben Minute zu
                    // Zwillingen machen (ein Verlauf fände den anderen weg).
                    ChatTimelineMerge.dedupeKey(
                        entry.agentId.hashCode(), entry.fromAgent,
                        callDedupeText(entry.text, entry.callInfo), entry.time,
                    ),
                )
                // HAKEN-FIX (1): Chip des Zwilling-Senders ⊔ Side-Car-Höchst-
                // stand (Platte trägt den höchsten bekannten Stand — der
                // reine Sendezeit-Snapshot (null = 1 Haken) allein war der
                // Neustart-Kipp). failed-Schranke des Side-Cars bleibt,
                // read heilt (Parität: ChatStatusRank).
                val entryMsgId = entry.msgId?.takeIf { it.isNotBlank() }
                entryMsgId?.let { restoredMsgIdByLocalId[id] = it }
                val diskChip = ChatStatusRank.fuse(
                    entry.status,
                    entryMsgId?.let { msgId -> statusByMsgId[msgId] },
                )
                diskChip?.let { statusById[id] = it }
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
                        // W22 A2: Call-Live-Zeilen dedupen über ihre Call-
                        // Felder (Parität zum Platte-Schlüssel oben).
                        ChatTimelineMerge.dedupeKey(
                            live.agentId, live.fromAgent,
                            callDedupeText(live.text, live.call), live.time,
                        )
                    }
                    liveKey !in restoredKeys
                }
            } else {
                restored
            }
            restoredStatusById = statusById
            // HAKEN-FIX (1): die restaurierten Bindungen schalten die Chip-
            // Live-Kette wieder an (id-Raum getrennt: live < RESTORED_ID_BASE
            // ≤ restored — keine Kollision im msgIdByLocalId-Verband).
            if (restoredMsgIdByLocalId.isNotEmpty()) {
                msgIdByLocalId = msgIdByLocalId + restoredMsgIdByLocalId
            }
        }
        historyRestored = true
    }

    // PH-6 (MERGE-LANE 18.11.): Volltext-Suchindex primen (Lazy-Hook —
    // Hintergrund-IO-Executor, NIE blockierend, noop wenn schon geprimt) und
    // den dezenten Aufbau-Hinweis führen: RAM-Index leer, WÄHREND Verlauf-
    // Dateien existieren (und kein Siegel-Verstoß die Meldung trägt) = Aufbau
    // läuft. EINE Zeile, kein Blockieren, Fenster-Deckel ~10 s (kein Ewigzähler;
    // leeres Verlauf zeigt nie einen Aufbau-Hinweis).
    LaunchedEffect(historyRestored) {
        if (!historyRestored) return@LaunchedEffect
        ChatHistoryStore.ensureSearchIndexAsync(ChatHistoryStore.root(historyContext.applicationContext))
        val indexRoot = ChatHistoryStore.root(historyContext.applicationContext)
        var ticks = 0
        while (ticks < 6) {
            val files = indexRoot.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            val building = !historySealBreach &&
                files?.isNotEmpty() == true &&
                ChatHistoryStore.searchIndexEntryCount() == 0
            searchIndexBuilding = building && ticks < 5
            if (!searchIndexBuilding) break
            kotlinx.coroutines.delay(2_000L)
            ticks++
        }
        searchIndexBuilding = false
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

    // ── B5 (Multi-Gerät): Sync-Echo-Übernahme ────────────────────────────────
    // Ein-Blase-Verband wie chatEvents, aber fromAgent = FALSE (die Nachricht
    // stammt vom NUTZER auf einem anderen Gerät) + Ursprungs-msg_id-Bindung —
    // die Chips laufen über denselben Broker-Kanal (chat.status trifft die
    // msg_id auf jedem Gerät). Loop-Schutz liegt im RelayClient (Ingest-only):
    // hier gibt es bewusst KEINEN Send-Pfad und KEIN Vorlesen.
    LaunchedEffect(chatSyncEvents, historyRestored) {
        if (!historyRestored) return@LaunchedEffect
        val consumed = consumedSyncKeys.toHashSet()
        val fresh = chatSyncEvents.filterNot { event ->
            ChatTimelineMerge.eventKey(event.chatId, event.ts) in consumed
        }
        if (fresh.isEmpty()) return@LaunchedEffect
        val restoredTwins = messages.filter { it.id >= RESTORED_ID_BASE }
        val unseen = ChatTimelineMerge.freshUnseenIngest(
            restoredTwins,
            fresh,
            agentIdOf = { it.agentId.hashCode() },
            textOf = { it.text },
            timeOf = { formatTimestamp(it.ts) },
            fromAgentOf = { false },
        )
        if (unseen.isNotEmpty()) {
            var nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
            val appended = unseen.map { event ->
                ChatMessage(
                    id = nextId++,
                    agentId = event.agentId.hashCode(),
                    fromAgent = false,
                    text = event.text,
                    time = formatTimestamp(event.ts),
                    date = ChatDateRules.dayKey(event.ts),
                )
            }
            messages = messages + appended
            // HAKEN-Kette (B5): die Echo-Blase trägt die Ursprungs-msg_id —
            // chat.status-/Snapshot-Frames des Brokers heben ihren Chip auf
            // JEDEM Gerät monotone an (ChatStatusRank-Kette).
            msgIdByLocalId = msgIdByLocalId + unseen.zip(appended)
                .mapNotNull { (event, msg) ->
                    event.msgId?.takeIf(String::isNotBlank)?.let { msg.id to it }
                }
        }
        consumedSyncKeys = (consumedSyncKeys + fresh.map {
            ChatTimelineMerge.eventKey(it.chatId, it.ts)
        }).takeLast(MAX_CONSUMED_EVENT_KEYS)
    }

    // ── B6 (Multi-Gerät): Status-Snapshot-Anhebung ───────────────────────────
    // ids-only msg_id→Status-Snapshot hebt die Bindungen (msgIdByLocalId) auf
    // den HÖCHSTEN bekannten Stand: restoredStatusById ⊔ statusHighWater über
    // ChatStatusRank.fuse — die W20-Haken-Fusion fällt NIE zurück.
    LaunchedEffect(statusSnapshot, msgIdByLocalId) {
        if (statusSnapshot.isEmpty() || msgIdByLocalId.isEmpty()) return@LaunchedEffect
        val highWater = ChatBackfillRules.raiseBindingStatuses(
            statusHighWaterByLocalId, msgIdByLocalId, statusSnapshot,
        )
        if (highWater != statusHighWaterByLocalId) {
            statusHighWaterByLocalId = highWater
        }
        val restored = ChatBackfillRules.raiseBindingStatuses(
            restoredStatusById, msgIdByLocalId, statusSnapshot,
        )
        if (restored != restoredStatusById) {
            restoredStatusById = restored
        }
    }

    // ── B6 (Multi-Gerät): Backfill-Verlauf-Prepend ───────────────────────────
    // chat.backfill.enc ist in der Platte CHRONOLOGISCH einsortiert (Chat-
    // HistoryStore.mergeBackfill) — der Screen liest den Disk-Stand je gelandeter
    // Batch EINMAL (Consumed-Epochen) und setzt die frischen Zeilen VOR den
    // bestehenden Stand. Dedupe: msgIdByLocalId-Bindungen + Schlüssel-Filter
    // (Parität zum Disk-Restore) — keine Doppel-Bubble, kein Verlust der Live-Bubbles.
    LaunchedEffect(backfillEpochs) {
        for ((agentId, epoch) in backfillEpochs) {
            if ((backfillEpochsConsumed[agentId] ?: 0L) >= epoch) continue
            val agentHash = agentId.hashCode()
            val root = ChatHistoryStore.root(context.applicationContext)
            // P24 (MERGE-LANE 18.11.): SealBreach-Fang — der Backfill-Load
            // wirft bei Siegel-Verstoß ehrlich laut; der Fang konsumiert die
            // Batch-Epoch (KEIN Retry-Loop — jede Wiederholung wirft denselben
            // Verstoß erneut), zeigt den Fehlerzustand, der Screen läuft weiter.
            val entries: List<ChatHistoryStore.Entry>? = try {
                withContext(Dispatchers.IO) {
                    ChatHistoryStore.load(root, agentId)
                }
            } catch (breach: HistorySeal.SealBreach) {
                historySealBreach = true
                backfillEpochsConsumed = backfillEpochsConsumed + (agentId to epoch)
                null
            }
            if (entries == null) continue
            val seenKeys = messages.map {
                ChatTimelineMerge.dedupeKey(it.agentId, it.fromAgent, it.text, it.time)
            }.toHashSet()
            var nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
            val prepended = ArrayList<ChatMessage>()
            val bindings = HashMap<Long, String>()
            for (entry in entries) {
                val key = ChatTimelineMerge.dedupeKey(agentHash, entry.fromAgent, entry.text, entry.time)
                if (key in seenKeys) continue
                val id = nextId++
                prepended += ChatMessage(
                    id = id,
                    agentId = agentHash,
                    fromAgent = entry.fromAgent,
                    text = entry.text,
                    time = entry.time,
                    // B6-Prepend trägt den Tages-Schlüssel — ein Backfill über
                    // Tag-Grenzen rendert korrekte Datum-Chips im Verlauf.
                    date = ChatDateRules.dayKey(entry.seq),
                )
                seenKeys += key
                entry.msgId?.takeIf(String::isNotBlank)?.let { bindings[id] = it }
            }
            backfillEpochsConsumed = backfillEpochsConsumed + (agentId to epoch)
            if (prepended.isEmpty()) continue
            messages = prepended + messages
            historyPrependCounts = historyPrependCounts +
                (agentHash to (historyPrependCounts[agentHash] ?: 0) + prepended.size)
            msgIdByLocalId = msgIdByLocalId + bindings
        }
    }

    // PH-3 Welle 2: rot-Banner (Fingerprint-ABWEICHUNG / Session-Reset).
    val e2eBanner by RelayClient.e2eBanner.collectAsState()
    // W18-1a: verdecktes FP-Abgleich-Ergebnis je Agent — Statuszeile unter
    // dem Chat-Kopf (keine Chat-Bubble; Verarbeitung + Status im RelayClient).
    val fpCheckResults by RelayClient.fpCheckResults.collectAsState()

    // ── W4-B (Zitieren): Zitieren-Verband ────────────────────────────────────

    // Aktiver Antwort-Verweis (Snapshot der Bubble, die der Longpress gewählt
    // hat). null = kein Zitier-Modus. Kein saveable: nach einer Rotation ist
    // der Zitier-Modus beendet — konservativ, nie ein Zitat an die falsche
    // Bubble kleben. Rückfold der restaurierten Bubbles unten (splitWireText).
    var replyQuote by remember { mutableStateOf<ChatQuote?>(null) }

    // W15 (Sir-Fixliste 9): Dashboard-Tap auf einen Agenten-Slot → direkt
    // die Conversation dieses Agenten öffnen (echte Relay-IDs matchen; der
    // Demo-Fallback hat leere agentId und matcht bewusst nie). onOpenedAgent
    // räumt den Pending-Merker ab → der nächste Tab-Besuch startet wieder
    // normal auf der Liste. Eingabe/Zitat starten je Conversation neu
    // (gleicher Verband wie der normale Listen-Tap).
    // W15c (KLEIN B, Jäger): onOpenedAgent NUR bei echtem Match — vorher
    // verbrannte der Aufruf den Pending-Merker AUCH ohne Treffer, der Tap
    // versprach also eine Conversation und lief ins Leere. Ohne Match bleibt
    // der Merker bewusst stehen (der nächste Tab-Besuch startet dann MIT
    // der Öffnung statt auf der Liste — bewusst, kein Verlust des Taps).
    LaunchedEffect(openAgentId) {
        if (openAgentId != null) {
            agents.firstOrNull { it.agentId == openAgentId }?.let { match ->
                selectedAgentId = match.id
                inputText = ""
                replyQuote = null
                onOpenedAgent()
            }
        }
    }

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
                // W19 (17.11.26): sendbar ist NUR ein Wert der Server-Liste
                // (agent.models) — Freitext-Feld ist AUS (Sir), stale getippte
                // App-Prefs werden hier final abgefangen → null = Auto, der
                // Gateway entscheidet. Kein Modell-Name hat den Draht ohne
                // Server-Vertrag (Sir-Generik-Regel: alles dynamisch).
                model = sendableModel(composerModel, agentModelsForSend[agent.agentId]),
                // B1 (Flagship-Review 17.11.): EFFORT_OFF (bewusstes „aus“) geht
                // als KEIN effort-Feld auf den Draht — der Gateway-Standard
                // bleibt; nur low/high/max sind App-Wünsche.
                effort = sendableEffort(composerEffort),
            ) { ok, error, msgId ->
                if (!ok) {
                    failedLocalIds = failedLocalIds + nextId
                    sendErrors = sendErrors + (
                        agent.agentId to
                            context.getString(
                                R.string.chat_send_failed,
                                error ?: context.getString(R.string.chat_send_failed_unknown),
                            )
                        )
                } else if (msgId != null) {
                    msgIdByLocalId = msgIdByLocalId + (nextId to msgId)
                }
            }
        }
        return nextId
    }

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
    // W16 P30b (Fixliste 30b): Hold-to-Record-Verband — B2s VoiceHoldRecorder-
    // Fassade (0,5 s-Kante je GESTE und RECORDER an derselben Zahl) + Wisch-
    // Abbruch-Marke der Fahne. Die TAP-Fallback-Sessionen (Assist-Start,
    // Berechtigungs-Fortsetzung) laufen unangetastet über voiceRecorder (B1).
    val voiceHoldRecorder = remember { VoiceHoldRecorder(context) }
    var voiceHoldCancelling by remember { mutableStateOf(false) }
    // Aufnahme beim Verlassen der Conversation bewusst abbrechen (kein
    // Zombie-Recorder im Hintergrund, kein Mikro-Claim über den Screen weg).
    // W16 B2 (Fixliste 31): Bubble-Wiedergabe miträumen — kein unsichtbarer
    // Player, der über den Rückweg zur Agenten-Liste weiterlaufen kann.
    DisposableEffect(selectedAgentId) {
        onDispose {
            if (voiceRecorder.isRecording) {
                voiceRecorder.cancel()
                voiceRecording = false
            }
            // W16 P30b: HOLD-Aufnahme miträumen (die Geste kann nicht mehr
            // releasen, sobald die Conversation weg ist).
            if (voiceHoldRecorder.isRecording) {
                voiceHoldRecorder.cancelNow()
                voiceRecording = false
            }
            voiceHoldCancelling = false
            ChatVoicePlayback.stop()
        }
    }

    /**
     * MEDIA-T1: Medium als lokale Bubble anhängen + verschlüsselten Upload
     * anstoßen (RelayClient.sendMedia, fail-closed ohne E2E-Session).
     */
    fun submitMedia(agent: ChatAgent, kind: String, name: String, mime: String, bytes: ByteArray) {
        val sentAtMs = System.currentTimeMillis()
        val nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        // W15: Label über Ressource (DE/EN) — Gate-Wrap-Context löst in der App-Sprache.
        val label = context.getString(mediaKindRes(kind))
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
            // W16.3 (F2): der ANHÄNGEN-ts (sentAtMs, Z. 585/601) reist mit —
            // RelayClient persistiert den Disk-Zwilling mit DEMSELBEN ts
            // (C6-Verband: kein Minuten-Roll zwischen Live-Bubble und Disk).
            RelayClient.sendMedia(
                agent.agentId, kind, name, mime, bytes,
                sentAtMs = sentAtMs,
            ) { ok, error, mediaId, msgId ->
                if (!ok) {
                    failedLocalIds = failedLocalIds + nextId
                    // HIG-W19 (Writing): Ressource + %-Parameter wie
                    // chat_send_failed — kein „Media:"-Präfix, kein Literal.
                    sendErrors = sendErrors + (
                        agent.agentId to context.getString(
                            R.string.chat_upload_failed_desc,
                            error ?: context.getString(R.string.chat_send_failed_unknown),
                        )
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
                        // stellt → ✓✓ Gelesen (grün)" IN der Media-Bubble
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
                    sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_unreadable))
                } else if (bytes.size.toLong() > MediaProtocol.MAX_BYTES) {
                    sendErrors = sendErrors + (
                        agent.agentId to context.getString(
                            R.string.chat_media_too_large,
                            MediaProtocol.formatBytes(MediaProtocol.MAX_BYTES),
                        )
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
            sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_permission_missing))
        }
    }

    // ── W16 P30b: Hold-to-Record (Fixliste 30b) — B2-APIs am runden Knopf ─────

    /**
     * Fertige AAC-Datei als Voice-Medium senden — der GEMEINSAME Sendeweg des
     * Hold-Pfads (P30b) und des Tap-Fallbacks (B1): identische Bubble, identischer
     * Upload, identische Fehlerbehandlung.
     */
    fun submitVoiceFile(agent: ChatAgent, file: File) {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_generic_error))
            return
        }
        submitMedia(agent, "voice", file.name, "audio/mp4", bytes)
    }

    /** Voice-Record starten/stopen — Stopp sendet die fertige AAC-Datei. */
    fun toggleVoice() {
        val agent = openAgent() ?: return
        if (voiceRecorder.isRecording) {
            val file = voiceRecorder.stop()
            voiceRecording = false
            if (file == null) {
                sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_too_short))
                return
            }
            submitVoiceFile(agent, file)
        } else if (voiceHoldRecorder.isRecording) {
            // W16 P30b: HOLD-Aufnahme OHNE Release-Verband (Geste von einem
            // System-Dialog/Call abgebrochen) — der Tap-Fallback beendet sie
            // und sendet (derselbe Sendeweg wie der Geste-Release).
            val file = voiceHoldRecorder.finish(VoiceHoldOutcome.SEND)
            voiceHoldCancelling = false
            voiceRecording = false
            if (file == null) {
                sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_too_short))
                return
            }
            submitVoiceFile(agent, file)
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                runCatching { voiceRecorder.start() }
                    .onSuccess { voiceRecording = true }
                    .onFailure {
                        sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_record_failed))
                    }
            } else {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // ── W16 P30b: Hold-Funktionen — onStart/onEnd/Abbruch (Fixliste 30b) ──────

    /**
     * P30b onStart (DRÜCKEN am Mikro-Knopf): Aufnahme auf der VoiceHoldRecorder-
     * Fassade starten. Rückgabe = Press übernommen:
     * - true → Geste hat die Sequenz (Aufnahme läuft bzw. der START_DENIED-Pfad
     *   öffnete den Berechtigungs-Dialog) — der Klick des kurzen Taps ist still.
     * - false → Press gehört B1s Tap-Fallback (Text im Feld = Send, Aufnahme
     *   läuft = Stop+Send, Recorder-Fail = Toggle-Neuversuch).
     */
    fun startVoiceHold(): Boolean {
        val agent = openAgent() ?: return false
        // Text im Feld = der Knopf ist gerade SEND (Icon-Morph) — kein Voice-Hold.
        if (inputText.isNotBlank()) return false
        // EINE Audio-Spur: Fallback-Session läuft → Geste verzichtet (der Tap
        // des Releases beendet sie über toggleVoice).
        if (voiceRecording) return false
        voiceHoldCancelling = false
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // START_DENIED-Pfad (B2-Vertrag): der Host-Launcher ist an der
            // Reihe. true = Press übernommen, damit der Klick nicht parallel
            // einen zweiten Berechtigungs-Request nachschiebt.
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return true
        }
        val started = voiceHoldRecorder.start()
        if (!started) {
            // B2-Vertrag: START_DENIED (Recorder-Fail) — Release-Aktion entfällt;
            // der Click-Fallback darf B1s Toggle einen Neuversuch geben.
            sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_record_failed))
            return false
        }
        voiceRecording = true
        return true
    }

    /**
     * P30b onEnd (LOSLASSEN): SEND → stop + senden; TOO_SHORT (< 0,5 s) →
     * verwerfen + Too-Short-Hinweis; SWIPE_CANCELLED/START_DENIED → verwerfen,
     * still. Läuft keine Geste-Session (Press verweigert bzw. Fallback-Aufnahme),
     * greift hier nichts — der Click hat den Pfad bereits bedient.
     */
    fun endVoiceHold(outcome: VoiceHoldOutcome, holdMs: Long) {
        // Wisch-Marke räumen — die Fahne rollt zurück, egal wie der Release ausgeht.
        voiceHoldCancelling = false
        if (!voiceHoldRecorder.isRecording) return
        val agent = openAgent()
        val file = voiceHoldRecorder.finish(outcome)
        voiceRecording = false
        if (agent == null) return
        when (outcome) {
            VoiceHoldOutcome.SEND -> if (file == null) {
                sendErrors = sendErrors + (agent.agentId to context.getString(R.string.chat_media_too_short))
            } else {
                submitVoiceFile(agent, file)
            }
            VoiceHoldOutcome.TOO_SHORT -> sendErrors = sendErrors +
                (agent.agentId to context.getString(R.string.chat_media_too_short))
            // SWIPE_CANCELLED / START_DENIED: still verwerfen (kein Send-Fehler).
            else -> {}
        }
        // [holdMs] = Geste-Haltezeit (Druck→Release); die Verwerfen-Kante sitzt
        // an VoiceHoldRules.MIN_HOLD_MS — je GESTE und RECORDER dieselbe Zahl.
    }

    /** P30b: harter Abbruch der Aufnahme (Fahne-X /Conversation-Verlassen). */
    fun cancelVoiceRecording() {
        voiceHoldCancelling = false
        if (voiceHoldRecorder.isRecording) {
            voiceHoldRecorder.cancelNow()
            voiceRecording = false
        }
        if (voiceRecorder.isRecording) {
            voiceRecorder.cancel()
            voiceRecording = false
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
    // W16.3 (F4): W16-Jäger-Befund — Unit-Key verbrannte den Merker im
    // ersten Frame, BEVOR Route-2/openAgentId selectedAgentId gesetzt hatte
    // → toggleVoice lief ins Leere (openAgent() = null) und der Wusch war
    // weg. Jetzt key auf Conversation + Agentenstand: solange openAgent()
    // null liefert, bleibt der Merker UNGEBRUCHEN.
    LaunchedEffect(selectedAgentId, agents) {
        if (com.hermesandroid.bridge.AssistLaunch.pendingVoice && openAgent() != null) {
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
            // W15: Label über Ressource (DE/EN) — im Effekt-Kontext kein
            // stringResource möglich, context.getString am Gate-Wrap.
            val label = context.getString(mediaKindRes(event.kind))
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
    // W16 (Fixliste 15): Offen-Status vorzeichenfest ableiten (AttachSheet-
    // Verband, B7-LIVE) und nach JEDER Composition melden. SideEffect + 
    // structural-equality-Write: gleicher Wert = keine Recompose-Kaskaden,
    // ein echter Wechsel (open/close) flippt die NavigationBar oben.
    val conversationOpen = AttachSheetRules.isConversationOpen(
        selectedAgentId,
        agents.map { it.id },
    )
    SideEffect {
        onConversationOpen(conversationOpen)
    }

    // ═══ W21 ANRUF (P4-P6 + G11/G12): Call-Mount ══════════════════════════════
    // Ankommender Anruf = Vollbild-Karte (Overlay über Liste UND Conversation),
    // laufender Anruf = Status-Zeile (Strip) über der Route (WA-Muster). Die
    // Session lebt in RelayClient.callSession; die Verbände hier sind nur
    // Aufnahme (P5 half-duplex) + Screenshot-Fluss (G12, §7.2) + Bindung.
    // Flat-Indent des bestehenden if/else bewusst unangetastet (nur Umklammerung).
    val callSession by RelayClient.callSession.collectAsState()
    val callAlias = callSession?.let { cs ->
        CallRules.displayName(
            relayAgents.firstOrNull { it.agentId == cs.agentId }?.displayName,
            cs.agentId,
        )
    }
    val callSteerCapabilityOn = CapabilityGate.isEnabled(CallRules.CAP_INTERACTION)
    // Eigener Recorder-Namespace neben dem Composer (ComposerUi bleibt unangetastet):
    // Call-Turns sind half-duplex-Verband (§3.1), kein Composer-Verwechslungsweg.
    val callRecorder = remember { VoiceMemoRecorder(context) }
    var callTalkRecording by remember { mutableStateOf(false) }
    var callInfoLine by remember { mutableStateOf<String?>(null) }
    var screengrabConfirmOpen by remember { mutableStateOf(false) }
    var screengrabBusy by remember { mutableStateOf(false) }
    var callDurationTick by remember { mutableStateOf(0L) }

    /** P5-Turn aufnehmen: Barge-in stoppt die Agent-Ausgabe (§3.1). */
    fun callTalkStart() {
        val cs = callSession ?: return
        if (cs.phase != CallRules.Phase.ACTIVE || callTalkRecording) return
        ChatSpeechSpeaker.stopAll()  // Barge-in: Nutzerrede stoppt TTS (§3.1)
        val micGranted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            callInfoLine = context.getString(R.string.chat_media_permission_missing)
            return
        }
        if (runCatching { callRecorder.start() }.isFailure) {
            callInfoLine = context.getString(R.string.chat_media_record_failed)
            return
        }
        callTalkRecording = true
        callInfoLine = null
    }

    /** P5-Turn senden (loslassen) oder verwerfen — Chunk-Cap per Auto-Stopp. */
    fun callTalkStop(cancelled: Boolean = false) {
        if (!callTalkRecording) return
        callTalkRecording = false
        if (cancelled) {
            // cancel() löscht die Teilaufnahme selbst (Recorder-Verband).
            runCatching { callRecorder.cancel() }
            return
        }
        val file = runCatching { callRecorder.stop() }.getOrNull()
        val cs = callSession
        if (file == null || cs == null || cs.phase != CallRules.Phase.ACTIVE) {
            file?.delete()
            return
        }
        mediaScope.launch(Dispatchers.IO) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            file.delete()  // Call-Turn lebt NUR als E2E-Chunk (keine Platte/Lokalkopie)
            if (bytes == null) {
                callInfoLine = context.getString(R.string.chat_media_generic_error)
                return@launch
            }
            RelayClient.sendCallAudio(cs.agentId, bytes) { ok, error ->
                if (!ok) {
                    callInfoLine = context.getString(R.string.call_screengrab_talk_failed) +
                        (error ?: context.getString(R.string.chat_send_failed_unknown))
                }
            }
        }
    }

    /** §7.2 Privacy-Kette: EXIF/Geo-Inherent-Strip (Neukompression) + Downscale ≤ 1400 px. */
    fun screengrabDownscaleBytes(b64: String): ByteArray? = runCatching {
        val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return@runCatching null
        val edge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (edge > CallRules.SCREENGRAB_MAX_EDGE_PX) {
            val factor = CallRules.SCREENGRAB_MAX_EDGE_PX.toFloat() / edge
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            null
        }
        val out = java.io.ByteArrayOutputStream()
        (scaled ?: bitmap).compress(
            android.graphics.Bitmap.CompressFormat.JPEG,
            CallRules.SCREENGRAB_JPEG_QUALITY,
            out,
        )
        bitmap.recycle()
        scaled?.recycle()
        out.toByteArray()
    }.getOrNull()

    fun callScreengrabExecute() {
        val cs = callSession ?: return
        // J1-FIX: G12-Konsent ist call-scoped — nur im LAUFENDEN Call (ENDED/
        // Zombie-Session kann keinen Screengrab mehr auslösen).
        if (cs.phase != CallRules.Phase.ACTIVE) return
        screengrabBusy = true
        mediaScope.launch {
            val shot = ActionExecutor.takeScreenshot()
            if (!shot.success) {
                screengrabBusy = false
                callInfoLine = context.getString(R.string.call_screengrab_failed_reason, shot.message.take(80))
                return@launch
            }
            val b64 = (shot.data as? Map<*, *>)?.get("image") as? String
            // Downscale/Neukompression auf IO (Bitmap-Arbeit gehört nicht aufs Main).
            val bytes = b64?.let { withContext(Dispatchers.IO) { screengrabDownscaleBytes(it) } }
            if (bytes == null) {
                screengrabBusy = false
                // SR-Bedarf: call_screengrab_failed
                callInfoLine = context.getString(R.string.call_screengrab_failed)
                return@launch
            }
            // §7.2 Privacy-Kette: Neukompression (EXIF/Geo strippt inherent),
            // Downscale ≤ 1400 px, KEIN MediaStore, KEINE Lokalkopie.
            RelayClient.sendScreengrab(
                cs.agentId,
                CallRules.screengrabName(cs.callId, System.currentTimeMillis()),
                "image/jpeg",
                bytes,
            ) { ok, error ->
                screengrabBusy = false
                callInfoLine = if (ok) {
                    // SR-Bedarf: call_screengrab_sent
                    context.getString(R.string.call_screengrab_sent)
                } else {
                    context.getString(R.string.call_screengrab_failed_reason, error?.take(80) ?: "?")
                }
            }
        }
    }

    /** G12-Fluss: 3-s-Bestätigungs-Chip (§7.2 Standard), Capability-Schranke, RAM-only. */
    fun callScreengrabTap() {
        if (screengrabBusy) return
        if (!CapabilityGate.isEnabled(CallRules.CAP_SCREEN_CAPTURE)) {
            callInfoLine = context.getString(R.string.call_screengrab_cap_missing)
            return
        }
        if (!screengrabConfirmOpen) {
            // SR-Bedarf: call_screengrab_confirm_hint
            callInfoLine = context.getString(R.string.call_screengrab_confirm_hint)
            screengrabConfirmOpen = true
            return
        }
        screengrabConfirmOpen = false
        callScreengrabExecute()
    }

    // ── W22 A1 (STT on-device v1): Freihand-Verband ─────────────────────────
    // §3.2 Variante A („Ton verlässt das Handy NICHT“): Sprache wird am Gerät
    // transkribiert (CallSpeechRecognizer, on-device-Gate) und als Chat-TEXT-
    // Turn im Call-Kontext gesendet — die Push-to-Talk-Aufnahme (E2E-Chunk)
    // bleibt als Fallback am selben Mikro-Knopf (Lang-Drücken = Modus-Wahl).
    val callSttSupported = remember { CallSpeechRecognizer.handsFreeSupported(context) }

    // W22 A2 (Fund 8): Nutzer-Wahl Freihand vs Push-to-Talk als Saveable — ein
    // Tab-Wechsel MITTEN im Anruf remountet den Screen und würde die getätigte
    // Wahl sonst still überschreiben. Key callId: je NEUEM Anruf startet der
    // Modus bewusst frisch (A1-Default); die Wahl gilt NUR diesen Anruf.
    var callHandsFree by rememberSaveable(callSession?.callId) { mutableStateOf(false) }
    var callHandsFreeChosen by rememberSaveable(callSession?.callId) { mutableStateOf(false) }
    var callSttListening by remember { mutableStateOf(false) }

    /**
     * A1: Freihand-Turn senden — erkannter TEXT als normaler Chat-Text im
     * Call-Kontext (chat.send E2E; der Adapter markiert die Antwort im akzep-
     * tierten Anruf als TTS-Chunk, call_chunk_seq §3.3). Die User-Bubble
     * erscheint wie WhatsApp-Transkript im Verlauf; RelayClient persistiert
     * den Disk-Zwilling selbst (sendChat-Verband) — hier NUR die Live-Bubble.
     * W22 A2 Fix 8 (Reviewer-A-Fund): ein Turn, der erst nach dem Auflegen
     * (Phase != ACTIVE, z. B. im ENDED-Fenster) ankommt, wird NICHT still
     * verworfen — er geht als normaler Chat-Text in denselben Thread (das
     * just erkannte Diktat bleibt sichtbar; Chat-Kontext = derselbe Agent/
     * Chat, kein eingefrorener Call-Zustand nötig). Ohne Anrufsitzung (cs =
     * null, Tab schon geräumt) bleibt der Drop — dokumentierte Grenze.
     */
    fun callSendSttTurn(text: String) {
        val cs = callSession ?: return
        val clean = CallRules.sttTurnText(text) ?: return
        val sentAtMs = System.currentTimeMillis()
        val nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        messages = messages + ChatMessage(
            id = nextId,
            agentId = cs.agentId.hashCode(),
            fromAgent = false,
            text = clean,
            time = formatTimestamp(sentAtMs),
            date = ChatDateRules.dayKey(sentAtMs),
        )
        liveMessagesPresent = true
        callInfoLine = null  // Partial-Overlay räumen (Turn ist raus)
        if (cs.agentId.isNotBlank()) {
            RelayClient.sendChat(cs.agentId, clean, sentAtMs) { ok, error, _ ->
                if (!ok) {
                    callInfoLine = context.getString(R.string.call_screengrab_talk_failed) +
                        (error ?: context.getString(R.string.chat_send_failed_unknown))
                }
            }
        }
    }

    /**
     * A1-Callbacks: Partial = Live-Transkript-Overlay + Barge-in (§3.1,
     * nicht-leerer Partial stoppt die Agenten-TTS), Turn = Senden, Fatal =
     * sichtbarer Hinweis + Auto-Fallback auf Push-to-Talk (callHandsFree
     * false — KEIN stiller Tod des Freihand-Modus).
     */
    val callStt = remember {
        CallSpeechRecognizer(context, object : CallSpeechRecognizer.Callbacks {
            override fun onPartial(text: String) {
                // Barge-in: Nutzerrauschen stoppt die Agenten-Ausgabe (§3.1).
                runCatching { ChatSpeechSpeaker.stopAll() }
                callInfoLine = text
            }

            override fun onTurn(text: String) {
                callSendSttTurn(text)
            }

            override fun onListeningChanged(listening: Boolean) {
                callSttListening = listening
            }

            override fun onFatal(errorCode: Int) {
                // Fail-soft (§A1): Freihand stirbt nie still — Modus fällt
                // SICHTBAR auf Push-to-Talk zurück, der Hinweis sagt warum.
                callHandsFree = false
                callSttListening = false
                callInfoLine =
                    // STRING: call_stt_failed — de "Spracherkennung fehlgeschlagen — Sprechen-Taste nutzen", en "Speech recognition failed — use the talk button"
                    "Spracherkennung fehlgeschlagen — Sprechen-Taste nutzen"
            }
        })
    }

    /**
     * A1-Modus-Wahl (EIN Aufenthaltsort: der Mikro-Knopf): Freihand nur mit
     * on-device-Provider UND Mikrofon-Erlaubnis — beides fehlt → ehrlicher
     * Hinweis, Modus bleibt Push-to-Talk (AKTIONEN-MIT-ERGEBNIS).
     */
    fun callToggleHandsFree(toHandsFree: Boolean) {
        val cs = callSession ?: return
        if (cs.phase != CallRules.Phase.ACTIVE) return
        // W22 A2 (Fund 8): ab hier ist es eine EXPLIZITE Nutzer-Wahl dieser
        // Anrufsitzung — sie verdrängt den Auto-Freihand-Start auch nach einem
        // Remount (Tab-Wechsel) nicht mehr.
        callHandsFreeChosen = true
        if (toHandsFree) {
            if (!callSttSupported) {
                // STRING: call_stt_unavailable — de "Freihand-Sprechen auf diesem Gerät nicht verfügbar — Sprechen-Taste nutzen", en "Hands-free speech unavailable on this device — use the talk button"
                callInfoLine = "Freihand-Sprechen auf diesem Gerät nicht verfügbar — Sprechen-Taste nutzen"
                return
            }
            val micGranted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!micGranted) {
                callInfoLine = context.getString(R.string.chat_media_permission_missing)
                return
            }
            callHandsFree = true
            callStt.start()
        } else {
            callHandsFree = false
            callStt.stop()
            callInfoLine = null
        }
    }

    // Dauer-Takt (Strip) + Turn-Cap + Räumung bei Phasenwechsel (P6-Verband).
    LaunchedEffect(callSession?.phase, callSession?.callId) {
        val phase = callSession?.phase
        if (phase != CallRules.Phase.ACTIVE) {
            callDurationTick = 0L
            callInfoLine = null
            screengrabConfirmOpen = false
            if (callTalkRecording) {
                runCatching { callRecorder.cancel() }
                callTalkRecording = false
            }
            // W22 A1: Lifecycle-Cancel bei Call-Ende — die Freihand-Erkennung
            // stirbt MIT dem Anruf (kein Mikro-Claim über den Call hinaus);
            // der Modus startet je NEUEM Anruf frisch (Default unten).
            callStt.stop()
            callSttListening = false
            callHandsFree = false
        } else {
            callDurationTick = System.currentTimeMillis()
            // W22 A1: Freihand startet AUTOMATISCH im angenommenen Anruf,
            // wenn on-device-STT verfügbar UND das Mikro erlaubt ist (§3.2-A-
            // Default des Plans); sonst bleibt es ehrlich bei Push-to-Talk —
            // der Nutzer lang-drückt den Mikro-Knopf, sieht den Grund-Hinweis.
            if (callSttSupported) {
                val micGranted = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (micGranted && !callHandsFreeChosen) {
                    // W22 A2 (Fund 8): Auto-Freihand NUR bis zum ersten Nutzer-
                    // Takt — hat der Nutzer für DIESEN Anruf bewusst Push-to-
                    // Talk gewählt, gewinnt die Wahl auch über Remounts.
                    callHandsFree = true
                    callStt.start()
                }
            }
            while (callSession?.phase == CallRules.Phase.ACTIVE) {
                kotlinx.coroutines.delay(1000L)
                callDurationTick = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(callTalkRecording) {
        if (callTalkRecording) {
            kotlinx.coroutines.delay(CallRules.AUDIO_CHUNK_MAX_MS)
            if (callTalkRecording) callTalkStop()  // Turn-Cap (§3.1, kein Diktat)
        }
    }
    LaunchedEffect(screengrabConfirmOpen) {
        if (screengrabConfirmOpen) {
            kotlinx.coroutines.delay(CallRules.SCREENGRAB_CONFIRM_MS)
            screengrabConfirmOpen = false
        }
    }
    // W22 A1: Screen-Räumung — Chat-Route verlassen (Tab-Wechsel mitten im
    // Anruf) stoppt die Freihand-Erkennung (kein Mikro-Claim ohne sichtbaren
    // Call-Strip). Die Rückkehr startet frisch: der Phasen-Effect oben läuft
    // bei JEDEM neuen Mount (LaunchedEffect = Mount + Key-Wechsel).
    DisposableEffect(Unit) {
        onDispose {
            callStt.stop()
            callSttListening = false
        }
    }

    // ── W22 A2 (P5): Call-Verlauf — Anruf-Bubble/Verpasst-Zeile je Ende ──────
    // Genau EIN Verlaufs-Eintrag je call_id: der RelayClient hält die ENDED-
    // Session 10 s („Anruf beendet“-Fenster) und Phasen-Effekte feuern je
    // Phasenwechsel — das Done-Set dedupliziert (kein Doppel-Eintrag, auch
    // nicht über App-Rückkehr/Mount-Ketten). Dauer liefert das PHONE lokal
    // (CallRules.callRecordFor aus activeSinceMs/endedAtMs).
    // W22 A2 Fix 6 (Reviewer-A-Fund): das Done-Set ist rememberSaveable — ein
    // remember{Set} überlebt Remounts (Tab-Wechsel/Rotation) NICHT und ein
    // Re-Mount im ENDED-Fenster persistierte dieselbe call_id ein zweites Mal
    // (Disk-vs-Disk-Duplikate, Store-seitig kein append-Dedupe).
    val callRecordedIds: MutableSet<String> = rememberSaveable(
        stateSaver = listSaver<MutableSet<String>, String>(
            save = { it.toList() },
            restore = { it.toHashSet() },
        ),
    ) { HashSet<String>() }
    LaunchedEffect(callSession?.phase, callSession?.callId) {
        val cs = callSession ?: return@LaunchedEffect
        if (cs.phase != CallRules.Phase.ENDED) return@LaunchedEffect
        if (!callRecordedIds.add(cs.callId)) return@LaunchedEffect
        if (cs.agentId.isBlank()) return@LaunchedEffect
        // Settings-Toggle „Anruf-Verlauf aufzeichnen“ (default an): aus =
        // der Anruf hinterlässt KEINE Spur im Verlauf.
        val record = CallRules.callRecordFor(
            cs, CallHistoryPrefs.recordEnabled(context),
        ) ?: return@LaunchedEffect
        val endedAt = cs.endedAtMs ?: System.currentTimeMillis()
        // Live-Anzeige in der Conversation-Zeitlinie (Anzeige-Text baut das
        // Widget je Locale — text bleibt bewusst leer).
        val nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
        messages = messages + ChatMessage(
            id = nextId,
            agentId = cs.agentId.hashCode(),
            fromAgent = false,
            text = "",
            time = formatTimestamp(endedAt),
            date = ChatDateRules.dayKey(endedAt),
            call = ChatCallInfo(
                direction = record.direction,
                missed = record.missed,
                durationSec = record.durationSec,
            ),
        )
        liveMessagesPresent = true
        // Platte: Eintrags-Art kind=call im ChatHistoryStore — fließt durch
        // ALLE Verbände (FIFO-Cap, Byte-Deckel, clearAgent-Löschverband,
        // P24-Siegel über den ganzen Datei-Inhalt).
        ChatHistoryStore.appendAsync(
            ChatHistoryStore.root(context.applicationContext),
            ChatHistoryStore.Entry(
                seq = endedAt,
                agentId = cs.agentId,
                chatId = cs.chatId,
                fromAgent = false,
                text = "",
                time = formatTimestamp(endedAt),
                callInfo = ChatHistoryStore.CallInfo(
                    direction = record.direction,
                    missed = record.missed,
                    durationSec = record.durationSec,
                ),
            ),
        )
    }

    // ── W21 ANRUF (P4): Route-Wrapper — Status-Zeile (aktiv) schwebt ÜBER der
    // Route (WA-Muster unter dem Kopf, kein Verdeckens des Chat-Headers), die
    // Klingel-Karte liegt als Overlay über Liste UND Conversation. Das
    // bestehende if/else bleibt unangetastet (Flat-Indent bewusst).
    Column(modifier = Modifier.fillMaxSize()) {
        if (callSession?.phase == CallRules.Phase.ACTIVE && callSession != null &&
            callAlias != null
        ) {
            val cs = callSession!!
            CallActiveStrip(
                session = cs,
                alias = callAlias,
                durationSeconds = CallRules.activeSeconds(cs, callDurationTick),
                steerCapabilityOn = callSteerCapabilityOn,
                screengrabPendingConfirmation = screengrabConfirmOpen,
                talkRecording = callTalkRecording,
                handsFree = callHandsFree,
                sttListening = callSttListening,
                infoLine = callInfoLine,
                onToggleSteer = { RelayClient.setCallAgentSteer(!cs.agentScreenSteer) },
                onScreengrabRequest = { callScreengrabTap() },
                onTalkStart = { callTalkStart() },
                onTalkEnd = { callTalkStop() },
                onToggleHandsFree = { toHandsFree -> callToggleHandsFree(toHandsFree) },
                onHangUp = { RelayClient.hangUpCall() },
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
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
                } else {
                    // HAKEN-FIX (2): MONOTONE CHIP-FUSION statt Fallback-Kette.
                    // Vorher: msgIdByLocalId → chatStatus[msg_id] ?: restored...
                    // — eine EVICTED Live-Zuordnung (>500 msg_ids, Medien-Chunks)
                    // drückt den Chip auf 1 Haken, obwohl delivered/read schon
                    // bekannt war; nach dem Neustart lag alles nur auf dem
                    // Sendezeit-Snapshot. Jetzt: Maximum über ALLE Quellen
                    // (ChatStatusRank.highestOf = Nie-unter-Höchststand):
                    // - Live-Map (Broker, evictbar) — nach Eviction beitragslos,
                    //   nie ein Downgrade-Anker;
                    // - restoredStatusById (Platte: Side-Car-Höchststand ⊔
                    //   Sendezeit-Snapshot, s. Restore-Verband);
                    // - persistenter High-Water je local_id (Rotation), gefüllt
                    //   vom Sammler-Effekt bei JEDEM Status-Frame.
                    // 'failed' bleibt Schranke (Sweep-Honesty, HERZ-Befund 8),
                    // read heilt — dieselbe Monotonie wie shouldApply.
                    ChatStatusRank.highestOf(
                        listOf(
                            msgIdByLocalId[message.id]?.let { msgId -> chatStatus[msgId] },
                            restoredStatusById[message.id],
                            statusHighWaterByLocalId[message.id],
                        ),
                    )
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
            // PH-3 Welle 2 (Sirs Revision (iii)) / W18-1a (Fixliste 18): der
            // FP-Abgleich lebt als ⋮-Menü-Eintrag und versendet 'FP-ABGLEICH:
            // <64 Hex des session_fp>' VERDECKT (RelayClient.sendFpCheck,
            // persist=false) — in der App erscheint er NICHT als Chat-Bubble.
            // Die Antwort kommt als chat.fp_check(-enc)-Kontrollframe bzw.
            // (Legacy-Agent) als 'Fingerprint-Abgleich'-Text; BEIDES verarbeitet
            // der RelayClient verdeckt (Banner/Ergebnis-Zeile, nie Chat-Zeile).
            // Fehler beim Senden = SendErrorBanner wie bei jeder anderen Zeile.
            onFpCheck = {
                val fp = RelayClient.sessionFingerprint(agent.agentId)
                if (fp == null) {
                    sendErrors = sendErrors + (
                        agent.agentId to
                            context.getString(R.string.chat_e2e_missing)
                        )
                } else {
                    RelayClient.sendFpCheck(agent.agentId) { ok, error, _ ->
                        if (!ok) {
                            sendErrors = sendErrors + (
                                agent.agentId to context.getString(
                                    R.string.chat_send_failed,
                                    error ?: context.getString(R.string.chat_send_failed_unknown),
                                )
                                )
                        }
                    }
                }
            },
            // MEDIA-T1: Anhang-Button (Picker/Datei/Voice) + Upload-Fortschritt
            // je media_id für die eigenen Medien-Bubbles.
            onAttach = { attachSheetOpen = true },
            voiceRecording = voiceRecording,
            onVoiceToggle = { toggleVoice() },
            // W16 P30b: Hold-to-Record-Verband (Fixliste 30b) — die Geste rutscht
            // in den runden Mikro-Knopf, die Fahne hängt über der Composer-Zeile.
            onVoiceHoldStart = { startVoiceHold() },
            onVoiceHoldEnd = { outcome, holdMs -> endVoiceHold(outcome, holdMs) },
            onVoiceHoldCancelState = { cancelling -> voiceHoldCancelling = cancelling },
            voiceHoldCancelling = voiceHoldCancelling,
            onVoiceCancel = { cancelVoiceRecording() },
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
            // W16 (Fixliste 32): „Chat löschen“ — Nachrichten-Liste SOFORT im
            // State räumen + Persist-Verlauf je Agent asynchron von der Platte
            // (ChatHistoryStore.clearAgent — gehashte Datei + Legacy-Zwilling).
            // KEIN Relay-/E2E-Eingriff: die verschlüsselten Session-Keys
            // (IdentityStore/relay-keys) bleiben unangetastet.
            onClearHistory = {
                messages = messages.filterNot { it.agentId == agent.id }
                // J3-FIX: Siegel-Breach-Banner gehört zum gelöschten Verlauf —
                // mit ihm weg, sonst bleibt das rote Banner nach dem Recovery
                // dauerhaft stehen und unterdrückt den Suchindex-Hinweis.
                historySealBreach = false
                if (agent.agentId.isNotBlank()) {
                    mediaScope.launch(Dispatchers.IO) {
                        ChatHistoryStore.clearAgent(
                            ChatHistoryStore.root(context.applicationContext),
                            agent.agentId,
                        )
                    }
                }
            },
            // P24 (MERGE-LANE 18.11.): Siegel-Verstoß-Fehlerzustand — die
            // Verlauf-löschen-Aktion nutzt den BESTEHENDEN Chat-löschen-Weg
            // (Bestätigungsdialog + onClearHistory räumt die Agent-Verlaufs-
            // datei inkl. Siegel; E2E-Keys unangetastet).
            historySealBreach = historySealBreach,
            // PH-6: dezenter Suchindex-Aufbau-Hinweis (kein Blockieren).
            searchIndexBuilding = searchIndexBuilding,
            // PH-3 Welle 2: rot-Banner bei Abweichung/Reset unter dem Kopf.
            e2eBanner = e2eBanner,
            onDismissE2eBanner = { RelayClient.clearE2eBanner() },
            // B6 (Multi-Gerät): dezente Backfill-Zeile + Blickpunkt-Halten.
            backfillSyncing = agent.agentId.isNotBlank() && agent.agentId in backfillSyncing,
            historyPrependCount = historyPrependCounts[agent.id] ?: 0,
            onConsumePrependCount = {
                historyPrependCounts = historyPrependCounts - agent.id
            },
            // W18-1a: das verdeckte FP-Ergebnis DIESES Agenten (Demo-Eintrag
            // ohne eigene agentId hat niemals eines) + Abmelden der Zeile.
            fpCheckResult = agent.agentId.takeIf { it.isNotBlank() }
                ?.let { id -> fpCheckResults[id] },
            onDismissFpCheck = { RelayClient.clearFpCheckResult(agent.agentId) },
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

            // ── W21 ANRUF (P4): Klingel-Karte als OVERLAY über beiden Zuständen
            // (Annehmen/Ablehnen sind 48-dp-Pills in CallScreen, Vibration über
            // den Ring-Kanal des RelayClient).
            if (callSession?.phase == CallRules.Phase.RINGING && callSession != null &&
                callAlias != null
            ) {
                val cs = callSession!!
                CallRingingOverlay(
                    session = cs,
                    alias = callAlias,
                    onAccept = { RelayClient.acceptCall() },
                    onDecline = { RelayClient.declineCall() },
                )
            }
        }  // Box (Call-Overlay-Ebene)
    }  // Column (Call-Route-Wrapper)

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
        // W15-3/4 (Sirs Update): 'Agenten'-Kopfzeile + Untertitel KOMPLETT weg —
        // die Liste beginnt direkt mit der Such-Kapsel. Kein hängender
        // Titel-Restraum: SectionTitle trug paddingFromBaseline(top = 28.dp)
        // ein; der ist mit dem Item entfallen. Einzig der Rhythmus-Abstand
        // zur Top-Bar bleibt als contentPadding(top) — konsistent zur
        // 16-dp-Seiten-Rhythmik der Kapsel/Zeilen.
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
    ) {
        // HIG-W19 (Demo-Befund): ehrlicher Leerzustand ohne Kopplung — statt
        // erfundener „Agent 1-3“-Einträge der Klartext-Stand plus der Pfad
        // zu Einstellungen › Server (Demo-Daten sind entfernt).
        if (agents.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.chat_empty_no_agents),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FlipsiColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.chat_empty_no_agents_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlipsiColors.TextSecondary,
                    )
                }
            }
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
                    // HIG-W19: Magnifier als ic_search-Vector (Icons.Filled.Search) —
                    // das 🔍-Emoji war schrift-/Versionsabhängig und kein echter
                    // Ikonenschnitt (Such-Doppel-Befund, gleicher Stil wie
                    // ConversationSearchBar).
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = FlipsiColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
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
                                    stringResource(R.string.chat_list_search_hint),
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
                        label = stringResource(R.string.chat_filter_all),
                        active = listFilter == "alle",
                        onClick = { listFilter = "alle" },
                    )
                    FilterChipFlipsi(
                        label = stringResource(R.string.chat_filter_online),
                        active = listFilter == "online",
                        onClick = { listFilter = "online" },
                    )
                    FilterChipFlipsi(
                        label = stringResource(R.string.chat_filter_offline),
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
                    stringResource(R.string.chat_list_empty_result),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            filteredAgents.forEach { agent ->
                // Vorschau-Zeile = letzte Nachricht dieses Agenten (oder Hinweis).
                val preview = messages.lastOrNull { it.agentId == agent.id }?.text
                    ?: stringResource(R.string.chat_list_no_messages)
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
    // W18-1a: verdecktes FP-Abgleich-Ergebnis (Statuszeile statt Chat-Bubble,
    // FpCheckNotice) + Abmelden der dismissbaren Zeile.
    fpCheckResult: RelayClient.FpCheckResult?,
    onDismissFpCheck: () -> Unit,
    onBack: () -> Unit,
    // MEDIA-T1: Anhang/Voice + Upload-Fortschritt (Progress-Chips je media_id).
    onAttach: (() -> Unit)? = null,
    voiceRecording: Boolean = false,
    onVoiceToggle: (() -> Unit)? = null,
    // W16 P30b: Hold-to-Record-Verband (B2-API voiceHoldToRecord) am runden
    // Mikro-Knopf + Aufnahme-Fahne über der Composer-Zeile + Abbruch (X).
    onVoiceHoldStart: (() -> Boolean)? = null,
    onVoiceHoldEnd: ((VoiceHoldOutcome, Long) -> Unit)? = null,
    onVoiceHoldCancelState: ((Boolean) -> Unit)? = null,
    voiceHoldCancelling: Boolean = false,
    onVoiceCancel: () -> Unit = {},
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
    // W16 (Fixliste 32): „Chat löschen“ — die Implementierung (messages-Liste
    // + ChatHistoryStore) lebt im ChatScreen-Verband; hier kommt der
    // Bestätigungsdialog davor und der Nachträgliche Callback dahinter.
    onClearHistory: () -> Unit = {},
    // B6 (Multi-Gerät): dezent — der Verlauf dieses Agenten wird vom Gateway
    // nachgeladen (chat.backfill_request offen/angekommen). KEIN Blockieren:
    // die Zeile sitzt unter dem Kopf, der Verlauf bleibt bedienbar.
    backfillSyncing: Boolean = false,
    // B6: frisch prepended Verlauf-Zeilen SEIT dem letzten Zähreff — der
    // Blickpunkt des scrollenden Verlaufs hält sich (Index-Kompensation),
    // statt vom auto-Scroll ans Ende gerissen zu werden („Scroll-Position
    // nicht springen lassen“).
    historyPrependCount: Int = 0,
    onConsumePrependCount: () -> Unit = {},
    // P24 (MERGE-LANE 18.11.): Siegel-Verstoß beim Verlauf-Restore — FEHLER-
    // zustand statt Crash: Zeile (Titel + Befund) + Verlauf-löschen-Aktion
    // über den BESTEHENDEN Bestätigungsdialog-Weg (confirmDeleteChat →
    // onClearHistory; der Chat-löschen-Verband räumt die Verlaufsdatei inkl.
    // Siegel, E2E-Keys unangetastet).
    historySealBreach: Boolean = false,
    // PH-6: dezenter Suchindex-Aufbau-Hinweis (nie blockierend).
    searchIndexBuilding: Boolean = false,
) {
    val listState = rememberLazyListState()
    // W22 A2 (P5): Fokus-Griff des Composers — die Verpasst-Anruf-Zeile setzt
    // ihn (sichtbares Ergebnis des Taps: Tastatur + Antwort-Entwurf).
    val missedCallFocus = remember { FocusRequester() }
    // W22 A2 Fix 7 (Reviewer-A-Fund): Missed-Tap bei OFFENEM Suchmodus — der
    // Composer ist dann aus der Composition, ein sofortiges requestFocus würde
    // still fehlen und der Entwurf bliebe unsichtbar. Der Tap schließt die
    // Suche; der Fokus geht erst post-composition (Composer remountet im
    // nächsten Frame).
    var missedFocusPending by remember { mutableStateOf(false) }
    LaunchedEffect(missedFocusPending) {
        if (missedFocusPending) {
            missedFocusPending = false
            runCatching { missedCallFocus.requestFocus() }
        }
    }
    // W4-B (Swipe-to-Reply): Reduced-Pass-Faser — EIN Lesen der Anim-Scala
    // je Composition (ChatMotionRules); steuert Geste-Settle UND Sprung-
    // Scroll der Chat-Suche (je Anim-Stelle genau einmal entschieden).
    val compositionContext = LocalContext.current
    val reducedMotion = remember(agent.agentId) {
        ChatMotionRules.reduced(ChatMotionRules.animatorScale(compositionContext.contentResolver))
    }
    // ── Komposer Welle 12: Modell-/Denken-Auswahl am Komposer ────────────────
    // W19 (17.11.26): Gateway-Stand dieses Agenten — Modell-Katalog + laufendes
    // Modell ('current') + Denk-Stufe ('effort'), ALLES aus agent.models-Frames
    // (keine App-Defaults, Sir-Generik-Regel).
    val agentModelsLive by RelayClient.agentModels.collectAsState()
    val gatewayModelCurrent by RelayClient.agentModelCurrent.collectAsState()
    val gatewayModelEffort by RelayClient.agentModelEffort.collectAsState()
    // Auswahl lebt PRO AGENT (SharedPreferences via ChatComposerPrefs); die
    // lokalen States spiegeln sie für die Chips. Das Sheet öffnet per Chip.
    // W19: initial überlebt NUR ein exakter Server-Listenwert (stale getippte
    // Namen aus Vorgängerversionen fallen auf Auto — Anzeige ehrlich, sendbar
    // via submitQuoted ebenfalls serverlisten-gebunden).
    var composerModel by remember(agent.agentId, agentModelsLive[agent.agentId]) {
        mutableStateOf(
            sendableModel(
                ChatComposerPrefs.model(compositionContext, agent.agentId),
                agentModelsLive[agent.agentId],
            )
        )
    }
    var composerEffort by remember(agent.agentId) {
        mutableStateOf(ChatComposerPrefs.effort(compositionContext, agent.agentId))
    }
    var composerPickerOpen by remember(agent.agentId) { mutableStateOf(false) }
    // W16 (Fixliste 34): „Unterstützen“-Sheet — der Herz-Tap im Header öffnet
    // DIREKT dieses Sheet (mit der play-Billing-SupporterCard) statt Umweg
    // Einstellungen. GitHub-Edition: Herz nicht gerendert (Header-Gate).
    var supporterSheetOpen by remember(agent.id) { mutableStateOf(false) }
    // W18-4 (Composer-Eingabe kaputt nach Klicks im Auto-Modell-Menü): beim
    // Sheet-Schluss Fokus EXPLIZIT räumen — der Auswahl-/Dismiss-Klick durfte
    // den Fokus im Sheet hängen lassen und die Eingabeleiste war danach tot.
    // Das Sheet trägt seit dem 0.16.9-Freitext-Weg KEIN Textfeld mehr (Beweis
    // im ComposerUi), der IME-Grund ist strukturell zu; die clearFocus-Schiene
    // bleibt als Backstop, damit der Speichern-Pfad (Auswahlklick) und der
    // Abbruch-Pfad (onDismissRequest) beide sauber räumen (onDismissRequest
    // feuert beim Programm-Schluss NICHT, siehe ComposerUi-Verband).
    val focusManager = LocalFocusManager.current
    // WELLE-13 P9 (Vorlesen): je Agent (Prefs vorlesen_<agentId>) — der Spiegel
    // füttert den Regler im ChatModelSheet; das Enqueue in ChatScreen liest
    // den Prefs-Stand zur Laufzeit, der Regler wirkt also sofort.
    var composerReadAloud by remember(agent.agentId) {
        mutableStateOf(ChatSpeechPrefs.enabled(compositionContext, agent.agentId))
    }
    // WELLE-13 P9: Speaking-Chips je Bubble — zustandsgesteuert vom TTS-Singleton
    // (IDs der gerade/nächst vorgelesenen Antworten; Tap auf den Chip stoppt).
    val speakingIds by ChatSpeechSpeaker.speakingIds.collectAsState()
    // W18 (17.11.26, Sir): Composer-Chip als KURZEL — "A" = Auto (Agenten-
    // Standard-Modell aus der Konfiguration), "M" = Manuel (ein anderes
    // Modell wurde für diesen Chat gewählt). Das lange 'Auto · <Modell>'
    // blähte die Pill auf und die Nachrichten-Zeile schrumpfte (Sir-Befund).
    // Das Modell selbst zeigt das Modell & Denken-Sheet (Auswahl-Status);
    // TalkBack kriegt die volle Erklärung über die *_a11y-Strings unten.
    val modelChipLabel = if (composerModel != null)
        stringResource(R.string.composer_chip_manual)
    else
        stringResource(R.string.composer_chip_auto)
    // ── W16 (Fixliste 32): Chat-Menü-Verband — ⋮-Menü am Kopf (ChatScreen-
    // Header), Mediathek als Overlay über dem Chat (Tab-Zeile entfällt),
    // Agenten-/Chatdesign-Sheets + Lösch-Bestätigung. Zustand JE Agent
    // (Agent-Wechsel räumt auf; kein saveable — Rotation kehrt in den Chat).
    var showMediathek by remember(agent.id) { mutableStateOf(false) }
    var agentSheetOpen by remember(agent.id) { mutableStateOf(false) }
    var designSheetOpen by remember(agent.id) { mutableStateOf(false) }
    var confirmDeleteChat by remember(agent.id) { mutableStateOf(false) }
    var sheetPictureError by remember(agent.id) { mutableStateOf(false) }
    // W16 (Chatdesign): Tick-Mirror — Wallpaper-Wechsel invalidiert die
    // Conversation-Composition. ChatWallpaperPrefs schreibt SharedPreferences
    // (KEIN Snapshot-State); ChatWallpaper liest den Stand je Composition neu
    // — der Tick ist der Recompose-Zündpunkt (sonst frisst der Pick still).
    var wallpaperTick by remember { mutableIntStateOf(0) }
    // Avatar-Regel des Sheets an die Store-Revision binden: Pick/Entfernen
    // rendert die „Profilbild entfernen“-Option frisch (Revision ist Snapshot-
    // State, ein Lesen im Scope abonniert sie).
    val avatarRevision = AgentAvatarStore.revision
    val hasAvatarPicture = remember(avatarRevision, agent.agentId) {
        AgentAvatarStore.hasAvatar(compositionContext, agent.agentId)
    }
    // W16 (Fixliste 32): Foto-Picker der Sheets — Agenten-Avatar (app-privat
    // Kopie via AgentAvatarStore, P32a) und Chat-Wallpaper (app-privat Kopie
    // via ChatWallpaperPrefs, W16.3 F6 — dieselbe Basis wie der Avatar,
    // Parität AgentAvatarStore; der reine URI-Verweis ist entfallen).
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            sheetPictureError = !AgentAvatarStore.setAvatar(
                compositionContext,
                agent.agentId,
                uri,
            )
        }
    }
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            // W16.3 (F6): Pick-Bytes app-privat kopieren (Parität
            // AgentAvatarStore.setAvatar) — die Kopie überlebt Galerie-
            // Revokes; bei Fail bleibt der Zustand ehrlich auf „Standard"
            // (kein Tick → keine gefälschte Statuszeile).
            if (ChatWallpaperPrefs.setWallpaperImage(compositionContext, uri)) {
                wallpaperTick++
            }
        }
    }
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
    // B6 (Multi-Gerät): ein reines Backfill-PREPEND ist KEIN Folgen-Anlass —
    // statt ans Ende zu zerrn hält der Blickpunkt sich (Index-Kompensation
    // um die prependeten Zeilen, ohne Animation = kein sichtbarer Sprung);
    // beim ersten Zusammensetzen der Conversation greift der gewohnte End-
    // Sprung (der Prepend ist dann bereits Teil der Ausgangsliste).
    var lastConversationSize by remember { mutableStateOf(-1) }
    LaunchedEffect(conversation.size) {
        if (searchOpen) return@LaunchedEffect
        if (conversation.isNotEmpty()) {
            val firstComposition = lastConversationSize == -1
            val grew = !firstComposition && conversation.size > lastConversationSize
            val prepended = historyPrependCount
            when {
                firstComposition -> {
                    listState.animateScrollToItem(conversation.lastIndex)
                    onConsumePrependCount()
                }
                grew && prepended > 0 -> {
                    val anchor = (listState.firstVisibleItemIndex + prepended)
                        .coerceAtMost(conversation.lastIndex)
                    onConsumePrependCount()
                    // Instant-Sprung auf dieselbe Inhaltsposition — KEINE Anim
                    // über evtl. 200 Zeilen (die würde sich als Sprung zeigen).
                    listState.scrollToItem(anchor)
                }
                else -> listState.animateScrollToItem(conversation.lastIndex)
            }
        }
        lastConversationSize = conversation.size
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        // B6 (Multi-Gerät): dezente Sync-Zeile — der Verlauf dieses Agenten
        // wird vom Gateway nachgeladen. Bewusst KEIN Blockieren/Deckel: nur
        // eine Zeile unter dem Kopf, der Verlauf bleibt bedienbar (Roadmap
        // Kap. 4.1: Backfill als Zusatz-Verband, nicht als Sperre).
        if (backfillSyncing) {
            Text(
                stringResource(R.string.chat_backfill_progress),
                style = MaterialTheme.typography.labelSmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // PH-6 (MERGE-LANE 18.11.): dezenter Aufbau-Hinweis der Volltext-
        // suche (gleiche dezent-Zeilen-Familie wie der Backfill-Hinweis).
        if (searchIndexBuilding) {
            Text(
                stringResource(R.string.search_index_building),
                style = MaterialTheme.typography.labelSmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // P24 (MERGE-LANE 18.11.): Siegel-Verstoß — FEHLERZUSTAND statt Crash.
        // Ehrlich laut: Titel + Befund + Verlauf-löschen-Aktion über den
        // bestehenden Bestätigungsdialog (confirmDeleteChat → onClearHistory
        // räumt die Agent-Verlaufsdatei inkl. Siegel — ein frischer Load startet
        // einen neuen, gesiegelten Verlauf; E2E-Keys bleiben unangetastet).
        if (historySealBreach) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.history_seal_breach_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FlipsiColors.SignalRedText,
                    )
                    Text(
                        stringResource(R.string.history_seal_breach_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlipsiColors.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { confirmDeleteChat = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.history_seal_breach_action_clear))
                }
            }
        }
        // WELLE 13 (Sir): Herz-Knopf = Support — W16 (Fixliste 34): play
        // öffnet DIREKT das „Unterstützen“-Sheet im Chat; GitHub hat das
        // Herz vollständig nicht (keine Supporter-Route).
        // W16 (Fixliste 30a/32): Header im WA-Format — dynamisches Profilbild
        // (AgentAvatarStore, P32a) + ⋮-Chat-Menü (Agent ansehen / Chatdesign /
        // Modell & Denken / Mediathek öffnen / Chat löschen). Im Suchmodus
        // bleibt der Header als Kontext-Zeile (Fixliste 28: „Agent-Header
        // minimal behalten"); der FP-Abgleich-Button blendet dort aus.
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
            // W16 (Fixliste 34): Herz (play) → DIREKT das „Unterstützen“-Sheet
            // mit der Billing-Karte (GitHub: Herz entfällt im Header).
            onSupport = { supporterSheetOpen = true },
            // W16 (Fixliste 32): Mediathek-Modus = Verlaufssuche ausblenden
            // (kein Blind-Tap auf die Suchleiste, die im Overlay fehlt).
            showSearchButton = !showMediathek,
            onMenuAgent = { agentSheetOpen = true },
            onMenuDesign = { designSheetOpen = true },
            onMenuModel = { composerPickerOpen = true },
            onMenuMediathek = {
                searchOpen = false
                showMediathek = true
            },
            onMenuDelete = {
                searchOpen = false
                showMediathek = false
                confirmDeleteChat = true
            },
        )
        HorizontalDivider(color = FlipsiColors.LineStrong)
        // PH2-FIX (E2E P1): dismissbare Fehler-Zeile direkt unter dem Kopf —
        // der Send-Fehler ist auf der Chat-Route sichtbar, ohne die Route zu
        // verlassen (BridgeStatus rendert hier nicht).
        SendErrorBanner(message = errorBanner, onDismiss = onDismissError)
        // PH-3 Welle 2: rot-Banner bei Fingerprint-ABWEICHUNG / Session-Reset
        // (möglicher Man-in-the-Middle) — gleiche rot-Sprache wie der Send-Fehler.
        SendErrorBanner(message = e2eBanner, onDismiss = onDismissE2eBanner)
        // W18-1a: verdecktes FP-Abgleich-Ergebnis — dismissbare Statuszeile
        // unter dem Kopf (grün = bestanden, rot = Abweichung). NIE eine Chat-
        // Bubble: das Ergebnis lebt im Kontrollframe-Verband (RelayClient).
        FpCheckNotice(result = fpCheckResult, onDismiss = onDismissFpCheck)
        // ── W16 (Fixliste 32-Nachtrag): die Tab-Zeile [Chat | Mediathek] ist
        // ENTFERNT — das Chat-Fenster ist pur (nur der Chat selbst). Die
        // Mediathek lebt als Overlay (Menü „Mediathek öffnen"), Zurück per ‹-
        // Zeile; die Fixliste-28-Ausblendungen bleiben konsistent (im Mediathek-
        // Modus gibt es Composer ohnehin nicht).
        if (showMediathek) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showMediathek = false }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.mediathek_back_a11y),
                        tint = FlipsiColors.TextPrimary,
                    )
                }
                Text(
                    stringResource(R.string.mediathek_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = FlipsiColors.TextPrimary,
                )
            }
            // ── Mediathek-Overlay — alle Medien DIESES Agenten (Store-Verbind +
            // LIVE-Bubbles, STALE = Platzhalter-Karte); ohne Composer, volle Fläche.
            MediathekTabBody(
                agent = agent,
                conversation = conversation,
                modifier = Modifier.weight(1f),
            )
        } else {
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
                    // W16: key(wallpaperTick) — ein Wallpaper-Wechsel baut die
                    // Unterlage frisch (Prefs sind kein Snapshot-State).
                    key(wallpaperTick) {
                        ChatWallpaper(modifier = Modifier.fillMaxSize())
                    }
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
                                    // HIG-W19 (Writing): Leerzustand als
                                    // Ressource (DE/EN) statt deutschem
                                    // Literal — aufgelöst agentenweise.
                                    stringResource(
                                        R.string.chat_empty_first_message,
                                        agent.name,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = FlipsiColors.TextSecondary,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        // WELLE-13 P11: je Nachricht EIN Item — der Datum-Chip
                        // (Tageswechsel) rendert ÜBER der Bubble desselben
                        // Items (kein separater lazy-Schlüssel, kein Doppelslot).
                        itemsIndexed(conversation, key = { _, m -> m.id }) { index, message ->
                            val prevDate = if (index > 0) conversation[index - 1].date else null
                            if (ChatDateRules.chipVisible(prevDate, message.date)) {
                                ChatDateRules.dayLabel(message.date, todayKey, yesterdayKey)?.let { label ->
                                    ChatDateChip(label = label)
                                }
                            }
                            // W22 A2 (P5): Anruf-Einträge rendern STATT der
                            // Text-Bubble — beendete Anrufe als „Anruf (2:41)“-
                            // Ereignis-Pill, verpasste als tappbare Systemzeile
                            // (Antwort-Fokus, s. CallMissedRow-KDoc).
                            val callInfo = message.call
                            if (callInfo != null && !callInfo.missed) {
                                CallHistoryBubble(info = callInfo, time = message.time)
                            } else if (callInfo != null) {
                                CallMissedRow(
                                    time = message.time,
                                    onCallBack = {
                                        // Sichtbares Ergebnis (AKTIONEN-MIT-
                                        // ERGEBNIS): Composer-Fokus + Antwort-
                                        // Entwurf, NICHTS geht ohne Nutzer raus.
                                        // (Echter Rückruf = v1.1-Outgoing-Route,
                                        // HOOK-BEDARF im Wellen-Report.)
                                        // W22 A2 Fix 7: offener Suchmodus ist
                                        // ZUERST zu schließen — sonst ist der
                                        // Composer aus der Composition und der
                                        // Entwurf bliebe unsichtbar; der Fokus
                                        // folgt post-composition via
                                        // missedFocusPending.
                                        if (searchOpen) searchOpen = false
                                        missedFocusPending = true
                                        if (inputText.isBlank()) {
                                            // STRING: call_missed_callback_draft — de "Bitte ruf mich zurück.", en "Please call me back."
                                            onInputChange("Bitte ruf mich zurück.")
                                        }
                                    },
                                )
                            } else {
                            MessageBubble(
                            message = message,
                            // W16 (Fixliste 16a): Label-Spalte (Agenten-Name +
                            // KI-Badge) neben der Bubble entfällt — die Kenn-
                            // zeichnung lebt in der Meta-Fußzeile (ChatWidgets).
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
                            // W16 P30/P32: Avatar der Agenten-TTS-Bubble aus
                            // dem AgentAvatarStore (deterministisch ohne Foto).
                            agentAvatar = if (message.fromAgent) {
                                { AgentAvatar(agentId = agent.agentId, name = agent.name, diameter = 26.dp) }
                            } else {
                                null
                            },
                            )
                            }  // W22 A2: Ende Anruf-/Text-Bubble-Zweig
                        }
                    }
                }
            }
            // W16 (Fixliste 28/Zitat-Verband): Antwort-Strip + Composer + Trenner
            // gehören zur Chat-Szene — IM Suchmodus WEG (die Fläche gehört der
            // Suche), beim Schließen (✕/Back/Treffer-Sprung) SOFORT UND VOLL
            // zurück. Der getippte Draft lebt in `inputText` (ChatScreen-State,
            // rememberSaveable) und überlebt die Modus-Wechsel unberührt.
            // (Zitat-Verband: der Strip hängt an replyQuote und gehört in den
            // Chat-Zweig — das Mediathek-Overlay zeigt ihn nicht.)
            if (!searchOpen) {
            replyQuote?.let { quote ->
                ReplyQuoteStrip(quote = quote, onCancel = onDismissReply)
            }
            HorizontalDivider(color = FlipsiColors.LineStrong)
            // ── W16 P30b: Aufnahme-Fahne ÜBER der Composer-Zeile (WA-Muster) ──
            // roter Laufzeit-Punkt + mm:ss + „‹ Wischen zum Abbrechen"; beim
            // Wisch-Abbruch rot, der X-Knopf bricht hart ab (onVoiceCancel).
            // Steht für BEIDE Aufnahme-Spuren: Hold-Geste (P30b) und die
            // Tap-Fallback-Sessionen (B1) — recording = voiceRecording.
            VoiceRecordingFahne(
                recording = voiceRecording,
                cancelling = voiceHoldCancelling,
                onCancel = onVoiceCancel,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
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
                    // W16 P30b: Hold-to-Record-Verband am runden Knopf + Fahne.
                    onVoiceHoldStart = onVoiceHoldStart,
                    onVoiceHoldEnd = onVoiceHoldEnd,
                    onVoiceHoldCancelState = onVoiceHoldCancelState,
                    // W16 (Fixliste 35): Modell-Chip (LIVE-Anzeige) — Tap öffnet
                    // dasselbe Modell & Denken-Sheet wie der ⋮-Punkt.
                    modelChipLabel = modelChipLabel,
                    onModelChip = { composerPickerOpen = true },
                    // W22 A2 (P5): Fokus-Griff (Verpasst-Anruf-Zeile → Composer).
                    inputFocusRequester = missedCallFocus,
                )
            }
            } // W16: Ende „!searchOpen" (Composer-Verband des Suchmodus)
            // W16 (Fixliste 30-Nachtrag): der Modell-/Denken-Picker lebt nach dem
            // Composer-Umbau im ⋮-Menü („Modell & Denken") — das Sheet bleibt
            // VERBANDS-gleich (Pro-Agent-Prefs, Vorlesen-Regler), nur der
            // Offen-Sprung kam vom Chip → zum Menüpunkt.
        }
        if (composerPickerOpen) {
            ChatModelSheet(
                agentId = agent.agentId,
                currentModel = composerModel,
                currentEffort = composerEffort,
                onPickModel = { picked ->
                    // W18-4: Speichern-Pfad — Fokus VOR dem Schließen räumen
                    // (der Klick schließt das Sheet selbst, onDismissRequest
                    // feuert in diesem Weg nicht).
                    focusManager.clearFocus()
                    composerModel = picked
                    composerPickerOpen = false
                },
                onPickEffort = { level ->
                    composerEffort = level
                },
                // W18-4: Abbruch-Pfad — Fokus ebenfalls explizit räumen.
                onDismiss = {
                    focusManager.clearFocus()
                    composerPickerOpen = false
                },
                // WELLE-13 P9: Vorlesen-Regler — Persistenz passiert im Sheet
                // (ChatSpeechPrefs), der Spiegel hält die Antwort des Chats.
                currentReadAloud = composerReadAloud,
                onToggleReadAloud = { enabled -> composerReadAloud = enabled },
                // W16 (Fixliste 35): wählbare Modell-Liste — NUR der vom
                // Gateway/Adapter gelieferte Katalog dieses Agenten.
                gatewayModels = agentModelsLive[agent.agentId],
                // W19 (17.11.26): Gateway-Stand ANZEIGEN (Aktiv-Zeile) + als
                // Slider-Suggestion, solange der Nutzer keine App-Auswahl hat
                // (die App-Auswahl gewinnt weiterhin).
                gatewayCurrent = gatewayModelCurrent[agent.agentId],
                gatewayEffort = gatewayModelEffort[agent.agentId],
            )
        }
        // W16 (Fixliste 34): „Unterstützen“-Sheet am Herz-Tap (direkt im Chat,
        // play-only — GitHub ohne Billing-Route hat weder Herz noch Sheet).
        if (supporterSheetOpen && BuildConfig.EDITION_PLAY) {
            ChatSupporterSheet(
                activity = com.hermesandroid.bridge.i18n.AppLanguage.LocalAppActivity.current,
                onDismiss = { supporterSheetOpen = false },
            )
        }
        // ── W16 (Fixliste 32): Sheets + Lösch-Bestätigung des ⋮-Chat-Menüs ────
        if (agentSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = {
                    agentSheetOpen = false
                    sheetPictureError = false
                },
                containerColor = FlipsiColors.Panel,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.chat_menu_view_agent),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FlipsiColors.TextPrimary,
                    )
                    Spacer(Modifier.height(16.dp))
                    AgentAvatar(
                        agentId = agent.agentId,
                        name = agent.name,
                        diameter = 96.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${stringResource(R.string.agent_sheet_name_label)}: ${agent.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FlipsiColors.TextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (agent.online) "Online" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.agent_sheet_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (agent.agentId.isNotBlank()) {
                            // W16 (Fixliste 32a): Profilbild ändern → Galerie-Pick
                            // → AgentAvatarStore (app-privat JPEG-Kopie).
                            TextButton(onClick = {
                                sheetPictureError = false
                                avatarPicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts
                                            .PickVisualMedia.ImageOnly,
                                    ),
                                )
                            }) {
                                Text(
                                    stringResource(R.string.agent_avatar_change),
                                    color = FlipsiColors.Ember,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (hasAvatarPicture) {
                            TextButton(onClick = {
                                AgentAvatarStore.clearAvatar(compositionContext, agent.agentId)
                                sheetPictureError = false
                            }) { Text(stringResource(R.string.agent_avatar_remove), color = FlipsiColors.TextPrimary) }
                        }
                    }
                    if (sheetPictureError) {
                        Text(
                            stringResource(R.string.agent_avatar_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.SignalRedText,
                        )
                    }
                }
            }
        }
        if (designSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { designSheetOpen = false },
                containerColor = FlipsiColors.Panel,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    Text(
                        stringResource(R.string.design_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FlipsiColors.TextPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Zustands-Zeile liest den Prefs-Stand je Composition — die
                    // Wahl wirkt beim nächsten Rendern (ChatWallpaper liest selbst).
                    // Der Tick liest im Scope mit und abonniert damit die Wahl.
                    val wallpaperSet = remember(wallpaperTick) {
                        ChatWallpaperPrefs.wallpaperPath(compositionContext).isNotBlank()
                    }
                    Text(
                        if (wallpaperSet) stringResource(R.string.design_sheet_state_photo)
                        else stringResource(R.string.design_sheet_state_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    FilterChipFlipsi(
                        label = stringResource(R.string.design_wallpaper_standard),
                        active = !wallpaperSet,
                        onClick = {
                            // W16.3 (F6): Kopie + Prefs-Eintrag räumen (URI-AltStand
                            // fliegt mit dem Key-Renaming ohnehin nie mehr an).
                            ChatWallpaperPrefs.clearWallpaper(compositionContext)
                            wallpaperTick++
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    FilterChipFlipsi(
                        label = stringResource(R.string.design_wallpaper_photo),
                        active = wallpaperSet,
                        onClick = {
                            wallpaperPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts
                                        .PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                }
            }
        }
        if (confirmDeleteChat) {
            AlertDialog(
                onDismissRequest = { confirmDeleteChat = false },
                title = { Text(stringResource(R.string.chat_delete_title)) },
                text = { Text(stringResource(R.string.chat_delete_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteChat = false
                        onClearHistory()
                    }) {
                        Text(
                            stringResource(R.string.chat_delete_confirm),
                            color = FlipsiColors.SignalRedText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteChat = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
                containerColor = FlipsiColors.PanelElev,
            )
        }
    }
}

/**
 * Kopf der Conversation (W16, Fixliste 30a/32 — WA-Format): dynamisches
 * Profilbild je Agent (AgentAvatarStore, P32a — Tap = „Agent ansehen“) +
 * Agent-Name + Online-Status + ⋮-Chat-Menü (Agent ansehen/Chatdesign/Modell
 * & Denken/Mediathek öffnen/Chat löschen). Menü dynamisch je Agent, KEINE
 * hartcodierten Namen.
 * PH2-T10 (F2-S3): arbeitet der Agent gerade (chat.activity working=true),
 * zeigt der Kopf einen kleinen Spinner + 'arbeitet … (mm:ss)' — die Zeit
 * läuft sekündlich ab started_at (Empfang beim Agenten) hoch.
 * W4-B (Chat-Suche): Magnifier-Knopf am Chat-Kopf → Suchleiste + Treffer-
 * Liste + Sprung-Anker (ChatSearchRules/ConversationBody); der Knopf toggelt
 * (Suchleiste offen = Schließen-Semantik, A11y je Zustand). W16.1 (Fixliste
 * 36): der FP-Abgleich lebt als Eintrag im ⋮-Menü (Kopf-Button entfernt,
 * KEIN DOPPEL); der Header bleibt als Kontext-Zeile (wer der Chat ist).
 * W16 (Fixliste 32): im Mediathek-Modus ruht die Verlaufssuche (Suchleiste
 * lebt nur im Chat-Zweig) → der Magnifier entfällt dort.
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
    // W16 (Fixliste 32): Verlaufssuche nur im Chat-Zweig anbieten (Mediathek-
    // Overlay = kein Blind-Tap auf die versteckte Leiste).
    showSearchButton: Boolean = true,
    // W16 (Fixliste 32): ⋮-Menü-Aktionen — dynamisch je Agent.
    onMenuAgent: () -> Unit = {},
    onMenuDesign: () -> Unit = {},
    onMenuModel: () -> Unit = {},
    onMenuMediathek: () -> Unit = {},
    onMenuDelete: () -> Unit = {},
) {
    // Je Agent eigener Menü-Zustand (Agent-Wechsel schließt das Menü; kein
    // saveable — Rotation kehrt zu, Menü zu = natürlicher Grundstand).
    var menuOpen by remember(agent.id) { mutableStateOf(false) }
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
                contentDescription = stringResource(R.string.chat_list_back_a11y),
                tint = FlipsiColors.TextPrimary,
            )
        }
        // W16 (Fixliste 32a): dynamisches Profilbild — eigenes Foto oder
        // deterministische Farbe + Initial (AgentAvatarStore, derselbe Store
        // wie Bubble/Agenten-Tab). Tap öffnet „Agent ansehen“ (Header-Tap ↔
        // Sheet, WA-Muster); A11y-Beschilderung über contentDescription.
        // HIG-W19: Tap-Ziel 48 dp — 38-dp-Avatar sitzt zentriert in einer
        // 48-dp-Wrapper-Box (der Tap erfasst den Ring, nicht nur das Bild).
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onMenuAgent),
            contentAlignment = Alignment.Center,
        ) {
            AgentAvatar(
                agentId = agent.agentId,
                name = agent.name,
                diameter = 38.dp,
                description = stringResource(R.string.agent_avatar_a11y),
            )
        }
        Spacer(Modifier.width(10.dp))
        // W16-P32-Fix (Drill 05:50): der ungewichtete Titel-Text fraß die
        // Zeilenbreite — bei schmalen Flächen bekam das ⋮ (letztes Row-Kind)
        // ~0 px = unsichtbar + stumm (Such-/Mediathek-Modus: FP ruht → ⋮ OK).
        // Fix: Titel+Status in einer WEIGHT-Zeile (fill = true) mit Ellipsis —
        // sie schrumpfen und die Aktionen (Suche/Herz/FP/⋮) bleiben sichtbar.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                agent.name,
                style = MaterialTheme.typography.titleMedium,
                color = FlipsiColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            // Status-Zeichen klein neben dem Namen — Punkt + Text, nie nur Farbe (A11y).
            OnlineDot(online = agent.online)
            Spacer(Modifier.width(4.dp))
            Text(
                if (agent.online) "Online" else "Offline",
                style = MaterialTheme.typography.labelSmall,
                color = FlipsiColors.TextSecondary,
                maxLines = 1,
            )
        }
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
        // W16.1 (Fixliste 36): der FP-Abgleich-Button lebt NICHT mehr in der
        // Kopf-Zeile — er ist Eintrag im ⋮-Menü (derselbe onFpCheck-Callback,
        // sendet 'FP-ABGLEICH: <hex>' als verschlüsselte Nachricht; Agent
        // antwortet automatisch ✓ gleich / ✗ ABWEICHUNG als rot-Banner).
        // KEIN-DOPPEL: der alte Kopf-Button ist ENTFERNT (nicht nur versteckt);
        // damit stirbt auch die P32-Clip-Quelle (FP-Feld am schmalen Kopf)
        // samt der headerNarrow-Kompakt-Variante.
        // W4-B + W16 (Fixliste 32): Magnifier nur im Chat-Zweig anbieten —
        // im Mediathek-Overlay lebt die Suchleiste nicht (kein Blind-Tap);
        // A11y-Label je Zustand (nie nur Farbe).
        if (showSearchButton) {
            IconButton(onClick = onSearchToggle) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(
                        if (searchOpen) R.string.chat_search_close_a11y else R.string.chat_search_open_a11y,
                    ),
                    tint = FlipsiColors.TextPrimary,
                )
            }
        }
        // WELLE 13 (Sir 12.11.): Herz = Unterstützer-Knopf (IAP, unaufdringlich).
        // W16 (Fixliste 34): play-only — die GitHub-Edition kennt KEINE
        // Supporter-Route (Play-Billing), das Herz entfällt dort vollständig;
        // play öffnet DIREKT das Unterstützen-Sheet statt Umweg Einstellungen.
        if (BuildConfig.EDITION_PLAY) {
            IconButton(onClick = onSupport) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.support_a11y),
                    tint = FlipsiColors.TextPrimary,
                )
            }
        }
        // W16.1 (Fixliste 36): Hier stand der FP-Abgleich-Button (Breit-Label
        // + schmale „FP“-Icon-Variante unter 340 dp) — er wanderte ins ⋮-Menü.
        // Die Kopf-Zeile trägt jetzt nur Magnifier (+ play: Herz) + ⋮.
        // W16 (Fixliste 32): ⋮-Chat-Menü im Header (WA-Muster, Referenz-
        // Screenshot) — die 4 Sir-Punkte (Agent ansehen, Chatdesign, Mediathek
        // öffnen, Chat löschen) plus „Modell & Denken“ (der Welle-12-Picker
        // zog mit dem Composer-Umbau aus den Chips hierher, kein Feature-Ver-
        // lust). Dropdown dynamisch je Agent, KEINE hartcodierten Namen.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.chat_menu_a11y),
                    tint = FlipsiColors.TextPrimary,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_view_agent)) },
                    onClick = {
                        menuOpen = false
                        onMenuAgent()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_design)) },
                    onClick = {
                        menuOpen = false
                        onMenuDesign()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_model)) },
                    onClick = {
                        menuOpen = false
                        onMenuModel()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_mediathek)) },
                    onClick = {
                        menuOpen = false
                        onMenuMediathek()
                    },
                )
                // W16.1 (Fixliste 36): „FP-Abgleich“ im ⋮-Menü — war Kopf-Button;
                // letzter Eintrag vor der destruktiven Aktion (sicheres Ende).
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_fp)) },
                    onClick = {
                        menuOpen = false
                        onFpCheck()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.chat_menu_delete),
                            color = FlipsiColors.SignalRedText,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onMenuDelete()
                    },
                )
            }
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
                contentDescription = stringResource(R.string.chat_error_close_a11y),
                tint = FlipsiColors.SignalRedText,
            )
        }
    }
}

/**
 * W18-1a: Statuszeile des VERDECKTEN FP-Abgleichs unter dem Chat-Kopf —
 * bestanden (grün) oder Abweichung (rot, zusätzlich zum e2eBanner). Das
 * Abgleich-Ergebnis lebt im Kontrollframe-Verband (chat.fp_check(.enc)) und
 * erscheint bewusst NIEMALS als Chat-Bubble (keine Persist, kein Verlauf, keine
 * Notification — RelayClient.handleChatFpCheckResult). null = keine Zeile.
 */
@Composable
private fun FpCheckNotice(result: RelayClient.FpCheckResult?, onDismiss: () -> Unit) {
    if (result == null) {
        return
    }
    val tint = if (result.ok) FlipsiColors.SignalGreen else FlipsiColors.SignalRed
    val text = stringResource(
        if (result.ok) R.string.chat_fp_check_ok else R.string.chat_fp_check_failed,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = tint.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (result.ok) FlipsiColors.TextPrimary else FlipsiColors.SignalRedText,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.chat_error_close_a11y),
                tint = if (result.ok) tint else FlipsiColors.SignalRedText,
            )
        }
    }
}

// ── Medienart → String-Ressource ─────────────────────────────────────────────
// HIG-W19 (Demo-Befund): sampleAgents()/sampleMessages() ENTFERNT — keine
// erfundenen Chats mehr; eine leere Liste zeigt den ehrlichen Leerzustand.

/**
 * W15 (I18n): Media-Art → String-Ressource (DE/EN) für Bubble-Platzhalter
 * („[Datei] name (Größe)“). UI-Spiegel von MediaProtocol.kindLabel — jenes
 * bleibt als stabile Protokoll-/Test-Kennung bestehen.
 */
internal fun mediaKindRes(kind: String): Int = when (kind) {
    "image" -> R.string.media_kind_image
    "video" -> R.string.media_kind_video
    "voice" -> R.string.media_voice_title
    else -> R.string.media_kind_file
}

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
 * W22 A2 (P5): Dedupe-Text von Verlaufs-Einträgen — Call-Einträge tragen
 * KEINEN Anzeige-Text (leer), der Restore-/Merge-Schlüssel baut sich statt-
 * dessen aus den Call-Feldern (Richtung/Verpasst/Dauer): zwei Anrufe der-
 * selben Minute (z. B. verpasst + sofortiger zweiter Versuch) wären sonst
 * Schlüssel-Zwillinge und der Restore-Merge fände einen Weg. Beide Overloads
 * spiegeln Platte ([ChatHistoryStore.CallInfo]) und UI ([ChatCallInfo]) —
 * die Schlüssel-Teile sind identisch formatiert.
 */
private fun callDedupeText(text: String, call: ChatHistoryStore.CallInfo?): String =
    call?.let { "call\u0000${it.direction}\u0000${it.missed}\u0000${it.durationSec}" } ?: text

/** UI-Spiegel von [callDedupeText] (Live-Bubbles im Restore-Merge). */
private fun callDedupeText(text: String, call: ChatCallInfo?): String =
    call?.let { "call\u0000${it.direction}\u0000${it.missed}\u0000${it.durationSec}" } ?: text

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

// ── W16 (Fixliste 32): die Tab-Verband-Konstanten TAB_CHAT/TAB_MEDIATHEK und
// die ChatTabsRow-Komposable sind ENTFERNT — Mediathek wandert ins ⋮-Menü
// („Mediathek öffnen“) als Overlay, der Chat-Fenster ist pur.

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
        // HIG-W19 (Such-Doppel): derselbe Kapsel-Schnitt wie die Such-Kapsel
        // der Agenten-Liste (PanelElev, 24-dp-Radius, 16/10-Padding,
        // ic_search-Vector) statt OutlinedTextField-Mischstil.
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(FlipsiColors.PanelElev)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = FlipsiColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = FlipsiColors.TextPrimary,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(FlipsiColors.Ember),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FlipsiColors.TextSecondary,
                        )
                    }
                    inner()
                },
            )
        }
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
                            // HIG-W19 (Apple-Audit): Treffer-Label als TEXT
                            // braucht 4,5:1 — TextSecondary (6,63:1 auf Panel);
                            // LineStrong (3,09:1) allein für Grafik-Kanten.
                            color = FlipsiColors.TextSecondary,
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