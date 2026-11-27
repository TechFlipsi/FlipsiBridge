package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W26-App: JVM-Tests des cron.pause/cron.resume-Rückkanals (CronRemoveRules-
 * Muster — Frame-Vertrag, fail-soft-Parse, Snack-Staffel, Warten-Merker).
 */
class CronPauseResumeRulesTest {

    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw).asJsonObject

    // ── frame(): Sendewelt je Op ─────────────────────────────────────────────

    @Test
    fun pauseFrameCarriesTypeVersionAndCronId() {
        val frame = CronPauseResumeRules.frame(CronPauseResumeRules.Op.PAUSE, " c-7 ", "agent-a")
        assertEquals("cron.pause", frame?.get("type")?.asString)
        assertEquals(1, frame?.get("v")?.asInt)
        assertEquals("c-7", frame?.get("cron_id")?.asString)
    }

    @Test
    fun resumeFrameCarriesType() {
        val frame = CronPauseResumeRules.frame(CronPauseResumeRules.Op.RESUME, "c-9", "agent-2")
        assertEquals("cron.resume", frame?.get("type")?.asString)
        assertEquals(1, frame?.get("v")?.asInt)
        assertEquals("c-9", frame?.get("cron_id")?.asString)
    }

    @Test
    fun frameRefusesBlankCronId() {
        // Deckel je remove-Parität: ohne Verbriefung wird BEWUSST NICHT gesendet.
        assertNull(CronPauseResumeRules.frame(CronPauseResumeRules.Op.PAUSE, null, "agent-a"))
        assertNull(CronPauseResumeRules.frame(CronPauseResumeRules.Op.PAUSE, "   ", "agent-a"))
        assertNull(CronPauseResumeRules.frame(CronPauseResumeRules.Op.RESUME, "", "agent-a"))
    }

    // ── parse(): fail-soft je Familie ─────────────────────────────────────────

    @Test
    fun parsesPrimaryOkVariants() {
        val pause = CronPauseResumeRules.parse(
            json("""{"type":"cron.pause_ok","ok":true,"cron_id":"c-7"}"""),
        )!!
        assertEquals(CronPauseResumeRules.Op.PAUSE, pause.op)
        assertEquals("c-7", pause.cronId)
        assertTrue(pause.ok)
        assertNull(pause.error)

        val resume = CronPauseResumeRules.parse(
            json("""{"type":"cron.resume_ok","ok":false,"error":"not-supported"}"""),
        )!!
        assertEquals(CronPauseResumeRules.Op.RESUME, resume.op)
        assertFalse(resume.ok)
        assertEquals("not-supported", resume.error)
    }

    @Test
    fun toleratesAltAckWriting() {
        // Schreib-Spiegel (Parität cron.remove_ack): gleicher Inhalt, andere Form.
        val alt = CronPauseResumeRules.parse(
            json("""{"type":"cron.pause_ack","ok":true}"""),
        )!!
        assertEquals(CronPauseResumeRules.Op.PAUSE, alt.op)
        assertTrue(alt.ok)
        val altResume = CronPauseResumeRules.parse(
            json("""{"type":"cron.resume_ack","ok":true,"cron_id":"c-2"}"""),
        )!!
        assertEquals("c-2", altResume.cronId)
    }

    @Test
    fun missingOkFieldIsNeverFakeSuccess() {
        val ack = CronPauseResumeRules.parse(
            json("""{"type":"cron.pause_ok","cron_id":"c-7"}"""),
        )!!
        assertFalse(ack.ok)
        assertEquals(
            CronPauseResumeRules.Outcome.ERROR,
            CronPauseResumeRules.outcome(ack),
        )
    }

    @Test
    fun foreignFramesAreDropped() {
        assertNull(CronPauseResumeRules.parse(json("""{"type":"cron.remove_ok","ok":true}""")))
        assertNull(CronPauseResumeRules.parse(json("""{"ok":true}""")))
        assertNull(CronPauseResumeRules.parse(json("""{"type":[]}""")))
    }

    @Test
    fun outcomeMapsFailSoft() {
        val ok = CronPauseResumeRules.parse(
            json("""{"type":"cron.resume_ok","ok":true}"""),
        )!!
        assertEquals(
            CronPauseResumeRules.Outcome.SUCCESS,
            CronPauseResumeRules.outcome(ok),
        )
        val notSupported = CronPauseResumeRules.parse(
            json("""{"type":"cron.pause_ok","ok":false,"error":"not-supported"}"""),
        )!!
        assertEquals(
            CronPauseResumeRules.Outcome.NOT_SUPPORTED,
            CronPauseResumeRules.outcome(notSupported),
        )
        val error = CronPauseResumeRules.parse(
            json("""{"type":"cron.pause_ok","ok":false,"error":"boom"}"""),
        )!!
        assertEquals(
            CronPauseResumeRules.Outcome.ERROR,
            CronPauseResumeRules.outcome(error),
        )
    }

    // ── Warten-Merker (Parität remove; W26c je RICHTUNG) ──────────────────────

    @Test
    fun pendingAddsOnceAndRefusesBlank() {
        var current = emptyMap<String, CronPauseResumeRules.Op>()
        current = CronPauseResumeRules.withPending(current, "a", CronPauseResumeRules.Op.PAUSE)
        current = CronPauseResumeRules.withPending(current, "a", CronPauseResumeRules.Op.PAUSE)
        assertEquals(mapOf("a" to CronPauseResumeRules.Op.PAUSE), current)
        // Deckel je remove-Parität — Blank-Verbriefung räumt NICHT ins Merkel.
        assertEquals(current, CronPauseResumeRules.withPending(current, "  ", CronPauseResumeRules.Op.RESUME))
    }

    @Test
    fun pendingEchoRemovesExactly() {
        var current = CronPauseResumeRules.withPending(
            CronPauseResumeRules.withPending(emptyMap(), "a", CronPauseResumeRules.Op.PAUSE),
            "b", CronPauseResumeRules.Op.RESUME,
        )
        current = CronPauseResumeRules.withoutPending(current, "a")
        assertEquals(mapOf("b" to CronPauseResumeRules.Op.RESUME), current)
        // Fremdes Echo räumt nichts.
        assertEquals(mapOf("b" to CronPauseResumeRules.Op.RESUME), CronPauseResumeRules.withoutPending(current, "x"))
    }

    @Test
    fun pendingEchoWithoutIdSweepsGenerously() {
        // Echo-los: EIN Auslöser-Faser — großzügig räumen (Parität remove):
        // ohne Echo-Wert räumt die Antwort den GESAMT-Wartestand.
        val current = CronPauseResumeRules.withPending(
            CronPauseResumeRules.withPending(emptyMap(), "a", CronPauseResumeRules.Op.PAUSE),
            "c", CronPauseResumeRules.Op.RESUME,
        )
        assertTrue(CronPauseResumeRules.sweepAllPending(current).isEmpty())
    }

    // ── W26c (Jäger-Sollte): Ziel-Status-Sweep der Toggle-Merker ─────────────
    // Der frische cron.status räumt nur, wenn die Zeile den ZIEL-Status zeigt
    // (Pause ⟶ paused, Resume ⟶ nicht-paused) — die kopierte „Job-weg“-
    // Semantik zeigte sonst Falsch-Warten je echtem Erfolg.

    @Test
    fun sweepDropsOnlyTargetStatusRows() {
        val feed = mapOf(
            "agent-a" to listOf(
                DashboardRules.CronJobItem("JobA", DashboardRules.CronJobState.PAUSED, nextAtMs = null, cronId = "id-1"),
                DashboardRules.CronJobItem("JobB", DashboardRules.CronJobState.PLANNED, nextAtMs = null, cronId = "id-2"),
                DashboardRules.CronJobItem("JobC", DashboardRules.CronJobState.RUNNING, nextAtMs = null, cronId = "id-3"),
            ),
        )
        val current = mapOf(
            "id-1" to CronPauseResumeRules.Op.PAUSE,
            "id-2" to CronPauseResumeRules.Op.RESUME,
            "id-3" to CronPauseResumeRules.Op.RESUME,
            "id-4" to CronPauseResumeRules.Op.PAUSE,   // Zeile noch nicht im Stand
            "id-5" to CronPauseResumeRules.Op.RESUME,  // Zeile noch nicht im Stand
        )
        val swept = CronPauseResumeRules.sweepPending(current, feed)
        // id-1 (paused=Ziel von PAUSE) + id-2/id-3 (nicht-paused = Ziel von
        // RESUME) räumen; id-4/id-5 warten auf Ack/Deckel:
        assertEquals(
            mapOf(
                "id-4" to CronPauseResumeRules.Op.PAUSE,
                "id-5" to CronPauseResumeRules.Op.RESUME,
            ),
            swept,
        )
        // Nichts zu tun → Identität (kein Zustands-Takt):
        val again = CronPauseResumeRules.sweepPending(swept, feed)
        assertEquals(swept, again)
        // Leerer Merker-Verband → unverändert:
        assertTrue(CronPauseResumeRules.sweepPending(emptyMap(), feed).isEmpty())
    }

    // ── W26c (Jäger-Blocker 2): die .enc-ACK-Welt ─────────────────────────────
    // Je aktiver E2E-Session baut der Adapter das ACK NUR als cron.pause_ok.enc
    // / cron.resume_ok.enc (Körper NUR im ct) — die App entschlüsselt und
    // parst DIESELBE Form in CronPauseResumeRules.parse.

    @Test
    fun parsesEncWorldAckBodies() {
        val pauseBody = json(
            """{"type":"cron.pause_ok","v":1,"agent_id":"agent-a",
                "cron_id":"c-7","ok":true}""",
        )
        val pause = CronPauseResumeRules.parse(pauseBody)!!
        assertEquals(CronPauseResumeRules.Op.PAUSE, pause.op)
        assertEquals("c-7", pause.cronId)
        assertTrue(pause.ok)
        assertEquals(
            CronPauseResumeRules.Outcome.SUCCESS,
            CronPauseResumeRules.outcome(pause),
        )
        val resumeBody = json(
            """{"type":"cron.resume_ok","ok":false,"error":"not-supported"}""",
        )
        val resume = CronPauseResumeRules.parse(resumeBody)!!
        assertEquals(CronPauseResumeRules.Op.RESUME, resume.op)
        assertFalse(resume.ok)
        assertEquals(
            CronPauseResumeRules.Outcome.NOT_SUPPORTED,
            CronPauseResumeRules.outcome(resume),
        )
    }

    // ── W26c (Jäger-Blocker 3): die Agent-Adresse am Steuer-Frame ────────────

    @Test
    fun toggleFrameCarriesNamedAgent() {
        // Die Zeile kam aus DER Agent-Gruppe — die Adresse wandert am Frame
        // (der Relay routet je GENANNTEM Agenten, fail-closed NACK).
        val frame = CronPauseResumeRules.frame(CronPauseResumeRules.Op.PAUSE, "c-7", " alpha ")!!
        assertEquals("alpha", frame.get("agent_id").asString)
        val resume = CronPauseResumeRules.frame(CronPauseResumeRules.Op.RESUME, "c-9", "agent-2")!!
        assertEquals("agent-2", resume.get("agent_id").asString)
    }

    @Test
    fun toggleFrameOmitsAgentIdForAltRows() {
        // Alt-Stand (""-Eimer, Frame ohne agent_id): das Feld bleibt WEG —
        // der Relay-Fallback (frischeste Session, LOUD-Log) ist legitim.
        val frame = CronPauseResumeRules.frame(CronPauseResumeRules.Op.PAUSE, "c-7", null)!!
        assertNull(frame.get("agent_id"))
        val blank = CronPauseResumeRules.frame(CronPauseResumeRules.Op.RESUME, "c-9", "   ")!!
        assertNull(blank.get("agent_id"))
    }
}