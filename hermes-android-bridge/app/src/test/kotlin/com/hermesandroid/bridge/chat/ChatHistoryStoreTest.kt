package com.hermesandroid.bridge.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SIRS-02 (P2, Volltest 03.11.2026): Persist-Roundtrip des Chat-Verlaufs —
 * „Konvo vor Neustart gefüllt → nach force-stop ‚Noch keine Nachrichten’“
 * war der Fund; dieser Test pinnt den Vertrag des Disk-Stores:
 *
 * - Roundtrip: append → loadAll liefert dieselben Klartext-Bubbles (je
 *   agent+chat_id, Reihenfolge, status-Snapshot) — der Verlauf überlebt den
 *   App-Restart (Store-Instanz = „neuer Prozess“, Datei bleibt).
 * - Isolation je Agent (fremde Dateien fliegen nie über).
 * - FIFO-Cap: älteste zuerst raus, die NEUESTEN überleben (MAX_PER_AGENT).
 * - Byte-Obergrenze: die Agent-Datei wächst nie über MAX_FILE_BYTES.
 * - Korrupte Zeilen kosten EINEN Eintrag, nie den ganzen Verlauf.
 */
class ChatHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(
        seq: Long,
        agentId: String = "jarvis",
        chatId: String = "chat-abc123",
        fromAgent: Boolean = true,
        text: String = "Antwort des Agenten",
        time: String = "07:52",
        status: String? = null,
    ) = ChatHistoryStore.Entry(seq, agentId, chatId, fromAgent, text, time, status)

    @Test
    fun persistRoundtripSurvivesStoreRestart() {
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.append(root, entry(1_000L, fromAgent = false,
            text = "Hallo Agent", time = "07:52", status = null))
        ChatHistoryStore.append(root, entry(2_000L,
            text = "Antwort (KI)", time = "07:53"))
        ChatHistoryStore.append(root, entry(3_000L, fromAgent = false,
            text = "Noch eine Frage", time = "07:54", status = "read"))
        ChatHistoryStore.append(root, entry(4_000L, agentId = "edwin",
            chatId = "chat-def456", text = "Andere Session", time = "08:01"))

        // „Neuer Prozess“ = frisches loadAll auf demselben Verzeichnis.
        val restored = ChatHistoryStore.loadAll(root)
        assertEquals(4, restored.size)
        // Cross-Agent chronologisch (seq-Sortierung):
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L),
            restored.map { it.seq })
        val own = restored[0]
        assertEquals("Hallo Agent", own.text)
        assertEquals("jarvis", own.agentId)
        assertEquals("chat-abc123", own.chatId)
        assertEquals(false, own.fromAgent)
        assertEquals("07:52", own.time)
        assertEquals(null, own.status)
        val failed = restored[2]
        assertEquals("read", failed.status)
        val other = restored[3]
        assertEquals("edwin", other.agentId)
        assertEquals("chat-def456", other.chatId)
    }

    @Test
    fun perAgentIsolationAndEmptyHistory() {
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.append(root, entry(1L, agentId = "jarvis"))
        ChatHistoryStore.append(root, entry(2L, agentId = "edwin"))
        assertEquals(listOf(2L), ChatHistoryStore.load(root, "edwin").map { it.seq })
        assertEquals(listOf(1L), ChatHistoryStore.load(root, "jarvis").map { it.seq })
        assertTrue("leerer Agent = leerer Verlauf",
            ChatHistoryStore.load(root, "unbekannt").isEmpty())
    }

    @Test
    fun fifoCapKeepsNewestMessages() {
        val root = tmp.newFolder("chat_history")
        repeat(ChatHistoryStore.MAX_PER_AGENT + 2) { i ->
            ChatHistoryStore.append(root, entry(seq = 100L + i, text = "Nachricht $i"))
        }
        val stored = ChatHistoryStore.load(root, "jarvis")
        assertEquals(ChatHistoryStore.MAX_PER_AGENT, stored.size)
        // Die NEUESTEN überleben, die 2 ältesten fliegen raus:
        assertEquals("Nachricht 2", stored.first().text)
        assertEquals("Nachricht ${ChatHistoryStore.MAX_PER_AGENT + 1}", stored.last().text)
    }

    @Test
    fun byteCapBoundsFileSize() {
        val root = tmp.newFolder("chat_history")
        // Je Eintrag ~16 KiB Text → 40 Einträge ≈ 640 KiB > 512 KiB-Obergrenze.
        val bigText = "x".repeat(16 * 1024)
        repeat(40) { i ->
            ChatHistoryStore.append(root, entry(seq = 1_000L + i, text = bigText))
        }
        val file = ChatHistoryStore.fileFor(root, "jarvis")
        assertTrue(
            "Agent-Datei muss unter der Obergrenze bleiben (ist ${file.length()} B)",
            file.length() <= ChatHistoryStore.MAX_FILE_BYTES,
        )
        val stored = ChatHistoryStore.load(root, "jarvis")
        assertTrue("Obergrenze kürzt, verliert aber NIE alles", stored.isNotEmpty())
        // FIFO gilt auch beim Byte-Kürzen: der NEUESTE Eintrag bleibt erhalten.
        assertEquals(1_000L + 39, stored.last().seq)
    }

    @Test
    fun corruptLineCostsOneEntryNotTheWholeHistory() {
        val root = tmp.newFolder("chat_history")
        val file = ChatHistoryStore.fileFor(root, "jarvis")
        file.writeText(
            gsonJsonOf(entry(1L)) + "\n" +
                "definitiv kein json\n" +
                gsonJsonOf(entry(2L)) + "\n",
        )
        val stored = ChatHistoryStore.load(root, "jarvis")
        assertEquals("korrupte Zeile = 1 Eintrag weg, nie der ganze Verlauf",
            listOf(1L, 2L), stored.map { it.seq })
    }

    @Test
    fun agentIdIsSanitizedIntoOwnFile() {
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.append(root, entry(1L, agentId = "../../evil/agent"))
        val files = root.listFiles()!!.map { it.name }
        assertEquals(1, files.size)
        assertTrue("kein Pfad-Escape: Datei bleibt im Store-Verzeichnis",
            files[0].endsWith(".jsonl") && !files[0].contains('/'))
        assertEquals(1, ChatHistoryStore.load(root, "../../evil/agent").size)
    }

    private fun gsonJsonOf(entry: ChatHistoryStore.Entry): String =
        com.google.gson.Gson().toJson(entry)
}
