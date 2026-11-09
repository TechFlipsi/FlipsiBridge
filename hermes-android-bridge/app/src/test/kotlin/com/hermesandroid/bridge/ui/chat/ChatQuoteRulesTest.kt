package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-B (Zitieren) — Draht-Verband der Zitieren-Kette als JVM-Vertrag:
 *
 * - Ratchet: das Zitat reist NUR im bestehenden `text`-Körper von chat.send /
 *   chat.send.enc (kein neuer Frame-Typ); wireText/splitWireText pinnen das
 *   Format, das der Relay unverändert persistiert (RelayClient.persistChatBubble
 *   speichert das GESAMTE text-Arg der Platte → Restore faltet mit splitWireText).
 * - Draht-Format: `Antwort auf [KI ]»<Zitat einzeilig, ≤ MAX_QUOTE_CHARS>«: <Antwort>`.
 * - Degradation: Doppel-„»…«:" im Original kürzt das Zitat eher — die ANTWORT
 *   geht nie verloren; Blank-Zitat → keine Präfixe; Agenten-Echo-Absätze
 *   (fromAgent = true) werden beim Restore nie gefaltet.
 */
class ChatQuoteRulesTest {

    private fun quote(fromAgent: Boolean, text: String, name: String = "") =
        ChatQuote(fromAgent = fromAgent, senderName = name, text = text)

    // ── wireText: Klartext + E2E (Gleicher Körper, nur Text-Feld) ──────────

    @Test
    fun wireWithoutQuoteIsThePlainReply() {
        assertEquals("Hallo Agent", ChatQuoteRules.wireText("Hallo Agent", null))
        // Blank-Zitat faltet zu leer → kein Präfix (degenerierter Fall).
        assertEquals(
            "Hallo Agent",
            ChatQuoteRules.wireText("Hallo Agent", quote(fromAgent = true, text = "  \n ")),
        )
    }

    @Test
    fun wireEmbedsQuoteForOwnOrigin() {
        val wire = ChatQuoteRules.wireText("Was bedeutet das?",
            quote(fromAgent = false, text = "Mach den Check."))
        assertEquals("Antwort auf »Mach den Check.«: Was bedeutet das?", wire)
    }

    @Test
    fun wireEmbedsQuoteForAgentOriginWithKiMarker() {
        val wire = ChatQuoteRules.wireText("Danke!",
            quote(fromAgent = true, text = "Alles erledigt.", name = "Jarvis"))
        assertEquals("Antwort auf KI »Alles erledigt.«: Danke!", wire)
    }

    @Test
    fun wireFlattensMultiLineQuotesToOneLine() {
        // Einzeilig = eindeutige Grenze zwischen Zitat und Antwort.
        val wire = ChatQuoteRules.wireText(
            "ok",
            quote(fromAgent = false, text = "Zeile 1\nZeile 2"),
        )
        assertEquals("Antwort auf »Zeile 1 / Zeile 2«: ok", wire)
        val parsed = ChatQuoteRules.splitWireText(wire)!!
        assertEquals("Zeile 1 / Zeile 2", parsed.quoteText)
        assertEquals("ok", parsed.reply)
    }

    @Test
    fun wireCapsHugeQuotesAtFourHundredChars() {
        val huge = "x".repeat(10_000)
        val wire = ChatQuoteRules.wireText("Antwort", quote(fromAgent = false, text = huge))
        assertTrue("Draht bleibt knapp (Cap + Antwort)", wire.length < 500)
        assertTrue(wire.endsWith("…«: Antwort"))
        val parsed = ChatQuoteRules.splitWireText(wire)!!
        assertEquals(ChatQuoteRules.MAX_QUOTE_CHARS, parsed.quoteText.length)
        assertEquals("Antwort", parsed.reply)
    }

    // ── splitWireText: Restore-Rückfold (Platte-Wahrheit) ──────────────────

    @Test
    fun splitParsesOwnAndAgentQuotes() {
        val own = ChatQuoteRules.splitWireText("Antwort auf »Frage«: Antwort Text")!!
        assertFalse(own.fromAgent)
        assertEquals("Frage", own.quoteText)
        assertEquals("Antwort Text", own.reply)

        val ki = ChatQuoteRules.splitWireText("Antwort auf KI »Frage«: Antwort")!!
        assertTrue(ki.fromAgent)
        assertEquals("Frage", ki.quoteText)
        assertEquals("Antwort", ki.reply)
    }

    @Test
    fun splitRejectsPlainTextAndPartialMatches() {
        assertNull("kein Präfix = kein Zitat", ChatQuoteRules.splitWireText("Einfache Nachricht"))
        // „Antwort auf KI" OHNE Zitat-Marker-Form (» fehlt, Terminator fehlt).
        assertNull(ChatQuoteRules.splitWireText("Antwort auf KI-Bubble: Hinweis"))
        // Degeneriertes leeres Zitat ( nie erzeugt, aber abgewehrt).
        assertNull(ChatQuoteRules.splitWireText("Antwort auf »»: Rest"))
        // Präfix nur IM Text: startet nicht mit dem Präfix → Echo, kein Fold.
        assertNull(ChatQuoteRules.splitWireText("Zitat: Antwort auf »Frage«: egal"))
    }

    @Test
    fun splitWithTwinQuoteMarkersKeepsTheReplyLossless() {
        // Original enthält selbst „»…«:" → Parser schneidet EHER; der Rest
        // bleibt in der Antwort sichtbar (Antwort-Text geht NIE verloren).
        val wire = ChatQuoteRules.wireText(
            "Antwort",
            quote(fromAgent = false, text = "Seite »links«: Kapitel 2"),
        )
        val parsed = ChatQuoteRules.splitWireText(wire)!!
        assertEquals("Seite »links", parsed.quoteText)
        assertTrue(parsed.reply.contains("Kapitel 2«: "))
        assertTrue(parsed.reply.endsWith("Antwort"))
    }

    @Test
    fun splitRequiresQuoteContentAfterThePrefix() {
        // „Antwort auf »…«:" direkt am Präfix = leeres Zitat → kein Fold.
        assertNull(ChatQuoteRules.splitWireText("Antwort auf »»: "))
    }

    @Test
    fun splitAndWireRoundTrip() {
        for ((fromAgent, quoted, reply) in listOf(
            Triple(false, "Originalfrage", "Antwort eins"),
            Triple(true, "Original\nAntwort", "Antwort\nzwei\nmehrzeilig"),
        )) {
            val wire = ChatQuoteRules.wireText(reply, quote(fromAgent, quoted, "Jarvis"))
            val parsed = ChatQuoteRules.splitWireText(wire)!!
            assertEquals(fromAgent, parsed.fromAgent)
            assertEquals(ChatQuoteRules.flatten(quoted), parsed.quoteText)
            assertEquals(reply, parsed.reply)
        }
    }

    // ── Labels + Kaps ──────────────────────────────────────────────────────

    @Test
    fun quoteLabelPrefersTheRealAgentName() {
        assertEquals("Du", ChatQuoteRules.quoteLabel(fromAgent = false, senderName = "Jarvis"))
        assertEquals("Jarvis", ChatQuoteRules.quoteLabel(fromAgent = true, senderName = "Jarvis"))
        // Demo-Eintrag (blank agentId → senderName leer/null) → neutrale Marke.
        assertEquals("KI", ChatQuoteRules.quoteLabel(fromAgent = true, senderName = null))
        assertEquals("KI", ChatQuoteRules.quoteLabel(fromAgent = true, senderName = "  "))
    }

    @Test
    fun quoteSpanFoldsAndCaps() {
        assertEquals("a", ChatQuoteRules.quoteSpan(" a "))
        assertEquals("a / b", ChatQuoteRules.quoteSpan("a\nb"))
        assertEquals("", ChatQuoteRules.quoteSpan(" \n\t "))
        assertEquals(
            ChatQuoteRules.MAX_QUOTE_CHARS,
            ChatQuoteRules.quoteSpan("ü".repeat(9999)).length,
        )
        assertTrue(ChatQuoteRules.quoteSpan("ü".repeat(9999)).endsWith("…"))
    }
}
