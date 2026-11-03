package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-WELLE-2 B7: Trigger-Test des AttachSheet-Open-Pfades (ChatScreen.kt
 * nutzt AttachSheetRules für die Render-Bedingung und die Offen-Prüfung
 * des BackHandlers).
 *
 * B7-LIVE-RE-DRILL (03.11.2026, scratch/herz_test/fw2-b7-redrill-beweis.txt):
 * Der Live-Chat-Befund war KEIN State-Reset-Race, sondern der ID-Vorzeichen-
 * Bug: Live-Agent-IDs sind agentId.hashCode() („jarvis“ → -1167182811) und
 * das alte Int-Gate `selectedAgentId >= 0` hielt das Sheet in JEDEM Live-Chat
 * geschlossen (nur die Demo-IDs 1..3 öffneten; Demo-Drill ging deshalb
 * fälschlich als „kein App-Bug“ durch). Der Vertrag ist jetzt vorzeichen-
 * fest: conversationOpen = die gewählte ID LIEGT in der Agenten-Liste.
 */
class AttachSheetRulesTest {

    @Test
    fun `offen in Conversation rendert das Sheet`() {
        assertTrue(
            AttachSheetRules.shouldRenderSheet(attachSheetOpen = true, conversationOpen = true),
        )
    }

    @Test
    fun `geschlossenes Sheet-Flag rendert nie ein Sheet`() {
        assertFalse(
            AttachSheetRules.shouldRenderSheet(attachSheetOpen = false, conversationOpen = true),
        )
    }

    @Test
    fun `ohne offene Conversation rendert nie ein Sheet`() {
        assertFalse(
            AttachSheetRules.shouldRenderSheet(attachSheetOpen = true, conversationOpen = false),
        )
    }

    @Test
    fun `geschlossenes Flag und keine Conversation bleiben einzeln geprueft`() {
        // Beide false-Gründe unabhängig: kein Kurzschluss-Klumpen, der bei
        // Refactoring (z. B. nur noch Open-Flag-Check) still verschwindet.
        assertFalse(
            AttachSheetRules.shouldRenderSheet(attachSheetOpen = false, conversationOpen = false),
        )
    }

    @Test
    fun `Live-Regression negative hashCode-ID ist eine offene Conversation`() {
        // Der exakte B7-Live-Fall: der gepaarte Agent „jarvis“ hat die Live-
        // ID "jarvis".hashCode() (JVM == Android: 31er-Polynom) = NEGATIV.
        // Das alte `id >= 0`-Gate verwarf ihn → Sheet blieb im Live-Chat stumm.
        val liveId = "jarvis".hashCode()
        assertEquals(-1167182811, liveId) // Sanity: Java-String-Hash ist negativ
        val open = AttachSheetRules.isConversationOpen(
            liveId, listOf(liveId, "edwin".hashCode()),
        )
        assertTrue(open)
        assertTrue(
            AttachSheetRules.shouldRenderSheet(attachSheetOpen = true, conversationOpen = open),
        )
    }

    @Test
    fun `Sentinel -1 oeffnet keine Conversation`() {
        // „Nichts gewählt“ bleibt zu — die -1 liegt in keiner Agenten-Liste.
        val open = AttachSheetRules.isConversationOpen(-1, listOf("jarvis".hashCode()))
        assertFalse(open)
        assertFalse(
            AttachSheetRules.shouldRenderSheet(attachSheetOpen = true, conversationOpen = open),
        )
    }

    @Test
    fun `fremde negative ID oeffnet keine Conversation`() {
        // Negativ allein reicht NICHT: die ID muss wirklich in der Liste
        // liegen (kein Sturm auf offene Türen für beliebige IDs).
        assertFalse(
            AttachSheetRules.isConversationOpen(-42, listOf("jarvis".hashCode())),
        )
    }
}