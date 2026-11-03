package com.hermesandroid.bridge.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PH-3 Welle 2 — Fixture-Vektoren EXAKT gegen secrets-docs/PH3-KDF-FIXTURES-v1.md
 * (FROZEN v1): jede Tabelle (RFC 5869, RFC 7748, AES-GCM-Cross, fb-v1-Kette,
 * hello_ack, Fingerprints) ist der byte-exakte Vertrag — dieselben Werte wie
 * tests/test_crypto_fixtures.py auf der Python-Seite.
 */
class CryptoEngineFixtureTest {

    private fun h(hex: String): ByteArray = CryptoEngine.fromHex(hex)

    private fun hex(bytes: ByteArray): String = CryptoEngine.hex(bytes)

    // ── §2 HKDF RFC 5869 (SHA-256) ─────────────────────────────────────────

    @Test
    fun hkdfRfc5869TestCase1_Basic() {
        val ikm = h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = h("000102030405060708090a0b0c")
        val info = h("f0f1f2f3f4f5f6f7f8f9")
        val prk = CryptoEngine.hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(CryptoEngine.hkdfExpand(prk, info, 42)),
        )
    }

    @Test
    fun hkdfRfc5869TestCase2_LongInputs() {
        val ikm = h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f")
        val salt = h("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
        val info = h(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
        )
        val prk = CryptoEngine.hkdfExtract(salt, ikm)
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", hex(prk))
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            hex(CryptoEngine.hkdfExpand(prk, info, 82)),
        )
    }

    @Test
    fun hkdfRfc5869TestCase3_EmptySaltAndInfo() {
        val ikm = h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val prk = CryptoEngine.hkdfExtract(ByteArray(0), ikm)
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", hex(prk))
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hex(CryptoEngine.hkdfExpand(prk, ByteArray(0), 42)),
        )
    }

    // ── §3 X25519 RFC 7748 ─────────────────────────────────────────────────

    @Test
    fun x25519Rfc7748EcdhVector_BothDirections() {
        val alicePriv = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = h("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, hex(CryptoEngine.x25519Dh(alicePriv, bobPub)))
        assertEquals(expected, hex(CryptoEngine.x25519Dh(bobPriv, alicePub)))
    }

    @Test
    fun x25519Rfc7748FunctionVectors() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            hex(CryptoEngine.x25519Dh(
                h("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                h("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))),
        )
        // §5.2 Vektor 2: u-Koordinate mit gesetztem Bit 255 — decodeUCoordinate
        // MASKIERT es (Python cryptography maskiert; der JDK-XDH nicht).
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            hex(CryptoEngine.x25519Dh(
                h("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                h("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"))),
        )
    }

    // ── §4 AES-256-GCM Cross-Vektoren ─────────────────────────────────────

    private val gcmKey = h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val gcmNonce = h("cafebabefacedbaddecaf888")

    @Test
    fun aesGcmCrossVectors() {
        assertEquals(
            "5b0a77e3716cc7e86cf71b26dee7d5a4",
            hex(CryptoEngine.aesGcmEncrypt(gcmKey, gcmNonce, ByteArray(0), ByteArray(0))),
        )
        assertEquals(
            "cccfc956d9130d692f6f3ab85b4dc1123e008323b06a195918ba6f05d1fb45cdbd78c18745baecae4e7e60a6b1c40405",
            hex(CryptoEngine.aesGcmEncrypt(
                gcmKey, gcmNonce, CryptoEngine.utf8("FlipsiBridge PH-3 Cross-Vektor 2"), ByteArray(0))),
        )
        val pt = h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        val aad = CryptoEngine.utf8("fb-v1|fb3demo-0001|chat-123|agent1")
        val ct = CryptoEngine.aesGcmEncrypt(gcmKey, gcmNonce, pt, aad)
        assertEquals(
            "8aa2a225ae7f491c4e0257d6771087301d31d242cb0c7c6356c61e6aa2947be08d71e365e5f16f450e7202431eae0e4a69659b6297bfbee71bab4b0782d9897d328c04f0224b457f63ec5982a4c750dd",
            hex(ct),
        )
        assertTrue(CryptoEngine.aesGcmDecrypt(gcmKey, gcmNonce, ct, aad).contentEquals(pt))
    }

    @Test
    fun aesGcmWrongKeyFailsWithoutPlaintext() {
        val pt = CryptoEngine.utf8("geheim")
        val ct = CryptoEngine.aesGcmEncrypt(gcmKey, gcmNonce, pt, ByteArray(0))
        try {
            CryptoEngine.aesGcmDecrypt(h("ff".repeat(32)), gcmNonce, ct, ByteArray(0))
            fail("Wrong-Key-Entschlüsselung muss AEADBadTagException werfen")
        } catch (expected: javax.crypto.AEADBadTagException) {
            // kein Klartext-Leak: Exception OHNE Inhalt
        }
    }

    // ── §5.5 fb-v1-Ableitungskette (feste Demo-Keys, Pflicht-Testvektor) ──

    private val ikP = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val ikPPub = h("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val ekP = h("98a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f")
    private val ekPPub = h("7ee1114db727263624da899815825ce5a8b5ce3d2c0a60272feac2205f836634")
    private val spkPPub = h("1ed0dd56484104c33f79797c39f3f47c2a4153662cfcfc146e4f8a5062c51c72")
    private val ikAPub = h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    private val spkAPub = h("52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e")

    @Test
    fun fbV1DerivationMatchesFixture() {
        val dh1 = CryptoEngine.x25519Dh(ekP, ikAPub)
        assertEquals("74faf45844c64bac4576b625d9df7d611b806e193276eea42ebfd3cbbe698014", hex(dh1))
        val dh2 = CryptoEngine.x25519Dh(ikP, ikAPub)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(dh2))
        val dh3 = CryptoEngine.x25519Dh(ekP, spkAPub)
        assertEquals("b67caf683bb48069685a0d9f0572a0d26c4037d1bacc49553054ded8b04ab44e", hex(dh3))
        val kdf = fbV1Kdf(dh1, dh2, dh3, ByteArray(0), "fb3demo-0001", "agent1",
            ikPPub, ekPPub, spkPPub, ikAPub, spkAPub)
        assertEquals("fcf3ffdd1ba183e7743b3cc0775441ec1ec9e6cef753a15477f34adfa37f2147", hex(kdf.transcript))
        assertEquals("07e64bf2f09f9520f3d0a4ee3d9007e0da93a389f40db451f16d9f9d38e79fa4",
            hex(CryptoEngine.hmacSha256(kdf.transcript, ByteArray(0))))
        assertEquals("5231d76000fd2f37da7bc9fbe62e2b896fae8ce68e3edbb65cb96193aa9655e9", hex(kdf.prk))
        assertEquals("c92a15332695cc9878aeecd1060916fd7729746286a7852e5317b99cfaeb68aa", hex(kdf.root0))
        assertEquals("1a531068f5603c0056763523e67e12827572c84dbb786b4ea8e8efff9803cb82", hex(kdf.kInit))
        assertEquals("2b3327cc77090e33fc235fdc49659c26507cfae7c1b843e6af7d150de3e8d52f", kdf.sessionFpHex)
    }

    @Test
    fun fbV1PskBindingVector() {
        val dh1 = CryptoEngine.x25519Dh(ekP, ikAPub)
        val dh2 = CryptoEngine.x25519Dh(ikP, ikAPub)
        val dh3 = CryptoEngine.x25519Dh(ekP, spkAPub)
        val kdf = fbV1Kdf(dh1, dh2, dh3, CryptoEngine.utf8("PAIRCODE12"), "fb3demo-0001", "agent1",
            ikPPub, ekPPub, spkPPub, ikAPub, spkAPub)
        assertEquals("c456c51cc80699f2b9f577a0c6b8cf0495baa3a09d419b5639946fc1fd6fdfd4", hex(kdf.root0))
        assertEquals("475ae8d2dd6b24f873854bdaa4eaabf5fbb1e90cd3eac864fb2136ecbf64d49f", hex(kdf.kInit))
    }

    // ── §5.6 hello_ack-Verify ──────────────────────────────────────────────

    @Test
    fun helloAckVerifyVector() {
        val kInit = h("1a531068f5603c0056763523e67e12827572c84dbb786b4ea8e8efff9803cb82")
        val nonce = h("00112233445566778899aabb")
        val aad = CryptoEngine.utf8("fb-v1|fb3demo-0001|agent1")
        val pt = CryptoEngine.utf8("flipsi-verify-v1|fb3demo-0001")
        val ct = CryptoEngine.aesGcmEncrypt(kInit, nonce, pt, aad)
        assertEquals(
            "2dacd90dd0fa8c92b42c199dc64f7f5af1f67add37e69ccb4a6c7a2897eef0350aac6292cd9fa18bf298169c19",
            hex(ct),
        )
        assertTrue(CryptoEngine.aesGcmDecrypt(kInit, nonce, ct, aad).contentEquals(pt))
    }

    // ── §5.8 Fingerprints ─────────────────────────────────────────────────

    @Test
    fun fingerprintVectors() {
        assertEquals(
            "b350365cbeb36d16524a2be7979ba6992de20a0950768f1d70e135d32c890b9d",
            CryptoEngine.fingerprintOfPub(ikAPub),
        )
        assertEquals(
            "6a2ec0a42bc556598854fe9f67f2d575a0c2d970f911c4beb9eaffbe556ebb7e",
            CryptoEngine.fingerprintOfPub(ikPPub),
        )
        assertEquals(
            "2b3327cc77090e33fc235fdc49659c26507cfae7c1b843e6af7d150de3e8d52f",
            CryptoEngine.hex(CryptoEngine.hkdfExpand(
                CryptoEngine.fromHex("5231d76000fd2f37da7bc9fbe62e2b896fae8ce68e3edbb65cb96193aa9655e9"),
                CryptoEngine.utf8("fb-fp") + ikPPub + ekPPub + spkPPub + ikAPub + spkAPub +
                    CryptoEngine.utf8("agent1") + CryptoEngine.utf8("fb3demo-0001"),
                32)),
        )
    }

    @Test
    fun fingerprintDisplayGroups8x4() {
        assertEquals(
            "2B3327CC 77090E33 FC235FDC 49659C26 507CFAE7 C1B843E6 AF7D150D E3E8D52F",
            CryptoEngine.fingerprintDisplay("2b3327cc77090e33fc235fdc49659c26507cfae7c1b843e6af7d150de3e8d52f"),
        )
    }

    // ── Kanonischer Payload-JSON (Python json.dumps-Parität) ──────────────

    @Test
    fun canonicalJsonMatchesPythonDumps() {
        assertEquals(
            "{\"msg_id\":\"x\",\"text\":\"a=b<c>&d\",\"working\":true}",
            CryptoEngine.canonicalJson(mapOf("text" to "a=b<c>&d", "msg_id" to "x", "working" to true)),
        )
        assertEquals(
            "{\"t\":\"zeile\\nmit \\\"quote\\\"\"}",
            CryptoEngine.canonicalJson(mapOf("t" to "zeile\nmit \"quote\"")),
        )
    }
}