package com.hermesandroid.bridge.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * W22 A2 (P5, Call-Verlauf im Chat): Store-Verträge der Call-Einträge
 * (kind=call über Entry.callInfo):
 *
 * - Persist-Roundtrip: Anruf-Bubble (Dauer) UND Verpasst-Zeile überleben den
 *   Store-Restart wie Text-Bubbles; text bleibt LEER (Anzeige baut die UI je
 *   Locale — kein eingefrorenes Literal).
 * - Alt-Zeilen (ohne callInfo) parsen weiter als normale Bubbles.
 * - Löschverband: clearAgent räumt Call-Einträge MIT („Chat löschen“ deckt
 *   die Anruf-Verlaufszeilen ein, Sir-Entscheidung aus dem Plan).
 * - FIFO-Cap zählt Call-Einträge wie jede Zeile.
 * - Backfill-Dedupe: zwei Anrufe derselben Minute (leerer Text!) sind KEINE
 *   Zwillinge — der Schlüssel faltet die Call-Felder ein.
 */
class ChatHistoryStoreCallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun textEntry(
        seq: Long,
        agentId: String = "alpha",
        fromAgent: Boolean = false,
        text: String = "Hallo Agent",
        time: String = "12:00",
    ) = ChatHistoryStore.Entry(seq, agentId, "chat-abc123", fromAgent, text, time)

    private fun callEntry(
        seq: Long,
        agentId: String = "alpha",
        missed: Boolean = false,
        durationSec: Long = 161L,
        time: String = "12:01",
    ) = ChatHistoryStore.Entry(
        seq = seq,
        agentId = agentId,
        chatId = "chat-abc123",
        fromAgent = false,
        text = "",
        time = time,
        callInfo = ChatHistoryStore.CallInfo(
            direction = "incoming",
            missed = missed,
            durationSec = durationSec,
        ),
    )

    @Test
    fun callEintragRoundtripUeberlebtStoreRestart() {
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.append(root, textEntry(1_000L))
        ChatHistoryStore.append(root, callEntry(2_000L, missed = false, durationSec = 161L))
        ChatHistoryStore.append(root, callEntry(3_000L, missed = true, durationSec = 0L, time = "12:02"))

        val restored = ChatHistoryStore.loadAll(root)
        assertEquals(3, restored.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), restored.map { it.seq })
        // Beendeter Anruf: Bubble-Felder, text bleibt leer.
        val ended = restored[1]
        assertEquals("", ended.text)
        assertEquals("incoming", ended.callInfo?.direction)
        assertEquals(false, ended.callInfo?.missed)
        assertEquals(161L, ended.callInfo?.durationSec)
        // Verpasster Anruf: Systemzeilen-Felder.
        val missed = restored[2]
        assertEquals("", missed.text)
        assertEquals(true, missed.callInfo?.missed)
        assertEquals(0L, missed.callInfo?.durationSec)
    }

    @Test
    fun altZeilenOhneCallInfoParsenWeiterAlsNormaleBubble() {
        val root = tmp.newFolder("chat_history")
        // Alt-Zeile per Hand (Vorgänger-Version, kein callInfo-Feld).
        File(root, "alpha_c0ffee000001.jsonl").writeText(
            "{\"seq\":100,\"agentId\":\"alpha\",\"chatId\":\"chat-abc123\"," +
                "\"fromAgent\":true,\"text\":\"Antwort (KI)\",\"time\":\"07:52\"}\n",
        )
        ChatHistoryStore.append(root, callEntry(200L))
        val restored = ChatHistoryStore.loadAll(root)
        assertEquals(2, restored.size)
        val altBubble = restored.first()
        assertNull(altBubble.callInfo)
        assertEquals("Antwort (KI)", altBubble.text)
        assertEquals("incoming", restored[1].callInfo?.direction)
    }

    @Test
    fun clearAgentRaeumtCallEintraegeMit() {
        val root = tmp.newFolder("chat_history")
        ChatHistoryStore.append(root, callEntry(1_000L, agentId = "alpha"))
        ChatHistoryStore.append(root, callEntry(2_000L, agentId = "beta", time = "12:05"))
        ChatHistoryStore.append(root, textEntry(3_000L, agentId = "beta", fromAgent = true,
            text = "Antwort (KI)", time = "12:06"))

        ChatHistoryStore.clearAgent(root, "alpha")

        // Alpha: weg (Call-Eintrag UND Datei), Beta: unberührt.
        assertTrue(ChatHistoryStore.load(root, "alpha").isEmpty())
        val beta = ChatHistoryStore.load(root, "beta")
        assertEquals(listOf(2_000L, 3_000L), beta.map { it.seq })
        assertEquals("incoming", beta[0].callInfo?.direction)
        assertEquals("Antwort (KI)", beta[1].text)
    }

    @Test
    fun fifoCapZaehltCallEintraegeWieJedeZeile() {
        val root = tmp.newFolder("chat_history")
        // 2 Cap-Überläufer: älteste fliegen zuerst raus — Text UND Call.
        // (Kürzung zieht im LOAD-Vertrag, loadAll liest bewusst ungekappt.)
        repeat(ChatHistoryStore.MAX_PER_AGENT + 2) { i ->
            ChatHistoryStore.append(
                root,
                if (i % 2 == 0) callEntry(seq = i.toLong() + 1L)
                else textEntry(seq = i.toLong() + 1L),
            )
        }
        val restored = ChatHistoryStore.load(root, "alpha")
        assertEquals(ChatHistoryStore.MAX_PER_AGENT, restored.size)
        // Die ältesten 2 (seq 1, 2) sind weg, die jüngsten überleben.
        assertEquals(
            3L,
            restored.first().seq,
        )
        assertTrue(restored.any { it.callInfo != null })
    }

    @Test
    fun backfillDedupeZweiAnrufeGleicherMinuteSindKeineZwillinge() {
        val root = tmp.newFolder("chat_history")
        // Bestand: verpasster Anruf 12:01 (Dauer 0) — leerer Text.
        ChatHistoryStore.append(root, callEntry(1_000L, missed = true, durationSec = 0L, time = "12:01"))
        // Backfill-Batch: BEENDETER Anruf derselben Minute (Dauer 161) — ohne
        // Call-Feld-Dedupe wäre der Schlüssel identisch ("" + "12:01").
        val fresh = ChatHistoryStore.mergeBackfill(
            root, "alpha",
            listOf(callEntry(1_500L, missed = false, durationSec = 161L, time = "12:01")),
        )
        assertEquals(1, fresh)
        // Exakt derselbe Call im Batch = Dublette → 0 neu.
        val dup = ChatHistoryStore.mergeBackfill(
            root, "alpha",
            listOf(callEntry(1_000L, missed = true, durationSec = 0L, time = "12:01")),
        )
        assertEquals(0, dup)
        val restored = ChatHistoryStore.loadAll(root)
        assertEquals(2, restored.size)
        assertTrue(restored[0].callInfo?.missed == true)
        assertTrue(restored[1].callInfo?.missed == false)
        assertEquals(161L, restored[1].callInfo?.durationSec)
    }
}
