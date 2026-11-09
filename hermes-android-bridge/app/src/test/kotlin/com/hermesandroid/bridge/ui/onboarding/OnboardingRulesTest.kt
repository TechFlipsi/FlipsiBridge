package com.hermesandroid.bridge.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-D (A6, QA J4): Wizard-Zustands-Regeln auf JVM — Fehler-Taxonomie der
 * Fehlerhilfe je Schritt (401 = „Code falsch“, 429 = Relay-Sperre, 0/-1 =
 * „Relay nicht erreichbar“), Feld-Plausis und der Automatik-Sprung von der
 * Code-Eingabe zur Fingerprint-Karte. Die Android-Verbands-Schale (Composable)
 * wird im Emulator ge-drillt (KDoc-Acker in OnboardingScreens.kt).
 */
class OnboardingRulesTest {

    // ── Fehler-Taxonomie (OnboardingHelp-Verband je Schritt 2) ──

    @Test
    fun `401 and 403 mean code rejected`() {
        assertEquals(
            OnboardingHelpKind.CODE_REJECTED,
            OnboardingRules.helpFor(OnboardingSignal.FAILED, 401),
        )
        assertEquals(
            OnboardingHelpKind.CODE_REJECTED,
            OnboardingRules.helpFor(OnboardingSignal.FAILED, 403),
        )
    }

    @Test
    fun `429 means relay blocked`() {
        assertEquals(
            OnboardingHelpKind.RELAY_BLOCKED,
            OnboardingRules.helpFor(OnboardingSignal.FAILED, 429),
        )
    }

    @Test
    fun `zero code means unreachable timeout or network`() {
        assertEquals(
            OnboardingHelpKind.UNREACHABLE,
            OnboardingRules.helpFor(OnboardingSignal.FAILED, 0),
        )
        assertEquals(
            OnboardingHelpKind.UNREACHABLE,
            OnboardingRules.helpFor(OnboardingSignal.FAILED, -1),
        )
    }

    @Test
    fun `auth stopped always means wrong code`() {
        assertEquals(
            OnboardingHelpKind.CODE_REJECTED,
            OnboardingRules.helpFor(OnboardingSignal.AUTH_STOPPED, -1),
        )
        assertEquals(
            OnboardingHelpKind.CODE_REJECTED,
            OnboardingRules.helpFor(OnboardingSignal.AUTH_STOPPED, 401),
        )
    }

    @Test
    fun `no help while waiting connected or idle`() {
        assertNull(
            OnboardingRules.helpFor(OnboardingSignal.WAITING, 401),
        )
        assertNull(
            OnboardingRules.helpFor(OnboardingSignal.CONNECTED, 429),
        )
        assertNull(
            OnboardingRules.helpFor(OnboardingSignal.NONE, 0),
        )
    }

    // ── Feld-Plausis (Schritt 1 URL / Schritt 2 Code) ──

    @Test
    fun `url problem for blank or hostless input`() {
        assertTrue(OnboardingRules.relayUrlProblem("", debugBuild = false))
        assertTrue(OnboardingRules.relayUrlProblem("   ", debugBuild = true))
        assertTrue(OnboardingRules.relayUrlProblem("/", debugBuild = true))
        assertTrue(OnboardingRules.relayUrlProblem(":///x", debugBuild = true))
    }

    @Test
    fun `https url acceptable in any build`() {
        assertFalse(OnboardingRules.relayUrlProblem("https://relay.example", debugBuild = false))
        assertFalse(OnboardingRules.relayUrlProblem("https://relay.example", debugBuild = true))
    }

    @Test
    fun `scheme-less url is plaintext transport and release rejects it (S1)`() {
        // „ relay.example:8766/ " bestand vorher jeden Verband (hostOf-only) —
        // buildWsUrl behandelt scheme-los weiterhin als Klartext (ws://…:8766).
        // W4-FIX-JAG2 (S1): Release lehnt ab, dashboard.lan bleibt im DEV-
        // Kontext die einzige Klartext-Relay-URL.
        assertTrue(OnboardingRules.relayUrlProblem(" relay.example:8766/ ", debugBuild = false))
        assertFalse(OnboardingRules.relayUrlProblem("http://dashboard.lan", debugBuild = true))
        assertTrue(OnboardingRules.relayUrlProblem("http://dashboard.lan", debugBuild = false))
    }

    @Test
    fun `code must not be blank`() {
        assertTrue(OnboardingRules.codeProblem(""))
        assertTrue(OnboardingRules.codeProblem("   "))
        assertFalse(OnboardingRules.codeProblem(" ABC23456DE "))
    }

    // ── Automatik-Sprung: nur von der Code-Eingabe (Schritt 2) ──

    @Test
    fun `advance on connected only from code step`() {
        assertTrue(
            OnboardingRules.advancesOnConnected(OnboardingStep.BRIDGE_CODE.ordinal),
        )
        assertFalse(
            OnboardingRules.advancesOnConnected(OnboardingStep.RELAY_URL.ordinal),
        )
        assertFalse(
            OnboardingRules.advancesOnConnected(OnboardingStep.FINGERPRINT.ordinal),
        )
    }

    @Test
    fun `step order is url code fingerprint`() {
        assertEquals(0, OnboardingStep.RELAY_URL.ordinal)
        assertEquals(1, OnboardingStep.BRIDGE_CODE.ordinal)
        assertEquals(2, OnboardingStep.FINGERPRINT.ordinal)
    }
}
