package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W23 Lane U5 (Punkt W23-5): JVM-Tests für den sichtbar gemachten
 * Retry-Backoff — Countdown-Berechnung (Ziel-basiert, aufgerundet, nie
 * negativ, Reset bei Erfolg) und Banner-Textformat („5 s“ / „1 Min 30 s“ /
 * „5 Min“, lokalisierte Einheiten).
 */
class RetryCountdownTest {

    // ── Countdown-Berechnung ──────────────────────────────────────────────

    @Test
    fun `countdown rundet auf — Rest-Bruchteile zählen als ganze Sekunde`() {
        val target = 100_000L
        // 42 400 ms Rest → „43 s“ (der Versuch frühestens dann, ehrlich).
        assertEquals(43L, RetryCountdown.remainingSeconds(target, 100_000L - 42_400L))
        // Exakt 42 000 ms Rest = 42.
        assertEquals(42L, RetryCountdown.remainingSeconds(target, 100_000L - 42_000L))
        // 100 ms Rest = 1 (rundet auf).
        assertEquals(1L, RetryCountdown.remainingSeconds(target, 100_000L - 100L))
        // 0 ms = 0.
        assertEquals(0L, RetryCountdown.remainingSeconds(target, 100_000L))
    }

    @Test
    fun `countdown ist nie negativ — Ziel in der Vergangenheit zählt als 0`() {
        val target = 50_000L
        assertEquals(0L, RetryCountdown.remainingSeconds(target, 50_001L))
        assertEquals(0L, RetryCountdown.remainingSeconds(target, target + 999_999L))
        // Stark überzogen (Doze-ticker hat lange geschlafen): auch hier 0.
        assertEquals(0L, RetryCountdown.remainingSeconds(target, 500_000L))
    }

    @Test
    fun `countdown zählt je Tick runter und bleibt einzeilig monoton`() {
        val target = 42_000L
        assertEquals(42L, RetryCountdown.remainingSeconds(target, 0L))
        assertEquals(41L, RetryCountdown.remainingSeconds(target, 1_000L))
        assertEquals(40L, RetryCountdown.remainingSeconds(target, 2_000L))
        // Ticker-Überhangelage (Freeze): Sprung direkt auf den wahren Rest.
        assertEquals(12L, RetryCountdown.remainingSeconds(target, 30_000L))
    }

    /**
     * Verband Backoff-Formel ↔ Countdown: die ReconnectPolicy-Sequenz
     * (1 s / 2 s / 4 s / 8 s / 16 s / 30 s / 30 s geklemmt) erscheint als
     * Ehrliche Rest-Sekunde im Fenster — jede geplante Warte-Sekunde ist
     * sichtbar identisch mit dem, was der Versuch tatsächlich wartet.
     */
    @Test
    fun `policy-backoff-sequenz erscheint 1-zu-1 als countdown`() {
        val policy = ReconnectPolicy(maxRetries = 10, maxBackoffMs = 30_000L, baseBackoffMs = 1_000L)
        val expectedBackoffSec = listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L)
        for (expected in expectedBackoffSec) {
            val backoffMs = policy.nextBackoffMs()
            val target = 1_000_000L + backoffMs
            val remaining = RetryCountdown.remainingSeconds(target, 1_000_000L)
            assertEquals(
                "Backoff ${backoffMs}ms muss als Rest $expected s sichtbar sein",
                expected, remaining,
            )
            assertTrue(remaining >= 0L)
        }
    }

    /**
     * Reset bei Erfolg: exhauste Policy → [ReconnectPolicy.reset] (User-
     * connect) → die Sequenz startet wieder an der Basis und der Countdown
     * zeigt die kurze Rest-Sekunde (kein eingefrorenes Fenster).
     */
    @Test
    fun `reset bei erfolg stellt die kurzen countdowns wieder her`() {
        val policy = ReconnectPolicy(maxRetries = 5, maxBackoffMs = 30_000L)
        repeat(5) { policy.nextBackoffMs() }
        assertTrue(policy.isExhausted)

        policy.reset()
        val backoffMs = policy.nextBackoffMs()
        assertEquals(1_000L, backoffMs)
        val target = 2_000_000L + backoffMs
        assertEquals(
            "Nach Reset zeigt der Countdown wieder die kurze Rest-Sekunde",
            1L, RetryCountdown.remainingSeconds(target, 2_000_000L),
        )
    }

    /**
     * Stabile Session (≥ 60 s) füllt das Budget — auch danach startet der
     * Countdown wieder an der Basis (Reset-Weg über [ReconnectPolicy].
     * onSessionEnded).
     */
    @Test
    fun `stabile session laesst das naechste fenster wieder klein starten`() {
        val policy = ReconnectPolicy(maxRetries = 5, maxBackoffMs = 30_000L, stableSessionMs = 60_000L)
        repeat(5) { policy.nextBackoffMs() }
        policy.onSessionEnded(90_000L)  // stabil → Budget frisch

        val backoffMs = policy.nextBackoffMs()
        assertEquals(1_000L, backoffMs)
        assertEquals(1L, RetryCountdown.remainingSeconds(3_000_000L + backoffMs, 3_000_000L))
    }

    // ── UI-Textformat (Banner-Countdown) ──────────────────────────────────

    @Test
    fun `dauerformat zeigt sekunden unter 60 — neuversuch-in-5-s-anteil`() {
        assertEquals("5 s", RetryCountdown.formatDuration(5L))
        assertEquals("42 s", RetryCountdown.formatDuration(42L))
        assertEquals("59 s", RetryCountdown.formatDuration(59L))
    }

    @Test
    fun `dauerformat zeigt minuten-und-sekunden ab 60 — brief-beispiel`() {
        assertEquals("1 Min 30 s", RetryCountdown.formatDuration(90L))
        assertEquals("1 Min 40 s", RetryCountdown.formatDuration(100L))
        assertEquals("5 Min 10 s", RetryCountdown.formatDuration(310L))  // 429-Blockfenster 310 s
    }

    @Test
    fun `dauerformat zeigt glatte minute ohne rest-anteil — low-rate-5-min-modus`() {
        assertEquals("5 Min", RetryCountdown.formatDuration(300L))
        assertEquals("1 Min", RetryCountdown.formatDuration(60L))
    }

    @Test
    fun `dauerformat ist nie negativ und vertraegt 0`() {
        assertEquals("0 s", RetryCountdown.formatDuration(0L))
        assertEquals("0 s", RetryCountdown.formatDuration(-3L))
    }

    @Test
    fun `dauerformat nimmt lokalisierte einheiten — parity je locale`() {
        assertEquals("1 min 30 s", RetryCountdown.formatDuration(90L, secUnit = "s", minUnit = "min"))
        assertEquals("42 Sek", RetryCountdown.formatDuration(42L, secUnit = "Sek"))
        assertEquals("5 Minuten", RetryCountdown.formatDuration(300L, secUnit = "s", minUnit = "Minuten"))
    }
}
