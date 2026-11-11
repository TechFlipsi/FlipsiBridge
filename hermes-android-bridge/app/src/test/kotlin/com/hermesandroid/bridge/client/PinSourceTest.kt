package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * WELLE-13 PRIVATSPHÄREN-FIX (Privatsphären-Gate G): Der Build enthält KEIN
 * Default-Pin-Set und KEINEN Default-Relay-Host mehr (keine privaten Domains
 * und keine SPKI-Hashes im Release). Pinning bleibt als RUNTIME-Funktion =
 * NUR wenn ein NUTZER seinen eigenen Relay mit Pins versieht (Konfiguration).
 *
 * Verträge (neutral):
 *  - KEIN Host ist vorgeseedet: pinsFor(x) = null für alle Hosts, solange
 *    kein Nutzer-Verband existiert (der frühere „eigener Relay“-Stand inkl.
 *    Literale wurde bewusst entfernt — ein Test, der die alten Pins erwartet,
 *    dreht den Test bewusst rot).
 *  - pinner() = null ohne konfigurierte Pins (OkHttp ohne Pinner =
 *    System-Trust + Hostname-Validierung, KEIN Bricking).
 *  - Kill-Switch (Debug): nimmt NUR die PIN-Schicht, nie Host-/Zert-Validierung.
 *  - FAILED-Mapper: Pin-Verstoß → ehrlicher FAILED-Text, andere Fehler → null.
 */
class PinSourceTest {

    @Test
    fun `kein_default_host_und_kein_default_pin_set_im_Build`() {
        assertNull(
            "Kein Default-Host im Build (Privatsphären-Gate G)",
            PinSource.OWN_RELAY_HOST.ifBlank { null },
        )
        assertTrue("Kein Default-Pin-Set im Build", PinSource.OWN_RELAY_PINS.isEmpty())
    }

    @Test
    fun `pinsFor_ist_neutral_ohne_nutzer_verband`() {
        assertNull("Kein Default-Pin für irgendeinen Host", PinSource.pinsFor("mein-relay.example.com"))
        assertNull("Cleartext-Ausnahme bleibt ohne Pin-Verband", PinSource.pinsFor("dashboard.lan"))
        assertNull("Subdomains tragen bewusst keine Pins", PinSource.pinsFor("sub.mein-relay.example.com"))
        assertNull("Leer/Blank = verweigert", PinSource.pinsFor(" "))
    }

    @Test
    fun `pinner_ist_null_ohne_konfigurierte_pins`() {
        PinSource.debugKillSwitch = false
        assertNull("Ohne Nutzer-Pins kein Pinner (System-Trust)", PinSource.pinner())
    }

    @Test
    fun `pinner_baut_nur_mit_nutzer_pins_und_genau_fuer_diesen_host`() {
        try {
            PinSource.OWN_RELAY_HOST = "mein-relay.example.com"
            PinSource.OWN_RELAY_PINS = listOf(
                "sha256/qgGoKoCQ9cUa7RvgD2AVujR7kA6P+3Zpjw+9NzWbGZ8=",
                "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=",
            )
            PinSource.debugKillSwitch = false
            val pinner = PinSource.pinner()
            assertNotNull(pinner)
            val pins = pinner!!.pins
            assertEquals(2, pins.size)
            assertTrue("Pinner-Muster trifft NUR den konfigurierten Host", pins.all { it.pattern == "mein-relay.example.com" })
            assertTrue(pins.all { it.hash.size == 32 })
            assertTrue(PinSource.isPinnedHost("https://mein-relay.example.com"))
        } finally {
            PinSource.OWN_RELAY_HOST = ""
            PinSource.OWN_RELAY_PINS = emptyList()
        }
    }

    @Test
    fun `kill_switch_nimmt_nur_pin_schicht`() {
        try {
            PinSource.OWN_RELAY_HOST = "mein-relay.example.com"
            PinSource.OWN_RELAY_PINS = listOf(
                "sha256/qgGoKoCQ9cUa7RvgD2AVujR7kA6P+3Zpjw+9NzWbGZ8=",
            )
            PinSource.debugKillSwitch = false
            assertNotNull(PinSource.pinner())
            PinSource.debugKillSwitch = true
            assertNull("Kill-Switch (Debug-Drill) → kein Pinner", PinSource.pinner())
            PinSource.debugKillSwitch = false
            assertNotNull("Reset → Pins wieder aktiv", PinSource.pinner())
        } finally {
            PinSource.debugKillSwitch = false
            PinSource.OWN_RELAY_HOST = ""
            PinSource.OWN_RELAY_PINS = emptyList()
        }
    }

    @Test
    fun `isPinnedHost_brauch_nutzer_host_und_verweigert_defekte_Urls`() {
        try {
            PinSource.OWN_RELAY_HOST = "mein-relay.example.com"
            assertTrue(PinSource.isPinnedHost("https://mein-relay.example.com"))
            assertTrue(PinSource.isPinnedHost("https://mein-relay.example.com:8443/apk"))
            assertTrue(PinSource.isPinnedHost("wss://mein-relay.example.com/agent_ws"))
            assertTrue(PinSource.isPinnedHost("HTTPS://MEIN-RELAY.EXAMPLE.COM"))
            assertFalse(PinSource.isPinnedHost("https://fremd.example.com"))
            assertFalse(PinSource.isPinnedHost(null))
            assertFalse(PinSource.isPinnedHost("kein-scheme-kein-host"))
        } finally {
            PinSource.OWN_RELAY_HOST = ""
        }
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
