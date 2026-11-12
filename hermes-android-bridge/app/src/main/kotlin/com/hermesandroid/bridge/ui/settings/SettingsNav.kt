package com.hermesandroid.bridge.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/**
 * Welle 15 / P7 (Sir): Pfad-Anzeige der Top-Bar im Einstellungen-Screen.
 *
 * KEINE doppelten Titel mehr: der Screen-Kopf entfällt, die Top-Bar zeigt,
 * wo man sich gerade befindet — „Einstellungen“ bzw. „Einstellungen › Server“.
 * Karten-Köpfe (SettingsCard + AccordionCard) melden ihr Klappen hierher.
 *
 * Bewusst reiner UI-Zustand (kein Persistieren): die ZULETZT aufgeklappte
 * Sektion gewinnt (dokumentierter Stand — KEIN Scroll-Tracking); klappt
 * genau sie zu, fällt die Anzeige auf „Einstellungen“ zurück. Nach
 * Prozess-Restart startet der Pfad neutral (kein Kleben).
 *
 * W15b (Fix D, Sir-Befund „Einstellungen › Language“ in deutscher UI): die
 * Sektion meldet jetzt eine @StringRes-ID statt eines lokalisierten Strings —
 * der alte Vertrag speicherte den Titel ALS TEXT zur Klapp-Zeit. Wer die App
 * auf English umstellte, hatte „Language“ im Pfad kleben, auch nach dem
 * Rückwechsel auf Deutsch. Die Auflösung passiert JETZT erst bei der
 * Anzeige (stringResource in [topBarPath]) — der Pfad wechselt live mit.
 */
object SettingsNav {

    /** @StringRes-ID der zuletzt aufgeklappten Sektion (null = nur „Einstellungen“). */
    @StringRes
    var openSectionRes: Int? by mutableStateOf(null)
        private set

    /** Von jedem Karten-Kopf bei Klappen aufzurufen (auf UND zu). */
    fun onSectionToggle(@StringRes titleRes: Int, expanded: Boolean) {
        if (expanded) {
            openSectionRes = titleRes
        } else if (openSectionRes == titleRes) {
            openSectionRes = null
        }
    }

    /**
     * Pfad für die Top-Bar: „Einstellungen › <Sektion>“ bzw. nur „Einstellungen“.
     * [settingsTitle] = lokalisierter Grundtitel (R.string.settings_home_title).
     * W15b: Sektions-Label wird HIER per stringResource live aufgelöst — Sprach-
     * Wechsel wirkt sofort, kein eingefrorener Titel mehr.
     */
    @Composable
    fun topBarPath(settingsTitle: String, separator: String = " › "): String =
        openSectionRes?.let { settingsTitle + separator + stringResource(it) } ?: settingsTitle
}
