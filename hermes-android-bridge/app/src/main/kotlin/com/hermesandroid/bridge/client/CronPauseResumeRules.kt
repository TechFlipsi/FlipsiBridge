package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.hermesandroid.bridge.ui.dashboard.DashboardRules

/**
 * W26-App (Arbeits-Brief; Frame-Verträge je Backend-Lanes —
 * w26-brief-relay.md TEIL 1 + w26-brief-adapter.md TEIL 1; W26c-Korruption
 * des Erstdokuments korrigiert): der cron.pause/cron.resume-Rückkanal —
 * rein, JVM-testbar, KEINE Android-Imports (CronRemoveRules-Muster, Parität
 * Feld für Feld).
 *
 * FRAME-VERTRAG:
 *
 * - VOM Phone ZUM Adapter (Agent-WS-Rückkanal, Relay leitet weiter):
 *     {"type": "cron.pause",  "v": 1, "cron_id": <Verbriefung>,
 *      "agent_id": <Agent-Adresse aus dem Zeilen-Kontext>}
 *     {"type": "cron.resume", "v": 1, "cron_id": <Verbriefung>,
 *      "agent_id": <Agent-Adresse aus dem Zeilen-Kontext>}
 *
 *   W26c (Jäger-Blocker 3): JE Steuer-Frame trägt die GENANNTE Agent-
 *   Adresse (die Zeile kam aus DER Agent-Gruppe des CronScreens — da ist
 *   der Agent bekannt, NICHT erfunden). Kein „chat_owner-Routing“ — der
 *   Relay routet je GENANNTEM Agenten; ohne agent_id (Alt-App-Zeile) gilt
 *   der max-ts-Fallback mit LOUD-Log (Abwärtskompat, bewusst dokumentiert).
 *
 * - ZUM Phone VOM Adapter (W23-9-Muster <frame>_ok):
 *     {"type": "cron.pause_ok"|"cron.resume_ok",
 *      "ok": <bool>, "error"?: <"not-supported"|…>, "cron_id"?: <Echo>}
 *
 *   Fail-soft: foreign/incomplete Structure → null (verwerfen); ok-Feld fehlt
 *   ⇒ NICHT als Erfolg gelesen (ehrliche ERROR-Familie); leeres/fehlerhaftes
 *   error ⇒ null. Defensiv toleriert JEDE Schreibvariante ("cron.pause_ack" /
 *   "cron.resume_ack") — Schreib-Spiegel, gleicher Inhalt (Parität
 *   CronRemoveRules.OK_FRAME_ALT).
 *
 * KEIN Wiederhol-Senden bei fehlender Antwort — der frische cron.status nach
 * dem Toggle (Adapter TEIL 1: Fresh-Feed nach JEDEM echten Erfolg) ist das
 * ECHTE Sicht-Signal; der Warten-Zustand ist ein Deckel, kein Retry (kein
 * Loop, Parität remove).
 */
object CronPauseResumeRules {

    const val PAUSE_FRAME = "cron.pause"
    const val RESUME_FRAME = "cron.resume"

    /** Primäre Antwort-Schreibweisen (W23-9-Verbands-Muster <frame>_ok). */
    const val PAUSE_OK_FRAME = "cron.pause_ok"
    const val RESUME_OK_FRAME = "cron.resume_ok"

    /** Defensiv tolerierte Schreibvarianten (Schreib-Spiegel, gleicher Inhalt). */
    const val PAUSE_OK_FRAME_ALT = "cron.pause_ack"
    const val RESUME_OK_FRAME_ALT = "cron.resume_ack"

    /** Adapter-Antwort für „fehlt die Cron-Steuer-Fähigkeit“ (fail-soft). */
    const val ERROR_NOT_SUPPORTED = "not-supported"

    /** Warten-Deckel je Lauf (kein UI-Schleudern) — Parität CronRemoveRules. */
    const val ACK_TIMEOUT_MS = 5_000L

    /** Frame-Version je Vertrag der Backend-Lanes ({type:"cron.pause", v:1, …}). */
    const val FRAME_VERSION = 1

    /** Die zwei Richtungen des Anlegen-Pausier-Verbands. */
    enum class Op { PAUSE, RESUME }

    /** Frame-Type-String je Op (Sendewelt der UI/RelayClient). */
    fun frameTypeOf(op: Op): String = when (op) {
        Op.PAUSE -> PAUSE_FRAME
        Op.RESUME -> RESUME_FRAME
    }

    /** Ausgang des Toggle-Laufs — die UI bildet je Stufe den Snack-Text. */
    enum class Outcome { SUCCESS, NOT_SUPPORTED, ERROR }

    /** Geparste Antwort ([cronId] null = Adapter ohne Echo — großzügig räumen). */
    data class ToggleAck(
        val op: Op,
        val cronId: String?,
        /** Server-Urteil: false = fehlgeschlagen (Feld fehlt = false, ehrlich). */
        val ok: Boolean,
        /** Abweisungs-Grund ("not-supported", …) — null = keiner. */
        val error: String?,
    )

    /**
     * Send-Vertrag des Toggle-Frames: [cronId] = Verbriefung aus der
     * cron.status-Zeile (dieselbe Feld-Ebene wie cron.remove — Parität).
     * [agentId] = die Agent-Adresse aus dem Zeilen-Kontext (die Zeile kam
     * aus DER Gruppe des Agents — W26c trägt JEDER Steuer-Frame sie am
     * Frame; der Relay routet je GENANNTEM Agenten statt frischester
     * Session, fail-closed NACK bei unbekannter Adresse). null/blank
     * agentId = Alt-Welt-Fall (Zeile aus einem Frame ohne agent_id — der
     * Relay fallbackt auf die frischeste Session mit LOUD-Log, bewusst
     * dokumentiert). null/blank [cronId] = Frame unvollständig (kein
     * wirksames Ziel) — der Aufrufer sendet dann BEWUSST NICHT (fail-soft
     * am Draht).
     */
    fun frame(op: Op, cronId: String?, agentId: String?): JsonObject? {
        val id = (cronId ?: "").trim()
        if (id.isEmpty()) return null
        return JsonObject().apply {
            addProperty("type", frameTypeOf(op))
            addProperty("v", FRAME_VERSION)
            addProperty("cron_id", id)
            val agent = (agentId ?: "").trim()
            if (agent.isNotEmpty()) addProperty("agent_id", agent)
        }
    }

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun boolOf(json: JsonObject, field: String): Boolean? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean
    }.getOrNull()

    private fun opOf(type: String): Op? = when (type) {
        PAUSE_OK_FRAME, PAUSE_OK_FRAME_ALT -> Op.PAUSE
        RESUME_OK_FRAME, RESUME_OK_FRAME_ALT -> Op.RESUME
        else -> null
    }

    /**
     * Ack-Verband: null = fremde/unvollständige Struktur (verwerfen —
     * fail-soft, KEIN Crash, KEIN UI-Zustands-Wechsel).
     */
    fun parse(json: JsonObject): ToggleAck? {
        val type = strOf(json, "type") ?: return null
        val op = opOf(type) ?: return null
        return ToggleAck(
            op = op,
            cronId = strOf(json, "cron_id")?.takeIf { it.isNotBlank() },
            ok = boolOf(json, "ok") ?: false,
            error = strOf(json, "error")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Ausgang → Snack-Staffel der UI (Brief: Erfolg = der frische cron.status
     * zieht den Zustand nach, Fehler = Snack fail-soft, KEIN Crash, KEIN Loop
     * — ein ERROR-Echo bleibt ohne Wiederhol-Senden).
     * [SUCCESS] nur bei ok=true; fehlendes ok-Feld liest ehrlich ERROR.
     */
    fun outcome(ack: ToggleAck): Outcome = when {
        ack.ok -> Outcome.SUCCESS
        ack.error == ERROR_NOT_SUPPORTED -> Outcome.NOT_SUPPORTED
        else -> Outcome.ERROR
    }

    // ── Warten-Zustand-Faltung (pure, für StateFlow-Updates; Parität
    // CronRemoveRules.withPending/withoutPending/sweepPending) ───────────────

    /** EIN Merker je cron_id: PAUSE/RESUME teilen das Zeilen-Deckel-Merkel
     * (eine Zeile kann nie pause- UND resumieren zugleich); die RICHTUNG
     * lebt je Merkern (W26c: der Ziel-Status-Sweep braucht sie). */
    fun withPending(current: Map<String, Op>, cronId: String, op: Op):
        Map<String, Op> {
        val id = cronId.trim()
        if (id.isEmpty() || (id in current && current[id] == op)) return current
        return current + (id to op)
    }

    /** Eine Antwort räumt ihren Merker (Echo-Fall; Parität CronRemoveRules). */
    fun withoutPending(current: Map<String, Op>, cronId: String?):
        Map<String, Op> {
        if (cronId.isNullOrBlank() || cronId !in current) return current
        return current - cronId
    }

    /**
     * Echo-los: EIN Auslöser-Faser — großzügig räumen (Parität remove:
     * handleCronRemoveAckFrame setzt emptyMap ohne Echo, kein Stau).
     */
    fun sweepAllPending(current: Map<String, Op>): Map<String, Op> = emptyMap()

    /**
     * W26c (Jäger-Sollte, Toggle-Sweep-Semantik): der frische cron.status
     * räumt einen Merker, wenn SEINE Zeile den ZIEL-Status trägt — Pause
     * erfüllt sich auf paused, Resume auf nicht-paused (planned/running).
     * Die kopierte remove-Semantik („Job weg“) tötete Toggle-Merker nur,
     * wenn die Zeile FLOH — bei Toggle bleibt sie (paused/planned) und
     * zeigte sonst falsche Warten-Snacks je echtem Erfolg. Merker ohne
     * Zeile im Stand warten auf Ack/Deckel — KEIN Loop von hier.
     */
    fun sweepPending(
        current: Map<String, Op>,
        jobsByAgent: Map<String, List<DashboardRules.CronJobItem>>,
    ): Map<String, Op> {
        if (current.isEmpty()) return current
        var next = current
        var changed = false
        current.forEach { (cronId, op) ->
            val row = jobsByAgent.values
                .asSequence()
                .flatten()
                .firstOrNull { it.cronId == cronId } ?: return@forEach
            val fulfilled = if (op == Op.PAUSE) {
                row.state == DashboardRules.CronJobState.PAUSED
            } else {
                row.state != DashboardRules.CronJobState.PAUSED
            }
            if (fulfilled) {
                next = next - cronId
                changed = true
            }
        }
        return if (changed) next else current
    }
}
