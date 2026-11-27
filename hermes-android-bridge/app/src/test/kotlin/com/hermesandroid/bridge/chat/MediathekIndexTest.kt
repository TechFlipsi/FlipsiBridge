package com.hermesandroid.bridge.chat

import com.hermesandroid.bridge.ui.chat.MediathekIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4 (App-Lane C) — JVM-Verband des Mediathek-Index (ChatHistoryStore.Entry
 * ist JVM-pur, die STALE-Regel wird über echte Tempdateien bewiesen):
 * - fromEntries mapt NUR Media-Einträge (Text-Bubbles draußen), Fallbacks je
 *   unvollständigem Feld (name → Bubble-Text, kind → "file").
 * - STALE-REGEL: mediaPath leer → "Nur Verweis"; Pfad gesetzt + Datei weg →
 *   path="" + stale=true (Platzhalter-Karte, kein Crash).
 * - merge deduppt Store/LIVE via media_id UND Meta-Schlüssel (Store zuerst).
 */
class MediathekIndexTest {

    private fun entry(
        seq: Long = 1_000L,
        mediaKind: String? = null,
        mediaName: String? = "foto.jpg",
        mediaSize: Long? = 10_000L,
        mediaPath: String? = null,
        mediaId: String? = null,
        text: String = "Text",
        time: String = "10:00",
    ) = ChatHistoryStore.Entry(
        seq = seq, agentId = "alpha", chatId = "chat-1", fromAgent = false,
        text = text, time = time, status = null,
        mediaKind = mediaKind, mediaName = mediaName, mediaMime = "image/jpeg",
        mediaSize = mediaSize, mediaPath = mediaPath, mediaId = mediaId,
    )

    /** Tempbild + exists-Resolver (STALE-Beweis: löschen → stale). */
    private fun tempFile(): Pair<Path, (String) -> Boolean> {
        val file = Files.createTempFile("mediathek", "jpg")
        file.writeBytes(ByteArray(8))
        val exists: (String) -> Boolean = { java.io.File(it).isFile }
        return file to exists
    }

    @Test
    fun `fromEntries mapt nur Media-Einträge und füttert Fallbacks`() {
        val entries = listOf(
            entry(text = "reine Text-Bubble"),                 // kein Media → draußen
            entry(mediaKind = "image"),
            entry(mediaKind = "", mediaName = null, text = "Kind-Blank"), // kind leer → file-Fallback
        )
        val items = MediathekIndex.fromEntries(entries) { false }
        assertEquals(2, items.size)
        // Fallbacks: name = Bubble-Text bei leerem mediaName; kind-Blank → file.
        val blank = items.first { it.name == "Kind-Blank" }
        assertEquals("file", blank.kind)
        assertTrue(blank.fromStore)
        val image = items.first { it.kind == "image" }
        assertEquals("foto.jpg", image.name)
        assertEquals("10:00", image.time)
    }

    @Test
    fun `stale-regel - path ohne datei ergibt platzhalter (stale true), mit datei nutzbar`() {
        val (temp, exists) = tempFile()
        try {
            // Datei DA → Pfad bleibt, nicht stale.
            val live = MediathekIndex.fromEntries(
                listOf(entry(mediaKind = "image", mediaPath = temp.toString())), exists,
            )
            assertEquals(1, live.size)
            assertEquals(temp.toString(), live[0].path)
            assertFalse(live[0].stale)
            // Datei WEG → STALE-REGEL: path leer + stale=true (Platzhalter-Karte).
            temp.deleteIfExists()
            val stale = MediathekIndex.fromEntries(
                listOf(entry(mediaKind = "image", mediaPath = temp.toString())), exists,
            )
            assertEquals("", stale[0].path)
            assertTrue(stale[0].stale)
            // Leerer Pfad → "Nur Verweis" (kein stale).
            val ref = MediathekIndex.fromEntries(
                listOf(entry(mediaKind = "image", mediaPath = null)), exists,
            )
            assertEquals("", ref[0].path)
            assertFalse(ref[0].stale)
        } finally {
            temp.deleteIfExists()
        }
    }

    @Test
    fun `resolvePath - leer ergibt verweis, weg ergibt stale, da ergibt pfad`() {
        val (temp, exists) = tempFile()
        try {
            assertEquals("" to false, MediathekIndex.resolvePath(null, exists))
            assertEquals("" to false, MediathekIndex.resolvePath("   ", exists))
            assertEquals(temp.toString() to false, MediathekIndex.resolvePath(temp.toString(), exists))
            temp.deleteIfExists()
            assertEquals("" to true, MediathekIndex.resolvePath(temp.toString(), exists))
        } finally {
            temp.deleteIfExists()
        }
    }

    @Test
    fun `merge - media_id dedupt store gegen live, live-fuellen hinten, meta-fallback`() {
        val (temp, exists) = tempFile()
        try {
            val store = MediathekIndex.fromEntries(
                listOf(entry(mediaKind = "image", mediaId = "med-0123456789", mediaPath = temp.toString())),
                exists,
            )
            // LIVE-Zwilling mit GLEICHER media_id → bleibt draußen.
            val liveTwin = MediathekIndex.Item(
                mediaId = "med-0123456789", kind = "image", name = "foto.jpg",
                mime = "image/jpeg", size = 10_000L, time = "10:01",
                path = "", stale = false, fromStore = false,
            )
            // LIVE-NEU (keine media_id im Store) → hinten dran.
            val liveNew = MediathekIndex.Item(
                mediaId = "med-new98765432", kind = "voice", name = "voice.mp4",
                mime = "audio/mp4", size = 20_000L, time = "10:02",
                path = "", stale = false, fromStore = false,
            )
            val merged = MediathekIndex.merge(store, listOf(liveTwin, liveNew))
            assertEquals(2, merged.size)
            assertEquals(true, merged[0].fromStore)
            assertEquals(false, merged[1].fromStore)
            assertEquals("med-new98765432", merged[1].mediaId)
            // Meta-Schlüssel: LIVE-Eintrag OHNE id, aber gleichem kind/name/size/time
            // wie der Store-Eintrag → ebenfalls Dublette.
            val metaOnly = MediathekIndex.Item(
                mediaId = "", kind = "image", name = "foto.jpg",
                mime = "image/jpeg", size = 10_000L, time = "10:00",
                path = "", stale = false, fromStore = false,
            )
            assertEquals(1, MediathekIndex.merge(store, listOf(metaOnly)).size)
        } finally {
            temp.deleteIfExists()
        }
    }

    @Test
    fun `byKind sortiert je Abschnitt und blendet leere Sektionen aus`() {
        val items = listOf(
            MediathekIndex.Item("med-0123456789", "image", "a.jpg", "image/jpeg", 1L, "10:00", "", false, true),
            MediathekIndex.Item("med-voice987654", "voice", "v.mp4", "audio/mp4", 2L, "10:01", "", false, true),
        )
        assertEquals(1, MediathekIndex.byKind(items, "image").size)
        assertEquals(1, MediathekIndex.byKind(items, "voice").size)
        assertEquals(0, MediathekIndex.byKind(items, "file").size)
        assertEquals(0, MediathekIndex.byKind(items, "video").size)
    }

    @Test
    fun `dedupeKeys - id-premiert, meta deckt id-lose Eintraege`() {
        val withId = MediathekIndex.Item("med-0123456789", "image", "a.jpg", "", 1L, "10:00", "", false, true)
        val keys = MediathekIndex.dedupeKeys(withId)
        assertTrue(keys.contains("id:med-0123456789"))
        assertTrue(keys.contains("m:image|a.jpg|1|10:00"))
        val withoutId = MediathekIndex.Item("", "image", "a.jpg", "", 1L, "10:00", "", false, true)
        assertEquals(setOf("m:image|a.jpg|1|10:00"), MediathekIndex.dedupeKeys(withoutId))
    }
}
