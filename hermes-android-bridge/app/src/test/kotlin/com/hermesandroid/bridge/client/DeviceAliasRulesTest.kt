package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W23-9 (Punkt W23-9): Umbenennen-Frames — SPIEGEL des Koordinator-
 * Verbands (VERTRAGS-KORREKTUR, siehe DeviceAliasRules-KDoc):
 *
 * - ZUM Relay: {"type": "pairing.device_alias", "request_id": <PRIMARY-Ziel
 *   (eindeutiges Kachel-Handle: die Maske ist am Relay NICHT eindeutig —
 *   nur Länge)>, "alias": <≤32, hygienisch>, "device_id"?: <Maske, Info>}.
 * - ZUM Phone: {"type": "pairing.device_alias_ok", "ok": <bool>,
 *   "error"?: <str>, "request_id": <Echo>, "alias": <Echo>,
 *   "device_id_masked"?: <Maske>}. Unvollständig = verwerfen (fail-soft);
 *   fehlendes ok-Feld = NICHT als Erfolg gelesen (nie ein Fake-„gespeichert“).
 */
class DeviceAliasRulesTest {

    private fun ackJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", "pairing.device_alias_ok")
        obj.addProperty("ok", true)
        obj.addProperty("request_id", "abc123")
        obj.addProperty("alias", "Küchen-Tablet")
        obj.addProperty("device_id_masked", "<masked:36>")
        return obj
    }

    // ── Send-Vertrag (aliasFrame): Handle = PRIMARY, Maske = Info ───────────

    @Test
    fun frameTraegtHandleAlsPrimaryZielUndMaskeNurAlsInfo() {
        val frame = DeviceAliasRules.aliasFrame("req-abc", "Werkstatt Handy", "<masked:36>")!!
        assertEquals("pairing.device_alias", frame.get("type").asString)
        // W23-9-KORREKTUR: das Handle ist die ZIELKERNUNG ...
        assertEquals("req-abc", frame.get("request_id").asString)
        // ... die Maske wandert NUR als Info-Feld daneben ...
        assertEquals("<masked:36>", frame.get("device_id").asString)
        assertEquals("Werkstatt Handy", frame.get("alias").asString)
    }

    @Test
    fun frameOhneMaskeBleibtVollGueltig() {
        val frame = DeviceAliasRules.aliasFrame("req-abc", "Namen", null)!!
        assertNull("device_id ist ein Info-Feld — fehlt es, bleibt der Frame gültig", frame.get("device_id"))
    }

    @Test
    fun frameOhneHandleWirdNichtGebaut() {
        // KORREKTUR-Zusammenspiel: ohne eindeutiges Handle ist es KEIN
        // vollständiger Frame — der Aufrufer sendet bewusst NICHT (fail-soft).
        assertNull(DeviceAliasRules.aliasFrame("", "Name", "<masked:36>"))
        assertNull(DeviceAliasRules.aliasFrame("   ", "Name", "<masked:36>"))
    }

    @Test
    fun frameOhneWirksamenAliasWirdNichtGebaut() {
        assertNull(DeviceAliasRules.aliasFrame("req-abc", "", "<masked:36>"))
        assertNull(DeviceAliasRules.aliasFrame("req-abc", "   ", "<masked:36>"))
        // Unsichtbare Reste (Steuerzeichen fliegen) = leer = kein Senden.
        assertNull(DeviceAliasRules.aliasFrame("req-abc", " \u0007\u0000\u001f ", "<masked:36>"))
    }

    @Test
    fun aliasHygieneKuerztUndKollabiert() {
        assertEquals("A B", DeviceAliasRules.normalizeAliasInput("  A\t\nB  "))
        assertEquals(32, DeviceAliasRules.ALIAS_MAX_CHARS)
        val long = "x".repeat(DeviceAliasRules.ALIAS_MAX_CHARS + 20)
        assertEquals(DeviceAliasRules.ALIAS_MAX_CHARS, DeviceAliasRules.normalizeAliasInput(long).length)
        assertEquals("", DeviceAliasRules.normalizeAliasInput(null))
    }

    // ── Ack-Verband (parse): {ok, error?} fail-soft ──────────────────────────

    @Test
    fun ackLiestDenKomplettenVerband() {
        val ack = DeviceAliasRules.parse(ackJson())!!
        assertEquals("abc123", ack.requestId)
        assertTrue(ack.ok)
        assertNull(ack.error)
        assertEquals("Küchen-Tablet", ack.alias)
        assertEquals("<masked:36>", ack.deviceIdMasked)
    }

    @Test
    fun ackAbweisungTraegtErrorUndOkFalse() {
        val denied = ackJson()
        denied.addProperty("ok", false)
        denied.addProperty("error", "denied")
        val ack = DeviceAliasRules.parse(denied)!!
        assertFalse(ack.ok)
        assertEquals("denied", ack.error)
        val bad = ackJson()
        bad.addProperty("ok", false)
        bad.addProperty("error", "bad")
        assertEquals("bad", DeviceAliasRules.parse(bad)!!.error)
    }

    @Test
    fun ackOhneOkFeldIstNieEinErfolg() {
        // Fail-soft-Verband: fehlend ok-Feld darf NICHT still als Erfolg
        // gegessen werden — ehrlich falsch statt Fake-„gespeichert“.
        val missing = ackJson()
        missing.remove("ok")
        val ack = DeviceAliasRules.parse(missing)!!
        assertFalse(ack.ok)
    }

    @Test
    fun ackOhneEchoHandleWirdVerworfen() {
        val noId = ackJson()
        noId.remove("request_id")
        assertNull(DeviceAliasRules.parse(noId))
        val blankId = ackJson()
        blankId.addProperty("request_id", "  ")
        assertNull(DeviceAliasRules.parse(blankId))
    }

    @Test
    fun ackFremdenTypVerworfen() {
        val other = ackJson()
        other.addProperty("type", "pairing.device_alias")
        assertNull("die Send-Richtung ist NIEMALS ein Ack", DeviceAliasRules.parse(other))
        assertNull(DeviceAliasRules.parse(JsonObject()))
    }

    @Test
    fun ackErrorFeldLeerWirdNull() {
        val ackJ = ackJson()
        ackJ.addProperty("error", "   ")
        ackJ.addProperty("ok", false)
        val ack = DeviceAliasRules.parse(ackJ)!!
        assertNull("leeres error-Feld = keiner Grund genannt", ack.error)
    }

    // ── Deckel: kein UI-Schleudern ──────────────────────────────────────────

    @Test
    fun ackTimeoutDeckelIstBegrenzt() {
        assertTrue(DeviceAliasRules.ACK_TIMEOUT_MS in 1..60_000L)
    }
}
