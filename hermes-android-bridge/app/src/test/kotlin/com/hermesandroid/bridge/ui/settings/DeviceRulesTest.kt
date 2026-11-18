package com.hermesandroid.bridge.ui.settings

import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import org.junit.Assert.assertEquals
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
}
