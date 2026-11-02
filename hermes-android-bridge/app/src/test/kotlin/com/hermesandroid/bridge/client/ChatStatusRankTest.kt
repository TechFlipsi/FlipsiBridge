package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}