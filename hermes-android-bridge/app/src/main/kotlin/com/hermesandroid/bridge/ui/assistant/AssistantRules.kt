package com.hermesandroid.bridge.ui.assistant

/**
 * ASSISTENT-PAKET G1–G10 (docs/roadmap-anruf.md §7, Sir-Zusatz 16.11.26) —
 * die App-seitigen Regeln als reine JVM-Logik (KEIN android.*-Import, damit
 * AssistantRulesTest ohne Robolectric läuft).
 *
 * Abgrenzung (ehrlich, nichts erfunden):
 *  - G1 (Standard-Assistent): Rolle + AssistEntryActivity existieren bereits
 *    (W13 P10/P12). Diese Regeln pinnen die EINSTIEGS-ROUTING-Entscheidung:
 *    Rollen-Invocation → Voice-Turn im Chat (AssistLaunch.pendingVoice) oder
 *    — Anruf-Lane — direkt ein Gespräch (Assistent-Modus).
 *  - G2 (Floating Bubble): SYSTEM_ALERT_WINDOW-Bubble, Tap = Voice-Turn.
 *    Eligibility über dieselbe Overlay-Freigabe wie der HUD (perm overlay).
 *    Der Bubble-Dienst selbst (overlay/) ist eigene Bauwelle — hier nur die
 *    Verbands-Regeln.
 *  - G5 („Was ist DAS hier?“ — Screen-Kontext): explizite Zustimmung JE
 *    SESSION (Gemini-Pattern), Nutzerklick, kein Silent — oben auf der
 *    bestehenden screen-Capability (CapabilityGate-Freischalt-Muster).
 *  - G3 (Sperrbildschirm) = Test-Matrix, G6/G7/G8/G10 = Anruf-/Agent-Lane
 *    (call_user, Agent-Cron, Voice-Frontend), G9 = Wake-Wort nicht verlässlich
 *    (Experiment) — KEINE App-Regel-Objekte für diese (nichts Erfundenes).
 */

/** G1+G2: Welcher Einstieg ergibt eine Assistenten-Invocation? */
object AssistantEntryRules {

    /** Einstiegs-Ursache einer Assistenten-Invocation. */
    enum class Source {
        /** System-Rollen-Trigger (Power-Taste halten / Home-Sweep / ASSIST-Intent). */
        ROLE_INVOCATION,

        /** Eigene Overlay-Bubble über allen Apps (G2) — unser Fenster, kein Trigger der Rolle. */
        OVERLAY_BUBBLE,
    }

    /** Aufgelöster Einstieg. NONE = ehrlich nichts tun (kein stiller Fake-Start). */
    enum class Entry { NONE, CHAT_VOICE_TURN, CALL }

    /**
     * G1: Routing der Rollen-Invocation (docs/roadmap-anruf.md §7 G1, Phase C+):
     * Rolle NICHT gehalten → NONE. Anruf-Feature „Assistent-Modus“ an → die
     * Zweit-Tür: Anruf direkt aus der Role-Invocation. Sonst der Bestand-Pfad:
     * Voice-Turn im Chat (AssistEntryActivity trampolint mit pendingVoice).
     *
     * G2: Die Bubble ist UNSER eigenes Fenster — sie braucht die Rolle nicht;
     * ihr Tap ist laut Roadmap IMMER ein Voice-Turn (auch bei aktivem
     * Assistent-Modus, kein Anruf aus der Blase).
     */
    fun resolve(source: Source, roleHeld: Boolean, callAssistantMode: Boolean): Entry = when (source) {
        Source.OVERLAY_BUBBLE -> Entry.CHAT_VOICE_TURN
        Source.ROLE_INVOCATION -> when {
            !roleHeld -> Entry.NONE
            callAssistantMode -> Entry.CALL
            else -> Entry.CHAT_VOICE_TURN
        }
    }
}

/** G2: Eligibility der Floating Bubble über allen Apps. */
object AssistantBubbleRules {

    enum class Eligibility {
        /** Bubble darf angezeigt werden (Freigabe da, Gerät unterstützt Overlays). */
        READY,

        /** SYSTEM_ALERT_WINDOW-Freigabe fehlt → Setup-Weg anbieten, nie still tot bleiben. */
        NEEDS_OVERLAY_PERMISSION,

        /** Gerät/OEM erlaubt Overlays nicht → ehrliche „nicht möglich“-Zeile. */
        NOT_SUPPORTED,
    }

    /**
     * [overlayPermissionGranted] = Settings.canDrawOverlays(ctx) (gleiche
     * Freigabe wie der HUD, perm overlay); [supported] = Gerät bietet
     * Overlay-Fenster überhaupt an (minSdk 26 immer true — Parameter bleibt
     * ehrlich für OEM-Ausnahmen).
     */
    fun eligibility(overlayPermissionGranted: Boolean, supported: Boolean = true): Eligibility = when {
        !supported -> Eligibility.NOT_SUPPORTED
        overlayPermissionGranted -> Eligibility.READY
        else -> Eligibility.NEEDS_OVERLAY_PERMISSION
    }
}

/**
 * G5: Session-gebundene Zustimmung für den Screen-Kontext — „Was ist DAS
 * hier?“ darf den Agenten den Bildschirm lesen lassen, aber NUR je Session
 * ausdrücklich (Gemini-Pattern; roadmaptermin: „Nutzerklick, kein Silent“ im
 * bestehenden Capability-Freischalt-Muster). Der Schlüssel verbandelt Agent
 * und Session-Epoch: neue Session oder anderer Agent = Zustimmung automatisch
 * tot, es wird neu gefragt. Reines Key-Vergleichen — kein Prefs-Zugriff hier,
 * das Halten des Stands ist Sache der Verdrahtung (Koordinator).
 */
object ScreenContextConsentRules {

    /**
     * Zustimmungs-Schlüssel für (agentId, sessionEpoch). null = unbrauchbar
     * (blank Agent / negative Epoch — Demo-Eintrag ohne Backend hat NOCH
     * keinen Screen-Kontext, eine leere Zustimmung wäre gelogen).
     */
    fun consentKey(agentId: String, sessionEpoch: Long): String? {
        if (agentId.isBlank() || sessionEpoch < 0) return null
        return "$agentId#$sessionEpoch"
    }

    /**
     * true = die UI MUSS neu fragen (Zustimmung fehlt, gilt für einen anderen
     * Agenten oder eine ältere Session). Unbrauchbare Parameter fragen IMMER —
     * niemals still durchwinken.
     */
    fun needsAsk(consentState: String?, agentId: String, sessionEpoch: Long): Boolean =
        consentState == null || consentState != consentKey(agentId, sessionEpoch)

    /**
     * Ausdrücklicher Nutzer-Klick: erzeugt den gültigen Schlüssel — null bei
     * unbrauchbaren Parametern (kein Grant, die UI fragt weiter). Der Aufrufer
     * speichert den Rückgabewert als consentState; nur EIN Aufrufweg setzt
     * Zustimmung (kein Silent-Grant).
     */
    fun grant(agentId: String, sessionEpoch: Long): String? = consentKey(agentId, sessionEpoch)

    /**
     * Direkter Key-Vergleich für die UI-Verdrahtung: true = die gespeicherte
     * Zustimmung trägt GENAU diese Session (Schlüssel gilt als unverändert).
     * null-Schlüssel (keine Session) → nie wahr — kein Schalter fürs Leere.
     */
    fun isGrantedForSession(consentState: String?, currentSessionKey: String?): Boolean =
        currentSessionKey != null && consentState != null && consentState == currentSessionKey

    /** Widerruf (jederzeit möglich) — Zustand fällt auf null, folgender Aufruf fragt wieder. */
    fun revoke(): String? = null
}
