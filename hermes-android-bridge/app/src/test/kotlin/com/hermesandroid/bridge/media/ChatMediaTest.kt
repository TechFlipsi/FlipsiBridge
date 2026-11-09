package com.hermesandroid.bridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * MEDIA-T1 JVM-Suite: Chunker + Assembler + Protokoll-Deckel.
 *
 * Verträge (Parität zu tools/android_relay.py MEDIA-T1 + contrib-Adapter):
 * - Chunking für ALLE Media (J4): 192-KiB-Raw-Chunks, Frame ~350 KB — die
 *   2-MB-WS-Grenze wird nie berührt.
 * - Reassembly: Out-of-Order-tolerant, Duplikate idempotent, sha256-Check
 *   fail-closed (Mismatch = MediaException, nichts wird geliefert).
 * - Deckel: 50 MB je Datei, 4096 Chunks, Struktur-Validierung vor Versand.
 */
class ChatMediaTest {

    // ── MediaProtocol ──────────────────────────────────────────────────────

    @Test
    fun `validateForSend akzeptiert gueltige Metadaten`() {
        assertNull(MediaProtocol.validateForSend("image", "urlaub.jpg", 1024))
        assertNull(MediaProtocol.validateForSend("voice", "memo.m4a", MediaProtocol.MAX_BYTES))
        assertNull(MediaProtocol.validateForSend("file", "bericht.pdf", 1))
    }

    @Test
    fun `validateForSend lehnt ungueltige Metadaten ab`() {
        assertNotNull(MediaProtocol.validateForSend("foto", "a.jpg", 1024))    // Art unbekannt
        assertNotNull(MediaProtocol.validateForSend("image", "", 1024))        // kein Name
        assertNotNull(MediaProtocol.validateForSend("image", "a.jpg", 0))      // leer
        assertNotNull(MediaProtocol.validateForSend("image", "a.jpg", -1))     // negativ
        assertNotNull(MediaProtocol.validateForSend(
            "image", "a.jpg", MediaProtocol.MAX_BYTES + 1))                    // über Deckel
        assertNotNull(MediaProtocol.validateForSend(
            "file", "x".repeat(129), 1024))                                    // Name zu lang
    }

    @Test
    fun `chunkCount deckelt auf Protokoll-Grenzen`() {
        assertEquals(1, MediaProtocol.chunkCount(1))
        assertEquals(1, MediaProtocol.chunkCount(MediaProtocol.CHUNK_BYTES.toLong()))
        assertEquals(2, MediaProtocol.chunkCount(MediaProtocol.CHUNK_BYTES.toLong() + 1))
        assertEquals(
            ((MediaProtocol.MAX_BYTES + MediaProtocol.CHUNK_BYTES - 1) / MediaProtocol.CHUNK_BYTES).toInt(),
            MediaProtocol.chunkCount(MediaProtocol.MAX_BYTES),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MediaProtocol.chunkCount(MediaProtocol.MAX_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) { MediaProtocol.chunkCount(0) }
    }

    @Test
    fun `formatBytes und kindLabel deutsch und stabil`() {
        assertEquals("1 KB", MediaProtocol.formatBytes(1024))
        assertEquals("1 KB", MediaProtocol.formatBytes(1))
        assertEquals("1,0 MB", MediaProtocol.formatBytes(1024 * 1024))
        assertEquals("1,5 MB", MediaProtocol.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("Foto", MediaProtocol.kindLabel("image"))
        assertEquals("Video", MediaProtocol.kindLabel("video"))
        assertEquals("Sprachnachricht", MediaProtocol.kindLabel("voice"))
        assertEquals("Datei", MediaProtocol.kindLabel("file"))
        assertEquals("Datei", MediaProtocol.kindLabel("unbekannt"))
    }

    @Test
    fun `mediaId-Form stimmt mit Relay-Regex ueberein`() {
        val id = MediaProtocol.newMediaId { ByteArray(16) { it.toByte() } }
        assertTrue(id.startsWith("med-"))
        assertTrue(MediaProtocol.validMediaId(id))
        assertTrue(MediaProtocol.validMediaId("med-d41d8cd98f00b204"))
        // Relay-Regex-Verstoß: zu kurz / falsche Zeichen / zu lang.
        assertTrue(!MediaProtocol.validMediaId("short"))
        assertTrue(!MediaProtocol.validMediaId("med-ungüültg!ä"))
        assertTrue(!MediaProtocol.validMediaId("med-" + "x".repeat(70)))
    }

    // ── MediaChunker ───────────────────────────────────────────────────────

    @Test
    fun `chunk roundtrip ueber mehrere Chunks ist byte-gena`() {
        val raw = ByteArray(250_000) { (it % 251).toByte() }
        val chunks = MediaChunker.chunk(raw)
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].seq)
        assertEquals(1, chunks[1].seq)
        assertTrue(chunks.all { it.total == 2 })
        // Chunk-Größen-Deckel (J4): raw ≤ 192 KiB, b64 < 350 KB.
        assertTrue(chunks.all { Base64.getDecoder().decode(it.b64).size <= MediaProtocol.CHUNK_BYTES })
        val reassembled = Base64.getDecoder().decode(chunks[0].b64) +
            Base64.getDecoder().decode(chunks[1].b64)
        assertTrue(reassembled.contentEquals(raw))
    }

    @Test
    fun `chunk exakt an der Chunkgrenze bleibt ein Chunk`() {
        val raw = ByteArray(MediaProtocol.CHUNK_BYTES) { 7 }
        val chunks = MediaChunker.chunk(raw)
        assertEquals(1, chunks.size)
        assertTrue(Base64.getDecoder().decode(chunks[0].b64).contentEquals(raw))
    }

    @Test
    fun `chunk lehnt leere und uebergrosse Dateien ab`() {
        assertThrows(MediaException::class.java) { MediaChunker.chunk(ByteArray(0)) }
        assertThrows(MediaException::class.java) {
            MediaChunker.chunk(ByteArray((MediaProtocol.MAX_BYTES + 1).toInt().coerceAtMost(Int.MAX_VALUE)))
        }
    }

    // ── MediaAssembler ──────────────────────────────────────────────────────

    @Test
    fun `assembler reassembliert sequenziell mit sha-Check`() {
        val raw = ByteArray(300_000) { (it % 7).toByte() }
        val sha = MediaProtocol.sha256Hex(raw)
        val chunks = MediaChunker.chunk(raw)
        val assembler = MediaAssembler()
        val decoder = Base64.getDecoder()
        var result: ByteArray? = null
        for (chunk in chunks) {
            result = assembler.add(
                "med-test-00000001", chunk.seq, chunk.total, chunk.b64,
                if (chunk.seq == 0) sha else null,
            )
        }
        assertTrue(result!!.contentEquals(raw))
        assertEquals(0, assembler.pendingCount())
    }

    @Test
    fun `assembler verkraftet out-of-order Chunks`() {
        val raw = ByteArray(400_000) { (it % 3).toByte() }
        val sha = MediaProtocol.sha256Hex(raw)
        val chunks = MediaChunker.chunk(raw)
        assertEquals(3, chunks.size)
        val assembler = MediaAssembler()
        val decoder = Base64.getDecoder()
        var result: ByteArray? = null
        for (chunk in chunks.reversed()) {
            result = assembler.add(
                "med-ooo-000000001", chunk.seq, chunk.total, chunk.b64,
                if (chunk.seq == 0) sha else null,
            )
        }
        assertTrue(result!!.contentEquals(raw))
    }

    @Test
    fun `assembler sha-Mismatch ist fail-closed`() {
        val raw = "inhaltsbytes".toByteArray()
        val wrongSha = MediaProtocol.sha256Hex("ANDERER-INHALT".toByteArray())
        val assembler = MediaAssembler()
        val b64 = Base64.getEncoder().encodeToString(raw)
        assertThrows(MediaException::class.java) {
            assembler.add("med-badsha-000001", 0, 1, b64, wrongSha)
        }
        assertTrue(
            "Assembly muss nach sha-Mismatch verworfen sein",
            assembler.pendingCount() == 0,
        )
    }

    @Test
    fun `assembler lehnt Struktur-Verstoesse ab`() {
        val assembler = MediaAssembler()
        val b64 = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThrows(MediaException::class.java) {
            assembler.add("kurz", 0, 1, b64, null)                       // media_id-Form
        }
        assertThrows(MediaException::class.java) {
            assembler.add("med-ok-000000001", 0, 0, b64, null)          // total 0
        }
        assertThrows(MediaException::class.java) {
            assembler.add("med-ok-000000001", 1, 1, b64, null)          // seq ≥ total
        }
        assertThrows(MediaException::class.java) {
            assembler.add("med-ok-000000001", 0, 1, "", null)           // leer
        }
        assertThrows(MediaException::class.java) {
            assembler.add("med-ok-000000001", 0, 1, "!!!kein-b64!!!", null)
        }
        assertThrows(MediaException::class.java) {
            assembler.add("med-ok-000000001", 0, 1,
                Base64.getEncoder().encodeToString(ByteArray(MediaProtocol.CHUNK_BYTES + 1)), null)
        }
    }

    @Test
    fun `assembler total-Wechsel wird abgelehnt`() {
        val assembler = MediaAssembler()
        val b64 = Base64.getEncoder().encodeToString(ByteArray(8))
        assembler.add("med-total-000001", 0, 2, b64, null)
        assertThrows(MediaException::class.java) {
            assembler.add("med-total-000001", 1, 3, b64, null)
        }
    }

    @Test
    fun `assembler Duplikate sind idempotent`() {
        val raw = "doppel-chunk".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(raw)
        val assembler = MediaAssembler()
        assertNull(assembler.add("med-dup-000000001", 0, 2, b64, null))
        assertNull(assembler.add("med-dup-000000001", 0, 2, b64, null))  // Duplikat ok
        val second = Base64.getEncoder().encodeToString("zweiter".toByteArray())
        val result = assembler.add("med-dup-000000001", 1, 2, second, null)
        assertNotNull(result)
        assertEquals("doppel-chunkzweiter", String(result!!))
    }

    @Test
    fun `assembler verdraengt aelteste Assemblies beim Cap`() {
        var clock = 0L
        val timed = MediaAssembler(maxConcurrent = 2, now = { clock })
        val b64 = Base64.getEncoder().encodeToString(ByteArray(4))
        timed.add("med-a-0000000001", 0, 2, b64, null)
        clock = 10
        timed.add("med-b-0000000002", 0, 2, b64, null)
        clock = 20
        timed.add("med-c-0000000003", 0, 2, b64, null)
        // Cap 2: die älteste (a) wurde verdrängt — pendingCount bleibt ≤ 2.
        assertTrue(timed.pendingCount() <= 2)
    }

    // ── W4-01c (App-Lane A, B3-Verband): Broker-msg_id aus Chunk-Response ───

    @Test
    fun `chunkResponseMsgId bindet NUR den Completion-Chunk seq gleich total-1`() {
        val body = "{\"chat_id\":\"chat-12345678\",\"msg_id\":\"3f0d2a9e-6f1b-4a1c-9a2b-8f2d6b7c5a41\",\"media_id\":\"med-x\",\"seq\":2,\"agent_id\":\"jarvis\",\"delivered\":true,\"queued\":false}"
        assertNull("früher Chunk (0/3) → nicht binden", MediaProtocol.chunkResponseMsgId(body, 0, 3))
        assertNull("Mittel-Chunk (1/3) → nicht binden", MediaProtocol.chunkResponseMsgId(body, 1, 3))
        assertEquals("Final-Chunk (2/3) → binden", "3f0d2a9e-6f1b-4a1c-9a2b-8f2d6b7c5a41", MediaProtocol.chunkResponseMsgId(body, 2, 3))
        assertEquals("Single-Chunk (0/1) = Final-Chunk → binden", "3f0d2a9e-6f1b-4a1c-9a2b-8f2d6b7c5a41", MediaProtocol.chunkResponseMsgId(body, 0, 1))
        assertNull("seq > total-1 = invalides Index-Verband → null", MediaProtocol.chunkResponseMsgId(body, 5, 3))
        assertNull("total < 1 → null", MediaProtocol.chunkResponseMsgId(body, 0, 0))
    }

    @Test
    fun `chunkResponseMsgId lehnt Alt-Relay-Bodies-ohne-msg-id-ab-ehrlicher Chip`() {
        assertNull("kein Body (alt-Relay-Response) → null", MediaProtocol.chunkResponseMsgId(null, 0, 1))
        assertNull("leerer Body → null", MediaProtocol.chunkResponseMsgId("   ", 0, 1))
        assertNull("Response ohne msg_id (Nutzer-Relay) → null",
            MediaProtocol.chunkResponseMsgId("{\"chat_id\":\"chat-x\",\"media_id\":\"med-x\",\"seq\":0}", 0, 1))
        assertNull("leere msg_id → null", MediaProtocol.chunkResponseMsgId("{\"msg_id\": \"\"}", 0, 1))
        assertNull("msg_id nicht als String (RED-05-Parität) → null", MediaProtocol.chunkResponseMsgId("{\"msg_id\": 42}", 0, 1))
        assertNull("msg_id als Liste → null", MediaProtocol.chunkResponseMsgId("{\"msg_id\": [\"a\"]}", 0, 1))
        assertNull("defekter JSON-Body → null (kein Exception-Pfad zur UI)", MediaProtocol.chunkResponseMsgId("{\"msg_id\": oops", 0, 1))
    }

    @Test
    fun `chunkResponseMsgId Verband str trim nonempty le128 B3 Relay Parität`() {
        // Relay W4-B3-Form: str(uuid.uuid4()) — 36-Zeichen-Form mit Trim-Rand:
        val padded = "{\"msg_id\": \"  abcdef01-2345-4678-9abc-def012345678  \"}"
        assertEquals("abcdef01-2345-4678-9abc-def012345678", MediaProtocol.chunkResponseMsgId(padded, 0, 1))
        assertNull(">128 Zeichen (B3-Ausreißer) → null", MediaProtocol.chunkResponseMsgId("{\"msg_id\": \"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}", 0, 1))
        assertEquals("128 Zeichen exakt gilt", "y".repeat(128), MediaProtocol.chunkResponseMsgId("{\"msg_id\": \"yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy\"}", 0, 1))
    }
}
