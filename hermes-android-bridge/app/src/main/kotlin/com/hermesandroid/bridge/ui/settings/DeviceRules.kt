package com.hermesandroid.bridge.ui.settings

import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import java.util.Locale

/**
 * B7 (Multi-Gerät, Plan docs/roadmap-multigeraete.md Kap. 4.2): Regeln der
 * Geräte-Verwaltung — rein, JVM-testbar, KEINE Android-Framework-Imports
 * (gleiche Faser wie DashboardRules: hier leben nur Wahrheit + Ordnung,
 * ihre Texte hängt die UI an).
 *
 * Backend-Verband (HEAD 24acda0, contrib/hermes-chat-plugin/adapter.py +
 * tools/android_relay.py):
 *  - relay.state devices[]: {device_id: per _mask_id NUR "<masked:N>" —
 *    KEINE stabile maskierte Kennung, alle Geräte gleicher Länge wirken
 *    identisch (Fehlstelle, Report B7); alias: Backend setzt beim Enroll
 *    KEINEN — leer ist der Normalfall; admin; online}. Der Frame listet
 *    NUR die LIVE verbundene Slots; offline Registry-Geräte fehlen ihm und
 *    last_seen steckt NICHT im Frame: lastSeenMs ist deshalb 0L =
 *    "unbekannt erlaubt" und wird ehrlich nicht angezeigt.
 *  - pairing.request-Frame: {request_id, device_id_masked, ip, ts(ms),
 *    ttl_s = 600 (10 min, _PAIRING_TTL_SECONDS), Dedupe im TTL-Fenster}.
 *    Der Frame trägt KEINEN Alias-Vorschlag — der Name wird APP-GENERISCH
 *    gebildet ([nextAliasSuggestion], "Gerät N", KEIN Personen-/Markenbezug).
 *    Freigabe/Abweisung läuft als pairing.approve/pairing.deny vom Admin-
 *    Gerät zurück; das Relay löst device_id AUCH über request_id auf —
 *    deshalb ist [PairingRequest.requestId] der Antwortträger (die App
 *    sieht nur die Maske, nie die rohe device_id).
 *  - Revoke: das Backend hat NOCH KEINEN Frame ("device-revoked" ist nur
 *    Plan Kap. 4.2, Close-Code 4403-Familie) — die UI-Kette läuft vollständig
 *    bis zur Bestätigung, das Absenden bleibt TODO (B-Lane/Backend), siehe
 *    DevicesCard.
 */
object DeviceRules {

    /** Ein Gerät der relay.state-Liste (verengter Frame-Verband). */
    data class DeviceEntry(
        /** maskierte Kennung des Relays — Form "<masked:N>" (PII-Regel). */
        val deviceId: String,
        /** Backend setzt heute keinen — leer ist der Normalfall. */
        val alias: String = "",
        /** Admin-Slot (verwaltet Anmelde-Tor + Entfernen). */
        val admin: Boolean = false,
        /** Live-WS verbunden (relay.state listet ohnehin nur diese). */
        val online: Boolean = false,
        /** Unix-ms der letzten Sichtung — 0L = unbekannt (Frame-Fehlstelle). */
        val lastSeenMs: Long = 0L,
    )

    /** Offene Kopplungs-Anfrage (pairing.request-Frame verengt). */
    data class PairingRequest(
        /** Antwortträger — approve/deny über request_id (App kennt nur Maske). */
        val requestId: String,
        /** Maskierte Kennung des Anfragenden (PII-Maske des Relays). */
        val maskedDeviceId: String,
        /** Remote-IP aus dem Tor-Frame (Kontext für die Freigabe-Entscheid). */
        val ip: String = "",
        /** Frame-ts in ms (Fensterbeginn). */
        val tsMs: Long = 0L,
        /** TTL aus dem Frame — Backend _PAIRING_TTL_SECONDS = 600 s (10 min). */
        val ttlS: Long = 600L,
    )

    /** Alias-Deckel (DoS-Hygiene im UI; Backend kennt heute gar keinen). */
    val ALIAS_MAX_CHARS = 32

    /**
     * Ordnung der Geräte-Liste: online > offline, dann Admin-Slots zuerst
     * (sie verwalten Anmelde-Tor + Entfernen), dann Alias (Groß-/Klein-
     * schreibung unempfindlich), dann device_id als End-Stabilisator.
     * Deterministisch bei JEDEM Snapshot (kein Flackern in der UI).
     */
    fun sortedDevices(devices: List<DeviceEntry>): List<DeviceEntry> =
        devices.sortedWith(
            compareByDescending<DeviceEntry> { it.online }
                .thenByDescending { it.admin }
                .thenBy { it.alias.trim().lowercase() }
                .thenBy { it.deviceId },
        )

    /**
     * Alias-Hygiene: Steuerzeichen fliegen, Whitespace kollabiert, Länge wird
     * auf [ALIAS_MAX_CHARS] gestutzt. Leere/unsichtbare Reste fallen auf den
     * APP-GENERISCHEN Namen zurück ("Gerät N" — KEIN Personen-/Markenbezug,
     * App-generiert, das Backend setzt ohnehin keinen).
     */
    fun sanitizeAlias(raw: String?, fallbackIndex: Int): String {
        val cleaned = (raw ?: "")
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isEmpty()) {
            genericAlias(fallbackIndex)
        } else {
            cleaned.take(ALIAS_MAX_CHARS)
        }
    }

    /** Der generische Name im App-Format: "Gerät 1", "Gerät 2", … (1-basiert). */
    fun genericAlias(fallbackIndex: Int): String =
        "Gerät ${(fallbackIndex + 1).coerceAtLeast(1)}"

    /**
     * Alias-Vorschlag für eine Kopplungs-Anfrage: der Frame trägt keinen —
     * die App schlägt die freie nächste "Gerät N"-Nummer vor. Bestehende
     * Namen NUR als Zahlen-Muster gelesen (keine Personen-/Markennamen-
     * Kontamination möglich), die höchste gefundene Nummer + 1 gewinnt.
     */
    fun nextAliasSuggestion(existingAliases: List<String>): String {
        var highest = 0
        for (raw in existingAliases) {
            val match = GENERIC_ALIAS_RE.matchEntire(raw.trim()) ?: continue
            val n = match.groupValues[1].toIntOrNull() ?: continue
            if (n > highest) highest = n
        }
        return genericAlias(highest)
    }

    private val GENERIC_ALIAS_RE = Regex("^Gerät\\s*(\\d+)$")

    /**
     * "Dieses Gerät"-Markierung: NUR bei exakter Übereinstimmung der maskierten
     * Kennung. Bewusst streng — das Backend-Signal ("<masked:N>") erlaubt heute
     * KEIN sicheres Eigen-Gerät-Matching (alle Masken tragen nur die Länge,
     * Fehlstelle Report B7); ohne Übereinkunft wird NICHT markiert statt
     * falsch markiert (never-wrong vor always-right).
     */
    fun isOwnDevice(entry: DeviceEntry, ownMaskedDeviceId: String?): Boolean =
        ownMaskedDeviceId != null && entry.deviceId.isNotEmpty() &&
            entry.deviceId == ownMaskedDeviceId

    /**
     * last_seen relativ: DashboardRules.relativeTime ist DIE Eine-Faser für
     * relative Zeiten ("gerade eben"/"vor X min"/"vor X Std."/"vor X Tagen").
     * lastSeenMs <= 0 (unbekannt — Frame-Fehlstelle) → null: die UI zeigt
     * dann keinen Zeit-Eimer, nie eine Fake-Uhrzeit. Zeit in der ZUKUNFT
     * (Geräte-Uhr voraus, Jäger-Doku W15c) fängt der negative Delta der
     * Regel als "gerade eben" — ehrlich unscharf statt negativer Anzeige.
     */
    fun lastSeenLabelRes(lastSeenMs: Long, nowMs: Long): DashboardRules.LabelRes? =
        DashboardRules.relativeTime(lastSeenMs, nowMs)

    /**
     * TTL-Restzeit der Kopplungs-Anfrage in ms — abgelaufene/negative Deltas
     * klemmen auf 0 (kein Negativwert gelangt ins UI).
     */
    fun pairingRemainingMs(request: PairingRequest, nowMs: Long): Long =
        (request.tsMs + request.ttlS * 1000L - nowMs).coerceAtLeast(0L)

    /** Abgelaufen? Restzeit 0 = das Tor hat die Anfrage verfallen lassen. */
    fun pairingExpired(request: PairingRequest, nowMs: Long): Boolean =
        pairingRemainingMs(request, nowMs) <= 0L

    /**
     * Countdown-Format "m:ss" (Locale.ROOT — keine länderspezifischen
     * Doppelpunkte, gleiche Konvention wie workingElapsedLabel). Werte über
     * dem TTL-Deckel (600 s → "10:00") sind kein Extra-Fall: floor-Sekunden.
     */
    fun pairingCountdown(remainingMs: Long): String {
        val totalSec = remainingMs / 1000L
        return String.format(Locale.ROOT, "%d:%02d", totalSec / 60L, totalSec % 60L)
    }
}
