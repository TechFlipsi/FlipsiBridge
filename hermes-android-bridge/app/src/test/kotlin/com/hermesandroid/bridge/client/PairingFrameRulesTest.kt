package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 (Multi-Gerät): Anmelde-Tor-Frames — SPIEGEL des Backend-Vertrags (Commit
 * 24acda0, tools/android_relay.py B1): _pairing_request_emit sendet
 * {"type":"pairing.request","v":1,"request_id","device_id_masked","ip","ts",
 * "ttl_s"}; _handle_pairing_admin_frame nimmt approve/deny mit request_id-
 * Basis an (device_id löst das Relay SELBST auf über die request_id).
 *
 * PII-Regel: die App hält NUR die maskierte Kennung — approve/deny enthält
 * deshalb bewusst NUR {type, request_id}.
 */
class PairingFrameRulesTest {

    private fun requestJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", "pairing.request")
        obj.addProperty("v", 1)
        obj.addProperty("request_id", "req-123")
        obj.addProperty("device_id_masked", "dev_…ab12")
        obj.addProperty("ip", "10.0.0.9")
        obj.addProperty("ts", 1_774_400_000_000L)
        obj.addProperty("ttl_s", 600L)
        return obj
    }

    @Test
    fun parse_liefert_die_maskierten_felder() {
        val frame = PairingFrameRules.parse(requestJson())!!
        assertEquals("req-123", frame.requestId)
        assertEquals("dev_…ab12", frame.deviceMasked)
        assertEquals("10.0.0.9", frame.ip)
        assertEquals(1_774_400_000_000L, frame.ts)
        assertEquals(600L, frame.ttlS)
    }

    @Test
    fun parse_verwirft_fremden_typ_und_leerraeumer() {
        val other = requestJson()
        other.addProperty("type", "pairing.response")   // fremder Typ
        assertNull(PairingFrameRules.parse(other))
        val noId = requestJson()
        noId.addProperty("request_id", "  ")
        assertNull("ohne request_id gibt es keine Anzeige", PairingFrameRules.parse(noId))
        assertNull(PairingFrameRules.parse(JsonObject()))
    }

    @Test
    fun ttl_laeuft_ehrlich_ab() {
        val frame = PairingFrameRules.parse(requestJson())!!
        val ts = frame.ts
        assertFalse(frame.expired(ts + 599_000L))
        assertTrue("TTL-abgelaufen = abgelehnt (fail-closed am Relay)", frame.expired(ts + 601_000L))
    }

    @Test
    fun approve_und_deny_tragen_nur_die_request_id() {
        val approve = PairingFrameRules.approveFrame("req-123")!!
        assertEquals("pairing.approve", approve.get("type").asString)
        assertEquals(2, approve.entrySet().size)
        assertEquals("req-123", approve.get("request_id").asString)
        // bewusst KEIN device_id-Feld: die App kennt NUR device_id_masked.
        assertNull(approve.get("device_id"))
        assertNull(approve.get("device_id_masked"))
        val deny = PairingFrameRules.denyFrame("req-123")!!
        assertEquals("pairing.deny", deny.get("type").asString)
        assertEquals(2, deny.entrySet().size)
    }

    @Test
    fun blank_request_id_baut_keinen_draht() {
        assertNull(PairingFrameRules.approveFrame("  "))
        assertNull(PairingFrameRules.denyFrame(""))
    }
}
