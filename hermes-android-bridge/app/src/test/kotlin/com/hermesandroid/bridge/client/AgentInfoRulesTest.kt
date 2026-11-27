package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W26-App: JVM-Tests des agent.info-Verbands (Caps je Adapter-Version,
 * fail-soft-Parse, PER-AGENT-REPLACE-Faltung — Parität CronScreenRules).
 */
class AgentInfoRulesTest {

    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw).asJsonObject

    // ── parse(): Pflicht-Träger + fail-soft Caps ──────────────────────────────

    @Test
    fun parsesFrameVerbatim() {
        val caps = AgentInfoRules.parse(
            json(
                """{"type":"agent.info","v":1,"agent_id":"agent-1",
                    "display_name":"Agent 1",
                    "adapter_caps":["cron.status","cron.remove","cron.pause",
                                    "cron.resume","cron.add","models","status"]}""",
            ),
        )!!
        assertEquals("agent-1", caps.agentId)
        assertEquals("Agent 1", caps.displayName)
        assertEquals(
            setOf(
                "cron.status", "cron.remove", "cron.pause",
                "cron.resume", "cron.add", "models", "status",
            ),
            caps.caps,
        )
    }

    @Test
    fun unknownCapsStayVerbatim() {
        // VERSIONS-fail-soft: die Zukunft darf neue Caps melden (verbatim),
        // ohne die App zu brechen.
        val caps = AgentInfoRules.parse(
            json("""{"agent_id":"a","adapter_caps":["cron.pause","voice"]}"""),
        )!!
        assertTrue(caps.caps.contains("voice"))
        assertTrue(AgentInfoRules.hasCap(caps.caps, "voice"))
    }

    @Test
    fun missingAgentIdDropsTheFrame() {
        assertNull(AgentInfoRules.parse(json("""{"adapter_caps":[]}""")))
        assertNull(AgentInfoRules.parse(json("""{"agent_id":"   "}""")))
    }

    @Test
    fun missingCapsListIsHonestEmpty() {
        // Alter/teilweiser Adapter: kein adapter_caps-Feld = leerer Cap-Stand
        // (ehrlich ohne Verwaltungs-Angebote, kein Fake).
        val caps = AgentInfoRules.parse(json("""{"agent_id":"a"}"""))!!
        assertTrue(caps.caps.isEmpty())
        assertFalse(AgentInfoRules.managesCron(caps.caps))
    }

    // ── hasCap/managesCron: die UI schaltet je Gemeldetem ─────────────────────

    @Test
    fun hasCapGateReadsOnlyTheVerbatimStand() {
        assertTrue(AgentInfoRules.hasCap(setOf("cron.add"), AgentInfoRules.CAP_CRON_ADD))
        assertFalse(AgentInfoRules.hasCap(setOf("cron.add"), AgentInfoRules.CAP_CRON_PAUSE))
        // null = kein agent.info-Stand (Alter Adapter) ⇒ ehrlich false.
        assertFalse(AgentInfoRules.hasCap(null, AgentInfoRules.CAP_CRON_PAUSE))
    }

    @Test
    fun managesCronCoversTheCronFamilie() {
        assertTrue(AgentInfoRules.managesCron(setOf("cron.status", "cron.remove")))
        assertTrue(AgentInfoRules.managesCron(setOf("voices", "cron.add")))
        assertFalse(AgentInfoRules.managesCron(setOf("models", "status")))
        assertFalse(AgentInfoRules.managesCron(emptySet()))
        assertFalse(AgentInfoRules.managesCron(null))
    }

    // ── replaceAgentCaps: PER-AGENT-REPLACE (Parität CronScreenRules) ─────────

    @Test
    fun foldReplacesTheAgentStand() {
        val initial = AgentInfoRules.replaceAgentCaps(
            emptyMap(),
            AgentInfoRules.AgentCaps("a", caps = setOf("models")),
        )
        val second = AgentInfoRules.replaceAgentCaps(
            initial,
            AgentInfoRules.AgentCaps("b", caps = setOf("status")),
        )
        assertEquals(listOf("a", "b"), second.keys.toList())
        // REPLACE (nicht Merge): der neue Stand des AGENTS zählt, nie ein Alt-Slot.
        val third = AgentInfoRules.replaceAgentCaps(
            second,
            AgentInfoRules.AgentCaps("a", caps = setOf("cron.add")),
        )
        assertEquals(listOf("b", "a"), third.keys.toList())
        assertEquals(setOf("cron.add"), third["a"]!!.caps)
    }
}
