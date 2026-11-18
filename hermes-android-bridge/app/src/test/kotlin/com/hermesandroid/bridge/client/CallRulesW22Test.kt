package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * W22 (A1 STT v1 + A2 Call-Verlauf): JVM-reine Regeln der Anruf-Komplettierung.
 *
 * - A1: sttTurnText (Normalisieren/Cap/nichts-sendbar) und sttErrorClass
 *   (fail-soft-Kante: Stille/OEM-Quirk = neu lauschen, alles andere FATAL →
 *   sichtbarer Push-to-Talk-Fallback; SpeechRecognizer-Error-Codes als Ints
 *   gepinnt, Parität android.speech.SpeechRecognizer.ERROR_*).
 * - A2: callRecordFor — die Verlaufs-Entscheidung aus einer BEENDETEN
 *   Session (Bubble mit Dauer / Verpasst-Zeile / bewusst KEIN Eintrag bei
 *   Abweisung oder ausgeschaltetem Toggle), Dauer aus den LOKALEN
 *   Session-Zeiten (endedAt − activeSince, nie negativ).
 */
class CallRulesW22Test {

    private fun ringing(
        callId: String = "call-abc12345",
        ringingAt: Long = 1_000_000L,
    ) = CallRules.CallSession(
        callId = callId,
        agentId = "alpha",
        chatId = "chat-aaaa-1111",
        kind = "voice",
        reason = "Wetter durchsagen",
        phase = CallRules.Phase.RINGING,
        ringingSinceMs = ringingAt,
    )

    private fun endedCall(
        endSource: CallRules.EndSource,
        acceptedAt: Long? = 2_000_000L,
        endedAt: Long? = 2_161_000L,
    ): CallRules.CallSession {
        var session = ringing()
        if (acceptedAt != null) {
            session = CallRules.accept(session, acceptedAt)!!
        }
        val now = endedAt ?: 3_000_000L
        val ended = when (endSource) {
            CallRules.EndSource.DECLINED -> CallRules.decline(session, now)
            CallRules.EndSource.TIMEOUT -> CallRules.ringTimeout(session, now)
            CallRules.EndSource.AGENT -> CallRules.remoteEnd(session, session.callId, now)
            CallRules.EndSource.LOCAL -> CallRules.hangUp(session, now)
            CallRules.EndSource.CONNECTION -> CallRules.connectionLost(session, now)
            // MISSED trägt kein eigener Übergang am Phone (verpasst läuft über
            // den Ring-Timeout) — der Helper wird damit nie beauftragt.
            CallRules.EndSource.MISSED -> throw IllegalStateException("kein Übergang")
        }
        return ended!!
    }

    // ── A1: STT-Turn-Text ───────────────────────────────────────────────────

    @Test
    fun stt_turn_text_normalisiert_trimmt_und_cappt() {
        // Whitespace falten (Erkennungs-Rohausgaben kommen mehrzeilig).
        assertEquals("gib mir das Wetter", CallRules.sttTurnText("  gib mir\n das\tWetter  "))
        assertNull(CallRules.sttTurnText(null))
        assertNull(CallRules.sttTurnText(""))
        assertNull(CallRules.sttTurnText("   \n\t "))
        // Cap (kein Riesendiktat über die Chat-Text-Schiene).
        val long = "a".repeat(CallRules.STT_TURN_MAX_CHARS + 50)
        assertEquals(
            CallRules.STT_TURN_MAX_CHARS,
            CallRules.sttTurnText(long)!!.length,
        )
        assertEquals("kurz", CallRules.sttTurnText("kurz"))
    }

    @Test
    fun stt_fehlerklassen_fail_soft_kante() {
        // Stille/OEM-Quirk → neu lauschen (KEIN Modus-Wechsel).
        assertEquals(
            CallRules.SttErrorClass.RETRY_SILENT,
            CallRules.sttErrorClass(CallRules.SpeechRecognizerErrors.SPEECH_TIMEOUT),
        )
        assertEquals(
            CallRules.SttErrorClass.RETRY_SILENT,
            CallRules.sttErrorClass(CallRules.SpeechRecognizerErrors.NO_MATCH),
        )
        assertEquals(
            CallRules.SttErrorClass.RETRY_SILENT,
            CallRules.sttErrorClass(CallRules.SpeechRecognizerErrors.RECOGNIZER_BUSY),
        )
        // Alles andere = FATAL → sichtbarer Push-to-Talk-Fallback (fail-soft,
        // kein stiller Tod) — NETWORK bewusst FATAL: on-device darf der Ton
        // das Gerät nie verlassen, ein Netz-Fehler ist Abbruch, kein Retry.
        for (fatal in listOf(
            CallRules.SpeechRecognizerErrors.NETWORK_TIMEOUT,
            CallRules.SpeechRecognizerErrors.NETWORK,
            CallRules.SpeechRecognizerErrors.AUDIO,
            CallRules.SpeechRecognizerErrors.SERVER,
            CallRules.SpeechRecognizerErrors.CLIENT,
            CallRules.SpeechRecognizerErrors.INSUFFICIENT_PERMISSIONS,
            99, // unbekannter Code = ehrlich FATAL, nie „weiter so“
        )) {
            assertEquals("Fehlercode $fatal muss FATAL sein",
                CallRules.SttErrorClass.FATAL, CallRules.sttErrorClass(fatal))
        }
    }

    // ── A2: callRecordFor — Verlaufs-Entscheidung ──────────────────────────

    @Test
    fun verlauf_toggle_aus_heisst_keine_spur() {
        val ended = endedCall(CallRules.EndSource.LOCAL)
        assertNull(CallRules.callRecordFor(ended, recordEnabled = false))
        // Auch die Verpasst-Zeile unterliegt dem Toggle.
        val missed = endedCall(CallRules.EndSource.TIMEOUT, acceptedAt = null)
        assertNull(CallRules.callRecordFor(missed, recordEnabled = false))
    }

    @Test
    fun verlauf_null_wenn_nicht_beendet_oder_keine_session() {
        assertNull(CallRules.callRecordFor(null, recordEnabled = true))
        val active = CallRules.accept(ringing(), 2_000_000L)!!
        assertNull(CallRules.callRecordFor(active, recordEnabled = true))
        assertNull(CallRules.callRecordFor(ringing(), recordEnabled = true))
    }

    @Test
    fun beendeter_anruf_traegt_echte_dauer_egal_wer_auflegt() {
        // 2_000_000 → 2_161_000 = 161 s = „Anruf (2:41)“.
        for (source in listOf(
            CallRules.EndSource.AGENT,
            CallRules.EndSource.LOCAL,
            CallRules.EndSource.CONNECTION,
        )) {
            val record = CallRules.callRecordFor(endedCall(source), recordEnabled = true)!!
            assertFalse(record.missed)
            assertEquals(CallRules.CALL_DIRECTION_INCOMING, record.direction)
            assertEquals(161L, record.durationSec)
        }
    }

    @Test
    fun dauer_nie_negativ_uhr_sprung_abdeckung() {
        // endedAt VOR activeSince (Uhr-Sprung) → 0 statt negativ (nie lügen).
        val session = endedCall(
            CallRules.EndSource.LOCAL,
            acceptedAt = 5_000_000L,
            endedAt = 4_000_000L,
        )
        val record = CallRules.callRecordFor(session, recordEnabled = true)!!
        assertEquals(0L, record.durationSec)
    }

    @Test
    fun verpasster_ring_wird_verpasst_zeile() {
        // Ring-Fenster aus (call_missed.enc-Weg): nie angenommen → verpasst.
        val missed = endedCall(
            CallRules.EndSource.TIMEOUT,
            acceptedAt = null,
            endedAt = 1_045_000L,
        )
        val record = CallRules.callRecordFor(missed, recordEnabled = true)!!
        assertTrue(record.missed)
        assertEquals(0L, record.durationSec)
        assertEquals(CallRules.CALL_DIRECTION_INCOMING, record.direction)
        // Auch Agent-/Verbindungsabbruch während des Klingelns = verpasst
        // (WA-Muster: der Ring endete, ohne dass jemand abgenommen hat).
        for (source in listOf(CallRules.EndSource.AGENT, CallRules.EndSource.CONNECTION)) {
            val r = CallRules.callRecordFor(
                endedCall(source, acceptedAt = null), recordEnabled = true,
            )!!
            assertTrue("Abbruch im Klingeln ($source) ist verpasst", r.missed)
        }
    }

    @Test
    fun abgelehnter_anruf_hinterlaesst_bewusst_keinen_eintrag() {
        // DECLINED = Nutzer-Entscheidung — kein verpasster Anruf (der Agent
        // fängt decline adapter-seitig mit seinem Chat-Fallback selbst).
        val declined = endedCall(
            CallRules.EndSource.DECLINED,
            acceptedAt = null,
            endedAt = 1_020_000L,
        )
        assertNull(CallRules.callRecordFor(declined, recordEnabled = true))
    }
}
