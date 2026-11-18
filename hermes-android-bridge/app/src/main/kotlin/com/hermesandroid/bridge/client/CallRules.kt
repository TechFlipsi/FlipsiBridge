package com.hermesandroid.bridge.client

/**
 * W21 ANRUF (P4-P6 + G11/G12, roadmap-anruf.md): JVM-reine Call-Regularien —
 * der Zustandsautomat (idle → ringing → active → ended), die Timeout-Regeln
 * und die Frame-/Payload-Validierung. RelayClient wendet die Ergebnisse rein
 * an (StateFlow + Senden); die UI (ui/call/CallScreen) rendert nur.
 *
 * FRAME-VERTRÄGE (aus dem Backend GELESEN, nicht geraten — HEAD b063693):
 * - tools/android_relay.py §call (W21 P1): Agent→Phone = call_invite.enc /
 *   call_end.enc (opak, Inhalt NUR im ct, Klar = chat_id/call_id/agent_id/
 *   session/hdr); Phone→Agent = call_ringing/accepted/declined/missed/ended.enc
 *   (je Geräte-Session, Rate-Cap 6/s, fail-closed je SENDER-Gerät).
 * - contrib/hermes-chat-plugin/adapter.py (W21 P2): call_id ^[A-Za-z0-9_-]{8,64}$,
 *   _CALL_REASON_MAX_CHARS = 600, _CALL_RING_TIMEOUT_S_DEFAULT = 45 (env-Fenster
 *   5-120 s), TTS-Chunks satzweise mit call_chunk_seq + call_id im ct (§3.3).
 * - §3.4: Ring-Annahme-Kette ringing → accepted; verpasster Ring → missed.
 *
 * AUDIO (P5, §3.1-§3.3): KEIN WebRTC — Nutzersprache läuft als Aufnahme-Chunk
 * über die bestehende Media-Pipeline (chat.media.enc, kind "voice", Adapter
 * ASR), Agentenantworten kommen als TTS-Chunks (call_chunk_seq-Marker) und
 * werden am Phone gesprochen. Ein Call-Chunk ist max. [AUDIO_CHUNK_MAX_MS]
 * lang (Turn-Deckel, kein Endlos-Diktat im Call).
 *
 * G11/G12 (§7.1/§7.2): die Freigabe-Pill (Agent darf im Call den Screen
 * steuern — über den bestehenden interaction-Tool-Verband) und der Screenshot
 * NUR für den Agenten (Capability screen_capture, RAM-only, KEIN Galerie-
 * Schreiben) tragen ihre Schalter hier als Konstanten.
 */
object CallRules {

    /** Call-Phasen — der ZUSTANDSAUTOMAT erlaubt nur die Kanten unten. */
    enum class Phase { IDLE, RINGING, ACTIVE, ENDED }

    /** WER hat den Call beendet (Chat-Zeile/A11y-Beschriftung leiten ab). */
    enum class EndSource { DECLINED, MISSED, TIMEOUT, AGENT, LOCAL, CONNECTION }

    /**
     * Eine Call-Session im StateFlow. Genau EIN Anruf je Gerät — ein Invite
     * während [Phase.RINGING]/[Phase.ACTIVE] wird mit call_declined.enc
     * abgewiesen (fail-closed gegen Zustands-Kollagen, Parität Backend
     * _CALLS_CAP + "kein lokaler Anruf-Auftrag"-Verwurf).
     */
    data class CallSession(
        val callId: String,
        val agentId: String,
        val chatId: String,
        val kind: String,
        val reason: String,
        val phase: Phase,
        /** Klingeln-Start (Unix-ms) — Timeout-Basis. */
        val ringingSinceMs: Long,
        /** Annahme-Zeitpunkt (Unix-ms) — Dauer-Basis, null = nie angenommen. */
        val activeSinceMs: Long? = null,
        val endedAtMs: Long? = null,
        val endSource: EndSource? = null,
        /**
         * G11 (§7.1): Freigabe-Pill — Sir erlaubt dem Agenten IM Call, den
         * Screen zu steuern (Wischen über den bestehenden interaction-Verband).
         * Je Call frisch (kein Dauer-Grant).
         */
        val agentScreenSteer: Boolean = false,
    )

    /** call_id-Form: Parität tools/android_relay.py _CALL_ID_RE. */
    private val CALL_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** Klingel-Fenster: Parität Adapter _CALL_RING_TIMEOUT_S_DEFAULT = 45 s. */
    const val RING_TIMEOUT_MS = 45_000L

    /** Klingel-Grund max.: Parität Adapter _CALL_REASON_MAX_CHARS. */
    const val REASON_MAX_CHARS = 600

    /** Turn-Deckel je Call-Aufnahme-Chunk (P5, §3.1 half-duplex — kein Diktat). */
    const val AUDIO_CHUNK_MAX_MS = 60_000L

    /** Sicherheits-Deckel eines ACTIVEN Calls — das Phone endet sauber mit
     *  call_ended.enc, falls keine Seite auflegt (Agent-Auto-End/TTL-Verband
     *  am Backend endet den Call ohnehin; dies ist die App-seitige Schranke). */
    const val ACTIVE_CAP_MS = 2L * 60L * 60_000L

    /** Klingel-Vibrations-Muster (an/aus-Wiederholung, Kanal-Verband). */
    val RING_VIBRATION_PATTERN = longArrayOf(0, 700, 500, 700, 500, 700)

    /** 3-s-Bestätigungsfenster des Screenshot-Chips (§7.2 Standard-Verband). */
    const val SCREENGRAB_CONFIRM_MS = 3_000L

    /** §7.2 Privacy-Kette: Downscale-Kante ~1400 px (RAM-Bitmap, kein EXIF). */
    const val SCREENGRAB_MAX_EDGE_PX = 1400

    /** §7.2 JPEG-Qualität (q80, Neukompression strippt EXIF/Geo inherent). */
    const val SCREENGRAB_JPEG_QUALITY = 80

    /** Capability-Kategorien (bestehender CapabilityGate-Verband, nicht neu). */
    const val CAP_INTERACTION = "interaction"      // G11: Wisch-/Steuer-Verband
    const val CAP_SCREEN_CAPTURE = "screen_capture" // G12: A11y-Screenshot (§7.2)

    // ── Frame-Typen (Parität tools/android_relay.py + adapter.py) ────────────
    const val FRAME_RINGING = "call_ringing.enc"
    const val FRAME_ACCEPTED = "call_accepted.enc"
    const val FRAME_DECLINED = "call_declined.enc"
    const val FRAME_MISSED = "call_missed.enc"
    const val FRAME_ENDED = "call_ended.enc"

    /** ct-Felder der Call-Modus-TTS-Antwort (§3.3, Parität Adapter-Marker). */
    const val CALL_ID_FIELD = "call_id"
    const val CALL_CHUNK_SEQ_FIELD = "call_chunk_seq"

    /** Ehrlich eindeutige Sprach-ID-Basis für Call-Chunks (keine Bubble-Bindung). */
    const val CALL_SPEAK_ID_BASE = 7_500_000L

    // ── Invite-Entscheidung (P4) ─────────────────────────────────────────────

    /** Eingehender Invite (Klar-ids + ct-Inhalt, bereits sanitisiert). */
    data class InviteSpec(
        val callId: String?,
        val agentId: String?,
        val chatId: String?,
        val kind: String,
        val reason: String?,
    )

    /** Ergebnis von [inviteDecision] — RelayClient rendert sie (Flows/Signale). */
    sealed interface InviteDecision {
        /** Klingeln annehmen: neuer Session-Eintrag. */
        data class Opened(val session: CallSession) : InviteDecision
        /** Duplikat-Invite derselben call_id (Spool-Flush) — Klingeln bleibt. */
        data object Refreshed : InviteDecision
        /** Zweiter Call während RINGING/ACTIVE — neuer Invite wird ABGEWIESEN
         *  (call_declined.enc für die NEUE call_id). */
        data object Busy : InviteDecision
        /** fail-closed: IDs unbrauchbar — verwerfen, KEIN Signal zurück. */
        data object Invalid : InviteDecision
    }

    fun validCallId(raw: String?): Boolean = !raw.isNullOrBlank() && CALL_ID_RE.matches(raw.trim())

    /**
     * Klingel-Grund normalisieren (Klingel-Text bleibt NUR im ct; dies ist die
     * UI-Anzeige-Kopie): trim, max [REASON_MAX_CHARS], null = "".
     */
    fun sanitizeReason(raw: String?): String =
        (raw ?: "").trim().take(REASON_MAX_CHARS)

    /** Anruf-Art normalisieren: v1 kennt "voice" — Unbekanntes fällt darauf zurück. */
    fun sanitizeKind(raw: String?): String = (raw ?: "").trim().ifEmpty { "voice" }

    /**
     * Invite-Entscheidung (P4-Zustandsautomat). fail-closed:
     * - IDs unbrauchbar → Invalid (verwerfen, KEIN Signal — kein Echo-Tor)
     * - Call bereits RINGING/ACTIVE:
     *   fremde call_id → Busy (ABWEISUNG des neuen Calls im Senden)
     *   eigene call_id → Refreshed (Duplikat-Invite, Klingeln bleibt stehen)
     * - sonst → Opened (RINGING, Klingeln startet)
     */
    fun inviteDecision(current: CallSession?, invite: InviteSpec, nowMs: Long): InviteDecision {
        if (!validCallId(invite.callId) || invite.agentId.isNullOrBlank() ||
            invite.chatId.isNullOrBlank()
        ) {
            return InviteDecision.Invalid
        }
        if (current != null && current.phase == Phase.RINGING) {
            return if (current.callId == invite.callId) InviteDecision.Refreshed
            else InviteDecision.Busy
        }
        if (current != null && current.phase == Phase.ACTIVE) {
            // J1-FIX: Duplikat der EIGENEN aktiven call_id (Spool-Flush/Re-Delivery
            // nach Annahme) darf den laufenden Call NICHT als Busy+decline töten —
            // verwerfen (kein Signal), der Anruf läuft weiter.
            return if (current.callId == invite.callId) InviteDecision.Invalid
            else InviteDecision.Busy
        }
        return InviteDecision.Opened(
            CallSession(
                callId = invite.callId!!.trim(),
                agentId = invite.agentId.trim(),
                chatId = invite.chatId.trim(),
                kind = sanitizeKind(invite.kind),
                reason = sanitizeReason(invite.reason ?: ""),
                phase = Phase.RINGING,
                ringingSinceMs = nowMs,
            ),
        )
    }

    // ── Übergänge (Kanten des Zustandsautomaten) ─────────────────────────────

    /** Annehmen: RINGING → ACTIVE (§3.4 accepted). Sonst null (fail-closed). */
    fun accept(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.RINGING) return null
        return session.copy(phase = Phase.ACTIVE, activeSinceMs = nowMs)
    }

    /** Ablehnen: RINGING → ENDED (declined). Sonst null — ACTIVE legt auf. */
    fun decline(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.RINGING) return null
        return session.copy(
            phase = Phase.ENDED,
            endedAtMs = nowMs,
            endSource = EndSource.DECLINED,
        )
    }

    /**
     * Ring-Fenster abgelaufen: RINGING → ENDED (timeout) — das Phone meldet
     * call_missed.enc (§3.4 verpasst; §3.5: Agent fällt auf Chat zurück).
     */
    fun ringTimeout(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.RINGING) return null
        return session.copy(
            phase = Phase.ENDED,
            endedAtMs = nowMs,
            endSource = EndSource.TIMEOUT,
        )
    }

    /** Ist das Klingel-Fenster für [session] vorbei? (Timeout-Takt im RelayClient) */
    fun ringWindowPassed(session: CallSession, nowMs: Long): Boolean =
        session.phase == Phase.RINGING && nowMs - session.ringingSinceMs >= RING_TIMEOUT_MS

    /**
     * Agent legt auf (call_end.enc): RINGING/ACTIVE mit MATCHENDER call_id →
     * ENDED (agent). Fremde/Spät-call_id → null (verwerfen, Parität Adapter
     * "kein lokaler Anruf-Auftrag").
     */
    fun remoteEnd(session: CallSession, callId: String?, nowMs: Long): CallSession? {
        if (callId != session.callId) return null
        if (session.phase != Phase.RINGING && session.phase != Phase.ACTIVE) return null
        return session.copy(
            phase = Phase.ENDED,
            endedAtMs = nowMs,
            endSource = EndSource.AGENT,
        )
    }

    /** Nutzer legt auf (Auflegen während ACTIVE): ACTIVE → ENDED (local). */
    fun hangUp(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.ACTIVE) return null
        return session.copy(
            phase = Phase.ENDED,
            endedAtMs = nowMs,
            endSource = EndSource.LOCAL,
        )
    }

    /** Kap-Ende des ACTIVEN Calls ([ACTIVE_CAP_MS] überschritten) — wie hangUp. */
    fun activeCapExceeded(session: CallSession, nowMs: Long): Boolean {
        val since = session.activeSinceMs ?: return false
        return session.phase == Phase.ACTIVE && nowMs - since >= ACTIVE_CAP_MS
    }

    /**
     * Reconnect/Trennung während eines Calls (P6): RINGING/ACTIVE → ENDED
     * (connection). Senden ist an der toten WS unmöglich — der Adapter holt
     * das über sein Ring-Fenster/Spool-Verwurf selbst (fail-closed dort).
     */
    fun connectionLost(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.RINGING && session.phase != Phase.ACTIVE) return null
        return session.copy(
            phase = Phase.ENDED,
            endedAtMs = nowMs,
            endSource = EndSource.CONNECTION,
        )
    }

    /** G11-Pill umlegen: nur im RINGING/ACTIVE-Phase (ENDED ist Geschichte). */
    fun setAgentSteer(session: CallSession, allowed: Boolean): CallSession? {
        if (session.phase != Phase.RINGING && session.phase != Phase.ACTIVE) return null
        if (session.agentScreenSteer == allowed) return null  // kein No-op-Write
        return session.copy(agentScreenSteer = allowed)
    }

    // ── Anzeige-Helfer ───────────────────────────────────────────────────────

    /**
     * Alias/Kennung generisch: Der Anzeigename GEWINNT (relay.state alias),
     * fehlt/leert er, bleibt die rohe agent_id (eherliche Kennung).
     */
    fun displayName(alias: String?, agentId: String): String =
        alias?.trim()?.takeIf { it.isNotEmpty() } ?: agentId.trim()

    /** Dauer in Sekunden je Phase (RINGING = 0 — Klingeln zählt nicht). */
    fun activeSeconds(session: CallSession?, nowMs: Long): Long {
        val since = session?.activeSinceMs?.takeIf { session.phase == Phase.ACTIVE }
            ?: return 0L
        return maxOf(0L, (nowMs - since) / 1000L)
    }

    /** m:ss bzw. h:mm:ss (Dauer-/Verlauf-Anzeige). */
    fun formatDuration(totalSeconds: Long): String {
        val s = maxOf(0L, totalSeconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
        }
    }

    // ── P5: Call-Modus-TTS (§3.3) + Media-Namen ──────────────────────────────

    /**
     * true = dieses chat.event.ct ist ein Call-Modus-TTS-Chunk des AKTIVEN
     * Calls: Marker call_chunk_seq (≥ 0) + call_id MUSS der aktiven Session
     * entsprechen (fremde/Spät-Marker werden NICHT gesprochen — sie sind
     * normale Chat-Antworten). RINGING/ENDED nie sprechen.
     */
    fun callTtsActive(session: CallSession?, payloadCallId: String?, chunkSeq: Int?): Boolean =
        session != null &&
            session.phase == Phase.ACTIVE &&
            !payloadCallId.isNullOrBlank() &&
            payloadCallId == session.callId &&
            chunkSeq != null &&
            chunkSeq >= 0

    /** Dateiname eines Call-Audio-Turns (Media-Name, Call-Kontext lesbar). */
    fun callAudioName(callId: String, seq: Int): String =
        "$callId-turn${seq.toString().padStart(3, '0')}.m4a"

    /** Screengrab-Dateiname (§7.2 — RAM-only, Name dient nur dem Meta-Frame). */
    fun screengrabName(callId: String?, tsMs: Long): String =
        if (callId.isNullOrBlank()) "agent-shot-$tsMs.jpg" else "agent-shot-$callId-$tsMs.jpg"
}
