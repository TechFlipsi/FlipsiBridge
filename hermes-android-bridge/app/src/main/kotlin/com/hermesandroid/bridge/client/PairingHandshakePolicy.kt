package com.hermesandroid.bridge.client

/**
 * W17 (F1 + F2) — reine Regeln für den WS-Kopplungs-Handshake (JVM-testbar,
 * KEINE Android-/Compose-Imports).
 *
 * F1 (Ein-Gerät-pro-Kopplungscode): der Kopplungs-Handshake trägt eine stabile
 * Gerätekennung ([HEADER_DEVICE_ID]); das Relay registriert sie bei der ersten
 * Annahme und weist ABWEICHENDE Gerät-Handshakes ab, solange die erste Verbin-
 * dung lebt (HTTP 403 + Maschinen-Code, nach dem Upgrade als WS-Close-Code).
 * Das App-Gerät selbst verbindet damit IMMER wieder (desselben Geräts Reconnect
 * hat dieselbe Kennung — kein Aussperren bei Doze/WLAN-Reconnect).
 *
 * F2 (Optionales Kopplungs-Passwort): verlangt der Server Code UND Passwort,
 * antwortet der Handshake mit [HEADER_ERROR]/WS-Close-Code — die App zeigt
 * daraufhin ihr Passwort-Prompt und STOPPT die Auto-Retries (sonst ver-
 * brennen Versuche im IP-Rate-Chain-Schild des Relays: 5 Fehlversuche/300 s).
 */
object PairingHandshakePolicy {

    /** Maschinen-Header des Relays für abgewiesene Kopplungs-Handshakes. */
    const val HEADER_ERROR = "X-Bridge-Error"

    /** Header mit der stabilen Gerätekennung (F1, per App-Install konstant). */
    const val HEADER_DEVICE_ID = "X-Device-Id"

    /** Header mit dem Kopplungs-Passwort (F2, Transport ist TLS wie der Bearer). */
    const val HEADER_PASSWORD = "X-Pairing-Password"

    /** Maschinen-Codes der Kopplungs-Abweisung (Header-Kanal UND JSON-Body). */
    const val ERROR_CODE_IN_USE = "code-in-use"
    const val ERROR_PASSWORD_REQUIRED = "password-required"
    const val ERROR_PASSWORD_INVALID = "password-invalid"

    /**
     * WS-Close-Code für den RACE-Slot (Post-Upgrade kann kein sauberer 403-JSON
     * mehr laufen; das Relay schließt denselben Sachverhalt als private-use
     * Close-Code, 4000-4999 ist wire-gültig).
     */
    const val CLOSE_CODE_IN_USE = 4403

    /** Passwortregeln des Servers (mirror /password_set-Validierung). */
    const val PASSWORD_MIN_LEN = 8
    const val PASSWORD_MAX_LEN = 256

    /** Nur bekannte Codes exakt durchlassen — Müll-Header wird NICHT zum Signal. */
    fun handshakeError(headerValue: String?): String? = when (headerValue) {
        ERROR_CODE_IN_USE -> ERROR_CODE_IN_USE
        ERROR_PASSWORD_REQUIRED -> ERROR_PASSWORD_REQUIRED
        ERROR_PASSWORD_INVALID -> ERROR_PASSWORD_INVALID
        else -> null
    }

    fun isCodeInUse(error: String?): Boolean = error == ERROR_CODE_IN_USE

    /** true für BEIDE Passwort-Antworten (fehlend/falsch) — App-Prompt-Träger. */
    fun isPasswordGate(error: String?): Boolean =
        error == ERROR_PASSWORD_REQUIRED || error == ERROR_PASSWORD_INVALID

    /** Nur echte Auth-Fehlversuche zählen: kein code-in-use, kein Passwort-Tor. */
    fun shouldBumpAuthStreak(error: String?): Boolean =
        error == null || (!isCodeInUse(error) && !isPasswordGate(error))

    /** Race-Slot-Antwort des Relays (WS-Close-Code) = code-in-use-Semantik. */
    fun closeCodeMeansCodeInUse(closeCode: Int): Boolean = closeCode == CLOSE_CODE_IN_USE

    /** true = Passwort darf NICHT mitgebracht/gesetzt werden (Server-Verband). */
    fun passwordAllowed(password: String?): Boolean =
        password != null && password.length in PASSWORD_MIN_LEN..PASSWORD_MAX_LEN
}
