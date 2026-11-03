package com.hermesandroid.bridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JÄGER-J4 (JAEGE-RFULL-20261102): /screen_record lieferte das Video als EIN
 * Base64-JSON-Frame — ab ~6 s Aufnahme (2 Mbit/s) überstieg das Result die
 * 2-MB-Framengrenze des Relays (max_msg_size), die Phone-WS riss mit 1009.
 *
 * Vertrag: frameBudgetBitRate(dauer) hält bytes ≈ bitrate · dauer / 8 für
 * JEDE erlaubte Dauer unter MAX_VIDEO_BYTES (1,3 MB → Base64 ≈ 1,73 MB +
 * JSON-Hülle < 2 MB); Bitrate bleibt in [300 kbit/s, 2 Mbit/s].
 */
class ScreenRecorderFrameBudgetTest {

    private val budgetBytes = 1_300_000L

    @Test
    fun shortRecordingsStayAtEncoderMaximum() {
        // Default 5 s und kürzer: Rohwert läge über 2 Mbit/s → Clamp nach oben.
        assertEquals(2_000_000, ScreenRecorder.frameBudgetBitRate(5_000L))
        assertEquals(2_000_000, ScreenRecorder.frameBudgetBitRate(200L))
    }

    @Test
    fun thirtySecondsFitTheFrameBudget() {
        // Der Fund: 30 s × 2 Mbit/s ≈ 7,5 MB Video → Base64 ~10 MB ≫ 2 MB.
        // Neu: Bitrate wird aufs Budget geklemmt (346_666 bit/s ≈ 1,3 MB).
        val rate = ScreenRecorder.frameBudgetBitRate(30_000L)
        assertEquals(346_666, rate)
        // PC-GATE-FUND (Fix-Welle 2, vorbelastet aus J4/76b105c — Test lief nie
        // auf einer JVM): Hier stand rate * 30_000L / 8L — ms statt Sekunden,
        // 1000× überschätzt. bytes = bit/s · s / 8 (Parität zur Schleifen-Prüfung).
        assertTrue(rate * 30_000L / 1000L / 8L <= budgetBytes)
    }

    @Test
    fun everyAllowedDurationStaysUnderTheBudget() {
        for (seconds in 1L..30L) {
            val durationMs = seconds * 1_000L
            val rate = ScreenRecorder.frameBudgetBitRate(durationMs)
            assertTrue("Bitrate ≥ 300k", rate >= 300_000)
            assertTrue("Bitrate ≤ 2M", rate <= 2_000_000)
            assertTrue(
                "bytes für ${seconds}s ($rate bit/s) übersteigen das Frame-Budget",
                rate * seconds / 8L <= budgetBytes,
            )
        }
    }
}