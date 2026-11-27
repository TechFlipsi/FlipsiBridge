package com.hermesandroid.bridge.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * B5/B6 (Multi-Gerät): ChatHistoryStore-Merge-Verband —
 * mergeBackfill sortiert chat.backfill.enc-Batches CHRONOLOGISCH in die Platte
 * (ÄLTERE Zeilen VOR den bestandswahrenden Zeilen), msg_id-Idempotenz (Kap.
 * 2.3 #1: msg_id ist der globale Einheitsschlüssel), Caps unverändert
 * (500 Zeilen / 512 KiB), korrupte Zeilen brechen nie den Verlauf.
 */
class ChatHistoryStoreMergeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(
        seq: Long,
        text: String,
        fromAgent: Boolean = false,
        msgId: String? = null,
        agentId: String = "agent-a",
    ) = ChatHistoryStore.Entry(
        seq = seq,
        agentId = agentId,
        chatId = "chat-1",
        fromAgent = fromAgent,
        text = text,
        time = "",
        status = null,
        msgId = msgId,
    )

    @Test
    fun merge_plattet_chronologisch_vor_den_bestand() {
        val root = tmp.newFolder()
        // Bestand: EIN neuerer Post des Nutzers (heutiges Senden).
        ChatHistoryStore.append(root, entry(1_100L, "heutige Frage"))
        val fresh = ChatHistoryStore.mergeBackfill(
            root, "agent-a",
            listOf(
                entry(300L, "alte Antwort", fromAgent = true, msgId = "m-2"),
                entry(500L, "alte Frage", msgId = "m-1"),
            ),
        )
        assertEquals("beide Backfill-Zeilen sind neu", 2, fresh)
        val loaded = ChatHistoryStore.load(root, "agent-a")
        assertEquals(listOf(300L, 500L, 1_100L), loaded.map { it.seq })
        assertEquals("Älteres vor Bestand — Verlauf bleibt chronologisch", "alte Antwort", loaded.first().text)
        assertTrue(loaded.first().fromAgent)
        assertEquals("m-2", loaded.first().msgId)
        // msgId-bindbare Zeit wird neu gebaut (HH:mm) — nie leer.
        assertTrue(loaded.none { it.time.isBlank() })
    }

    @Test
    fun merge_dedupet_msgid_in_beiden_richtungen() {
        val root = tmp.newFolder()
        ChatHistoryStore.append(root, entry(1_000L, "Bestand", msgId = "m-keep"))
        // Gleiches msg_id im Batch → Platte gewinnt (0 neue Zeilen).
        assertEquals(0, ChatHistoryStore.mergeBackfill(
            root, "agent-a", listOf(entry(50L, "dublette", msgId = "m-keep")),
        ))
        // Gemischter Batch: neu (m-new) + Dublette (m-keep) → nur EINE neue.
        assertEquals(1, ChatHistoryStore.mergeBackfill(
            root, "agent-a",
            listOf(
                entry(50L, "dublette", msgId = "m-keep"),
                entry(60L, "neu", msgId = "m-new"),
            ),
        ))
        val loaded = ChatHistoryStore.load(root, "agent-a")
        assertEquals(2, loaded.size)
        assertEquals(listOf(60L, 1_000L), loaded.map { it.seq })
        // Re-Merge desselben Batches (Reconnect spoolt denselben Verlauf) = 0 neu.
        assertEquals(0, ChatHistoryStore.mergeBackfill(
            root, "agent-a",
            listOf(
                entry(50L, "dublette", msgId = "m-keep"),
                entry(60L, "neu", msgId = "m-new"),
            ),
        ))
    }

    @Test
    fun merge_msgid_lose_zeilen_dedupen_ueber_den_schluessel() {
        val root = tmp.newFolder()
        ChatHistoryStore.append(root, entry(1_000L, "ohne msg_id"))
        // Gleicher Text + gleiche seq (→ gleiche HH:mm) + gleiche Richtung =
        // derselbe Beitrag → skip (Text-Variante bleibt eine NEUE Zeile).
        assertEquals(0, ChatHistoryStore.mergeBackfill(root, "agent-a", listOf(entry(1_000L, "ohne msg_id"))))
        assertEquals(1, ChatHistoryStore.mergeBackfill(root, "agent-a", listOf(entry(1_000L, "anders", fromAgent = true))))
        // Richtung trennt zusätzlich (fromAgent-Flag im Schlüssel).
        assertEquals(1, ChatHistoryStore.mergeBackfill(root, "agent-a", listOf(entry(1_000L, "ohne msg_id", fromAgent = true))))
        assertEquals(3, ChatHistoryStore.load(root, "agent-a").size)
    }

    @Test
    fun merge_respektiert_die_caps_500_und_512KiB() {
        val root = tmp.newFolder()
        val incoming = (0 until 600).map { entry(it.toLong(), "zeile-$it", msgId = "m-$it") }
        val fresh = ChatHistoryStore.mergeBackfill(root, "agent-a", incoming)
        assertEquals(600, fresh)
        val loaded = ChatHistoryStore.load(root, "agent-a")
        assertEquals("FIFO-Cap 500 bleibt harte Invariante", 500, loaded.size)
        assertEquals("die AELTESTEN flogen (älteste zuerst)", "zeile-100", loaded.first().text)
        // Re-Merge desselben 600er-Batches: die evicteten 100 ältesten Zeilen
        // sind aus der Platte-Wahrheit NEU (Cap-Schnitt) — ehrlich einsortiert,
        // der Cap schneidet wieder den Kopf (nie über die 500 hinaus).
        assertEquals(100, ChatHistoryStore.mergeBackfill(root, "agent-a", incoming))
        assertEquals(500, ChatHistoryStore.load(root, "agent-a").size)
        assertEquals("zeile-100", ChatHistoryStore.load(root, "agent-a").first().text)
    }

    @Test
    fun hasHistory_unterscheidet_leer_von_bestand() {
        val root = tmp.newFolder()
        assertFalse("frisches Gerät = kein Bestand", ChatHistoryStore.hasHistory(root, "agent-a"))
        ChatHistoryStore.append(root, entry(1L, "etwas"))
        assertTrue(ChatHistoryStore.hasHistory(root, "agent-a"))
        assertFalse("fremder Agent hat weiterhin keinen", ChatHistoryStore.hasHistory(root, "agent-b"))
    }

    @Test
    fun korrupte_zeilen_brechen_merge_und_laden_nie() {
        val root = tmp.newFolder()
        ChatHistoryStore.append(root, entry(1_000L, "Bestand"))
        val file = File(root, ChatHistoryStore.fileFor(root, "agent-a").name)
        file.appendText("{KEIN-JSON}\n")
        assertEquals(1, ChatHistoryStore.mergeBackfill(root, "agent-a", listOf(entry(50L, "neu", msgId = "m-1"))))
        val loaded = ChatHistoryStore.load(root, "agent-a")
        assertEquals(2, loaded.size)
        assertEquals("neu", loaded.first().text)
    }

    @Test
    fun merge_leere_und_fremde_agent_argumente_sind_noops() {
        val root = tmp.newFolder()
        assertEquals(0, ChatHistoryStore.mergeBackfill(root, "agent-a", emptyList()))
        assertEquals(0, ChatHistoryStore.mergeBackfill(root, "", listOf(entry(1L, "x"))))
    }
}
