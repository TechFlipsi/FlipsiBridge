package com.hermesandroid.bridge.ui.settings

import androidx.compose.runtime.Immutable  // W24 Lane D: Anzeige-Datenstabilität (DeviceEntry)
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import java.util.Locale

/**
 * B7 (Multi-Gerät, Plan docs/Multigeräte-Konzept Kap. 4.2): Regeln der
 * Geräte-Verwaltung — rein, JVM-testbar, KEINE Android-Framework-Imports
 * (gleiche Faser wie DashboardRules: hier leben nur Wahrheit + Ordnung,
 * ihre Texte hängt die UI an).
 *
 * Backend-Verband (APP-ANSCHLUSS-Klärung, HEAD 733504c GELESEN —
 * tools/android_relay.py _send_phone_state, NICHT geändert):
 *  - relay.state devices[] (JE Phone-Hallo-/Presence-Frame): je Eintrag
 *    {device_id: per _mask_id NUR "<masked:N>" — die Maske trägt NUR die
 *    LÄNGE der rohen Kennung, KEINE stabile maskierte Kennung (alle Geräte
 *    gleicher Länge wirken identisch); alias: Backend setzt beim Enroll
 *    KEINEN — leer ist der Normalfall; admin; online; last_seen: Unix-ms
 *    der letzten Registrierungs-/Slot-Pflege (0 = unbekannt — für frühere
 *    Frames gilt das ehrlich weiter); request_id: EPHEMERES B4-Revoke-
 *    Handle (_device_revoke_id, in-memory) = Zielträger des device_revoke-
 *    Frames}. Der Frame liest Registry ∪ LIVE-Slots — OFFLINE Geräte
 *    bleiben sichtbar (online=False + last_seen).
 *  - pairing.request-Frame: {request_id, device_id_masked, ip, ts(ms),
 *    ttl_s = 600 (10 min, _PAIRING_TTL_SECONDS), Dedupe im TTL-Fenster}.
 *    Der Frame trägt KEINEN Alias-Vorschlag — der Name wird APP-GENERISCH
 *    gebildet ([nextAliasSuggestion], "Gerät N", KEIN Personen-/Markenbezug).
 *    Freigabe/Abweisung läuft als pairing.approve/pairing.deny vom Admin-
 *    Gerät zurück; das Relay löst device_id AUCH über request_id auf —
 *    deshalb ist [PairingRequest.requestId] der Antwortträger (die App
 *    sieht nur die Maske, nie die rohe device_id).
 *  - Revoke (B4, seit HEAD 733504c real): device_revoke vom Phone
 *    {type: "device_revoke", request_id ODER raw device_id; OHNE Ziel =
 *    Self-Revoke} — die App sendet bewusst NUR das request_id-Handle
 *    (PII-Regel); fremde Geräte entfernen NUR Admin-Slots (fail-closed
 *    am Relay), das Ziel wird mit Close-Code 4404 "device-revoked"
 *    gekickt. Die 2-stufige UI-Kette sendet erst nach der Bestätigung
 *    und NUR mit Handle (DevicesCard/SettingsHome-Verdrahtung).
 */
object DeviceRules {

    /**
     * Ein Gerät der relay.state-Liste (verengter Frame-Verband).
     *
     * W24 (Lane D): @Immutable je ANZEIGE-Datenklasse (T7-F7 — NUR die reine
     * Anzeige-Form, NICHT am Event-Kern): DevicesCard-Skips je unveränderter
     * Kachel statt je relay.state-Frame die Geräte-Zeilen neu. Die Regel-
     * Faser bleibt unverändert JVM-testbar (die Annotation ist Compiler-
     * Metadaten aus androidx.compose.runtime, kein Framework-Zwang in der
     * Rechen-Logik).
     */
    @Immutable
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
        /**
         * APP-ANSCHLUSS-Verdrahtung (B4-Kontrakt, HEAD 733504c): das
         * EPHEMERE Revoke-Handle des Relays (relay.state devices[].request_id,
         * _device_revoke_id — in-memory uuid4-Präfix). Einziges Zielträger-
         * Konzept, das die App je Geräte-Eintrag kennt: das device_revoke-
         * Frame löst das Ziel über REQUEST_ID auf (Parität pairing.approve-
         * Norm: request_id ODER raw device_id — die App hält NIEMALS rohe
         * Kennungen, PII-Regel). Leer = Frame ohne request_id-Feld (alter
         * Relay vor B4) → die UI verweigert das Senden (fail-closed: ein
         * Frame OHNE Ziel würde am Backend als Self-Revoke wirken).
         */
        val requestId: String = "",
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
     * W23-9 (VERTRAGS-KORREKTUR): Zielträger des Umbenennen-Frames — das
     * EINDEUTIGE request_id-Handle der relay.state-Zeile (Maske ist am Relay
     * NICHT eindeutig auflösbar, nur Länge der rohen Kennung) + die Maske
     * selbst als Info-Feld daneben.
     */
    data class OwnAliasTarget(
        /** Eindeutiges Handle der eigenen Kachel (PRIMARY im Frame). */
        val requestId: String,
        /** Maske der eigenen Kachel (Info-Feld im Frame, "<masked:N>"). */
        val deviceIdMasked: String,
    )

    /**
     * Eigenes Umbenennen-Ziel NIE-WRONG ableiten: die eigene Maske wird über
     * [ownMaskedDeviceId] aufgelöst (Null = mehrdeutig/keine Kennung) und die
     * Kachel muss ein Handle tragen. Kein Beweis (Mehrdeutigkeit, Handle
     * leer — alter Relay) → null: der Aufrufer sendet BEWUSST NICHT
     * (never-wrong vor always-right; der Name bleibt später über Einstellungen
     * › Geräte setzbar, nachdem die eindeutige Kachel gemeldet ist).
     */
    fun ownAliasTarget(
        devices: List<DeviceEntry>,
        rawOwnDeviceId: String?,
    ): OwnAliasTarget? {
        val mask = ownMaskedDeviceId(devices, rawOwnDeviceId) ?: return null
        val own = devices.firstOrNull { it.deviceId == mask } ?: return null
        val handle = own.requestId.trim()
        if (handle.isEmpty()) return null
        return OwnAliasTarget(handle, own.deviceId)
    }

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
     * Kennung. Bewusst streng — markiert wird NUR mit einer Maske, die
     * [ownMaskedDeviceId] NIE-WRONG aufgelöst hat; ohne Auflösung (null) wird
     * NICHT markiert statt falsch markiert (never-wrong vor always-right).
     *
     * APP-ANSCHLUSS-Klärung (Backend HEUTE gelesen, NICHT geändert — HEAD
     * 733504c, tools/android_relay.py _mask_id): _mask_id(value) =
     * "<masked:{len(value)}>" — die Maske trägt NUR die LÄNGE der rohen
     * Kennung. Die App kennt ihre EIGENE rohe Kennung (PairingManager.deviceId,
     * per-Install UUID) → ihre EIGENE Maske ist app-seitig MECHANISCH
     * berechenbar; [ownMaskedDeviceId] deckelt die Mehrdeutigkeit bewusst mit
     * dem Einträger-Zähl-Gate, damit das Exakt-Matching hier nie-wrong bleibt.
     */
    fun isOwnDevice(entry: DeviceEntry, ownMaskedDeviceId: String?): Boolean =
        ownMaskedDeviceId != null && entry.deviceId.isNotEmpty() &&
            entry.deviceId == ownMaskedDeviceId

    /**
     * Backend-Form-Gate der Geräte-Kennung (Spiegel zu tools/android_relay.py
     * _DEVICE_ID_RE: "[A-Za-z0-9._-]{1,64}") — NUR eine Kennung dieser Form
     * überlebt _normalize_registry_device_id UNVERÄNDERT (Länge bleibt ==
     * Länge der rohen App-Kennung); alles andere fällt ans Backend auf
     * "phone" (5 Zeichen) zurück. Ohne dieses Gates wäre die Länge-Maske im
     * Sonderfall NICHT mehr die eigene — never-wrong schließt den Fall aus.
     */
    private val RAW_DEVICE_ID_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    /**
     * APP-ANSCHLUSS (Self-Match-Klärung): maskierte EIGENE Kennung für
     * [isOwnDevice] — oder null (bewusst DEAKTIVIERT) wenn kein NIE-WRONG-
     * Beweis möglich ist. Herleitung aus gelesenem Backend-Verband:
     *
     *  1. _mask_id(value) = "<masked:{len(value)}>" — Maske ⟺ Länge der rohen
     *     Kennung; die Sonderform "none" entsteht NUR für leere Werte und
     *     trägt die "<masked:"-Form nie.
     *  2. Der Frame listet Registry ∪ LIVE-Slots (devices[]-Aufbau) — der
     *     EIGENE Slot ist immer dabei, solange dieser Frame ankommt.
     *  3. Form-Gate (oben): nur bei RE-gültiger Kennung ist die Maske des
     *     eigenen Eintrags "<masked:{len(own)}>".
     *  4. GATE: trägt GENAU EIN Eintrag diese Maske, so hat genau ein Geräte-
     *     Eintrag diese Länge — und der eigene (2./3.) zählt auch dazu →
     *     der einzige Träger IST der eigene Eintrag. Beweis-streng nie-wrong.
     *
     * Tragen ≥ 2 Einträge dieselbe Maske (der Normalfall je zwei App-Geräte:
     * beide per-Install-UUIDs mit 36 Zeichen → beide "<masked:36>"), ist der
     * Abgleich MEHRDEUTIG → null: KEIN "Dieses Gerät"-Chip (deaktiviert,
     * ehrlich — never-wrong vor always-right, Besitzer-Regel). Der Wert ist REIN
     * abgeleitet — KEINE Server-Abfrage, NIEMALS geraten.
     *
     * @param devices aktueller relay.state devices[]-Snapshot.
     * @param rawOwnDeviceId EIGENE rohe Kennung (PairingManager.deviceId()).
     */
    fun ownMaskedDeviceId(devices: List<DeviceEntry>, rawOwnDeviceId: String?): String? {
        val raw = rawOwnDeviceId?.trim().orEmpty()
        if (!RAW_DEVICE_ID_RE.matches(raw)) return null
        val mask = "<masked:${raw.length}>"
        return if (devices.count { it.deviceId == mask } == 1) mask else null
    }

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
     * W26 (Audit „Geräte-Karte ehrlich" — 14-Tage-Räumung sichtbar): das
     * Backend räumt die Registry automatisch je `_registry_sweep` (W23-Besitzer-
     * Regel: Geräte >N Tage OHNE Online-Kontakt fallen raus; Admin-Geräte
     * sind vom Sweep IMMER ausgenommen — Selbst-Lockout-Schutz). Die UI
     * zeigt den Räum-Hinweis daher nur, wo die Frist WIRKLICH läuft:
     * aktuell OFFLINE + KEIN Admin + last_seen bekannt (> 0 — 0 = unbekannt,
     * die Regel rät nicht über die Frame-Fehlstelle). Online-Geräte frischen
     * last_seen je Slot-Pflege — die Frist läuft dort nicht sichtbar.
     *
     * W26d (R5, UX-Report): die Frist ist ENV-geformt (ANDROID_RELAY_DEVICE_
     * MAX_IDLE_DAYS, default 14, 0 = SWEEP AUS) — das Relay transportiert
     * sie je relay.state-Hallo/Presence-Frame (Frame-Kopf-Feld
     * `device_idle_days`, additiver Vertrag; alter Relay ohne Feld = null).
     * JE geliefertem Stand ist die UI ehrlich:
     *   null (alter Relay, Stand unbekannt) → Bestand-Gate (Hinweis je Form),
     *   0 (Sweep AUS)   → false: der Hinweis GEHT statt einer Behauptung,
     *                     die nie wahr wird (die Form-Welt allein lügt dann),
     *   n > 0 (Stand)    → Hinweis mit der ECHTEN Zahl (dev_idle_removal_hint_days).
     */
    fun idleRemovalHintApplies(entry: DeviceEntry, idleDays: Long?): Boolean {
        if (idleDays != null && idleDays <= 0L) return false // Sweep AUS — ehrlich
        return idleRemovalHintApplies(entry)
    }

    /** Form-Gate der Hinweis-Welt (Stand-unabhängig — der Bestand). */
    fun idleRemovalHintApplies(entry: DeviceEntry): Boolean =
        !entry.online && !entry.admin && entry.lastSeenMs > 0L

    /**
     * W26d (F6, UX-Report): die EIGENE Längen-Maske ist MEHRDEUTIG belegt
     * (≥ 2 Geräte tragen „<masked:N>" derselben Länge — der Normalfall je
     * zwei App-Phones mit 36-Zeichen-UUIDs). Genau dann verschwand die
     * „Agent hinzufügen"-Karte STUMM (ownMaskedDeviceId = null ⇒
     * isAdminDevice false) — die App konnte die Mehrdeutigkeit sehen, hielt
     * sie aber vor dem Nutzer verborgen. TRUE (Form-Gate gültig + Mindest-
     * zwei Träger) schaltet den ehrlichen Hinweis-Text frei (die Karte
     * bleibt sichtbar); false = die Stumm-Welt betrifft hier NICHT.
     */
    fun ownMaskAmbiguous(devices: List<DeviceEntry>, rawOwnDeviceId: String?): Boolean {
        val raw = rawOwnDeviceId?.trim().orEmpty()
        if (!RAW_DEVICE_ID_RE.matches(raw)) return false
        return devices.count { it.deviceId == "<masked:${raw.length}>" } >= 2
    }

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
