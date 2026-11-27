package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W23-11-App (TEIL 2): cron.remove-Rückkanal-Verband — Send-Frame exakt
 * ({type, cron_id}, Verbriefung Pflicht), Ack-Verarbeitung fail-soft
 * (cron.remove_ok primär + cron.remove_ack toleriert; fehlendes ok-Feld liest
 * ehrlich ERROR statt Fake-Erfolg), Ausgangs-Staffel je UI-Snack und die
 * Warten-Faltung (kein Loop, frischer cron.status räumt erledigte Merker).
 */
class CronRemoveRulesTest {

    // Parität CronPauseResumeRulesTest: der JSON-Parser-Deckel je Testklasse.
    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw).asJsonObject

    // ── Send-Vertrag ─────────────────────────────────────────────────────────

    @Test
    fun removeFrameCarriesExactlyTypeAndCronId() {
        // ZF2 (W27, Suite-Nachziehen): der ältere „exakt zwei Felder"-Verband
        // ist veraltet — seit W26c trägt der Frame die GENANNTE Agent-Adresse
        // als routing-Pflicht („removeFrameCarriesNamedAgent" pinnt sie).
        // Der Vertrag hier: der Feld-Satz ist GENAU type + cron_id + agent_id.
        val frame = CronRemoveRules.removeFrame("cron-1", "agent-a")!!
        assertEquals(3, frame.size())
        assertEquals(CronRemoveRules.REMOVE_FRAME, frame.get("type").asString)
        assertEquals("cron-1", frame.get("cron_id").asString)
        assertEquals("agent-a", frame.get("agent_id").asString)
    }

    @Test
    fun removeFrameRefusesBlankVerbriefung() {
        // Kein Blind-Frame am Draht: ohne Verbriefung NICHT senden (fail-soft).
        assertNull(CronRemoveRules.removeFrame(null, "agent-a"))
        assertNull(CronRemoveRules.removeFrame("", "agent-a"))
        assertNull(CronRemoveRules.removeFrame("   ", "agent-a"))
    }

    @Test
    fun removeFrameTrimsTheTarget() {
        assertEquals("cron-1", CronRemoveRules.removeFrame(" cron-1 ", "agent-a")!!.get("cron_id").asString)
    }

    // ── Ack-Verband (fail-soft) ───────────────────────────────────────────────

    private fun ack(
        type: String,
        ok: Boolean? = null,
        error: String? = null,
        cronId: String? = null,
    ): JsonObject {
        val json = JsonObject()
        json.addProperty("type", type)
        ok?.let { json.addProperty("ok", it) }
        error?.let { json.addProperty("error", it) }
        cronId?.let { json.addProperty("cron_id", it) }
        return json
    }

    @Test
    fun okFrameParsesFully() {
        val parsed = CronRemoveRules.parse(
            ack(CronRemoveRules.OK_FRAME, ok = true, cronId = "cron-1", error = "x"),
        )!!
        assertEquals("cron-1", parsed.cronId)
        assertTrue(parsed.ok)
        assertEquals("x", parsed.error)
    }

    @Test
    fun altSpellingIsAccepted() {
        val parsed = CronRemoveRules.parse(
            ack(CronRemoveRules.OK_FRAME_ALT, ok = false, error = CronRemoveRules.ERROR_NOT_SUPPORTED),
        )!!
        assertFalse(parsed.ok)
        assertEquals(CronRemoveRules.ERROR_NOT_SUPPORTED, parsed.error)
    }

    @Test
    fun unknownOrMissingTypeIsRejected() {
        assertNull(CronRemoveRules.parse(JsonObject()))
        assertNull(CronRemoveRules.parse(ack("cron.removed", ok = true)))
        assertNull(CronRemoveRules.parse(ack("pairing.device_alias_ok", ok = true)))
    }

    @Test
    fun missingOkFieldIsHonestFailure() {
        // Nie ein Fake-„gelöscht“ — fehlendes ok liest ehrlich als Fehlschlag.
        val parsed = CronRemoveRules.parse(ack(CronRemoveRules.OK_FRAME))!!
        assertFalse(parsed.ok)
        assertNull(parsed.error)
        assertNull(parsed.cronId)
    }

    @Test
    fun blankFieldsReadAsNull() {
        val parsed = CronRemoveRules.parse(
            ack(CronRemoveRules.OK_FRAME, ok = true, error = "  ", cronId = " "),
        )!!
        assertNull(parsed.error)
        assertNull(parsed.cronId)
    }

    // ── Ausgangs-Staffel (UI-Snack) ───────────────────────────────────────────

    @Test
    fun outcomeStaffelFollowsTheBrief() {
        // Brief TEIL 2: Erfolg (frischer Stand), „not-supported“-Ehrlichkeit,
        // sonst Fehl-Familie — fehlendes ok KEIN Fake-Erfolg.
        assertEquals(
            CronRemoveRules.Outcome.SUCCESS,
            CronRemoveRules.outcome(CronRemoveRules.RemoveAck("c", true, null)),
        )
        assertEquals(
            CronRemoveRules.Outcome.NOT_SUPPORTED,
            CronRemoveRules.outcome(CronRemoveRules.RemoveAck("c", false, "not-supported")),
        )
        assertEquals(
            CronRemoveRules.Outcome.ERROR,
            CronRemoveRules.outcome(CronRemoveRules.RemoveAck("c", false, "something-else")),
        )
        assertEquals(
            CronRemoveRules.Outcome.ERROR,
            CronRemoveRules.outcome(CronRemoveRules.RemoveAck(null, false, null)),
        )
        // ok=true gewinnt selbst über mitgeschleppte error-Felder (ehrlich, aber
        // der Erfolg ist der Frische-Verband): ok-first.
        assertEquals(
            CronRemoveRules.Outcome.SUCCESS,
            CronRemoveRules.outcome(CronRemoveRules.RemoveAck("c", true, "weird-echo")),
        )
    }

    // ── Warten-Faltung (kein Loop) ────────────────────────────────────────────

    @Test
    fun withPendingAddsAndDedupes() {
        assertEquals(
            setOf("a"),
            CronRemoveRules.withPending(emptySet(), "a"),
        )
        assertEquals(
            setOf("a"),
            CronRemoveRules.withPending(setOf("a"), "a"),
        )
        assertEquals(
            setOf("a", "b"),
            CronRemoveRules.withPending(setOf("a"), "b"),
        )
        // Blank bleibt OUT (der Frame würde ohnehin verweigert).
        assertEquals(setOf("a"), CronRemoveRules.withPending(setOf("a"), " "))
    }

    @Test
    fun withoutPendingRemovesOnlyEchoMatch() {
        val pending = setOf("a", "b")
        assertEquals(setOf("a"), CronRemoveRules.withoutPending(pending, "b"))
        // Echo-los = GROSSZÜGIG räumen ist Aufgabe des Aufrufers (RelayClient
        // leert komplett) — hier bleibt die Faltung ehrlich null-tolerant:
        assertSame(pending, CronRemoveRules.withoutPending(pending, null))
        assertSame(pending, CronRemoveRules.withoutPending(pending, "x"))
    }

    @Test
    fun sweepPendingDropsResolvedAndKeepsOutstanding() {
        val merged = mapOf(
            // Boss-Zeile MIT Verbriefung steht WEITERHIN im frischen Stand
            // (not-supported-Fall) — ihr Merker wartet weiter auf Ack bzw. Deckel.
            "boss" to listOf(
                DashboardRules.CronJobItem(
                    "B",
                    DashboardRules.CronJobState.PLANNED,
                    nextAtMs = null,
                    cronId = "there-after-not-supported",
                ),
            ),
            // Job OHNE Verbriefung (Alt-Stand): trägt nie einen Merker.
            "worker" to listOf(
                DashboardRules.CronJobItem("W", DashboardRules.CronJobState.RUNNING, nextAtMs = null, cronId = null),
            ),
        )
        // "gone" trägt KEINE Zeile mehr im frischen Stand → Merker erledigt;
        // Merker MIT Zeile im Stand bleiben (auf Ack bzw. Deckel — KEIN Loop
        // von hier).
        assertEquals(
            setOf("there-after-not-supported"),
            CronRemoveRules.sweepPending(
                setOf("gone", "there-after-not-supported"), merged,
            ),
        )
    }

    @Test
    fun sweepKeepsSameInstanceWhenNothingChanges() {
        val pending = setOf("a")
        // Kein sinnloses StateFlow-Emit (assertSame-Muster, AgentStatusRules):
        // Der Merker trägt noch einen Job im Stand → GLEICHE Instanz zurück.
        val merged = mapOf(
            "boss" to listOf(
                DashboardRules.CronJobItem("B", DashboardRules.CronJobState.PLANNED, nextAtMs = null, cronId = "a"),
            ),
        )
        assertSame(pending, CronRemoveRules.sweepPending(pending, merged))
    }

    @Test
    fun sweepOnEmptyPendingIsNoOp() {
        val merged = mapOf("boss" to listOf(DashboardRules.CronJobItem("B", DashboardRules.CronJobState.PLANNED, nextAtMs = null, cronId = "x")))
        assertSame(emptySet<String>(), CronRemoveRules.sweepPending(emptySet(), merged))
    }

    // ── W26c (Jäger-Blocker 2): die .enc-ACK-Welt ─────────────────────────────
    // Je aktiver E2E-Session baut der Adapter das ACK NUR als cron.remove_ok.enc
    // (Körper NUR im ct) — die App entschlüsselt und parst DIESELBE Form in
    // CronRemoveRules.parse.
    @Test
    fun parsesEncWorldAckBody() {
        val body = json(
            """{"type":"cron.remove_ok","v":1,"agent_id":"agent-a",
                "cron_id":"c-3","ok":true,"request_id":"req-e"}""",
        )!!
        val ack = CronRemoveRules.parse(body)
        assertEquals("c-3", ack!!.cronId)
        assertTrue(ack.ok)
        assertEquals(CronRemoveRules.Outcome.SUCCESS, CronRemoveRules.outcome(ack))
    }

    // ── W26c (Jäger-Blocker 3): die Agent-Adresse am Steuer-Frame ────────────

    @Test
    fun removeFrameCarriesNamedAgent() {
        val frame = CronRemoveRules.removeFrame("cron-1", " alpha ")!!
        assertEquals("alpha", frame.get("agent_id").asString)
        assertEquals("cron-1", frame.get("cron_id").asString)
    }

    @Test
    fun removeFrameOmitsAgentIdForAltRows() {
        // Alt-Stand ohne agent_id: Feld WEG (Relay-Fallback mit LOUD-Log).
        val frame = CronRemoveRules.removeFrame("cron-1", null)!!
        assertNull(frame.get("agent_id"))
    }
}