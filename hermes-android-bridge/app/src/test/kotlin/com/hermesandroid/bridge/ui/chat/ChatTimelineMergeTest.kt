package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH-FIX-CHAT-A (F1, Sortierung): Merge-Vertrag der EINEN Verlaufsliste.
 *
 * Agenten-Antworten werden beim Eintreffen in `messages` übernommen; der
 * Disk-Restore legt dieselben Beiträge als persistierte Zwillinge ab.
 * Der Vertrag: freshUnseen filtert NUR exakte Restore-Dubletten
 * (gleicher Agent, gleicher Text, gleiche Minute) — alles andere
 * (anderer Agent, anderer Text, andere Minute, eigene Nachricht mit
 * gleichem Text) bleibt erhalten.
 */
class ChatTimelineMergeTest {

    private fun msg(id: Long, agentId: Int, fromAgent: Boolean, text: String, time: String) =
        ChatMessage(id, agentId, fromAgent, text, time)

    /** Ein Tupel (wie ein frisches chat.event): (agentIdHash, text, HH:mm). */
    private data class Ev(val agentId: Int, val text: String, val time: String)

    private fun unseen(messages: List<ChatMessage>, fresh: List<Ev>): List<Ev> =
        ChatTimelineMerge.freshUnseen(
            messages,
            fresh,
            agentIdOf = { it.agentId },
            textOf = { it.text },
            timeOf = { it.time },
        )

    // ── Restore-Dublette wird gefiltert ────────────────────────────────────

    @Test
    fun restoreDubletteIsFiltered() {
        // Der Fall aus dem Live-Betrieb: Frame kam vor dem Restore, liegt
        // schon als persistierter Eintrag in der Liste → NICHT doppelt.
        val messages = listOf(msg(2_000_000_000L, 7, true, "Antwort A", "14:02"))
        val fresh = listOf(Ev(7, "Antwort A", "14:02"), Ev(7, "Antwort B", "14:05"))
        val result = unseen(messages, fresh)
        assertEquals(listOf(Ev(7, "Antwort B", "14:05")), result)
    }

    @Test
    fun identicalTextDifferentMinuteIsKept() {
        // Agent antwortet zweimal „Ja" — verschiedene Minute = kein Duplikat.
        val messages = listOf(msg(2_000_000_000L, 7, true, "Ja", "14:02"))
        val fresh = listOf(Ev(7, "Ja", "14:09"))
        assertEquals(1, unseen(messages, fresh).size)
    }

    @Test
    fun identicalTextDifferentAgentIsKept() {
        val messages = listOf(msg(2_000_000_000L, 7, true, "Ja", "14:02"))
        val fresh = listOf(Ev(8, "Ja", "14:02"))
        assertEquals(listOf(Ev(8, "Ja", "14:02")), unseen(messages, fresh))
    }

    @Test
    fun ownMessageWithSameTextDoesNotSwallowAgentEvent() {
        // fromAgent gehört zum Schlüssel: die EIGENE „Ja"-Nachricht (z. B.
        // der FP-Abgleich) darf die Agenten-Antwort „Ja" nie wegfiltern.
        val messages = listOf(msg(1L, 7, false, "Ja", "14:02"))
        val fresh = listOf(Ev(7, "Ja", "14:02"))
        assertEquals(listOf(Ev(7, "Ja", "14:02")), unseen(messages, fresh))
    }

    @Test
    fun emptyHistoryKeepsEverything() {
        val fresh = listOf(Ev(7, "A", "10:00"), Ev(7, "B", "10:01"))
        assertEquals(fresh, unseen(emptyList(), fresh))
    }

    // ── Dedup-Schlüssel: Trennzeichen-Sicherheit ──────────────────────────

    @Test
    fun dedupeKeySeparatesDelimiterAmbiguity() {
        val a = ChatTimelineMerge.dedupeKey(7, true, "ab|c", "10:00")
        val b = ChatTimelineMerge.dedupeKey(7, true, "a|bc", "10:00")
        assertNotEquals("Längen-Präfix trennt „ab|c“ vs „a|bc“", a, b)
    }

    @Test
    fun dedupeKeyOwnAndAgentMessageDiffer() {
        val own = ChatTimelineMerge.dedupeKey(7, false, "Ja", "14:02")
        val agent = ChatTimelineMerge.dedupeKey(7, true, "Ja", "14:02")
        assertNotEquals(own, agent)
    }

    @Test
    fun dedupeKeyIsDeterministic() {
        assertEquals(
            ChatTimelineMerge.dedupeKey(7, true, "Text", "14:02"),
            ChatTimelineMerge.dedupeKey(7, true, "Text", "14:02"),
        )
    }

    // ── J1: Event-Identitäts-Schlüssel (Konsum-Markierung) ────────────────

    @Test
    fun eventKeyDiffersPerTs() {
        // Zwei Nachrichten derselben Session (gleiche chat_id) sind
        // unterschiedliche Schlüssel — sonst fresse die Konsum-Markierung
        // die zweite Nachricht.
        assertNotEquals(
            ChatTimelineMerge.eventKey("chat-abc", 1_000L),
            ChatTimelineMerge.eventKey("chat-abc", 1_500L),
        )
    }

    @Test
    fun eventKeyDiffersPerChatId() {
        assertNotEquals(
            ChatTimelineMerge.eventKey("chat-abc", 1_000L),
            ChatTimelineMerge.eventKey("chat-def", 1_000L),
        )
    }

    @Test
    fun eventKeyIsDeterministicAndSeparatorSafe() {
        assertEquals(
            ChatTimelineMerge.eventKey("chat-abc", 1_000L),
            ChatTimelineMerge.eventKey("chat-abc", 1_000L),
        )
        // "chat-a|bc" + ts 0 darf NIE "chat-a" + ts "bc0" gleichen:
        assertNotEquals(
            ChatTimelineMerge.eventKey("chat-a\u0000bc", 0L),
            ChatTimelineMerge.eventKey("chat-a", 0L),
        )
    }

    // ── J1: Restore-Dedupe nur gegen RESTAURIERTE Zwillinge ───────────────

    @Test
    fun liveDuplicateIsKeptWhenDedupeRunsAgainstRestoredTwinsOnly() {
        // Aufrufer-Vertrag (ChatScreen, J1): freshUnseen bekommt als
        // `messages` NUR die restaurierten Disk-Zwillinge (id ≥ RESTORED_ID_
        // BASE) — NICHT die live übernommenen Nachrichten. Ein legitimer
        // Doppeltext (gleicher Agent, gleicher Text, gleiche Minute) aus
        // EINER Session wird dadurch NICHT gefressen; nur die persistierte
        // Restore-Dublette fliegt (siehe restoreDubletteIsFiltered).
        val restoredTwin = msg(2_000_000_000L, 7, true, "Ja", "14:02")
        val liveTwin = msg(5L, 7, true, "Ja", "14:02")
        // Simuliert: dedupe gegen [restoredTwin] (die live Twin id 5 ist
        // NICHT Teil der dedupe-Menge) — das zweite „Ja“ bleibt:
        val fresh = listOf(Ev(7, "Ja", "14:02"))
        val result = ChatTimelineMerge.freshUnseen(
            listOf(restoredTwin, liveTwin).filter { it.id >= 2_000_000_000L },
            fresh,
            agentIdOf = { it.agentId },
            textOf = { it.text },
            timeOf = { it.time },
        )
        // Die restored Twin ist in der Filtermenge → Duplikat fliegt:
        assertTrue(result.isEmpty())
        // Aber filtert man nur die restored Twins (id-Basis), bleibt eine
        // NEUE Nachricht derselben Minute erhalten:
        val freshNew = listOf(Ev(7, "Ganz anderer Text", "14:02"))
        assertEquals(freshNew, ChatTimelineMerge.freshUnseen(
            listOf(liveTwin).filter { it.id >= 2_000_000_000L },
            freshNew,
            agentIdOf = { it.agentId },
            textOf = { it.text },
            timeOf = { it.time },
        ))
    }

    // ── Restore-Merge (Disk-Stand + Live-Einträge dieser Session) ─────────

    @Test
    fun restoreMergeDropsPersistedLiveTwinAndKeepsUnpersisted() {
        // Restore-Race: M1 („Schon weg“) ist bereits persistiert und liegt im
        // Disk-Stand; M2 („Auf dem Weg“) ist live gesendet, aber noch NICHT
        // auf der Platte. Der Merge (ChatScreen) benutzt dedupeKey direkt:
        // M1-Live-Zwilling fliegt, M2 bleibt in der Anzeige.
        val restored = listOf(
            msg(2_000_000_000L, 7, false, "Schon weg", "14:01"),
            msg(2_000_000_001L, 7, true, "Antwort", "14:02"),
        )
        val live = listOf(
            msg(1L, 7, false, "Schon weg", "14:01"),
            msg(2L, 7, false, "Auf dem Weg", "14:03"),
        )
        val restoredKeys = restored.map {
            ChatTimelineMerge.dedupeKey(it.agentId, it.fromAgent, it.text, it.time)
        }.toHashSet()
        val keptLive = live.filter {
            ChatTimelineMerge.dedupeKey(it.agentId, it.fromAgent, it.text, it.time) !in restoredKeys
        }
        assertEquals(1, keptLive.size)
        assertEquals("Auf dem Weg", keptLive.single().text)
        assertFalse(keptLive.any { it.text == "Schon weg" })
        assertTrue(restored.none { it.text == "Auf dem Weg" })
    }
}