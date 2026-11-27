package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WELLE-13 P11 (Datum-Chip) — Regeln der Tageswechsel-Gruppierung als
 * JVM-Vertrag (die Composables rendern nur, die Regeln sind pur):
 *
 * - chipVisible: Datum bekannt UND verschieden vom Vorgänger (null-Vorgänger
 *   = erste Nachricht → Chip); leeres Datum = BEWUSST kein Chip (kein
 *   erfundenes Datum für alte Disk-Restore-Einträge).
 * - dayLabel: „Heute“/„Gestern“ je Schlüssel-Vergleich, sonst ausgeschrieben
 *   deutsches Datum; unbrauchbarer Schlüssel = null (kein Chip, ehrlich).
 * - dayKey: Tages-Schlüssel aus Unix-Millisekunden, stabil „yyyy-MM-dd“.
 */
class ChatDateRulesTest {

    // ── chipVisible ────────────────────────────────────────────────────────

    @Test
    fun chipVisibleOnKnownDayChangeAndFirstMessage() {
        assertTrue("Tageswechsel", ChatDateRules.chipVisible("2026-11-10", "2026-11-11"))
        assertTrue("erste Nachricht", ChatDateRules.chipVisible(null, "2026-11-11"))
    }

    @Test
    fun chipInvisibleOnSameDayOrUnknownDate() {
        assertFalse("gleicher Tag", ChatDateRules.chipVisible("2026-11-11", "2026-11-11"))
        assertFalse("kein Datum bekannt", ChatDateRules.chipVisible("2026-11-11", ""))
        assertFalse("erste Nachricht ohne Datum", ChatDateRules.chipVisible(null, ""))
    }

    // ── dayLabel ────────────────────────────────────────────────────────────

    @Test
    fun dayLabelTodayAndYesterday() {
        assertEquals("Heute", ChatDateRules.dayLabel("2026-11-11", "2026-11-11", "2026-11-10"))
        assertEquals("Gestern", ChatDateRules.dayLabel("2026-11-10", "2026-11-11", "2026-11-10"))
    }

    @Test
    fun dayLabelSpelledOutGermanDate() {
        val label = ChatDateRules.dayLabel("2026-11-08", "2026-11-11", "2026-11-10")
        assertNotNull(label)
        assertTrue("ausgeschrieben, deutsch", label!!.contains("November"))
    }

    @Test
    fun dayLabelUnknownOrBrokenDateIsNull() {
        assertNull("leer", ChatDateRules.dayLabel("", "2026-11-11", "2026-11-10"))
        assertNull("Müll-Schlüssel", ChatDateRules.dayLabel("kein-datum", "2026-11-11", "2026-11-10"))
    }

    // ── dayKey ──────────────────────────────────────────────────────────────

    @Test
    fun dayKeyIsStableIsoFormat() {
        val now = System.currentTimeMillis()
        val a = ChatDateRules.dayKey(now)
        val b = ChatDateRules.dayKey(now + 3_600_000L) // +1 h: i. d. R. derselbe Tag
        // ISO-Format garantiert; Gleichheit nur, solange die Stunde den Tag
        // nicht wechselt (23:00-Fall → bewusst nur das Format gepinnt).
        assertTrue(a.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertTrue(b.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }
}
