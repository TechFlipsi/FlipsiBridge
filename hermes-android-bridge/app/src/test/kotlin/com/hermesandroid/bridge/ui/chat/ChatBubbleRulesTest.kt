package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SIRS-04/SIRS-05 (Volltest 03.11.2026) — Anzeige-Regeln der Chat-Bubbles als
 * JVM-Vertrag (die Composables selbst rendern nur, die Regeln sind pur):
 *
 * - SIRS-04: „Antwort nach 0.0 s“ wirkte wie eine kaputte Messung. Vertrag:
 *   replyAfterLabel = null für alles unter 100 ms (Zeile bleibt weg), sonst
 *   die ECHTE Zeit mit einer Nachkommastelle.
 * - SIRS-05: Senden-Button nur bei nicht-leerem Text aktiv (leer/Whitespace =
 *   disabled statt enabled zu bleiben und still nichts zu tun).
 */
class ChatBubbleRulesTest {

    // ── SIRS-04: „Antwort nach X s“ ──────────────────────────────────────────

    @Test
    fun replyAfterBelow100msIsHidden() {
        // Der Volltest-Fund: elapsed 0.0 s als „kaputte Messung“-Optik.
        assertNull("null = keine Zeile", replyAfterLabel(0L))
        assertNull(replyAfterLabel(45L))
        assertNull(replyAfterLabel(99L))  // knapp unter dem Schwellwert
        assertNull(replyAfterLabel(null)) // keine Messung = keine Zeile
    }

    @Test
    fun replyAfterAtOrAbove100msShowsRealTime() {
        assertEquals("Antwort nach 0,1 s".replace(',', '.'), replyAfterLabel(100L)
            ?.replace(',', '.'))
        assertEquals("Antwort nach 0.5 s".replace(',', '.'), replyAfterLabel(500L)
            ?.replace(',', '.'))
        assertEquals("Antwort nach 10.0 s".replace(',', '.'), replyAfterLabel(10_000L)
            ?.replace(',', '.'))
    }

    @Test
    fun replyAfterThresholdConstantPinned() {
        assertEquals(100L, ChatBubbleRules.MIN_REPLY_AFTER_MS)
        // Grenzfälle exakt am Schwellwert: 100 ms ZEIGT, 99 ms nicht.
        assertNull(replyAfterLabel(ChatBubbleRules.MIN_REPLY_AFTER_MS - 1))
        // Locale-sicher: "%.1f".format liefert auf de-Systemen "0,1" —
        // Normalisierung wie in replyAfterAtOrAbove100msShowsRealTime.
        assertTrue(replyAfterLabel(ChatBubbleRules.MIN_REPLY_AFTER_MS)!!
            .replace(',', '.').contains("0.1"))
    }

    @Test
    fun replyAfterDescriptionSpellsOutSeconds() {
        assertEquals("Antwort nach 0.5 Sekunden".replace(',', '.'),
            replyAfterDescription(500L).replace(',', '.'))
    }

    // ── SIRS-05: Senden nur bei nicht-leerem Text ──────────────────────────

    @Test
    fun canSendRequiresNonBlankText() {
        assertFalse("leer = kein Senden", canSendChat(""))
        assertFalse("Whitespace = kein Senden", canSendChat("   "))
        assertFalse("Tab/Newline = kein Senden", canSendChat("\t\n"))
        assertTrue("echter Text = Senden", canSendChat("Hallo Agent"))
        assertTrue("ein Zeichen reicht", canSendChat("x"))
    }

    @Test
    fun canSendMatchesTheOldIsNotBlankGuard() {
        // Verhaltens-Parität zum bisherigen Guard (kein Verhaltenbruch —
        // die Regel ist nur explizit benannt und gepinnt).
        for (probe in listOf("", "  ", "a", " a ", "\u00A0", "ÄÖÜ")) {
            assertEquals(probe.isNotBlank(), canSendChat(probe))
        }
        assertNotEquals(canSendChat(""), canSendChat("x"))
    }

    // ── W15 (Sir-Fixliste 10): 3-Stufen-Häkchen ✓/✓✓/✓✓grün ────────────────

    @Test
    fun deliveryCheckLevelThreeStages() {
        // Stufe 1 = EIN Häkchen (versendet — null/Broker-Accept ohne Status).
        assertEquals(1, deliveryCheckLevel(null))
        assertEquals(1, deliveryCheckLevel(""))
        // Stufe 2 = ZWEI Häkchen (zugestellt).
        assertEquals(2, deliveryCheckLevel("delivered"))
        // Stufe 3 = ZWEI Häkchen GRÜN (Agent hat gelesen — Read-Ack).
        assertEquals(3, deliveryCheckLevel("read"))
    }

    @Test
    fun deliveryCheckLevelQueuedAndFailedDrawNoCheck() {
        // 0 = kein Häkchen: queued zeichnet die Amber-Uhr, failed das rote ✕.
        assertEquals(0, deliveryCheckLevel("queued"))
        assertEquals(0, deliveryCheckLevel("failed"))
    }
}
