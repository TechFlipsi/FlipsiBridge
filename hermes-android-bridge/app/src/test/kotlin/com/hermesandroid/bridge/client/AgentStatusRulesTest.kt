package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W18-Kette: agent.status-Frame-Verband — Beweise der reinen Logik-Einheit
 * [AgentStatusRules], die RelayClient.handleAgentStatusFrame/.handleAgent-
 * StatusEnc faltet. Der Relay-Vertrag (parallel-gebaut): agent_id/working(bool)
 * /started_at/label(str≤64)/sub{?id≤64,name≤64}/end(bool) — sub != null =
 * Sub-Slot (upsert), end == true = entfernen; ohne sub = Haupt-Status
 * (working=false ohne sub setzt den Status ehrlich auf „fertig“).
 *
 * Der enc-Anteil (agent.status.enc) liest am Phone DIESELBEN klaren
 * Top-Level-Felder wie der Klartext-Verband (ct ist reiner Kettensync,
 * Spiegel des chat.activity.enc-Aufbaus im Relay) — der Test beweist, dass
 * beide Frame-Formen durch dasselbe Parse identisch faltbar sind.
 */
class AgentStatusRulesTest {

    private fun json(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    // ── Haupt-Status (sub == null) ─────────────────────────────────────────

    @Test
    fun haupt_status_ohne_sub_wird_gesetzt_und_working_false_ehrlich() {
        val up = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1700001234000,"label":"Sucht im Web"}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull("Haupt-Update geparst", up)
        assertNotNull("Status-Update vorhanden", up!!.status)
        val slot = up.status!!
        assertEquals("Status-Update trägt die agent_id", "jarvis", up.agentId)
        assertTrue("Working-Zwahrheit kommt durch", slot.working)
        assertEquals(1700001234000L, slot.startedAt)
        assertEquals("Label-Verband: Server-Maschinenwort unverändert", "Sucht im Web", slot.label)
        assertNull("Kein sub = keine Sub-Operation", up.subUpsert)
        assertNull(up.subRemoveKey)

        val down = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":false,
                    "started_at":1700001300000}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(down)
        val downSlot = down!!.status!!
        assertTrue("working=false ohne sub = Status ehrlich fertig", !downSlot.working)
        assertNull("Ende ohne Label bleibt ohne Label", downSlot.label)
    }

    @Test
    fun label_ueber_64_fail_soft_getroffen_status_bleibt_wahr() {
        val over = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1700001234000,"label":"${"x".repeat(65)}"}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(over)
        val overSlot = over!!.status!!
        assertTrue("Status bleibt wahr (working/started_at unverändert)", overSlot.working)
        assertNull("label > 64 = verletztes Feld verworfen (fail-soft)", overSlot.label)

        val exactly64 = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1700001234000,"label":"${"y".repeat(64)}"}"""),
            fallbackNowMs = 1L,
        )
        assertEquals("Vertrags-Obergrenze ≤64 ist gültig", "y".repeat(64), exactly64?.status?.label)

        val blank = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1700001234000,"label":"   "}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(blank)
        assertNull("Leeres Label = kein Label (keine Platzhalter)", blank!!.status?.label)
    }

    @Test
    fun pflicht_felder_fehlen_verwirft_frame_started_at_faellt_zurueck() {
        assertNull("ohne agent_id → verworfen", AgentStatusRules.parse(
            json("""{"type":"agent.status","working":true,"started_at":1}"""), 9L,
        ))
        assertNull("ohne working (Pflicht, chat.activity-Parität) → verworfen",
            AgentStatusRules.parse(
                json("""{"type":"agent.status","agent_id":"jarvis","started_at":1}"""), 9L,
            ))
        val fallback = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true}"""),
            fallbackNowMs = 1700099999000L,
        )
        assertEquals("started_at fehlt → Empfangszeit (fallbackNowMs)",
            1700099999000L, fallback?.status?.startedAt)
        // Falsche Typen bleiben Exceptions-frei (fail-soft) und verwerfen:
        assertNull(AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":"ja"}"""), 9L,
        ))
        // end=true OHNE sub ändert nichts am Status-Verband (end ist ein
        // Sub-Slot-Begriff — der Status zählt nur working/started_at/label):
        val endOhneSub = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":false,"end":true}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(endOhneSub)
        assertNull("end ohne sub = keine Entfernung möglich", endOhneSub!!.subRemoveKey)
        assertTrue("Status bleibt working=false wahr", !endOhneSub.status!!.working)
    }

    // ── Sub-Slot upsert/entfernen-Verband ──────────────────────────────────

    @Test
    fun sub_slot_upsert_und_ersetzung_selben_key() {
        val first = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1700001234000,"sub":{"id":"sub-1","name":"Code-Aufgaben"}}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(first)
        assertNull("Sub-Frame ohne end ändert den Haupt-Status NICHT", first!!.status)
        assertNull(first.subRemoveKey)
        assertNotNull(first.subUpsert)
        val sub = first.subUpsert!!
        assertEquals("Key = sub.id", "sub-1", sub.id)
        assertEquals("Anzeigename aus sub.name", "Code-Aufgaben", sub.name)
        assertEquals(1700001234000L, sub.startedAt)
        assertTrue(sub.working)

        var map: Map<String, Map<String, AgentStatusRules.SubAgentSlot>> = emptyMap()
        map = AgentStatusRules.withSubUpsert(map, "jarvis", sub)
        assertEquals("Sub-Slot landet je agent_id",
            "Code-Aufgaben", map["jarvis"]?.get("sub-1")?.name)

        val replacedFrame = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":false,
                    "started_at":1700001900000,"sub":{"id":"sub-1","name":"Renamer"}}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(replacedFrame)
        assertNotNull(replacedFrame!!.subUpsert)
        val replacedSub = replacedFrame.subUpsert!!
        map = AgentStatusRules.withSubUpsert(map, "jarvis", replacedSub)
        assertEquals("Upsert ersetzt vorhandenen Key (1 Slot, neuer Name)",
            1, map["jarvis"]?.size)
        assertEquals("Renamer", map["jarvis"]?.get("sub-1")?.name)
        assertTrue("working=false am Sub-Frame bleibt Sub-Zustand", !replacedSub.working)
    }

    @Test
    fun sub_slot_end_true_entfernt_und_leerer_agent_verschwindet() {
        val sub = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1,"sub":{"id":"sub-1","name":"Code-Aufgaben"}}"""),
            fallbackNowMs = 1L,
        )
        val subB = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":2,"sub":{"name":"Nur-Name-Slot"}}"""),
            fallbackNowMs = 1L,
        )
        val subOther = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"edwin","working":true,
                    "started_at":3,"sub":{"id":"sub-9","name":"Web-Recherche"}}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(sub); assertNotNull(subB); assertNotNull(subOther)
        var map: Map<String, Map<String, AgentStatusRules.SubAgentSlot>> = emptyMap()
        map = AgentStatusRules.withSubUpsert(map, "jarvis", sub!!.subUpsert!!)
        map = AgentStatusRules.withSubUpsert(map, "jarvis", subB!!.subUpsert!!)
        map = AgentStatusRules.withSubUpsert(map, "edwin", subOther!!.subUpsert!!)
        assertEquals("Slot ohne id fällt auf name als Key zurück",
            "Nur-Name-Slot", map["jarvis"]?.get("Nur-Name-Slot")?.name)

        val endFrame = AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":false,
                    "started_at":1700009000000,"end":true,"sub":{"id":"sub-1","name":"Code-Aufgaben"}}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(endFrame)
        assertEquals("end=true liefert den Entfernungs-Key", "sub-1", endFrame!!.subRemoveKey)
        assertNull("End-Frames ändern weder Haupt-Status noch upserten", endFrame.status)
        assertNull(endFrame.subUpsert)

        map = AgentStatusRules.withSubRemove(map, "jarvis", endFrame.subRemoveKey!!)
        assertEquals("sub-1 weg, „Nur-Name-Slot“ bleibt",
            setOf("Nur-Name-Slot"), map["jarvis"]?.keys)
        assertEquals("edwin unangetastet (agent_id-Scoping)",
            "Web-Recherche", map["edwin"]?.get("sub-9")?.name)

        val noOp = AgentStatusRules.withSubRemove(map, "jarvis", "sub-1")
        assertSame("Fehlender Key = GLEICHE Instanz (kein sinnloses StateFlow-Emit)", map, noOp)

        map = AgentStatusRules.withSubRemove(map, "jarvis", "Nur-Name-Slot")
        assertTrue("letzter Sub weg → leerer Agent verschwindet ganz", !map.containsKey("jarvis"))
    }

    @Test
    fun sub_name_ueber_64_oder_fehlend_fliegt_kein_slot() {
        // Name > 64 = verletzter Eintrag (parseSessionMeta-Parität): Frame
        // ändert NICHTS (weder Slot noch Haupt-Status):
        assertNull(AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1,"sub":{"id":"sub-1","name":"${"n".repeat(65)}"}}"""),
            fallbackNowMs = 1L,
        ))
        // Upsert ohne Anzeige-Namen (und ohne id) kann kein Slot sein:
        assertNull(AgentStatusRules.parse(
            json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                    "started_at":1,"sub":{"id":"sub-1"}}"""),
            fallbackNowMs = 1L,
        ))
        assertNull("Leeres sub-Objekt ohne id/name → nichts zu tun",
            AgentStatusRules.parse(
                json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                        "started_at":1,"sub":{}}"""),
                fallbackNowMs = 1L,
            ))
    }

    @Test
    fun sub_kap_entlaesst_aeltesten_slot_nie_den_frischen() {
        var map: Map<String, Map<String, AgentStatusRules.SubAgentSlot>> = emptyMap()
        (1..(AgentStatusRules.MAX_SUBS_PER_AGENT + 1)).forEach { i ->
            val frame = AgentStatusRules.parse(
                json("""{"type":"agent.status","agent_id":"jarvis","working":true,
                        "started_at":$i,"sub":{"id":"sub-$i","name":"Sub $i"}}"""),
                fallbackNowMs = 1L,
            )
            assertNotNull("Slot $i geparst", frame)
            map = AgentStatusRules.withSubUpsert(map, "jarvis", frame!!.subUpsert!!)
        }
        assertNotNull(map["jarvis"])
        val subs = map["jarvis"]!!
        assertEquals("Verband ist kein Archiv: Kap ${AgentStatusRules.MAX_SUBS_PER_AGENT}",
            AgentStatusRules.MAX_SUBS_PER_AGENT, subs.size)
        assertNull("sub-1 (ältester) entlassen", subs["sub-1"])
        assertEquals("frisch aufgedrückter Slot bleibt",
            "Sub ${AgentStatusRules.MAX_SUBS_PER_AGENT + 1}",
            subs["sub-${AgentStatusRules.MAX_SUBS_PER_AGENT + 1}"]?.name)
    }

    // ── enc-Empfang (Kettensync-Spiegel, Spiegel von chat.activity.enc) ────

    @Test
    fun enc_spegel_liest_dieselben_klaren_top_level_felder() {
        val clear = """{"type":"agent.status","agent_id":"jarvis","working":true,
                "started_at":1700001234000,"label":"Sucht im Web",
                "sub":{"id":"sub-1","name":"Code-Aufgaben"},"v":1}"""
        val encMirror = """{"type":"agent.status.enc","v":1,"agent_id":"jarvis",
                "working":true,"started_at":1700001234000,"label":"Sucht im Web",
                "sub":{"id":"sub-1","name":"Code-Aufgaben"},
                "session_id":"sess-1","hdr":{"rk":"aabbccdd","n":1,"pn":0},
                "nonce":"b64-b64-b64=","ct":"b64-b64=","ts":1700001234000}"""
        val parsedClear = AgentStatusRules.parse(json(clear), fallbackNowMs = 1L)
        val parsedEnc = AgentStatusRules.parse(json(encMirror), fallbackNowMs = 1L)
        assertNotNull(parsedClear); assertNotNull(parsedEnc)
        assertEquals("Klartext-Felder identisch geparst (Umschlags-Felder schweigen)",
            parsedClear, parsedEnc)
        assertNotNull("Sub-Verband läuft auch über den enc-Spiegel", parsedEnc!!.subUpsert)
        assertEquals("sub-1", parsedEnc.subUpsert?.id)
        // Haupt-Status-Verband ohne sub — Label läuft durch den enc-Spiegel:
        val encMain = AgentStatusRules.parse(
            json("""{"type":"agent.status.enc","agent_id":"jarvis","working":true,
                    "started_at":1700001234000,"label":"Sucht im Web",
                    "session_id":"s","hdr":{"rk":"a","n":0},"nonce":"x","ct":"y","ts":8}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(encMain)
        assertEquals("Label-Verband durch enc-Spiegel ungekürzt", "Sucht im Web", encMain!!.status?.label)
        assertTrue(encMain.status!!.working)
    }

    @Test
    fun enc_spegel_sub_end_verband_komplett() {
        val up = AgentStatusRules.parse(
            json("""{"type":"agent.status.enc","agent_id":"jarvis","working":true,
                    "started_at":5,"sub":{"id":"sub-1","name":"Code-Aufgaben"},
                    "session_id":"s","hdr":{"rk":"a","n":0},"nonce":"x","ct":"y","ts":6}"""),
            fallbackNowMs = 1L,
        )
        val end = AgentStatusRules.parse(
            json("""{"agent_id":"jarvis","working":false,"end":true,
                    "sub":{"id":"sub-1","name":"Code-Aufgaben"},
                    "session_id":"s","hdr":{"rk":"a","n":1},"nonce":"x","ct":"y","ts":7}"""),
            fallbackNowMs = 1L,
        )
        assertNotNull(up); assertNotNull(end)
        assertNull("enc-Sub-Frame ohne end ändert den Haupt-Status nicht", up!!.status)
        var map: Map<String, Map<String, AgentStatusRules.SubAgentSlot>> = emptyMap()
        map = AgentStatusRules.withSubUpsert(map, "jarvis", up.subUpsert!!)
        assertEquals("Code-Aufgaben", map["jarvis"]?.get("sub-1")?.name)
        map = AgentStatusRules.withSubRemove(map, "jarvis", end!!.subRemoveKey!!)
        assertTrue("enc-End entfernt den Slot (SIRS-Design: verschwindet bei Ende)",
            !map.containsKey("jarvis"))
    }
}
