package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-B (Chat-Suche) — Filter-Verband als JVM-Vertrag:
 *
 * - Suche über den VOLLSTÄNDIGEN Verlauf (ChatHistoryStore-Restore + Live),
 *   NICHT am 200er-chatEvents-Cap hängend: >200-Eintrag-Verhältnisse finden
 *   Treffer in den ältesten Einträgen (>200-Durcharbeiten in [matchesBeyond200]).
 * - Case-insensitives Enthaltens; Blank-Query = inaktiv (keine Trefferliste).
 * - Anzeige-Cap 50 begrenzt NUR die Trefferliste (vollständiger Scan läuft,
 *   chronologische Reihenfolge bleibt).
 * - Zitat-Text der Antwort-Bubble wird mitgesucht (Treffer im Zitat → Bubble-
 *   Anker); previewAround liefert ein Fenster um den Ersten Hit.
 */
class ChatSearchRulesTest {

    private fun msg(id: Long, fromAgent: Boolean, text: String, time: String = "10:0${id % 10}") =
        ChatMessage(id = id, agentId = 1, fromAgent = fromAgent, text = text, time = time)

    // ── Aktivierung + Normalisierung ────────────────────────────────────────

    @Test
    fun blankQueryIsInactive() {
        assertFalse(ChatSearchRules.active(""))
        assertFalse(ChatSearchRules.active("   \t\n"))
        assertTrue(ChatSearchRules.active(" münzen "))
    }

    @Test
    fun matchesOfBlankQueryIsEmpty() {
        assertTrue(ChatSearchRules.matches("", listOf(msg(1, true, "Münzen"))).isEmpty())
        assertTrue(ChatSearchRules.matches("  ", listOf(msg(1, true, "Münzen"))).isEmpty())
    }

    // ── Case-insensitive Treffer + Umlaute ──────────────────────────────────

    @Test
    fun matchIsCaseInsensitiveIncludingUmlauts() {
        val list = listOf(msg(1, true, "Die Münzen sind sortiert."))
        val hits = ChatSearchRules.matches("münzen", list)
        assertEquals(1, hits.size)
        assertEquals(1L, hits[0].messageId)
        assertEquals(0, hits[0].index)
        assertTrue("Agenten-Bubble erkannt (fromAgent)", hits[0].fromAgent)
    }

    @Test
    fun matchHitsBubblesAndTheirMediaLabels() {
        val list = listOf(
            msg(1, false, "Erste Frage"),
            msg(2, true, "[Bild] planung.png (1,2 MB)"),
            msg(3, false, "Danke, passt."),
        )
        val hits = ChatSearchRules.matches("planung", list)
        assertEquals(1, hits.size)
        assertEquals(2L, hits[0].messageId)
        assertEquals(1, hits[0].index)
    }

    // ── Zitat-Verband: Treffer im Zitat springt zur Bubble ─────────────────

    @Test
    fun quotedTextIsSearchableToo() {
        val list = listOf(
            msg(1, true, "Alles erledigt."),
            ChatMessage(
                id = 2, agentId = 1, fromAgent = false,
                text = "Danke!",
                time = "10:02",
                quoted = ChatQuote(
                    fromAgent = true, senderName = "Jarvis",
                    text = "Die Münzen liegen im Safe.",
                ),
            ),
        )
        val hits = ChatSearchRules.matches("MÜNZEN", list)
        assertEquals(1, hits.size)
        assertEquals(2L, hits[0].messageId)
        assertTrue("Hit im Zitat trägt den Zitat-Text", hits[0].previewText.contains("Münzen"))
    }

    // ── >200-Verhältnis: Filter über den VOLLSTÄNDIGEN Verlauf ──────────────

    @Test
    fun matchesBeyond200() {
        val list = (1L..300L).map { id ->
            msg(id, fr(id), "Bubble $id — Standardtext")
        }
        // Treffer jenseits des chatEvents-200er-Caps (Eintrag 250 + der Älteste).
        val list250 = list.take(200) + msg(9001L, false, "Bubble 250 — SONDERSUCHE") + list.drop(200).takeLast(99)
        val hitsRare = ChatSearchRules.matches("SONDERSUCHE", list)
        assertTrue(hitsRare.isEmpty())

        val hitsOld = ChatSearchRules.matches("Bubble 1 — Std", list)
        assertEquals("Ältester Eintrag (Index 0) wird getroffen", 1, hitsOld.size)
        assertEquals(0, hitsOld[0].index)

        val hits = ChatSearchRules.matches("Standardtext", list)
        assertEquals("Vollständige Scan-Größe (300, Cap NUR Anzeige)", ChatSearchRules.MAX_RESULTS, hits.size)
        assertEquals(0, hits[0].index)
        assertTrue(hits[0].index < ChatSearchRules.MAX_RESULTS)

        val hitsBeyond = ChatSearchRules.matches("Bubble 250", list250)
        val beyond200 = hitsBeyond.count { it.index >= 200 }
        assertTrue("Treffer > Position 200 gefunden", beyond200 > 0)
    }

    // ── Anzeige-Fenster + Zähl-Label ────────────────────────────────────────

    @Test
    fun previewWindowEllipsisesTheEdges() {
        val text = "a".repeat(500) + "Münze" + "b".repeat(500)
        val window = ChatSearchRules.previewAround(text, text.indexOf("Münze"))
        assertTrue(window.startsWith("…"))
        assertTrue(window.endsWith("…"))
        assertTrue(window.contains("Münze"))
        assertTrue("Fenster bleibt klein", window.length < 140)

        assertEquals("Kurzer Text ohne Ränder", "Münze", ChatSearchRules.previewAround("Münze", 0))
    }

    @Test
    fun countLabelSpeaksGermanSingularPlural() {
        assertEquals("1 Treffer", ChatSearchRules.countLabel(1))
        assertEquals("0 Treffer", ChatSearchRules.countLabel(0))
        assertEquals("50 Treffer", ChatSearchRules.countLabel(50))
    }

    private fun fr(id: Long) = id % 2L == 0L
}
