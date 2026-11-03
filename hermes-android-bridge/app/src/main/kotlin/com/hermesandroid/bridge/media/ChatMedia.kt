package com.hermesandroid.bridge.media

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * MEDIA-T1: Kern des Medien-/Datei-Versands im Chat (JVM-pur getestet).
 *
 * Protokoll (Parität zu tools/android_relay.py MEDIA-T1 + contrib-adapter):
 * - JE Chunk ein chat.media.enc-Frame durch die E2E-Ratchet-Sitzung — die
 *   Payload (Daten, base64) läuft NUR verschlüsselt (kein eigener Key-Pfad,
 *   Klartext-Fallback FORBIDDEN, fail-closed wie jeder enc-Frame).
 * - KLARE Metadaten je Frame: media_id, seq, total; seq=0 zusätzlich
 *   kind/name/mime/size (Relay-Validierung ohne Inhalts-Zugriff) + sha256
 *   der GESAMTEN Datei (Integrität am Empfänger, fail-closed).
 * - J4: Chunking für ALLE Media — die 2-MB-WS-Grenze wird nie berührt
 *   (192 KiB raw → Frame ~350 KB; kein Einzel-Frame-Video/-Bild).
 *
 * Chunk-Reihenfolge: normal sequenziell (Relay-Spool liefert FIFO); die
 * [MediaAssembler] verkraftet aber Out-of-Order (Relay-Flush-Gaps, parallele
 * Frames) — Vollständigkeit zählt, nicht die Reihenfolge.
 */
object MediaProtocol {

    /** Raw-Bytes je Chunk (→ ~350-KB-Frame inkl. Base64×2 + JSON). */
    const val CHUNK_BYTES = 192 * 1024

    /** Maximalgröße je Datei (Relay-Deckel ANDROID_RELAY_MEDIA_MAX_BYTES, Default 50 MB). */
    const val MAX_BYTES = 50L * 1024 * 1024

    /** Chunk-Zahl-Deckel (Parität zum Relay). */
    const val MAX_CHUNKS = 4096

    /** Gleichzeitige halbfertige Assemblies am Empfänger (RAM-Schutz). */
    const val MAX_CONCURRENT_ASSEMBLIES = 8

    /** Erlaubte Media-Arten. */
    val KINDS = setOf("image", "video", "file", "voice")

    /** Media-ID-Form (Relay: ^[A-Za-z0-9_-]{8,64}$). */
    private val MEDIA_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** SHA-256 der Bytes als Kleinbuchstaben-Hex (Integritäts-Check). */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Neue Media-ID (16 Zufalls-Bytes hex, Relay-Form). */
    fun newMediaId(random: () -> ByteArray): String =
        "med-" + random().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Metadaten-Validierung VOR dem Versand (fail-closed, spart Bandbreite). */
    fun validateForSend(kind: String, name: String, size: Long): String? {
        if (kind !in KINDS) return "unbekannte Media-Art: $kind"
        if (size < 1) return "Datei ist leer"
        if (size > MAX_BYTES) return "Datei zu groß (max ${formatBytes(MAX_BYTES)})"
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Datei ohne Namen"
        if (trimmed.length > 128) return "Dateiname zu lang (max 128 Zeichen)"
        return null
    }

    /** Chunk-Anzahl für eine Dateigröße (Deckel-geprüft). */
    fun chunkCount(size: Long): Int {
        require(size in 1..MAX_BYTES) { "Größe außerhalb des Protokoll-Deckels" }
        val total = (((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt())
        require(total in 1..MAX_CHUNKS) { "zu viele Chunks" }
        return total
    }

    /** Datei-Größe als deutsche Anzeige („1,2 MB" / „45 KB"). */
    fun formatBytes(size: Long): String {
        val mb = size >= 1024L * 1024L
        return if (mb) {
            String.format(java.util.Locale.GERMANY, "%.1f MB", size / (1024.0 * 1024.0))
        } else {
            "${maxOf(1L, size / 1024L)} KB"
        }
    }

    /** Anzeige-Label je Media-Art (deutsch; kein Emoji-only — A11y). */
    fun kindLabel(kind: String): String = when (kind) {
        "image" -> "Foto"
        "video" -> "Video"
        "voice" -> "Sprachnachricht"
        else -> "Datei"
    }

    /** Icon-Zeichen je Art (Text-Bubble-Fallback + TalkBack-Kontext). */
    fun kindGlyph(kind: String): String = when (kind) {
        "image" -> "\uD83D\uDCF7"      // 📷
        "video" -> "\uD83C\uDFAC"      // 🎬
        "voice" -> "\uD83C\uDFA4"      // 🎤
        else -> "\uD83D\uDCCE"         // 📎
    }

    /** Media-ID nach Relay-Form prüfen (vor Reassembly/Versand). */
    fun validMediaId(mediaId: String): Boolean = MEDIA_ID.matches(mediaId)
}

/** Ein Chunk (base64 der Rohteile) + Protokoll-Metadaten. */
data class MediaChunk(
    val seq: Int,
    val total: Int,
    val b64: String,
)

/**
 * Sender-seitiges Chunking: Bytes → [MediaChunk]-Liste (sequenzielle seq).
 * Wirft [MediaException] bei Regel-Verstoß (leer/zu groß) — fail-closed.
 */
object MediaChunker {

    fun chunk(bytes: ByteArray): List<MediaChunk> {
        if (bytes.isEmpty()) throw MediaException("Datei ist leer")
        if (bytes.size.toLong() > MediaProtocol.MAX_BYTES) {
            throw MediaException("Datei zu groß")
        }
        val total = MediaProtocol.chunkCount(bytes.size.toLong())
        val encoder = Base64.getEncoder()
        return (0 until total).map { seq ->
            val from = seq * MediaProtocol.CHUNK_BYTES
            val to = minOf(bytes.size, from + MediaProtocol.CHUNK_BYTES)
            MediaChunk(seq, total, encoder.encodeToString(bytes.copyOfRange(from, to)))
        }
    }
}

/**
 * Empfänger-seitiges Reassembly je media_id (Out-of-Order-tolerant).
 *
 * [add] liefert die VOLLSTÄNDIGEN Bytes zurück, sobald der letzte erwartete
 * Chunk angekommen ist (null = noch unvollständig). Duplikate sind idempotent.
 * Integrität: sha256 aus dem Chunk-0-Payload (falls mitgegeben) wird gegen
 * die fertigen Bytes geprüft — Mismatch = [MediaException] (fail-closed,
 * Assembly bleibt verworfen). Halbfertige Assemblies: Cap
 * [MediaProtocol.MAX_CONCURRENT_ASSEMBLIES] (älteste fliegen zuerst).
 */
class MediaAssembler(
    private val maxConcurrent: Int = MediaProtocol.MAX_CONCURRENT_ASSEMBLIES,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private class Assembly(
        val total: Int,
        val createdAt: Long,
    ) {
        var lastSeen: Long = createdAt
        var sha256: String? = null
        var received = 0
        val chunks = arrayOfNulls<ByteArray>(total)
    }

    private val assemblies = ConcurrentHashMap<String, Assembly>()

    /**
     * Chunk einsortieren. Rückgabe: komplette Datei beim LETZTEN Chunk, sonst
     * null. Wirft [MediaException] bei Struktur-/Integritäts-Verstoß.
     */
    @Synchronized
    fun add(mediaId: String, seq: Int, total: Int, b64: String, sha256: String?): ByteArray? {
        if (!MediaProtocol.validMediaId(mediaId)) throw MediaException("media_id ungültig")
        if (total < 1 || total > MediaProtocol.MAX_CHUNKS) throw MediaException("total ungültig")
        if (seq < 0 || seq >= total) throw MediaException("seq außerhalb [0, total)")
        if (b64.isEmpty()) throw MediaException("Chunk ohne Daten")
        val data = try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw MediaException("Chunk nicht base64")
        }
        if (data.size > MediaProtocol.CHUNK_BYTES) throw MediaException("Chunk über Größen-Deckel")
        evictStale()
        val assembly = assemblies[mediaId]
            ?: Assembly(total, now()).also { assemblies[mediaId] = it }
        if (assembly.total != total) throw MediaException("total weicht vom Chunk-0-Header ab")
        assembly.lastSeen = now()
        if (seq == 0 && !sha256.isNullOrBlank()) assembly.sha256 = sha256.lowercase()
        if (assembly.chunks[seq] == null) {
            assembly.chunks[seq] = data
            assembly.received += 1
        }
        if (assembly.received < assembly.total) return null
        assemblies.remove(mediaId)
        val blob = ByteArray(assembly.chunks.sumOf { it?.size ?: 0 })
        var offset = 0
        for (chunk in assembly.chunks) {
            if (chunk == null) throw MediaException("Chunk fehlt (Lücke in der Assembly)")
            System.arraycopy(chunk, 0, blob, offset, chunk.size)
            offset += chunk.size
        }
        val claimed = assembly.sha256
        if (claimed != null && MediaProtocol.sha256Hex(blob) != claimed) {
            throw MediaException("sha256-Integrität verletzt")
        }
        return blob
    }

    /** Laufende Assemblies (Diagnose/Tests). */
    @Synchronized
    fun pendingCount(): Int = assemblies.size

    private fun evictStale() {
        while (assemblies.size >= maxConcurrent) {
            val oldest = assemblies.minByOrNull { it.value.lastSeen } ?: return
            assemblies.remove(oldest.key)
        }
    }
}

/** Media-Übertragungs-Fehler (fail-closed, ohne Klartext-Inhalt). */
class MediaException(message: String) : Exception(message)