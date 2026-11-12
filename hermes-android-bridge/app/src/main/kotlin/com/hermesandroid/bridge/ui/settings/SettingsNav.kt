package com.hermesandroid.bridge.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Welle 15 / P7 (Sir): Pfad-Anzeige der Top-Bar im Einstellungen-Screen.
 *
 * KEINE doppelten Titel mehr: der Screen-Kopf entfällt, die Top-Bar zeigt,
 * wo man sich gerade befindet — „Einstellungen“ bzw. „Einstellungen › Server“.
 * Karten-Köpfe (SettingsCard + AccordionCard) melden ihr Klappen hierher.
 *
 * Bewusst reiner UI-Zustand (kein Persistieren): die ZULETZT aufgeklappte
 * Sektion gewinnt; klappt genau sie zu, fällt die Anzeige auf „Einstellungen“
 * zurück. Nach Prozess-Restart startet der Pfad neutral (kein Kleben).
 */
object SettingsNav {

    /** Titel der zuletzt aufgeklappten Sektion (null = nur „Einstellungen“). */
    var openSectionTitle by mutableStateOf<String?>(null)
        private set

    /** Von jedem Karten-Kopf bei Klappen aufzurufen (auf UND zu). */
    fun onSectionToggle(title: String, expanded: Boolean) {
        if (expanded) {
            openSectionTitle = title
        } else if (openSectionTitle == title) {
            openSectionTitle = null
        }
    }

    /**
     * Pfad für die Top-Bar: „Einstellungen › <Sektion>“ bzw. nur „Einstellungen“.
     * [settingsTitle] = lokalisierter Grundtitel (R.string.settings_home_title).
     */
    fun topBarPath(settingsTitle: String, separator: String = " › "): String =
        openSectionTitle?.let { settingsTitle + separator + it } ?: settingsTitle
}
