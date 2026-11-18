package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W21 ANRUF (P4-P6, roadmap-anruf.md §3.4/§3.5/§3.6): CallRules — JVM-reiner
 * Zustandsautomat + Frame-/Payload-Validierung. FRAME-VERTRÄGE aus dem Backend
 * GELESEN (nicht geraten, HEAD b063693):
 * - tools/android_relay.py _CALL_ID_RE = ^[A-Za-z0-9_-]{8,64}$,
 *   _CALL_SIGNAL_MAX_PER_SECOND = 6 (Phone→Relay).
 * - adapter.py: _CALL_REASON_MAX_CHARS = 600, _CALL_RING_TIMEOUT_S_DEFAULT
 *   = 45.0 (Fenster 5-120 s), call_chunk_seq satzweise (§3.3).
 * Tests halten die Vertrags-paritäten fest und beweisen die fail-closed-Kanten
 * (Zweitinvite, fremde call_id, illegale Übergänge, Timeout-Fenster).
 */
class CallRulesTest {

    private fun ringing(
        callId: String = "call-abc12345",
        agentId: String = "alpha",
        ringingAt: Long = 1_000_000L,
    ) = CallRules.CallSession(
        callId = callId,
        agentId = agentId,
        chatId = "chat-aaaa-1111",
        kind = "voice",
        reason = "Wetter durchsagen",
        phase = CallRules.Phase.RINGING,
        ringingSinceMs = ringingAt,
    )

    private fun active(session: CallRules.CallSession, acceptedAt: Long): CallRules.CallSession =
        CallRules.accept(session.copy(), acceptedAt)!!

    // ── Form-Validierung (Parität _CALL_ID_RE) ──────────────────────────────

    @Test
    fun call_id_form_paritaet_relay() {
        assertTrue(CallRules.validCallId("call-abc12345"))
        assertTrue(CallRules.validCallId("call-rate0000"))
        assertTrue(CallRules.validCallId("call_fremde1_underscore-"))
        // zu kurz / ungültige Zeichen / leer / null — alles fail-closed.
        assertFalse(CallRules.validCallId("call-1"))
        assertFalse(CallRules.validCallId("call mit leer"))
        assertFalse(CallRules.validCallId("call/slash"))
        assertFalse(CallRules.validCallId(""))
        assertFalse(CallRules.validCallId(null))
    }

    @Test
    fun reason_und_kind_sanitisiert_kappung_und_fallback() {
        assertEquals("", CallRules.sanitizeReason(null))
        assertEquals("", CallRules.sanitizeReason("  "))  // trim → leer
        assertEquals("A".repeat(600), CallRules.sanitizeReason("A".repeat(600) + "drüber"))
        assertEquals("Wetter durchsagen", CallRules.sanitizeReason("  Wetter durchsagen "))
        // v1 kennt "voice" — unbekannt/leer fällt ehrlich darauf zurück.
        assertEquals("voice", CallRules.sanitizeKind(null))
        assertEquals("voice", CallRules.sanitizeKind(""))
        assertEquals("video", CallRules.sanitizeKind("video"))
        assertEquals("voice", CallRules.sanitizeKind(" voice "))
    }

    // ── Invite-Entscheidung (P4: genau EIN Anruf je Gerät) ──────────────────

    @Test
    fun invite_ohne_bestehenden_call_oeffnet_ringing() {
        val decision = CallRules.inviteDecision(
            null,
            CallRules.InviteSpec("call-abc12345", "alpha", "chat-aaaa-1111", "voice", "Wetter"),
            1_000_000L,
        )
        assertTrue(decision is CallRules.InviteDecision.Opened)
        val session = (decision as CallRules.InviteDecision.Opened).session
        assertEquals(CallRules.Phase.RINGING, session.phase)
        assertEquals("call-abc12345", session.callId)
        assertEquals("alpha", session.agentId)
        assertEquals("chat-aaaa-1111", session.chatId)
        assertEquals("Wetter", session.reason)
        assertEquals("voice", session.kind)
        assertEquals(1_000_000L, session.ringingSinceMs)
        assertTrue(session.activeSinceMs == null)
    }

    @Test
    fun invite_fremd_während_ring_ist_busy_own_callId_refreshed() {
        val current = ringing()
        // Fremde call_id → Busy (ABWEISUNG des neuen Calls im Senden).
        assertEquals(
            CallRules.InviteDecision.Busy,
            CallRules.inviteDecision(
                current,
                CallRules.InviteSpec("call-fremde1_", "beta", "chat-aaaa-1111", "voice", "x"),
                1_000_010L,
            ),
        )
        // Eigene call_id (Spool-Flush) → Refreshed (Klingeln bleibt stehen).
        assertEquals(
            CallRules.InviteDecision.Refreshed,
            CallRules.inviteDecision(
                current,
                CallRules.InviteSpec("call-abc12345", "alpha", "chat-aaaa-1111", "voice", "Wetter"),
                1_000_020L,
            ),
        )
    }

    @Test
    fun invite_duplikat_während_active_ist_invalid_statt_busy() {
        // J1-Fix (e50a59b): ein Spool-Re-Invite der EIGENEN call_id während ACTIVE
        // darf den laufenden Call nicht als Busy+decline töten → Invalid (verwerfen).
        val current = active(ringing(), 1_000_500L)
        assertEquals(
            CallRules.InviteDecision.Invalid,
            CallRules.inviteDecision(
                current,
                CallRules.InviteSpec("call-abc12345", "alpha", "chat-aaaa-1111", "voice", "x"),
                1_000_510L,
            ),
        )
    }

    @Test
    fun invite_mit_defekten_ids_ist_invalid_ohne_signal() {
        // Null/leer/falsche Form — KEIN Signal zurück (kein Echo-Tor).
        val bad = listOf(
            CallRules.InviteSpec(null, "alpha", "chat-1", "voice", "x"),
            CallRules.InviteSpec("call-1", "alpha", "chat-1", "voice", "x"),
            CallRules.InviteSpec("call-abc12345", "", "chat-1", "voice", "x"),
            CallRules.InviteSpec("call-abc12345", "alpha", null, "voice", "x"),
            CallRules.InviteSpec("call-abc12345", "alpha", "  ", "voice", "x"),
        )
        bad.forEach { spec ->
            assertEquals(
                "fail-closed für $spec",
                CallRules.InviteDecision.Invalid,
                CallRules.inviteDecision(null, spec, 0L),
            )
        }
    }

    // ── Übergänge ────────────────────────────────────────────────────────────

    @Test
    fun accept_nur_aus_ringing_liefert_active_mit_annahmezeit() {
        val session = ringing(ringingAt = 1_000_000L)
        val accepted = CallRules.accept(session, 1_000_600L)!!
        assertEquals(CallRules.Phase.ACTIVE, accepted.phase)
        assertEquals(1_000_600L, accepted.activeSinceMs)
        assertEquals("call-abc12345", accepted.callId)
        // ACTIVE→accept ist keine Kante.
        assertNull(CallRules.accept(accepted, 1_000_700L))
        // ENDED→accept ebenso (Spät-Accept auf verpasstem Call — fail-closed).
        assertNull(CallRules.accept(CallRules.ringTimeout(session, 1_050_000L)!!, 1_100_000L))
    }

    @Test
    fun decline_nur_aus_ringing_mit_ended_gefaltet() {
        val ended = CallRules.decline(ringing(), 1_000_800L)!!
        assertEquals(CallRules.Phase.ENDED, ended.phase)
        assertEquals(CallRules.EndSource.DECLINED, ended.endSource)
        assertEquals(1_000_800L, ended.endedAtMs)
        // ACTIVE → Ablehnen ist KEINE Kante (dort gehört Auflegen her).
        assertNull(CallRules.decline(active(ringing(), 1_000_500L), 1_000_900L))
    }

    @Test
    fun ring_timeout_nur_aus_ringing_paritaet_45s_fenster() {
        // Parität Adapter _CALL_RING_TIMEOUT_S_DEFAULT = 45.0 s.
        assertEquals(45_000L, CallRules.RING_TIMEOUT_MS)
        val ended = CallRules.ringTimeout(ringing(), 1_045_001L)!!
        assertEquals(CallRules.EndSource.TIMEOUT, ended.endSource)
        assertNull(CallRules.ringTimeout(active(ringing(), 1_000_500L), 1_045_100L))
    }

    @Test
    fun ring_window_passed_genau_am_fensterrand() {
        val session = ringing(ringingAt = 1_000_000L)
        assertFalse(CallRules.ringWindowPassed(session, 1_000_000L))
        assertFalse(CallRules.ringWindowPassed(session, 1_000_000L + CallRules.RING_TIMEOUT_MS - 1))
        assertTrue(CallRules.ringWindowPassed(session, 1_000_000L + CallRules.RING_TIMEOUT_MS))
        // Nach Ende kein Timeout mehr.
        assertFalse(CallRules.ringWindowPassed(endedAfter(session), 2_000_000L))
    }

    @Test
    fun remote_end_braucht_matchende_callId_und_lebende_phase() {
        val session = ringing()
        val ended = CallRules.remoteEnd(session, "call-abc12345", 1_001_000L)!!
        assertEquals(CallRules.EndSource.AGENT, ended.endSource)
        assertEquals(CallRules.Phase.ENDED, ended.phase)
        // ACTIVE → Agent-Ende ebenfalls gültige Kante.
        val activeEnded = CallRules.remoteEnd(active(session, 1_000_500L), "call-abc12345", 1_002_000L)!!
        assertEquals(CallRules.EndSource.AGENT, activeEnded.endSource)
        // Fremde/Spät-call_id → verwerfen (Parität "kein lokaler Anruf-Auftrag").
        assertNull(CallRules.remoteEnd(session, "call-fremde1_", 1_001_000L))
        assertNull(CallRules.remoteEnd(session, null, 1_001_000L))
        // ENDED kann nicht nochmal enden.
        assertNull(CallRules.remoteEnd(ended, "call-abc12345", 1_003_000L))
    }

    @Test
    fun hang_up_nur_während_active_local_end() {
        val activeSession = active(ringing(), 1_000_500L)
        val ended = CallRules.hangUp(activeSession, 1_002_500L)!!
        assertEquals(CallRules.EndSource.LOCAL, ended.endSource)
        assertEquals(CallRules.Phase.ENDED, ended.phase)
        // Beim KLINGELN gibt es kein Auflegen — Ablehnen ist die Kante.
        assertNull(CallRules.hangUp(ringing(), 1_000_600L))
    }

    @Test
    fun connection_lost_endet_ringing_und_active_ohne_signal() {
        assertEquals(
            CallRules.EndSource.CONNECTION,
            CallRules.connectionLost(ringing(), 1_010_000L)!!.endSource,
        )
        assertEquals(
            CallRules.EndSource.CONNECTION,
            CallRules.connectionLost(active(ringing(), 1_000_500L), 1_010_000L)!!.endSource,
        )
        // ENDED bleibt (kein Doppel-Ende).
        assertNull(CallRules.connectionLost(endedAfter(ringing()), 1_010_000L))
    }

    @Test
    fun agent_steer_nur_während_ringing_or_active_kein_noop_write() {
        val session = ringing()
        // Freigabe ON (G11-Pill).
        val granted = CallRules.setAgentSteer(session, true)!!
        assertTrue(granted.agentScreenSteer)
        // Kein State-Write für denselben Zustand (null = keine Änderung).
        assertNull(CallRules.setAgentSteer(granted, true))
        // Freigabe OFF.
        assertFalse(CallRules.setAgentSteer(granted, false)!!.agentScreenSteer)
        // ENDED ist Geschichte — die Pill lebt je Call.
        assertNull(CallRules.setAgentSteer(endedAfter(session), true))
    }

    @Test
    fun active_cap_exceeded_genau_am_rand() {
        val session = active(ringing(), 1_000_000L) // activeSinceMs
        assertFalse(CallRules.activeCapExceeded(session, 1_000_000L + CallRules.ACTIVE_CAP_MS - 1))
        assertTrue(CallRules.activeCapExceeded(session, 1_000_000L + CallRules.ACTIVE_CAP_MS))
        // RINGING hat keine aktive-phase-Kappe.
        assertFalse(CallRules.activeCapExceeded(ringing(), 99_999_999L))
        // ENDED ebenfalls nicht.
        assertFalse(CallRules.activeCapExceeded(endedAfter(session), 999_999_999L))
    }

    // ── Anzeige-Helfer ───────────────────────────────────────────────────────

    @Test
    fun display_name_alias_gewinnt_blank_faellt_auf_agent_id() {
        assertEquals("Jarvis Zuhause", CallRules.displayName("Jarvis Zuhause ", "alpha"))
        assertEquals("alpha", CallRules.displayName(null, "alpha"))
        assertEquals("alpha", CallRules.displayName("", "alpha"))
        assertEquals("alpha", CallRules.displayName("   ", "alpha"))
    }

    @Test
    fun active_seconds_ring_zaehlt_nicht() {
        val ringingSession = ringing(ringingAt = 1_000_000L)
        assertEquals(0L, CallRules.activeSeconds(ringingSession, 1_065_000L))
        val accepted = active(ringingSession, 1_060_000L)
        assertEquals(5L, CallRules.activeSeconds(accepted, 1_065_000L))
        // Nach Ende (gehaltene Zeit) — Anzeige hört auf: Phase ≠ ACTIVE.
        assertEquals(0L, CallRules.activeSeconds(CallRules.ringTimeout(accepted, 0L), 1_065_000L))
        assertEquals(0L, CallRules.activeSeconds(null, 1_065_000L))
    }

    @Test
    fun format_duration_mit_stunden_stufe() {
        assertEquals("0:00", CallRules.formatDuration(0))
        assertEquals("0:41", CallRules.formatDuration(41))
        assertEquals("12:05", CallRules.formatDuration(725))
        assertEquals("1:02:03", CallRules.formatDuration(3723))
        // Negative Werte (Clock-Skew) heilen auf Null — nie ein "-12:07".
        assertEquals("0:00", CallRules.formatDuration(-725))
    }

    // ── P5: Call-Modus-TTS-Marker (§3.3) ─────────────────────────────────────

    @Test
    fun call_tts_marker_nur_im_aktiven_matching_call() {
        val activeSession = active(ringing(), 1_000_500L)
        assertTrue(
            CallRules.callTtsActive(activeSession, "call-abc12345", 0),
        )
        assertTrue(CallRules.callTtsActive(activeSession, "call-abc12345", 31))
        // RINGING: nichts sprechen (Agent sagt nichts zu einer Klingel).
        assertFalse(CallRules.callTtsActive(ringing(), "call-abc12345", 0))
        // ENDED: Marker-Verband zu (Parität Backend "kein Marker nach Call-Ende").
        assertFalse(CallRules.callTtsActive(endedAfter(activeSession), "call-abc12345", 0))
        // Fremde/leere call_id + fehlender Chunk = normale Chat-Antwort.
        assertFalse(CallRules.callTtsActive(activeSession, "call-fremde1_", 0))
        assertFalse(CallRules.callTtsActive(activeSession, "call-abc12345", null))
        assertFalse(CallRules.callTtsActive(activeSession, null, 0))
        assertFalse(CallRules.callTtsActive(null, "call-abc12345", 0))
        assertTrue(CallRules.callTtsActive(activeSession, "call-abc12345", -1).not())
    }

    // ── Media-Namen (P5-Chunk + G12-Screengrab) ──────────────────────────────

    @Test
    fun call_audio_name_trägt_call_und_sequenz_gepaddet() {
        assertEquals("call-abc12345-turn000.m4a", CallRules.callAudioName("call-abc12345", 0))
        assertEquals("call-abc12345-turn009.m4a", CallRules.callAudioName("call-abc12345", 9))
        assertEquals("call-abc12345-turn012.m4a", CallRules.callAudioName("call-abc12345", 12))
    }

    @Test
    fun invite_duplikat_der_eigenen_call_id_waehrend_active_wird_verworfen_nicht_busy() {
        // J1-FIX: Spool-Flush-Re-Invite nach Annahme darf den laufenden Call
        // NICHT per Busy+decline töten — Invalid (kein Signal) statt Busy.
        val active = CallRules.CallSession(
            callId = "call-abc12345", agentId = "agent-1", chatId = "chat-1",
            kind = "voice", reason = "", phase = CallRules.Phase.ACTIVE,
            ringingSinceMs = 0L,
        )
        val dup = CallRules.inviteDecision(
            active,
            CallRules.InviteSpec("call-abc12345", "agent-1", "chat-1", "voice", ""),
            System.currentTimeMillis(),
        )
        assertTrue(dup is CallRules.InviteDecision.Invalid)
        val other = CallRules.inviteDecision(
            active,
            CallRules.InviteSpec("call-other99", "agent-1", "chat-1", "voice", ""),
            System.currentTimeMillis(),
        )
        assertTrue(other is CallRules.InviteDecision.Busy)
    }

    @Test
    fun screengrab_name_mit_und_ohne_call_id() {
        assertEquals("agent-shot-42.jpg", CallRules.screengrabName(null, 42L))
        assertEquals("agent-shot-42.jpg", CallRules.screengrabName("", 42L))
        assertEquals(
            "agent-shot-call-abc12345-42.jpg",
            CallRules.screengrabName("call-abc12345", 42L),
        )
    }

    // ── Konstanten-Kette (Paritäten, damit Drift laut schlägt statt still) ──

    @Test
    fun vertrags_konstanten_paritaet_backend() {
        assertEquals(45_000L, CallRules.RING_TIMEOUT_MS)          // adapter.py 45.0 s
        assertEquals(600, CallRules.REASON_MAX_CHARS)             // adapter.py 600
        assertEquals(3_000L, CallRules.SCREENGRAB_CONFIRM_MS)     // §7.2 3-s-Chip
        assertEquals(1400, CallRules.SCREENGRAB_MAX_EDGE_PX)      // §7.2 ~1400 px
        assertEquals(80, CallRules.SCREENGRAB_JPEG_QUALITY)       // §7.2 q80
        // 2 h Sicherheits-Deckel des aktiven Calls (app-seitige Schranke).
        assertEquals(2L * 60L * 60_000L, CallRules.ACTIVE_CAP_MS)
        // Vibrations-Pattern: [0, a, b, ...] — Erst-Schlag sofort, mehrfach.
        assertEquals(0L, CallRules.RING_VIBRATION_PATTERN[0])
        assertTrue(CallRules.RING_VIBRATION_PATTERN.size >= 4)
    }

    private fun endedAfter(session: CallRules.CallSession): CallRules.CallSession =
        CallRules.remoteEnd(
            if (session.phase == CallRules.Phase.ACTIVE) session
            else CallRules.accept(session, session.ringingSinceMs + 1_000L) ?: session,
            session.callId,
            session.ringingSinceMs + 2_000L,
        ) ?: session.copy(phase = CallRules.Phase.ENDED, endedAtMs = session.ringingSinceMs + 2_000L)
}
