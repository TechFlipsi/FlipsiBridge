package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W18-1a (Besitzer-Fund 17.11., Screenshot-beweisen): Der FP-Abgleich darf in der
 * App NIEMALS als sichtbare Chat-Bubble erscheinen (live gesehen: Nutzer-
 * Hash-Bubble 'FP-ABGLEICH: …' + Agenten-Antwort „Fingerprint-Abgleich: OK
 * — Code ist gleich"). Neue Auswertungsregeln des verdeckten chat.fp_check-
 * Kontrollframes:
 *
 * - Der Agent antwortet als chat.fp_check (.enc)-Kontrollframe; die App
 *   VERARBEITET ihn (FP-Status: Banner bei Abweichung + dismissbare Ergebnis-
 *   Zeile) und persistiert/zeigt ihn NIERGENS als Chat-Zeile — Beweis im
 *   Programmtext: handleChatFpCheckResult enthält KEINEN chatEvents-/
 *   persistChatBubble-/notifyIncomingChat-Aufruf (RelayClient.kt, W18-1a-
 *   Verband).
 * - Legacy-Agenten antworten noch als Chat-Text mit dem Ergebnis-Präfix —
 *   isFpResultText() lenkt auch DIESER Weg auf den verdeckten Pfad ( Routing
 *   in handleChatEventFrame + handleChatEventEnc VOR dem Bubble-Eintrag).
 * - Das Senden bleibt unverändert 'FP-ABGLEICH: <hex>' über chat.send(.enc)
 *   (PhoneCryptoPeer.FP_PREFIX) — Agent-seitige Erkennung identisch.
 */
class FpCheckRulesTest {

    @Test
    fun controlFrameTypesMatchTheDesignSketch() {
        assertEquals("chat.fp_check", FpCheckRules.TYPE_CLEAR)
        assertEquals("chat.fp_check.enc", FpCheckRules.TYPE_ENC)
    }

    @Test
    fun clearFrameWithExplicitOkFieldWins() {
        val json = JsonObject().apply {
            addProperty("type", FpCheckRules.TYPE_CLEAR)
            addProperty("agent_id", "agent-07")
            addProperty("chat_id", "chat-abc12345")
            addProperty("ts", 1_000L)
            addProperty("ok", true)
        }
        val parsed = FpCheckRules.parse(json)
        assertEquals("agent-07", parsed?.agentId)
        assertEquals(true, parsed?.ok)
        assertEquals("Verdict folgt dem expliziten ok-Feld (Erfolg)", true, FpCheckRules.verdict(parsed?.ok, parsed?.text))
    }

    @Test
    fun okFieldFalseMeansDeviation() {
        // Live-Verband: Abweichung → rot-Banner (Revision (iii)), NIE Text.
        val verdict = FpCheckRules.verdict(ok = false, text = null)
        assertEquals(false, verdict)
    }

    @Test
    fun legacyChatReplyTextOkIsHiddenAndPasses() {
        // Besitzer-Screenshot (17.11., Agent-Chat): „Fingerprint-Abgleich:
        // OK — Code ist gleich" lief als sichtbare Bubble — jetzt verdeckt.
        val text = "Fingerprint-Abgleich: OK — Code ist gleich"
        assertTrue(FpCheckRules.isFpResultText(text))
        assertEquals(
            "Legacy-OK-Antwort = bestanden, verdeckt (keine Zeile)",
            true,
            FpCheckRules.verdict(ok = null, text = text),
        )
    }

    @Test
    fun legacyChatReplyTextFehlgeschlagenMeansDeviation() {
        val text = "Fingerprint-Abgleich: FEHLGESCHLAGEN — Fingerprint stimmen nicht überein!"
        assertTrue(FpCheckRules.isFpResultText(text))
        assertEquals(false, FpCheckRules.verdict(ok = null, text = text))
        // Fail-soft: FAILED-Frame ohne Text → ehrlicher Fallback-Text fürs Banner.
        assertEquals(
            "Fingerprint-Abgleich: FEHLGESCHLAGEN",
            FpCheckRules.FALLBACK_FAILED_TEXT,
        )
    }

    @Test
    fun failedMarkIsOnlyRecognizedRightAfterThePrefix() {
        // Präzision: 'FEHLGESCHLAGEN' zählt NUR direkt nach dem Ergebnis-Präfix
        // (Prefix-Verband wie am alten Bubble-Pfad) — kein contains-Fehlschlag.
        assertTrue(
            FpCheckRules.verdict(
                ok = null,
                text = "Fingerprint-Abgleich: OK — (nicht FEHLGESCHLAGEN)",
            ) == true,
        )
    }

    @Test
    fun frameWithoutAgentIdIsRejected() {
        val json = JsonObject().apply {
            addProperty("type", FpCheckRules.TYPE_CLEAR)
            addProperty("ok", true)
        }
        assertNull("agent_id ist PFLICHT — Frame wird verworfen", FpCheckRules.parse(json))
    }

    @Test
    fun normalChatTextIsNeverRoutedAsFpResult() {
        assertFalse(FpCheckRules.isFpResultText("Hallo, wie geht es dir?"))
        assertFalse(FpCheckRules.isFpResultText(""))
        assertFalse(FpCheckRules.isFpResultText(null))
        assertNull(
            "Ohne ok-Feld ohne Ergebnis-Präfix: nicht auswertbar → Frame verwerfen",
            FpCheckRules.verdict(ok = null, text = "normale Antwort"),
        )
    }

    @Test
    fun encPayloadParsingCarriesTextOrOk() {
        val okPayload = JsonObject().apply { addProperty("ok", true) }
        val (okText, okFlag) = FpCheckRules.parsePayload(okPayload)!!
        assertNull(okText)
        assertEquals(true, okFlag)

        val textPayload = JsonObject().apply {
            addProperty("text", "Fingerprint-Abgleich: OK")
        }
        val (text, ok) = FpCheckRules.parsePayload(textPayload)!!
        assertEquals("Fingerprint-Abgleich: OK", text)
        assertNull(ok)

        assertNull("Leere Nutzlast = nicht verwertbar", FpCheckRules.parsePayload(JsonObject()))
    }
}
