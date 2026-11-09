package com.hermesandroid.bridge.client

import okhttp3.CertificatePinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * W4-01b (W3-03, App-Lane A): JVM-Verband des Pin-Sets (Plan-Verband
 * „JVM-Test CertificatePinnerConfig-Regeln").
 *
 * Verträge:
 *  - NUR der eigene Relay-Host trägt Pins (SF5: Nutzer-Relays OHNE Pins =
 *    verifizierbare Default-URL-Kette, hostname-only; Cleartext-Ausnahme
 *    `dashboard.lan` unangetastet; Subdomains bewusst ohne Pin-Verband).
 *  - Pin-Format = `sha256/<Base64 des SPKI>`; die LIVE-Chain-Pins
 *    (Beweis: openssl s_client 09.11.2026, Verify 0) sind literal getürmt —
 *    eine versehentliche Konstanten-Änderung dreht den Test rot.
 *  - Kill-Switch: Debug-Drill-Schalter nimmt NUR die PIN-Schicht (pinner()
 *    = null), nie Host-/Zertifikats-Validierung.
 *  - FAILED-Mapper: Pin-Verstoß → ehrlicher FAILLED-Text, andere Fehler →
 *    null (bestehender Text bleibt).
 */
class PinSourceTest {

    private fun assertPinFormat(pin: String) {
        assertTrue("Pin-Format sha256/<Base64>: $pin", Regex("^sha256/[A-Za-z0-9+/]{43}=$").matches(pin))
    }

    @Test
    fun `pinsFor_eigener_Relay_Host_liefert_vier_Live_Pin_Kette`() {
        val pins = PinSource.pinsFor("REDACTED-RELAY")
        assertNotNull(pins)
        assertEquals(4, pins!!.size)
        pins.forEach { assertPinFormat(it) }
        // Literal-Türme gegen versehentliche Konstanten-Änderung (Sicherheits-
        // kritisch — die Pins sind der Beweis-Verband zur live gezogenen
        // LE-Kette (Leaf YE1 Root-YE Root-X2), 09.11.2026, Verify 0):
        assertEquals("sha256/qgGoKoCQ9cUa7RvgD2AVujR7kA6P+3Zpjw+9NzWbGZ8=", pins[0])
        assertEquals("sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=", pins[1])
        assertEquals("sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", pins[2])
        assertEquals("sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", pins[3])
    }

    @Test
    fun `pinsFor_Nutzer_Relay_hosten_keine_Pins_SF5_hostname_only`() {
        assertNull("Nutzer-Relay OHNE Pins (Default-Empfehlung)", PinSource.pinsFor("mein-relay.example.com"))
        assertNull("Cleartext-Ausnahme bleibt ohne Pin-Verband", PinSource.pinsFor("dashboard.lan"))
        assertNull("Subdomains tragen bewusst keine Pins (Nur exakt-Verband)", PinSource.pinsFor("sub.REDACTED-RELAY"))
        assertNull("Leer/Blank = verweigert", PinSource.pinsFor(" "))
    }

    @Test
    fun `pinner_baut_Muster_nur_fuer_eigenes_Relay`() {
        PinSource.debugKillSwitch = false
        val pinner = PinSource.pinner()
        assertNotNull(pinner)
        val pins = pinner!!.pins
        assertEquals(4, pins.size)
        assertTrue(
            "Pinner-Muster trifft NUR den eigenen Relay-Host",
            pins.all { it.pattern == PinSource.OWN_RELAY_HOST },
        )
        assertTrue(pins.all { it.hash.size == 32 }) // sha256-Hash-Länge
    }

    @Test
    fun `kill_switch_nimmt_nur_pin_schicht_und_laesst_sich_zuruecksetzen`() {
        try {
            PinSource.debugKillSwitch = false
            assertNotNull(PinSource.pinner())
            // Direkt am internen Feld (variantenfest — setPinningDisabledForDrill
            // ignoriert den Schalter bewusst in Release-Bauten, wo BuildConfig.DEBUG
            // = false ist; die Wächter-Route ist UI/Drill-Code, nicht JVM-Test):
            PinSource.debugKillSwitch = true
            assertNull("Kill-Switch (Debug-Drill) → kein Pinner", PinSource.pinner())
            PinSource.debugKillSwitch = false
            assertNotNull("Reset → Pins wieder aktiv", PinSource.pinner())
        } finally {
            PinSource.debugKillSwitch = false
        }
    }

    @Test
    fun `isPinnedHost_parst_https_wss_und_verweigert_fremde_und_defekte_Urls`() {
        assertTrue(PinSource.isPinnedHost("https://REDACTED-RELAY"))
        assertTrue(PinSource.isPinnedHost("https://REDACTED-RELAY:8443/apk"))
        assertTrue(PinSource.isPinnedHost("wss://REDACTED-RELAY/agent_ws"))
        assertTrue(PinSource.isPinnedHost("HTTPS://RELAY.KIRCHWEGER.DE"))
        assertFalse(PinSource.isPinnedHost("https://mein-relay.example.com"))
        assertFalse(PinSource.isPinnedHost(null))
        assertFalse(PinSource.isPinnedHost("kein-scheme-kein-host"))
    }

    @Test
    fun `pinFailureText_mappt_nur_Pin_Verstoesse`() {
        assertEquals(PinSource.PIN_FAILED_DE, PinSource.pinFailureText(SSLPeerUnverifiedException("Certificate pinning failure!")))
        assertEquals(
            PinSource.PIN_FAILED_DE,
            PinSource.pinFailureText(SSLHandshakeException("Pin verification failed")),
        )
        assertNull("Andere Fehler bleiben beim generischen Text", PinSource.pinFailureText(SSLHandshakeException("Handshake hat nicht geklappt")))
        assertNull(PinSource.pinFailureText(IllegalStateException("andere Route")))
    }
}
