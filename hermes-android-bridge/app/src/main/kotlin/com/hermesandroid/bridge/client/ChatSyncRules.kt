package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * B5 (Multi-Gerät, Sir-General-GO 17.11.): Sync-Echo-Ingest-Regel — rein,
 * JVM-testbar (RelayClient.handleChatSyncEnc rendert nur).
 *
 * FRAME-VERTRAG (aus dem Backend gelesen, nicht geraten — Commit 24acda0,
 * contrib/hermes-chat-plugin/adapter.py):
 * - B3_SYNC_ENC_FRAME = "chat.sync.enc"  → Echo des NUTZER-Texts an NEBENgeräte
 * - B3_ECHO_FIELD     = "sync"           → eindeutiges Echo-Feld im ct-Payload
 * - B3_SYNC_OF_FIELD  = "sync_of"        → msg_id-Bezug: die Echo-Blase trägt
 *                                          die Ursprungs-msg_id (Kap. 2.3 #1)
 * - B3_SYNC_ECHO_DEDUP = 256             → FIFO-Dedupe je Echo (App spiegelt live)
 *
 * LOOP-SCHUTZ (Plan Kap. 8 #6): ein Sync-Frame ist UNBEDINGT ingest-only — die
 * Regel liefert ausschließlich Bubble-Daten ([parseEcho]); es gibt bewusst KEINE
 * Send-/Frame-Bau-Funktion in diesem Objekt. RelayClient.handleChatSyncEnc ruft
 * KEINE sendChat-/chat.state-Funktion (statische Beweise im QA-Lauf). Damit kann
 * ein Echo nie wieder in den Gateway-Verlauf fallen (kein Duplex-Loop), selbst
 * wenn eine App-Version fehlerhaft wäre: Echo NUR lokale Platte, KEIN Re-Post.
 *
 * Toleranz: solange Backend B4 das Echo noch nicht sendet, kommt der Frame nie
 * an — die Regel verweigert ohne Marker sauber (null). msg_id-Kaskade: sync_of
 * → origin_msg_id (Plan-Kap-2.3-Wortlaut) → payload-msg_id → Frame-msg_id; nur
 * nicht-blank-Zeilen tragen die Bindung (Chip-Verband + Dedup).
 */
object ChatSyncRules {

    const val SYNC_FRAME = "chat.sync.enc"
    const val ECHO_FIELD = "sync"
    const val SYNC_OF_FIELD = "sync_of"
    const val SEEN_CAP = 256   // Parität: B3_SYNC_ECHO_DEDUP (adapter.py)

    /** Eine Sync-Bubble (Nutzer-Text vom ANDEREN Gerät — fromAgent = false beim Rendern). */
    data class SyncEcho(
        val agentId: String,
        val chatId: String,
        val text: String,
        val ts: Long,
        val msgId: String?,
    )

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    /**
     * true = der Payload trägt den Echo-Marker (Kap. 8 #6: Marker erkennen).
     * Akzeptiert: "sync": true ODER vorhandenes nicht-blank "sync_of".
     */
    fun isEchoPayload(payload: JsonObject): Boolean {
        val echoed = runCatching {
            payload.get(ECHO_FIELD)?.takeIf { it.isJsonPrimitive }?.asBoolean
        }.getOrNull()
        if (echoed == true) return true
        return !strOf(payload, SYNC_OF_FIELD).isNullOrBlank()
    }

    /**
     * msg_id-Kaskade der Echo-Blase (Kap. 2.3 #1: echo trägt die Ursprungs-
     * msg_id mit → Chip-Frame trifft die Blase auf JEDEM Gerät).
     */
    fun originMsgId(payload: JsonObject?, frame: JsonObject?): String? {
        if (payload != null) {
            strOf(payload, SYNC_OF_FIELD)?.takeIf { it.isNotBlank() }?.let { return it }
            strOf(payload, "origin_msg_id")?.takeIf { it.isNotBlank() }?.let { return it }
            strOf(payload, "msg_id")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (frame != null) {
            strOf(frame, "msg_id")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * Echo-Payload → Bubble-Daten. null = KEIN Echo (kein Marker) oder leere
     * Text-Bulle — Frame verwerfen (nie als Agenten-Antwort rendern, nie senden).
     */
    fun parseEcho(
        payload: JsonObject,
        frame: JsonObject,
        agentId: String,
        chatId: String,
        ts: Long,
    ): SyncEcho? {
        if (!isEchoPayload(payload)) return null
        val text = strOf(payload, "text")?.takeIf { it.isNotBlank() } ?: return null
        return SyncEcho(
            agentId = agentId,
            chatId = chatId,
            text = text,
            ts = ts,
            msgId = originMsgId(payload, frame),
        )
    }

    /**
     * FIFO-Dedupe je msg_id (Parität B3_SYNC_ECHO_DEDUP = 256): true = neu
     * (eingehängt + gemerkt), false = Duplikat (bereits geplattet). Blanker
     * msg_id kann nichts gemerkt werden — die Blase geht durch (UI-Zwilling-
     * Filter frisst Rest-Dubletten über ChatTimelineMerge, dokumentierte
     * Toleranz ohne Backend-Bindung).
     */
    fun isNewAndRemember(seen: MutableCollection<String>, msgId: String?, cap: Int = SEEN_CAP): Boolean {
        val id = msgId?.takeIf { it.isNotBlank() } ?: return true
        if (seen is MutableSet<String> && !seen.add(id)) return false
        if (seen is MutableSet<String> && seen is LinkedHashSet<String>) {
            // Insertion-Order-FIFO: älteste Merker fliegen zuerst (Parität Relay-
            // _cap_chat_status: Cap NACH dem Insert, kein Überschuss-Rückstand).
            while (seen.size > cap) {
                val first = seen.firstOrNull() ?: break
                seen.remove(first)
            }
        }
        return true
    }

    /** Merker einer neuen Session LEEREN (kein Stau über Verbindungszeiten). */
    fun clear(seen: MutableCollection<String>) = seen.clear()
}
