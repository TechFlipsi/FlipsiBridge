package com.hermesandroid.bridge.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PH-3 Welle 2 — Krypto-Primitive (JDK-only, PH3-CRYPTO-SPEC §1 Option A).
 *
 * EXAKT gegen secrets-docs/PH3-KDF-FIXTURES-v1.md implementiert:
 * - HKDF RFC 5869 (SHA-256) aus `javax.crypto.Mac("HmacSHA256")` (~60 Zeilen,
 *   gegen die RFC-Vektoren A.1–A.3 geprüft → Handroll-Risiko kontrolliert).
 * - X25519 via JDK XDH (`KeyAgreement.getInstance("XDH")`, Raw-Wire-Bytes
 *   RFC 7748: u-Koordinate little-endian). NIEMALS handgerollte
 *   Punktmultiplikation (Spec §1 Bedingung).
 * - AES-256-GCM (96-Bit-Nonce, 128-Bit-Tag, ct = CTR ‖ Tag).
 *
 * P-256-Fallback-Flag (Spec §1 Option A, minSdk 26): XDH-Verfügbarkeit wird
 * pro JVM/Gerät geprüft ([xdhAvailable]). Fehlt XDH, ist E2E deaktiviert
 * (fail-closed, Legacy-Klartext v0.14-kompatibel) — ein echtes P-256-Envelope
 * (größere Raw-Keys) ist die 3b-Entscheidung; hier steht nur der Flag.
 *
 * Klartext-Verbot: diese Klasse loggt NIEMALS Key-Material oder Nutzdaten.
 */
object CryptoEngine {

    /**
     * XDH fehlt auf dieser JVM/diesem Gerät → E2E aus (fail-closed).
     *
     * DRILL-FUND (vc36, Emulator): Conscrypt (Android) unterstützt
     * KeyPairGenerator.initialize(NamedParameterSpec) NICHT — die alte
     * Detection rief initialize auf und meldete XDH fälschlich als fehlend
     * → E2E war auf ALLEN Android-Geräten fail-closed aus, obwohl Conscrypt
     * XDH/X25519 liefert. Detection jetzt ohne initialize (Default von XDH
     * ist auf JDK und Conscrypt X25519; Probe-Beweis am Emulator: KPG_OK,
     * KF_OK, KA_OK).
     */
    val xdhAvailable: Boolean by lazy {
        runCatching {
            KeyPairGenerator.getInstance("XDH")
            KeyFactory.getInstance("XDH")
            true
        }.getOrDefault(false)
    }

    /**
     * P1-FIX (vc36-Drill, JDI-Beweis scratch/e2e/ph3-evidence/fund1-jdi-stack):
     * KeyFactory.getInstance("XDH") liefert auf Android eine libcore-Delegate
     * mit PERMANENTEM Provider-Failover (KeyFactory.nextSpi: wirft der
     * Conscrypt-Spi auf einen Spec, wechselt die Delegate this.spi zum
     * nächsten Provider — im App-Prozess die AndroidKeyStoreKeyFactorySpi,
     * die JEDE Spec ablehnt). Der PKCS8/SPKI-Fallback auf DEMSELBEN kf-
     * Objekt lief dann auf der korruptierten Delegate → InvalidKeySpec-
     * Exception entkam → Handshake deterministisch tot (Shell/app_process
     * hat den Keystore-Provider nicht → dort liefen alle Proben grün).
     *
     * Fix: XDH-Provider einmalig per Selbst-Test ermitteln — der erste
     * Provider, dessen KeyFactory wirklich PKCS8+SPKI parst (Conscrypt
     * „AndroidOpenSSL" auf Android, SunEC auf JVM). Danach wird ÜBERALL
     * provider-gepinnt instanziiert (kein Delegate-Failover). Ein ent-
     * fernter Provider (Test-Rotation) wird bei der nächsten Nutzung
     * erkannt und neu gepinnt.
     */
    @Volatile
    private var pinnedXdhProvider: String? = null

    private fun xdhProviderName(): String? {
        pinnedXdhProvider?.let { name ->
            if (Security.getProvider(name) != null) return name
            pinnedXdhProvider = null  // Provider wurde entfernt → neu auflösen
        }
        var found: String? = null
        runCatching {
            val probePriv = PKCS8EncodedKeySpec(X25519_PKCS8_HEAD + ByteArray(32))
            val probePub = X509EncodedKeySpec(X25519_SPKI_HEAD + ByteArray(32))
            for (provider in Security.getProviders()) {
                val kf = runCatching { KeyFactory.getInstance("XDH", provider) }
                    .getOrNull() ?: continue
                val ok = runCatching {
                    kf.generatePrivate(probePriv)
                    kf.generatePublic(probePub)
                    true
                }.getOrDefault(false)
                if (ok) {
                    found = provider.name
                    break
                }
            }
        }
        pinnedXdhProvider = found
        return found
    }

    /** Nur Regressionstests (CryptoEngineProviderPinTest): Pin neu auflösen. */
    fun reprobeXdhProviderForTest() {
        pinnedXdhProvider = null
    }

    /** Provider-gepinnte XDH-KeyFactory (P1: kein libcore-Delegate-Failover). */
    private fun xdhKeyFactory(): KeyFactory {
        val pinned = xdhProviderName()
        if (pinned != null) {
            runCatching { return KeyFactory.getInstance("XDH", pinned) }
        }
        return KeyFactory.getInstance("XDH")
    }

    /** Provider-gepinnter XDH-KeyAgreement (P1: gleiche Failover-Klasse). */
    private fun xdhKeyAgreement(): KeyAgreement {
        val pinned = xdhProviderName()
        return (if (pinned != null)
            runCatching { KeyAgreement.getInstance("XDH", pinned) }.getOrNull() else null)
            ?: KeyAgreement.getInstance("XDH")
    }

    /**
     * X25519-Pair erzeugen — initialize(X25519) nur wo unterstützt (JDK);
     * Conscrypt wirft InvalidAlgorithmParameterException und nutzt den
     * X25519-Default (Probe4/5-Beweis am Emulator).
     */
    private fun kpgX25519(): KeyPair {
        // P1: auch den KPG provider-pinnen — Conscrypt-KPG wirft bei
        // initialize(ParamSpec) bewusst (Default = X25519); ohne Pin könnte
        // die libcore-Delegate denselben Failover-Pfad nehmen wie die
        // KeyFactory (JDI-Beweis).
        val pinned = xdhProviderName()
        val kpg = (if (pinned != null)
            runCatching { KeyPairGenerator.getInstance("XDH", pinned) }.getOrNull() else null)
            ?: KeyPairGenerator.getInstance("XDH")
        runCatching { kpg.initialize(NamedParameterSpec.X25519) }
            .onFailure { /* Conscrypt: Default = X25519, kein Param-Spec */ }
        return kpg.generateKeyPair()
    }

    class CryptoUnavailableException(message: String) : RuntimeException(message)

    // ── Kodierungen ──────────────────────────────────────────────────────────

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 64-Hex-PubKey-Parse (lowercase-Ausgabe wie Python `bytes.fromhex`). */
    fun fromHex(text: String): ByteArray {
        require(text.length % 2 == 0 && text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "ungültiges Hex"
        }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            out[i] = text.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
        return out
    }

    fun b64e(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    fun b64d(text: String): ByteArray = Base64.getDecoder().decode(text)

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun secureRandomBytes(count: Int): ByteArray =
        ByteArray(count).also { SecureRandom().nextBytes(it) }

    fun longTo8BigEndian(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0..7) {
            out[7 - i] = (value ushr (8 * i)).toByte()
        }
        return out
    }

    // ── Hash / MAC / HKDF (RFC 5869) ─────────────────────────────────────────

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        // RFC 5869 §2.2: leerer Salt = HashLen Nullbytes; HMAC paddet beide
        // identisch (0×N key ≡ 32×0x00 key) — Python `hmac.new(b"", …)`-Parität.
        val effectiveKey = if (key.isEmpty()) ByteArray(32) else key
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(effectiveKey, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 §2.2 Extract: HMAC-SHA256(key=salt, msg=ikm). */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmacSha256(salt, ikm)

    /** RFC 5869 §2.3 Expand: T(i) = HMAC(prk, T(i-1) ‖ info ‖ i). */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF-Länge ungültig" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val take = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take
            counter++
        }
        return out
    }

    /** HKDF-SHA256 = Expand(Extract(salt, ikm), info, length). */
    fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int = 32): ByteArray =
        hkdfExpand(hkdfExtract(salt, ikm), info, length)

    // ── X25519 (JDK XDH, Raw-Wire RFC 7748) ─────────────────────────────────

    data class X25519KeyPair(val priv: ByteArray, val pub: ByteArray)

    /** Frisches X25519-Pair (Raw 32 B little-endian, wie Python Raw-Encoding). */
    fun x25519Generate(): X25519KeyPair {
        if (!xdhAvailable) throw CryptoUnavailableException("XDH nicht verfügbar — E2E fail-closed")
        val pair = kpgX25519()
        return X25519KeyPair(privRaw(pair.private), pubWire(pair.public))
    }

    /** X25519(priv, peerPub) = RFC 7748 §5 — identisch zu `cryptography`. */
    fun x25519Dh(priv: ByteArray, peerPub: ByteArray): ByteArray {
        if (!xdhAvailable) throw CryptoUnavailableException("XDH nicht verfügbar — E2E fail-closed")
        // P1: provider-gepinnte KeyFactory — die ungepinnte libcore-Delegate
        // failovert bei Conscrypt-Spec-Fehlern PERMANENT auf AndroidKeyStore
        // (das wirft bei JEDEM Spec) und der PKCS8/SPKI-Fallback unten läuft
        // dann auf der korruptierten Delegate → InvalidKeySpecException.
        val kf = xdhKeyFactory()
        // Priv: JDK via XECPrivateKeySpec; Conscrypt (Android) unterstützt
        // generatePrivate(XECPrivateKeySpec) NICHT (Probe5: InvalidKeySpec)
        // → PKCS8-Fallback 302e020100300506032b656e04220420‖scalar.
        val privKey = runCatching {
            kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, priv))
        }.getOrElse {
            kf.generatePrivate(PKCS8EncodedKeySpec(X25519_PKCS8_HEAD + priv))
        }
        // Pub: JDK via XECPublicKeySpec (Bit-255-Maskierung manuell, RFC §5.2);
        // Conscrypt wirft InvalidKeySpec → SPKI-Fallback (feste X25519-SPKI-
        // Kopfzeile + Peer-Wire; OpenSSL maskiert Bit 255 selbst).
        val pubKey = runCatching {
            val u = BigInteger(1, peerPub.reversedArray()).clearBit(255)
            kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, u))
        }.getOrElse {
            kf.generatePublic(X509EncodedKeySpec(X25519_SPKI_HEAD + peerPub))
        }
        val agreement = xdhKeyAgreement()
        agreement.init(privKey)
        agreement.doPhase(pubKey, true)
        return agreement.generateSecret()
    }

    /** Private-Raw-Bytes aus einem JDK-PrivateKey (KeySpec → Scalar). */
    private fun privRaw(key: java.security.PrivateKey): ByteArray {
        val kf = xdhKeyFactory()
        val spec = kf.getKeySpec(key, XECPrivateKeySpec::class.java)
        return spec.scalar ?: throw CryptoUnavailableException("Privat-Key nicht lesbar")
    }

    /** Public-Wire-Bytes (32 B, little-endian u) aus einem JDK-PublicKey. */
    private fun pubWire(key: java.security.PublicKey): ByteArray {
        // JDK: XECPublicKey-Interface (u → little-endian Wire). Conscrypt
        // (Android): OpenSSLX25519PublicKey implementiert XECPublicKey NICHT
        // (Probe4: ClassCastException) → getEncoded()-SPKI-Fallback — der
        // X25519-SPKI ist 44 B mit fester Kopfzeile, Wire = Bytes [12, 44)
        // (Probe5: head_match=true).
        val xec = key as? java.security.interfaces.XECPublicKey
        if (xec != null) {
            var be = xec.u.toByteArray()
            if (be.size > 1 && be[0].toByte() == 0.toByte()) be = be.copyOfRange(1, be.size)
            val wire = ByteArray(32)
            for (i in be.indices) {
                if (i < 32) wire[be.size - 1 - i] = be[i]
            }
            return wire
        }
        val enc = key.encoded
            ?: throw CryptoUnavailableException("Public-Key nicht lesbar (kein XEC, kein Encoding)")
        if (enc.size != X25519_SPKI_HEAD.size + 32) {
            throw CryptoUnavailableException("Public-Key-Encoding unerwartet (len=${enc.size})")
        }
        for (i in X25519_SPKI_HEAD.indices) {
            if (enc[i] != X25519_SPKI_HEAD[i]) {
                throw CryptoUnavailableException("Public-Key kein X25519-SPKI")
            }
        }
        return enc.copyOfRange(X25519_SPKI_HEAD.size, X25519_SPKI_HEAD.size + 32)
    }

    /** X25519-SPKI-Kopf (DER): SEQ(302a) AlgID(300506032b656e) BITSTRING(032100). */
    private val X25519_SPKI_HEAD =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

    /** X25519-PKCS8-Kopf (DER): SEQ(302e) ver(020100) AlgID(300506032b656e) OCTET(04220420). */
    private val X25519_PKCS8_HEAD =
        byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e,
            0x04, 0x22, 0x04, 0x20)

    // ── AES-256-GCM ──────────────────────────────────────────────────────────

    /** ct = AES-CTR ‖ 128-Bit-Tag (identisch zu `cryptography` AESGCM). */
    fun aesGcmEncrypt(key: ByteArray, nonce12: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(nonce12.size == 12) { "Nonce muss 12 Byte sein" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce12))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Entschlüsselt ct‖tag. Falscher Key / manipulierter Tag / falsches AAD →
     * [AEADBadTagException]. NIE partiellen Klartext zurückgeben.
     */
    fun aesGcmDecrypt(key: ByteArray, nonce12: ByteArray, ctAndTag: ByteArray, aad: ByteArray): ByteArray {
        require(nonce12.size == 12) { "Nonce muss 12 Byte sein" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce12))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ctAndTag)
    }

    // ── Fingerprints (PH3-KDF-FIXTURES §5.8) ────────────────────────────────

    /** agent_fp = HKDF-SHA256(salt=SHA256("flipsi-fp"), ikm=pub, info="fb-agent-fp", 32). */
    fun fingerprintOfPub(pub: ByteArray): String =
        hex(hkdfSha256(salt = sha256(utf8("flipsi-fp")), ikm = pub, info = utf8("fb-agent-fp"), length = 32))

    /** Anzeige: 8 Gruppen à 8 Hex-Zeichen, Großbuchstaben (Abgleich = Hex-Gleichheit). */
    fun fingerprintDisplay(fpHex: String): String =
        fpHex.chunked(8).joinToString(" ") { it.uppercase() }

    // ── Kanonischer Payload-JSON (Python: json.dumps sort_keys, separators)) ──

    /**
     * Kanonischer Klartext-Payload VOR der Verschlüsselung — exakt wie
     * `json.dumps(payload, sort_keys=True, separators=(",", ":"))` der
     * Python-Referenz: sortierte Keys, keine Leerzeichen. Unterstützte Werte:
     * String, Boolean, Int, Long, null. Eigener Serializer (bewusst kein Gson-
     * toString — dessen Escaping-Regeln sind nicht Teil des Vertrags).
     */
    fun canonicalJson(payload: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        payload.keys.sorted().forEachIndexed { index, key ->
            if (index > 0) sb.append(',')
            sb.append(jsonString(key)).append(':').append(jsonValue(payload[key]))
        }
        return sb.append('}').toString()
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        else -> jsonString(value.toString())
    }

    /** JSON-String-Escaping (RFC 8259: Anführungszeichen, Backslash, Steuerzeichen). */
    private fun jsonString(text: String): String {
        val sb = StringBuilder("\"")
        for (ch in text) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('\"').toString()
    }
}