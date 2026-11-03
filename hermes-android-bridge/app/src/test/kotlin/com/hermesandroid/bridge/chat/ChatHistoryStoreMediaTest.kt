package com.hermesandroid.bridge.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MEDIA-T1: ChatHistoryStore persistiert Media-VERWEISE (kind/name/mime/
 * size/path/mediaId) — NIE die Bytes (Datei liegt im app-privaten chat_media-
 * Dir). Alte Text-Zeilen (ohne Media-Felder) müssen weiter parsen (Gson
 * füllt fehlende Felder mit null — kein Datenverlust beim App-Update).
 */
class ChatHistoryStoreMediaTest {

    private fun tmpRoot(): File =
        File.createTempFile("fb-chat-media", ".dir").let {
            it.delete()
            it.mkdirs()
            it
        }

    @Test
    fun `Media-Eintrag ueberlebt den Persist-Roundtrip komplett`() {
        val root = tmpRoot()
        val entry = ChatHistoryStore.Entry(
            seq = 1000L,
            agentId = "jarvis",
            chatId = "chat-media-0001",
            fromAgent = false,
            text = "[Foto] urlaub.jpg (1,2 MB)",
            time = "14:32",
            status = null,
            mediaKind = "image",
            mediaName = "urlaub.jpg",
            mediaMime = "image/jpeg",
            mediaSize = 1_258_291L,
            mediaPath = "/data/.../chat_media/med-1-urlaub.jpg",
            mediaId = "med-d41d8cd98f00b204",
        )
        ChatHistoryStore.append(root, entry)
        val loaded = ChatHistoryStore.load(root, "jarvis")
        assertEquals(1, loaded.size)
        val restored = loaded[0]
        assertEquals("image", restored.mediaKind)
        assertEquals("urlaub.jpg", restored.mediaName)
        assertEquals("image/jpeg", restored.mediaMime)
        assertEquals(1_258_291L, restored.mediaSize)
        assertEquals("/data/.../chat_media/med-1-urlaub.jpg", restored.mediaPath)
        assertEquals("med-d41d8cd98f00b204", restored.mediaId)
        assertEquals("[Foto] urlaub.jpg (1,2 MB)", restored.text)
    }

    @Test
    fun `Alte Text-Zeilen ohne Media-Felder parsen weiter (keine Null-Sorgen)`() {
        val root = tmpRoot()
        val file = ChatHistoryStore.fileFor(root, "jarvis")
        // Historisches Zeilenformat (vorm MEDIA-T1): KEINE Media-Felder.
        file.writeText(
            """
            {"seq":5,"agentId":"jarvis","chatId":"chat-0001","fromAgent":true,"text":"Hallo Sir","time":"09:41","status":"read"}
            """.trimIndent() + "\n",
        )
        val loaded = ChatHistoryStore.load(root, "jarvis")
        assertEquals(1, loaded.size)
        assertEquals("Hallo Sir", loaded[0].text)
        assertNull(loaded[0].mediaKind)
        assertNull(loaded[0].mediaName)
        assertNull(loaded[0].mediaSize)
        assertNull(loaded[0].mediaPath)
    }

    @Test
    fun `Text- und Media-Eintraege mischen sauber (loadAll bleibt chronologisch)`() {
        val root = tmpRoot()
        ChatHistoryStore.append(
            root,
            ChatHistoryStore.Entry(100L, "jarvis", "chat-0001", false, "Frage", "10:00"),
        )
        ChatHistoryStore.append(
            root,
            ChatHistoryStore.Entry(
                200L, "jarvis", "chat-0001", false, "[Foto] foto.jpg (45 KB)",
                "10:01", null,
                mediaKind = "image", mediaName = "foto.jpg", mediaMime = "image/jpeg",
                mediaSize = 46_080L, mediaPath = "/x/f.jpg", mediaId = "med-abc-000000001",
            ),
        )
        ChatHistoryStore.append(
            root,
            ChatHistoryStore.Entry(300L, "jarvis", "chat-0001", true, "Antwort", "10:02"),
        )
        val all = ChatHistoryStore.loadAll(root)
        assertEquals(listOf(100L, 200L, 300L), all.map { it.seq })
        assertTrue(all[1].mediaKind == "image")
        assertNull(all[0].mediaKind)
        assertNull(all[2].mediaKind)
    }
}