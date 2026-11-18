package com.hermesandroid.bridge.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B6 (Multi-Gerät): Backfill-Regeln — SPIEGEL des Backend-Vertrags (Commit
 * 24acda0, contrib/hermes-chat-plugin/adapter.py B3): Request-Frame heißt
 * chat.backfill_request (NICHT „backfill.request“), K je Agent = 200
 * (B3_BACKFILL_MAX), Batch ids-only; Status-Snapshot (Roadmap Kap. 4.1)
 * ids-only, Cap 500, ChatStatusRank-Monotonie (W20-Haken-Kette).
 */
class ChatBackfillRulesTest {

    private fun json(vararg fields: Any): JsonObject {
        val obj = JsonObject()
        for (field in fields) {
            @Suppress("UNCHECKED_CAST")
            val (key, value) = field as Pair<String, Any>
            when (value) {
                is String -> obj.addProperty(key, value)
                is Boolean -> obj.addProperty(key, value)
                is Number -> obj.addProperty(key, value)
            }
        }
        return obj
    }

    // ── B5: Request ──────────────────────────────────────────────────────────

    @Test
    fun request_nur_bei_leerem_verlauf() {
        assertTrue(ChatBackfillRules.shouldRequest(hasHistory = false))
        assertFalse("Bestand vorhanden = NIE anfordern (Kap. 4.1)", ChatBackfillRules.shouldRequest(hasHistory = true))
    }

    @Test
    fun request_frame_ids_only_mit_echten_namen_und_k_cap() {
        val frame = ChatBackfillRules.requestFrame("chat-abc")!!
        assertEquals("chat.backfill_request", frame.get("type").asString)
        assertEquals(1, frame.get("v").asInt)
        // ids-only: Wire-Anker + Plan-Feldname, KEIN Text/Inhalt im Klar-Frame.
        assertEquals("chat-abc", frame.get("chat_id").asString)
        assertEquals("chat-abc", frame.get("gateway_chat").asString)
        assertEquals(200, frame.get("limit").asInt)
        assertEquals("ids-only = type,v,chat_id,gateway_chat,limit", 5, frame.entrySet().size)
        assertNull("ohne Session-chat_id kein Request", ChatBackfillRules.requestFrame("  "))
    }

    @Test
    fun request_limit_klemmt_auf_k() {
        assertEquals(200, ChatBackfillRules.requestFrame("c", limit = 5_000)!!.get("limit").asInt)
        assertEquals(1, ChatBackfillRules.requestFrame("c", limit = 0)!!.get("limit").asInt)
        assertEquals(64, ChatBackfillRules.requestFrame("c", limit = 64)!!.get("limit").asInt)
    }

    @Test
    fun request_gate_einmal_je_connect() {
        val requested = HashSet<String>()
        assertTrue(ChatBackfillRules.mayRequestOnce(requested, "agent-a"))
        assertFalse("Zweit-Request ohne Connect verbrannt nur das DoS-Schild", ChatBackfillRules.mayRequestOnce(requested, "agent-a"))
        assertTrue(ChatBackfillRules.mayRequestOnce(requested, "agent-b"))
        ChatBackfillRules.unmarkRequested(requested, "agent-a")
        assertTrue("neuer Connect = neues Gate", ChatBackfillRules.mayRequestOnce(requested, "agent-a"))
        assertFalse(ChatBackfillRules.mayRequestOnce(requested, ""))
    }

    // ── B6: Ingest ───────────────────────────────────────────────────────────

    private fun entryJson(vararg fields: Pair<String, Any>): JsonObject = json(*fields)

    @Test
    fun entries_tolerante_aliases_und_role() {
        val batch = JsonObject().apply {
            add("entries", JsonArray().apply {
                add(entryJson("msg_id", "m-1", "text", "alt-alias", "from_agent", true, "ts", 300L))
                add(entryJson("messageId", "m-2", "message", "kuerzel", "fromAgent", false, "timestamp", 100L))
                add(entryJson("msg_id", "m-3", "text", "rolle-ki", "role", "assistant", "ts", 200L))
                add(entryJson("text", "rolle-nutzer", "role", "user", "ts", 150L))
            })
        }
        val entries = ChatBackfillRules.parseEntries(batch, fallbackTs = 9L)!!
        assertEquals(4, entries.size)
        // Chronologisch ASC — 100 → 150 → 200 → 300.
        assertEquals(listOf(100L, 150L, 200L, 300L), entries.map { it.ts })
        assertEquals(true, entries[0].fromAgent)   // alt: m-1 from_agent=true? NEIN —
        // Sortierung: 100 = m-2 (fromAgent=false), 150 = user, 200 = assistant,
        // 300 = m-1. Präzise:
        assertEquals("kuerzel", entries[0].text)
        assertEquals(false, entries[0].fromAgent)
        assertEquals(false, entries[1].fromAgent)
        assertEquals(true, entries[2].fromAgent)
        assertEquals(true, entries[3].fromAgent)
        assertEquals("m-2", entries[0].msgId)
        assertNull(entries[1].msgId)
    }

    @Test
    fun entries_dedupe_im_frame_und_leere_batch_ehrlich() {
        val batch = JsonObject().apply {
            add("entries", JsonArray().apply {
                add(entryJson("msg_id", "m-1", "text", "a", "ts", 10L))
                add(entryJson("msg_id", "m-1", "text", "a-zweimal", "ts", 11L))
                add(entryJson("text", "ohne-id", "ts", 12L))
            })
        }
        val entries = ChatBackfillRules.parseEntries(batch, fallbackTs = 9L)!!
        assertEquals(2, entries.size)
        assertEquals("a", entries[0].text)
        assertEquals("ohne-id", entries[1].text)
        // Leere Batch = leere Liste (NICHT null — „nichts mehr da“ ist ehrlich).
        val empty = ChatBackfillRules.parseEntries(
            JsonObject().apply { add("entries", JsonArray()) }, fallbackTs = 1L,
        )!!
        assertTrue(empty.isEmpty())
        // Kein Batch-Feld = null (Fremd-Payload → Frame verwerfen).
        assertNull(ChatBackfillRules.parseEntries(json("other", 1), fallbackTs = 1L))
    }

    @Test
    fun entries_k_cap_behaelt_die_neuesten_chronologisch() {
        val batch = JsonObject().apply {
            add("entries", JsonArray().apply {
                for (i in 0 until 250) {
                    add(entryJson("msg_id", "m-$i", "text", "t-$i", "ts", i.toLong()))
                }
            })
        }
        val entries = ChatBackfillRules.parseEntries(batch, fallbackTs = 0L)!!
        assertEquals(200, entries.size)                    // K-Cap (B3_BACKFILL_MAX)
        assertEquals("t-50", entries.first().text)         // die AELTESTEN 50 flogen
        assertEquals("t-249", entries.last().text)
        assertEquals((50 until 250).toList(), entries.map { it.ts.toInt() })
    }

    // ── B6: Status-Snapshot (ids-only, Cap 500) ─────────────────────────────

    @Test
    fun snapshot_array_und_map_formate() {
        val asArray = json("statuses", 0).apply {
            add("statuses", JsonArray().apply {
                add(entryJson("msg_id", "m-1", "status", "delivered"))
                add(entryJson("msg_id", "m-2", "status", "read"))
                add(entryJson("msg_id", "  ", "status", "read"))        // blank → raus
                add(entryJson("msg_id", "m-3", "status", "was-auch-immer")) // unbekannt → raus
            })
        }
        assertEquals(
            listOf("m-1" to "delivered", "m-2" to "read"),
            ChatBackfillRules.parseStatusSnapshot(asArray),
        )
        val asMap = json().apply {
            val map = JsonObject()
            map.addProperty("m-7", "queued")
            map.addProperty("m-8", "bogus")
            add("chat_status", map)
        }
        assertEquals(listOf("m-7" to "queued"), ChatBackfillRules.parseStatusSnapshot(asMap))
        assertNull(ChatBackfillRules.parseStatusSnapshot(json("other", 1)))
    }

    @Test
    fun snapshot_monotonie_w20_kette_faellt_nie_zurueck() {
        val existing = mapOf("a" to "read")
        val merged = ChatBackfillRules.mergeStatuses(existing, listOf("a" to "delivered"))
        assertEquals("verspätetes delivered kippt read NIE (Haken-Fusion)", "read", merged["a"])
        val healed = ChatBackfillRules.mergeStatuses(merged, listOf("a" to "read"))
        assertEquals("read bleibt read", "read", healed["a"])
        // failed-Schranke (Parität shouldApply) + Selbstheilung:
        val failed = ChatBackfillRules.mergeStatuses(emptyMap(), listOf("b" to "failed"))
        assertEquals("failed", failed["b"])
        val blocked = ChatBackfillRules.mergeStatuses(failed, listOf("b" to "delivered"))
        assertEquals("failed blockt nachkommend queued/delivered", "failed", blocked["b"])
        val healedB = ChatBackfillRules.mergeStatuses(blocked, listOf("b" to "read"))
        assertEquals("reales read heilt failed (Parität)", "read", healedB["b"])
        // Unbekannte Status gewinnen nie:
        assertEquals("read", ChatBackfillRules.mergeStatuses(healed, listOf("a" to "??"))["a"])
    }

    @Test
    fun snapshot_cap_500_eviction_rang_priorisiert() {
        var map = mapOf<String, String>()
        val incoming = ArrayList<Pair<String, String>>()
        repeat(497) { incoming.add("r-$it" to "read") }   // Endstände (ejectRank 3)
        incoming.add("q-1" to "queued")                   // flüchtig (ejectRank 0)
        incoming.add("q-2" to "delivered")                // Zwischenstand (ejectRank 1)
        incoming.add("q-3" to "queued")
        map = ChatBackfillRules.mergeStatuses(map, incoming)
        assertEquals(500, map.size)
        // Der 501. Insert zwingt die Eviction UND der Rang entscheidet:
        // ein flüchtiger queued-Stand fliegt vor jedem 'read'-Endstand
        // (Parität handleChatStatusFrame: Write-Back Side-Car dort).
        map = ChatBackfillRules.mergeStatuses(map, listOf("r-498" to "read"))
        assertEquals(500, map.size)
        assertTrue(!map.containsKey("q-1"))
        assertTrue(map.containsKey("q-2"))
        assertTrue(map.containsKey("q-3"))
        assertTrue(map.containsKey("r-498"))
        assertTrue(map.containsKey("r-1"))
    }

    @Test
    fun raise_bindings_nur_aufwaerts_und_kein_neu_recompose() {
        // Snapshot hebt die Bindungen auf den höheren Stand — nie senkt.
        val snapshot = mapOf("m-1" to "read", "m-2" to "queued", "m-3" to "delivered")
        val current = mapOf(1L to "delivered", 2L to "read")
        val bindings = mapOf(1L to "m-1", 2L to "m-2", 3L to "m-3")
        val raised = ChatBackfillRules.raiseBindingStatuses(current, bindings, snapshot)
        assertEquals("delivered → read: Aufstieg", "read", raised[1L])
        assertEquals("read + queued: fusioniert auf read", "read", raised[2L])
        assertEquals("neue Bindung übernimmt den Snapshot-Stand", "delivered", raised[3L])
        // Unverändert = DIESELBE Map (kein Zustand-Churn im Compose).
        val same = ChatBackfillRules.raiseBindingStatuses(current, emptyMap(), snapshot)
        assertSame(current, same)
    }
}
