package com.hermesandroid.bridge.crypto

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-Folgeregression (vc36-Drill 02.11.): Die Richtung Agent→Phone war NIE
 * cross-fixiert (Fixtures §5.7 deckten Agent-first + Kotlin↔Kotlin-Roundtrip
 * ab) — live fiel im Drill: „chat.event.enc-Entschlüsselung fehlgeschlagen“
 * für JEDE Agent-Antwort, obwohl hello_ack-Verify + Phone-Sendeframe
 * funktionierten.
 *
 * Replay des deterministischen Python-Cross-Traces (Generator:
 * scratch/e2e/ph3f/gen_trace.py gegen contrib/hermes-chat-plugin/adapter.py
 * @ 0937040): Phone-first-Sequenz hello → ack → Phone-Sende-Nachricht →
 * Agent-Frames (read/activity/reply/activity). Die Kotlin-Seite muss die
 * AGENT-Frames (Python-encrypt) NACH dem eigenen ersten Senden entschlüsseln
 * — byte-identisch zur Python-Referenz-Phone (PHONE_DECRYPT_0..3 im Trace).
 */
class PhoneCryptoPeerCrossReplyTest {

    private fun h(hex: String): ByteArray = CryptoEngine.fromHex(hex)

    // ── Trace-Werte (Python-Referenz, fb-cross01) ─────────────────────────────
    private val agentIkPub =
        "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
    private val agentSpkPub =
        "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"
    private val ikPub =
        "7b0d47d93427f8311160781c7c733fd89f88970aef490d8aa0ee19a4cb8a1b14"
    private val spkPub =
        "ff2ee45601ec1b67310c7790404585ae697331eee1c1f8cf2419731c1fff3e6b"
    private val ekPub =
        "38ab664bd86f77d7e66bdd9ae0792913a94fd8b33a1260027e4b46c1f4884c67"
    private val b1Pub =
        "1cf579aba45a10ba1d1ef06d91fca2aa9ed0a1150515653155405d0b18cb9a67"
    private val a2Pub =
        "30d3c865a48fceb3d6118577cf2e5f228d6ff69866264757785b253cb7a4806a"
    private val sessionFp =
        "84a2fc7e895b4cb95da40b008428c9d27134005c7d0cb976c5a19aa16daf322c"
    private val ackJson =
        """{"type":"crypto.hello_ack","v":1,"session_id":"fb-cross01","agent_id":"testagent",""" +
            """"kex_pub":"219e4d800da968d2a5fcb009c784f4746c7138edb9ee4844b739e830b05cf424",""" +
            """"nonce":"HB0eHyAhIiMkJSYn","ct":"5RasbEbX/nSfBnTnQKqrp+lQSmrOxrLXsSp+8npWOQqCEHGnlffh9tsaJw=="}"""

    /** Agent-Frames aus dem Trace: (nonce-b64, ct-b64) je Zähler 0..3. */
    private val agentFrames = listOf(
        "KissLQAAAAAAAAAA" to "EvCmFZPTp9/6k07o2zVlJfJB4oO18+SIfx+r+iYz4g==",
        "KissLQAAAAAAAAAB" to "/PUBMpZFr1IDra6Z4+EHVS5gCZ201jNoKcsabPreEw==",
        "KissLQAAAAAAAAAC" to "tSUlMml6ddFJjzHWa6qcEmOjgY+eH+4gcD9UfxSg3A==",
        "KissLQAAAAAAAAAD" to "33i5I5JriAdA+e8qkmVtnPCg35CymNX/ng9DIBM6bg==",
    )

    private fun peer(): PhoneCryptoPeer {
        val ek = CryptoEngine.X25519KeyPair(h("55".repeat(32)), h(ekPub))
        val b1 = CryptoEngine.X25519KeyPair(h("77".repeat(32)), h(b1Pub))
        val b2 = CryptoEngine.X25519KeyPair(h("99".repeat(32)), ByteArray(32))
        val keys = ArrayDeque(listOf(ek, b1, b2))
        return PhoneCryptoPeer(
            agentId = "testagent",
            agentIkPubHex = agentIkPub,
            agentSpkPubHex = agentSpkPub,
            identityIk = CryptoEngine.X25519KeyPair(h("33".repeat(32)), h(ikPub)),
            identitySpk = CryptoEngine.X25519KeyPair(h("44".repeat(32)), h(spkPub)),
            keyGen = { keys.removeFirst() },
        )
    }

    @Test
    fun phoneDecryptsPythonAgentFramesAfterPhoneFirstSend() {
        val nowMs = 1793580200000L
        val peer = peer()

        // 1) hello — Identity-/EK-Pubs exakt wie der Python-Trace.
        val hello = peer.makeHello("fb-cross01", "chat-cross01", nowMs)
        assertEquals(ikPub, hello.get("ik_pub").asString)
        assertEquals(ekPub, hello.get("ek_pub").asString)
        assertEquals(spkPub, hello.get("spk_pub").asString)
        assertEquals(sessionFp, peer.fingerprint)

        // 2) hello_ack-Verify (k_init) — Python-ack byte-exakt akzeptiert.
        val ack = com.google.gson.JsonParser.parseString(ackJson).asJsonObject
        peer.consumeAck(ack, nowMs)

        // 3) Phone-first-Senden: erste Sende-Nachricht (Alice-Schritt, B1).
        val msg0 = peer.encryptFrame(
            "chat.send.enc", "chat-cross01",
            mapOf("msg_id" to "m0", "text" to "E2E-Trace vc36"), nowMs)
        val hdr0 = msg0.get("hdr").asJsonObject
        assertEquals(b1Pub, hdr0.get("rk").asString)
        assertEquals(0, hdr0.get("n").asInt)
        assertEquals(0, hdr0.get("pn").asInt)

        // 4) KERN: die 4 Agent-Frames (Python-encrypt) entschlüsseln — das ist
        //    die im vc36-Drill ausgefallene Richtung.
        agentFrames.forEachIndexed { i, (nonceB64, ctB64) ->
            val frame = JsonObject().apply {
                addProperty("type", if (i == 2) "chat.reply.enc" else "chat.read.enc")
                addProperty("v", 1)
                addProperty("session_id", "fb-cross01")
                addProperty("chat_id", "chat-cross01")
                addProperty("agent_id", "testagent")
                add("hdr", JsonObject().apply {
                    addProperty("rk", a2Pub)
                    addProperty("n", i)
                    addProperty("pn", 0)
                })
                addProperty("nonce", nonceB64)
                addProperty("ct", ctB64)
                addProperty("ts", nowMs)
            }
            val pt = peer.decryptFrame(frame, nowMs)
            assertEquals("m0", pt.get("msg_id").asString)
        }
    }

    @Test
    fun concurrentAgentFramesDecryptWithoutRatchetDesync() {
        // P1b (vc36-Drill): Die 4 Agent-Frames treffen am Gerät SIMULTAN ein
        // (je Frame eine Dispatchers.IO-Coroutine — live-Beweis: 3 Thread-IDs
        // in 6 ms, alle Entschlüsselungen rot). Ohne Per-Peer-Sperre inter-
        // leave 4 parallele decryptFrame-Aufrufe die dhRatchet/KDF-CK-
        // Mutationen → Ketten-Desync. Mit @Synchronized: serialisiert, je
        // Reihenfolge korrekt (OOO über den Skipped-Key-Cache).
        val nowMs = 1793580200000L
        val peer = peer()
        val hello = peer.makeHello("fb-cross01", "chat-cross01", nowMs)
        assertEquals(sessionFp, peer.fingerprint)
        val ack = com.google.gson.JsonParser.parseString(ackJson).asJsonObject
        peer.consumeAck(ack, nowMs)
        val msg0 = peer.encryptFrame(
            "chat.send.enc", "chat-cross01",
            mapOf("msg_id" to "m0", "text" to "E2E-Trace vc36"), nowMs)
        assertEquals(b1Pub, msg0.get("hdr").asJsonObject.get("rk").asString)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(agentFrames.size)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val futures = agentFrames.mapIndexed { i, (nonceB64, ctB64) ->
                val frame = JsonObject().apply {
                    addProperty("type", if (i == 2) "chat.reply.enc" else "chat.read.enc")
                    addProperty("v", 1)
                    addProperty("session_id", "fb-cross01")
                    addProperty("chat_id", "chat-cross01")
                    addProperty("agent_id", "testagent")
                    add("hdr", JsonObject().apply {
                        addProperty("rk", a2Pub)
                        addProperty("n", i)
                        addProperty("pn", 0)
                    })
                    addProperty("nonce", nonceB64)
                    addProperty("ct", ctB64)
                    addProperty("ts", nowMs)
                }
                java.util.concurrent.CompletableFuture.supplyAsync({
                    start.await()
                    peer.decryptFrame(frame, nowMs).get("msg_id").asString
                }, pool)
            }
            start.countDown()
            futures.forEach { assertEquals("m0", it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * SEC-W1 W1C-8 (Parität zum Relay/Adapter-Fix): decryptFrame prüfte ts
     * nur gegen die VERGANGENHEIT — ein Zukunfts-ts (+1 h) passierte das
     * 10-min-Freshness-Fenster. Symmetrische Obergrenze: Frame-ts aus der
     * Zukunft → CryptoFrameException, gültiger ts weiter ok.
     */
    @Test
    fun futureTsFrameRejectedByFreshnessWindow() {
        val nowMs = 1793580200000L
        val peer = peer()
        val hello = peer.makeHello("fb-cross01", "chat-cross01", nowMs)
        assertEquals(sessionFp, peer.fingerprint)
        val ack = com.google.gson.JsonParser.parseString(ackJson).asJsonObject
        peer.consumeAck(ack, nowMs)
        peer.encryptFrame(
            "chat.send.enc", "chat-cross01",
            mapOf("msg_id" to "m0", "text" to "E2E-Trace vc36"), nowMs)

        fun frameWithTs(ts: Long): JsonObject = JsonObject().apply {
            addProperty("type", "chat.read.enc")
            addProperty("v", 1)
            addProperty("session_id", "fb-cross01")
            addProperty("chat_id", "chat-cross01")
            addProperty("agent_id", "testagent")
            add("hdr", JsonObject().apply {
                addProperty("rk", a2Pub)
                addProperty("n", 0)
                addProperty("pn", 0)
            })
            addProperty("nonce", agentFrames[0].first)
            addProperty("ct", agentFrames[0].second)
            addProperty("ts", ts)
        }

        // Zukunfts-ts (+1 h) → verworfen (früher: passierte still).
        try {
            peer.decryptFrame(frameWithTs(nowMs + 60L * 60 * 1000), nowMs)
            throw AssertionError("Zukunfts-ts muss CryptoFrameException werfen")
        } catch (expected: CryptoFrameException) {
            // "Frame-ts in der Zukunft (>10 min)" — exakt der neue Guard.
        }
        // Grenzfall +9 min = innerhalb des Fensters → weiter gültig.
        val pt = peer.decryptFrame(frameWithTs(nowMs + 9L * 60 * 1000), nowMs)
        assertEquals("m0", pt.get("msg_id").asString)
    }
}
