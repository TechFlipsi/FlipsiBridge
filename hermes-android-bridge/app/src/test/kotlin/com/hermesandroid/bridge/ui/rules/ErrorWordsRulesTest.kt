package com.hermesandroid.bridge.ui.rules

import com.hermesandroid.bridge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W26d (F1, UX-Report): JVM-Tests der Klartext-Wörter je Backend-Fehler-
 * familien-Code — die Snacks tragen verständliche Gründe (denied/too-many/
 * not-found/invalid/error/agent-unknown, je Werten ×3); UNBEKANNTE/leere
 * Codes bleiben offen (null) — die UI zeigt den rohen Code ehrlich weiter,
 * nie ein erfundenes Klartext-Wort (never-wrong).
 */
class ErrorWordsRulesTest {

    @Test
    fun plainWordsPerFamily() {
        assertEquals(R.string.error_word_too_many, ErrorWordsRules.plainWordRes("too-many"))
        assertEquals(R.string.error_word_not_found, ErrorWordsRules.plainWordRes("not-found"))
        assertEquals(R.string.error_word_denied, ErrorWordsRules.plainWordRes("denied"))
        assertEquals(R.string.error_word_invalid, ErrorWordsRules.plainWordRes("invalid"))
        assertEquals(R.string.error_word_error, ErrorWordsRules.plainWordRes("error"))
        assertEquals(R.string.error_word_agent_unknown, ErrorWordsRules.plainWordRes("agent-unknown"))
    }

    @Test
    fun trimAndCaseFallIntoFamily() {
        // Der Trim/case-Ausgleich (Backend-Code-Hygiene je Draht):
        assertEquals(R.string.error_word_denied, ErrorWordsRules.plainWordRes(" denied "))
        assertEquals(R.string.error_word_too_many, ErrorWordsRules.plainWordRes("TOO-MANY"))
    }

    @Test
    fun unknownAndBlankStayOpen() {
        // „not-supported“ hat eigene ehrliche Snacks (Rule-Outcomes) und läuft
        // NICHT durch die Klartext-Faser.
        assertNull(ErrorWordsRules.plainWordRes("not-supported"))
        assertNull(ErrorWordsRules.plainWordRes("unknown-device"))
        assertNull(ErrorWordsRules.plainWordRes(""))
        assertNull(ErrorWordsRules.plainWordRes("   "))
        assertNull(ErrorWordsRules.plainWordRes(null))
        assertNull(ErrorWordsRules.plainWordRes("totally-new-code"))
    }
}
