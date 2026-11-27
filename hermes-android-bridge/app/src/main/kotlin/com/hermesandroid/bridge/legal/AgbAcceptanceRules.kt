package com.hermesandroid.bridge.legal

/**
 * PH8-T6 (W22, Lane E) — reine Regeln der AGB-Erststart-Akzeptanz (JVM-testbar,
 * keine Android-Imports — Muster ServerProfileRules).
 *
 * Verband:
 *  - Der Akzeptanz-Schritt lebt als EINGEBETTETER Wizard-Schritt (Schritt 1
 *    RECHTLICHES) — kein VOR-Schritt vor dem Wizard (der Wizard existiert seit
 *    Welle 13 mit dem Schritt; ein zweiter Vorgänger wäre ein Doppel). Die
 *    Reihenfolge (0 Sprache → 1 Rechtliches → Kopplung) bleibt unverändert:
 *    das Accept-Gate steht VOR der ersten Datenübermittlung.
 *  - Flag: OnboardingLegalPrefs («legal_accepted_v1», Haus
 *    «hermes_bridge_prefs»). Einmal akzeptiert = dauerhaft (nie wiederkehrend);
 *    das Flag wird NIE zurückgesetzt — auch nicht, wenn Profile gelöscht
 *    werden (die Akzeptanz-Aussage des Nutzers bleibt).
 *  - Upgrade/Migration fail-safe: bestehende Installationen werden NICHT
 *    erneut gefragt. Zwei Schichten: (1) die Upgrade-Migration in
 *    ServerProfiles setzt onboardingDone=true (bestehende Kopplung → Wizard
 *    erscheint gar nicht, QA J2); (2) selbst wenn ein Pfad den Wizard doch
 *    wieder aufbrächte, gilt eine Installation mit Verlauf/Kopplung als
 *    akzeptiert ([effectiveAccepted]) — der Flag-Default «false» darf nie
 *    dazu führen, dass ein laufender Brücken-Betrieb durch ein
 *    Rechtstext-Gate blockiert wird.
 *  - Skip-Verband (Welle 12b/15) bleibt: «Setup überspringen» verzögert die
 *    Einrichtung bewusst OHNE Kopplung; es findet dann auch keine
 *    Datenübermittlung statt (Checkliste C), das Akzeptanz-Gate greift beim
 *    späteren Fortsetzen erneut, bevor gekoppelt wird.
 *  - Ablehnen = App beendet sich sauber ([declineEndsApp]: finishAffinity) —
 *    mit klarem Hinweis (Bestätigungs-Dialog vor dem Beenden).
 */
object AgbAcceptanceRules {

    /**
     * Bestehende Kopplung aus dem alten Verband = Relay-URL (Server-Profil/
     * Prefs) UND Kopplungscode im Vault — exakt die [com.hermesandroid.bridge.
     * server.ServerProfileRules.migrationAllowed]-Semantik (beide Teile nötig,
     * keine Hälfte zählt).
     */
    fun hasLegacyPairing(serverUrl: String?, storedCode: String): Boolean =
        !serverUrl.isNullOrBlank() && storedCode.isNotBlank()

    /**
     * effektiv akzeptiert: das persistierte ODER der Upgrade-Fail-Safe.
     *
     * hasProfiles = eine Profil-Liste mit mindestens einem Eintrag (Kopplung
     * existiert), hasLegacyPairing = [hasLegacyPairing]. Der Fail-Safe gilt
     * bewusst auch bei gestrichenem Wizard-Bruch: eine Installation mit
     * Verlauf/Kopplung wird nie durch den Flag-Default «false» erneut gefragt.
     */
    fun effectiveAccepted(
        storedFlag: Boolean,
        hasProfiles: Boolean,
        hasLegacyPairing: Boolean,
    ): Boolean = storedFlag || hasProfiles || hasLegacyPairing

    /**
     * true = Akzeptanz-Gate stellen (echter Erststart: nie akzeptiert UND
     * kein Verlauf/Kopplung). false = Schritt im Lesemodus (Karten sichtbar,
     * „Weiter“ ohne erneute Zustimmung — „nie wiederkehrend“).
     */
    fun mustAsk(
        storedFlag: Boolean,
        hasProfiles: Boolean,
        hasLegacyPairing: Boolean,
    ): Boolean = !effectiveAccepted(storedFlag, hasProfiles, hasLegacyPairing)

    /**
     * Ablehnen-Vertrag: die App endet sauber (finishAffinity je Activity),
     * es gibt keinen „weiter ohne Akzeptanz“-Weg an der Ablehnen-Fläche.
     * Invariante für den UI-Verband (JVM-gepinnt).
     */
    val declineEndsApp: Boolean = true

    /**
     * Akzeptanz-Vertrag: die Zustimmung wird beim „Akzeptieren“ DIREKT
     * persistiert (OnboardingLegalPrefs.accept) bevor der Wizard weitergeht —
     * ein Absturz/Rotieren nach dem Tipp verliert die Einlösung nicht
     * (P7-Verband der Welle 13 bleibt erhalten).
     */
    val acceptPersistsImmediately: Boolean = true
}
