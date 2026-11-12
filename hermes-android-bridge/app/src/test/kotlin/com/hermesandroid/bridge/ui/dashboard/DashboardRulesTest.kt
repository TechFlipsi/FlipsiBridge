package com.hermesandroid.bridge.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W15 (Sir-Fixliste 9): Regeln des Status-Dashboards — Status-Ableitung
 * (Online/Beschäftigt/Offline inkl. 10-Minuten-Deckel), mm:ss-Live-Label,
 * relative Zeit der letzten Aktivität und die Zusammenfassungs-Zeile.
 */
class DashboardRulesTest {

    private val t0 = 1_735_000_000_000L   // beliebiger fester Bezugspunkt

    // ── statusOf ───────────────────────────────────────────────────────────

    @Test
    fun onlineAndIdleShowsOnline() {
        assertEquals(
            DashboardRules.AgentStatus.ONLINE,
            DashboardRules.statusOf(online = true, working = false, startedAt = t0, nowMs = t0),
        )
    }

    @Test
    fun onlineWithoutWorkingFrameShowsOnline() {
        assertEquals(
            DashboardRules.AgentStatus.ONLINE,
            DashboardRules.statusOf(online = true, working = null, startedAt = 0L, nowMs = t0),
        )
    }

    @Test
    fun freshWorkingFrameShowsBusy() {
        assertEquals(
            DashboardRules.AgentStatus.BUSY,
            DashboardRules.statusOf(online = true, working = true, startedAt = t0, nowMs = t0 + 30_000L),
        )
    }

    @Test
    fun staleWorkingFrameFallsBackToOnline() {
        // PIPELINE-BUGS BUG 3-Verband: working=true ohne false-Frame darf nie
        // endlos „Beschäftigt“ zeigen — nach 10 Minuten Deckel gilt Online.
        assertEquals(
            DashboardRules.AgentStatus.ONLINE,
            DashboardRules.statusOf(online = true, working = true, startedAt = t0, nowMs = t0 + 11 * 60_000L),
        )
    }

    @Test
    fun offlineBeatsWorking() {
        // Ohne Verbindung „arbeitet“ niemand — Offline schlägt working=true.
        assertEquals(
            DashboardRules.AgentStatus.OFFLINE,
            DashboardRules.statusOf(online = false, working = true, startedAt = t0, nowMs = t0),
        )
        assertEquals(
            DashboardRules.AgentStatus.OFFLINE,
            DashboardRules.statusOf(online = false, working = false, startedAt = t0, nowMs = t0),
        )
    }

    // ── workingTimedOut (Deckel-Delegation, gleiche Faser wie statusOf) ─────

    @Test
    fun workingTimedOutMatchesStatusOfRule() {
        // frisch = nicht verwaist
        assertEquals(false, DashboardRules.workingTimedOut(t0, t0 + 30_000L))
        // über dem 10-Minuten-Deckel = verwaist (kein Ewigzähler)
        assertEquals(true, DashboardRules.workingTimedOut(t0, t0 + 11 * 60_000L))
        // startedAt <= 0 gilt wie im Conversation-Kopf als verwaist
        assertEquals(true, DashboardRules.workingTimedOut(0L, t0))
    }

    // ── workingElapsedLabel ────────────────────────────────────────────────

    @Test
    fun elapsedLabelFormatsMinutesSeconds() {
        assertEquals("0:00", DashboardRules.workingElapsedLabel(t0, t0))
        assertEquals("0:59", DashboardRules.workingElapsedLabel(t0, t0 + 59_000L))
        assertEquals("2:05", DashboardRules.workingElapsedLabel(t0, t0 + 125_000L))
    }

    @Test
    fun elapsedLabelNeverNegativeAndCappedAtTenMinutes() {
        // startedAt in der Zukunft (Uhr-Abweichung) = 0, nie minus.
        assertEquals("0:00", DashboardRules.workingElapsedLabel(t0 + 5_000L, t0))
        // Über dem Deckel bleibt der Text bei 10:00 stehen (kein Ewigzähler).
        assertEquals("10:00", DashboardRules.workingElapsedLabel(t0, t0 + 45 * 60_000L))
    }

    // ── relativeTime ───────────────────────────────────────────────────────

    @Test
    fun relativeTimeBuckets() {
        assertEquals("—", DashboardRules.relativeTime(0L, t0))
        assertEquals("gerade eben", DashboardRules.relativeTime(t0 - 30_000L, t0))
        assertEquals("vor 4 min", DashboardRules.relativeTime(t0 - 4 * 60_000L - 1_000L, t0))
        assertEquals("vor 2 Std.", DashboardRules.relativeTime(t0 - 2 * 3_600_000L, t0))
        assertEquals("vor 1 Tag", DashboardRules.relativeTime(t0 - 24 * 3_600_000L, t0))
        assertEquals("vor 3 Tagen", DashboardRules.relativeTime(t0 - 3 * 24 * 3_600_000L, t0))
    }

    // ── summaryLine / agentCountLabel ──────────────────────────────────────

    @Test
    fun summaryLineDropsEmptyPartsAndUsesSingular() {
        assertEquals("", DashboardRules.summaryLine(total = 0, online = 0, busy = 0))
        assertEquals("1 Agent", DashboardRules.summaryLine(total = 1, online = 0, busy = 0))
        assertEquals("3 Agenten · 2 online · 1 beschäftigt",
            DashboardRules.summaryLine(total = 3, online = 2, busy = 1))
        assertEquals("2 Agenten · 2 online", DashboardRules.summaryLine(total = 2, online = 2, busy = 0))
    }

    @Test
    fun agentCountSingularAndPlural() {
        assertEquals("1 Agent", DashboardRules.agentCountLabel(1))
        assertEquals("5 Agenten", DashboardRules.agentCountLabel(5))
    }
}
