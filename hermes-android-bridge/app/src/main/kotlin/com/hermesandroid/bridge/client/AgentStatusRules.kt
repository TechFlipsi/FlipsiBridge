package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * W18-Kette (v0.16.7→…): agent.status-Frame-Verband — reine Logik-Einheit,
 * SPIEGEL des parallel-gebaute Relay-Vertrags (agent_id/working(bool)/
 * started_at/label(str≤64)/sub{?id≤64,name≤64}/end(bool)). RelayClient.
 * handleAgentStatusFrame wendet sie an, damit Parsen + Verbands-Faltung
 * JVM-getestet bleiben (gleiche Faser wie ChatStatusRank/ReconnectPolicy).
 *
 * Frame-Semantik:
 * - sub == null  → Haupt-Status-Update ([AgentStatusSlot]: working,
 *   startedAt, label) — last-write-wins wie chat.activity. Ein
 *   working=false-Frame ohne sub setzt den Haupt-Status ehrlich auf „fertig“.
 * - sub != null && end != true → Sub-Agent-Slot UPSERT (Key = sub.id ?:
 *   sub.name; Name ist Anzeige-Pflicht — Name-freie Einträge fliegen,
 *   parseSessionMeta-Parität).
 * - sub != null && end == true → Sub-Agent-Slot ENTFERNEN (Key wie beim
 *   Upsert; ohne gültigen Key gibt es nichts zu entfernen → No-op).
 *
 * Fail-soft: verletzte Felder werden verworfen, nicht gehadert —
 * label > 64 Zeichen → Label weg (Status-Update bleibt wahr), Name > 64
 * → Eintrag weg, falsche Typen → Frame verworfen. KEINE Exception-Ketten.
 */
object AgentStatusRules {

    /** Vertrags-Obergrenze des Server-Labels (Maschinenwort, z. B. „Sucht im Web“). */
    const val MAX_LABEL_LEN = 64

    /** Vertrags-Obergrenze der Sub-Kennung/Name. */
    const val MAX_SUB_ID_LEN = 64
    const val MAX_SUB_NAME_LEN = 64

    /** Obergrenze der Sub-Slots je Agent (Verband ist kein Archiv). */
    const val MAX_SUBS_PER_AGENT = 16

    /**
     * Haupt-Status eines Agenten aus agent.status (sub == null): Working-
     * Zustand + Startzeit (unix-ms) + Server-Label (null = keins angegeben
     * oder vertragsverletzt — die UI zeigt dann das neutrale „arbeitet …“).
     */
    data class AgentStatusSlot(
        val working: Boolean,
        val startedAt: Long,
        val label: String? = null,
    )

    /**
     * EIN Sub-Agent-Slot (agent.status mit sub != null): Key = [id] (id ?:
     * Name beim Upsert), Anzeige-Name = [name], Live-Zustand [working].
     */
    data class SubAgentSlot(
        val id: String,
        val name: String,
        val startedAt: Long,
        val working: Boolean,
    )

    /**
     * Geparste Frame-Wirkung — exakt EINE der drei Operationen ist gesetzt
     * (status / subUpsert / subRemoveKey), oder null = Frame verworfen.
     */
    data class AgentStatusFrame(
        val agentId: String,
        val status: AgentStatusSlot?,
        val subUpsert: SubAgentSlot?,
        val subRemoveKey: String?,
    )

    /**
     * Frame streng lesen (Klon der handleChatActivityFrame-Disziplin):
     * agent_id + working sind Pflicht (falsche Typen/o. Fehlen → null),
     * started_at fällt auf [fallbackNowMs], Sub-Zweig folgt dem Vertrag.
     */
    fun parse(json: JsonObject, fallbackNowMs: Long): AgentStatusFrame? {
        val agentId = runCatching {
            json.get("agent_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull().orEmpty()
        if (agentId.isEmpty()) return null
        // working(bool) ist Pflicht STRENG (Vertrag „working(bool)“): nur echte
        // JSON-Booleans — Gson koerziert den String „ja“ sonst still zu false
        // (asBoolean→Boolean.parseBoolean) und die UI könnte aus Müll „fertig“
        // lesen. Verletzter Typ → Frame verworfen (fail-soft).
        val working = runCatching {
            val raw = json.get("working")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            if (raw != null && raw.isBoolean) raw.asBoolean else null
        }.getOrNull() ?: return null
        val startedAt = runCatching {
            json.get("started_at")?.takeIf { it.isJsonPrimitive }?.asLong
        }.getOrNull() ?: fallbackNowMs
        val label = runCatching {
            json.get("label")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL_LEN }

        val sub = json.get("sub") as? JsonObject
        if (sub == null) {
            return AgentStatusFrame(
                agentId = agentId,
                status = AgentStatusSlot(working, startedAt, label),
                subUpsert = null,
                subRemoveKey = null,
            )
        }
        // end(bool) ist laut Vertrag ein Frame-Feld auf TOP-LEVEL („… /
        // sub{?id,name} / end(bool)“) — defensiv wird ein end am sub-Objekt
        // mitgelesen (misverstandener Spiegel verfälscht sonst End-Verband
        // still in Dauer-Upsert). Fail-soft: fehlend = false.
        val end = runCatching {
            json.get("end")?.takeIf { it.isJsonPrimitive }?.asBoolean
        }.getOrNull() ?: runCatching {
            sub.get("end")?.takeIf { it.isJsonPrimitive }?.asBoolean
        }.getOrNull() ?: false
        val id = readLenientString(sub, "id", MAX_SUB_ID_LEN)
        val name = readLenientString(sub, "name", MAX_SUB_NAME_LEN)
        val key = id ?: name ?: return null
        if (end) {
            return AgentStatusFrame(agentId, null, null, key)
        }
        // Upsert braucht den Anzeige-Namen (Name-freie Einträge fliegen):
        if (name == null) return null
        return AgentStatusFrame(
            agentId = agentId,
            status = null,
            subUpsert = SubAgentSlot(key, name, startedAt, working),
            subRemoveKey = null,
        )
    }

    /** String-Feld klein-gelesen: trimmed, nicht-leer, ≤ [max] — sonst null. */
    private fun readLenientString(obj: JsonObject, field: String, max: Int): String? =
        runCatching {
            obj.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= max }

    // ── Verbands-Faltung (pure, für AtomicStateFlow.update {} — CAS) ─────────

    /**
     * Haupt-Status-Update: letzter Frame gewinnt (chat.activity-Parität).
     */
    fun withStatus(
        current: Map<String, AgentStatusSlot>,
        agentId: String,
        slot: AgentStatusSlot,
    ): Map<String, AgentStatusSlot> = current + (agentId to slot)

    /**
     * Sub-Slot upsert (end != true): Key ersetzt vorhandenen Eintrag,
     * Kap-Grenze hält ein verwaistes Verband-Archiv klein (ältester Key
     * fliegt zuerst — LinkedHashMap = Einfüge-Reihenfolge).
     */
    fun withSubUpsert(
        current: Map<String, Map<String, SubAgentSlot>>,
        agentId: String,
        sub: SubAgentSlot,
    ): Map<String, Map<String, SubAgentSlot>> {
        val next = LinkedHashMap(current[agentId].orEmpty())
        next[sub.id] = sub
        while (next.size > MAX_SUBS_PER_AGENT) {
            val oldest = next.keys.first()
            if (oldest == sub.id) break // Frisch upserteter Key niemals selbst entlassen
            next.remove(oldest)
        }
        return current + (agentId to next)
    }

    /**
     * Sub-Slot entfernen (end == true): fehlt der Eintrag → GLEICHE Map-
     * Instanz zurück (kein sinnloses StateFlow-Emit, assertSame-Muster wie
     * ChatStatusRank.sweepUnreadToFailed); leerer Agent verschwindet ganz.
     */
    fun withSubRemove(
        current: Map<String, Map<String, SubAgentSlot>>,
        agentId: String,
        key: String,
    ): Map<String, Map<String, SubAgentSlot>> {
        val subs = current[agentId] ?: return current
        if (!subs.containsKey(key)) return current
        val next = LinkedHashMap(subs)
        next.remove(key)
        return if (next.isEmpty()) current - agentId else current + (agentId to next)
    }
}
