package com.hermesandroid.bridge.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PH-3 Welle 2 — PhoneCryptoPeer ↔ Agent-Responder-Roundtrip (Kotlin-Gegenstück
 * des Python-Handshake-Tests): Fingerprint-Gleichheit beider Seiten, Frame-
 * Kompatibilität mit der Python-Referenz (Schema + AAD + Kette), Replay-Block,
 * Wrong-Key, FP-ABGLEICH-Text, Session-Rotate (frische crypto.hello) — die
 * Rotations-Kadenz §5.4 (je Peer-Announcement + je Session, NICHT je N) bleibt
 * im Ratchet-Code bewusst ohne Eigen-Rotation.
 */
class PhoneCryptoPeerTest {

    private fun h(bytes: ByteArray) = CryptoEngine.hex(bytes)

    /**
     * Agent-Responder NUR für Tests (Produktiv-Responder = Python adapter.py
     * AgentCryptoSession) — gleiche fb-v1-Kette, gleiche Frames.
     */
    private class TestAgentResponder(
        val agentId: String,
        private val identity: CryptoEngine.X25519KeyPair,
        private val prekey: CryptoEngine.X25519KeyPair,
        private val rotateEvery: Int = 100,
    ) {
        var sessionId: String? = null
        var sessionFp: String? = null
        var ratchet: DoubleRatchet? = null

        fun respondToHello(hello: JsonObject): JsonObject {
            val sid = hello.get("session_id").asString
            sessionId = sid
            val ikP = CryptoEngine.fromHex(hello.get("ik_pub").asString)
            val ekP = CryptoEngine.fromHex(hello.get("ek_pub").asString)
            val spkP = CryptoEngine.fromHex(hello.get("spk_pub").asString)
            val dh1 = CryptoEngine.x25519Dh(identity.priv, ekP)
            val dh2 = CryptoEngine.x25519Dh(identity.priv, ikP)
            val dh3 = CryptoEngine.x25519Dh(prekey.priv, ekP)
            val kdf = fbV1Kdf(dh1, dh2, dh3, ByteArray(0), sid, agentId,
                ikP, ekP, spkP, identity.pub, prekey.pub)
            val r = DoubleRatchet(kdf.root0, rotateEvery = rotateEvery)
            r.initResponder(ekP)
            ratchet = r
            sessionFp = kdf.sessionFpHex
            val ackNonce = CryptoEngine.secureRandomBytes(12)
            val ackCt = CryptoEngine.aesGcmEncrypt(
                kdf.kInit, ackNonce,
                CryptoEngine.utf8("flipsi-verify-v1|$sid"),
                CryptoEngine.utf8("fb-v1|$sid|$agentId"),
            )
            return JsonObject().apply {
                addProperty("type", "crypto.hello_ack")
                addProperty("v", 1)
                addProperty("session_id", sid)
                addProperty("agent_id", agentId)
                addProperty("kex_pub", CryptoEngine.hex(r.dhPubForTest()!!))
                addProperty("nonce", CryptoEngine.b64e(ackNonce))
                addProperty("ct", CryptoEngine.b64e(ackCt))
            }
        }

        fun decryptFrame(frame: JsonObject): JsonObject {
            val sid = sessionId!!
            val hdr = frame.getAsJsonObject("hdr")
            val header = DoubleRatchet.EncryptedHeader(
                rk = CryptoEngine.fromHex(hdr.get("rk").asString),
                n = hdr.get("n").asInt,
                pn = if (hdr.get("pn") != null) hdr.get("pn").asInt else 0,
            )
            val pt = ratchet!!.decrypt(
                header,
                CryptoEngine.b64d(frame.get("nonce").asString),
                CryptoEngine.b64d(frame.get("ct").asString),
                CryptoEngine.utf8("fb-v1|$sid|${frame.get("chat_id").asString}|$agentId"),
            )
            return JsonParser.parseString(String(pt, Charsets.UTF_8)).asJsonObject
        }

        fun encryptFrame(frameType: String, chatId: String, payload: Map<String, Any?>): JsonObject {
            val sid = sessionId!!
            val enc = ratchet!!.encrypt(
                CryptoEngine.utf8(CryptoEngine.canonicalJson(payload)),
                CryptoEngine.utf8("fb-v1|$sid|$chatId|$agentId"),
            )
            return JsonObject().apply {
                addProperty("type", frameType)
                addProperty("v", 1)
                addProperty("session_id", sid)
                addProperty("chat_id", chatId)
                addProperty("agent_id", agentId)
                add("hdr", JsonObject().apply {
                    addProperty("rk", enc.rkHex)
                    addProperty("n", enc.n)
                    addProperty("pn", enc.pn)
                    if (enc.rotHint) addProperty("rot", 1)
                })
                addProperty("nonce", CryptoEngine.b64e(enc.nonce))
                addProperty("ct", CryptoEngine.b64e(enc.ct))
                addProperty("ts", System.currentTimeMillis())
            }
        }
    }

    private fun freshPair(rotateEvery: Int = 100): Pair<PhoneCryptoPeer, TestAgentResponder> {
        val agentIdentity = CryptoEngine.x25519Generate()
        val agentPrekey = CryptoEngine.x25519Generate()
        val responder = TestAgentResponder("agent1", agentIdentity, agentPrekey, rotateEvery)
        val phone = PhoneCryptoPeer(
            agentId = "agent1",
            agentIkPubHex = h(agentIdentity.pub),
            agentSpkPubHex = h(agentPrekey.pub),
            rotateEvery = rotateEvery,
        )
        val hello = phone.makeHello(
            "sess-" + h(CryptoEngine.secureRandomBytes(6)),
            "chat-" + h(CryptoEngine.secureRandomBytes(5)),
        )
        val ack = responder.respondToHello(hello)
        phone.consumeAck(ack)
        return phone to responder
    }

    @Test
    fun handshakeFingerprintEqualityBothSides() {
        val (phone, responder) = freshPair()
        assertEquals(
            "session_fp identisch auf beiden Seiten (Abgleich-Wert, §5.2)",
            responder.sessionFp, phone.fingerprint,
        )
        assertTrue(phone.isActive())
        assertEquals("FP-ABGLEICH: ${phone.fingerprint}", phone.fpAbgleichText())
    }

    @Test
    fun helloFrameSchemaMatchesProtocol() {
        val agentIdentity = CryptoEngine.x25519Generate()
        val agentPrekey = CryptoEngine.x25519Generate()
        val phone = PhoneCryptoPeer("agent1", h(agentIdentity.pub), h(agentPrekey.pub))
        val hello = phone.makeHello("sess-schema-00001", "chat-schema-0001")
        assertEquals("crypto.hello", hello.get("type").asString)
        assertEquals(1, hello.get("v").asInt)
        assertEquals("sess-schema-00001", hello.get("session_id").asString)
        assertEquals("agent1", hello.get("agent_id").asString)
        listOf("ik_pub", "ek_pub", "spk_pub").forEach { key ->
            assertEquals("hello.$key = 64 Hex", 64, hello.get(key).asString.length)
        }
        assertFalse("vor hello_ack ist die Session NICHT aktiv", phone.isActive())
    }

    @Test
    fun encRoundtripBothDirectionsOver100Frames() {
        val (phone, responder) = freshPair()
        for (i in 0 until 60) {
            val outFrame = phone.encryptFrame("chat.send.enc", phone.chatId!!,
                mapOf("msg_id" to "m$i", "text" to "Phone Nachricht $i"))
            assertEquals("chat.send.enc", outFrame.get("type").asString)
            assertEquals("Agent entschlüsselt Phone-Frame",
                "Phone Nachricht $i", responder.decryptFrame(outFrame).get("text").asString)
            val inFrame = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
                mapOf("msg_id" to "r$i", "text" to "Agent Antwort $i"))
            assertEquals("Phone entschlüsselt Agent-Frame",
                "Agent Antwort $i", phone.decryptFrame(inFrame).get("text").asString)
        }
    }

    @Test
    fun frameSchemaExactlyLikePythonReference() {
        val (phone, responder) = freshPair()
        val frame = phone.encryptFrame("chat.send.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "schema"))
        assertTrue(frame.keySet().containsAll(
            setOf("type", "v", "session_id", "chat_id", "agent_id", "hdr", "nonce", "ct", "ts")))
        val hdr = frame.getAsJsonObject("hdr")
        assertTrue(hdr.keySet() == setOf("rk", "n", "pn"))
        assertEquals(64, hdr.get("rk").asString.length)
        assertEquals(12, CryptoEngine.b64d(frame.get("nonce").asString).size)
        assertEquals(frame.get("session_id").asString, phone.sessionId)
    }

    @Test
    fun replayBlockedDuplicateFrame() {
        val (phone, responder) = freshPair()
        val frame = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "einmal"))
        assertNull("Frame 1. Mal ok", runCatchingPhone(phone, frame))
        assertTrue("Duplikat = Replay-Drop (Cache-Verbrauch)", runCatchingPhone(phone, frame) != null)
    }

    @Test
    fun windowViolationAndNonceBindingBlocked() {
        val (phone, responder) = freshPair()
        val far = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "far"))
        val hdr = far.getAsJsonObject("hdr")
        val forgedN = hdr.get("n").asInt + 500
        hdr.addProperty("n", forgedN)
        far.addProperty("nonce", CryptoEngine.b64e(
            CryptoEngine.b64d(far.get("nonce").asString).copyOf(4) +
                CryptoEngine.longTo8BigEndian(forgedN.toLong())))
        assertTrue("n ≥ Nr+100 → Drop", runCatchingPhone(phone, far) != null)

        val mis = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "mis"))
        mis.getAsJsonObject("hdr").addProperty("n", mis.getAsJsonObject("hdr").get("n").asInt + 1)
        assertTrue("Nonce ≠ Zähler → Drop", runCatchingPhone(phone, mis) != null)
    }

    @Test
    fun outOfOrderFramesDecryptViaSkippedCache() {
        val (phone, responder) = freshPair()
        val frames = (0 until 4).map { i ->
            responder.encryptFrame("chat.reply.enc", phone.chatId!!,
                mapOf("msg_id" to "", "text" to "ooo$i"))
        }
        for (i in listOf(3, 0, 1, 2)) {
            assertEquals("ooo$i", phone.decryptFrame(frames[i]).get("text").asString)
        }
    }

    @Test
    fun wrongKeyAckRejectedSessionDiscarded() {
        val agentIdentity = CryptoEngine.x25519Generate()
        val agentPrekey = CryptoEngine.x25519Generate()
        val responder = TestAgentResponder("agent1", agentIdentity, agentPrekey)
        val phone = PhoneCryptoPeer("agent1", h(agentIdentity.pub), h(agentPrekey.pub))
        val hello = phone.makeHello("sess-wrongkey-001", "chat-wrongkey-01")
        val ack = responder.respondToHello(hello)
        // Tag kippen = Wrong-Key/MITM-Simulation (Spec §6: KEIN Cleartext-Fallback)
        val corrupted = CryptoEngine.b64d(ack.get("ct").asString).copyOf().also {
            it[it.size - 1] = (it[it.size - 1].toInt() xor 0x5a).toByte()
        }
        ack.addProperty("ct", CryptoEngine.b64e(corrupted))
        try {
            phone.consumeAck(ack)
            fail("Wrong-Key hello_ack muss CryptoFrameException werfen")
        } catch (expected: CryptoFrameException) {
        }
        assertFalse("Session verworfen — NICHT aktiv", phone.isActive())
    }

    @Test
    fun rotateHintTriggersFreshHelloSupport() {
        val (phone, responder) = freshPair(rotateEvery = 5)
        var hints = 0
        for (i in 0 until 7) {
            val frame = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
                mapOf("msg_id" to "", "text" to "r$i"))
            phone.decryptFrame(frame)
            if (phone.hasRotateHint(frame)) hints++
        }
        assertEquals("hdr.rot=1 genau nach N=5", 1, hints)
        // Session-Rotate = frische crypto.hello (§5.4): danach wieder volle Funktion.
        val hello2 = phone.makeHello("sess-rotated-0002", "chat-rotated-0002")
        phone.consumeAck(responder.respondToHello(hello2))
        val out = phone.encryptFrame("chat.send.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "nach-rotate"))
        assertEquals("nach-rotate", responder.decryptFrame(out).get("text").asString)
    }

    @Test
    fun oldPeerRatchetPubReplayBlocked() {
        val (phone, responder) = freshPair()
        val oldFrame = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "alt"))
        phone.decryptFrame(oldFrame)
        // Phone sendet → Agent rotiert je Peer-Announcement → Agent-Antwort
        // trägt den NEUEN Pub → Phone rotiert; der ALTE Frame ist nun Replay.
        phone.decryptFrame(
            responder.encryptFrame("chat.reply.enc", phone.chatId!!,
                mapOf("msg_id" to "", "text" to "neu1")))
        val out = phone.encryptFrame("chat.send.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "neu2"))
        responder.decryptFrame(out)
        phone.decryptFrame(
            responder.encryptFrame("chat.reply.enc", phone.chatId!!,
                mapOf("msg_id" to "", "text" to "neu3")))
        assertTrue("alter Ratchet-Pub = Old-Chain-Replay → Drop", runCatchingPhone(phone, oldFrame) != null)
    }

    @Test
    fun tsPflichtVerwirftEncFrameOhneZeitstempel() {
        // PH-3 F3 (Jäger P2): ts ist PFLICHT — ohne ts übersprang der Frame
        // bis jetzt die 10-min-Fenster-Prüfung (Replay-Schutz umgehbar).
        val (phone, responder) = freshPair()
        val frame = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "ohne ts"))
        frame.remove("ts")
        val exc = runCatchingPhone(phone, frame)
        assertTrue("enc-Frame ohne ts muss verworfen werden", exc is CryptoFrameException)
        assertEquals("ts fehlt (Pflicht)", exc!!.message)
        // Kontrolle: gleicher Frame MIT ts entschlüsselt normal (kein
        // False-Positive des Guards).
        val okFrame = responder.encryptFrame("chat.reply.enc", phone.chatId!!,
            mapOf("msg_id" to "", "text" to "mit ts"))
        assertEquals("mit ts", phone.decryptFrame(okFrame).get("text").asString)
    }

    /** decryptFrame geworfen? null = ok. */
    private fun runCatchingPhone(phone: PhoneCryptoPeer, frame: JsonObject): Exception? =
        try {
            phone.decryptFrame(frame)
            null
        } catch (e: Exception) {
            e
        }
}