package com.hermesandroid.bridge.ui.settings

import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B7 (Multi-Gerät): Regeln der Geräte-Verwaltung — Sortierung
 * (online > offline), Alias-Hygiene (APP-GENERISCH, keine Personen-/Marken-
 * namen), last_seen-Alter (Altersfaser dash_*), TTL-Mathematik der
 * Kopplungs-Anfrage (10 min) und die strenge Eigen-Gerät-Markierung.
 * Rein JVM — R-Konstanten sind plain Ints, keine Android-Laufzeit nötig.
 */
class DeviceRulesTest {

    private val t0 = 1_735_000_000_000L

    private fun device(
        id: String,
        alias: String = "",
        admin: Boolean = false,
        online: Boolean = false,
        lastSeenMs: Long = 0L,
    ) = DeviceRules.DeviceEntry(deviceId = id, alias = alias, admin = admin, online = online,
        lastSeenMs = lastSeenMs)

    // ── sortedDevices: online > offline ─────────────────────────────────────

    @Test
    fun onlineDevicesSortAboveOffline() {
        val sorted = DeviceRules.sortedDevices(
            listOf(
                device("d-off", online = false),
                device("d-on", online = true),
            ),
        )
        assertEquals("d-on", sorted[0].deviceId)
        assertEquals("d-off", sorted[1].deviceId)
    }

    @Test
    fun adminSortsFirstWithinSameOnlineState() {
        val sorted = DeviceRules.sortedDevices(
            listOf(
                device("b", online = true, admin = false),
                device("a", online = true, admin = true),
            ),
        )
        assertEquals("a", sorted[0].deviceId)
        assertEquals("b", sorted[1].deviceId)
    }

    @Test
    fun aliasSortIsCaseInsensitive() {
        val sorted = DeviceRules.sortedDevices(
            listOf(
                device("d1", alias = "tablet", online = true),
                device("d2", alias = "Tablet", online = true),
            ),
        )
        // Gleiche Groß-/Kleinschreibung-unempfindliche Ordnung — der End-
        // Stabilisator device_id entscheidet deterministisch.
        assertEquals("d1", sorted[0].deviceId)
        assertEquals("d2", sorted[1].deviceId)
    }

    @Test
    fun sortingIsDeterministicAcrossRuns() {
        val devices = listOf(
            device("x", online = false),
            device("b", alias = "Gerät 2", online = true),
            device("a", alias = "Gerät 1", online = true, admin = true),
        )
        assertEquals(DeviceRules.sortedDevices(devices), DeviceRules.sortedDevices(devices))
        assertEquals(listOf("a", "b", "x"), DeviceRules.sortedDevices(devices).map { it.deviceId })
    }

    @Test
    fun emptyListSortsToEmptyList() {
        assertTrue(DeviceRules.sortedDevices(emptyList()).isEmpty())
    }

    // ── Alias-Hygiene (APP-GENERISCH) ────────────────────────────────────────

    @Test
    fun emptyAliasFallsBackToGenericName() {
        assertEquals("Gerät 1", DeviceRules.sanitizeAlias("", fallbackIndex = 0))
        assertEquals("Gerät 2", DeviceRules.sanitizeAlias("   ", fallbackIndex = 1))
    }

    @Test
    fun nullAliasFallsBackToGenericName() {
        assertEquals("Gerät 4", DeviceRules.sanitizeAlias(null, fallbackIndex = 3))
    }

    @Test
    fun genericAliasIsOneBased() {
        assertEquals("Gerät 1", DeviceRules.genericAlias(0))
        assertEquals("Gerät 10", DeviceRules.genericAlias(9))
        // Negativer Index klemmt auf 1 (keine "Gerät 0").
        assertEquals("Gerät 1", DeviceRules.genericAlias(-3))
    }

    @Test
    fun whitespaceCollapsesAndControlCharsVanish() {
        assertEquals("Petras Handy", DeviceRules.sanitizeAlias("  Petras \t Handy  ", 0))
        assertEquals("geraet", DeviceRules.sanitizeAlias("ger\u0000a\u0007et", 0))
    }

    @Test
    fun longAliasIsCapped() {
        val long = "a".repeat(DeviceRules.ALIAS_MAX_CHARS + 20)
        assertEquals(DeviceRules.ALIAS_MAX_CHARS, DeviceRules.sanitizeAlias(long, 0).length)
    }

    @Test
    fun suggestionStartsAtOneOnEmptyRegistry() {
        assertEquals("Gerät 1", DeviceRules.nextAliasSuggestion(emptyList()))
    }

    @Test
    fun suggestionSkipsToNextFreeNumber() {
        assertEquals("Gerät 3", DeviceRules.nextAliasSuggestion(listOf("Gerät 1", "Gerät 2")))
        assertEquals("Gerät 8", DeviceRules.nextAliasSuggestion(listOf("Gerät 7")))
    }

    @Test
    fun personNamedAliasesNeverContaminateTheCounter() {
        // APP-GENERISCH: benannte Einträge (Person/Gerätezimmer) liefern KEINE
        // Zahl-Merkung — der Vorschlag bleibt die nächste freie "Gerät N".
        assertEquals(
            "Gerät 1",
            DeviceRules.nextAliasSuggestion(listOf("Petras Handy", "Wohnzimmer-Tablet")),
        )
    }

    @Test
    fun suggestionCountsOnlyRealNumbers() {
        assertEquals("Gerät 5", DeviceRules.nextAliasSuggestion(listOf("Gerät 4", "Gerät x")))
        assertEquals("Gerät 5", DeviceRules.nextAliasSuggestion(listOf("Gerät 04")))
    }

    // ── Eigen-Gerät-Markierung (never-wrong) ────────────────────────────────

    @Test
    fun ownDeviceNeedsExactMaskMatch() {
        val entry = device("<masked:36>")
        assertTrue(DeviceRules.isOwnDevice(entry, "<masked:36>"))
        assertFalse(DeviceRules.isOwnDevice(entry, "<masked:40>"))
        assertFalse(DeviceRules.isOwnDevice(entry, null))
        assertFalse(DeviceRules.isOwnDevice(device(""), "<masked:36>"))
    }

    @Test
    fun withoutOwnSignalNothingIsMarked() {
        val devices = listOf(device("<masked:36>"), device("<masked:12>", admin = true))
        // Fehlstelle-Verband: das Backend-Signal erlaubt kein sicheres Eigen-
        // Matching — ohne Übereinkunft wird NICHT markiert (never-wrong).
        assertTrue(devices.none { DeviceRules.isOwnDevice(it, null) })
    }

    // ── last_seen relativ (Altersfaser dash_*) ──────────────────────────────

    @Test
    fun unknownLastSeenShowsNoBucket() {
        assertNull(DeviceRules.lastSeenLabelRes(0L, t0))
        assertNull(DeviceRules.lastSeenLabelRes(-1L, t0))
    }

    @Test
    fun freshLastSeenCarriesJustNowKey() {
        val label = DeviceRules.lastSeenLabelRes(t0 - 30_000L, t0)
        assertEquals(R.string.dash_just_now, label?.res)
    }

    @Test
    fun minutesLastSeenCarriesMinutesKey() {
        val label = DeviceRules.lastSeenLabelRes(t0 - 5 * 60_000L, t0)
        assertEquals(R.string.dash_minutes_ago, label?.res)
        assertEquals(listOf<Any>(5), label?.args)
    }

    @Test
    fun hoursLastSeenCarriesHoursKey() {
        val label = DeviceRules.lastSeenLabelRes(t0 - 2 * 3_600_000L, t0)
        assertEquals(R.string.dash_hours_ago, label?.res)
        assertEquals(listOf<Any>(2), label?.args)
    }

    @Test
    fun daysLastSeenCarriesPluralKey() {
        val label = DeviceRules.lastSeenLabelRes(t0 - 3 * 24L * 3_600_000L, t0)
        assertEquals(R.plurals.dash_days_ago, label?.res)
        assertEquals(3, label?.count)
        assertEquals(listOf<Any>(3), label?.args)
    }

    @Test
    fun futureStampDoesNotGoNegative() {
        // Geräte-Uhr voraus (Jäger-Doku W15c): ein Zeitstempel in der Zukunft
        // wird als "gerade eben" gelesen statt negativ zu drehen.
        assertEquals(
            DashboardRules.LabelRes(R.string.dash_just_now),
            DeviceRules.lastSeenLabelRes(t0 + 90_000L, t0),
        )
    }

    // ── TTL der Kopplungs-Anfrage (10 min) ──────────────────────────────────

    @Test
    fun ttlWindowIsExactlyBackendContract() {
        // Backend: _PAIRING_TTL_SECONDS = 600 (10 min) — der Frame trägt ttl_s
        // (Fensterbeginn = ts).
        val request = DeviceRules.PairingRequest(
            requestId = "r1", maskedDeviceId = "<masked:12>",
            tsMs = t0, ttlS = 600L,
        )
        assertEquals(600_000L, DeviceRules.pairingRemainingMs(request, t0))
        assertFalse(DeviceRules.pairingExpired(request, t0 + 599_000L))
        assertFalse(DeviceRules.pairingExpired(request, t0 + 600_000L - 1))
        assertTrue(DeviceRules.pairingExpired(request, t0 + 600_000L))
    }

    @Test
    fun expiredClampsToZeroAndExpires() {
        val request = DeviceRules.PairingRequest(
            "r1", "<masked:12>", tsMs = t0, ttlS = 600L,
        )
        assertTrue(DeviceRules.pairingRemainingMs(request, t0 + 700_000L) == 0L)
        assertTrue(DeviceRules.pairingExpired(request, t0 + 700_000L))
    }

    @Test
    fun countdownIsMinutesSecondsRootLocale() {
        assertEquals("10:00", DeviceRules.pairingCountdown(600_000L))
        assertEquals("9:59", DeviceRules.pairingCountdown(599_000L))
        assertEquals("0:00", DeviceRules.pairingCountdown(0L))
        assertEquals("1:01", DeviceRules.pairingCountdown(61_000L))
    }

    // ── W23-9: eigenes Umbenennen-Ziel (VERTRAGS-KORREKTUR) ────────────────────

    /**
     * W23-9 (VERTRAGS-KORREKTUR): [DeviceRules.ownAliasTarget] liefert das
     * EINDEUTIGE request_id-Handle der eigenen relay.state-Kachel (Maske ist
     * am Relay nicht eindeutig auflösbar) + die Maske als Info — NIE-WRONG:
     * Mehrdeutigkeit/kein Handle → null (nicht senden).
     */
    @Test
    fun ownAliasTargetResolvesEindeutigesHandle() {
        val devices = listOf(
            DeviceRules.DeviceEntry(deviceId = "<masked:12>", requestId = "rev-2"),
            DeviceRules.DeviceEntry(deviceId = "<masked:36>", requestId = "rev-7"),
        )
        // Eigene rohe Kennung mit 36 Zeichen (Form-Gate-gültig) → Maske
        // "<masked:36>", in diesem Stand GENAU EINmal = eindeutig bewiesen.
        val raw = "0123456789abcdef0123456789abcdef0123"
        val target = DeviceRules.ownAliasTarget(devices, raw)!!
        assertEquals("<masked:36>", target.deviceIdMasked)
        assertEquals("rev-7", target.requestId)
    }

    @Test
    fun ownAliasTargetMehrdeutigeMaskeLiefertNull() {
        // Zwei 36-Zeichen-UUID-Geräte kollidieren auf "<masked:36>" — NIE-WRONG:
        // kein Ziel, kein Senden (der Name bleibt später am eindeutig
        // gemeldeten Stand setzbar).
        val devices = listOf(
            DeviceRules.DeviceEntry(deviceId = "<masked:36>", requestId = "a"),
            DeviceRules.DeviceEntry(deviceId = "<masked:36>", requestId = "b"),
        )
        val raw = "0123456789abcdef0123456789abcdef0123"
        assertNull(DeviceRules.ownAliasTarget(devices, raw))
    }

    @Test
    fun ownAliasTargetOhneHandleLiefertNull() {
        // Alter Relay (devices[] ohne request_id-Feld) → leerer Handle = null.
        val devices = listOf(DeviceRules.DeviceEntry(deviceId = "<masked:12>", requestId = ""))
        val raw = "0123456789abcdef0123"
        assertNull(DeviceRules.ownAliasTarget(devices, raw))
    }

    @Test
    fun ownAliasTargetFremdeFormOderLeererStandLiefertNull() {
        assertNull("Form-Gate: ungültige eigene Kennung → null", DeviceRules.ownAliasTarget(
            listOf(DeviceRules.DeviceEntry(deviceId = "<masked:5>", requestId = "r")),
            "bad id",
        ))
        assertNull("leerer Stand → null", DeviceRules.ownAliasTarget(emptyList(), "0123456789abcdef"))
    }

    // ── W26 (Audit „Geräte-Karte ehrlich“): 14-Tage-Räumungs-Hinweis ─────────

    @Test
    fun entfernungshinweisNurOfflineOhneAdminMitBekanntemLastSeen() {
        // Das Backend räumt offline Geräte >14 Tage (_registry_sweep, Admin
        // IMMER geschützt) — der Hinweis läuft NUR dort, wo die Frist läuft.
        assertTrue(
            "offline + kein Admin + last_seen bekannt = Frist läuft sichtbar",
            DeviceRules.idleRemovalHintApplies(
                DeviceRules.DeviceEntry(deviceId = "<masked:36>", online = false, lastSeenMs = t0),
            ),
        )
        // Kontrastfälle — jeder einzeln hebt den Hinweis auf (nie geraten):
        assertFalse(
            "ONLINE Gerät frischt last_seen je Slot-Pflege — Frist läuft nicht",
            DeviceRules.idleRemovalHintApplies(
                DeviceRules.DeviceEntry(deviceId = "<masked:36>", online = true, lastSeenMs = t0),
            ),
        )
        assertFalse(
            "ADMIN ist vom Sweep IMMER ausgenommen (Selbst-Lockout-Schutz)",
            DeviceRules.idleRemovalHintApplies(
                DeviceRules.DeviceEntry(deviceId = "<masked:36>", admin = true, lastSeenMs = t0),
            ),
        )
        assertFalse(
            "last_seen 0 = unbekannt (Frame-Fehlstelle) — die Regel rät nicht",
            DeviceRules.idleRemovalHintApplies(
                DeviceRules.DeviceEntry(deviceId = "<masked:36>", online = false, lastSeenMs = 0L),
            ),
        )
    }

    // ── W26d (R5): das Hinweis-Gate folgt dem ECHTEN ENV-Stand ────────────────

    private fun idleOfflineEntry() =
        DeviceRules.DeviceEntry(deviceId = "<masked:36>", online = false, lastSeenMs = t0)

    @Test
    fun hinweisFolgtDemGeliefertenEnvStand() {
        // n > 0 (Sweep aktiv je ENV): Form-Welt UNVERÄNDERT — der Hinweis
        // läuft dort, wo die Frist läuft.
        assertTrue(DeviceRules.idleRemovalHintApplies(idleOfflineEntry(), 14L))
        assertTrue(DeviceRules.idleRemovalHintApplies(idleOfflineEntry(), 30L))
        assertFalse(DeviceRules.idleRemovalHintApplies(idleOfflineEntry().copy(admin = true), 14L))

        // 0 (Sweep AUS je ENV): KEIN Hinweis — statt der Behauptung, die nie
        // wahr wird (die unveränderliche „14 Tage"-Zeile lügt dann konstant,
        // Report R5).
        assertFalse(
            "Sweep AUS ⇒ der falsche Räum-Hinweis GEHT statt zu behaupten",
            DeviceRules.idleRemovalHintApplies(idleOfflineEntry(), 0L),
        )
        assertFalse(DeviceRules.idleRemovalHintApplies(idleOfflineEntry(), -1L))

        // null (alter Relay ohne device_idle_days-Feld = Stand UNBEKANNT):
        // der Bestand (Hinweis je Form) bleibt — nichts erfunden, nichts geraten.
        assertTrue(DeviceRules.idleRemovalHintApplies(idleOfflineEntry(), null))
    }

    // ── W26d (F6): die Mehrdeutig-Maske wird SEHbar statt stumm versteckt ─────

    @Test
    fun eigeneMaskeMehrdeutigJeZweiTraegern() {
        // Der Normalfall je zweier App-Phones (36-Zeichen-UUIDs): beide
        // „<masked:36>“ — die eigene Maske ist MEHRDEUTIG belegt.
        // ZF2 (W27, Suite-Nachziehen): das Fixture-UUID war 37 Zeichen (letzte
        // Gruppe 13) — die Maske las „<masked:37>" neben den „<masked:36>"-
        // Trägern und der Mehrdeutigkeits-Sieg kippte auf false. Jetzt eine
        // echte 36-Zeichen-UUID (8-4-4-4-12).
        val raw = "a1b2c3d4-e1f2-4a1b-8c3d-5e6f7a8b9c0d"
        assertEquals(36, raw.length)
        val two36 = listOf(
            DeviceRules.DeviceEntry(deviceId = "<masked:36>", admin = true),
            DeviceRules.DeviceEntry(deviceId = "<masked:36>", admin = false),
        )
        assertTrue(
            "zwei Träger derselben Maske = der F6-Fall (Hinweis-Karte, nie stumm)",
            DeviceRules.ownMaskAmbiguous(two36, raw),
        )
        // Ein Träger = eindeutig (ownMaskedDeviceId löst) — keine Mehrdeutigkeit.
        assertFalse(
            DeviceRules.ownMaskAmbiguous(
                listOf(DeviceRules.DeviceEntry(deviceId = "<masked:36>", admin = true)),
                raw,
            ),
        )
        // Kein Form-Gate-Beweis (rohe Kennung unformig/leer/null) → nie
        // Mehrdeutigkeit behauptet (nie-wrong).
        assertFalse(DeviceRules.ownMaskAmbiguous(two36, ""))
        assertFalse(DeviceRules.ownMaskAmbiguous(two36, "mit space!"))
        assertFalse(DeviceRules.ownMaskAmbiguous(two36, null))
        assertFalse(DeviceRules.ownMaskAmbiguous(emptyList(), "abc"))
    }
}
