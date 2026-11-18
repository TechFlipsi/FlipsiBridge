package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * B6 (Multi-Gerät, Sir-General-GO 17.11.): Backfill-Regeln — rein, JVM-testbar
 * (RelayClient/ChatScreen führen nur aus).
 *
 * FRAME-VERTRÄGE (aus dem Backend gelesen, nicht geraten — Commit 24acda0):
 * - contrib/hermes-chat-plugin/adapter.py:
 *     B3_BACKFILL_REQUEST_FRAME = "chat.backfill_request"  (Klar-Steuer-Frame,
 *     ids-only) — RICHTIGER Name, NICHT „backfill.request" (Task-Schätzung).
 *     B3_BACKFILL_ENC_FRAME = "chat.backfill.enc" (Gateway-Verlauf je Geräte-
 *     Session enc; K je Agent = B3_BACKFILL_MAX = 200; je Frame max 64 Entries /
 *     96 KiB, Parität B3_BACKFILL_BATCH_MAX/B3_BACKFILL_BYTES_MAX).
 *     Backfill-Rate je Session: 1 Request / 30 s (B3_BACKFILL_RATE_S — App
 *     sendet bewusst nur EINEN Request je Connect × Agent, kein Hammer).
 * - docs/roadmap-multigeraete.md Kap. 4.1: chat.status_snapshot (ids-only,
 *     Cap 500) + Request-Feld-Namen {gateway_chat, since?, limit}.
 *
 * Toleranz-Vertrag: Backend B6 (Backfill-Gesamt) ist auf dem Relay/Adapter noch
 * nicht verdrahtet (adapter.py: „Prod-Wiring → B6") — die Ingest-Rule parst
 * defenso-tolerant (Feldnamen-Varianten), der Request trägt ids-only den
 * Plan-Namen UND den Wire-Anker (chat_id = E2E-Session-Handle, identischer
 * Wert). Sobald das Backend baut, fängt der Vertrag ohnehin an dieser Stelle.
 */
object ChatBackfillRules {

    const val BACKFILL_REQUEST_FRAME = "chat.backfill_request"
    const val BACKFILL_ENC_FRAME = "chat.backfill.enc"
    const val BACKFILL_K = 200                      // Parität B3_BACKFILL_MAX
    const val STATUS_SNAPSHOT_FRAME = "chat.status_snapshot"
    const val STATUS_SNAPSHOT_CAP = 500             // Roadmap Kap. 4.1 (ids-only)
    const val PROGRESS_TIMEOUT_MS = 30_000L         // ehrliches Fortschritt-Ende

    // ── B5: Request (frisches Gerät) ─────────────────────────────────────────

    /**
     * Request nur bei LEEREM Verlauf je Agent (Kap. 4.1: ChatHistoryStore-Datei
     * existiert nicht → anfordern). Bestand vorhanden = NIE anfordern.
     */
    fun shouldRequest(hasHistory: Boolean): Boolean = !hasHistory

    /**
     * chat.backfill_request (ids-only — KEIN Text/Inhalt im Klar-Frame): type/v/
     * chat_id (Wire-Handle der E2E-Session, AAD-Anker) + gateway_chat (Plan-4.1-
     * Feldname, identischer Handle) + limit (geklemmt 1..200). null = ohne
     * Session-chat_id kein Request (der Adapter kann sonst den Verlauf nicht
     * zuordnen — Session-Kontext schlägt Rate).
     */
    fun requestFrame(chatId: String, limit: Int = BACKFILL_K): JsonObject? {
        val chat = chatId.trim()
        if (chat.isEmpty()) return null
        return JsonObject().apply {
            addProperty("type", BACKFILL_REQUEST_FRAME)
            addProperty("v", 1)
            addProperty("chat_id", chat)
            addProperty("gateway_chat", chat)
            addProperty("limit", limit.coerceIn(1, BACKFILL_K))
        }
    }

    // ── B6: Ingest ───────────────────────────────────────────────────────────

    /** Ein Verlauf-Eintrag aus dem Backfill-Batch (tolerant geparst). */
    data class BackfillEntry(
        val msgId: String?,
        val text: String,
        val fromAgent: Boolean,
        val ts: Long,
    )

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun boolOf(json: JsonObject, field: String): Boolean? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.takeIf { p ->
            p.isBoolean || (p.isString && (p.asString == "true" || p.asString == "false"))
        }?.asBoolean
    }.getOrNull()

    private fun longOf(json: JsonObject, field: String): Long? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    /**
     * Batch-Payload → Einträge. Akzeptiert "entries" ODER "messages" (Array);
     * je Eintrag text|message Pflicht, msg_id|messageId optional, from_agent|
     * fromAgent|is_agent|role („agent"/"assistant" = Agent, „user" = Nutzer). 
     * Rückgabe immer chronologisch ASC, msg_id-Dedupe (erstes Auftreten gewinnt),
     * K-Cap (letzte = NEUESTE 200). null = kein Batch-Feld gefunden (Fremd-
     * Payload → Frame verwerfen); leere Liste = leere Batch (ehrlich).
     */
    fun parseEntries(payload: JsonObject, fallbackTs: Long): List<BackfillEntry>? {
        val array = runCatching {
            (payload.get("entries") ?: payload.get("messages"))
                ?.takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull() ?: return null
        val out = ArrayList<BackfillEntry>(array.size())
        val seenIds = HashSet<String>()
        for (item in array) {
            val entry = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val text = strOf(entry, "text") ?: strOf(entry, "message") ?: continue
            if (text.isBlank()) continue
            val msgId = (strOf(entry, "msg_id") ?: strOf(entry, "messageId"))
                ?.takeIf { it.isNotBlank() }
            // msg_id-Dedupe NOCH IM FRAME: dieselbe msg_id zweimal (Backend-Batch
            // Scherben) darf nie doppelt in die Platte.
            if (msgId != null && !seenIds.add(msgId)) continue
            val fromAgent = boolOf(entry, "from_agent")
                ?: boolOf(entry, "fromAgent")
                ?: boolOf(entry, "is_agent")
                ?: when (strOf(entry, "role")?.lowercase()) {
                    "agent", "assistant", "ai" -> true
                    "user", "human" -> false
                    else -> false  // Default: Nutzer-Zeile (fromAgent=false, ehrlich neutral)
                }
            val ts = longOf(entry, "ts")
                ?: longOf(entry, "timestamp")
                ?: longOf(entry, "time_ms")
                ?: fallbackTs
            out += BackfillEntry(msgId, text, fromAgent, ts)
        }
        return capAndSort(out)
    }

    /**
     * Chronologisch ASC sortieren + K-Cap (BEHÄLT die neuesten Einträge —
     * "K je Agent" beschreibt den Verlaufs-Reichweite-Deckel, Kap. 4.1).
     * Ohne msg_id bleibt die Reihenfolge stabil (sort ist stable).
     */
    fun capAndSort(entries: List<BackfillEntry>, k: Int = BACKFILL_K): List<BackfillEntry> {
        if (entries.size <= k) return entries.sortedBy { it.ts }
        val ordered = entries.sortedBy { it.ts }
        return ordered.takeLast(k).sortedBy { it.ts }
    }

    // ── B6: Status-Snapshot (ids-only) ───────────────────────────────────────

    /**
     * chat.status_snapshot (ids-only, Cap 500, Roadmap Kap. 4.1) →
     * (msg_id, status)-Paare (nur CHATSTATUS-honest Werte). Tolerante Formate:
     * A) "statuses"|"chat_status" = Array von {msg_id|messageId, status|state}
     * B) "statuses"|"chat_status" = Map { msg_id: status }
     * null = keines der Felder (Fremd-Frame verwerfen).
     */
    fun parseStatusSnapshot(json: JsonObject): List<Pair<String, String>>? {
        val statuses = json.get("statuses") ?: json.get("chat_status") ?: return null
        return runCatching {
            val pairs = ArrayList<Pair<String, String>>()
            if (statuses.isJsonArray) {
                for (item in statuses.asJsonArray) {
                    val entry = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val msgId = (strOf(entry, "msg_id") ?: strOf(entry, "messageId"))
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val status = strOf(entry, "status") ?: strOf(entry, "state") ?: continue
                    pairs.add(msgId to status)
                }
            } else if (statuses.isJsonObject) {
                for ((msgId, value) in statuses.asJsonObject.entrySet()) {
                    val status = value.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    if (msgId.isNotBlank()) pairs.add(msgId.trim() to status)
                }
            }
            pairs.filter { (msgId, status) -> msgId.isNotBlank() && ChatStatusRank.isKnown(status) }
        }.getOrNull()
    }

    /**
     * Monotone Snapshot-Übernahme (W20-Kette): je msg_id der HÖHERE der beiden
     * bekannten Stände (ChatStatusRank.fuse) — 'delivered' drückt 'read' nie
     * zurück (Haken-Fusion: die W20-Monotonie gilt auch für den Verlaufs-Export),
     * 'failed' blockt nachkommend queued/delivered, reales 'read' heilt (Parität
     * shouldApply). Cap 500 mit rang-priorisierter Eviction (Parität zur Live-
     * Map von RelayClient.handleChatStatusFrame).
     */
    fun mergeStatuses(
        existing: Map<String, String>,
        incoming: List<Pair<String, String>>,
        cap: Int = STATUS_SNAPSHOT_CAP,
    ): Map<String, String> {
        val merged = LinkedHashMap(existing)
        for ((msgId, status) in incoming) {
            if (msgId.isBlank() || !ChatStatusRank.isKnown(status)) continue
            merged[msgId] = ChatStatusRank.fuse(merged[msgId], status) ?: continue
            while (merged.size > cap) {
                val victim = ChatStatusRank.evictionVictims(merged).firstOrNull() ?: break
                merged.remove(victim)
            }
        }
        return merged
    }

    /**
     * Chip-Anhebung über msg_id→local_id-Bindungen (restoredStatusById /
     * statusHighWaterByLocalId): nur AUFSTEIGEND (fuse), ein fehlender Snapshot-
     * Eintrag bindet NIE eine Senkung. Unverändert = dieselbe Map (kein Recompose).
     */
    fun raiseBindingStatuses(
        current: Map<Long, String>,
        bindings: Map<Long, String>,
        snapshot: Map<String, String>,
    ): Map<Long, String> {
        var changed = false
        val out = HashMap(current)
        for ((localId, msgId) in bindings) {
            val status = snapshot[msgId] ?: continue
            if (!ChatStatusRank.isKnown(status)) continue
            val fused = ChatStatusRank.fuse(out[localId], status)
            if (fused != null && fused != out[localId]) {
                out[localId] = fused
                changed = true
            }
        }
        return if (changed) out else current
    }

    /**
     * Request-Gate je Connect × Agent (ein Set im RelayClient, hier die Regel):
     * einmal je (Re)Connect — ein zweiter Request ohne neuen Connect verbrannt
     * nur das DoS-Schild des Adapters (B3_BACKFILL_RATE_S), ohne neuen Verlauf.
     */
    fun mayRequestOnce(requestedThisConnect: MutableSet<String>, agentId: String): Boolean {
        val id = agentId.trim()
        if (id.isEmpty()) return false
        return requestedThisConnect.add(id)
    }

    fun unmarkRequested(requestedThisConnect: MutableSet<String>, agentId: String) {
        requestedThisConnect.remove(agentId.trim())
    }
}
