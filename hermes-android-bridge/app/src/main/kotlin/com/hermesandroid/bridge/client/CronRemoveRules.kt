package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.hermesandroid.bridge.ui.dashboard.DashboardRules

/**
 * W23-11-App (Arbeits-Brief; Frame-Vertrag
 * Lane-Brief TEIL 2): der cron.remove-Rückkanal — rein,
 * JVM-testbar (RelayClient sendet/empfängt nur; die Adapter-Seite baut die
 * Python-Lane separat — der Client bleibt fail-soft, wenn der Frame (noch)
 * fehlt: KEIN Crash, KEIN Loop, KEIN Fake-Erfolg).
 *
 * FRAME-VERTRAG (Adapter-Lane TEIL 2):
 *
 * - VOM Phone ZUM Adapter (Agent-WS-Rückkanal):
 *     {"type": "cron.remove", "cron_id": <PRIMARY-Zielkennung aus der
 *              cron.status-Jobs-Zeile — die Verbriefung des Jobs>}
 *
 *   Kein weiteres Pflicht-Feld: der cron_id-Verweis ist die eindeutige
 *   Zielkennung (die cron.status-Zeile trägt die Verbriefung; fehlt sie am
 *   Alt-Stand, sendet die UI bewusst NICHT — CronJobItem.cronId == null →
 *   Lösch-Element verborgen).
 *
 * - ZUM Phone VOM Adapter (Antwort — W23-9-Muster:
 *   <frame>_ok mit {ok, error?}; defensively akzeptiert die Bindung AUCH
 *   "cron.remove_ack" als Schreibvariante — beide fail-soft):
 *     {"type": "cron.remove_ok",
 *      "ok": <bool>, "error"?: <"not-supported"|…>, "cron_id"?: <Echo>}
 *
 *   Fail-soft: unvollständige/fremde Struktur → verwerfen; ok-Feld fehlt →
 *   NICHT als Erfolg gelesen (ehrliche ERROR-Familie statt Fake-„gelöscht“);
 *   leeres error → null. KEIN Wiederhol-Senden bei fehlender Antwort — der
 *   frische cron.status nach dem Remove (Adapter TEIL 2: „der cron.status-
 *   Fresh-Frame nach jedem Remove“) ist das ECHTE Sicht-Signal; der
 *   Warten-Zustand ist ein Deckel, kein Retry (kein Loop).
 */
object CronRemoveRules {

    const val REMOVE_FRAME = "cron.remove"
    /** Primäre Antwort-Schreibweise (W23-9-Verbands-Muster <frame>_ok). */
    const val OK_FRAME = "cron.remove_ok"
    /** Defensive tolerierte Schreibvariante (Schreib-Spiegel, gleicher Inhalt). */
    const val OK_FRAME_ALT = "cron.remove_ack"

    /** Adapter-Antwort für „fehlt die Cron-Remove-Fähigkeit“ (fail-soft). */
    const val ERROR_NOT_SUPPORTED = "not-supported"

    /** Warten-Deckel je Lauf (kein UI-Schleudern) — Parität DeviceAliasRules. */
    const val ACK_TIMEOUT_MS = 5_000L

    /** Ausgang des Lösch-Laufs — die UI bildet je Stufe den Snack-Text. */
    enum class Outcome { SUCCESS, NOT_SUPPORTED, ERROR }

    /** Geparste Antwort (Echo may null — die UI räumt dann großzügiger). */
    data class RemoveAck(
        /** Echo des gesandten cron_id (null = Adapter ohne Echo). */
        val cronId: String?,
        /** Server-Urteil: false = fehlgeschlagen (Feld fehlt = false, ehrlich). */
        val ok: Boolean,
        /** Abweisungs-Grund ("not-supported", …) — null = keiner. */
        val error: String?,
    )

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun boolOf(json: JsonObject, field: String): Boolean? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean
    }.getOrNull()

    /**
     * Send-Vertrag des Lösch-Frames: [cronId] = Verbriefung aus der
     * cron.status-Zeile; [agentId] = die Agent-Adresse aus dem Zeilen-/Gruppen-
     * Kontext (W26c: JEDER Steuer-Frame trägt sie — der Relay routet je
     * GENANNTEM Agenten, fail-closed NACK bei unbekannter Adresse; ein Alt-
     * Stand ohne agent_id bleibt legitim, der Relay fallbackt mit LOUD-Log).
     * null [cronId] = Frame unvollständig (kein wirksames Ziel) — der
     * Aufrufer sendet dann BEWUSST NICHT (fail-soft am Draht).
     */
    fun removeFrame(cronId: String?, agentId: String?): JsonObject? {
        val id = (cronId ?: "").trim()
        if (id.isEmpty()) return null
        return JsonObject().apply {
            addProperty("type", REMOVE_FRAME)
            addProperty("cron_id", id)
            val agent = (agentId ?: "").trim()
            if (agent.isNotEmpty()) addProperty("agent_id", agent)
        }
    }

    /**
     * Ack-Verband: null = fremde/unvollständige Struktur (verwerfen —
     * fail-soft, KEIN Crash, KEIN UI-Zustands-Wechsel).
     */
    fun parse(json: JsonObject): RemoveAck? {
        val type = strOf(json, "type") ?: return null
        if (type != OK_FRAME && type != OK_FRAME_ALT) return null
        return RemoveAck(
            cronId = strOf(json, "cron_id")?.takeIf { it.isNotBlank() },
            // Fehlendes ok-Feld = nie ein Fake-„gelöscht“ (W23-9-Muster:
            // ehrlich falsch statt still grün).
            ok = boolOf(json, "ok") ?: false,
            error = strOf(json, "error")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Ausgang → Snack-Staffel der UI (Brief TEIL 2: Erfolg = der frische
     * cron.status zieht die Zeile weg, Fehler = Snack fail-soft, KEIN Crash,
     * KEIN Loop — ein ERROR-Echo bleibt ohne Wiederhol-Senden).
     * [SUCCESS] nur bei ok=true; fehlendes ok-Feld liest ehrlich ERROR.
     */
    fun outcome(ack: RemoveAck): Outcome = when {
        ack.ok -> Outcome.SUCCESS
        ack.error == ERROR_NOT_SUPPORTED -> Outcome.NOT_SUPPORTED
        else -> Outcome.ERROR
    }

    // ── Warten-Zustand-Faltung (pure, für StateFlow-Updates) ─────────────────

    /** Je Lauf EIN Merker (doppel-Tap schützt nichts — die UI disabled). */
    fun withPending(current: Set<String>, cronId: String): Set<String> {
        val id = cronId.trim()
        if (id.isEmpty() || id in current) return current
        return current + id
    }

    /** Eine Antwort räumt ihren Merker (Echo-Fall). */
    fun withoutPending(current: Set<String>, cronId: String?): Set<String> {
        if (cronId.isNullOrBlank()) return current
        if (cronId !in current) return current
        return current - cronId
    }

    /**
     * Frischer cron.status räumt: Merker ohne Verbriefung im NEUEN Stand sind
     * erledigt (die Zeile ist schon fort). MERKER, die WEITERHIN im Stand
     * stehen (z. B. nach „not-supported“), bleiben — sie warten auf das Ack
     * bzw. auf den Deckel, KEIN Loop von hier.
     */
    fun sweepPending(
        current: Set<String>,
        jobsByAgent: Map<String, List<DashboardRules.CronJobItem>>,
    ): Set<String> {
        if (current.isEmpty()) return current
        val stillThere = jobsByAgent.values.flatMap { jobs -> jobs.mapNotNull { it.cronId } }.toSet()
        val next = current.filterTo(LinkedHashSet(current.size)) { it in stillThere }
        return if (next.size == current.size) current else next
    }
}
