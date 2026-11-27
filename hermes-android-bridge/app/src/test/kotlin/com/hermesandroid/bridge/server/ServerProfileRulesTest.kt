package com.hermesandroid.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-D (A6/A7, QA J4): reine Server-Profil-Regeln — Migration, Er-Start-Flag,
 * Letzt-Verbunden-Bucket, Host/Label. Der Store selbst ist context-gebunden
 * (Robolectric-frei getestet wird hier bewusst nur die Faser, die auch auf
 * JVM wahr sein MUSS: die Profil-Wechsel-Beweis-Stellen im Drill liegen
 * in den KDoc-Ackern von ServerProfiles/RelayServerList).
 */
class ServerProfileRulesTest {

    // ── hostOf: URL-Formen der Praxis (Scheme optional, Port/Pfad dran) ──

    @Test
    fun `hostOf with scheme`() {
        assertEquals("relay.example.com", ServerProfileRules.hostOf("https://relay.example.com"))
    }

    @Test
    fun `hostOf with path and trailing slash`() {
        assertEquals("dashboard.lan", ServerProfileRules.hostOf("http://dashboard.lan/"))
    }

    @Test
    fun `hostOf without scheme but with port`() {
        assertEquals(
            "mein-relay.example.com",
            ServerProfileRules.hostOf("mein-relay.example.com:8766"),
        )
    }

    @Test
    fun `hostOf strips userinfo path and port`() {
        assertEquals("foo.example", ServerProfileRules.hostOf("https://user@foo.example:9/pfad"))
    }

    // ── labelOrHost: Leer-Label fällt auf Host / End-Fallback ──

    @Test
    fun `label wins when set`() {
        assertEquals("Zuhause", ServerProfileRules.labelOrHost("Zuhause ", "https://a.example"))
    }

    @Test
    fun `blank label falls back to host`() {
        assertEquals("a.example", ServerProfileRules.labelOrHost("", "http://a.example/"))
    }

    @Test
    fun `unparseable url falls back to generic label`() {
        assertEquals("Relay-Server", ServerProfileRules.labelOrHost("", "  "))
    }

    // ── Letzt-Verbunden-Bucket ──

    private fun now() = 1_700_000_000_000L

    @Test
    fun `bucket never for unset stamp`() {
        assertEquals(
            ServerProfileRules.LastConnectedBucket.NEVER,
            ServerProfileRules.lastConnectedBucket(0L, now()),
        )
        assertEquals(
            ServerProfileRules.LastConnectedBucket.NEVER,
            ServerProfileRules.lastConnectedBucket(-5L, now()),
        )
    }

    @Test
    fun `bucket now under one minute`() {
        assertEquals(
            ServerProfileRules.LastConnectedBucket.NOW,
            ServerProfileRules.lastConnectedBucket(now() - 59_999L, now()),
        )
        assertEquals(
            ServerProfileRules.LastConnectedBucket.NOW,
            ServerProfileRules.lastConnectedBucket(now(), now()),
        )
    }

    @Test
    fun `bucket minutes under one hour`() {
        assertEquals(
            ServerProfileRules.LastConnectedBucket.MINUTES,
            ServerProfileRules.lastConnectedBucket(now() - 60_000L, now()),
        )
    }

    @Test
    fun `bucket hours under one day`() {
        assertEquals(
            ServerProfileRules.LastConnectedBucket.HOURS,
            ServerProfileRules.lastConnectedBucket(now() - 60L * 60_000L, now()),
        )
    }

    @Test
    fun `bucket history beyond one day`() {
        assertEquals(
            ServerProfileRules.LastConnectedBucket.HISTORY,
            ServerProfileRules.lastConnectedBucket(now() - 24L * 60L * 60_000L, now()),
        )
    }

    // ── Migration / Er-Start-Verband ──

    @Test
    fun `migration needs url and code`() {
        assertTrue(
            ServerProfileRules.migrationAllowed("https://relay.example", "ABC123"),
        )
        assertFalse(ServerProfileRules.migrationAllowed("https://relay.example", "   "))
        assertFalse(ServerProfileRules.migrationAllowed(null, "ABC123"))
        assertFalse(ServerProfileRules.migrationAllowed("", ""))
    }

    @Test
    fun `migration mirrors onboarding completion`() {
        assertTrue(
            ServerProfileRules.onboardingAfterMigration("https://relay.example", "ABC123"),
        )
        assertFalse(ServerProfileRules.onboardingAfterMigration(null, "CODE"))
    }

    @Test
    fun `wizard flag resets only when all profiles are gone`() {
        assertTrue(ServerProfileRules.shouldResetOnboardingFlag(0, true))
        assertFalse(ServerProfileRules.shouldResetOnboardingFlag(2, true))
        assertFalse(ServerProfileRules.shouldResetOnboardingFlag(0, false))
    }

    @Test
    fun `show wizard mirrors flag`() {
        assertTrue(ServerProfileRules.showWizard(false))
        assertFalse(ServerProfileRules.showWizard(true))
    }

    // ── W4-FIX-JAG2 (S1): Relay-URL-Transport-Verband ──

    @Test
    fun `tls urls valid in any build`() {
        assertTrue(ServerProfileRules.relayUrlValidForConnect("https://relay.example", false))
        assertTrue(ServerProfileRules.relayUrlValidForConnect("wss://relay.example:443/ws", false))
        assertTrue(ServerProfileRules.relayUrlValidForConnect("https://relay.example", true))
    }

    @Test
    fun `plaintext urls only for dashboard-lan in debug build`() {
        // Release: jeder Klartext-Transport verboten — auch dashboard.lan als
        // Relay-URL (NSC-Pin-Umgehung: Klartext-WS mit Bearer-Kopplungscode).
        assertFalse(ServerProfileRules.relayUrlValidForConnect("http://dashboard.lan", false))
        assertFalse(ServerProfileRules.relayUrlValidForConnect("http://dashboard.lan/", false))
        assertFalse(ServerProfileRules.relayUrlValidForConnect("dashboard.lan", false))
        assertFalse(ServerProfileRules.relayUrlValidForConnect("mein-relay.example.com:8766", false))
        // Dev-Kontext: NUR die NSC-Ausnahme bleibt.
        assertTrue(ServerProfileRules.relayUrlValidForConnect("http://dashboard.lan", true))
        assertTrue(ServerProfileRules.relayUrlValidForConnect("dashboard.lan", true))
        assertFalse(ServerProfileRules.relayUrlValidForConnect("http://PRIVAT-LAN-5:8766", true))
    }

    @Test
    fun `hostless urls always invalid`() {
        assertFalse(ServerProfileRules.relayUrlValidForConnect("", false))
        assertFalse(ServerProfileRules.relayUrlValidForConnect("  ", true))
        assertFalse(ServerProfileRules.relayUrlValidForConnect(":///x", true))
    }

    @Test
    fun `cleartext exception host is exactly dashboard-lan`() {
        assertTrue(ServerProfileRules.isCleartextExceptionHost("http://dashboard.lan/"))
        assertTrue(ServerProfileRules.isCleartextExceptionHost("DASHBOARD.LAN"))
        assertFalse(ServerProfileRules.isCleartextExceptionHost("sub.dashboard.lan"))
        assertFalse(ServerProfileRules.isCleartextExceptionHost("REDACTED-RELAY"))
    }
}
