package com.hermesandroid.bridge.chat

import com.google.gson.Gson
import com.hermesandroid.bridge.client.ChatStatusRank
import com.hermesandroid.bridge.ui.chat.ChatTimelineMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * HAKEN-FIX (17.11.2026): Status-Side-Car + msg_id-Persistenz — der 2-Haken-
 * Bug-Fix. Vorher lebten delivered/read NUR in RelayClient._chatStatus (MAX_
 * CHAT_STATUS = 500, Insertion-Order-Eviction, Prozess-Tod = weg); die Platte
 * trug nur den Sendezeit-Snapshot (null = 1 Haken). Dieser Test pinnt:
 *
 * - Neustart-Restore-Chips: delivered/read aus dem Side-Car wiederhergestellt
 *   (Platte = höchster bekannter Stand, NICHT der Sendezeit-Snapshot).
 * - Sweep-Honesty auf der Platte: failed (Session-Reset) bleibt Schranke,
 *   ein echtes read heilt (Parität shouldApply).
 * - Cap: der Side-Car wächst nie ungebunden (Trim der NEUESTEN behalten).
 * - Korrupte Zeilen/Fremd-Werte: NIE in die Faltung (nie Crash).
 * - msg_id-Roundtrip im Entry (+ Legacy-Zeilen ohne msg_id parsen weiter).
 * - Restore-Race-Merge: Zwillinge droppen die Live-Einheit per dedupeKey,
 *   Bindungen (live ⊕ restored) übernehmen — id-Räume getrennt.
 */
class ChatStatusPersistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun twin(
        seq: Long,
        msgId: String? = null,
        status: String? = null,
        text: String = "Nachricht $seq",
        time: String = "09:15",
        agentId: String = "alpha",
    ) = ChatHistoryStore.Entry(
        seq = seq, agentId = agentId, chatId = "chat-1", fromAgent = false,
        text = text, time = time, status = status, msgId = msgId,
    )

    private fun sidecar(root: File): File = ChatHistoryStore.statusSidecarFile(root)

    // ── Neustart-Restore-Chips (delivered/read aus dem Side-Car) ──────────

    @Test
    fun neustart_restore_liefert_chips_aus_sidecar() {
        val root = tmp.newFolder("chat_history")
        // Konvo: m1 queued→delivered→read, m2 nur delivered — AUF der Platte
        // (die Entry-Zeilen tragen nur den Sendezeit-Snapshot null).
        ChatHistoryStore.append(root, twin(1_000, msgId = "m1"))
        ChatHistoryStore.append(root, twin(2_000, msgId = "m2"))
        ChatHistoryStore.recordStatus(root, "m1", "queued")
        ChatHistoryStore.recordStatus(root, "m1", "delivered")
        ChatHistoryStore.recordStatus(root, "m2", "delivered")
        ChatHistoryStore.recordStatus(root, "m1", "read")

        // „Neuer Prozess": loadAll + loadStatuses auf demselben Verzeichnis.
        val entries = ChatHistoryStore.loadAll(root)
        assertEquals(listOf("m1", "m2"), entries.map { it.msgId })
        assertEquals("Sendezeit-Snapshot bleibt ehrlich null",
            listOf(null, null), entries.map { it.status })
        val statuses = ChatHistoryStore.loadStatuses(root)
        // Restore-Fusion wie ChatScreen: fuse(Snapshot, Side-Car-Stand) —
        // der HÖCHSTE bekannte Stand überlebt den Neustart, der 1-Haken-Kipp
        // aus dem Bug ist weg (2 grüne Haken für m1).
        val chips = entries.associate { entry ->
            entry.seq to ChatStatusRank.fuse(entry.status, entry.msgId?.let { statuses[it] })
        }
        assertEquals(
            mapOf(1_000L to "read", 2_000L to "delivered"),
            chips,
        )
    }

    @Test
    fun legacy_eintrag_ohne_msg_id_bleibt_snapshot_ehrlich() {
        val root = tmp.newFolder("chat_history")
        // Alt-Bestand vor dem Fix: Snapshot als Fallback (kein Side-Car-Stand).
        ChatHistoryStore.append(root, twin(5L, status = "delivered"))
        val entry = ChatHistoryStore.loadAll(root).single()
        assertEquals(null, entry.msgId)
        assertEquals("delivered", ChatStatusRank.fuse(entry.status, null))
    }

    @Test
    fun snapshot_und_sidecar_falten_failed_liess_read_heilen() {
        val root = tmp.newFolder("chat_history")
        // Timeout-Race: lokaler Persist als failed, Broker meldet später read
        // (Side-Car). Fusion: read gewinnt (Parität shouldApply) — kein
        // endloses rotes ✕, keine grüne Lüge vorher (failed blockt).
        ChatHistoryStore.recordStatus(root, "m9", "read")
        ChatHistoryStore.append(root, twin(7L, msgId = "m9", status = "failed"))
        val statuses = ChatHistoryStore.loadStatuses(root)
        assertEquals("read heilt das lokale failed",
            "read", ChatStatusRank.fuse("failed", statuses["m9"]))
    }

    // ── Sweep-Honesty auf der Platte ──────────────────────────────────────

    @Test
    fun sweep_schranke_ueberlebt_neustart_und_read_heilt_auf_platte() {
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.recordStatus(root, "m3", "delivered")
        ChatHistoryStore.recordStatus(root, "m3", "failed")  // Session-Reset-Diff
        assertEquals("failed-Schranke gefaltet",
            "failed", ChatHistoryStore.loadStatuses(root)["m3"])
        // Spool-Replay nach dem Sweep senkt die Schranke NICHT:
        ChatHistoryStore.recordStatus(root, "m3", "delivered")
        assertEquals("failed", ChatHistoryStore.loadStatuses(root)["m3"])
        // Echtes read später — Selbstheilung (live UND auf der Platte):
        ChatHistoryStore.recordStatus(root, "m3", "read")
        assertEquals("read", ChatHistoryStore.loadStatuses(root)["m3"])
    }

    // ── Cap + Korruption ───────────────────────────────────────────────────

    @Test
    fun sidecar_cap_behaelt_neueste_zustaende() {
        val root = tmp.newFolder("chat_history")
        repeat(3_500) { i -> ChatHistoryStore.recordStatus(root, "m-$i", "delivered") }
        assertTrue(
            "Side-Car-Datei bleibt unter der Byte-Obergrenze (ist ${sidecar(root).length()} B)",
            sidecar(root).length() <= ChatHistoryStore.SIDECAR_MAX_BYTES,
        )
        val statuses = ChatHistoryStore.loadStatuses(root)
        // Je msg_id genau EINE gefaltete Zeile = je Datei-Zeile ein Eintrag:
        assertEquals(
            "Faltung = dedupe je Zeile (keine Zeile geht verloren)",
            sidecar(root).readLines().count { it.isNotBlank() },
            statuses.size,
        )
        assertTrue("NEUESTE Stände bleiben", "m-3499" in statuses)
        assertFalse("ÄLTESTE fliegen zuerst", "m-0" in statuses)
        assertEquals("Faltung dedupiziert je msg_id", "delivered", statuses["m-3499"])
    }

    @Test
    fun korrupte_zeilen_und_fremdwerte_kosten_nur_sich_selbst() {
        val root = tmp.newFolder("chat_history")
        sidecar(root).parentFile?.mkdirs()
        sidecar(root).writeText(
            "{\"msgId\":\"m1\",\"status\":\"read\"}\n" +          // gültig
                "definitiv kein json\n" +                          // korrupt
                "{\"msgId\":\"m2\",\"status\":\"gelesen\"}\n" +    // fremder Wert
                "{\"msgId\":\"\",\"status\":\"read\"}\n",          // leere msg_id
        )
        assertEquals("nur der gültige Stand ist in der Faltung",
            mapOf("m1" to "read"), ChatHistoryStore.loadStatuses(root))
        // Fremdwert wird NICHT nachgeschrieben:
        ChatHistoryStore.recordStatus(root, "m2", "gelesen")
        assertEquals("unverändert", mapOf("m1" to "read"), ChatHistoryStore.loadStatuses(root))
    }

    // ── Platte-Trennung + msg_id-Roundtrip ────────────────────────────────

    @Test
    fun load_all_parses_sidecar_nie_als_verlauf() {
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.recordStatus(root, "m1", "read")
        assertTrue("Side-Car-Rows sind keine Verlaufs-Entries",
            ChatHistoryStore.loadAll(root).isEmpty())
        ChatHistoryStore.append(root, twin(1L, msgId = "m1"))
        val entries = ChatHistoryStore.loadAll(root)
        assertEquals(listOf(1L), entries.map { it.seq })
        assertEquals("m1", entries.single().msgId)
        assertTrue("Side-Car-Datei trägt loadAll-Filter aus (.sidecar ≠ .jsonl)",
            sidecar(root).name.endsWith(".sidecar"))
    }

    @Test
    fun legacy_zeile_ohne_msg_id_feld_parst_weiter() {
        val root = tmp.newFolder("chat_history")
        // Manuell eine Alt-Zeile OHNE msgId-Feld in die Verlauf-Datei (vor dem
        // Fix geschrieben) + eine Neu-Zeile mit msgId — beide laden sauber.
        val legacyJson = Gson().toJson(
            ChatHistoryStore.Entry(1L, "alpha", "chat-1", false, "Alt", "10:00", null),
        )
        File(
            root,
            ChatHistoryStore.fileFor(root, "alpha").name,
        ).writeText(legacyJson + "\n")
        ChatHistoryStore.append(root, twin(2L, msgId = "m2"))
        val entries = ChatHistoryStore.loadAll(root)
        assertEquals(listOf(1L, 2L), entries.map { it.seq })
        assertEquals("Alt-Zeile = msgId null (gson fehlendes Feld)", null, entries[0].msgId)
        assertEquals("m2", entries[1].msgId)
    }

    // ── Restore-Race-Merge ─────────────────────────────────────────────────

    @Test
    fun restore_race_merge_behaelt_live_bubbles_und_bindet_zwillinge() {
        // ChatScreen-Verband (pure Regeln): der Disk-Stand bildet dedupeKeys;
        // Live-Bubbles MIT Zwilling fliegen, OHNE Zwilling bleiben; die
        // msg_id-Bindungen kommen aus ZWEI getrennten id-Räumen und fusionieren
        // verlustfrei (live: kleine ids, restored: RESTORED_ID_BASE + n).
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.append(root, twin(1_000, msgId = "m1", text = "Frage mit Antwort-Verweis"))
        val restored = ChatHistoryStore.loadAll(root)
        val restoredKeys = restored.map {
            ChatTimelineMerge.dedupeKey(
                it.agentId.hashCode(), it.fromAgent, it.text, it.time,
            )
        }.toSet()
        // Live-Bubble mit gleichem Draht/Ts wie der Zwilling:
        val liveTwinKey = ChatTimelineMerge.dedupeKey("alpha".hashCode(), false, "Frage mit Antwort-Verweis", "09:15")
        // Live-Bubble, deren Persist noch läuft (kein Zwilling auf der Platte):
        val liveOnlyKey = ChatTimelineMerge.dedupeKey("alpha".hashCode(), false, "Noch nicht persistiert", "09:16")
        assertTrue("Zwilling gewinnt: live-Einheit wird dedupliziert (keine Dublette)", liveTwinKey in restoredKeys)
        assertFalse("noch nicht persistierte Sendung fliegt NICHT aus der Anzeige", liveOnlyKey in restoredKeys)
        val keptLive = listOf(liveTwinKey, liveOnlyKey).filter { it !in restoredKeys }
        assertEquals(listOf(liveOnlyKey), keptLive)

        // Bindungs-Merge: live-Bindung (im Restore-Race vor dem Merge) +
        // restored-Bindungen (Disk-Zwilling) fusionieren — verlustfrei.
        val liveBindings = mapOf(1L to "m1")
        val restoredBindings = mapOf(2_000_000_000L to "m1")
        val merged: Map<Long, String> = liveBindings + restoredBindings
        assertEquals(2, merged.size)
        assertTrue(
            "id-Räume getrennt: keine Kollision im msgIdByLocalId-Verband",
            merged.keys.contains(1L) && merged.keys.contains(2_000_000_000L),
        )
        assertEquals("beide Bindungen zeigen auf dieselbe msg_id-Kette", "m1", merged[2_000_000_000L])
    }
}
