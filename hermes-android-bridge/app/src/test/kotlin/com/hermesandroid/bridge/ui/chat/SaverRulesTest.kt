package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W22 A2 Fix 1 (Reviewer-A-Fund, HOCH — Saver off-by-one): JVM-Vertrag der
 * ChatMessageListSaver-Chiffre, ausgelagert nach [SaverRules]. Der Alt-Stand
 * chunkte mit off-by-one Konstanten (v4: 19 statt 20 geschrieben, v3: 15 statt
 * 16, v2: 14 statt 15) — ab Nachricht 2 verrutscht der Restore und
 * `g[15] as Boolean` castete den date-String (CCE). Diese Tests beweisen:
 *
 * - v4 save→restore identisch inkl. Media/Quote/Date/Call (Pflicht-Roundtrip),
 * - syntetische Alt-Bundles v3/v2/v1 restaurieren ohne Crash (call=null),
 * - der ALT-Stand ist am selben Bundle messbar kaputt (permanenter Rot-Beweis).
 */
class SaverRulesTest {

    private fun msg(
        id: Long = 1L,
        text: String = "Hallo Agent",
        time: String = "12:00",
        date: String = "2026-11-18",
        media: SaverRules.Media? = null,
        quote: SaverRules.Quote? = null,
        call: SaverRules.Call? = null,
    ) = SaverRules.Msg(
        id = id,
        agentId = 42,
        fromAgent = false,
        text = text,
        time = time,
        media = media,
        quote = quote,
        date = date,
        call = call,
    )

    // ── Pflicht: v4-Roundtrip (save→restore identisch inkl. Call) ───────────

    @Test
    fun v4RoundtripIdentischInklCallUndAllenAnhaengen() {
        val original = listOf(
            msg(
                id = 7L,
                text = "Erste Bubble",
                media = SaverRules.Media("md-1", "voice", "aufnahme.zip", 4_096L, "/p/vox"),
                quote = SaverRules.Quote(fromAgent = true, senderName = "Agent", text = "Zitiert"),
                call = null,
            ),
            msg(
                id = 8L,
                text = "",
                time = "12:02",
                call = SaverRules.Call("incoming", missed = false, durationSec = 161L),
            ),
            msg(
                id = 9L,
                text = "",
                time = "12:04",
                date = "2026-11-18",
                call = SaverRules.Call("incoming", missed = true, durationSec = 0L),
            ),
            msg(id = 10L, text = "Bare Bubble", date = ""),
        )
        val restored = SaverRules.fromFlat(SaverRules.flatList(original))
        // JEDE Nachricht restauriert eins-zu-eins — der Alt-Stand verrutschte
        // ab Nachricht 2 und warf CCE.
        assertEquals(original, restored)
        // Call-Anhänge bleiben erhalten (der Brief-Pflichtteil „inkl. call“):
        assertEquals(161L, restored[1].call?.durationSec)
        assertEquals(false, restored[1].call?.missed)
        assertEquals(true, restored[2].call?.missed)
    }

    @Test
    fun v4FlatTraegtVersionPlus20SlotsJeNachricht() {
        val flat = SaverRules.flatList(listOf(msg(), msg(id = 2L)))
        assertEquals(1 + 2 * SaverRules.GROUP_V4, flat.size)
        assertEquals(4, flat.first())
        assertEquals(SaverRules.GROUP_V4, SaverRules.chunkSize(4))
    }

    // ── Alt-Bundles je Version: restaurieren ohne Crash, ohne erfundene Call ─

    @Test
    fun v3AltBundleMitDateRestauriertOhneCrashUndOhneCall() {
        // v3-Alt-Draht (16 Slot-Gruppen, W13-Stand): 11 Basis + 4 Zitat + date.
        val msg1 = SaverRules.Msg(
            id = 3L, agentId = 5, fromAgent = true, text = "KI-Antwort", time = "08:15",
            quote = SaverRules.Quote(false, "Agent", "Original"), date = "2026-09-13",
        )
        val msg2 = msg1.copy(id = 4L, text = "Zweite", date = "2026-09-13")
        val body = listOf<Any>(
            3L, 5, true, msg1.text, msg1.time,
            false, "", "", "", 0L, "",
            true, false, "Agent", "Original",
            "2026-09-13",
            4L, 5, true, msg2.text, msg2.time,
            false, "", "", "", 0L, "",
            false, false, "", "",
            "2026-09-13",
        )
        val restored = SaverRules.fromFlat(listOf<Any>(3) + body)
        assertEquals(2, restored.size)
        assertEquals("2026-09-13", restored[0].date)
        assertEquals("2026-09-13", restored[1].date)
        assertEquals("KI-Antwort", restored[0].text)
        assertEquals("Zweite", restored[1].text)
        // Alt-Format: kein Call-Slot → call bleibt null (kein erfundener Anruf).
        assertNull(restored[0].call)
        assertNull(restored[1].call)
        // Der Alt-RESTORE (Chunk 15) hätte ab Nachricht 2 verrutscht — beide
        // Nachrichten tragen hier ihre ECHTE date-Spalte.
    }

    @Test
    fun v2AltBundleRestauriertOhneCrashOhneDateUndOhneCall() {
        // v2-Alt-Draht (15 Slot-Gruppen, W4-B-Stand): 11 Basis + 4 Zitat.
        val body = listOf<Any>(
            11L, 9, false, "Nutzer-Frage", "09:30",
            false, "", "", "", 0L, "",
            true, true, "Agent", "Zitattext",
            12L, 9, false, "Zweite", "09:31",
            false, "", "", "", 0L, "",
            false, false, "", "",
        )
        val restored = SaverRules.fromFlat(listOf<Any>(2) + body)
        assertEquals(2, restored.size)
        assertEquals("Nutzer-Frage", restored[0].text)
        assertEquals("Zweite", restored[1].text)
        assertEquals("Zitattext", restored[0].quote?.text)
        // v2 kennt weder date noch call — beides bleibt ehrlich leer/null.
        assertEquals("", restored[0].date)
        assertEquals("", restored[1].date)
        assertNull(restored[0].call)
        assertNull(restored[1].call)
    }

    @Test
    fun legacyV1BundleOhneVersionsHeaderRestauriertOhneCrash() {
        // v1-Legacy (11 Slot-Gruppen, PH2-MEDIA-Stand): Liste beginnt mit Long-id.
        val body = listOf<Any>(
            21L, 2, false, "Legacy-Nachricht", "10:05",
            false, "", "", "", 0L, "",
            22L, 2, true, "Legacy-KI", "10:06",
            false, "", "", "", 0L, "",
        )
        val restored = SaverRules.fromFlat(body)  // kein Int-Header → version 1
        assertEquals(2, restored.size)
        assertEquals(21L, restored[0].id)
        assertEquals("Legacy-Nachricht", restored[0].text)
        assertEquals(true, restored[1].fromAgent)
        assertNull(restored[0].quote)
        assertNull(restored[0].call)
        assertEquals("", restored[0].date)
    }

    @Test
    fun abgeschnittenerAbschnittCrashedNicht() {
        // Beschädigtes Bundle: letzte Gruppe unvollständig (20er-Zyklus) —
        // der Restore wirft NICHT, liefert die vollen Nachrichten.
        val original = listOf(msg(id = 1L), msg(id = 2L))
        val flat = SaverRules.flatList(original)
        val truncated = flat.dropLast(7)
        val restored = SaverRules.fromFlat(truncated)
        assertEquals(1, restored.size)
        assertEquals(1L, restored.first().id)
    }

    // ── Permanenter Rot-Beweis: der ALT-Stand ist am selben Bundle kaputt ────

    @Test
    fun altStandVerkalkuliertDieZweiteNachricht() {
        // Alt-Stand (vor Fix 1, Commit aaaf4f0): Chunk 19 + `g[15] as Boolean`.
        val flat = SaverRules.flatList(listOf(msg(), msg(id = 2L)))
        assertEquals(41, flat.size)
        val altChunks = flat.drop(1).chunked(19)  // alt: MSG_SAVER_GROUP = 19
        assertEquals(3, altChunks.size)           // 19/19/2 — Abschnitt verrutscht
        // Der alte Restore castete auf dem ERSTEN Chunk g[15] (= date-String der
        // Nach­richt 1, Slot 15 statt 16) als Boolean → CCE bei JEDEM
        // Mehr-Nachrichten-v4-Restore.
        val thrown = runCatching {
            @Suppress("UNCHECKED_CAST")
            altChunks[0][15] as Boolean
        }
        assertTrue(
            "Alt-Stand: date-String wurde als Boolean gecastet (CCE) — beweist den Fund",
            thrown.exceptionOrNull() is ClassCastException,
        )
        assertEquals(
            "date-Slot der Nachricht 1 lag im alten Chunk an Index 15",
            "2026-11-18",
            altChunks[0][15] as? String,
        )
    }
}
