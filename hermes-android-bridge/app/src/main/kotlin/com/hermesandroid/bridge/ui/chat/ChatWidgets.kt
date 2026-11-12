package com.hermesandroid.bridge.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Job
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.FlipsiColors
import kotlinx.coroutines.launch

/**
 * Chat-Bausteine (PH-2-T3): Message-Bubble, Agenten-Zeile, Eingabe-Leiste.
 * Reine UI-Gerüst-Widgets — Backend-Anbindung folgt in PH-2-Main.
 */

// ── Daten-Modell (Gerüst — Backend-Anbindung folgt in PH-2-Main) ────────────

/**
 * MEDIA-T1: Media-Anhang einer Nachricht (null = reine Text-Bubble).
 * [localPath] = app-private Datei (empfangen: filesDir/chat_media, eigene:
 * Picker-Source oder Kopie) — nur VERWEIS, keine Bytes im Model.
 */
data class ChatMediaInfo(
    val mediaId: String,
    val kind: String,        // image/video/file/voice
    val name: String,
    val mime: String = "",
    val size: Long = 0L,
    val localPath: String = "",
)

/**
 * W4-B (Zitieren): Snapshot der zitierten Nachricht — bewusst WERTE statt
 * Verweis: die Bubble überlebt Prozess-Tod + Disk-Restore (ChatHistoryStore)
 * ohne Lookup, ein quoted_id-Verweis könnte nach evicteten Einträgen ins
 * Leere laufen. [senderName] leer/„Du“? — Label baut [ChatQuoteRules.quoteLabel].
 */
data class ChatQuote(
    val fromAgent: Boolean, // true = Agenten-Antwort wird beantwortet („KI“-Marke)
    val senderName: String, // Anzeigename des Agenten (leer = Demo-Eintrag)
    val text: String,       // Anzeige-Text des Originals (Media-Bubbles: Label)
)

/**
 * Eine Chat-Nachricht. [fromAgent] = true → Antwort des Agenten (KI) und
 * bekommt deshalb das „KI“-Badge (Art. 50 AI-Act-Kennzeichnungspflicht).
 * MEDIA-T1: [media] != null → Media-Bubble (Bild/Voice/Datei-Rendering in
 * [MessageBubble] statt Fließtext).
 * W4-B (Zitieren): [quoted] != null → beantwortete Nachricht — der Zitat-
 * Block rendert die zitierte Nachricht ÜBER dem Antwort-Text der Sende-/
 * Empfangs-Bubble (Messenger-Muster). [text] bleibt die REINE Antwort
 * (das Draht-Format mit Zitat baut ChatQuoteRules.wireText nur für Versand +
 * Platte).
 */
data class ChatMessage(
    val id: Long,
    val agentId: Int,
    val fromAgent: Boolean,
    val text: String,
    /** Anzeige-Zeitstempel „HH:mm“ — als String gehalten, damit der Saver primitiv bleibt. */
    val time: String,
    val media: ChatMediaInfo? = null,
    val quoted: ChatQuote? = null,
    /**
     * WELLE-13 P11 (Datum-Chip): Tages-Schlüssel „yyyy-MM-dd“ der Nachricht —
     * Grundlage der Tageswechsel-Gruppierung ([ChatDateRules] + mittiger
     * Datum-Chip im Verlauf). Leer = unbekannter Tag (z. B. alte Disk-Restore-
     * Einträge ohne Datums-Spalte) → KEIN Chip, ehrlich statt erfunden.
     */
    val date: String = "",
)

/**
 * Agenten-Eintrag der Chat-Liste (Anzeige-Modell, keine privaten Daten).
 * PH2-MERGE: [agentId] = echte Relay-ID (leer = Demo-Fallback-Eintrag ohne
 * Backend-Anbindung).
 */
data class ChatAgent(
    val id: Int,
    val name: String,
    val online: Boolean,
    val agentId: String = "",
)

/**
 * Saver für den Nachrichten-Verlauf in rememberSaveable: jede Nachricht wird
 * flach in primitive Werte zerlegt. MEDIA-T1: FESTE Gruppe pro Nachricht
 * (id, agentId, fromAgent, text, time, hasMedia, mediaId, kind, name, size,
 * path) — hat eine Nachricht kein Media, bleiben die Media-Slots leer (feste
 * Größe = robustes chunked-Restore, kein Gruppen-Verrutschen).
 *
 * W4-B (Zitieren): VERSIONIERTES Format — erstes Element der Flachliste ist
 * die Gruppen-Version, danach feste 14er-Gruppen (11 Basis-Slots + hasQuote,
 * quoteFromAgent, quoteSender, quoteText; die Zeit des ORIGINALS gehört
 * bewusst nicht ins Zitat-Snapshot-Format). Der Versions-Marker ist determi-
 * nistisch vom Legacy-Format trennbar: Vor-W4-Flachlisten beginnen mit der
 * Long-id, die W4-Flachliste mit dem Int-VERSION. Legacy-Bundles (Zwischen-
 * stand vor dieser Welle) werden ohne Zitat unverändert restauriert — kein
 * Gruppen-Verrutschen.
 *
 * WELLE-13 P11 (Datum-Chip): VERSION 3 — gleiche Slots wie v2 plus einen
 * date-Slot je Gruppe (Gruppe = 15). v2-Bundles (14er) und v1-Legacy-Bundles
 * (11er) werden anhand des Versions-Markers wieder korrekt restauriert;
 * v2-Einträge bekommen date = "" (kein Datum bekannt → kein Chip, kein Fake).
 */
private const val MSG_SAVER_LEGACY_GROUP = 11
private const val MSG_SAVER_GROUP_V2 = 14  // 11 Basis + hasQuote + fromAgent + sender + text
private const val MSG_SAVER_GROUP = 15     // v3 = v2 + date (Tageswechsel-Chip)
private const val MSG_SAVER_VERSION = 3

val ChatMessageListSaver = listSaver<List<ChatMessage>, Any>(
    save = { list ->
        val flat = list.flatMap { m ->
            listOf<Any>(
                m.id, m.agentId, m.fromAgent, m.text, m.time,
                m.media != null,
                m.media?.mediaId ?: "",
                m.media?.kind ?: "",
                m.media?.name ?: "",
                m.media?.size ?: 0L,
                m.media?.localPath ?: "",
                m.quoted != null,
                m.quoted?.fromAgent ?: false,
                m.quoted?.senderName ?: "",
                m.quoted?.text ?: "",
                m.date,
            )
        }
        listOf(MSG_SAVER_VERSION) + flat
    },
    restore = { flat ->
        // Versions-Marker: v1-Legacy beginnt mit der Long-id, v2+ mit Int.
        val version = (flat.firstOrNull() as? Int) ?: 1
        val versioned = version >= 2
        val body = if (versioned) flat.drop(1) else flat
        val group = when {
            version >= MSG_SAVER_VERSION -> MSG_SAVER_GROUP
            versioned -> MSG_SAVER_GROUP_V2
            else -> MSG_SAVER_LEGACY_GROUP
        }
        body.chunked(group).map { g ->
            val media = if (g[5] as Boolean) {
                ChatMediaInfo(
                    mediaId = g[6] as String,
                    kind = g[7] as String,
                    name = g[8] as String,
                    mime = "",
                    size = g[9] as Long,
                    localPath = g[10] as String,
                )
            } else null
            val quoted = if (versioned && g[11] as Boolean) {
                ChatQuote(
                    fromAgent = g[12] as Boolean,
                    senderName = g[13] as String,
                    text = g[14] as String,
                )
            } else null
            ChatMessage(
                id = g[0] as Long,
                agentId = g[1] as Int,
                fromAgent = g[2] as Boolean,
                text = g[3] as String,
                time = g[4] as String,
                media = media,
                quoted = quoted,
                // v3-Slot; v2/Legacy-Gruppen haben ihn nicht → "" (kein Chip).
                date = if (version >= MSG_SAVER_VERSION && g.size > 14) {
                    (g[14] as? String) ?: ""
                } else "",
            )
        }
    },
)

// ── Anzeige-Regeln (SIRS-04/SIRS-05 — JVM-getestet: ChatBubbleRulesTest) ────

/** Schwellwert-Objekt der Chat-Bubble-Regeln (SIRS-Fix-Welle). */
object ChatBubbleRules {
    /**
     * SIRS-04 (Volltest 03.11.2026): „Antwort nach 0.0 s“ wirkte wie eine
     * kaputte Messung (der Ehrlichkeits-Shim misst Empfang→Send, lokal real
     * <50 ms). Vertrag: unter 100 ms = unter der Anzeige-Auflösung → die
     * Zeile bleibt WEG; darüber steht die ECHTE Zeit (eine Nachkommastelle).
     */
    const val MIN_REPLY_AFTER_MS = 100L
}

/**
 * SIRS-04/W15: Sekunden-Wert der Antwort-Latenz — null = nicht anzeigen
 * (unter 100 ms). Der ANZEIGE-Text lebt als Ressource (chat_reply_after_s /
 * chat_reply_after_long_s) und löst unter dem AppLanguageGate live in DE/EN
 * auf; der formatierte Wert wird am Aufrufort übergeben.
 */
internal fun replyAfterSeconds(ms: Long?): Double? =
    if (ms == null || ms < ChatBubbleRules.MIN_REPLY_AFTER_MS) null else ms / 1000.0

/**
 * SIRS-05 (Volltest): Senden nur bei nicht-leerem Text — leer/Whitespace =
 * kein Versand; der Button ist disabled statt enabled zu bleiben und nur
 * still nichts zu tun. (Der Volltest-Dump empty-field-state.xml traf die
 * TextField-Zeile; die Regel ist jetzt explizit gepinnt und getestet.)
 */
internal fun canSendChat(text: String): Boolean = text.isNotBlank()

/**
 * PH-FIX-CHAT-A (F5): Regeln des Arbeitsstatus-Kopfes („arbeitet … (mm:ss)“).
 *
 * PIPELINE-BUGS-20261102 BUG 3: working=true blieb ohne jede Invalidierung
 * stehen — der Kopf-Spinner countete endlos, wenn working=false in drei
 * Live-Situationen nie ankam (E2E-Verwurfs-Pfad bei toter Session, Agent-Tod
 * ohne WS-Close-Erkennung der App, App-WS-Tod zwischen true und false).
 * Vertrag: nach [WORKING_TIMEOUT_MS] gilt der Zustand als verwaist →
 * Anzeige bricht ab. started_at liefert der Adapter mit (chat.activity-
 * Frames); die Räumung des Flow-Zustands bei Disconnect übernimmt
 * RelayClient — die UI folgt dem Flow automatisch.
 */
object WorkingStatusRules {

    /** Deckel des Arbeits-Spinners (Sirs längste Antwortzeiten ≪ 10 min). */
    const val WORKING_TIMEOUT_MS = 10 * 60_000L

    /**
     * true = der Working-State ist abgelaufen (oder unbrauchbar) und darf
     * keinen Spinner mehr zeigen. startedAt <= 0 = Müll-Wert → direkt tot;
     * eine kleine Uhr-Abweichung (startedAt in der Zukunft) zählt von 0 hoch
     * und läuft irgendwann in den Deckel.
     */
    fun timedOut(startedAt: Long, nowMs: Long): Boolean =
        startedAt <= 0L || nowMs - startedAt >= WORKING_TIMEOUT_MS
}

// ── Message-Bubble ──────────────────────────────────────────────────────────

/**
 * PH2-T10: Anzeige-Chip je Zustell-/Lese-Status einer EIGENEN Nachricht.
 * null/unbekannt = gesendet (App hat den Broker-Accept), queued = Wartet
 * (Agent offline, Nachricht im Spool), delivered = zugestellt, read = blau
 * gelesen. Zustand NIE nur über Farbe (A11y): Text bleibt lesbar.
 * PH2-FIX (Jäger B7): 'failed' = endgültig gescheiterter Send (RelayClient
 * meldete ok=false) → '✕ Fehlgeschlagen' in Rot statt des gelogenen
 * '✓ Gesendet'. SignalRedText statt SignalRed: BridgeTheme schreibt
 * SignalRed NUR für Flächen/Umrandungen vor (Text braucht ≥4,5:1 Kontrast).
 */
data class StatusChip(val label: String, val color: Color, val description: String)

@Composable
private fun statusChip(status: String?): StatusChip = when (status) {
    "queued" -> StatusChip(
        stringResource(R.string.chat_status_queued),
        FlipsiColors.SignalAmber,
        stringResource(R.string.chat_status_queued_desc),
    )
    "delivered" -> StatusChip(
        stringResource(R.string.chat_status_delivered),
        FlipsiColors.TextSecondary,
        stringResource(R.string.chat_status_delivered_desc),
    )
    "read" -> StatusChip(
        stringResource(R.string.chat_status_read),
        FlipsiColors.Ember,
        stringResource(R.string.chat_status_read_desc),
    )
    "failed" -> StatusChip(
        stringResource(R.string.chat_status_failed),
        FlipsiColors.SignalRedText,
        stringResource(R.string.chat_status_failed_desc),
    )
    else -> StatusChip(
        stringResource(R.string.chat_status_sent),
        FlipsiColors.TextSecondary,
        stringResource(R.string.chat_status_sent_desc),
    )
}

/**
 * WELLE 13 P11 (Sir: „Lesebestätigungs-Häkchen wie WhatsApp"): Status-NICHT
 * mehr als Wort-Text, sondern als Häkchen-Grammatik NEBEN dem Zeitstempel —
 * 1 Häkchen = gesendet, 2 Häkchen = zugestellt, 2 Häkchen in Akzentfarbe
 * (Ember statt WhatsApp-Blau) = gelesen, rotes ✕ = fehlgeschlagen, Amber-
 * Uhr = wartet. A11y bleibt GEWAHRT: TalkBack liest die vollen Worte über
 * contentDescription — sie stehen bewusst NICHT als sichtbarer Text am
 * Screen (das ist die WhatsApp-Anmutung), die Bedeutung ist nie nur im
 * Bild/Konkurrenz (Beschreibung immer gesetzt).
 * Häkchen = selbst gezeichnete Canvas-Pfade (keine fremden Assets).
 */
@Composable
fun DeliveryChecks(
    status: String?,
    tint: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(end = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            "queued" -> Text(
                "⏱",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
            "failed" -> Text(
                "✕",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Bold,
            )
            else -> {
                val double = status == "delivered" || status == "read"
                val pathColor = tint
                androidx.compose.foundation.Canvas(Modifier.width(20.dp).height(14.dp)) {
                    val stroke = 1.6.dp.toPx()
                    val seg = size.width / (if (double) 2.4f else 1.35f)
                    fun checkPath(x0: Float) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(x0 + seg * 0.18f, size.height * 0.62f)
                            lineTo(x0 + seg * 0.45f, size.height * 0.88f)
                            lineTo(x0 + seg * 0.82f, size.height * 0.18f)
                        }
                        drawPath(
                            path = p,
                            color = pathColor,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = stroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            ),
                        )
                    }
                    checkPath(0f)
                    if (double) checkPath(size.width * 0.40f)
                }
            }
        }
    }
}

/**
 * Eine Nachricht im Verlauf: eigene Nachrichten rechts (Ember-Ton), Agenten-
 * Antworten links (Panel-Ton). Jede Agent-Antwort trägt links das kleine
 * „KI“-Badge — Kennzeichnungspflicht nach Art. 50 AI-Act (EU AI Regulation);
 * der Status ist damit nie nur über die Platzierung erkennbar (TalkBack liest
 * den Badge-Text mit). Zeitstempel klein unter der Bubble.
 *
 * PH2-T10 (F2-S1/S2): [status] = Broker-Status der EIGENEN Nachricht
 * (queued/delivered/read/failed, null = nur gesendet) → Chip neben dem
 * Zeitstempel.
 * PH2-FIX (Jäger B7): [showStatusChip] = false (Demo-Eintrag ohne Backend)
 * → GAR KEIN Chip — '✓ Gesendet' ohne echten Versand wäre gelogen.
 * PH2-T11: [replyAfterMs] = Empfang→Antwort der AGENTEN-Antwort →
 * 'Antwort nach X s' unter dem Zeitstempel.
 * PH-FIX-CHAT-A (F3): [senderName] = Anzeigename des Agenten — Hauptlabel
 * neben dem „KI“-Badge (vorher stand hartkodiert „KI“ über jeder Antwort,
 * der Name des Agenten war der Bubble nicht bekannt). null = alter Zustand
 * (Demo-Eintrag ohne Backend).
 *
 * W4-B (Zitieren):
 * - [message.quoted] != null → Zitat-Block ÜBER dem Inhalt der Bubble
 *   (Vertrag: die zitierte Nachricht erscheint über der Sende-/Empfangs-
 *   Bubble) — [ChatQuoteBlock], Akzentkante nach Ursprung.
 * - [onReply] = Longpress auf der Bubble → Zitieren-Overlay (GLEICHE Wirkung
 *   über die Wischgeste nach rechts, W4-B Swipe-to-Reply). TalkBack liest
 *   onLongClickLabel mit (R.string.chat_quote_a11y).
 * - [chatReducedMotion] = Reduced-Pass-Faser (ChatMotionRules): steuert NUR
 *   die Settle-Anim der Geste-Rücklauf-Stelle (0 ms statt Tween).
 *
 * ## Drill-Acker (Bau-Welle, Emulator)
 * - Longpress auf Agenten-Bubble → Zitieren-Strip über der Eingabe-Leiste;
 *   Senden → Antwort-Bubble mit Zitat-Block darüber.
 * - Wisch nach rechts ≥ 32 dp → derselbe Strip (gleiche Wirkung); < Schwelle
 *   oder nach links → nichts, Bubble rollt zurück (Reduced: ohne Tween).
 * - Zitat eines DEMO-Eintrags (blank agentId) → Strip mit „KI“-Label, kein
 *   Relay-Versand (submitText-Fallback-Pfad).
 * - Rotation: Zitat-Block überlebt (Saver v2, 14er-Gruppen).
 * - TalkBack: onLongClickLabel „Nachricht zitieren“; Zitat-Block liest
 *   contentDescription „Zitierte Nachricht“ + Text mit.
 *
 * WELLE-13 P11 (Sirs Bauvorlage): Radius 14 dp mit kleiner (3 dp) unterer
 * Ecke zur ABSENDER-Seite (Agent: unten links, eigene: unten rechts),
 * eigene = EmberDim / Agent = Panel, Zeitstempel + Status-Chip INSIDE
 * unten-rechts, Bubble max 75 % Breite — Medien-Bubbles erben die gleiche
 * Silhouette über dieselbe Surface.
 * WELLE-13 P9: [speaking] = diese Agenten-Bubble wird gerade vorgelesen →
 * Speaking-Chip (Tap = Stopp) in der Meta-Zeile ([onStopSpeaking]).
 * WELLE-13 P8: [onDownloadMedia] = Holen-Verband für eingehende Medien ohne
 * lokale Datei (mediaId vorhanden, localPath leer) — null = kein Download-
 * Dienst → ehrlicher Hinweis in der Voice-Zeile statt totem Button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    senderName: String? = null,
    status: String? = null,
    showStatusChip: Boolean = true,
    replyAfterMs: Long? = null,
    // MEDIA-T1: Upload-Fortschritt der EIGENEN Medien-Bubble (null = keine
    // Media-Nachricht oder kein laufender Upload) — Zeile unter der Bubble:
    // 'Upload x/y Chunks', '✓ Übertragen' (alle Chunks vom Agenten geackt)
    // oder rot bei Fehlschlag.
    mediaUpload: RelayClient.MediaUploadState? = null,
    modifier: Modifier = Modifier,
    // W4-B (Zitieren): Longpress → Zitieren-Overlay (ConversationBody setzt
    // den Strip über der Eingabe-Leiste und den Zitat-Verband beim Senden).
    onReply: () -> Unit = {},
    // W4-B (Swipe-to-Reply): Reduced-Pass-Faser der Geste-Rücklauf-Stelle
    // (ChatMotionRules): true = Settle sofort, kein Tween.
    chatReducedMotion: Boolean = false,
    // W4-B (Chat-Suche): Sprung-Highlight (Ember-Rand + Text-Verband, nie
    // nur Farbe) — ~1,6 s je Sprung-Anker der ConversationBody-Suche.
    searchHighlight: Boolean = false,
    // WELLE-13 P9 (Vorlesen): true = genau DIESE Agenten-Bubble wird gerade
    // vorgelesen (ChatSpeechSpeaker-Verband) → Speaking-Chip mit Stopp-Tap.
    speaking: Boolean = false,
    onStopSpeaking: () -> Unit = {},
    // WELLE-13 P8 (UI-Verband): Holen-Callback für eingehende Medien OHNE
    // lokal gecachte Datei (mediaId vorhanden, localPath leer) — null = kein
    // Download-Dienst angebunden → ehrlicher Hinweis statt totem Button.
    onDownloadMedia: ((mediaId: String, onDone: (String?) -> Unit) -> Unit)? = null,
) {
    // W4-B (Chat-Suche): TalkBack-Beschreibung der Suchtreffer-Bubble.
    val searchHitDescription = stringResource(R.string.chat_search_hit_a11y)
    // WELLE-13 P9/W15: Stopp-A11y der vorgelesenen Bubble als Ressource (DE/EN).
    val stopReadAloudA11y = stringResource(R.string.chat_tts_stop_a11y)
    // PH-FIX-CHAT-A (F2): relative statt fixe 300-dp-Maximalbreite — auf
    // Sirs Handy (~360–400 dp) nahm eine lange Nachricht fast die volle
    // Breite ein (16 dp Außen + 12 dp Text-Padding = „randnah + gequetscht“).
    // WELLE-13 P11 (Sirs Bauvorlage): Bubble max ≈ 75 % der Listenbreite.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.75f
        // W4-B (Swipe-to-Reply): Geste-Faser — [swipeTravel] ist die SYNC-Wahrheit
        // der Finger-Übersetzung (je Drag-Puls direkt gesetzt, kein Coroutine-
        // Tween bzw. sofort bei Reduced-Motion, ChatMotionRules). Auslöser =
        // GLEICHE onReply-Wirkung wie Longpress; Callback über rememberUpdatedState
        // (Geste-Lauf bleibt stabil, kein stale Closure je Recomposition).
        var swipeTravel by remember(message.id) { mutableStateOf(0f) }
        val settleAnim = remember(message.id) { Animatable(0f) }
        // W4-FIX-JAG2 (C3): Handover-/Job-Regeln der Settle-Faser (siehe
        // settleSwipe) — je Geste-Sequenz genau EIN lebender Anim-Job.
        var settleHandover by remember(message.id) { mutableStateOf(false) }
        var settleJob by remember(message.id) { mutableStateOf<Job?>(null) }
        val swipeScope = rememberCoroutineScope()
        val replyCallback = rememberUpdatedState(onReply)
        val reducedState = rememberUpdatedState(chatReducedMotion)
        val density = LocalDensity.current
        val swipeTriggerPx = with(density) { ChatSwipeRules.TRIGGER_DP.dp.toPx() }
        val swipeMaxPx = with(density) { ChatSwipeRules.MAX_TRAVEL_DP.dp.toPx() }
        fun settleSwipe() {
            // Settle-Faser: aktuelle Fahrt in die Anim übergeben, Ziel 0.
            val from = swipeTravel
            if (reducedState.value) {
                // Reduced-Pass: OHNE Tween — Ziel ist schon erreicht (Sync-
                // Nullsetzen bleibt, kein Animations-Zweig zu übergeben).
                swipeTravel = 0f
                return
            }
            // W4-FIX-JAG2 (C3): der Handover an die Anim erzeugt KEIN sichtbares
            // Zwischen-Bild mit translationX = 0. Vorher stand der Sync-Nullsetzer
            // VOR dem suspend-Handover (snapTo lief im Coroutine-Lauf, der
            // Layer blendete 0 ein und sprang zur Übergabe-Fahrt zurück = Blip).
            // Jetzt: Handover-Flag + snapTo im selben Lauf — der Layer liest
            // während der Übergabe NUR die Anim (fortlaufend from → 0), sonst
            // nur die Sync-Fahrt; der Nullsetzer passiert nach dem snapTo.
            settleJob?.cancel()
            settleJob = swipeScope.launch {
                settleAnim.snapTo(from)
                settleHandover = true
                swipeTravel = 0f
                settleAnim.animateTo(0f, tween(ChatMotionRules.settleTweenMs(false)))
            }
        }
        fun cancelSettle() {
            // W4-FIX-JAG2 (C3): laufende Settle-Faser STOPPEN (Job-Cancel) statt
            // der Anim nur einen zweiten Snap hinterherzuschicken — der alte
            // animateTo lief gegen den neuen Snap (Ruck) und Jobs häuften sich
            // je Geste. Nach dem Cancel liest der Layer sofort wieder die Sync-
            // Fahrt (beim Drag-Start 0 = sichtbarer Abriss, wie im Drill-Verband
            // „Abriss ohne Doppelauslösen“); der Snap räumt nur den Anim-Stand.
            settleJob?.cancel()
            settleJob = null
            settleHandover = false
            swipeScope.launch { settleAnim.snapTo(0f) }
        }
        Row(
            // PH-FIX-CHAT-A (F2): 8 dp hier + 12 dp contentPadding der Liste
            // (ChatScreen) = 20 dp Außenabstand zum Bildschirmrand — der
            // Padding-Rhythmus liegt EINheitlich auf der Liste, nicht mehr
            // allein auf der Bubble-Zeile.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = if (message.fromAgent) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (message.fromAgent) {
                // Art. 50 AI-Act: KI-generierte Antwort sichtbar kennzeichnen.
                // PH-FIX-CHAT-A (F3): Agent-NAME als Hauptlabel, „KI“ als
                // kleines Badge darunter — das Badge selbst bleibt („KI“ war
                // vorher das EINZIGE Label; contentDescription unverändert,
                // TalkBack liest die Kennzeichnung weiter mit).
                Column(horizontalAlignment = Alignment.Start) {
                    senderName?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FlipsiColors.TextSecondary,
                        )
                    }
                    // Text-Badge „KI“ statt smart_toy-Icon: das Icon liegt
                    // nicht im icons-core-Set und eine neue Dependency ist
                    // tabu — ein Haus-Drawable kann mit PH-2-Main ergänzt
                    // werden.
                    Text(
                        "KI",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FlipsiColors.SignalAmber,
                        modifier = Modifier
                            .background(
                                color = FlipsiColors.Panel,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                            .semantics { contentDescription = "Von einer KI erzeugt" },
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Column(
                horizontalAlignment = if (message.fromAgent) Alignment.Start else Alignment.End,
            ) {
                Surface(
                    // Gestreckte Ecke unten zur Schreibseite, runde Ecken sonst;
                    // maximale Breite, damit lange Texte umbrechen statt randlos laufen.
                    // W4-B (Zitieren): Longpress → onReply (Zitieren-Overlay);
                    // onLongClickLabel als TalkBack-Aktion (A11y-Verband).
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onReply,
                            onLongClickLabel = stringResource(R.string.chat_quote_a11y),
                        )
                        // W4-B (Swipe-to-Reply): Finger-Folge über graphicsLayer
                        // (Layer-Redraw, keine Recomposition je Drag-Puls) +
                        // Geste-Sensor NACH der Click-Verkabelung.
                        // W4-FIX-JAG2 (C3): Handover-Quelle — während der Settle-
                        // Übergabe liest der Layer NUR die Anim (fortlaufend, kein
                        // Blip), im Drag nur die Sync-Fahrt. Die alte Summe
                        // (swipeTravel + settleAnim.value) verzählte beim Handover
                        // zwangsweise (0+f oder f+f je Ausspiel-Reihenfolge).
                        .graphicsLayer {
                            translationX = if (settleHandover) settleAnim.value else swipeTravel
                        }
                        .pointerInput(Unit) {
                            var fired = false
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    // Neue Drag-Sequenz: Latch zurücksetzen,
                                    // laufende Settle-Anim abreißen (Snap auf 0).
                                    fired = false
                                    cancelSettle()
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    if (fired) {
                                        // Ausgelöst: Rest-Fahrt stilllegen.
                                        return@detectHorizontalDragGestures
                                    }
                                    // SYNC-Fahrt: direkt setzen (kein Coroutine-Lag);
                                    // Klemmung [0, MAX] = Links-Fahrt nie sichtbar.
                                    swipeTravel = ChatSwipeRules.clampTravel(
                                        swipeTravel + dragAmount,
                                        swipeMaxPx,
                                    )
                                    // NUR nach rechts (Links-Fahrt bleibt
                                    // Listen-Fling, clampTravel hält bei 0).
                                    if (ChatSwipeRules.triggered(swipeTravel, swipeTriggerPx)) {
                                        fired = true
                                        settleSwipe()
                                        replyCallback.value()
                                    }
                                },
                                onDragEnd = { settleSwipe() },
                                onDragCancel = { settleSwipe() },
                            )
                        }
                        .then(
                            // W4-B (Chat-Suche): Sprung-Highlight — Ember-Rand
                            // + TalkBack-Verband „Suchtreffer“ (nie nur Farbe).
                            if (searchHighlight) {
                                Modifier.semantics {
                                    contentDescription = searchHitDescription
                                }
                            } else {
                                Modifier
                            }
                        ),
                    // WELLE-13 P11 (Sirs Bauvorlage): Radius 14 dp überall,
                    // nur die untere Ecke zur ABSENDER-Seite bleibt klein
                    // (3 dp, flach-modern — kein Tail-Bau) → Silhouette liest
                    // sich als Sprechblase ohne Kopierte Grafik. Farben: eigene
                    // = EmberDim (warm), Agent = Panel — Medien-Bubbles laufen
                    // durch dieselbe Surface automatisch mit (gleiche Silhouette).
                    shape = if (message.fromAgent) {
                        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 3.dp, bottomEnd = 14.dp)
                    } else {
                        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 3.dp)
                    },
                    color = if (message.fromAgent) FlipsiColors.Panel else FlipsiColors.EmberDim,
                    // W4-B (Chat-Suche): Sprung-Highlight der Suche — Ember-Rand
                    // (A11y: zusätzlich contentDescription „Suchtreffer“, nie
                    // nur Farbe; Verkabelung in der Modifier-Kette oben).
                    border = if (searchHighlight) {
                        BorderStroke(2.dp, FlipsiColors.Ember)
                    } else null,
                ) {
                    // W4-B (Zitieren): Vertrag — die zitierte Nachricht erscheint
                    // ÜBER der Sende-/Empfangs-Bubble (Messenger-Muster).
                    message.quoted?.let { quote -> ChatQuoteBlock(quote = quote) }
                    if (message.media != null) {
                        // MEDIA-T1: Media-Bubble je Art (Bild/Voice/Datei/Video).
                        // WELLE-13 P8: Holen-Verband für eingehende Medien
                        // durchgereicht (localPath leer + mediaId vorhanden).
                        MediaBubbleContent(
                            media = message.media,
                            fromAgent = message.fromAgent,
                            text = message.text,
                            onDownloadMedia = onDownloadMedia,
                        )
                    } else {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.fromAgent) FlipsiColors.TextPrimary else Color(0xFFFCE9DC),
                            // PH-FIX-CHAT-A (F2): 14/10 dp Text-Padding (vorher 12/8) —
                            // Luft zwischen Text und Bildschirmrand, Messenger-Muster.
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                        )
                    }
                    // ── WELLE-13 P11: Meta-Zeile INSIDE der Bubble (unten-rechts) ──
                    // Sirs Bauvorlage: Zeitstempel labelSmall/TextSecondary steht
                    // IN der Bubble, der Status-Chip direkt daneben — gleiches
                    // Silhouette-Bild für Text- UND Medien-Bubbles (die Zeile
                    // liegt in der Surface, nicht mehr unter der Bubble).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // WELLE-13 P9: Speaking-Chip — diese Agenten-Antwort wird
                        // gerade vorgelesen; Tap auf den Chip = Stopp (Queue leer).
                        if (message.fromAgent && speaking) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(FlipsiColors.EmberDim)
                                    .clickable(onClick = onStopSpeaking)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .semantics { contentDescription = stopReadAloudA11y },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.chat_tts_chip),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FlipsiColors.Ember,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        // PH2-T11: Agenten-Antwort mit Antwort-Latenz ('Antwort nach X s').
                        // SIRS-04: unter 100 ms bleibt die Zeile WEG — 'Antwort nach
                        // 0.0 s' war eine Anzeige unter der Messauflösung (kein echtes
                        // Timing), darüber steht die echte Zeit.
                        val replySeconds = replyAfterSeconds(replyAfterMs)
                        if (message.fromAgent && replySeconds != null) {
                            // W15: Text als Ressource — "%.1f".format folgt dem
                            // App-Locale (AppLanguageGate setzt Locale.setDefault).
                            val replyText = "%.1f".format(replySeconds)
                            Text(
                                stringResource(R.string.chat_reply_after_s, replyText),
                                style = MaterialTheme.typography.labelSmall,
                                color = FlipsiColors.TextSecondary,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .semantics {
                                        contentDescription = stringResource(
                                            R.string.chat_reply_after_long_s, replyText,
                                        )
                                    },
                            )
                        }
                        // PH2-T10: Zustell-/Lese-Chip je EIGENER Nachricht (rechts
                        // neben der Zeit). PH2-FIX (Jäger B7): Demo-Eintrag → gar
                        // kein Chip (kein Backend).
                        if (!message.fromAgent && showStatusChip) {
                            val chip = statusChip(status)
                        DeliveryChecks(
                            status = status,
                            tint = chip.color,
                            description = chip.description,
                        )
                        }
                        Text(
                            message.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = FlipsiColors.TextSecondary,
                        )
                        // MEDIA-T1: Upload-Fortschritt eigener Medien (neben der Zeit,
                        // nie nur Farbe — Text-Chip, TalkBack-lesbar).
                        if (!message.fromAgent && message.media != null && mediaUpload != null) {
                            val progress = when {
                                mediaUpload.failed != null ->
                                    stringResource(R.string.chat_upload_failed, mediaUpload.failed!!.take(40)) to
                                        FlipsiColors.SignalRedText
                                mediaUpload.done ->
                                    stringResource(R.string.chat_upload_done, mediaUpload.totalChunks) to
                                        FlipsiColors.SignalGreen
                                else -> stringResource(
                                    R.string.chat_upload_progress,
                                    mediaUpload.ackedChunks,
                                    mediaUpload.totalChunks,
                                ) to FlipsiColors.TextSecondary
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                progress.first,
                                style = MaterialTheme.typography.labelSmall,
                                color = progress.second,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── WELLE-13 P11: Datum-Chip (Tageswechsel-Gruppierung) ──────────────────────

/**
 * WELLE-13 P11 (Datum-Chip): Regeln der Tageswechsel-Gruppierung — reine
 * Funktionen, JVM-getestet (ChatDateRulesTest). Der Verlauf zeigt bei jedem
 * Tageswechsel (und über der ersten Nachricht) einen mittigen Datum-Chip
 * (PanelElev-Pill). Nachrichten ohne Tages-Schlüssel (alte Disk-Restore-
 * Einträge, Demo-Zeilen) bekommen BEWUSST keinen Chip — ein erfundenes
 * Datum wäre gelogen, „kein Chip“ ist ehrlich.
 */
object ChatDateRules {

    /** Tages-Schlüssel aus Unix-Millisekunden: „yyyy-MM-dd“ (stabil sortierbar). */
    fun dayKey(ts: Long): String =
        java.time.LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(ts),
            java.time.ZoneId.systemDefault(),
        ).toString()

    /**
     * true = vor dieser Nachricht gehört ein Datum-Chip in den Verlauf:
     * Datum bekannt UND verschieden vom Vorgänger (null = erste Nachricht
     * der Liste → Chip, wenn das Datum bekannt ist).
     */
    fun chipVisible(prevDate: String?, date: String): Boolean =
        date.isNotBlank() && date != prevDate

    /**
     * Anzeige-Label des Tages: „Heute“ / „Gestern“ / sonst ausgeschrieben
     * („Mittwoch, 11. November“). Unbraubares Datum → null (kein Chip).
     */
    fun dayLabel(date: String, todayKey: String, yesterdayKey: String): String? {
        if (date.isBlank()) return null
        return when (date) {
            todayKey -> "Heute"
            yesterdayKey -> "Gestern"
            else -> runCatching {
                java.time.LocalDate.parse(date).format(
                    java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM", java.util.Locale.GERMANY),
                )
            }.getOrNull()
        }
    }
}

/**
 * WELLE-13 P11: mittiger Datum-Chip des Tageswechsels — kleine PanelElev-
 * Pill (labelSmall, TextSecondary), volle Breite zentriert darüber im
 * Verlauf. A11y: reiner Text-Chip, kein Farb-Einzelkriterium.
 */
@Composable
internal fun ChatDateChip(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = FlipsiColors.TextSecondary,
            modifier = Modifier
                .background(color = FlipsiColors.PanelElev, shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = "Datum $label" },
        )
    }
}

// ── W4-B (Zitieren): Zitat-Block + Zitieren-Strip ────────────────────────────

/**
 * W4-B (Zitieren): Zitat-Block IN der Sende-/Empfangs-Bubble — die zitierte
 * Nachricht erscheint ÜBER der Antwort (Vertrag). Dunkles Panel + Akzent-
 * kante je Ursprung: Ember = Agenten-Antwort beantwortet, LineStrong = eigene
 * Nachricht beantwortet; Text immer voll lesbar (nie nur Farbe).
 *
 * ## Drill-Acker (Bau-Welle, Emulator)
 * - Eigene Antwort auf Agenten-Bubble → Ember-Kante, Label = Agent-Name.
 * - Antwort auf eigene Bubble → graue Kante, Label „Du“.
 * - Aufgetrennter Text: maxLines = 2 + Ellipsis (kein Endlos-Zitat in der
 *   Bubble; das Draht-Limit cappen über MAX_QUOTE_CHARS).
 */
@Composable
internal fun ChatQuoteBlock(quote: ChatQuote, modifier: Modifier = Modifier) {
    val label = ChatQuoteRules.quoteLabel(quote.fromAgent, quote.senderName)
    val blockDescription = stringResource(R.string.chat_quote_block_a11y, label)
    val accent = if (quote.fromAgent) FlipsiColors.Ember else FlipsiColors.LineStrong
    Row(
        modifier = modifier
            .padding(start = 10.dp, end = 10.dp, top = 8.dp)
            .background(color = FlipsiColors.Panel, shape = RoundedCornerShape(6.dp))
            .semantics { contentDescription = blockDescription } // Zitat-Text liest mit
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 28.dp)
                .background(color = accent, shape = RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(
                quote.text,
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * W4-B (Zitieren): Zitieren-Strip ÜBER der Eingabe-Leiste (das Antwort-Overlay
 * nach Longpress auf eine Bubble) — Quelle der Antwort + Original-Zitat + X
 * zum Abwählen. Der Strip hängt am replyQuote-State der Konversation in
 * ChatScreen: Senden baut den Draht-Text (ChatQuoteRules.wireText) und den
 * Zitat-Block.
 *
 * ## Drill-Acker (Bau-Welle, Emulator)
 * - Longpress auf Bubble → Strip erscheint, Eingabe fokussierbar bleibt.
 * - X im Strip → Strip weg, Senden ohne Zitat (reine Bubble).
 * - Strip überlebt das Tippen in das Textfeld (kein Auto-Dismiss).
 * - TalkBack: Label „Antworten an …“, X liest Entfernen-Beschreibung.
 */
@Composable
internal fun ReplyQuoteStrip(quote: ChatQuote, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = FlipsiColors.PanelElev)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 30.dp)
                .background(
                    color = if (quote.fromAgent) FlipsiColors.Ember else FlipsiColors.LineStrong,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.chat_reply_strip_label,
                    ChatQuoteRules.quoteLabel(quote.fromAgent, quote.senderName),
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.Ember,
            )
            Text(
                quote.text,
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.chat_reply_cancel_a11y),
                tint = FlipsiColors.TextPrimary,
            )
        }
    }
}

// ── Agenten-Zeile ────────────────────────────────────────────────────────────

/**
 * Eintrag der Agenten-Liste: Online-Punkt (grün/grau), Anzeigename und
 * Vorschau-Zeile der letzten Nachricht. Touch-Ziel ≥ 48 dp via heightIn(min).
 */
@Composable
fun AgentRow(
    agent: ChatAgent,
    preview: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // WELLE-13 P11b (Sirs Wunschbild „Chat-Menü mit mehreren Agenten"):
    // Avatar-Kreis (PanelElev + Ember-Initial) mit Online-Ampel am Fuß,
    // Name bold, 1-Zeiler-Preview — Zeile ≈ 72 dp Kapsel-Gefühl.
    Row(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(FlipsiColors.PanelElev, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    agent.name.trim().take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = FlipsiColors.Ember,
                )
            }
            OnlineDot(
                online = agent.online,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                agent.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextPrimary,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * WELLE-13 P11b: Filter-Chip der Agenten-Liste. Aktiv = Ember-Füllung mit
 * dunklem Text (Kontrast-Kette), inaktiv = Fläche. Bedeutung NIE nur über
 * Farbe: aktive Chips bekommen zusätzlich ein ✓-Zeichen.
 */
@Composable
fun FilterChipFlipsi(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) FlipsiColors.Ember else FlipsiColors.PanelElev)
            .clickable(onClick = onClick, role = Role.Checkbox)
            .semantics(mergeDescendants = true) { selected = active; contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Text(
                "✓",
                style = MaterialTheme.typography.labelMedium,
                color = FlipsiColors.OnEmber,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) FlipsiColors.OnEmber else FlipsiColors.TextSecondary,
        )
    }
}

/**
 * Online-Punkt: grün = erreichbar, grau = offline. Zustand NIE nur über
 * Farbe (A11y-Regel) → contentDescription zusätzlich als Text.
 * (Nicht-private, weil ConversationHeader in ChatScreen.kt ihn mit nutzt.)
 */
@Composable
fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    val label = if (online) "Online" else "Offline"
    Box(
        modifier = modifier
            .size(10.dp)
            .background(
                color = if (online) FlipsiColors.SignalGreen else FlipsiColors.TextSecondary,
                shape = CircleShape,
            )
            .semantics { contentDescription = label },
    )
}

// ── Eingabe-Leiste ──────────────────────────────────────────────────────────

/**
 * Eingabe-Leiste unten in der Conversation: Anhang-Button (MEDIA-T1) +
 * Text-Feld + Senden-Button. Der Senden-Button ist nur bei nicht-leerem
 * Text aktiv (Zustand ist über enabled sichtbar, nicht nur über Farbe).
 * MEDIA-T1: [onAttach] öffnet den Medien-Picker (Foto/Video, Datei,
 * Sprachnachricht); ein laufender Voice-Record ([voiceRecording]) tauscht
 * Senden → Stopp/Senden-Aus.
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onAttach: (() -> Unit)? = null,
    voiceRecording: Boolean = false,
    onVoiceToggle: (() -> Unit)? = null,
    // Komposer Welle 12 (Referenz-Übernahme): Meta-Reihe mit Modell-/Denk-
    // Chip (öffnet die Auswahl-Sheet) im Flipsi-Pill-Look.
    modelLabel: String = "Auto",
    effortLabel: String? = null,
    onOpenPicker: () -> Unit = {},
) {
    val canSend = canSendChat(value)
    Column(
        modifier = modifier
            // PH-FIX-CHAT-A (F2): 16 dp Außenabstand — bündig mit den Bubbles
            // (12 dp contentPadding + 8 dp Zeilen-Padding ergeben 20 dp).
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Referenz-Form: EIN abgerundetes Pill-Panel, oben das Textfeld
        // (ohne eigenen Kasten), darunter die Meta-Reihe (Anhang, Modell/
        // Denken-Chips, Mikrofon, Send-Kreis). Flipsi-Farben: Panel,
        // TextPrimary/Secondary, Ember-Aktion, SignalRed-Record.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(FlipsiColors.Panel)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = FlipsiColors.TextPrimary,
                ),
                cursorBrush = SolidColor(FlipsiColors.Ember),
                maxLines = 4,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                if (voiceRecording) stringResource(R.string.chat_input_hint_recording)
                                else stringResource(R.string.chat_input_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = FlipsiColors.TextSecondary,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onAttach != null) {
                    IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.chat_attach_a11y),
                            tint = FlipsiColors.TextSecondary,
                        )
                    }
                }
                // Modell-Chip (Referenz: 'Auto'/Kurzname am Komposer — Tippen
                // öffnet die Auswahl-Sheet).
                // WELLE-13 P6 (Auto-Chip-Bug, Fix): Hitbox auf ≥ 48 dp gehoben
                // (vorher heightIn(min = 32 dp) — unter der Touchhöhen-Pflicht
                // der Checkliste F; der Chip war schwer treffbar). Der Tap-Pfad
                // ChatInputBar → onOpenPicker → composerPickerOpen war korrekt
                // verdrahtet; das Sheet blieb trotzdem weg, weil ChatModelSheet
                // bei blank agentId still returnete — das zweite Bein des Fixes
                // liegt in ComposerUi.kt.
                // W15: A11y-Text des Picker-Chips als Ressource (DE/EN, mit Modell-Name).
                val modelPickerA11y = stringResource(R.string.chat_model_picker_a11y, modelLabel)
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(FlipsiColors.PanelElev)
                        .clickable(onClick = onOpenPicker)
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = modelPickerA11y },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modelLabel.take(14),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (modelLabel == "Auto") FlipsiColors.TextSecondary
                                else FlipsiColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (effortLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    // WELLE-13 P6: gleiche 48-dp-Hitbox-Regel für den Denk-Chip.
                    Row(
                        Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(FlipsiColors.EmberDim)
                            .clickable(onClick = onOpenPicker)
                            .padding(horizontal = 12.dp)
                            .semantics { contentDescription = "Denkstufe: $effortLabel" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Denk: $effortLabel",
                            style = MaterialTheme.typography.labelLarge,
                            color = FlipsiColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onVoiceToggle != null) {
                    IconButton(onClick = onVoiceToggle, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_mic),
                            contentDescription = if (voiceRecording)
                                "Sprachnachricht beenden und senden"
                            else "Sprachnachricht aufnehmen",
                            tint = if (voiceRecording) FlipsiColors.SignalRed
                                   else FlipsiColors.TextSecondary,
                        )
                    }
                }
                // KOMPOSER WELLE 12: Send-Kreis (Referenz: runder Button mit
                // ↑/Send); Ember-Fläche wenn send-fähig, gedimmt sonst —
                // A11y via IconButton + Beschreibung, SIRS-05 bleibt gepinnt.
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (canSend) FlipsiColors.Ember
                                        else FlipsiColors.PanelElev),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_send),
                            // W15: Senden-A11y als Ressource (DE/EN).
                            contentDescription = stringResource(R.string.chat_send_a11y),
                            tint = if (canSend) FlipsiColors.OnEmber
                                   else FlipsiColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}