package com.hermesandroid.bridge.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PH-3 Welle 2 — Double-Ratchet-Kette gegen PH3-KDF-FIXTURES §5.4/§5.7
 * (Signal-KDF, RA/RP-fixe Vektoren, Nachricht-0-ct = Frame-Trace-Kompatibilität
 * mit der Python-Referenz PhoneCryptoPeer/AgentCryptoSession) + Replay-Fenster,
 * Nonce-Zähler-Bindung, rot-Hinweis je N, Ratchet-Kette 100+.
 */
class DoubleRatchetTest {

    private fun h(hex: String): ByteArray = CryptoEngine.fromHex(hex)

    private fun hex(bytes: ByteArray): String = CryptoEngine.hex(bytes)

    private val root0 = h("ed4af0ee97db707575e330a20eac4544b608b09c43da7703facd3200b6aa3724")
    private val ekPPub = h("7ee1114db727263624da899815825ce5a8b5ce3d2c0a60272feac2205f836634")
    private val raPriv = h("1111111111111111111111111111111111111111111111111111111111111111")
    private val raPub = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
    private val rpPriv = h("2222222222222222222222222222222222222222222222222222222222222222")
    private val rpPub = "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"
    private val ekPPriv = h("98a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f")
    private val msg0Aad = CryptoEngine.utf8("fb-v1|fb3demo-0001|chat-123|agent1")

    @Test
    fun kdfRkAndKdfCkVectors() {
        val ratchet = DoubleRatchet(root0)
        val (rk1, cks1) = ratchet.kdfRk(root0, CryptoEngine.x25519Dh(raPriv, ekPPub))
        assertEquals("99488063b00f1538fb27c3b919aa13fa0ac6e6881183c6c733c5f868530f791e", hex(rk1))
        assertEquals("adf933b72c8a0ed7ad1e0e77fde5cb892480ab58fd4190b56b04096b14a9f2d1", hex(cks1))
        val (ck1, mk0) = ratchet.kdfCk(cks1)
        assertEquals("b96c49be7953ea8b0f0736c2453e46c99a6ecad11288eef175e19617bf6cd3fa", hex(ck1))
        assertEquals("40fcf41504a57364220d5afb03903a4dac383ebb138288767591ca7d61961e65", hex(mk0))
        assertEquals(
            "625c9b03d93e91d004b47c33a9c0ab3e8392ac6059fc2f7bad53e9fe4ae527c4",
            hex(ratchet.messageKey(mk0)),
        )
    }

    @Test
    fun agentMessage0CiphertextMatchesPythonReference() {
        val agent = DoubleRatchet(
            root0,
            keyGen = { CryptoEngine.X25519KeyPair(raPriv, h(raPub)) },
            noncePrefixGen = { h("aabbccdd") },
        )
        agent.initResponder(ekPPub)
        val enc0 = agent.encrypt(
            CryptoEngine.utf8(CryptoEngine.canonicalJson(mapOf("msg_id" to "m0", "text" to "Hallo vom Agent"))),
            msg0Aad,
        )
        // §5.7: Nachricht 0 des Agenten — byte-exakter Cross-Beweis zur Python-Seite.
        assertEquals(
            "e8037ede3d217516e20699b67a6b49521af116bc117968f93318ac741b78cb427b86d8564b8ec243066ae56f050c5b0c9abc623ca04f9003",
            hex(enc0.ct),
        )
        assertEquals(raPub, enc0.rkHex)
        assertEquals("aabbccdd0000000000000000", hex(enc0.nonce))
        assertEquals(0, enc0.n)
        assertEquals(0, enc0.pn)
        assertTrue(!enc0.rotHint)
    }

    @Test
    fun phoneFirstSendChainMatchesFixture() {
        val phone = DoubleRatchet(root0, keyGen = { CryptoEngine.X25519KeyPair(rpPriv, h(rpPub)) })
        phone.initInitiator(ekPPriv, ekPPub, h(raPub))
        // Signal-Alice-Init: EIN Advance → RK1 (identisch zur Agent-Seite).
        assertEquals("99488063b00f1538fb27c3b919aa13fa0ac6e6881183c6c733c5f868530f791e",
            hex(phone.rootKeyForTest()))
        phone.forceRotateSendChainForTest()
        // §5.7 Gegenprobe: RK2 + CKs_phone mit fixem RP.
        assertEquals("996bd586dcc7f5d286584c519602fbc778b870c02e1ce6a96c64adcd48bd5c64",
            hex(phone.rootKeyForTest()))
        assertEquals("d6882bd3845d9665702a81e422b7bb6cccfd5b64d3b651009e9612c4261ebd01",
            hex(phone.sendChainKeyForTest()!!))
    }

    @Test
    fun nonceBindsCounter_BigEndian8() {
        val agent = DoubleRatchet(root0,
            keyGen = { CryptoEngine.X25519KeyPair(raPriv, h(raPub)) },
            noncePrefixGen = { h("deadbeef") })
        agent.initResponder(ekPPub)
        val enc = agent.encrypt(ByteArray(0), msg0Aad)
        assertEquals("deadbeef0000000000000000", hex(enc.nonce))
        try {
            agent.decrypt(
                DoubleRatchet.EncryptedHeader(h(enc.rkHex), enc.n, enc.pn),
                h("deadbeef") + CryptoEngine.longTo8BigEndian((enc.n + 1).toLong()),
                enc.ct, msg0Aad,
            )
            fail("Nonce muss an den Zähler geblockt werden")
        } catch (expected: CryptoFrameException) {
            // Nonce passt nicht zum Zähler → Drop
        }
    }

    @Test
    fun replayAndWindowBlocked() {
        val agent = DoubleRatchet(root0,
            keyGen = { CryptoEngine.X25519KeyPair(raPriv, h(raPub)) },
            noncePrefixGen = { h("00112233") })
        agent.initResponder(ekPPub)
        val receiver = DoubleRatchet(root0, keyGen = { CryptoEngine.x25519Generate() })
        receiver.initInitiator(ekPPriv, ekPPub, h(raPub))
        val aad = CryptoEngine.utf8("fb-v1|s|c|a")

        val frames = (0 until 3).map { i ->
            agent.encrypt(CryptoEngine.utf8("m$i"), aad)
        }
        frames.forEach { enc ->
            receiver.decrypt(DoubleRatchet.EncryptedHeader(h(enc.rkHex), enc.n, enc.pn), enc.nonce, enc.ct, aad)
        }
        // Duplikat (n < Nr) → Replay-Drop
        try {
            val first = frames[0]
            receiver.decrypt(DoubleRatchet.EncryptedHeader(h(first.rkHex), first.n, first.pn), first.nonce, first.ct, aad)
            fail("Replay-Duplikat muss geblockt werden")
        } catch (expected: CryptoFrameException) {
        }
        // n ≥ Nr+100 → Fenster-Verletzung
        val far = agent.encrypt(CryptoEngine.utf8("far"), aad)
        try {
            receiver.decrypt(
                DoubleRatchet.EncryptedHeader(h(far.rkHex), far.n + 500, far.pn),
                far.nonce.copyOf(4) + CryptoEngine.longTo8BigEndian((far.n + 500).toLong()),
                far.ct, aad,
            )
            fail("Fenster-Verletzung (>100) muss geblockt werden")
        } catch (expected: CryptoFrameException) {
        }
    }

    @Test
    fun outOfOrderViaSkippedCache() {
        val agent = DoubleRatchet(root0,
            keyGen = { CryptoEngine.X25519KeyPair(raPriv, h(raPub)) },
            noncePrefixGen = { h("0f0f0f0f") })
        agent.initResponder(ekPPub)
        val receiver = DoubleRatchet(root0, keyGen = { CryptoEngine.x25519Generate() })
        receiver.initInitiator(ekPPriv, ekPPub, h(raPub))
        val aad = CryptoEngine.utf8("fb-v1|s|c|a")
        val frames = (0 until 4).map { i -> agent.encrypt(CryptoEngine.utf8("m$i"), aad) }
        // Reihenfolge 3, 0, 1, 2 — übersprungene Keys aus dem Cache (Cap 100).
        for (i in listOf(3, 0, 1, 2)) {
            val enc = frames[i]
            val pt = receiver.decrypt(DoubleRatchet.EncryptedHeader(h(enc.rkHex), enc.n, enc.pn), enc.nonce, enc.ct, aad)
            assertEquals("m$i", String(pt, Charsets.UTF_8))
        }
    }

    @Test
    fun rotateHintAfterEveryNFrames_NoCryptoEffect() {
        val agent = DoubleRatchet(root0, rotateEvery = 5,
            keyGen = { CryptoEngine.X25519KeyPair(raPriv, h(raPub)) },
            noncePrefixGen = { h("01010101") })
        agent.initResponder(ekPPub)
        val aad = CryptoEngine.utf8("fb-v1|s|c|a")
        var hints = 0
        for (i in 0 until 7) {
            val enc = agent.encrypt(CryptoEngine.utf8("m$i"), aad)
            if (enc.rotHint) hints++
        }
        assertEquals("hdr.rot=1 GENAU nach N=5 Frames (reiner Hinweis)", 1, hints)
        // KEINE Krypto-Wirkung: Frame 7 (nach dem Hinweis) entschlüsselt normal.
        val receiver = DoubleRatchet(root0, keyGen = { CryptoEngine.x25519Generate() })
        receiver.initInitiator(ekPPriv, ekPPub, h(raPub))
        val last = agent.encrypt(CryptoEngine.utf8("nach-hinweis"), aad)
        val pt = receiver.decrypt(
            DoubleRatchet.EncryptedHeader(h(last.rkHex), last.n, last.pn), last.nonce, last.ct, aad)
        assertEquals("nach-hinweis", String(pt, Charsets.UTF_8))
    }

    @Test
    fun ratchetChainOver100Frames() {
        // Zwei vollständige Peers: Agent (responder-Init) + Phone (initiator-Init)
        // über 110 Frames beidseitig — Kette >100 ohne Desync, danach Normalbetrieb.
        val agent = DoubleRatchet(root0)
        agent.initResponder(ekPPub)
        val phone = DoubleRatchet(root0)
        phone.initInitiator(ekPPriv, ekPPub, agent.dhPubForTest()!!)
        val aad = CryptoEngine.utf8("fb-v1|s|c|a")
        var frames = 0
        for (i in 0 until 55) {
            val out = phone.encrypt(CryptoEngine.utf8("p$i"), aad)
            val pt = agent.decrypt(DoubleRatchet.EncryptedHeader(h(out.rkHex), out.n, out.pn), out.nonce, out.ct, aad)
            assertEquals("p$i", String(pt, Charsets.UTF_8))
            val back = agent.encrypt(CryptoEngine.utf8("a$i"), aad)
            val pt2 = phone.decrypt(DoubleRatchet.EncryptedHeader(h(back.rkHex), back.n, back.pn), back.nonce, back.ct, aad)
            assertEquals("a$i", String(pt2, Charsets.UTF_8))
            frames += 2
        }
        assertTrue("110 Frames über beide Ketten ohne Desync", frames > 100)
        // Frame 111 (nach >100) weiterhin gültig.
        val extra = agent.encrypt(CryptoEngine.utf8("extra"), aad)
        val pt = phone.decrypt(DoubleRatchet.EncryptedHeader(h(extra.rkHex), extra.n, extra.pn), extra.nonce, extra.ct, aad)
        assertEquals("extra", String(pt, Charsets.UTF_8))
    }

    @Test
    fun pnCapBlocktAngreiferKontrolliertesPn() {
        // PH-3 F2 (Jäger P2): hdr.pn ist angreifer-kontrolliert (bis 2³¹) —
        // decrypt muss pn > nr + REPLAY_WINDOW abweisen, sonst leitet
        // skipMessageKeys Milliarden Message-Keys ab (CPU/RAM-DoS).
        val agent = DoubleRatchet(root0, keyGen = { CryptoEngine.x25519Generate() })
        agent.initResponder(ekPPub)
        val phone = DoubleRatchet(root0, keyGen = { CryptoEngine.x25519Generate() })
        phone.initInitiator(ekPPriv, ekPPub, agent.dhPubForTest()!!)
        val aad = CryptoEngine.utf8("fb-v1|s|c|a")

        // Runde 1: Phone sendet → Agent ratchets → Antwort etabliert rk#2.
        val p0 = phone.encrypt(CryptoEngine.utf8("p0"), aad)
        agent.decrypt(DoubleRatchet.EncryptedHeader(h(p0.rkHex), p0.n, p0.pn), p0.nonce, p0.ct, aad)
        val a0 = agent.encrypt(CryptoEngine.utf8("a0"), aad)
        phone.decrypt(DoubleRatchet.EncryptedHeader(h(a0.rkHex), a0.n, a0.pn), a0.nonce, a0.ct, aad)

        // Runde 2: Agent-Antwort trägt die FRESHE rk#3 — Angriff: pn verdreht.
        val p1 = phone.encrypt(CryptoEngine.utf8("p1"), aad)
        agent.decrypt(DoubleRatchet.EncryptedHeader(h(p1.rkHex), p1.n, p1.pn), p1.nonce, p1.ct, aad)
        val a1 = agent.encrypt(CryptoEngine.utf8("a1"), aad)
        try {
            phone.decrypt(
                DoubleRatchet.EncryptedHeader(h(a1.rkHex), a1.n, Int.MAX_VALUE / 2),
                a1.nonce, a1.ct, aad,
            )
            fail("angreifer-kontrollierter pn (2³¹-nah) muss über dem Fenster geblockt werden")
        } catch (expected: CryptoFrameException) {
            assertEquals("pn über Fenster (>100)", expected.message)
        }

        // KEIN Zustands-Konsum durch den Abweisungs-Pfad: der unverfälschte
        // Frame (legitimes pn) entschlüsselt danach normal.
        val pt = phone.decrypt(
            DoubleRatchet.EncryptedHeader(h(a1.rkHex), a1.n, a1.pn), a1.nonce, a1.ct, aad)
        assertEquals("a1", String(pt, Charsets.UTF_8))
    }
}