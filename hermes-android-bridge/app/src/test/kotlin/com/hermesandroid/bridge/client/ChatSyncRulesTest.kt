package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 (Multi-Gerät): Sync-Echo-Ingest-Regel — SPIEGEL des Backend-Vertrags
 * (contrib/hermes-chat-plugin/adapter.py B3, Commit 24acda0):
 * B3_SYNC_ENC_FRAME = "chat.sync.enc", B3_ECHO_FIELD = "sync",
 * B3_SYNC_OF_FIELD = "sync_of", B3_SYNC_ECHO_DEDUP = 256.
 *
 * KERN-INVARIANTE (Loop-Schutz, Roadmap Kap. 8 #6): Echo → NUR Bubble/Platte,
 * nie ein Send. Die Regel baut deshalb KEINE Sende-Frames — der Test hält den
 * Vertragsraum fest (parseEcho liefert Ingest-Daten, FIFO-Dedup je msg_id).
 */
class ChatSyncRulesTest {

    private fun payload(vararg fields: Any): JsonObject {
        val obj = JsonObject()
        var i = 0
        while (i + 1 < fields.size) {
            val key = fields[i] as String
            when (val value = fields[i + 1]) {
                is String -> obj.addProperty(key, value)
                is Boolean -> obj.addProperty(key, value)
                is Number -> obj.addProperty(key, value)
            }
            i += 2
        }
        return obj
    }

    private fun frame(msgId: String? = "broker-42"): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", ChatSyncRules.SYNC_FRAME)
        obj.addProperty("agent_id", "agent-a")
        obj.addProperty("chat_id", "chat-1")
        obj.addProperty("ts", 1_000L)
        obj.addProperty("msg_id", msgId)
        return obj
    }

    @Test
    fun parseEcho_verlangt_echo_marker_rendert_nur_bubble() {
        // Ohne Marker (Backend-B4-lose Struktur) = KEIN Sync-Frame → verwerfen.
        assertNull(ChatSyncRules.parseEcho(payload("text", "hi"), frame(), "agent-a", "chat-1", 1_000L))
        // Marker "sync": true → Bubble-Daten (fromAgent = false entscheidet der
        // Renderer; die Regel ist bewusst reiner Ingest-Verband).
        val echo = ChatSyncRules.parseEcho(
            payload("text", "hallo vom Telefon A", "sync", true, "sync_of", "m-1"),
            frame("broker-42"), "agent-a", "chat-1", 1_000L,
        )
        assertTrue(echo != null)
        assertEquals("hallo vom Telefon A", echo!!.text)
        assertEquals("m-1", echo.msgId)
        assertEquals("agent-a", echo.agentId)
        assertEquals(1_000L, echo.ts)
    }

    @Test
    fun sync_of_marker_genuegt_ohne_sync_bool() {
        val parse = ChatSyncRules.parseEcho(
            payload("text", "x", "sync_of", "m-9"),
            frame(), "agent-a", "chat-1", 5L,
        )
        assertTrue(parse != null)
        assertEquals("m-9", parse!!.msgId)
    }

    @Test
    fun origin_kaskade_bevozugt_sync_of_und_falls_back() {
        // sync_of gewinnt über origin_msg_id und msg_id.
        assertEquals(
            "m-sync",
            ChatSyncRules.originMsgId(
                payload("sync_of", "m-sync", "origin_msg_id", "m-origin", "msg_id", "m-inner"),
                frame("m-frame"),
            ),
        )
        assertEquals(
            "m-origin",
            ChatSyncRules.originMsgId(
                payload("origin_msg_id", "m-origin", "msg_id", "m-inner"),
                frame("m-frame"),
            ),
        )
        assertEquals(
            "m-inner",
            ChatSyncRules.originMsgId(payload("msg_id", "m-inner"), frame("m-frame")),
        )
        assertEquals("m-frame", ChatSyncRules.originMsgId(payload(), frame("m-frame")))
        assertEquals("m-frame", ChatSyncRules.originMsgId(payload("sync_of", "  "), frame("m-frame")))
        assertNull(ChatSyncRules.originMsgId(payload(), frame(null)))
    }

    @Test
    fun leerer_text_verworfen_kein_ghosting() {
        assertNull(ChatSyncRules.parseEcho(
            payload("text", "   ", "sync", true), frame(), "agent-a", "chat-1", 0L,
        ))
    }

    @Test
    fun fifo_merkt_msgid_und_verwirft_duplikate() {
        val seen = LinkedHashSet<String>()
        assertTrue(ChatSyncRules.isNewAndRemember(seen, "m-1"))
        assertFalse("gleiche Ursprungs-msg_id = bereits geplattet", ChatSyncRules.isNewAndRemember(seen, "m-1"))
        assertTrue(ChatSyncRules.isNewAndRemember(seen, "m-2"))
        assertFalse(ChatSyncRules.isNewAndRemember(seen, "m-2"))
    }

    @Test
    fun fifo_cap_256_raeumt_aelteste_merker() {
        val seen = LinkedHashSet<String>()
        for (i in 0 until ChatSyncRules.SEEN_CAP) {
            assertTrue(ChatSyncRules.isNewAndRemember(seen, "m-$i"))
        }
        assertEquals(ChatSyncRules.SEEN_CAP, seen.size)
        // Neues Merk über dem Cap → der ÄLTESTE Merker (m-0) flog raus:
        assertTrue(ChatSyncRules.isNewAndRemember(seen, "m-256"))
        assertEquals(ChatSyncRules.SEEN_CAP, seen.size)
        // m-0 ist jetzt wieder frei (Cap NACH dem Insert — Parität
        // Relay-_cap_chat_status, kein Überschuss-Rückstand):
        assertTrue(ChatSyncRules.isNewAndRemember(seen, "m-0"))
        assertEquals(ChatSyncRules.SEEN_CAP, seen.size)
        // Aber m-256 (noch gemerkt) bleibt ein Duplikat:
        assertFalse(ChatSyncRules.isNewAndRemember(seen, "m-256"))
    }

    @Test
    fun blank_msgid_wird_nicht_gem_und_durchgelassen() {
        val seen = LinkedHashSet<String>()
        // Blank = nichts merkbar → Blase geht durch (doppelter Schutz: UI-
        // Zwilling-Filter, ChatHistoryStore msg_id-Idempotenz).
        assertTrue(ChatSyncRules.isNewAndRemember(seen, null))
        assertTrue(ChatSyncRules.isNewAndRemember(seen, ""))
        assertTrue(ChatSyncRules.isNewAndRemember(seen, "  "))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun clear_raeumt_die_merker_kette() {
        val seen = LinkedHashSet<String>()
        ChatSyncRules.isNewAndRemember(seen, "m-1")
        ChatSyncRules.clear(seen)
        assertTrue(seen.isEmpty())
        assertTrue(ChatSyncRules.isNewAndRemember(seen, "m-1"))
    }
}
