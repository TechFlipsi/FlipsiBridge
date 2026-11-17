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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
 * SIRS-04: Anzeige-Zeile „Antwort nach X s“ — null = nicht anzeigen (unter
 * 100 ms = unter der Anzeige-Auflösung). Reiner JVM-Vertrag (ChatBubbleRulesTest
 * ruft ihn direkt); die Bubble selbst rendert den Text über die RESSOURCE
 * chat_reply_after_s (W15/i18n: löst live in DE/EN auf) — diese Funktion ist
 * Format- und Schwellwert-Pinne + Fallback-Template, keine zweite UI-Quelle.
 */
internal fun replyAfterLabel(ms: Long?): String? =
    if (ms == null || ms < ChatBubbleRules.MIN_REPLY_AFTER_MS) null
    else "Antwort nach ${"%.1f".format(ms / 1000.0)} s"

/**
 * SIRS-04: TalkBack-Beschreibung der Antwort-Latenz („Sekunden“ ausgeschrieben)
 * — reiner JVM-Vertrag wie [replyAfterLabel]; die UI nutzt chat_reply_after_long_s.
 */
internal fun replyAfterDescription(ms: Long): String =
    "Antwort nach ${"%.1f".format(ms / 1000.0)} Sekunden"

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
 * (Agent offline, Nachricht im Spool), delivered = zugestellt, read = GRÜN
 * gelesen. Zustand NIE nur über Farbe (A11y): TalkBack liest die vollen
 * Worte über contentDescription.
 * W15 (Sir-Fixliste 10): read = SignalGreen (Sir: „✓✓ GRÜN wenn gelesen",
 * WhatsApp-Muster) — vorher Ember, was sich nicht von 'zugestellt' abhob.
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
    // W15 (Sir-Fixliste 10): read = GRÜN (SignalGreen, WhatsApp-Muster) —
    // vorher Ember, was sich nicht von 'zugestellt' abhob.
    "read" -> StatusChip(
        stringResource(R.string.chat_status_read),
        FlipsiColors.SignalGreen,
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
 * 1 Häkchen = gesendet, 2 Häkchen = zugestellt, 2 Häkchen GRÜN
 * (SignalGreen, WhatsApp-Muster) = gelesen, rotes ✕ = fehlgeschlagen, Amber-
 * Uhr = wartet. A11y bleibt GEWAHRT: TalkBack liest die vollen Worte über
 * contentDescription — sie stehen bewusst NICHT als sichtbarer Text am
 * Screen (das ist die WhatsApp-Anmutung), die Bedeutung ist nie nur im
 * Bild/Konkurrenz (Beschreibung immer gesetzt).
 * Häkchen = selbst gezeichnete Canvas-Pfade (keine fremden Assets).
 */

/**
 * W15 (Sir-Fixliste 10): 3-Stufen-Häkchen-Grammatik einer EIGENEN Nach-
 * richt — reine Regel, JVM-getestet (ChatBubbleRulesTest):
 * 1 = EIN Häkchen (versendet), 2 = ZWEI Häkchen (zugestellt),
 * 3 = ZWEI Häkchen GRÜN (vom Agenten gelesen — Read-Ack-Kette: Relay
 * chat.read → chat.status 'read' → ChatStatusRank → Chip).
 * 0 = KEIN Häkchen (queued = Amber-Uhr, failed = rotes ✕ — diese Zweige
 * zeichnen in [DeliveryChecks] ihr eigenes Zeichen statt der Häkchen).
 */
internal fun deliveryCheckLevel(status: String?): Int = when (status) {
    "read" -> 3
    "delivered" -> 2
    "queued", "failed" -> 0
    else -> 1
}

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
                // W15 (Fixliste 10): Stufe aus der reinen Regel — Doppel-Häkchen
                // ab Stufe 2 (delivered), GRÜN liefert der tint aus statusChip
                // ('read' = SignalGreen).
                val double = deliveryCheckLevel(status) >= 2
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
 * Antworten links (Panel-Ton). Jede Agent-Antwort trägt in der Meta-Fußzeile
 * das kompakte „KI“-Badge — Kennzeichnungspflicht nach Art. 50 AI-Act (EU AI
 * Regulation); der Status ist damit nie nur über die Platzierung erkennbar
 * (TalkBack liest den Badge-Text mit). Zeitstempel klein in der Bubble.
 *
 * PH2-T10 (F2-S1/S2): [status] = Broker-Status der EIGENEN Nachricht
 * (queued/delivered/read/failed, null = nur gesendet) → Chip neben dem
 * Zeitstempel.
 * PH2-FIX (Jäger B7): [showStatusChip] = false (Demo-Eintrag ohne Backend)
 * → GAR KEIN Chip — '✓ Gesendet' ohne echten Versand wäre gelogen.
 * PH2-T11: [replyAfterMs] = Empfang→Antwort der AGENTEN-Antwort →
 * 'Antwort nach X s' unter dem Zeitstempel.
 * W16 (Fixliste 16a): der frühere [senderName]-Parameter ist ENTFERNT — der
 * Agenten-Name als Label-Spalte neben jeder Bubble entfällt; wer den Namen
 * liest, liest ihn im Chat-Kopf (der Zitat-Verband trägt seinen Namen über
 * den stored [ChatQuote]-Snapshot weiter — keiner Abhängigkeit zur UI-Param-
 * Kette).
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
 * WELLE-13 P11 (Sirs Bauvorlage): Radius 14 dp, eigene = EmberDim /
 * Agent = Panel, Zeitstempel + Status-Häkchen INSIDE unten-rechts, Bubble
 * max 75 % Breite — Medien-Bubbles erben die gleiche Silhouette über
 * dieselbe Surface. W15 (Fixliste 1): alle vier Ecken voll gerundet.
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
    status: String? = null,
    showStatusChip: Boolean = true,
    replyAfterMs: Long? = null,
    // MEDIA-T1: Upload-Fortschritt der EIGENEN Medien-Bubble (null = keine
    // Media-Nachricht oder kein laufender Upload) — W15 (Sir-Fixliste 10):
    // kleines Icon OHNE sichtbaren Text in der Meta-Zeile (⏳ läuft / rotes
    // ✕ fehlgeschlagen); A11y über contentDescription. Nach FERTIGEM Upload
    // zeigt die Zeile NICHTS mehr — die Zustell-Häkchen (✓/✓✓/✓✓grün)
    // übernehmen, kein doppelter Haken (Video-Befund: '✓ Übertragen (1
    // Chunks)' wirkte wie Debug-Text und drückte Zeit+Inhalt weg).
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
    // lokale Datei (mediaId vorhanden, localPath leer) — null = kein Download-
    // Dienst angebunden → ehrlicher Hinweis statt totem Button.
    onDownloadMedia: ((mediaId: String, onDone: (String?) -> Unit) -> Unit)? = null,
    // W16 P30/P32: Agenten-Avatar (AgentAvatarStore) für die TTS-Sprachnachricht-
    // Bubble — null = kein Kreis (B2-Vertrag: graceful, kein Deko-Fake).
    agentAvatar: (@Composable () -> Unit)? = null,
) {
    // W4-B (Chat-Suche): TalkBack-Beschreibung der Suchtreffer-Bubble.
    val searchHitDescription = stringResource(R.string.chat_search_hit_a11y)
    // WELLE-13 P9/W15: Stopp-A11y der vorgelesenen Bubble als Ressource (DE/EN).
    val stopReadAloudA11y = stringResource(R.string.chat_tts_stop_a11y)
    // W16 P30/P32 (Fixliste 31, Agenten-Richtung): TTS-/Audio-Flag — die
    // WA-Sprachnachricht-Bubble von B2 steht in der Agenten-Textnachricht,
    // solange sie vorgelesen (speakingIds) ODER ihre Datei-Wiedergabe läuft.
    // Mount-Garantie: der Play-Verdränger stoppt das Vorlesen (Engine → Datei);
    // die Bubble wäre sonst mitten im Sound aus dem Screen gefallen — solange
    // der tts-Key aktiv bleibt, bleibt der Mount, erst das Ende räumt ab.
    // (Chip s. unten: entfällt, solange die Bubble steht — B2-Vertrag
    // „anstelle des Speaking-Chips".)
    val ttsActiveKey by ChatVoicePlayback.activeKey.collectAsState()
    val showTtsBubble = message.fromAgent && message.media == null &&
        (speaking || ttsActiveKey == ChatVoicePlayback.ttsKey(message.id))
    // W16 (Fixliste 16a): KI-Metabadge — Text + TalkBack-Beschreibung einmal
    // hoisten (stringResource lebt NICHT im non-composable semantics-Lambda).
    val kiBadgeText = stringResource(R.string.chat_ki_badge)
    val kiBadgeA11y = stringResource(R.string.chat_ki_badge_a11y)
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
            // W16 (Sir-Fixliste 16a): die einstige Label-SPALTE links neben der
            // Agent-Bubble (Agenten-Name + „KI“-Badge) ist ENTFERNT — sinnloser
            // Platzverlust, der Chat-Kopf zeigt den Agenten schon. Die
            // AI-Act-Kennzeichnung (Art. 50) bleibt PFLICHT und lebt jetzt als
            // kompakter „KI“-Mini-BADGE in der Meta-Fußzeile der Bubble
            // (sichtbar + TalkBack-Beschreibung, siehe unten). Die Bubble
            // sitzt damit bündig am Rand.
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
                    // W15 (Sir-Fixliste 1): Radius 14 dp auf ALLEN vier Ecken —
                    // die bisherige kleine 3-dp-Ecke unten zur AbSender-Seite
                    // wirkte wie abgeschnitten (Sirs Screenshot-Befund), kein
                    // Tail-Bau. Farben: eigene = EmberDim (warm), Agent =
                    // Panel — Medien-Bubbles laufen durch dieselbe Surface
                    // automatisch mit (gleiche Silhouette).
                    shape = RoundedCornerShape(14.dp),
                    color = if (message.fromAgent) FlipsiColors.Panel else FlipsiColors.EmberDim,
                    // W4-B (Chat-Suche): Sprung-Highlight der Suche — Ember-Rand
                    // (A11y: zusätzlich contentDescription „Suchtreffer“, nie
                    // nur Farbe; Verkabelung in der Modifier-Kette oben).
                    border = if (searchHighlight) {
                        BorderStroke(2.dp, FlipsiColors.Ember)
                    } else null,
                ) {
                    // W15b (Fix A, Sir-Befund): die M3-Surface stapelt ihren
                    // Inhalt in einer BOX — die Meta-Zeile als weiteres Kind
                    // lag OBEN ueber der ersten Textzeile statt unten. Column:
                    // Zitat/Text/Medien oben, Metareihe ALS LETZTES Element
                    // unten (WhatsApp-Stil, rechtsbuendig klein).
                    Column {
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
                        // ── W16 P30/P32: Agenten-TTS-Sprachnachricht-Bubble ──
                        // Agenten-Textnachricht mit TTS-/Audio-Flag: B2s
                        // VoiceBubbleAgentTts (Play = synthesizeToFile-Datei,
                        // Verdränger stoppt das Vorlesen). Steht ANSTELLE des
                        // Speaking-Chips (Chip unten entfällt solange).
                        if (showTtsBubble) {
                            Box(
                                Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                            ) {
                                VoiceBubbleAgentTts(
                                    messageId = message.id,
                                    text = message.text,
                                    avatar = agentAvatar,
                                )
                            }
                        }
                        // ── WELLE-13 P11: Meta-Zeile INSIDE der Bubble (unten-rechts) ──
                        // Sirs Bauvorlage: Zeitstempel labelSmall/TextSecondary steht
                        // IN der Bubble, der Status-Chip direkt daneben — gleiches
                        // Silhouette-Bild für Text- UND Medien-Bubbles (die Zeile
                        // liegt in der Surface, nicht mehr unter der Bubble).
                        // W16 (Sir-Fixliste 16b): das innere fillMaxWidth() ist
                        // ENTFERNT — die Zeile ließ die Bubble auf die volle Max-
                        // Breite (75 %) aufstrecken, „Test“ renderte RIESIG. Die
                        // Zeile misst jetzt NUR ihren Inhalt; .align(End) hält sie
                        // unten-rechts, die Bubble wächst mit dem TEXT bis max 75 %.
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // W16 (AI-Act, Fixliste 16a): kompakte „KI“-Kennzeichnung
                            // je Agenten-Antwort IN der Meta-Fußzeile — sichtbar und
                            // TalkBack-beschriftet, ohne breite Label-Spalte.
                            if (message.fromAgent) {
                                Text(
                                    kiBadgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FlipsiColors.SignalAmber,
                                    modifier = Modifier
                                        .background(
                                            color = FlipsiColors.PanelElev,
                                            shape = RoundedCornerShape(6.dp),
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                        .semantics {
                                            contentDescription = kiBadgeA11y
                                        },
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            // WELLE-13 P9: Speaking-Chip — diese Agenten-Antwort wird
                            // gerade vorgelesen; Tap auf den Chip = Stopp (Queue leer).
                            // W16 P30/P32: entfällt, solange die TTS-Bubble steht
                            // (dort übernimmt der Play-Verdränger die Kontrolle).
                            if (message.fromAgent && speaking && !showTtsBubble) {
                                Row(
                                    Modifier
                                        // HIG-W19: Touch-Ziel 48 dp (war ~18 dp).
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(FlipsiColors.EmberDim)
                                        .clickable(onClick = onStopSpeaking)
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
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
                                // W15: Long-Press-Beschreibung VOR das semantics-
                                // Lambda ziehen — stringResource ist @Composable und
                                // darf nicht im Non-Composable-Lambda laufen.
                                val replyLongText = stringResource(
                                    R.string.chat_reply_after_long_s, replyText,
                                )
                                Text(
                                    stringResource(R.string.chat_reply_after_s, replyText),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FlipsiColors.TextSecondary,
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .semantics {
                                            contentDescription = replyLongText
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
                            // MEDIA-T1 + W15 (Sir-Fixliste 10): Upload-Fortschritt
                            // eigener Medien als kleines Icon OHNE sichtbaren Text
                            // (⏳ läuft, rotes ✕ fehlgeschlagen). FERTIGER Upload
                            // zeigt NICHTS — die Zustell-Häkchen übernehmen (kein
                            // doppelter Haken). A11y: TalkBack liest die volle
                            // Info über contentDescription, nie nur Farbe/Form.
                            val uploadFailed = !message.fromAgent && message.media != null &&
                                mediaUpload?.failed != null
                            val uploadRunning = !message.fromAgent && message.media != null &&
                                mediaUpload != null && mediaUpload.failed == null && !mediaUpload.done
                            // i18n: TalkBack-Texte aus String-Ressourcen (stringResource
                            // ist nicht im semantics-Lambda aufrufbar → vorher hoisten).
                            val uploadDesc = if (uploadFailed) {
                                stringResource(
                                    R.string.chat_upload_failed_desc,
                                    mediaUpload?.failed?.take(40) ?: "",
                                )
                            } else {
                                stringResource(
                                    R.string.chat_upload_progress_desc,
                                    mediaUpload?.ackedChunks ?: 0,
                                    mediaUpload?.totalChunks ?: 0,
                                )
                            }
                            if (uploadFailed || uploadRunning) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (uploadFailed) "✕" else "⏳",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uploadFailed) FlipsiColors.SignalRedText
                                            else FlipsiColors.TextSecondary,
                                    fontWeight = if (uploadFailed) FontWeight.Bold else null,
                                    modifier = Modifier.semantics {
                                        contentDescription = uploadDesc
                                    },
                                )
                            }
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
                // HIG-W19 (Apple-Audit): Label als TEXT braucht 4,5:1 — Ember
                // (5,93:1) respektive TextSecondary (6,63:1); LineStrong
                // (3,09:1) bleibt allein für die Grafik-Kante links.
                color = if (quote.fromAgent) FlipsiColors.Ember else FlipsiColors.TextSecondary,
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
        // W16 (Fixliste 32a): Avatar aus dem dynamischen AgentAvatarStore —
        // eigenes Profilbild = Foto, sonst deterministische Farbe + Initial
        // (derselbe Store wie Chat-Header/Bubble, B6 liest ihn ebenfalls).
        Box(Modifier.size(48.dp)) {
            AgentAvatar(
                agentId = agent.agentId,
                name = agent.name,
                diameter = 48.dp,
                modifier = Modifier.fillMaxSize(),
            )
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
            // HIG-W19: Touch-Ziel 48 dp (war ~30 dp) — der Clip bleibt, die
            // Zuhöhe wächst; heightIn zentriert per CenterVertically.
            .heightIn(min = 48.dp)
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
 * Eingabe-Leiste unten in der Conversation (W16, Sir-Fixliste 30a — Composer
 * im WhatsApp-Aufbau): EINE Pill-Zeile — links der Anhang-Knopf (der
 * „Smiley“-Slot der WA-Vorlage; hier funktional als Anhang gebaut, die App
 * hat kein Emoji-Panel), der das Textfeld trägt — und rechts AUSSERHALB der
 * Pill der RUNDE Send-/Mikro-Button INLINE (kein breites Feld, KEINE zweite
 * Buttons-Zeile unter dem Textfeld). Feld leer = Mikro, Text = Send — der
 * Icon-Morph folgt allein dem Feldstand; während einer Aufnahme zeigt der
 * Button den roten Mikro-Stop.
 * W16 P30b: standardmäßig rutscht die Hold-to-Record-Geste an den Knopf
 * ([onVoiceHoldStart]/[onVoiceHoldEnd]/[onVoiceHoldCancelState] — Halten =
 * Aufnahme, Loslassen = Senden, Wisch-links = Abbruch, < 0,5 s = Too-Short);
 * B1s Tap-Logik ([onVoiceToggle]/[toggleVoice]) bleibt als FALLBACK-Pfad
 * erhalten (TalkBack-Click, STOP-Tap bei laufender Fallback-Aufnahme,
 * Berechtigungs-Fortsetzung, Assist-Start). SIRS-05 bleibt gepinnt: leerer
 * Text sendet NIE den Text-Kanal.
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
    // W16 P30b (Fixliste 30b): Hold-to-Record-Verband (B2-API voiceHoldToRecord)
    // am runden Mikro-Knopf — Halten = Aufnahme, Loslassen = Senden, Wischen
    // nach Links = Abbruch, < 0,5 s = Too-Short. null = Verband nicht angebunden,
    // der Knopf fällt vollständig auf B1s Tap-Logik (onVoiceToggle) zurück.
    onVoiceHoldStart: (() -> Boolean)? = null,
    onVoiceHoldEnd: ((VoiceHoldOutcome, holdMs: Long) -> Unit)? = null,
    onVoiceHoldCancelState: ((cancelling: Boolean) -> Unit)? = null,
    // W16 (Fixliste 35) + W18 (Sir): Modell-Chip im Composer — KURZEL-Verband:
    // "A" = Auto (Agenten-Standard aus der Konfiguration), "M" = Manuel (für
    // diesen Chat explizit gewählt); direkter Ins-Sheet-Sprung (dasselbe
    // Modell & Denken-Sheet wie der ⋮-Punkt). null = Chip nicht verdrahtet.
    modelChipLabel: String? = null,
    onModelChip: (() -> Unit)? = null,
) {
    val canSend = canSendChat(value) && !voiceRecording
    // W16: A11y-Texte einmal hoisten (stringResource ist nicht im onClick-
    // Lambda aufrufbar).
    val sendA11y = stringResource(R.string.chat_send_a11y)
    val micStartA11y = stringResource(R.string.chat_mic_a11y_start)
    val micStopA11y = stringResource(R.string.chat_mic_a11y_stop)
    // W16 P30b: Press-Fanger der Hold-Geste — true = DIESE Press-Sequenz gehörte
    // der Geste (Aufnahme gestartet ODER der Berechtigungs-Dialog wurde vom
    // START_DENIED-Pfad geöffnet): der Klick des kurzen Taps (< 0,5 s Release)
    // stillstellen, das Release regelt onVoiceHoldEnd. Der Fanger räumt bei JEDEM
    // Down (kein Alt-Zustand über Press-Sequenzen hinweg); TalkBack-Clicks
    // (keine echten Pointer-Events) laufen unangetastet in B1s Fallback-Pfad.
    var holdTookPress by remember { mutableStateOf(false) }
    val holdStartCallback = onVoiceHoldStart
    val holdEndCallback = onVoiceHoldEnd
    Column(
        modifier = modifier
            // PH-FIX-CHAT-A (F2): 16 dp Außenabstand — bündig mit den Bubbles
            // (12 dp contentPadding + 8 dp Zeilen-Padding ergeben 20 dp).
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // EIN gerundetes Rund-Feld (Pill): Anhang links, Textfeld (Flex).
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(FlipsiColors.Panel)
                    .heightIn(min = 48.dp)
                    .padding(start = 2.dp, end = 10.dp),
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
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = FlipsiColors.TextPrimary,
                    ),
                    cursorBrush = SolidColor(FlipsiColors.Ember),
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        // W16.1 (Fixliste 37): Hinweis UND getippter Text sitzen
                        // vertikal MITTIG in der Pill — vorher top-bündig im
                        // 48dp-Feld („weiter oben“-Optik, Sir). Die min-Höhe
                        // bleibt hier statt am Kern, contentAlignment zentriert
                        // den Inhalt je Zeilenzahl (1 Zeile = mittig, mehr = füllt).
                        Box(
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
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
            }
            Spacer(Modifier.width(8.dp))
            // W16 (Fixliste 35) + W18: kompakter Modell-Chip ZWISCHEN Pill und
            // dem runden Knopf — Kürzel A (Auto/Konfiguration) bzw. M (manuell
            // für diesen Chat gewählt), öffnet DIREKT das Modell & Denken-Sheet
            // (dasselbe wie der ⋮-Punkt — kein Umweg).
            val chipA11y = stringResource(R.string.composer_chip_a11y)
            if (modelChipLabel != null && onModelChip != null) {
                Row(
                    modifier = Modifier
                        // HIG-W19: Touch-Ziel 48 dp (war min 36 dp).
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(FlipsiColors.PanelElev)
                        .clickable(onClick = onModelChip, onClickLabel = chipA11y)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modelChipLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            // RUNDEN Send-/Mikro-Button INLINE RECHTS im selben Row — der
            // Icon-Morph: leer+keine Aufnahme = Mikro (Aufnahme starten),
            // Text = Send (Ember), Aufnahme läuft = Mikro rot (Tap = Stop+Send).
            // Die frühere Buttons-Zeile UNTER dem Textfeld (Anhang, Modell-/
            // Denk-Chips, Mikro, Send) ist ENTFERNT (Fixliste 30-Nachtrag) —
            // der Modell-/Denken-Picker lebt nun im ⋮-Chat-Menü (ChatScreen).
            IconButton(
                onClick = {
                    if (holdTookPress) {
                        // W16 P30b: die Geste übernahm diese Press-Sequenz — der
                        // Klick des kurzen Taps regt B1s Toggle NICHT an (kein
                        // Doppel-Lauf gegen den Release-Verband onVoiceHoldEnd).
                        holdTookPress = false
                        return@IconButton
                    }
                    when {
                        voiceRecording -> onVoiceToggle?.invoke() // Stop + Senden
                        canSend -> onSend()
                        else -> onVoiceToggle?.invoke() // Aufnahme starten
                    }
                },
                enabled = voiceRecording || canSend || onVoiceToggle != null,
                modifier = Modifier
                    .size(44.dp)
                    // W16 P30b: Hold-to-Record-Geste am runden Knopf (B2-API).
                    // Die Geste konsumiert KEINE Events — der kurze Tap feuert
                    // zusätzlich den Click, deshalb stellt der Press-Fanger
                    // oben still; ein echter Haltezug bleibt click-frei
                    // (Clickable bricht den Tap beim Long-Press-Spiel ab).
                    .then(
                        if (holdStartCallback != null && holdEndCallback != null) {
                            Modifier.voiceHoldToRecord(
                                // JE Down: Alt-Fanger räumen, dann der Host-
                                // Start (true = Press übernommen).
                                onStart = {
                                    holdTookPress = false
                                    holdStartCallback().also { holdTookPress = it }
                                },
                                onEnd = { outcome, holdMs -> holdEndCallback(outcome, holdMs) },
                                onSwipeCancelState = onVoiceHoldCancelState,
                            )
                        } else {
                            Modifier
                        }
                    ),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                voiceRecording -> FlipsiColors.PanelElev
                                canSend -> FlipsiColors.Ember
                                else -> FlipsiColors.PanelElev
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val micMode = !canSend || voiceRecording
                    Icon(
                        painterResource(if (micMode) R.drawable.ic_mic else R.drawable.ic_send),
                        contentDescription = when {
                            voiceRecording -> micStopA11y
                            canSend -> sendA11y
                            else -> micStartA11y
                        },
                        tint = when {
                            voiceRecording -> FlipsiColors.SignalRed
                            canSend -> FlipsiColors.OnEmber
                            else -> FlipsiColors.TextSecondary
                        },
                    )
                }
            }
        }
    }
}