package com.hermesandroid.bridge.client

/**
 * W21 ANRUF (P4-P6 + G11/G12, Anruf-Konzept): JVM-reine Call-Regularien —
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

    /** Call-Phasen — der ZUSTANDSAUTOMAT erlaubt nur die Kanten unten.
     * W23-10: OUTGOING_RINGING = Besitzer hat den Agenten aus der APP angerufen
     * (call_request.enc unterwegs); der Agent nimmt mit dem BESTAND call_
     * invite.enc derselben call_id ab → ACTIVE (kein neuer Modus dort). */
    enum class Phase { IDLE, RINGING, OUTGOING_RINGING, ACTIVE, ENDED }

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
         * G11 (§7.1): Freigabe-Pill — Besitzer erlaubt dem Agenten IM Call, den
         * Screen zu steuern (Wischen über den bestehenden interaction-Verband).
         * Je Call frisch (kein Dauer-Grant).
         */
        val agentScreenSteer: Boolean = false,
        /**
         * W23-10: Richtung der Session — true = SIR hat angerufen (outbound,
         * call_request.enc aus dem App-Tap); false/Default = eingehender
         * Agent-Anruf (Bestand — kein Disk-Format-Bruch im Verlauf).
         */
        val outbound: Boolean = false,
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

    /** W23-10: Outbound-Anfrage Phone→Agent — im Allowlist-Verband des Relays
     *  (_PHONE_CALL_ENC_FRAMES), dieselben Guards/Fan-out wie die Quittungen. */
    const val FRAME_REQUEST = "call_request.enc"

    /**
     * ct-Felder der Outbound-Anfrage (E2E — wie kind/reason beim Invite):
     * direction = "outbound-app" markiert die SIR-Richtung für den Adapter,
     * caller_device = die EIGENE Geräte-ID (Parität PairingManager.deviceId()
     * im Handshake). Klar bleiben weiterhin nur IDs.
     */
    const val CALL_DIRECTION_FIELD = "direction"
    const val CALL_DIRECTION_APP_OUTBOUND = "outbound-app"
    const val CALL_CALLER_DEVICE_FIELD = "caller_device"

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
        if (current != null && current.phase == Phase.OUTGOING_RINGING) {
            // W23-10: ein AUSGEHENDER Anruf läuft — fremder Invite = neuer Call
            // (Busy-Abweisung des NEUEN Calls), Duplikat derselben call_id des
            // EIGENEN Agenten (Spool-Flush) hält das Rufen. Der PICKUP
            // (abgenommen) ist oben in outboundPickup abgespalten.
            return if (current.callId == invite.callId && current.agentId == invite.agentId?.trim()) {
                InviteDecision.Refreshed
            } else {
                InviteDecision.Busy
            }
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

    // ── W23-10: OUTBOUND — Besitzer ruft den Agenten (call_request.enc) ───────────

    /**
     * call_id des PHONES für den Outbound-Anruf: hex(Bytes) — Form Parität
     * tools/android_relay.py _CALL_ID_RE (8-64 Zeichen [A-Za-z0-9_-], validiert
     * per [validCallId]). bytes liefert der Aufrufer (CryptoEngine.
     * secureRandomBytes); diese Regel bleibt JVM-rein.
     */
    fun newCallId(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    /**
     * Outbound-Session aus dem App-Tap: fail-closed gegen Form-Mist —
     * ungültige call_id oder leere agent-/chat_id → null (KEIN Frame, KEIN
     * leeres Klingeln). reason bleibt bewusst leer (Besitzer wählt am Tap, kein
     * Klingel-Text), direction im ct trägt die Richtung, nicht die Session.
     */
    fun outboundSession(
        callId: String?,
        agentId: String?,
        chatId: String?,
        nowMs: Long,
        kind: String = "voice",
    ): CallSession? {
        if (!validCallId(callId) || agentId.isNullOrBlank() || chatId.isNullOrBlank()) {
            return null
        }
        return CallSession(
            callId = callId!!.trim(),
            agentId = agentId.trim(),
            chatId = chatId.trim(),
            kind = sanitizeKind(kind),
            reason = "",
            phase = Phase.OUTGOING_RINGING,
            ringingSinceMs = nowMs,
            outbound = true,
        )
    }

    /**
     * Genau EIN Anruf je Gerät (Parität inviteDecision Busy): der Outbound-Tap
     * ist erlaubt NUR ohne laufenden Anruf — RINGING/OUTGOING_RINGING/ACTIVE
     * schließen ab; eine ENDED-Session (beendet/10-s-Fenster) hindert nicht.
     */
    fun outboundRequestAllowed(current: CallSession?): Boolean =
        current == null || current.phase == Phase.ENDED

    /**
     * Der Agent nimmt den Outbound-Call ab: er antwortet (Adapter-Verband)
     * mit dem BESTEHENDEN call_invite.enc — dieselbe call_id, derselbe Agent.
     * Nur genau dieses Muster transitioniert zu ACTIVE (activeSinceMs =
     * Dauer-Basis); fremde call_id/gespoofter Invite/falscher Agent → null —
     * die normale Invite-Entscheidung (Busy-Abweisung des NEUEN) übernimmt.
     */
    fun outboundPickup(current: CallSession?, invite: InviteSpec, nowMs: Long): CallSession? {
        if (current == null || !current.outbound) return null
        if (current.phase != Phase.OUTGOING_RINGING) return null
        if (invite.callId?.trim() != current.callId) return null
        if (invite.agentId?.trim() != current.agentId) return null
        return current.copy(phase = Phase.ACTIVE, activeSinceMs = nowMs)
    }

    /**
     * Abbrechen während des Rufe-Fensters: OUTGOING_RINGING → ENDED (local).
     * Sonst null — eingehendes Klingeln nutzt [decline], ACTIVE nutzt [hangUp].
     */
    fun cancelOutgoing(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.OUTGOING_RINGING) return null
        return session.copy(
            phase = Phase.ENDED,
            endedAtMs = nowMs,
            endSource = EndSource.LOCAL,
        )
    }

    // ── Übergänge (Kanten des Zustandsautomaten) ─────────────────────────────

    /**
     * Annehmen: RINGING/OUTGOING_RINGING → ACTIVE (§3.4 accepted; W23-10:
     * der Outbound-Pickup läuft über denselben Kanten-Aufruf). Sonst null
     * (fail-closed).
     */
    fun accept(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.RINGING && session.phase != Phase.OUTGOING_RINGING) {
            return null
        }
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
     * Ring-Fenster abgelaufen: RINGING/OUTGOING_RINGING → ENDED (timeout) —
     * das Phone meldet call_missed.enc (§3.4 verpasst; §3.5: Agent fällt auf
     * Chat zurück; W23-10 ausgehend = ehrlich „nicht angenommen“).
     */
    fun ringTimeout(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.RINGING && session.phase != Phase.OUTGOING_RINGING) {
            return null
        }
        return session.copy(
            phase = Phase.ENDED,
            endedAtMs = nowMs,
            endSource = EndSource.TIMEOUT,
        )
    }

    /** Ist das Klingel-/Rufe-Fenster für [session] vorbei? (Timeout-Takt im RelayClient) */
    fun ringWindowPassed(session: CallSession, nowMs: Long): Boolean =
        (session.phase == Phase.RINGING || session.phase == Phase.OUTGOING_RINGING) &&
            nowMs - session.ringingSinceMs >= RING_TIMEOUT_MS

    /**
     * Agent legt auf (call_end.enc): RINGING/OUTGOING_RINGING/ACTIVE mit
     * MATCHENDER call_id → ENDED (agent; W23-10: der Agent kann die
     * ausgehende Anfrage auch ablehnen, bevor Besitzer sie abbricht). Fremde/
     * Spät-call_id → null (verwerfen, Parität Adapter "kein lokaler
     * Anruf-Auftrag").
     */
    fun remoteEnd(session: CallSession, callId: String?, nowMs: Long): CallSession? {
        if (callId != session.callId) return null
        if (session.phase != Phase.RINGING && session.phase != Phase.OUTGOING_RINGING &&
            session.phase != Phase.ACTIVE
        ) {
            return null
        }
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
     * Reconnect/Trennung während eines Calls (P6): RINGING/OUTGOING_RINGING/
     * ACTIVE → ENDED (connection). Senden ist an der toten WS unmöglich —
     * der Adapter holt das über sein Ring-Fenster/Spool-Verwurf selbst
     * (fail-closed dort). Beim AUSGEHENDEN Call (W23-10) holt es das der
     * ADAPTER über sein Request-Fenster.
     */
    fun connectionLost(session: CallSession, nowMs: Long): CallSession? {
        if (session.phase != Phase.RINGING && session.phase != Phase.OUTGOING_RINGING &&
            session.phase != Phase.ACTIVE
        ) {
            return null
        }
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

    // ── W22 A1 (STT on-device v1, §3.2 Variante A „Ton verlässt Handy NICHT") ─

    /**
     * Richtung eines Anrufs im Verlauf. W22 A2 hielt das Feld für die
     * spätere outgoing-Route offen (ohne Disk-Format-Bruch); W23-10 nutzt
     * [CALL_DIRECTION_OUTGOING] tatsächlich (Besitzer ruft den Agenten aus der
     * App an — CallRules.Outbound-Verband + ChatCallInfo.direction).
     */
    const val CALL_DIRECTION_INCOMING = "incoming"
    const val CALL_DIRECTION_OUTGOING = "outgoing"

    /** Chat-Text-Deckel eines STT-Turns (kein Riesendiktat über die Text-Schiene). */
    const val STT_TURN_MAX_CHARS = 2_000

    /**
     * Fehlerklasse eines SpeechRecognizer-Error-Codes (android.speech.
     * SpeechRecognizer.ERROR_* — als reine Ints gepinnt, damit diese Regel
     * JVM-testbar bleibt; die Android-Konstanten liefert CallSpeechRecognizer):
     * - RETRY_SILENT = Stille/OEM-Quirk: neu lauschen, KEIN Modus-Wechsel —
     *   ERROR_SPEECH_TIMEOUT(6), ERROR_NO_MATCH(7), ERROR_RECOGNIZER_BUSY(8).
     * - FATAL = ehrlich aufgeben → sichtbarer Hinweis + Auto-Fallback auf die
     *   Push-to-Talk-Audio-Richtung (kein stiller Tod):
     *   ERROR_NETWORK_TIMEOUT(1), ERROR_NETWORK(2), ERROR_AUDIO(3),
     *   ERROR_SERVER(4), ERROR_CLIENT(5), ERROR_INSUFFICIENT_PERMISSIONS(9).
     *   NETWORK zählt bewusst FATAL: im On-Device-Modus darf KEIN Ton das
     *   Gerät verlassen — ein Netz-Fehler ist ein Abbruch-Grund, kein Retry.
     */
    enum class SttErrorClass { RETRY_SILENT, FATAL }

    fun sttErrorClass(errorCode: Int): SttErrorClass = when (errorCode) {
        SpeechRecognizerErrors.SPEECH_TIMEOUT,
        SpeechRecognizerErrors.NO_MATCH,
        SpeechRecognizerErrors.RECOGNIZER_BUSY,
        -> SttErrorClass.RETRY_SILENT
        else -> SttErrorClass.FATAL
    }

    /** Error-Code-Werte von android.speech.SpeechRecognizer (API-33-Stand). */
    object SpeechRecognizerErrors {
        const val NETWORK_TIMEOUT = 1
        const val NETWORK = 2
        const val AUDIO = 3
        const val SERVER = 4
        const val CLIENT = 5
        const val SPEECH_TIMEOUT = 6
        const val NO_MATCH = 7
        const val RECOGNIZER_BUSY = 8
        const val INSUFFICIENT_PERMISSIONS = 9
    }

    /**
     * A1: STT-Turn-Text normalisieren — trim, Whitespace falten, Cap
     * [STT_TURN_MAX_CHARS]. null = nichts sendbares (Stille/leerer Chunk)
     * — der Aufrufer sendet dann NICHT (kein leerer Chat-Turn).
     */
    fun sttTurnText(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.take(STT_TURN_MAX_CHARS)
    }

    // ── W22 A2 Fix 2 (Reviewer-A-Fund, HOCH): OEM-BUSY-Restart-Deckel ────────
    // Ohne Deckel läuft RETRY_SILENT (Stille/NO_MATCH/RECOGNIZER_BUSY) als
    // unbegrenzte ~3 Hz Create/Destroy-Schleife — kein Backoff, keine Eskala-
    // tion, die UI behauptet weiter „lauscht". Diese Regel-Paar gibt der
    // Restart-Schleife eine Treppe und ein Ende:
    //
    // - nextRestartDelayMs(attemptsDone): Verzögerung für den (attemptsDone+1)-ten
    //   Silent-Restart nach der bereits geschehenen Restart-Zahl — Treppe
    //   300 → 600 → 1200 → 2400 ms, dann Deckel auf 2400 ms.
    // - shouldEscalateStt(attemptsDone): ab MAX_ATTEMPTS aufeinanderfolgenden
    //   Silent-Restarts ohne Erfolg (neuer Erfolg = Zähler-Reset bei
    //   onBeginningOfSpeech/onResults) schaltet die Freihand-Erkennung SICHTBAR
    //   auf FATAL (Hinweis + Push-to-Talk-Fallback) — kein stiller Tod, kein
    //   Mikro-Claim im Endlos-Loop.
    /** Start-Treppenelement der Silent-Restart-Verzögerung. */
    const val STT_RESTART_BASE_DELAY_MS = 300L

    /**
     * Silent-Restarts in Folge, bevor ehrlich FATAL eskaliert wird — bei der
     * Treppensumme 300+600+1200+2400 ms (4 Versuche) eskaliert der 5. Fehler.
     */
    const val STT_RESTART_MAX_ATTEMPTS = 5

    /** Verzögerung für den nächsten Silent-Restart (Treppe mit 2400-ms-Deckel). */
    fun nextRestartDelayMs(attemptsDone: Int): Long =
        STT_RESTART_BASE_DELAY_MS shl attemptsDone.coerceIn(0, 3)

    /**
     * Ehrlich aufgeben? true ab [STT_RESTART_MAX_ATTEMPTS] aufeinanderfolgenden
     * Silent-Restarts (der Aufrufer eskaliert dann auf den FATAL-Weg).
     */
    fun shouldEscalateStt(attemptsDone: Int): Boolean =
        attemptsDone >= STT_RESTART_MAX_ATTEMPTS

    // ── W22 A2 (P5): Call-Verlauf im Chat (WhatsApp-Referenz) ────────────────

    /**
     * A2: Verlaufs-Spezifikation eines beendeten Calls — die UI/Platte leitet
     * daraus Anruf-Bubble („Anruf (2:41)") bzw. Verpasst-Zeile ab.
     * null = kein Verlaufs-Eintrag.
     */
    data class CallRecord(
        val direction: String,
        val missed: Boolean,
        val durationSec: Long,
    )

    /**
     * A2/W23-10: Entscheidung aus einer BEENDETEN Session, ob und was der
     * Verlauf festhält (JVM-rein, UI/Store wenden sie nur an):
     * - recordEnabled = false (Settings-Toggle „Anruf-Verlauf aufzeichnen“,
     *   default an) → null — der Anruf hinterlässt KEINE Spur.
     * - Angenommen & beendet (activeSince != null, egal wer aufgelegt hat:
     *   AGENT/LOCAL/CONNECTION) → Bubble mit ECHTER Dauer
     *   (endedAt − activeSince, min 0 — negative Uhr-Sprünge lügen nie);
     *   die Richtung kommt aus dem Session-Flag ([CallSession.outbound]).
     * - Nie angenommen + Ausgehend + LOCAL (Abbruch-Tap beim Rauf- oder
     *   Abhören) → null (Nutzer-Entscheidung, ehrlich ohne Spur — Parität
     *   eingehend DECLINED).
     * - Nie angenommen + eingehend + DECLINED → null (bewusst: die ABLEHNUNG
     *   ist eine Nutzer-Entscheidung, kein verpasster Anruf; der Agent fängt
     *   decline adapter-seitig mit seinem Chat-Fallback selbst).
     * - Nie angenommen + sonstige (TIMEOUT = verpasst/nicht angenommen,
     *   AGENT/CONNECTION während des Klingelns) → Verpasst-Zeile
     *   (missed = true, Dauer 0).
     * Dauer liefert das PHONE lokal (activeSinceMs/endedAtMs) — das Backend
     * schickt im call_end.enc KEIN Dauer-Feld (Beweis adapter.py call_end:
     * encrypt_frame(..., {}) — Hook-Bedarf nur, falls künftig der AGENT
     * eine eigene Dauer-Doku braucht).
     */
    fun callRecordFor(
        session: CallSession?,
        recordEnabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): CallRecord? {
        if (!recordEnabled) return null
        if (session == null || session.phase != Phase.ENDED) return null
        val direction = if (session.outbound) {
            CALL_DIRECTION_OUTGOING
        } else {
            CALL_DIRECTION_INCOMING
        }
        val accepted = session.activeSinceMs
        return when {
            accepted != null -> CallRecord(
                direction = direction,
                missed = false,
                durationSec = maxOf(0L, ((session.endedAtMs ?: nowMs) - accepted) / 1000L),
            )
            session.outbound && session.endSource == EndSource.LOCAL -> null
            session.endSource == EndSource.DECLINED -> null
            else -> CallRecord(
                direction = direction,
                missed = true,
                durationSec = 0L,
            )
        }
    }
}
