package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W19 (17.11.26, Sir-Befunde): Sheet-Regeln (JVM) —
 * (a) Slider-Suggestion: App-Auswahl GEWINNT, ohne App-Pref ist der
 *     Gateway-Stand (agent.models 'effort') die Vorschlags-Raste;
 * (b) Server-Liste-only: sendbar ist ALLEIN ein exakter Listen-Wert —
 *     der Freitext-Pfad ist technisch zu (Sir: „nur Dropdown, nichts selbst
 *     einzugeben“), stale getippte Prefs fallen auf Auto.
 *
 * Mock-Werte 'test-model-…' = klar erkennbare Test-Mocks (Sir-Generik-Regel:
 * die App kennt KEINE Modell-Namen, KEINE Fallback-Listen — UI-Werte kommen
 * dynamisch vom agent.models-Frame).
 */
class ModelSheetRulesTest {

    @Test
    fun appEffortWinsOverGatewaySuggestion() {
        assertEquals(1, effortStageIndex("low", "max"))
        // App-Auswahl gilt auch ohne Gateway-Wissen in der App.
        assertEquals(3, effortStageIndex("max", null))
        // Weder App- noch Gateway-Wert → Raste 0 („aus“).
        assertEquals(0, effortStageIndex(null, null))
    }

    @Test
    fun gatewayEffortIsTheSuggestionWithoutAppPref() {
        // Befund-Regression: Slider stand bei Neuinstallation dauerhaft auf
        // Raste 0 („aus“), weil 'effort' nie verarbeitet wurde — jetzt zeigt
        // der Slider ohne App-Pref die Gateway-Raste als VORSCHLAG.
        assertEquals(3, effortStageIndex(null, "max"))
        assertEquals(1, effortStageIndex(null, "low"))
        assertEquals(2, effortStageIndex(null, "high"))
    }

    @Test
    fun unknownEffortWordsFailSoftToStageZero() {
        assertEquals(0, effortStageIndex(null, "unbekannt-stufe"))
        assertEquals(0, effortStageIndex("unbekannt-app-wahl", null))
    }

    @Test
    fun sendableModelOnlyFromServerList() {
        // Exakter Listen-Wert → sendbar.
        assertEquals(
            "test-model-a",
            sendableModel("test-model-a", listOf("test-model-a", "test-model-b")),
        )
        // Getippter Name (stale Prefs einer Vorgängerversion) → NICHT sendbar.
        assertNull(sendableModel("selber-getippter-name", listOf("test-model-a")))
        // Kein Serverwissen (Frame fehlt / leere Liste) → gar kein Modell senden.
        assertNull(sendableModel("test-model-a", null))
        assertNull(sendableModel("test-model-a", emptyList()))
        // Auto (null/blank) bleibt Auto — der Server entscheidet.
        assertNull(sendableModel(null, listOf("test-model-a")))
        assertNull(sendableModel("", listOf("test-model-a")))
        assertNull(sendableModel("   ", listOf("test-model-a")))
    }

    @Test
    fun thinkStagesContractStaysFourStages() {
        assertEquals(4, THINK_STAGES.size)
        assertNull(THINK_STAGES[0].second)
        assertEquals(listOf("aus", "leicht", "hoch", "max"), THINK_STAGES.map { it.first })
        assertEquals(listOf(null, "low", "high", "max"), THINK_STAGES.map { it.second })
    }
}
