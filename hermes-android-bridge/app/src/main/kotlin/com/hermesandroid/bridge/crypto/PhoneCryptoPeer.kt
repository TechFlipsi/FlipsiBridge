package com.hermesandroid.bridge.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * fb-v1-Ableitung (PH3-KDF-FIXTURES §5.2, byte-exakt — von Phone UND
 * Agent-Seite benutzt, top-level damit beide Rollen dieselbe Kette teilen):
 * transcript = SHA256("flipsi-v1" ‖ session_id ‖ IK_p ‖ EK_p ‖ SPK_p ‖ IK_a ‖
 *                    SPK_a ‖ agent_id) — Reihenfolge BINDEND.
 * psk_comp = HMAC(transcript, psk) · PRK = HKDF-Extract(transcript, DH1‖DH2‖DH3‖psk_comp)
 * context = IK_p ‖ EK_p ‖ SPK_p ‖ IK_a ‖ SPK_a ‖ agent_id ‖ session_id.
 */
internal data class FbV1Keys(
    val transcript: ByteArray,
    val prk: ByteArray,
    val root0: ByteArray,
    val kInit: ByteArray,
    val sessionFpHex: String,
)

internal fun fbV1Kdf(
    dh1: ByteArray, dh2: ByteArray, dh3: ByteArray,
    psk: ByteArray, sessionId: String, agentId: String,
    ikP: ByteArray, ekP: ByteArray, spkP: ByteArray,
    ikA: ByteArray, spkA: ByteArray,
): FbV1Keys {
    val transcript = CryptoEngine.sha256(
        CryptoEngine.utf8("flipsi-v1") + CryptoEngine.utf8(sessionId) +
            ikP + ekP + spkP + ikA + spkA + CryptoEngine.utf8(agentId)
    )
    val pskComp = CryptoEngine.hmacSha256(transcript, psk)
    val prk = CryptoEngine.hkdfExtract(transcript, dh1 + dh2 + dh3 + pskComp)
    val context = ikP + ekP + spkP + ikA + spkA +
        CryptoEngine.utf8(agentId) + CryptoEngine.utf8(sessionId)
    return FbV1Keys(
        transcript = transcript,
        prk = prk,
        root0 = CryptoEngine.hkdfExpand(prk, CryptoEngine.utf8("fb-root") + context, 32),
        kInit = CryptoEngine.hkdfExpand(prk, CryptoEngine.utf8("fb-init") + context, 32),
        sessionFpHex = CryptoEngine.hex(
            CryptoEngine.hkdfExpand(prk, CryptoEngine.utf8("fb-fp") + context, 32)
        ),
    )
}

/**
 * PH-3 Welle 2 — Phone-Initiator (App-Seite des fb-v1-Handshakes).
 *
 * Kotlin-Gegenstück der Python-REFERENZ `PhoneCryptoPeer`
 * (contrib/hermes-chat-plugin/adapter.py) — Frame-Formate EXAKT identisch:
 * - crypto.hello: {type, v, session_id, agent_id, ik_pub, ek_pub, spk_pub, ts}
 * - crypto.hello_ack (Verifikation): {session_id, agent_id, kex_pub, nonce, ct}
 * - chat.*.enc: {type, v, session_id, chat_id, agent_id, hdr{rk,n,pn,rot?},
 *                 nonce(b64 12B), ct(b64), ts} — AAD bindet chat_id, der
 *   Relay benennt chat.reply.enc → chat.event.enc um (Typ bewusst NICHT im AAD).
 *
 * fb-v1-Ableitung (PH3-KDF-FIXTURES §5.2, byte-exakt):
 * transcript = SHA256("flipsi-v1" ‖ session_id ‖ IK_p ‖ EK_p ‖ SPK_p ‖ IK_a ‖
 *                    SPK_a ‖ agent_id)   (Reihenfolge BINDEND)
 * psk_comp   = HMAC(transcript, psk) · PRK = HKDF-Extract(transcript, DH1‖DH2‖DH3‖psk_comp)
 * root0 = HKDF-Expand(PRK, "fb-root"‖context, 32) · k_init = "fb-init" ·
 * session_fp = "fb-fp" · context = IK_p ‖ EK_p ‖ SPK_p ‖ IK_a ‖ SPK_a ‖ agent_id ‖ session_id
 * k_init GENAU EINMAL: hello_ack-Verify. Danach nur Ratchet-Keys.
 *
 * Wrong-Key/PSK: hello_ack-Verify scheitert → Session verwerfen, KEIN
 * Cleartext-Fallback (§6). Jeder Entschlüsselungs-Fehlschlag: konstante
 * 0,25 s Verzögerung + Fehler OHNE Inhalt (kein Timing-/Text-Orakel).
 */
class PhoneCryptoPeer(
    val agentId: String,
    agentIkPubHex: String,
    agentSpkPubHex: String,
    /** Phone-Identity aus dem IdentityStore (persistent — Fingerprint-Stabilität). */
    private val identityIk: CryptoEngine.X25519KeyPair? = null,
    private val identitySpk: CryptoEngine.X25519KeyPair? = null,
    private val psk: ByteArray = ByteArray(0),
    private val rotateEvery: Int = DoubleRatchet.RATCHET_ROTATE_EVERY,
    private val keyGen: () -> CryptoEngine.X25519KeyPair = { CryptoEngine.x25519Generate() },
) {

    companion object {
        const val PROTOCOL_VERSION = 1
        const val FRAME_MAX_AGE_MS = 10 * 60 * 1000L          // Replay-Fenster 100 + 10 min
        const val SESSION_MAX_AGE_MS = 24 * 60 * 60 * 1000L   // 24-h-Session
        const val DECRYPT_FAIL_DELAY_MS = 250L               // konstante Fehlverzögerung
        const val ENC_CT_MAX_BYTES = 512 * 1024
        const val FP_PREFIX = "FP-ABGLEICH:"
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    }

    val agentIkPub: ByteArray = hex32(agentIkPubHex)
    val agentSpkPub: ByteArray = hex32(agentSpkPubHex)

    /** Phone-Identity (langfristig aus dem IdentityStore, sonst frisch je Peer). */
    val ik: CryptoEngine.X25519KeyPair = identityIk ?: keyGen()
    val spk: CryptoEngine.X25519KeyPair = identitySpk ?: keyGen()

    var sessionId: String? = null
        private set
    var chatId: String? = null
        private set

    /** session_fp (64 Hex) — identisch auf beiden Seiten (Abgleich-Wert). */
    var fingerprint: String? = null
        private set

    private var ek: CryptoEngine.X25519KeyPair? = null
    private var pending: FbV1Keys? = null
    private var ratchet: DoubleRatchet? = null
    private var createdAtMs: Long = 0
    private var acked: Boolean = false

    /** E2E-Session aktiv (hello_ack VERIFIZIERT, < 24 h)? — makeHello reicht NICHT. */
    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        acked && nowMs - createdAtMs < SESSION_MAX_AGE_MS && nowMs - createdAtMs >= 0

    /** Agent-Identity-Fingerprint (aus /key_get / Settings-Anzeige). */
    val agentFingerprint: String = CryptoEngine.fingerprintOfPub(agentIkPub)

    /** FP-Abgleich-Nachricht exakt wie Sirs Revision (iii): 'FP-ABGLEICH: <64 hex>'. */
    fun fpAbgleichText(): String {
        val fp = fingerprint ?: return ""
        return "$FP_PREFIX $fp"
    }

    // ── X3DH-vereinfacht: crypto.hello ───────────────────────────────────────

    /**
     * Handshake-Start: frisches EK, fb-v1-Ableitung, DoubleRatchet-Wurzel.
     * chatId = phone-gewählte Konversations-ID (8–128 Z.; AAD bindet daran —
     * der Relay ersetzt sie im enc-Modus NICHT).
     */
    /**
     * P1b-FIX (vc36-Drill, 02.11.): Der WS-Empfangspfad dispatcht JEDE Frame
     * auf einer eigenen Dispatchers.IO-Coroutine — die Agenten-Spiegel + An-
     * twort (read/activity/reply) treffen SIMULTAN ein. Der DoubleRatchet ist
     * eine nicht-threadsichere Zustandsmaschine: 4 parallele decryptFrame-
     * Aufrufe interleave dhRatchet/KDF-CK-Mutationen → die Ketten desyncen
     * → JEDE Entschlüsselung schlägt fehl (live bewiesen: 3 Thread-IDs in
     * 6 ms, alle Frames n=0..3 korrekt lt. Agent-FRAMELOG; sequenziell lief
     * derselbe Trace im JVM-Test grün). Fix: JEDE Ratchet-berührende Methode
     * sperrt auf der Peer-Instanz (per-Peer-Serialisierung; Out-of-Order
     * bleibt erlaubt — der Skipped-Key-Cache deckt jede Reihenfolge ab).
     */
    @Synchronized
    fun makeHello(sessionIdIn: String, chatIdIn: String, nowMs: Long = System.currentTimeMillis()): JsonObject {
        require(sessionIdIn.length in 8..128) { "session_id 8-128 Zeichen" }
        require(chatIdIn.length in 8..128) { "chat_id 8-128 Zeichen" }
        val ekPair = keyGen()
        ek = ekPair
        sessionId = sessionIdIn
        chatId = chatIdIn
        val kdf = fbV1Kdf(
            dh1 = CryptoEngine.x25519Dh(ekPair.priv, agentIkPub),
            dh2 = CryptoEngine.x25519Dh(ik.priv, agentIkPub),
            dh3 = CryptoEngine.x25519Dh(ekPair.priv, agentSpkPub),
            psk = psk, sessionId = sessionIdIn, agentId = agentId,
            ikP = ik.pub, ekP = ekPair.pub, spkP = spk.pub,
            ikA = agentIkPub, spkA = agentSpkPub,
        )
        pending = kdf
        fingerprint = kdf.sessionFpHex
        ratchet = DoubleRatchet(kdf.root0, rotateEvery = rotateEvery, keyGen = keyGen)
        createdAtMs = nowMs
        return JsonObject().apply {
            addProperty("type", "crypto.hello")
            addProperty("v", PROTOCOL_VERSION)
            addProperty("session_id", sessionIdIn)
            addProperty("agent_id", agentId)
            addProperty("ik_pub", CryptoEngine.hex(ik.pub))
            addProperty("ek_pub", CryptoEngine.hex(ekPair.pub))
            addProperty("spk_pub", CryptoEngine.hex(spk.pub))
            addProperty("ts", nowMs)
        }
    }

    // ── hello_ack-Verify (k_init GENAU EINMAL) ───────────────────────────────

    /**
     * hello_ack verifizieren — Wrong-Key/MITM/PSK = CryptoFrameException,
     * Session verwerfen (KEIN Fallback). Bei Erfolg: Signal-Alice-Init.
     */
    @Synchronized
    fun consumeAck(ack: JsonObject, nowMs: Long = System.currentTimeMillis()) {
        val kdf = pending ?: throw CryptoFrameException("consumeAck vor makeHello")
        val ekPair = ek ?: throw CryptoFrameException("consumeAck vor makeHello")
        val sid = sessionId ?: throw CryptoFrameException("consumeAck vor makeHello")
        if (ack.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString != sid) {
            throw CryptoFrameException("fremde session_id")
        }
        val kexPub = hex32(ack.get("kex_pub")?.takeIf { it.isJsonPrimitive }?.asString)
        val nonce = runCatching {
            CryptoEngine.b64d(ack.get("nonce")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
        }.getOrElse { throw CryptoFrameException("hello_ack-Nonce ungültig") }
        if (nonce.size != 12) throw CryptoFrameException("hello_ack-Nonce nicht 12 Byte")
        val ct = runCatching {
            CryptoEngine.b64d(ack.get("ct")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
        }.getOrElse { throw CryptoFrameException("hello_ack-ct ungültig") }
        val aad = CryptoEngine.utf8("fb-v1|$sid|$agentId")
        val pt = try {
            CryptoEngine.aesGcmDecrypt(kdf.kInit, nonce, ct, aad)
        } catch (e: Exception) {
            throw CryptoFrameException("hello_ack-Verify fehlgeschlagen (Wrong-Key/PSK)")
        }
        if (!pt.contentEquals(CryptoEngine.utf8("flipsi-verify-v1|$sid"))) {
            throw CryptoFrameException("hello_ack-Payload falsch")
        }
        ratchet?.initInitiator(ekPair.priv, ekPair.pub, kexPub)
        createdAtMs = nowMs
        acked = true
        pending = null // k_init nur für den Handshake — danach verwerfen
    }

    // ── chat.*.enc-Frames ───────────────────────────────────────────────────

    /**
     * Verschlüsselt einen Klartext-Payload (canonical JSON, sortierte Keys,
     * compact — exakt wie Python json.dumps(sort_keys=True, separators)) in
     * einen chat.*.enc-Frame. Klartext bleibt intern-only, nie geloggt.
     */
    @Synchronized
    fun encryptFrame(
        frameType: String,
        chatIdIn: String,
        payload: Map<String, Any?>,
        nowMs: Long = System.currentTimeMillis(),
    ): JsonObject {
        val r = ratchet ?: throw RatchetException("keine Session — zuerst Handshake")
        val sid = sessionId ?: throw RatchetException("keine session_id")
        val enc = r.encrypt(
            CryptoEngine.utf8(CryptoEngine.canonicalJson(payload)),
            encAad(sid, chatIdIn),
        )
        return JsonObject().apply {
            addProperty("type", frameType)
            addProperty("v", PROTOCOL_VERSION)
            addProperty("session_id", sid)
            addProperty("chat_id", chatIdIn)
            addProperty("agent_id", agentId)
            add("hdr", JsonObject().apply {
                addProperty("rk", enc.rkHex)
                addProperty("n", enc.n)
                addProperty("pn", enc.pn)
                if (enc.rotHint) addProperty("rot", 1)
            })
            addProperty("nonce", CryptoEngine.b64e(enc.nonce))
            addProperty("ct", CryptoEngine.b64e(enc.ct))
            addProperty("ts", nowMs)
        }
    }

    /**
     * chat.*.enc-Frame entschlüsseln → Klartext-Payload (JsonObject). Bei
     * JEDEM Fehlschlag: konstante 0,25 s Verzögerung + Fehler OHNE Inhalt.
     */
    @Synchronized
    fun decryptFrame(frame: JsonObject, nowMs: Long = System.currentTimeMillis()): JsonObject {
        try {
            val sid = sessionId ?: throw CryptoFrameException("keine Session")
            if (frame.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString != sid) {
                throw CryptoFrameException("fremde session_id")
            }
            if (nowMs - createdAtMs >= SESSION_MAX_AGE_MS) {
                throw CryptoFrameException("Session abgelaufen (>24h)")
            }
            // PH-3 F3 (Jäger P2): ts ist PFLICHT — ein fehlender ts übersprang
            // bis jetzt die 10-min-Fenster-Prüfung (Replay-Schutz umgehbar).
            // Parität: adapter.py decrypt_frame, gleiche Meldung.
            val ts = runCatching {
                frame.get("ts")?.takeIf { it.isJsonPrimitive }?.asLong
            }.getOrNull() ?: throw CryptoFrameException("ts fehlt (Pflicht)")
            if (ts < nowMs - FRAME_MAX_AGE_MS) {
                throw CryptoFrameException("Frame zu alt (>10 min)")
            }
            // SEC-W1 W1C-8 (Parität zum Relay-Fix): ts war nur gegen die
            // VERGANGENHEIT geprüft — ein Zukunfts-ts (+1 h) passierte das
            // Freshness-Fenster. Symmetrische Obergrenze ±10 min.
            if (ts > nowMs + FRAME_MAX_AGE_MS) {
                throw CryptoFrameException("Frame-ts in der Zukunft (>10 min)")
            }
            val nonce = runCatching {
                CryptoEngine.b64d(frame.get("nonce")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
            }.getOrElse { throw CryptoFrameException("Nonce nicht base64") }
            if (nonce.size != 12) throw CryptoFrameException("Nonce nicht 12 Byte")
            val ct = runCatching {
                CryptoEngine.b64d(frame.get("ct")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
            }.getOrElse { throw CryptoFrameException("ct nicht base64") }
            if (ct.isEmpty() || ct.size > ENC_CT_MAX_BYTES) {
                throw CryptoFrameException("ct leer/zu groß")
            }
            val hdrElement = frame.get("hdr")
            if (hdrElement == null || !hdrElement.isJsonObject) {
                throw CryptoFrameException("hdr fehlt")
            }
            val hdrJson = hdrElement.asJsonObject
            val rkHex = hdrJson.get("rk")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw CryptoFrameException("hdr.rk fehlt")
            val header = DoubleRatchet.EncryptedHeader(
                rk = hex32(rkHex),
                n = intOf(hdrJson, "n"),
                pn = runCatching { intOf(hdrJson, "pn") }.getOrDefault(0),
            )
            val chatIdIn = frame.get("chat_id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val pt = ratchet!!.decrypt(header, nonce, ct, encAad(sid, chatIdIn))
            return JsonParser.parseString(String(pt, Charsets.UTF_8)).asJsonObject
        } catch (e: CryptoFrameException) {
            sleepDecryptFail()
            throw e
        } catch (e: Exception) {
            sleepDecryptFail()
            throw CryptoFrameException("GCM-Tag ungültig")
        }
    }

    /** hdr.rot = 1 gelesen? (reiner Hinweis — Antwort: frische crypto.hello). */
    fun hasRotateHint(frame: JsonObject): Boolean =
        frame.get("hdr")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("rot")?.takeIf { it.isJsonPrimitive }?.asInt == 1

    // ── interne Helfer ──────────────────────────────────────────────────────

    /** AAD (bindend): 'fb-v1|session_id|chat_id|agent_id' — Typ bewusst NICHT drin. */
    internal fun encAad(sessionId: String, chatId: String): ByteArray =
        CryptoEngine.utf8("fb-v1|$sessionId|$chatId|$agentId")

    private fun intOf(obj: JsonObject, field: String): Int {
        val v = obj.get(field)?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw CryptoFrameException("hdr-Zähler ungültig")
        if (v < 0) throw CryptoFrameException("hdr-Zähler negativ")
        return v
    }

    private fun sleepDecryptFail() {
        try {
            Thread.sleep(DECRYPT_FAIL_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    internal fun hex32(value: String?): ByteArray {
        if (value == null || !HEX64.matches(value)) {
            throw CryptoFrameException("PubKey kein 64-Hex")
        }
        return CryptoEngine.fromHex(value)
    }

    internal fun ratchetForTest(): DoubleRatchet? = ratchet

}
