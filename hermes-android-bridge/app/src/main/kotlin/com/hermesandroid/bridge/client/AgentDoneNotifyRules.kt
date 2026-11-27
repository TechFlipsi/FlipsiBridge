package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.hermesandroid.bridge.R

/**
 * W23-11-App (Arbeits-Brief; Frame-Verträge
 * Lane-Brief TEIL 3+4): Fertig-Benachrichtigungen der
 * Agenten-Arbeit — rein, JVM-testbar (RelayClient notifiziert nur; die
 * Adapter-Seite baut die Python-Lane separat — der Client bleibt fail-soft,
 * wenn die Frames (noch) fehlen: KEIN Crash, KEIN Spam-Gerüst).
 *
 * FRAME-VERTRÄGE (Adapter-Lane):
 *
 * - agent.subagent_done {child_session_id, parent_session_id,
 *    child_status ("completed"|"interrupted"|"failed"), child_summary,
 *    duration_ms, child_goal} — nach dem subagent_stop-W19-Hook, je
 *    Phone-Slot-Fan-out (NICHT je Agent). Fail-soft: leere summary ok
 *    (goal als Fallback — SERVER-seitig gecapt bei 64 Z; die App cappt die
 *    Anzeige zusätzlich).
 * - agent.idle_transition {agent_id, display_name, working_started_at} —
 *    je Transition working true→false (Haupt-Agent fertig), gleicher
 *    Fan-out.
 *
 * KANAL-Verband (Brief): Telegram/Discord/App-„unabhängig“ ist SERVER-seitig
 * (die notify-Kette der Adapter-Lane); die App-Notif ist NUR die App-Ebene —
 * EINE Android-Notif je Frame (keine Doppel-Spam-Verband), dem Bestand folgt
 * (NotificationManager + dedizierter Kanal, KEIN neuer Service, kein
 * E2E-öffentlicher Split — diese Frames tragen KEINEN Chat-Inhalt).
 *
 * Generik: display_name/Status-Texte kommen aus den Frames, KEIN
 * hartkodierter Name; die Status-Worte laufen über Ressourcen (×3), Unbekanntes
 * bleibt ehrlich als Roh-Wert.
 */
object AgentDoneNotifyRules {

    const val SUBAGENT_DONE_FRAME = "agent.subagent_done"
    const val SUBAGENT_DONE_ENC_FRAME = "agent.subagent_done.enc"
    const val IDLE_TRANSITION_FRAME = "agent.idle_transition"
    const val IDLE_TRANSITION_ENC_FRAME = "agent.idle_transition.enc"

    /** Prefs-Toggles (Einstellungen › App — „Fertigmeldungen“-Karte). */
    const val PREF_ENABLE_SUBAGENT = "notify_subagent_done"
    const val PREF_ENABLE_AGENT = "notify_agent_done"
    /** Default beides AN (Brief: „Default beide AN“). */
    const val DEFAULT_ENABLED = true

    /** Anzeige-Cap des Notif-Texts (Parität notifyIncomingChat: take(120)). */
    const val BODY_MAX_CHARS = 120

    /**
     * Geparste subagent_done-Felder (alle lenient; [content] = summary mit
     * goal-Fallback, leer → "" — die Stufe bleibt sichtbar).
     */
    data class SubagentDone(
        val childSessionId: String?,
        val parentSessionId: String?,
        val childStatus: String?,
        val summary: String?,
        val durationMs: Long?,
        val goal: String?,
    ) {
        val content: String get() = (summary ?: "").trim().ifEmpty { (goal ?: "").trim() }
    }

    /** Geparsede idle_transition (display_name mit agent_id-Fallback). */
    data class IdleTransition(
        val agentId: String,
        val displayName: String,
        val workingStartedAt: Long?,
    ) {
        val displayNameOrId: String get() = displayName.trim().ifEmpty { agentId.trim() }
    }

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
    }.getOrNull()

    private fun longOf(json: JsonObject, field: String): Long? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    /**
     * parseSubagentDone — fail-soft: ganz fremdes/leeres Frame → null (keine
     * Notif aus Rauschen). Pflicht-Gate: mind. child_session_id ODER
     * child_status muss anwesend sein (jenseits dessen sind alle Felder
     * lenient, leere summary liest den goal-Fallback).
     */
    fun parseSubagentDone(json: JsonObject): SubagentDone? {
        val childSessionId = strOf(json, "child_session_id")
        val childStatus = strOf(json, "child_status")
        if (childSessionId.isNullOrBlank() && childStatus.isNullOrBlank()) return null
        return SubagentDone(
            childSessionId = childSessionId,
            parentSessionId = strOf(json, "parent_session_id"),
            childStatus = childStatus,
            summary = strOf(json, "child_summary"),
            durationMs = longOf(json, "duration_ms"),
            goal = strOf(json, "child_goal"),
        )
    }

    /**
     * parseIdleTransition — fail-soft: ohne agent_id UND ohne display_name →
     * null (keine Notif ohne Träger); Fehl-started_at ist ok (die App zeigt
     * ihn nicht).
     */
    fun parseIdleTransition(json: JsonObject): IdleTransition? {
        val agentId = strOf(json, "agent_id").orEmpty()
        val displayName = strOf(json, "display_name").orEmpty()
        if (agentId.isEmpty() && displayName.isBlank()) return null
        return IdleTransition(
            agentId = agentId,
            displayName = displayName,
            workingStartedAt = longOf(json, "working_started_at"),
        )
    }

    /**
     * Status-Wort der Sub-Stufen — Protokoll-Strings des Vertrags in
     * Ressourcen (×3); Unbekanntes/Leeres → null (die Stufe bleibt ehrlich
     * als Roh-Wert der Stufe stehen statt erfundener Übersetzung).
     */
    fun statusRes(childStatus: String?): Int? = when (childStatus?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "completed" -> R.string.cron_sub_status_completed
        "interrupted" -> R.string.cron_sub_status_interrupted
        "failed" -> R.string.cron_sub_status_failed
        else -> null
    }

    /** Notif-Text-Cap (Anzeige-Verband, Parität dem Chat-Notif-Bestand). */
    fun clipBody(raw: String): String = raw.trim().take(BODY_MAX_CHARS)
}
