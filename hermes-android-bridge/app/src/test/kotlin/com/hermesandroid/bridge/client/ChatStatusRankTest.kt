package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH-3 F4 (Jäger-P2-Fix): Rang-Dedup der Chat-Status — RelayClient.
 * handleChatStatusFrame verwirrt eingehende Frames mit Rang ≤ existierendem
 * (kein Chip-Downgrade: ein verspätetes 'delivered' klebt NIE einen 'read'-
 * Chip zurück). Diese Test-Einheit hält die Relay-Spiegelung fest.
 */
class ChatStatusRankTest {

    @Test
    fun rangfolge_queued_lieferd_read() {
        assertEquals(0, ChatStatusRank.rank("queued"))
        assertEquals(1, ChatStatusRank.rank("delivered"))
        assertEquals(2, ChatStatusRank.rank("read"))
        assertEquals("unbekannter Status = -1 (nie anwendbar)", -1, ChatStatusRank.rank("seen"))
    }

    @Test
    fun aufstieg_gewinnt_duplikat_und_downgrade_verworfen() {
        assertTrue("kein Eintrag = anwendbar", ChatStatusRank.shouldApply(null, "queued"))
        assertTrue("Aufstieg queued → delivered", ChatStatusRank.shouldApply("queued", "delivered"))
        assertTrue("Aufstieg delivered → read", ChatStatusRank.shouldApply("delivered", "read"))
        assertTrue("Fernsprung queued → read", ChatStatusRank.shouldApply("queued", "read"))
        // Der FINEMACHER-Beweis: last-write-wins hätte den read-Chip auf
        // delivered zurückgeklebt (Spool-Umsortierung) — Dedup verwirft das.
        assertFalse("Downgrade read → delivered verworfen", ChatStatusRank.shouldApply("read", "delivered"))
        assertFalse("Downgrade read → queued verworfen", ChatStatusRank.shouldApply("read", "queued"))
        assertFalse("Duplikat delivered → delivered verworfen", ChatStatusRank.shouldApply("delivered", "delivered"))
    }

    // ── FIX-WELLE-2 B8: Chip-Sweep bei Session-Invalidierung ─────────────

    @Test
    fun sweep_fegt_ungelesene_zu_failed_read_bleibt() {
        val swept = ChatStatusRank.sweepUnreadToFailed(
            mapOf(
                "m1" to "queued",
                "m2" to "delivered",
                "m3" to "read",
                "m4" to "failed",
            ),
        )
        assertEquals("queued → failed (kann nie mehr gelesen werden)", "failed", swept["m1"])
        assertEquals("delivered → failed (HERZ-Befund 8: kein Endlos-Zugestellt)", "failed", swept["m2"])
        assertEquals("read bleibt read (wirklich gelesen)", "read", swept["m3"])
        assertEquals("failed bleibt failed", "failed", swept["m4"])
    }

    @Test
    fun sweep_keine_aenderung_gibt_dieselbe_map_instanz_zurueck() {
        val statuses = mapOf("m1" to "read", "m2" to "failed")
        assertSame(
            "Ohne Sweep-Kandidaten keine neue Instanz (kein sinnloses StateFlow-Update)",
            statuses,
            ChatStatusRank.sweepUnreadToFailed(statuses),
        )
    }

    @Test
    fun sweep_und_nachkommende_status_nur_read_gewinnt() {
        // Nachricht auf einem ANDEREN Agenten, fälschlich gefegt: trifft ihr
        // echtes Read später ein, gewinnt es — der Sweep kann nie dauerhaft
        // eine gelesene Nachricht als failed lügen lassen.
        val swept = ChatStatusRank.sweepUnreadToFailed(mapOf("m1" to "delivered"))
        assertEquals("failed", swept["m1"])
        assertTrue("read nach Sweep gewinnt (Selbstheilung)", ChatStatusRank.shouldApply(swept["m1"], "read"))
        // Status-Re-Flush (Spool/Reconnect) klebt den Chip NICHT zurück:
        // queued und delivered nach dem Sweep = verworfen — sonst käme die
        // Endlos-Zustellt-Lüge aus HERZ-Befund 8 durch die Hintertür zurück.
        assertFalse("delivered nach Sweep verliert", ChatStatusRank.shouldApply(swept["m1"], "delivered"))
        assertFalse("queued nach Sweep verliert", ChatStatusRank.shouldApply(swept["m1"], "queued"))
        assertFalse("failed-Duplikat verliert", ChatStatusRank.shouldApply(swept["m1"], "failed"))
    }
}