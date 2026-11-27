package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W23-11-App (TEIL 3): Fertig-Benachrichtigungs-Verband der Agenten-Arbeit —
 * Frame-Parsen fail-soft (agent.subagent_done {child_status, child_summary,
 * child_goal,…}, agent.idle_transition {agent_id, display_name,…}),
 * goal-Fallback bei leerer summary, Status-Wort-Ressourcen der 3
 * Protokoll-Stufen (Unbekanntes = Roh-Wert), Name-Fallback id → display_name,
 * Prefs-Schlüßel + Default beides AN und der 6-Frame-Toleranz-Verband.
 */
class AgentDoneNotifyRulesTest {

    private fun subagentDone(
        childSessionId: String? = "cs-1",
        childStatus: String? = "completed",
        summary: String? = "Recherche abgeschlossen",
        goal: String? = "Recherche",
        durationMs: Long? = 1500L,
        parentSessionId: String? = "ps-1",
    ): JsonObject {
        val json = JsonObject()
        childSessionId?.let { json.addProperty("child_session_id", it) }
        parentSessionId?.let { json.addProperty("parent_session_id", it) }
        childStatus?.let { json.addProperty("child_status", it) }
        summary?.let { json.addProperty("child_summary", it) }
        durationMs?.let { json.addProperty("duration_ms", it) }
        goal?.let { json.addProperty("child_goal", it) }
        return json
    }

    // ── parseSubagentDone ─────────────────────────────────────────────────────

    @Test
    fun subagentDoneParsesAllFields() {
        val frame = AgentDoneNotifyRules.parseSubagentDone(subagentDone())!!
        assertEquals("cs-1", frame.childSessionId)
        assertEquals("ps-1", frame.parentSessionId)
        assertEquals("completed", frame.childStatus)
        assertEquals(1500L, frame.durationMs)
        assertEquals("Recherche abgeschlossen", frame.content)
    }

    @Test
    fun emptySummaryFallsBackToGoal() {
        // Fail-soft-Verband des Briefs: leere summary ok, goal als Fallback.
        val frame = AgentDoneNotifyRules.parseSubagentDone(
            subagentDone(summary = "", goal = "Ziel-Text"),
        )!!
        assertEquals("Ziel-Text", frame.content)
        // summary nur-Whitespace liest ebenso wie leer:
        val ws = AgentDoneNotifyRules.parseSubagentDone(
            subagentDone(summary = "   ", goal = "Ziel-Text"),
        )!!
        assertEquals("Ziel-Text", ws.content)
    }

    @Test
    fun bothContentPartsEmptyKeepTheStageVisible() {
        val frame = AgentDoneNotifyRules.parseSubagentDone(
            subagentDone(summary = null, goal = null),
        )!!
        assertEquals("", frame.content)
        assertNotNull(frame) // Frame selbst bleibt (Stufe + Status bleiben sichtbar)
    }

    @Test
    fun foreignNoiseIsRejected() {
        // Ganz fremdes/leeres Frame → null (keine Notif aus Rauschen, fail-soft).
        assertNull(AgentDoneNotifyRules.parseSubagentDone(JsonObject()))
        assertNull(AgentDoneNotifyRules.parseSubagentDone(subagentDone(childSessionId = null, childStatus = null)))
    }

    // ── Status-Worte (Protokoll → Ressourcen ×3) ──────────────────────────────

    @Test
    fun contractStatusesMapToResources() {
        assertEquals(RRule.cron_sub_status_completed, AgentDoneNotifyRules.statusRes("completed"))
        assertEquals(RRule.cron_sub_status_interrupted, AgentDoneNotifyRules.statusRes("interrupted"))
        assertEquals(RRule.cron_sub_status_failed, AgentDoneNotifyRules.statusRes("failed"))
    }

    @Test
    fun caseAndWhitespaceDoNotBreakTheMapping() {
        assertEquals(RRule.cron_sub_status_completed, AgentDoneNotifyRules.statusRes("COMPLETED"))
        assertEquals(RRule.cron_sub_status_failed, AgentDoneNotifyRules.statusRes("  failed "))
    }

    @Test
    fun unknownStatusStaysRaw() {
        // Unbekanntes/Leeres → null: die Notif zeigt den Roh-Wert statt einer
        // erfundenen Übersetzung (ehrlich fail-soft).
        assertNull(AgentDoneNotifyRules.statusRes("aborted"))
        assertNull(AgentDoneNotifyRules.statusRes(""))
        assertNull(AgentDoneNotifyRules.statusRes(null))
    }

    // ── parseIdleTransition ───────────────────────────────────────────────────

    private fun idle(
        agentId: String? = "alpha",
        displayName: String? = "Haupt-Agent",
        startedAt: Long? = 42L,
    ): JsonObject {
        val json = JsonObject()
        agentId?.let { json.addProperty("agent_id", it) }
        displayName?.let { json.addProperty("display_name", it) }
        startedAt?.let { json.addProperty("working_started_at", it) }
        return json
    }

    @Test
    fun idleTransitionParsesNameAndFallsBackToId() {
        val frame = AgentDoneNotifyRules.parseIdleTransition(idle())!!
        assertEquals("alpha", frame.agentId)
        assertEquals(42L, frame.workingStartedAt)
        val fallback = AgentDoneNotifyRules.parseIdleTransition(idle(displayName = ""))!!
        assertEquals("alpha", fallback.displayNameOrId)
        assertEquals("Haupt-Agent", AgentDoneNotifyRules.parseIdleTransition(idle())!!.displayName.trim())
    }

    @Test
    fun idleTransitionWithoutBothCarriersIsRejected() {
        assertNull(AgentDoneNotifyRules.parseIdleTransition(JsonObject()))
        assertNull(AgentDoneNotifyRules.parseIdleTransition(idle(agentId = null, displayName = "  ")))
    }

    // ── Toggles + Anzeige-Cap ──────────────────────────────────────────────────

    @Test
    fun toggleKeysAndDefaultOnMatchTheBrief() {
        // Brief TEIL 3: „Default beide AN“ — die App liest zwei eigene Keys.
        assertEquals("notify_subagent_done", AgentDoneNotifyRules.PREF_ENABLE_SUBAGENT)
        assertEquals("notify_agent_done", AgentDoneNotifyRules.PREF_ENABLE_AGENT)
        assertTrue(AgentDoneNotifyRules.DEFAULT_ENABLED)
        // Bewusst KEIN zweites Default (eine Quelle, zwei Keys):
        assertFalse(AgentDoneNotifyRules.PREF_ENABLE_AGENT == AgentDoneNotifyRules.PREF_ENABLE_SUBAGENT)
    }

    @Test
    fun frameFamilyConstantsMatchTheContract() {
        assertEquals("agent.subagent_done", AgentDoneNotifyRules.SUBAGENT_DONE_FRAME)
        assertEquals("agent.subagent_done.enc", AgentDoneNotifyRules.SUBAGENT_DONE_ENC_FRAME)
        assertEquals("agent.idle_transition", AgentDoneNotifyRules.IDLE_TRANSITION_FRAME)
        assertEquals("agent.idle_transition.enc", AgentDoneNotifyRules.IDLE_TRANSITION_ENC_FRAME)
    }

    @Test
    fun bodyClipKeepsTheNotifHonest() {
        val long = "x".repeat(500)
        assertEquals(120, AgentDoneNotifyRules.clipBody(long).length)
        // Trim + Cap (keine Rand-Whitespace im Notif-Text):
        assertEquals("kort", AgentDoneNotifyRules.clipBody("  kort  "))
        assertEquals(120, AgentDoneNotifyRules.BODY_MAX_CHARS)
    }
}

/** Test-eigene Konstanten-Spiegel (R.string-IDs sind plain Ints, W15c). */
object RRule {
    val cron_sub_status_completed = com.hermesandroid.bridge.R.string.cron_sub_status_completed
    val cron_sub_status_interrupted = com.hermesandroid.bridge.R.string.cron_sub_status_interrupted
    val cron_sub_status_failed = com.hermesandroid.bridge.R.string.cron_sub_status_failed
}
