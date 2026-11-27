package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W23-10 (ausgehender Anruf): JVM-reine Outbound-Regeln.
 *
 * - Besitzers Anruf-Tap → call_request.enc (Phone→Agent): newCallId/outboundSession/
 *   outboundRequestAllowed (fail-closed gegen Form-Mist und Zweitruf).
 * - Pickup: der Agent antwortet mit dem BESTAND call_invite.enc derselben
 *   call_id → outboundPickup ⇒ ACTIVE; jedes andere Muster fällt in die
 *   normale Invite-Entscheidung (Busy-Abweisung des NEUEN Calls).
 * - Abbruch/Timeout/Fremd-Ende/Verbindungsverlust im Rufe-Fenster.
 * - Verlauf: callRecordFor trägt die Richtung "outgoing" — abgenommene Anrufe
 *   mit echter Dauer, unbeantwortete als "nicht angenommen", der eigene
 *   Abbruch ehrlich OHNE Eintrag (Parität eingehend DECLINED).
 */
class CallRulesW23OutboundTest {

    private val tapTime = 1_000_000L

    /** SIRS Rauf-Anruf (Tap-Zeitpunkt, OUTGOING_RINGING, outbound = true). */
    private fun outgoing(
        callId: String = "a1b2c3d4e5f6a7b8c9d0e1f2",
        at: Long = tapTime,
    ): CallRules.CallSession = CallRules.outboundSession(
        callId, "alpha", "chat-aaaa-1111", at,
    )!!

    /** Invite des Agenten als Pickup/Echo einer call_id (Adapter-Verband). */
    private fun invite(
        callId: String?,
        agentId: String = "alpha",
        chatId: String? = "chat-aaaa-1111",
    ) = CallRules.InviteSpec(callId, agentId, chatId, "voice", null)

    // ── newCallId + outboundSession (fail-closed) ───────────────────────────

    @Test
    fun neue_call_id_ist_form_gueltig_und_deterministisch() {
        val bytes = byteArrayOf(
            0xDE.toByte(), 0xAD.toByte(), 0x01, 0x23, 0x45, 0x67,
            0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x11, 0x22,
        )
        val callId = CallRules.newCallId(bytes)
        // Parität tools/android_relay.py _CALL_ID_RE (8-64, [A-Za-z0-9_-]).
        assertTrue(CallRules.validCallId(callId))
        assertEquals("dead0123456789abcdef1122", callId)
        assertEquals(24, callId.length)
        // Verschiedene Bytes = verschiedene call_id (kein Kollisions-Muster).
        assertFalse(callId == CallRules.newCallId(byteArrayOf(0x00, 0x01) + bytes))
        // Verschiedene Längen unter der 8-Zeichen-Grenze = unbrauchbar (8 hex ≙
        // ByteArray(4) sind exakt die UNTERGRENZE und daher noch gültig).
        assertTrue(CallRules.validCallId(CallRules.newCallId(ByteArray(4))))
        assertFalse(CallRules.validCallId(CallRules.newCallId(ByteArray(3))))
    }

    @Test
    fun outbound_session_fail_closed_gegen_form_mist() {
        assertNotNull(outgoing())
        assertNull(CallRules.outboundSession("zu-kurz", "alpha", "chat-aaaa-1111", tapTime))
        assertNull(CallRules.outboundSession(null, "alpha", "chat-aaaa-1111", tapTime))
        assertNull(CallRules.outboundSession("a1b2c3d4", " ", "chat-aaaa-1111", tapTime))
        assertNull(CallRules.outboundSession("a1b2c3d4", "alpha", "", tapTime))
        assertNull(CallRules.outboundSession("a1b2c3d4", null, "chat-aaaa-1111", tapTime))
        // Gültige Session: Phase OUTGOING_RINGING, outbound-Flag gesetzt.
        val s = outgoing()
        assertEquals(CallRules.Phase.OUTGOING_RINGING, s.phase)
        assertTrue(s.outbound)
        assertEquals("alpha", s.agentId)
        assertEquals("", s.reason)
    }

    // ── Genau EIN Anruf je Gerät (Tap-Gate) ─────────────────────────────────

    @Test
    fun outbound_tap_nur_ohne_laufenden_anruf() {
        assertTrue(CallRules.outboundRequestAllowed(null))
        // ENDED (Abbruch-Fenster 10 s im Flow) hindert NICHT.
        assertTrue(
            CallRules.outboundRequestAllowed(
                CallRules.cancelOutgoing(outgoing(), tapTime + 500),
            ),
        )
        assertFalse(CallRules.outboundRequestAllowed(outgoing()))  // Rufe läuft
        assertFalse(
            CallRules.outboundRequestAllowed(
                CallRules.accept(outgoing(), tapTime + 100)!!,  // ACTIVE
            ),
        )
        assertFalse(
            CallRules.outboundRequestAllowed(
                // Eingehendes Klingeln schließt den Tap ebenso ab (ein Call je
                // Gerät, Parität inviteDecision Busy).
                CallRules.CallSession(
                    callId = "z9y8x7w6", agentId = "beta", chatId = "chat-bbbb-1",
                    kind = "voice", reason = "", phase = CallRules.Phase.RINGING,
                    ringingSinceMs = tapTime,
                ),
            ),
        )
    }

    // ── Pickup: Agent antwortet mit call_invite.enc derselben call_id ───────

    @Test
    fun pickup_mit_bestand_invite_derselben_call_id_wird_aktiv() {
        val s = outgoing()
        val picked = CallRules.outboundPickup(s, invite(s.callId), tapTime + 900)
        assertNotNull(picked)
        assertEquals(CallRules.Phase.ACTIVE, picked!!.phase)
        assertEquals(tapTime + 900, picked.activeSinceMs)
        assertTrue(picked.outbound)
        // Der Bestand-Übergang (accept) transportiert denselben Pickup:
        assertEquals(picked, CallRules.accept(s, tapTime + 900))
    }

    @Test
    fun pickup_fail_closed_bei_fremden_mustern() {
        val s = outgoing()
        // Fremde call_id = NEUER Agent-Anruf → kein Pickup.
        assertNull(CallRules.outboundPickup(s, invite("fremde0000001"), tapTime + 10))
        // Fremder Agent mit derselben call_id (Spoof) → kein Pickup.
        assertNull(CallRules.outboundPickup(s, invite(s.callId, agentId = "beta"), tapTime + 10))
        // call_id kaputt/leer → kein Pickup.
        assertNull(CallRules.outboundPickup(s, invite(null), tapTime + 10))
        // Schon ACTIVE (Duplikat-Invite nach der Annahme, J1-Muster) → kein
        // Übergang mehr, der Bestand verwirft via inviteDecision Invalid.
        assertNull(
            CallRules.outboundPickup(
                CallRules.accept(s, tapTime + 5)!!, invite(s.callId), tapTime + 10,
            ),
        )
        // Eingehende Session (Bestand, outbound=false): der Call-Invite-Verband
        // bleibt unangetastet — KEINE Pickup-Abkürzung von der Seite.
        val inbound = CallRules.inviteDecision(
            null, invite("z9y8x7w6"), tapTime,
        ) as CallRules.InviteDecision.Opened
        assertNull(CallRules.outboundPickup(inbound.session, invite("z9y8x7w6"), tapTime + 10))
    }

    @Test
    fun zweiter_anruf_waehrend_einem_rufe_wird_busy() {
        val s = outgoing()
        // Fremder Invite während OUTGOING_RINGING = NEUER Call → Busy-Abweisung.
        val decision = CallRules.inviteDecision(s, invite("fremde0000001"), tapTime + 10)
        assertTrue(decision is CallRules.InviteDecision.Busy)
        // Duplikat desselben Agenten (Spool-Flush) → Klingeln bleibt stehen.
        assertTrue(
            CallRules.inviteDecision(s, invite(s.callId), tapTime + 10)
                is CallRules.InviteDecision.Refreshed,
        )
    }

    // ── Übergänge im Rufe-Fenster ────────────────────────────────────────────

    @Test
    fun abbrechen_nur_im_rufe_fenster() {
        val s = outgoing()
        val canceled = CallRules.cancelOutgoing(s, tapTime + 300)!!
        assertEquals(CallRules.Phase.ENDED, canceled.phase)
        assertEquals(CallRules.EndSource.LOCAL, canceled.endSource)
        assertEquals(tapTime + 300, canceled.endedAtMs)
        // ACTIVE nutzt hangUp, RINGING (eingehend) nutzt decline — kein Mischweg.
        assertNull(CallRules.cancelOutgoing(CallRules.accept(s, tapTime + 100)!!, tapTime + 400))
        assertNull(
            CallRules.cancelOutgoing(
                CallRules.CallSession(
                    callId = "z9y8x7w6", agentId = "beta", chatId = "chat-bbbb-1",
                    kind = "voice", reason = "", phase = CallRules.Phase.RINGING,
                    ringingSinceMs = tapTime,
                ),
                tapTime + 400,
            ),
        )
    }

    @Test
    fun rufe_fenster_gilt_fuer_beide_richtungen() {
        val s = outgoing()
        assertFalse(CallRules.ringWindowPassed(s, tapTime + CallRules.RING_TIMEOUT_MS - 1))
        assertTrue(CallRules.ringWindowPassed(s, tapTime + CallRules.RING_TIMEOUT_MS))
        val ended = CallRules.ringTimeout(s, tapTime + CallRules.RING_TIMEOUT_MS)!!
        assertEquals(CallRules.Phase.ENDED, ended.phase)
        assertEquals(CallRules.EndSource.TIMEOUT, ended.endSource)
        // Der Übergang ändert NICHTS an der Richtung.
        assertTrue(ended.outbound)
    }

    @Test
    fun agent_ende_im_rufe_fenster_wird_akkzeptiert() {
        // Der Agent lehnt die Rauf-Anfrage ab (call_end.enc vor dem Pickup):
        val refused = CallRules.remoteEnd(outgoing(), "a1b2c3d4e5f6a7b8c9d0e1f2", tapTime + 250)!!
        assertEquals(CallRules.Phase.ENDED, refused.phase)
        assertEquals(CallRules.EndSource.AGENT, refused.endSource)
        // Fremde call_id fliegt weiterhin raus (Bestand).
        assertNull(CallRules.remoteEnd(outgoing(), "fremde0000001", tapTime + 10))
    }

    @Test
    fun verbindungsverlust_im_rufe_fenster_beendet_sauber() {
        val ended = CallRules.connectionLost(outgoing(), tapTime + 600)!!
        assertEquals(CallRules.EndSource.CONNECTION, ended.endSource)
        assertTrue(ended.phase == CallRules.Phase.ENDED)
    }

    // ── Verlauf (Richtung "outgoing") ────────────────────────────────────────

    @Test
    fun abgenommener_outgoing_anruf_traegt_richtung_und_dauer() {
        val s = outgoing()
        val ended = CallRules.hangUp(
            CallRules.accept(s, tapTime + 200)!!,
            tapTime + 200 + 161_000L,
        )!!
        val record = CallRules.callRecordFor(ended, recordEnabled = true)!!
        assertFalse(record.missed)
        assertEquals(CallRules.CALL_DIRECTION_OUTGOING, record.direction)
        assertEquals(161L, record.durationSec)
    }

    @Test
    fun unbeantworteter_outgoing_anruf_ist_ehrlich_nicht_angenommen() {
        // Rufe-Fenster ohne Pickup verstrichen → Verlaufs-Zeile "nicht angenommen".
        val ended = CallRules.ringTimeout(outgoing(), tapTime + 45_000L)!!
        val record = CallRules.callRecordFor(ended, recordEnabled = true)!!
        assertTrue(record.missed)
        assertEquals(0L, record.durationSec)
        assertEquals(CallRules.CALL_DIRECTION_OUTGOING, record.direction)
        // Ebenso wenn der AGENT die Anfrage ablehnt (AGENT-Ende, nie angenommen).
        val refused = CallRules.remoteEnd(outgoing(), "a1b2c3d4e5f6a7b8c9d0e1f2", tapTime + 300)!!
        val record2 = CallRules.callRecordFor(refused, recordEnabled = true)!!
        assertTrue(record2.missed)
        assertEquals(CallRules.CALL_DIRECTION_OUTGOING, record2.direction)
    }

    @Test
    fun eigener_abbruch_hinterlaesst_bewusst_keinen_eintrag() {
        // Besitzer zieht zurück, bevor der Agent nimmt (= die eigene Entscheidung;
        // Parität eingehend DECLINED: ehrlich ohne Spur).
        val canceled = CallRules.cancelOutgoing(outgoing(), tapTime + 300)!!
        assertNull(CallRules.callRecordFor(canceled, recordEnabled = true))
    }

    @Test
    fun eingehender_bestand_unveraendert() {
        // Der W22-Verband überlebt die Outbound-Erweiterung unverändert: 
        // ohne Flag (outbound = false) bleibt die Richtung "incoming".
        val ringing = CallRules.CallSession(
            callId = "z9y8x7w6", agentId = "beta", chatId = "chat-bbbb-1",
            kind = "voice", reason = "", phase = CallRules.Phase.RINGING,
            ringingSinceMs = tapTime,
        )
        val active = CallRules.accept(ringing, tapTime + 100)!!
        assertFalse(active.outbound)
        val ended = CallRules.hangUp(active, tapTime + 100 + 42_000L)!!
        val record = CallRules.callRecordFor(ended, recordEnabled = true)!!
        assertEquals(CallRules.CALL_DIRECTION_INCOMING, record.direction)
        assertFalse(record.missed)
        assertEquals(42L, record.durationSec)
    }
}
