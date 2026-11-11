package com.hermesandroid.bridge.client

import android.util.Log
import com.hermesandroid.bridge.BuildConfig
import okhttp3.CertificatePinner
import java.net.URI
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * W4-01b (W3-03, App-Lane A): SPKI-PIN-QUELLE für OkHttp — TOFU-freies
 * CertificatePinner-Verband für das EIGENE Relay, fail-closed bei Fremd-Kette.
 *
 * Sirs Entscheidung (SF5, verbatim übernommen): NUR das eigene Relay
 * (`<relay-host>`) wird vor-seedeet. JEDEN von Sir hinzugefügten
 * Nutzer-Relay-Host lässt dieses Verband OHNE Pin-Set laufen (hostname-only,
 * System-Trust) — kein Nutzer-Relay kann durch falsche/abgelaufene Pins
 * unverbunden bleiben. Die NSC-Schiene (res/xml/network_security_config.xml,
 * Pin-Set am Domänen-Eintrag) trägt denselben Pin-Stand plattformseitig und
 * erfasst auch Clients, die [pinner] nie sehen; beide Schienen decken genau
 * denselben Host ab und NIEMALS die Cleartext-Ausnahme `dashboard.lan`.
 *
 * Pin-Kette (Beweis: `openssl s_client -connect <relay-host>:443
 * -servername <relay-host> -showcerts`, Verify return code: 0 (ok),
 * gezogen 09.11.2026 am kibot; SPKI-SHA256 je Kettenglied):
 *  - Leaf: SAN-DNS `<relay-host>`, ECDSA-P384, LE-Short-Lived-Profil
 *    (7-Tage-Zertifikat, 2026-11-08 … 2026-11-15) — der Leaf-Schlüssel KANN
 *    bei jedem Renewal wechseln; sein Pin ist bewusst NUR der Positiv-Pin.
 *  - Backup-Pins tragen die Rotation: Intermediate `CN=YE1,O=Let's Encrypt`
 *    (gültig bis 2028-09-02), `CN=Root YE,O=ISRG` (bis 2032-09-02) und
 *    `CN=ISRG Root X2,O=ISRG` (Cross-Sign von Root X1, bis 2032-09-02).
 *    Ein Pin-Erfolg geltet, wenn EIN Kettenglied einen Pin trifft — Leaf-
 *    Renewal mit gleichem Intermediate/Root bleibt verbunden (R5-Verband).
 *  - Rotations-Pflicht (dokumentiert, Sir-Verband): Spätestens bei einem
 *    Intermediate-/Root-Wechsel (YE1/Root YE) MÜSSEN beide Schienen
 *    (diese Datei + NSC-Pin-Set) im selben Deploy neu geseedet werden;
 *    Erinnerungsebene = NSC-`expiration` + CHANGELOG-Notiz der Build-Welle.
 *
 * Kill-Switch für Drills (Plan-Verband W4-APP-02): nur in Debug-Bauten
 * schaltbar — er nimmt NUR die PIN-Schicht der OKHTTP-Schiene raus (Hostname-/
 * Zertifikats-Validierung bleibt voll aktiv, fail-safe Richtung), NIE das
 * Trust-Set. Grenze (bewusst dokumentiert): die NSC-Schiene ist im APK fest
 * verdrahtet und hat keinen Laufzeit-Schalter — ein Drill, der am eigenen
 * Relay PIN-frei laufen muss, baut die Drill-Variante ohne NSC-Pin-Set
 * (Build-Welle entscheidet); der Kill-Switch deckt den OkHttp-Arm allein.
 *
 * FAILED-Text (Mapper-Ertrag im RelayClient.onFailure): [PIN_FAILED_DE] /
 * [PIN_FAILED_EN] — Strings-Verband W4-APP-04/05 übernimmt den Text als
 * Ressourcen-Key `pin_failed_status` (default = DE, values-en = EN). Bis zur
 * Ressourcen-Adoption gilt der Bestand-Muster-Standard der App-Statuszeile
 * (deutsches Literal, wie alle RelayClient-Statusmeldungen).
 */
object PinSource {

    private const val TAG = "FBPinSource"

    /** Hostname des EIGENEN Relays (einziger vor-geseeder Pin-Host, SF5). */
    const val OWN_RELAY_HOST = "" // PRIVATSPHÄREN-FIX: kein Default-Host im Build (G-Scan)

    /** Prefs-Key (Info-Merker): konfigurierte Relay-URL = gepinnter Host. */
    const val PREF_PINS_INSTALLED = "pins_installed"

    /**
     * Pin-Set des eigenen Relays — Reihenfolge: Leaf (Positiv-Pin) →
     * Intermediate YE1 → Root YE → ISRG Root X2 (Backup-Kette, siehe KDoc).
     * Format `sha256/<Base64-(SPKI)>` — wortgetreu aus dem Beweis-Dump.
     */
    val OWN_RELAY_PINS: List<String> = emptyList() // leer im Release — Pins nur via Nutzer-Konfiguration

    /** FAILED-Anzeigetext DE (Konvention: aktive App-Sprache, W4-05 zieht um). */
    const val PIN_FAILED_DE: String =
        "Zertifikats-Pin fehlgeschlagen — Verbindung zum Relay verweigert " +
            "(Zertifikat entspricht nicht dem Pin-Set)"

    /** FAILED-Anzeigetext EN (Strings-Verband-Key `pin_failed_status`). */
    const val PIN_FAILED_EN: String =
        "Certificate pinning failed — connection to the relay refused " +
            "(certificate does not match the pin set)"

    /** Debug-Drill-Schalter; Release-Bauten ignorieren ihn hart. */
    @Volatile
    internal var debugKillSwitch: Boolean = false

    /**
     * Kill-Switch je Drill (Plan-Verband „Kill-Switch (Debug-Flag) für
     * Drills"): false = Pins aktiv. In Release-Bauten wirkungslos (Log-Warnung),
     * damit kein Nutzer-Zweig die Pin-Schiene stilllegen kann.
     */
    fun setPinningDisabledForDrill(disabled: Boolean) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Pin-Kill-Switch ignoriert — nur in Debug-Bauten schaltbar")
            return
        }
        debugKillSwitch = disabled
        Log.w(TAG, "Pin-Kill-Switch (Debug-Drill) = $disabled — Trust/Hostname-Prüfung bleibt AN")
    }

    /**
     * Pin-Set je Host — je-Server-Profil-Faser (SF5): NUR der eigene Relay-Host
     * → Pins; JEDER andere Host (Sir-Nutzer-Relays, Cleartext-Ausnahme
     * `dashboard.lan`) → null = hostname-only (System-Trust, kein TOFU-Zwang,
     * kein Bricking durch fremde Renewals). Exact-Host-Verband: Subdomains
     * bekommen bewusst KEINE Pins (Parität zu NSC includeSubdomains=false).
     */
    fun pinsFor(host: String): List<String>? {
        val normalized = host.trim().lowercase()
        return if (normalized == OWN_RELAY_HOST) OWN_RELAY_PINS else null
    }

    /**
     * Pinner für OkHttp-Clients (RelayClient-Hauptclient + UpdateInstaller-
     * Client — Verband „beide Clients pinnen"). null = Pins aus (nur
     * Debug-Kill-Switch); hostname-only-Hosts treffen KEIN Pin-Muster.
     */
    fun pinner(): CertificatePinner? {
        if (debugKillSwitch) return null
        val builder = CertificatePinner.Builder()
        for (pin in OWN_RELAY_PINS) builder.add(OWN_RELAY_HOST, pin)
        return builder.build()
    }

    /**
     * Trägt die konfigurierte Relay-URL den gepinnten Host? (Info-Merker für
     * den Pref `pins_installed`; URL-Form https/wss tolerant, Parsing-Fehler
     * = false = nicht gepinnt, nie umgekehrt.)
     */
    fun isPinnedHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { URI(url.trim()).host }.getOrNull()
            ?: Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)").find(url.trim())
                ?.groupValues?.get(1)
        return host != null && pinsFor(host) != null
    }

    /**
     * FAILED-Ursachen-Mapper: Pin-Verstoß → [PIN_FAILED_DE], alles andere →
     * null (bestehender generischer Verbindungsfehler-Text bleibt). Deckt die
     * OkHttp-Pinner-Route (SSLPeerUnverifiedException) UND die NSC-Plattform-
     * Route ("Pin verification failed" im SSLHandshakeException-Stapel) ab.
     */
    fun pinFailureText(t: Throwable): String? = when {
        t is SSLPeerUnverifiedException -> PIN_FAILED_DE
        t is SSLHandshakeException && pinText(t.message) -> PIN_FAILED_DE
        else -> null
    }

    private fun pinText(message: String?): Boolean =
        message?.contains("Pin verification failed", ignoreCase = true) == true
}
