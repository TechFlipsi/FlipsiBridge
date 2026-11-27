package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * W26-App: JVM-Tests des cron.add-Rückkanals (CronRemoveRules-Muster —
 * Frame-Vertrag je JSON-Kapsel, Backend-Caps fail-soft, Form-Fibel je Modus).
 * Vertrags-Sync je Adapter-Lane 5aa0498: Prompt PFLICHT je Gateway-Welt,
 * deliver = STRING-Pass-through (≤ 64, kein Bool), naive ISO je
 * Gateway-Konvention.
 */
class CronAddRulesTest {

    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw).asJsonObject

    private fun spec(
        name: String = "Tagesbericht",
        schedule: String = "0 9 * * 1-5",
        mode: CronAddRules.ScheduleMode? = CronAddRules.ScheduleMode.REPEAT,
        prompt: String = "Bericht schreiben",
        deliver: String? = null,
    ) = CronAddRules.JobSpec(
        name = name,
        schedule = schedule,
        mode = mode,
        prompt = prompt,
        deliver = deliver,
    )

    // ── frame(): Vertrag + Caps-VOR-Dem-Deckel ────────────────────────────────

    @Test
    fun addFrameCarriesTypeVersionAndJob() {
        val frame = CronAddRules.addFrame(spec(), "agent-a")!!
        assertEquals("cron.add", frame.get("type").asString)
        assertEquals(1, frame.get("v").asInt)
        val job = frame.get("job").asJsonObject
        assertEquals("Tagesbericht", job.get("name").asString)
        assertEquals("0 9 * * 1-5", job.get("schedule").asString)
        assertEquals("Bericht schreiben", job.get("prompt").asString)
        // deliver-WEG je leeren Pass-through (keine Füll-Felder).
        assertNull(job.get("deliver"))
    }

    @Test
    fun deliverStaysAStringLengthPassThrough() {
        // Adapter-Lane 5aa0498: deliver = STRING-Pass-through (≤ 64, KEIN Bool).
        val job = CronAddRules.addFrame(spec(deliver = " chat "), "agent-a")!!
            .get("job").asJsonObject
        assertEquals("chat", job.get("deliver").asString)
    }

    @Test
    fun promptIsGatewayObligation() {
        // create_job ohne Prompt = EMPTY_PAYLOAD → 'invalid' je Gateway-Welt —
        // die App sendet bewusst NICHT statt stiller Backend-Abweisung.
        assertNull(CronAddRules.addFrame(spec(prompt = "   "), "agent-a"))
        assertNull(CronAddRules.addFrame(spec(prompt = ""), "agent-a"))
    }

    @Test
    fun frameRefusesCapsOverrunsAndBlanks() {
        // Name > 128 (Backend-Deckel w26-brief-adapter.md TEIL 2).
        assertNull(CronAddRules.addFrame(spec(name = "x".repeat(129)), "agent-a"))
        assertNull(CronAddRules.addFrame(spec(name = "   "), "agent-a"))
        // Schedule > 128.
        assertNull(CronAddRules.addFrame(spec(schedule = "*".repeat(129)), "agent-a"))
        assertNull(CronAddRules.addFrame(spec(schedule = "   "), "agent-a"))
        // Prompt > 4096.
        assertNull(CronAddRules.addFrame(spec(prompt = "p".repeat(4097)), "agent-a"))
        // deliver > 64 (Kurzfeld-Pass-through je Adapter).
        assertNull(CronAddRules.addFrame(spec(deliver = "d".repeat(65)), "agent-a"))
    }

    @Test
    fun repeatModeValidatesExprShape() {
        // Je Modus geprüft (mode = REPEAT ⇒ Expr-Sanity PFLICHT).
        assertNull(CronAddRules.addFrame(spec(schedule = "unfug", mode = CronAddRules.ScheduleMode.REPEAT), "agent-a"))
        // Modus offener Vertrag (null) = Backend-Interpretation, kein App-Zwang.
        assertTrue(CronAddRules.addFrame(spec(schedule = "unfug", mode = null), "agent-a") != null)
    }

    // ── Form-Fibel je Modus ───────────────────────────────────────────────────

    @Test
    fun validRepeatExprAcceptsCommonSchatz() {
        assertTrue(CronAddRules.validRepeatExpr("0 9 * * 1-5"))
        assertTrue(CronAddRules.validRepeatExpr("*/5 * * * *"))
        assertTrue(CronAddRules.validRepeatExpr("30 8 1 * MON#1")) // Namen-Schatz
        assertTrue(CronAddRules.validRepeatExpr("0 0 1 1 ? 2027")) // 6+Feld mit Jahr
        assertTrue(CronAddRules.validRepeatExpr("  0 7,17 * * *  "))
    }

    @Test
    fun validRepeatExprRejectsFakes() {
        assertFalse(CronAddRules.validRepeatExpr(null))
        assertFalse(CronAddRules.validRepeatExpr(""))
        assertFalse(CronAddRules.validRepeatExpr("   "))
        // Kein Feld-Strand (3 Felder) — ehrlich abgewiesen statt Backend-Lüge.
        assertFalse(CronAddRules.validRepeatExpr("0 9 *"))
        assertFalse(CronAddRules.validRepeatExpr("*".repeat(129))) // Deckel
        assertFalse(CronAddRules.validRepeatExpr("immer täglich")) // Form-Fremd ohne Schatz
    }

    @Test
    fun onceIsoIsNaivePerGatewayConvention() {
        // Adapter-Lane 5aa0498: die Gateway-Welt macht NAIVE ISO aware in der
        // konfigurierten Hermes-Zeitzone — die App sendet bewusst NAIV.
        assertEquals("2026-12-01T09:30:00", CronAddRules.onceScheduleIso(2026, 12, 1, 9, 30))
        assertEquals("2027-01-01T00:00:00", CronAddRules.onceScheduleIso(2027, 1, 1, 0, 0))
        assertFalse(CronAddRules.onceScheduleIso(2026, 12, 1, 9, 30)!!.contains('+'))
    }

    @Test
    fun onceIsoValidatesCalendarBoundaries() {
        // Schalt-Fibel: der 29.02. existiert 2028, nicht 2027 (nie raten).
        assertTrue(CronAddRules.onceScheduleIso(2028, 2, 29, 8, 0) != null)
        assertNull(CronAddRules.onceScheduleIso(2027, 2, 29, 8, 0))
        assertNull(CronAddRules.onceScheduleIso(2026, 13, 1, 8, 0))
        assertNull(CronAddRules.onceScheduleIso(2026, 4, 31, 8, 0))
        assertNull(CronAddRules.onceScheduleIso(2026, 12, 24, 24, 0))
        assertNull(CronAddRules.onceScheduleIso(2026, 12, 24, 12, 60))
    }

    @Test
    fun onceInputParsersAreStrict() {
        assertEquals(
            Triple(2026, 12, 1),
            CronAddRules.parseOnceDatePart("2026-12-01"),
        )
        assertEquals(
            Triple(2027, 1, 5),
            CronAddRules.parseOnceDatePart("2027-1-5"),
        )
        assertNull(CronAddRules.parseOnceDatePart("1.12.2026"))
        assertNull(CronAddRules.parseOnceDatePart(null))
        assertEquals(9 to 30, CronAddRules.parseOnceTimePart("9:30"))
        assertNull(CronAddRules.parseOnceTimePart("24 Uhr"))
        assertNull(CronAddRules.parseOnceTimePart(null))
    }

    // ── W26d (F2): Eingabe-Helfer — Ziffern-Tastatur braucht KEINE „-“/„:“-Taste ─

    @Test
    fun dateInputHelperFormsDashes() {
        // Der Nutzer tippt NUR Ziffern; der Strich setzt die Faser:
        assertEquals("2026", CronAddRules.formatOnceDateInput("2026"))
        assertEquals("2026-1", CronAddRules.formatOnceDateInput("20261"))
        assertEquals("2026-11", CronAddRules.formatOnceDateInput("202611"))
        assertEquals("2026-11-3", CronAddRules.formatOnceDateInput("2026113"))
        assertEquals("2026-11-30", CronAddRules.formatOnceDateInput("20261130"))
    }

    @Test
    fun dateInputHelperDropsNonDigitsAndCaps() {
        // Nicht-Ziffern fliegen (Fremd-IME/Sonderfall), über 8 Ziffern hinaus wird
        // beschnitten:
        assertEquals("2026-12-01", CronAddRules.formatOnceDateInput("2a0b2c6120d1"))
        assertEquals("2026-12-01", CronAddRules.formatOnceDateInput("202612015"))
        assertEquals("", CronAddRules.formatOnceDateInput("abc"))
        assertEquals("", CronAddRules.formatOnceDateInput(""))
        assertEquals("", CronAddRules.formatOnceDateInput(null))
    }

    @Test
    fun dateInputHelperStaysDeleteSafe() {
        // Läsch-Welt (Backspace-Simulation je hinten stehendem Zeichen): der
        // geformte Strich fliegt mit, nichts hängt fest.
        assertEquals("2026-12-0", CronAddRules.formatOnceDateInput("2026-12-0"))
        assertEquals("2026-12", CronAddRules.formatOnceDateInput("2026-12-"))
        assertEquals("2026-1", CronAddRules.formatOnceDateInput("2026-1"))
        assertEquals("2026", CronAddRules.formatOnceDateInput("2026-"))
        assertEquals("202", CronAddRules.formatOnceDateInput("2026".dropLast(1)))
    }

    @Test
    fun timeInputHelperFormsColon() {
        assertEquals("9", CronAddRules.formatOnceTimeInput("9"))
        assertEquals("90", CronAddRules.formatOnceTimeInput("90"))
        assertEquals("9:00", CronAddRules.formatOnceTimeInput("900"))
        assertEquals("09:00", CronAddRules.formatOnceTimeInput("0900"))
        assertEquals("09:01", CronAddRules.formatOnceTimeInput("09015"))
        assertEquals("", CronAddRules.formatOnceTimeInput(""))
        assertEquals("", CronAddRules.formatOnceTimeInput(null))
    }

    @Test
    fun onceInputHelperTextsFeedTheParser() {
        // Die Helfer bauen exakt die Formen, die die Parser-Faser liest
        // (Hint-Format bleibt JJJJ-MM-TT / HH:MM):
        assertEquals(
            Triple(2026, 11, 30),
            CronAddRules.parseOnceDatePart(CronAddRules.formatOnceDateInput("20261130")),
        )
        assertEquals(
            9 to 30,
            CronAddRules.parseOnceTimePart(CronAddRules.formatOnceTimeInput("930")),
        )
        assertEquals(9 to 30, CronAddRules.parseOnceTimePart(CronAddRules.formatOnceTimeInput("0930")))
    }

    // ── parse() + outcome(): fail-soft je Familie ─────────────────────────────

    @Test
    fun parsesAddAck() {
        val ack = CronAddRules.parse(
            json("""{"type":"cron.add_ok","ok":true,"cron_id":"c-n","name":"Tagesbericht"}"""),
        )!!
        assertEquals("c-n", ack.cronId)
        assertEquals("Tagesbericht", ack.name)
        assertTrue(ack.ok)
        assertEquals(CronAddRules.Outcome.SUCCESS, CronAddRules.outcome(ack))

        val notSupported = CronAddRules.parse(
            json("""{"type":"cron.add_ok","ok":false,"error":"not-supported"}"""),
        )!!
        assertEquals(CronAddRules.Outcome.NOT_SUPPORTED, CronAddRules.outcome(notSupported))
    }

    @Test
    fun missingOkFieldIsNeverFakeSuccess() {
        val ack = CronAddRules.parse(
            json("""{"type":"cron.add_ok","cron_id":"c-n"}"""),
        )!!
        assertFalse(ack.ok)
        assertEquals(CronAddRules.Outcome.ERROR, CronAddRules.outcome(ack))
    }

    @Test
    fun foreignFramesAreDropped() {
        assertNull(CronAddRules.parse(json("""{"type":"cron.pause_ok","ok":true}""")))
        assertNull(CronAddRules.parse(json("""{"ok":true}""")))
    }

    // ── W26c (Jäger-Blocker 2): die .enc-ACK-Welt ─────────────────────────────
    // Je aktiver E2E-Session baut der Adapter das ACK NUR als cron.add_ok.enc
    // (Körper NUR im ct) — die App entschlüsselt und parst DIESELBE Form in
    // CronAddRules.parse (der decrypted Körper trägt den inneren *_ok-Typ).

    @Test
    fun parsesEncWorldAckBody() {
        // Der ct-Nachrichtenkörper (Adapter-Inhalt im ct) → dieselbe Wirkung:
        val body = json(
            """{"type":"cron.add_ok","v":1,"agent_id":"agent-a",
                "cron_id":"c-new","name":"Tagesbericht","ok":true}""",
        )
        val ack = CronAddRules.parse(body)!!
        assertEquals("c-new", ack.cronId)
        assertEquals("Tagesbericht", ack.name)
        assertTrue(ack.ok)
        assertEquals(CronAddRules.Outcome.SUCCESS, CronAddRules.outcome(ack))
    }

    // ── W26c (Jäger-Blocker 3): die Agent-Adresse am Anlegen-Frame ───────────

    @Test
    fun addFrameCarriesChosenAgent() {
        // Die Anlage braucht EIN GEWÄHLTES Ziel (W26c — je GENANNTEM Agenten
        // geroutet; blank = Frame unvollständig, BEWUSST NICHT gesendet).
        val frame = CronAddRules.addFrame(spec(), " alpha ")!!
        assertEquals("alpha", frame.get("agent_id").asString)
        assertEquals("cron.add", frame.get("type").asString)
    }

    @Test
    fun addFrameRefusesBlankAgent() {
        assertNull(CronAddRules.addFrame(spec(), null))
        assertNull(CronAddRules.addFrame(spec(), "   "))
    }
}