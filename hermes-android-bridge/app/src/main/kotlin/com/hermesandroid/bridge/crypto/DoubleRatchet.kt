package com.hermesandroid.bridge.crypto

import java.security.SecureRandom

/** PH-3: Ratchet-/Frame-Fehler — NIE mit Klartext-Inhalt in der Nachricht. */
class CryptoFrameException(message: String) : RuntimeException(message)

/** PH-3: Ratchet-Zustand unbenutzbar (z. B. keine Sende-Kette). */
class RatchetException(message: String) : RuntimeException(message)

/**
 * PH-3 Welle 2 — VOLLES Double Ratchet, Signal-Referenz-Parametrisierung.
 *
 * Byte-exakter Port der Python-Referenz (`contrib/hermes-chat-plugin/adapter.py`
 * → `DoubleRatchet`, PhoneCryptoPeer = Frame-Vorlage) gegen
 * secrets-docs/PH3-KDF-FIXTURES-v1.md §5.4:
 *
 * KDF_RK(rk, dh_out) : okm = HKDF-SHA256(salt=rk, ikm=dh_out, info="fb-ratchet", 64)
 *                      → (rk' = okm[0:32], ck = okm[32:64])
 * KDF_CK(ck)         : mk = HMAC(ck, 0x01), ck' = HMAC(ck, 0x02)
 * MsgAES(mk)         : key = HKDF(salt="fb-msgkey", ikm=mk, info="fb-aes256", 32)
 * Nonce (12 B)       : chain_prefix(4 B je Kette) ‖ Ns (8 B big-endian)
 * hdr                : {"rk": <eigene aktuelle Ratchet-Pub hex>, "n": Ns, "pn": PN}
 *
 * ROTATIONS-KADENZ (§5.4, bindend — am Python-Trace bewiesen): DH-Rotation
 * geschieht (a) automatisch JE Peer-Announcement (jede neue Ratchet-Pub im
 * hdr.rk rotiert beide Ketten — Signal-Lockstep, 2 RK-Advances) und (b) je
 * Session (frische crypto.hello). Spontane Eigen-Rotation ist VERBOTEN (bricht
 * die RK-Parität: 1-Advance passt nie auf die 2-Advance-Schritte des Peers →
 * GCM-Desync). rotate_every setzt daher NUR einen HINWEIS (hdr.rot = 1) nach N
 * gesendeten Frames ohne DH-Schritt — der Empfänger kann mit einer frischen
 * crypto.hello antworten (Session-Rotate).
 *
 * Replay-Schutz: Skipped-Cache 100, Fenster 100 Counter + 10 min Frame-Alter
 * (Altersprüfung in der Session-Schicht), Nonce an den Zähler gebunden,
 * gesehene Peer-Ratchet-Pubs (4) = Old-Chain-Replay-Drop.
 *
 * Test-Hooks: keyGen/noncePrefixGen/now injizierbar (Vektor-Tests); Produktion
 * = SecureRandom-X25519. Diese Klasse loggt NIEMALS Key-Material.
 */
class DoubleRatchet(
    rootKey: ByteArray,
    private val rotateEvery: Int = RATCHET_ROTATE_EVERY,
    private val keyGen: () -> CryptoEngine.X25519KeyPair = { CryptoEngine.x25519Generate() },
    private val noncePrefixGen: () -> ByteArray = { CryptoEngine.secureRandomBytes(4) },
) {

    companion object {
        const val REPLAY_WINDOW = 100
        const val SKIPPED_KEY_CACHE_MAX = 100
        const val SEEN_PEER_PUBS_MAX = 4
        const val RATCHET_ROTATE_EVERY = 100
        private val KDF_RK_INFO = CryptoEngine.utf8("fb-ratchet")
        private val MSGKEY_SALT = CryptoEngine.utf8("fb-msgkey")
        private val MSGKEY_INFO = CryptoEngine.utf8("fb-aes256")
    }

    /** Verschlüsselter Frame: Ratchet-Header (rk hex, n, pn, rot-Hinweis) + Nonce + ct‖tag. */
    data class Encrypted(
        val rkHex: String,
        val n: Int,
        val pn: Int,
        val rotHint: Boolean,
        val nonce: ByteArray,
        val ct: ByteArray,
    )

    // ── Zustand (nur Heap — Session-Keys werden nie persistiert) ────────────
    private var rk: ByteArray = rootKey
    private var ckS: ByteArray? = null
    private var ckR: ByteArray? = null
    private var dhPriv: ByteArray? = null
    private var dhPub: ByteArray? = null
    private var dhR: ByteArray? = null
    private var ns = 0
    private var nr = 0
    private var pn = 0
    private var noncePrefix: ByteArray = noncePrefixGen()
    private var sentSinceDh = 0
    private val rotateEverySafe = rotateEvery.coerceAtLeast(1)

    /** Skipped-Keys: (peerRkHex, n) → mk; Einfüge-Reihenfolge = FIFO-Verdrängung. */
    private val skipped = LinkedHashMap<Pair<String, Int>, ByteArray>()

    /** Gesehene Peer-Ratchet-Pubs (4) — Old-Chain-Replay-Drop. */
    private val seenPeerPubs = ArrayDeque<String>()

    // ── Init (Rollen) ────────────────────────────────────────────────────────

    /** Agent nach X3DH: RA-Pair + Sende-Kette aus X25519(RA, EK_p). */
    fun initResponder(peerEkPub: ByteArray) {
        val pair = keyGen()
        dhPriv = pair.priv
        dhPub = pair.pub
        dhR = peerEkPub
        val (newRk, ckS) = kdfRk(rk, CryptoEngine.x25519Dh(pair.priv, peerEkPub))
        rk = newRk
        this.ckS = ckS
        ns = 0
        pn = 0
        nr = 0
    }

    /**
     * Phone nach hello_ack-Verify (Signal-Alice-Init, EIN Advance):
     * RK1, CKr = KDF_RK(root0, X25519(EK, RA)) = Agent-Sendekette.
     * ckS bleibt null — die erste eigene Sende-Nachricht macht den
     * Alice-Sendeschritt (frisches Pair gegen RA), siehe [encrypt].
     */
    fun initInitiator(ownEkPriv: ByteArray, ownEkPub: ByteArray, peerRatchetPub: ByteArray) {
        dhPriv = ownEkPriv
        dhPub = ownEkPub
        dhR = peerRatchetPub
        val (newRk, ckR) = kdfRk(rk, CryptoEngine.x25519Dh(ownEkPriv, peerRatchetPub))
        rk = newRk
        this.ckR = ckR
        nr = 0
        ns = 0
        pn = 0
    }

    /** Session-Rotate: Wurzel neu, Rest zurücksetzen (frische crypto.hello). */
    fun resetRoot(newRootKey: ByteArray) {
        rk = newRootKey
        ckS = null
        ckR = null
        dhPriv = null
        dhPub = null
        dhR = null
        ns = 0
        nr = 0
        pn = 0
        sentSinceDh = 0
        skipped.clear()
        seenPeerPubs.clear()
        noncePrefix = noncePrefixGen()
    }

    // ── interne Schritte ─────────────────────────────────────────────────────

    /** Empfangs-Schritt (neuer Peer-Key): beide Ketten rotieren (Signal-Lockstep). */
    private fun dhRatchet(remotePub: ByteArray) {
        dhR?.let { trackPeerPub(CryptoEngine.hex(it)) }
        dhR = remotePub
        val ownPriv = dhPriv ?: throw RatchetException("kein eigenes Pair — zuerst Handshake")
        val (rk1, ckR1) = kdfRk(rk, CryptoEngine.x25519Dh(ownPriv, remotePub))
        rk = rk1
        ckR = ckR1
        nr = 0
        pn = ns // Länge der bisherigen Sende-Kette (für hdr.pn)
        val pair = keyGen()
        dhPriv = pair.priv
        dhPub = pair.pub
        val (rk2, ckS1) = kdfRk(rk, CryptoEngine.x25519Dh(pair.priv, remotePub))
        rk = rk2
        ckS = ckS1
        ns = 0
        noncePrefix = noncePrefixGen()
        sentSinceDh = 0 // Ratchet-Schritt = frische Rotation der Ketten
    }

    /**
     * Rotation: frisches Pair gegen den aktuellen Peer-Key. Aufruf NUR für die
     * erste Phone-Sende-Nachricht (ckS = null, Signal-Alice-Sendeschritt) —
     * Mittendrin-Spontan-Rotation ist verboten (RK-Parität, s. Klassen-Doc).
     */
    private fun rotateSendChain() {
        val peer = dhR ?: throw RatchetException("kein Peer-Key — zuerst Handshake")
        pn = ns
        val pair = keyGen()
        dhPriv = pair.priv
        dhPub = pair.pub
        val (newRk, ckS1) = kdfRk(rk, CryptoEngine.x25519Dh(pair.priv, peer))
        rk = newRk
        ckS = ckS1
        ns = 0
        noncePrefix = noncePrefixGen()
    }

    private fun trackPeerPub(pubHex: String) {
        if (pubHex !in seenPeerPubs) {
            seenPeerPubs.addLast(pubHex)
            while (seenPeerPubs.size > SEEN_PEER_PUBS_MAX) {
                seenPeerPubs.removeFirst()
            }
        }
    }

    /** Übersprungene Message-Keys [nr, until) ableiten + cachen (Cap 100, FIFO). */
    private fun skipMessageKeys(until: Int, remoteHex: String) {
        val chain = ckR ?: return
        if (until <= nr) return
        var ck = chain
        while (nr < until) {
            if (skipped.size >= SKIPPED_KEY_CACHE_MAX) {
                skipped.remove(skipped.keys.first())
            }
            val (nextCk, mk) = kdfCk(ck)
            ck = nextCk
            skipped[remoteHex to nr] = mk
            nr++
        }
        ckR = ck
    }

    // ── Chiffre ──────────────────────────────────────────────────────────────

    /** Sendet: (hdr, nonce, ct). Python-Referenz encrypt() byte-exakt. */
    fun encrypt(payload: ByteArray, aad: ByteArray): Encrypted {
        if (dhPub == null) {
            throw RatchetException("kein eigenes Ratchet-Pair — zuerst Handshake")
        }
        if (ckS == null) {
            rotateSendChain() // erste Phone-Sende-Nachricht (Alice-Schritt)
        }
        val pub = dhPub!!
        val (nextCk, mk) = kdfCk(ckS!!)
        ckS = nextCk
        val nonce = noncePrefix + CryptoEngine.longTo8BigEndian(ns.toLong())
        val ct = CryptoEngine.aesGcmEncrypt(messageKey(mk), nonce, payload, aad)
        sentSinceDh++
        val rotHint = sentSinceDh % rotateEverySafe == 0
        val result = Encrypted(
            rkHex = CryptoEngine.hex(pub),
            n = ns,
            pn = pn,
            rotHint = rotHint,
            nonce = nonce,
            ct = ct,
        )
        ns++
        return result
    }

    /** Empfängt: Python-Referenz decrypt() Reihenfolge exakt. Wirft bei JEDEM Fehlschlag. */
    fun decrypt(header: EncryptedHeader, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val remote = header.rk
        val n = header.n
        val pn = header.pn
        if (nonce.size != 12 || !nonce.copyOfRange(4, 12).contentEquals(CryptoEngine.longTo8BigEndian(n.toLong()))) {
            throw CryptoFrameException("Nonce passt nicht zum Zähler")
        }

        // 1) Out-of-Order aus dem Skipped-Cache (Verbrauch = Replay-Schutz).
        val cached = skipped.remove(CryptoEngine.hex(remote) to n)
        if (cached != null) {
            return CryptoEngine.aesGcmDecrypt(messageKey(cached), nonce, ct, aad)
        }

        // 2) Alter Peer-Key = Replay (kein Zustands-Konsum, gerader Drop).
        if (dhR != null && !remote.contentEquals(dhR)) {
            if (CryptoEngine.hex(remote) in seenPeerPubs) {
                throw CryptoFrameException("alter Ratchet-Key (Replay)")
            }
            // PH-3 F2 (Jäger P2): pn-Cap — hdr.pn ist ANGREIFER-KONTROLLIERT
            // (bis 2³¹); ohne Cap leitet skipMessageKeys Milliarden
            // Message-Keys ab (CPU/RAM-DoS). Grenze = Empfangs-Fenster
            // (Parität: adapter.py DoubleRatchet.decrypt, gleiche Meldung).
            if (pn > nr + REPLAY_WINDOW) {
                throw CryptoFrameException("pn über Fenster (>100)")
            }
            skipMessageKeys(pn, CryptoEngine.hex(dhR!!))
            dhRatchet(remote)
        }
        if (ckR == null) {
            throw CryptoFrameException("keine Empfangs-Kette")
        }

        // 3) Fenster-Prüfung (100 Counter) + Ablauf der aktuellen Kette.
        if (n < nr) {
            throw CryptoFrameException("Zähler unter Fenster (Replay)")
        }
        if (n >= nr + REPLAY_WINDOW) {
            throw CryptoFrameException("Zähler über Fenster (>100)")
        }
        skipMessageKeys(n, CryptoEngine.hex(remote))
        // Kette ERST NACH dem Skip lesen (Python-Referenz: self.ck_r nach
        // _skip_message_keys) — Out-of-Order advanced ckR, ein stale Local
        // wäre der falsche Message-Key (GCM-Desync, am OOO-Trace bewiesen).
        val current = ckR ?: throw CryptoFrameException("keine Empfangs-Kette")
        val (nextCk, mk) = kdfCk(current)
        ckR = nextCk
        nr = n + 1
        return CryptoEngine.aesGcmDecrypt(messageKey(mk), nonce, ct, aad)
    }

    /** Für Tests: eigene aktuelle Ratchet-Pub (kex_pub im hello_ack). */
    internal fun dhPubForTest(): ByteArray? = dhPub

    /** Für Tests: aktuelle Sende-Ketten-CK (CKs_phone-Vektor §5.7). */
    internal fun sendChainKeyForTest(): ByteArray? = ckS

    /** Für Tests: Zustand nach initInitiator (RK2/CKs_phone-Vektoren §5.7). */
    internal fun forceRotateSendChainForTest() = rotateSendChain()

    /** Für Tests: aktueller Root-Key (nur Vektor-Verifikation, nie loggen). */
    internal fun rootKeyForTest(): ByteArray = rk

    // ── KDFs (§5.4, byte-exakt) ──────────────────────────────────────────────

    internal fun kdfRk(rkIn: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = CryptoEngine.hkdfSha256(salt = rkIn, ikm = dhOut, info = KDF_RK_INFO, length = 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    internal fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> =
        CryptoEngine.hmacSha256(ck, byteArrayOf(0x02)) to
            CryptoEngine.hmacSha256(ck, byteArrayOf(0x01))

    internal fun messageKey(mk: ByteArray): ByteArray =
        CryptoEngine.hkdfSha256(salt = MSGKEY_SALT, ikm = mk, info = MSGKEY_INFO, length = 32)

    /** Geparster Ratchet-Header (rk = 32 B, n/pn Zähler, rot = reiner Hinweis). */
    data class EncryptedHeader(val rk: ByteArray, val n: Int, val pn: Int, val rot: Boolean = false)
}