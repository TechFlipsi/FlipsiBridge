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

    // ── HAKEN-FIX (17.11.2026): Monotonie + Eviction + Side-Car-Faltordnung ──

    @Test
    fun fusion_fehlende_quelle_senkt_nicht_nie() {
        // >500 msg_ids haben die Live-Zuordnung evictet (null) — der Chip
        // darf trotzdem NICHT auf '✓ Gesendet' unter den höchsten Stand kippen.
        assertEquals("read", ChatStatusRank.highestOf(listOf(null, "read", null)))
        assertEquals("delivered", ChatStatusRank.highestOf(listOf(null, null, "delivered")))
        assertEquals("Maximum über alle Quellen gewinnt",
            "read", ChatStatusRank.highestOf(listOf(null, "read", "delivered")))
        assertEquals("keine Quelle = kein Chip",
            null, ChatStatusRank.highestOf(listOf<String?>(null, null, null)))
    }

    @Test
    fun fusion_spool_replay_nach_neustart_kippt_nicht_zurueck() {
        // Platte (Side-Car) weiß read; nach dem Neustart tauscht ein
        // Spool-Re-Flush nur delivered — der Chip bleibt read (Monotonie
        // statt Fallback-Kette).
        assertEquals("read", ChatStatusRank.fuse("delivered", "read"))
        assertEquals("symmetrisch, gleiche Regeln",
            "read", ChatStatusRank.fuse("read", "delivered"))
        assertEquals("queued nach read senkt nie", "read", ChatStatusRank.fuse("read", "queued"))
        assertEquals("duplite gleicher Stand bleibt", "delivered", ChatStatusRank.fuse("delivered", "delivered"))
    }

    @Test
    fun fusion_failed_schranke_bis_read_selbstheilung() {
        // Parität zu shouldApply/sweep: failed (Session-Reset-Sweep) blockt
        // queued/delivered, verliert aber gegen ein reales read.
        assertEquals("failed", ChatStatusRank.fuse("delivered", "failed"))
        assertEquals("failed", ChatStatusRank.fuse("failed", "delivered"))
        assertEquals("read heilt", "read", ChatStatusRank.fuse("failed", "read"))
        assertEquals("read heilt (Reihenfolge egal)", "read", ChatStatusRank.fuse("read", "failed"))
        assertEquals("failed aus leerem Stand bleibt Schranke",
            "failed", ChatStatusRank.fuse(null, "failed"))
        assertEquals("read ohne Partner gewinnt", "read", ChatStatusRank.fuse(null, "read"))
    }

    @Test
    fun fusion_unbekannter_status_gewinnt_nie() {
        // Parität zu shouldApply (rank -1): Fremdwerte wären Chip-Lügen.
        assertEquals("delivered", ChatStatusRank.fuse("delivered", "seen"))
        assertEquals("read", ChatStatusRank.fuse("seen", "read"))
        // Allein (keine bekannte Quelle) bleibt er stumm sichtbar, gewinnt
        // aber nie gegen einen bekannten Stand:
        assertEquals("fremd", ChatStatusRank.highestOf(listOf<String?>(null, "fremd", null)))
        assertEquals("delivered", ChatStatusRank.highestOf(listOf("delivered", "fremd", null)))
    }

    @Test
    fun eviction_rang_priorisiert_queued_zuerst_read_zuletzt() {
        assertEquals(0, ChatStatusRank.ejectRank("queued"))
        assertEquals(1, ChatStatusRank.ejectRank("delivered"))
        assertEquals(2, ChatStatusRank.ejectRank("failed"))
        assertEquals(3, ChatStatusRank.ejectRank("read"))
    }

    @Test
    fun eviction_ueber_500_kippt_chips_nicht() {
        // (>500 → Chip bleibt): 501 Live-Einträge; der Cap-Evict holt den
        // NIEDRIGSTEN Rang zuerst (Insertion-Order-Tie-Break paritätisch zum
        // alten Verhalten). Der read-Endstand überlebt den Schnitt — und
        // selbst nach späterer EVICTION der Bindung hält die Fusion den Chip
        // via High-Water/Platte.
        val entries = LinkedHashMap<String, String>()
        repeat(499) { entries["msg-$it"] = "queued" }
        entries["victim-delivered"] = "delivered"
        entries["msg-kept"] = "read"
        assertEquals(501, entries.size)
        val victims = ChatStatusRank.evictionVictims(entries)
        assertEquals("niedrigster Rang zuerst", "msg-0", victims.first())
        assertEquals("read wird ZULETZT evictet", "msg-kept", victims.last())
        val trimmed = LinkedHashMap(entries)
        while (trimmed.size > 500) {
            trimmed.remove(ChatStatusRank.evictionVictims(trimmed).firstOrNull() ?: break)
        }
        assertEquals("queued flog zuerst", false, "msg-0" in trimmed)
        assertEquals("delivered überlebt den ersten Schnitt",
            true, "victim-delivered" in trimmed)
        assertEquals("read überlebt IMMER als letzter Kandidat",
            "read", trimmed["msg-kept"])
        // Flutwelle: +120 neue queued-Evictions — read/delivered halten.
        repeat(120) { i -> trimmed["flood-$i"] = "queued" }
        while (trimmed.size > 500) {
            trimmed.remove(ChatStatusRank.evictionVictims(trimmed).firstOrNull() ?: break)
        }
        assertEquals("delivered nach Flutwelle noch da", "delivered", trimmed["victim-delivered"])
        assertEquals("read nach Flutwelle noch da", "read", trimmed["msg-kept"])
        // UND selbst wenn die Bindung später ganz evictet: der Chip fällt
        // über (restored|High-Water) NICHT zurück:
        assertEquals("read", ChatStatusRank.highestOf(listOf(null, null, "read")))
    }

    @Test
    fun sidecar_faltordnung_chronologisch_trotzdem_monoton() {
        // Side-Car-Reihenfolge = Schreibreihenfolge: frames + Sweep-Diffs +
        // Spool-Replays. Die Faltung hält je msg_id den HÖCHSTEN Stand
        // (Parität shouldApply; read heilt, failed blockt).
        val folded = ChatStatusRank.foldRows(
            listOf(
                "m1" to "queued",
                "m1" to "delivered",
                "m1" to "read",          // Endstand
                "m1" to "delivered",     // Spool-Replay senkt NICHT
                "m2" to "delivered",
                "m2" to "failed",        // Session-Reset-Sweep (Schranke)
                "m2" to "delivered",     // Spool-Replay senkt NICHT unter Schranke
                "m3" to "failed",
                "m3" to "read",          // echtes read heilt
            ),
        )
        assertEquals(
            mapOf(
                "m1" to "read",
                "m2" to "failed",
                "m3" to "read",
            ),
            folded,
        )
        // Duplikat-Zeilen (CAS-Retries beim Write-Back) sind idempotent:
        val dup = ChatStatusRank.foldRows(listOf("m4" to "read", "m4" to "read"))
        assertEquals(mapOf("m4" to "read"), dup)
    }

    @Test
    fun isKnown_filtert_fremdwerte() {
        assertTrue(ChatStatusRank.isKnown("queued"))
        assertTrue(ChatStatusRank.isKnown("delivered"))
        assertTrue(ChatStatusRank.isKnown("read"))
        assertTrue(ChatStatusRank.isKnown("failed"))
        assertFalse("fremde Werte kommen nie in die Faltung", ChatStatusRank.isKnown("seen"))
        assertFalse(ChatStatusRank.isKnown(""))
    }
}