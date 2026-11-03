package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH-FIX-CHAT-A (F5): Timeout-Vertrag des Arbeits-Spinners
 * (PIPELINE-BUGS-20261102 BUG 3 — „arbeitet … (mm:ss)“ countete endlos,
 * weil working=false in drei Live-Situationen nie ankam).
 *
 * Vertrag: nach 10 Minuten (oder bei unbrauchbarem started_at) bricht die
 * Anzeige ab; davor läuft der mm:ss-Ticker normal weiter. started_at in
 * der Zukunft (Uhr-Abweichung) zählt von 0 hoch und läuft in den Deckel.
 */
class WorkingStatusRulesTest {

    private val t0 = 1_735_000_000_000L   // beliebiger fester Bezugspunkt

    @Test
    fun notTimedOutInsideTheBudget() {
        assertFalse(WorkingStatusRules.timedOut(t0, t0))
        assertFalse(WorkingStatusRules.timedOut(t0, t0 + 9 * 60_000L))          // 9:59 …
        assertFalse(WorkingStatusRules.timedOut(t0, t0 + 10 * 60_000L - 1))    // … 1 ms vorher
    }

    @Test
    fun timedOutAtAndAfterTenMinutes() {
        assertTrue(WorkingStatusRules.timedOut(t0, t0 + 10 * 60_000L))         // exakt 10:00
        assertTrue(WorkingStatusRules.timedOut(t0, t0 + 45 * 60_000L))          // der Live-Fund: Ewigzähler
    }

    @Test
    fun garbageStartedAtIsImmediatelyDead() {
        assertTrue(WorkingStatusRules.timedOut(0L, t0))
        assertTrue(WorkingStatusRules.timedOut(-1L, t0))
    }

    @Test
    fun futureStartedAtCountsUpAndEventuallyHitsTheCap() {
        // Uhr-Abweichung: started_at 2 min in der Zukunft — nicht sofort tot,
        // aber der Deckel greift, sobald die Differenz 10 min überschreitet.
        assertFalse(WorkingStatusRules.timedOut(t0 + 2 * 60_000L, t0))
        assertTrue(WorkingStatusRules.timedOut(t0 + 2 * 60_000L, t0 + 12 * 60_000L))
    }

    @Test
    fun budgetConstantIsTenMinutes() {
        assertTrue(WorkingStatusRules.WORKING_TIMEOUT_MS == 10 * 60_000L)
    }
}