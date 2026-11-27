package com.hermesandroid.bridge.ui.cron

import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W23-11-App (TEIL 1): Gruppier-Regeln des 5. Tabs „Geplante Aufgaben“ —
 * generische PRO-AGENT-Verband (Namen NIE hartkodiert: Presence-lookup →
 * agent_id → generischer Fallback), PER-AGENT-REPLACE-Faltung und die
 * cron_id-Verbriefung, die die Dashboard-Faser unverändert durchwandert.
 */
class CronScreenRulesTest {

    private val t0 = 1_700_000_000_000L

    private fun agent(id: String, name: String = id, online: Boolean = false) =
        CronScreenRules.AgentRef(id, name, online)

    private fun job(
        name: String,
        nextAt: Long? = null,
        cronId: String? = null,
    ) = DashboardRules.CronJobItem(
        name = name,
        state = DashboardRules.CronJobState.PLANNED,
        nextAtMs = nextAt,
        cronId = cronId,
    )

    // ── groups(): PRO-AGENT-Verband ──────────────────────────────────────────

    @Test
    fun groupsFollowPresenceOrderAndNames() {
        // Reihen-Fibel = die Presence-Fluss-Reihenfolge (relay.state), NIE eine
        // eigene Sortier-Fibel der App.
        val groups = CronScreenRules.groups(
            jobsByAgent = mapOf(
                "worker" to listOf(job("W1", t0 + 60_000L, "c-w1")),
                "boss" to listOf(job("B1"), job("B2")),
            ),
            agents = listOf(agent("boss", "Haupt", online = true), agent("worker", "Helfer")),
            nowMs = t0,
        )
        assertEquals(listOf("boss", "worker"), groups.map { it.agentKey })
        assertEquals("Haupt", groups[0].displayName)
        assertEquals("Helfer", groups[1].displayName)
        assertTrue(groups.all { it.known })
        assertTrue(groups[0].online)
        assertFalse(groups[1].online)
        // Zeilen der Faser dashboard = identisch sortiert:
        assertEquals(2, groups[0].rows.size)
    }

    @Test
    fun presencelessAgentKeysFallBackToTheirIdSorted() {
        // Presence fehlt → die agent_id IST der Name (Frame-Daten, generisch);
        // alphabetische Determinismus (Locale.ROOT), bekannt=false, offline-Dot.
        val groups = CronScreenRules.groups(
            jobsByAgent = mapOf(
                "zeta" to listOf(job("Z")),
                "alpha" to listOf(job("A")),
            ),
            agents = emptyList(),
            nowMs = t0,
        )
        assertEquals(listOf("alpha", "zeta"), groups.map { it.agentKey })
        assertEquals("alpha", groups[0].displayName)
        assertFalse(groups[0].known)
        assertFalse(groups[0].online)
    }

    @Test
    fun unknownBucketComesLastAsGenericFallback() {
        // Stand ohne agent_id (Alt-Klartext) = ""-Eimer → generische
        // Fallback-Gruppe GANZ ZULETZT, Anzeige-Ressource ist Sache der UI.
        val groups = CronScreenRules.groups(
            jobsByAgent = mapOf(
                "" to listOf(job("Legacy")),
                "boss" to listOf(job("B")),
            ),
            agents = listOf(agent("boss", "Haupt")),
            nowMs = t0,
        )
        assertEquals(2, groups.size)
        assertEquals("boss", groups[0].agentKey)
        assertEquals("", groups[1].agentKey)
        assertEquals("", groups[1].displayName)
        assertFalse(groups[1].known)
    }

    @Test
    fun agentsWithoutJobsGetNoGroup() {
        // Leeres Dropdown = Geraffel — Agenten ohne Crons verschwinden ganz.
        val groups = CronScreenRules.groups(
            jobsByAgent = mapOf("boss" to listOf(job("B"))),
            agents = listOf(agent("boss", "Haupt"), agent("idle", "Nichtstuer")),
            nowMs = t0,
        )
        assertEquals(1, groups.size)
        assertEquals("boss", groups[0].agentKey)
    }

    @Test
    fun emptyInputGivesNoGroups() {
        assertTrue(CronScreenRules.groups(emptyMap(), emptyList(), t0).isEmpty())
    }

    // ── replaceAgentJobs() / flattenJobs(): PER-AGENT-REPLACE ────────────────

    @Test
    fun replaceAgentJobsReplacesOnlyThatAgentsStand() {
        var merged = CronScreenRules.replaceAgentJobs(
            emptyMap(), "boss", listOf(job("alt")),
        )
        merged = CronScreenRules.replaceAgentJobs(
            merged, "worker", listOf(job("W")),
        )
        // Zweiter Frame desselben Agents = REPLACE (nicht Append, nicht Merge):
        merged = CronScreenRules.replaceAgentJobs(
            merged, "boss", listOf(job("neu1"), job("neu2")),
        )
        assertEquals(listOf("worker", "boss"), merged.keys.toList()) // Einfüge-Reihenfolge bleibt
        assertEquals(listOf("neu1", "neu2"), merged["boss"]!!.map { it.name })
    }

    @Test
    fun emptyStandDropsTheAgent() {
        var merged = CronScreenRules.replaceAgentJobs(emptyMap(), "boss", listOf(job("B")))
        merged = CronScreenRules.replaceAgentJobs(merged, "boss", emptyList())
        assertFalse(merged.containsKey("boss"))
        // Andere Agenten bleiben unangetastet:
        merged = CronScreenRules.replaceAgentJobs(emptyMap(), "a", listOf(job("A")))
        merged = CronScreenRules.replaceAgentJobs(merged, "b", listOf(job("B")))
        merged = CronScreenRules.replaceAgentJobs(merged, "a", emptyList())
        assertEquals(listOf("b"), merged.keys.toList())
    }

    @Test
    fun flattenKeepsGroupOrderForTheDashboardUnion() {
        val merged = mapOf(
            "boss" to listOf(job("B"), job("B2")),
            "" to listOf(job("legacy")),
            "worker" to listOf(job("W")),
        )
        assertEquals(listOf("B", "B2", "legacy", "W"), CronScreenRules.flattenJobs(merged).map { it.name })
    }

    // ── cron_id-Verbriefung (TEIL 2-Vorbereitung) ────────────────────────────

    @Test
    fun rowsCarryCronIdThroughTheSortingFiber() {
        // Die Sortier-Faser des Dashboards wandert mit — die CronScreen-Zeile
        // bekommt die Verbriefung unverändert (Vertrag TEIL 2).
        val groups = CronScreenRules.groups(
            jobsByAgent = mapOf(
                "boss" to listOf(
                    job("später", nextAt = t0 + 90_000L, cronId = "late"),
                    job("fällig", nextAt = t0 + 60_000L, cronId = "soon"),
                ),
            ),
            agents = listOf(agent("boss", "Haupt")),
            nowMs = t0,
        )
        val ids = groups[0].rows.map { it.item.cronId }
        assertEquals(listOf("soon", "late"), ids) // geplante nach nächster Ausführung
        assertEquals(2, ids.count { it != null })
    }

    @Test
    fun rowsWithoutCronIdStayVisible() {
        // Alt-Stand ohne Verbriefung: Sicht bleibt, cronId bleibt null (die
        // UI verbirgt dann das Lösch-Element statt still zu senden).
        val groups = CronScreenRules.groups(
            jobsByAgent = mapOf("boss" to listOf(job("B"))),
            agents = emptyList(),
            nowMs = t0,
        )
        assertTrue(groups[0].rows[0].item.cronId == null)
        assertEquals("B", groups[0].rows[0].item.name)
    }

    // ── W26d (F5): die Uhr-Sprung-Adresse auf einen Agenten OHNE Jobs ────────

    @Test
    fun pendingFocusGetsAnEmptyGroupEvenWithoutJobs() {
        // Der Sprung-Fokus je 0-Jobs-Agent (agent.info-Cap, NULL Aufgaben):
        // GENAU eine (leere)-Gruppe je ihm — die Adresse ist nie tot (Report
        // F5: sonst stand der Nutzer in einer Sicht, die ihn nicht ansprach).
        val groups = CronScreenRules.groups(
            jobsByAgent = emptyMap(),
            agents = listOf(agent("boss", "Haupt", online = true)),
            nowMs = t0,
            pendingFocusAgentId = "boss",
        )
        assertEquals(listOf("boss"), groups.map { it.agentKey })
        assertEquals("Haupt", groups[0].displayName)
        assertTrue(groups[0].known)
        assertTrue(groups[0].online)
        assertTrue(groups[0].rows.isEmpty())
    }

    @Test
    fun pendingFocusResolvesPresenceNameElseAgentId() {
        // OHNE presence-Zeile: der Anzeige-Name ist die agent_id (Frame-Daten,
        // generisch — KEIN hartkodierter Name).
        val groups = CronScreenRules.groups(
            jobsByAgent = emptyMap(),
            agents = emptyList(),
            nowMs = t0,
            pendingFocusAgentId = "worker-9",
        )
        assertEquals(1, groups.size)
        assertEquals("worker-9", groups[0].agentKey)
        assertEquals("worker-9", groups[0].displayName)
        assertFalse(groups[0].known)
        assertFalse(groups[0].online)
    }

    @Test
    fun pendingFocusDoesNotDuplicateAnExistingGroup() {
        // Der Fokus-Agent HAT Jobs: die (leere)-Faser doppelt NIE — die
        // Bestands-Gruppe (mit ihren Zeilen) bleibt exakt ohne Zweit-Karte.
        val groups = CronScreenRules.groups(
            jobsByAgent = mapOf("boss" to listOf(job("B1", t0 + 60_000L, "c-b1"))),
            agents = listOf(agent("boss", "Haupt")),
            nowMs = t0,
            pendingFocusAgentId = "boss",
        )
        assertEquals(listOf("boss"), groups.map { it.agentKey })
        assertEquals(1, groups[0].rows.size)
    }

    @Test
    fun blankPendingFocusKeepsTheBestExactly() {
        // Kein Sprung (null/blank): KEINE Extra-Gruppe — der Bestand bleibt
        // exakt (leerer Stand = leere Liste; leere Agent-Gruppen fliegen wie
        // zuvor).
        assertTrue(
            CronScreenRules.groups(
                jobsByAgent = emptyMap(),
                agents = listOf(agent("boss", "Haupt")),
                nowMs = t0,
            ).isEmpty(),
        )
        assertTrue(
            CronScreenRules.groups(
                jobsByAgent = emptyMap(),
                agents = listOf(agent("boss", "Haupt")),
                nowMs = t0,
                pendingFocusAgentId = "   ",
            ).isEmpty(),
        )
    }
}
