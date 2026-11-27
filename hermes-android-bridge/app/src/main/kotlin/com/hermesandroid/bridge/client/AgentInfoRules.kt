package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * W26-App (Arbeits-Brief; Frame-Vertrag w26-brief-adapter.md
 * TEIL 3; Relay-Durchleitung je w26-brief-relay.md TEIL 4): der agent.info-
 * Verband — rein, JVM-testbar, KEINE Android-Imports (CronRemoveRules-Muster,
 * gson-parsing, fail-soft).
 *
 * FRAME-VERTRAG (Adapter → App, je Connect/Reconnect/Handshake — Parität
 * cron.status-Feuerstellen; Klartext existiert NUR ohne E2E-Session, sonst
 * enc-Spiegel {ct: …} mit agent_id am äußeren Frame):
 *     {"type": "agent.info", "v": 1, "agent_id": <Pflicht>,
 *      "display_name"?: <str>,
 *      "adapter_caps": ["cron.status", "cron.remove", "cron.pause",
 *                       "cron.resume", "cron.add", "models", "status"]}
 *
 * caps = die Frames, die DIESE Adapter-Version wirklich unterstützt. Die App
 * zeigt ihre Verwaltungs-Elemente (Cron-Löschen/Pause/Resume/Anlegen, Agenten-
 * Zentrale-Cap-Hinweis) NUR je gemeldeter Cap — VERSIONS-fail-soft: ohne
 * agent.info (Alter Adapter) bleibt die UI ehrlich ohne die Elemente, KEIN
 * Fake-Angebot. Bekannte/unbekannte Cap-Strings verbleiben verbatim im Set
 * (die Zukunft darf neue Caps melden, ohne die App zu brechen).
 */
object AgentInfoRules {

    const val INFO_FRAME = "agent.info"
    /** enc-Spiegel (Parität cron.status.enc — Klartext-Pfad nur ohne E2E). */
    const val INFO_FRAME_ENC = "agent.info.enc"

    const val FRAME_VERSION = 1

    // Caps-Katalog (die Strings SIND der Vertrag — nie Variabilität erfinden).
    const val CAP_CRON_STATUS = "cron.status"
    const val CAP_CRON_REMOVE = "cron.remove"
    const val CAP_CRON_PAUSE = "cron.pause"
    const val CAP_CRON_RESUME = "cron.resume"
    const val CAP_CRON_ADD = "cron.add"
    const val CAP_MODELS = "models"
    const val CAP_STATUS = "status"

    /**
     * EIN gemeldeter Cap-Stand je Agent ([agentId] = Pflicht-Träger,
     * [displayName] vom Frame (leer = Presence/Eintrag-Name der UI),
     * [caps] verbatim — leer = Adapter ohne Verwaltungs-Fähigkeit).
     */
    data class AgentCaps(
        val agentId: String,
        val displayName: String = "",
        val caps: Set<String> = emptySet(),
    )

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    /**
     * Parse fail-soft: ohne agent_id (Pflicht des Vertrags) ⇒ null (Frame
     * verwerfen — KEIN Crash, KEIN UI-Zustands-Wechsel ohne Träger).
     */
    fun parse(json: JsonObject): AgentCaps? {
        val agentId = strOf(json, "agent_id")?.trim().orEmpty()
        if (agentId.isEmpty()) return null
        val displayName = strOf(json, "display_name")?.trim().orEmpty()
        val caps = LinkedHashSet<String>()
        runCatching {
            json.get("adapter_caps")?.takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull()?.forEach { element ->
            val raw = element.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val cap = raw.trim()
            if (cap.isNotEmpty()) caps.add(cap)
        }
        return AgentCaps(agentId = agentId, displayName = displayName, caps = caps)
    }

    /**
     * PER-AGENT-REPLACE-Faltung (pure, für AtomicStateFlow-Updates — Parität
     * CronScreenRules.replaceAgentJobs): je Frame ersetzt der Cap-Stand des
     * AGENTS den Gesamt-Stand. REPLACE (nicht Merge, nicht Append); Einfüge-
     * Reihenfolge folgt dem Fluss (remove + put — der zuletzt gesehene Frame
     * reiht FRISCH ein, kein Alt-Slot; LinkedHashMap).
     */
    fun replaceAgentCaps(
        current: Map<String, AgentCaps>,
        caps: AgentCaps,
    ): Map<String, AgentCaps> {
        val next = LinkedHashMap(current)
        next.remove(caps.agentId)
        next[caps.agentId] = caps
        return next
    }

    /** Exakte Cap-Prüfung (null = kein agent.info-Stand ⇒ ehrlich false). */
    fun hasCap(caps: Set<String>?, cap: String): Boolean =
        caps?.contains(cap) == true

    /**
     * „Verwaltet: Cron“-Fibel der Agenten-Zentrale: der Adapter meldet IRGENDEINE
     * Cron-Cap (status/remove/pause/resume/add) ⇒ die Kachel zeigt den
     * Verwaltungs-Hinweis (null = kein Stand ⇒ false, Element verborgen).
     */
    fun managesCron(caps: Set<String>?): Boolean = caps?.any { it.startsWith(CRON_PREFIX) } == true

    private const val CRON_PREFIX = "cron."
}
