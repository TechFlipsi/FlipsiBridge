package com.hermesandroid.bridge.ui.chat

/**
 * FIX-WELLE-2 B7 (P2, HERZ-Befund 7): Trigger-Regel des AttachSheets als
 * reine, JVM-testbare Einheit.
 *
 * HERZ-Clean-Drill (b7-attachsheet-drill-beweis.txt): Der Demo-Teil der
 * HERZ-Meldung war Test-Methodik (IME-offen-Lage, Taps trafen die
 * Tastatur) — Tap auf den verifizierten Button (110,2115, IME geschlossen)
 * öffnete den Dialog reproduzierbar. Kein R8 (Release ohne Minify).
 *
 * B7-LIVE-RE-DRILL (03.11.2026, scratch/herz_test/fw2-b7-redrill-beweis.txt):
 * Der Re-Drill im ECHT gepaarten Relay-Chat widerlegte die Adjudizierung
 * „kein relay-abhängiger Zweig-Unterschied“: 4 atomare IME-geschlossene
 * Taps, Button enabled+clickable, kein Crash, keine Exception — das Sheet
 * öffnete NUR in der Demo-Conversation, im Live-Chat blieb es stumm.
 * Root-Cause: KEIN State-Reset-Race, sondern ein ID-VORZEICHEN-Bug.
 * Live-Agenten bekommen id = agentId.hashCode() (ChatScreen), und Java-
 * String-Hashes sind vorzeichenbehaftet — Live-Agent-Namen
 * können NEGATIV hashen (B7-Live-Fall: -1167182811). Die Bedingung `selectedAgentId >= 0` war damit
 * in JEDEM Live-Chat false (Demo-IDs 1..3 bleiben positiv → nur Demo
 * öffnete); derselbe Sign-Bug hielt den System-BackHandler in jedem
 * Live-Chat deaktiviert (Back sprang zur Route statt zur Agenten-Liste).
 *
 * Der Vertrag ist jetzt vorzeichenfest: „Conversation offen“ leitet sich
 * daraus ab, ob die gewählte ID in der Agenten-Liste LIEGT
 * (isConversationOpen), nicht aus dem ID-Wert selbst.
 */
object AttachSheetRules {

    /**
     * Vorzeichenfeste Offen-Prüfung: true, wenn [selectedAgentId] in
     * [agentIds] liegt. Live-Agent-IDs sind hashCode-Werte und KÖNNEN
     * negativ sein — `id >= 0` als „offen“-Proxy war der B7-Live-Bug.
     */
    fun isConversationOpen(selectedAgentId: Int, agentIds: Collection<Int>): Boolean =
        selectedAgentId in agentIds

    /**
     * true = das AttachSheet (AlertDialog Foto/Datei/Sprachnachricht) soll
     * komponiert werden — nur bei gesetztem Open-Flag UND offener
     * Conversation (die Picker-Launcher brauchen den Agenten-Kontext).
     * [conversationOpen] ist vor dem ID-Vorzeichen indifferent zu fassen
     * (via [isConversationOpen] ableiten, nicht `id >= 0`).
     */
    fun shouldRenderSheet(attachSheetOpen: Boolean, conversationOpen: Boolean): Boolean =
        attachSheetOpen && conversationOpen
}