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
}