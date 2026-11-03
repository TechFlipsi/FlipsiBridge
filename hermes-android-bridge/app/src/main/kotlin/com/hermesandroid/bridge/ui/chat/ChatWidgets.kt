package com.hermesandroid.bridge.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.FlipsiColors

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
 * Eine Chat-Nachricht. [fromAgent] = true → Antwort des Agenten (KI) und
 * bekommt deshalb das „KI“-Badge (Art. 50 AI-Act-Kennzeichnungspflicht).
 * MEDIA-T1: [media] != null → Media-Bubble (Bild/Voice/Datei-Rendering in
 * [MessageBubble] statt Fließtext).
 */
data class ChatMessage(
    val id: Long,
    val agentId: Int,
    val fromAgent: Boolean,
    val text: String,
    /** Anzeige-Zeitstempel „HH:mm“ — als String gehalten, damit der Saver primitiv bleibt. */
    val time: String,
    val media: ChatMediaInfo? = null,
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
 * flach in primitive Werte zerlegt. MEDIA-T1: FESTE 11er-Gruppe pro
 * Nachricht (id, agentId, fromAgent, text, time, hasMedia, mediaId, kind,
 * name, size, path) — hat eine Nachricht kein Media, bleiben die Media-
 * Slots leer (feste Größe = robustes chunked-Restore, kein Gruppen-Verrutschen).
 */
val ChatMessageListSaver = listSaver<List<ChatMessage>, Any>(
    save = { list ->
        list.flatMap { m ->
            listOf(
                m.id, m.agentId, m.fromAgent, m.text, m.time,
                m.media != null,
                m.media?.mediaId ?: "",
                m.media?.kind ?: "",
                m.media?.name ?: "",
                m.media?.size ?: 0L,
                m.media?.localPath ?: "",
            )
        }
    },
    restore = { flat ->
        flat.chunked(11).map { g ->
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
            ChatMessage(
                id = g[0] as Long,
                agentId = g[1] as Int,
                fromAgent = g[2] as Boolean,
                text = g[3] as String,
                time = g[4] as String,
                media = media,
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

/** SIRS-04: Anzeige-Zeile „Antwort nach X s“ — null = nicht anzeigen. */
internal fun replyAfterLabel(ms: Long?): String? =
    if (ms == null || ms < ChatBubbleRules.MIN_REPLY_AFTER_MS) null
    else "Antwort nach ${"%.1f".format(ms / 1000.0)} s"

/** SIRS-04: TalkBack-Beschreibung der Antwort-Latenz („Sekunden“ ausgeschrieben). */
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
    "queued" -> StatusChip("⏱ Wartet", FlipsiColors.SignalAmber, "Nachricht wartet im Relay-Spool")
    "delivered" -> StatusChip("✓✓ Zugestellt", FlipsiColors.TextSecondary, "Beim Agenten zugestellt")
    "read" -> StatusChip("✓✓ Gelesen", Color(0xFF58A6FF), "Vom Agenten gelesen")
    "failed" -> StatusChip("✕ Fehlgeschlagen", FlipsiColors.SignalRedText, "Senden fehlgeschlagen")
    else -> StatusChip("✓ Gesendet", FlipsiColors.TextSecondary, "An das Relay gesendet")
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
 */
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
) {
    // PH-FIX-CHAT-A (F2): relative statt fixe 300-dp-Maximalbreite — auf
    // Sirs Handy (~360–400 dp) nahm eine lange Nachricht fast die volle
    // Breite ein (16 dp Außen + 12 dp Text-Padding = „randnah + gequetscht“).
    // Messenger-Muster: Bubble max ≈ 78 % der Listenbreite.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f
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
                    modifier = Modifier.widthIn(max = maxBubbleWidth),
                    shape = if (message.fromAgent) {
                        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                    } else {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                    },
                    color = if (message.fromAgent) FlipsiColors.PanelElev else FlipsiColors.EmberDim,
                ) {
                    if (message.media != null) {
                        // MEDIA-T1: Media-Bubble je Art (Bild/Voice/Datei/Video).
                        MediaBubbleContent(
                            media = message.media,
                            fromAgent = message.fromAgent,
                            text = message.text,
                        )
                    } else {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.fromAgent) FlipsiColors.TextPrimary else Color(0xFFFCE9DC),
                            // PH-FIX-CHAT-A (F2): 14/10 dp Text-Padding (vorher 12/8) —
                            // Luft zwischen Text und Bildschirmrand, Messenger-Muster.
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // PH2-T10: Zustell-/Lese-Chip je EIGENER Nachricht (neben der Zeit).
                    // PH2-FIX (Jäger B7): Demo-Eintrag → gar kein Chip (kein Backend).
                    if (!message.fromAgent && showStatusChip) {
                        val chip = statusChip(status)
                        Text(
                            chip.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = chip.color,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .semantics { contentDescription = chip.description },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        message.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                    )
                    // MEDIA-T1: Upload-Fortschritt eigener Medien (neben der Zeit,
                    // nie nur Farbe — Text-Chip, TalkBack-lesbar).
                    if (!message.fromAgent && message.media != null && mediaUpload != null) {
                        val progress = when {
                            mediaUpload.failed != null ->
                                "✕ ${mediaUpload.failed!!.take(40)}" to FlipsiColors.SignalRedText
                            mediaUpload.done ->
                                "✓ Übertragen (${mediaUpload.totalChunks} Chunks)" to FlipsiColors.SignalGreen
                            else -> "⏳ Upload ${mediaUpload.ackedChunks}/${mediaUpload.totalChunks} Chunks" to
                                FlipsiColors.TextSecondary
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            progress.first,
                            style = MaterialTheme.typography.labelSmall,
                            color = progress.second,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // PH2-T11: Agenten-Antwort mit Antwort-Latenz ('Antwort nach X s').
                    // SIRS-04: unter 100 ms bleibt die Zeile WEG — 'Antwort nach
                    // 0.0 s' war eine Anzeige unter der Messauflösung (kein echtes
                    // Timing), darüber steht die echte Zeit.
                    val replyLine = replyAfterLabel(replyAfterMs)
                    if (message.fromAgent && replyLine != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            replyLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = FlipsiColors.TextSecondary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .semantics {
                                    contentDescription = replyAfterDescription(replyAfterMs!!)
                                },
                        )
                    }
                }
            }
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
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnlineDot(online = agent.online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                agent.name,
                style = MaterialTheme.typography.titleMedium,
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
) {
    Row(
        modifier = modifier
            .heightIn(min = 72.dp)
            // PH-FIX-CHAT-A (F2): 16 dp Außenabstand — bündig mit den
            // Bubbles (12 dp contentPadding + 8 dp Zeilen-Padding
            // ergibt 20 dp); vorher ragte das Feld optisch enger an den
            // Rand als der Verlauf.
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (onAttach != null) {
            IconButton(
                onClick = onAttach,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Anhang hinzufügen (Foto, Datei, Sprachnachricht)",
                    tint = FlipsiColors.TextPrimary,
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            placeholder = {
                Text(if (voiceRecording) "Sprachnachricht läuft …" else "Nachricht schreiben")
            },
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (voiceRecording && onVoiceToggle != null) {
            // MEDIA-T1: Aufnahme läuft — Stopp/Senden-Button (Ember-Rot-Sprache
            // wie der restliche Chat; A11y: Text-Badge statt nur Farbe).
            IconButton(
                onClick = onVoiceToggle,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_mic),
                    contentDescription = "Sprachnachricht beenden und senden",
                    tint = FlipsiColors.SignalRed,
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                // SIRS-05: leerer Text = disabled (Regel JVM-gepinnt, siehe ChatBubbleRules).
                enabled = canSendChat(value),
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_send),
                    contentDescription = "Senden",
                    tint = if (canSendChat(value)) FlipsiColors.Ember else FlipsiColors.TextSecondary,
                )
            }
        }
    }
}