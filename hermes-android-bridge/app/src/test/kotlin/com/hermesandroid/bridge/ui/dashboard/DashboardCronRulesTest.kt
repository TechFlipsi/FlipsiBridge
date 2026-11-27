package com.hermesandroid.bridge.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 (18.11.2026, Vollgas-Batch): Regeln der Dashboard-Karte „Geplante
 * Aufgaben“ (cron.status-Verband, APP-GENERISCH) — Zustandsableitung
 * (running/planned/paused), relative Termin-Eimer, Verpasst-Flag, Sortierung
 * (laufende zuerst, verpasste drängen vorn, pausiert zuletzt) und das
 * Leerzustand-Gate. Rein JVM (keine Android-Laufzeit, gleiche Faser wie
 * DashboardRulesTest).
 */
class DashboardCronRulesTest {

    private val t0 = 1_735_000_000_000L   // beliebiger fester Bezugspunkt
    private val MIN = 60_000L

    // ── cronJobState (Protokoll-String → Zustand) ─────────────────────────────

    @Test
    fun knownStatesMapDirectly() {
        assertEquals(
            DashboardRules.CronJobState.RUNNING,
            DashboardRules.cronJobState("running"),
        )
        assertEquals(
            DashboardRules.CronJobState.PAUSED,
            DashboardRules.cronJobState("paused"),
        )
        assertEquals(
            DashboardRules.CronJobState.PLANNED,
            DashboardRules.cronJobState("scheduled"),
        )
    }

    @Test
    fun unknownOrBlankStateFallsBackToPlanned() {
        // Kein Throw, kein Fake-Zustand: Unbekanntes wird ehrlich als „geplant“
        // gelesen (der Adapter schickt keine anderen Werte).
        assertEquals(
            DashboardRules.CronJobState.PLANNED,
            DashboardRules.cronJobState("completed"),
        )
        assertEquals(
            DashboardRules.CronJobState.PLANNED,
            DashboardRules.cronJobState(null),
        )
        assertEquals(
            DashboardRules.CronJobState.PLANNED,
            DashboardRules.cronJobState("  "),
        )
    }

    // ── cronJobView (relative Eimer + Verpasst-Flag) ──────────────────────────

    @Test
    fun plannedNearFutureReadsInMinutes() {
        val view = DashboardRules.cronJobView(
            "Backup", DashboardRules.CronJobState.PLANNED, t0 + 5 * MIN, t0)
        assertEquals(5L, view.minutesFromNow)
        assertEquals(0L, view.hoursFromNow)
        assertFalse(view.overdue)
    }

    @Test
    fun plannedRoundsMinutesUp() {
        // 59 s bis zum Termin liest als „in 1 min“ (ceil-Verband) — keine „in 0
        // min“-Zeile, die sofort veraltet.
        val view = DashboardRules.cronJobView(
            "Backup", DashboardRules.CronJobState.PLANNED, t0 + 59_000L, t0)
        assertEquals(1L, view.minutesFromNow)
    }

    @Test
    fun dueExactlyNowReadsOverdueByOneMinute() {
        // delta = 0 (fällig JETZT): keine „in 0 min“-Zeile, sondern ehrlich
        // „überfällig · vor 1 min“ (knapp-fällig zählt zur Verpasst-Familie).
        val view = DashboardRules.cronJobView(
            "Backup", DashboardRules.CronJobState.PLANNED, t0, t0)
        assertTrue(view.overdue)
        assertEquals(1L, view.minutesFromNow)
    }

    @Test
    fun plannedHoursBucket() {
        val view = DashboardRules.cronJobView(
            "Backup", DashboardRules.CronJobState.PLANNED, t0 + 3 * 60 * MIN, t0)
        assertEquals(180L, view.minutesFromNow)
        assertEquals(3L, view.hoursFromNow)
    }

    @Test
    fun overduePlannedIsFlaggedNotFaked() {
        // next_at HINTER der aktuellen Zeit = verpasst (Scheduler-Tot) — ehrlich
        // als overdue geliefert statt als fälschliches „demnächst“. minutesFromNow
        // trägt die VERPASSTEN Minuten (kein negatives Loch im UI).
        val view = DashboardRules.cronJobView(
            "Backup", DashboardRules.CronJobState.PLANNED, t0 - 90 * MIN, t0)
        assertTrue(view.overdue)
        assertEquals(90L, view.minutesFromNow)
        assertEquals(null, view.hoursFromNow)
    }

    @Test
    fun runningAndPausedCarryNoTerm() {
        // running/paused mit Stempel: KEIN next_at-Konsument (Protokoll sendet
        // nur bei planned) — die Regel darf daraus KEINE Zeit erfinden.
        val running = DashboardRules.cronJobView(
            "Läuft", DashboardRules.CronJobState.RUNNING, 42L, t0)
        assertEquals(null, running.minutesFromNow)
        assertEquals(false, running.overdue)
        val paused = DashboardRules.cronJobView(
            "Pause", DashboardRules.CronJobState.PAUSED, t0 - MIN, t0)
        assertEquals(null, paused.minutesFromNow)
        assertEquals(false, paused.overdue)
    }

    @Test
    fun blankJobNameFallsBackToQuestionMark() {
        val view = DashboardRules.cronJobView(
            "  ", DashboardRules.CronJobState.PLANNED, t0 + MIN, t0)
        assertEquals("?", view.item.name)
    }

    // ── cronSortedRows (Karten-Ordnung) ───────────────────────────────────────

    @Test
    fun runningFirstThenPlannedByNextRunThenPaused() {
        val rows = DashboardRules.cronSortedRows(
            listOf(
                DashboardRules.CronJobItem("Pause", DashboardRules.CronJobState.PAUSED),
                DashboardRules.CronJobItem("Spät", DashboardRules.CronJobState.PLANNED, t0 + 90 * MIN),
                DashboardRules.CronJobItem("Läuft", DashboardRules.CronJobState.RUNNING),
                DashboardRules.CronJobItem("Früh", DashboardRules.CronJobState.PLANNED, t0 + 10 * MIN),
            ),
            t0,
        )
        assertEquals(
            listOf("Läuft", "Früh", "Spät", "Pause"),
            rows.map { it.item.name },
        )
    }

    @Test
    fun overduePlannedPushesToFrontOfPlanned() {
        // Verpasste Termine (rot) stehen VOR den demnächstigen — aber NACH den
        // laufenden Jobs; kein Cron-Entry springt über ein RUNNING hinweg.
        val rows = DashboardRules.cronSortedRows(
            listOf(
                DashboardRules.CronJobItem("Demnächst", DashboardRules.CronJobState.PLANNED, t0 + 5 * MIN),
                DashboardRules.CronJobItem("Verpasst", DashboardRules.CronJobState.PLANNED, t0 - 30 * MIN),
            ),
            t0,
        )
        assertEquals(listOf("Verpasst", "Demnächst"), rows.map { it.item.name })
        assertTrue(rows[0].overdue)
    }

    @Test
    fun rowsAreCappedAtSixteen() {
        val jobs = (1..30).map {
            DashboardRules.CronJobItem(
                "Job-$it", DashboardRules.CronJobState.PLANNED, t0 + it * MIN)
        }
        assertEquals(16, DashboardRules.cronSortedRows(jobs, t0).size)
    }

    // ── cronCardVisible (Leerzustand-Gate, W19-Muster) ────────────────────────

    @Test
    fun emptyListKeepsTheWholeSectionAway() {
        // W19 KEIN-DOPPEL-Verband: ohne Jobs keine Titelzeile, kein „keine“-
        // Geraffel — die Sektion existiert nur mit Inhalt.
        assertFalse(DashboardRules.cronCardVisible(emptyList()))
        assertTrue(
            DashboardRules.cronCardVisible(
                listOf(DashboardRules.CronJobItem("J", DashboardRules.CronJobState.PLANNED, t0 + MIN)))
        )
    }
}
