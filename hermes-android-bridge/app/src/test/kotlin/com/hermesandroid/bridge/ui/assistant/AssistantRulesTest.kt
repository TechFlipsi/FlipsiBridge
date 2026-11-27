package com.hermesandroid.bridge.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASSISTENT-PAKET G1/G2/G5 (docs/Anruf-Konzept) — JVM-Verträge der
 * Einstiegs-Routing-, Bubble-Eligibility- und Session-Zustimmungs-Regeln.
 * Reine JVM-Logik (kein Android), gepinnt, damit die Anruf-Assistent-Lane
 * (G1-Zweit-Tür, Bubble-Dienst) gegen die gleichen Regeln baut.
 */
class AssistantRulesTest {

    // ── G1: Rollen-Invocation-Routing ────────────────────────────────────────

    @Test
    fun roleWithoutHeldRoleResolvesToNone() {
        assertEquals(
            AssistantEntryRules.Entry.NONE,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.ROLE_INVOCATION,
                roleHeld = false,
                callAssistantMode = false,
            ),
        )
        assertEquals(
            AssistantEntryRules.Entry.NONE,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.ROLE_INVOCATION,
                roleHeld = false,
                callAssistantMode = true,
            ),
        )
    }

    @Test
    fun heldRoleWithoutAssistantModeResolvesToVoiceTurnInChat() {
        // Bestand-Pfad (W13 P10): AssistEntryActivity trampolint mit
        // pendingVoice → Sprach-Turn im Chat, kein Anruf.
        assertEquals(
            AssistantEntryRules.Entry.CHAT_VOICE_TURN,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.ROLE_INVOCATION,
                roleHeld = true,
                callAssistantMode = false,
            ),
        )
    }

    @Test
    fun heldRoleWithAssistantModeResolvesToCall() {
        // G1-Zweit-Tür (Phase C+): Assistent-Modus des Anruf-Features startet
        // das Gespräch direkt aus der Role-Invocation.
        assertEquals(
            AssistantEntryRules.Entry.CALL,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.ROLE_INVOCATION,
                roleHeld = true,
                callAssistantMode = true,
            ),
        )
    }

    // ── G2: Bubble-Tap ist IMMER ein Voice-Turn (eigenes Fenster, keine Rolle) ─

    @Test
    fun bubbleTapNeverRoutesToCall() {
        assertEquals(
            AssistantEntryRules.Entry.CHAT_VOICE_TURN,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.OVERLAY_BUBBLE,
                roleHeld = false,
                callAssistantMode = false,
            ),
        )
        assertEquals(
            AssistantEntryRules.Entry.CHAT_VOICE_TURN,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.OVERLAY_BUBBLE,
                roleHeld = true,
                callAssistantMode = true,
            ),
        )
    }

    @Test
    fun bubbleEligibilityFollowsOverlayPermission() {
        assertEquals(
            AssistantBubbleRules.Eligibility.READY,
            AssistantBubbleRules.eligibility(overlayPermissionGranted = true),
        )
        assertEquals(
            AssistantBubbleRules.Eligibility.NEEDS_OVERLAY_PERMISSION,
            AssistantBubbleRules.eligibility(overlayPermissionGranted = false),
        )
        assertEquals(
            AssistantBubbleRules.Eligibility.NOT_SUPPORTED,
            AssistantBubbleRules.eligibility(overlayPermissionGranted = true, supported = false),
        )
    }

    // ── G5: Session-Zustimmung („explizit je Session, Nutzerklick“) ──────────

    @Test
    fun consentKeyRejectsBlankAgentAndNegativeEpoch() {
        assertNull(ScreenContextConsentRules.consentKey("", 0L))
        assertNull(ScreenContextConsentRules.consentKey("   ", 5L))
        assertNull(ScreenContextConsentRules.consentKey("agent-1", -1L))
        assertEquals("agent-1#3", ScreenContextConsentRules.consentKey("agent-1", 3L))
    }

    @Test
    fun consentIsAskedWithoutStateAndForForeignOrStaleSessions() {
        // Nie gesetzt → fragen.
        assertTrue(ScreenContextConsentRules.needsAsk(null, "agent-1", 0L))
        // Andere Session (neue Epoch) → alte Zustimmung ist automatisch tot.
        assertTrue(ScreenContextConsentRules.needsAsk("agent-1#0", "agent-1", 1L))
        // Anderer Agent → eigene Session-Zustimmung nötig.
        assertTrue(ScreenContextConsentRules.needsAsk("agent-2#7", "agent-1", 7L))
        // Unbrauchbare Parameter fragen IMMER (nie still durchwinken).
        assertTrue(ScreenContextConsentRules.needsAsk(null, "", 0L))
        assertTrue(ScreenContextConsentRules.needsAsk("agent-1#2", "agent-1", -5L))
    }

    @Test
    fun grantOfCurrentSessionStopsTheAsk() {
        val state = ScreenContextConsentRules.grant("agent-1", 7L)
        assertEquals("agent-1#7", state)
        assertFalse(ScreenContextConsentRules.needsAsk(state, "agent-1", 7L))
        // Direkter Key-Vergleich (UI-Verdrahtung): nur der IDENTISCHEN Session
        // gilt die Zustimmung — null oder fremder Key nie.
        assertTrue(
            ScreenContextConsentRules.isGrantedForSession(
                ScreenContextConsentRules.grant("agent-1", 7L), "agent-1#7",
            ),
        )
        assertFalse(
            ScreenContextConsentRules.isGrantedForSession(
                ScreenContextConsentRules.grant("agent-1", 6L), "agent-1#7",
            ),
        )
        assertFalse(ScreenContextConsentRules.isGrantedForSession("agent-1#7", null))
        // Demo/ohne Backend: Grant liefert null → die UI fragt weiter, nichts klebt.
        assertNull(ScreenContextConsentRules.grant("", 7L))
        assertNull(ScreenContextConsentRules.grant("agent-1", -3L))
    }

    @Test
    fun revokeRequiresNewConsent() {
        val state = ScreenContextConsentRules.revoke()
        assertNull(state)
        assertTrue(ScreenContextConsentRules.needsAsk(state, "agent-1", 7L))
    }
}
