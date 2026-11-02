package com.hermesandroid.bridge.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PH-2X X-2 — DeviceIntentResolver-Liste-Integrität (X-2-Spec: je Profil
 * ≥ 4 Kandidaten je Feature, keine Duplikate, Fallback-Text je Profil) +
 * Hersteller-Erkennung + no-crash-Fallback-Pfad (PackageManager gemockt).
 * Robolectric: echte Intent/Uri-Semantik für die toIntent-Assertions.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceIntentResolverTest {

    private val appPackage = "at.flipsi.bridge"

    // ── Hersteller-Erkennung (Build.MANUFACTURER) ─────────────────────────

    @Test
    fun hersteller_erkennung_je_profil() {
        val map = mapOf(
            "Xiaomi" to DeviceProfile.XIAOMI,
            "xiaomi" to DeviceProfile.XIAOMI,
            "Redmi" to DeviceProfile.XIAOMI,
            "POCO" to DeviceProfile.XIAOMI,
            "samsung" to DeviceProfile.SAMSUNG,
            "Samsung" to DeviceProfile.SAMSUNG,
            "OPPO" to DeviceProfile.OPPO,
            "OnePlus" to DeviceProfile.OPPO,   // bewusst im ColorOS-Profil
            "realme" to DeviceProfile.OPPO,
            "vivo" to DeviceProfile.VIVO,
            "iQOO" to DeviceProfile.VIVO,
            "HUAWEI" to DeviceProfile.HUAWEI,
            "Honor" to DeviceProfile.HUAWEI,  // com.huawei.systemmanager
            "motorola" to DeviceProfile.MOTOROLA,
            "Nothing" to DeviceProfile.NOTHING,
            "Google" to DeviceProfile.GENERIC,
            "unknown" to DeviceProfile.GENERIC,
            "" to DeviceProfile.GENERIC,
        )
        map.forEach { (manufacturer, expected) ->
            assertEquals("$manufacturer → $expected", expected, DeviceProfile.fromManufacturer(manufacturer))
        }
        assertEquals(DeviceProfile.GENERIC, DeviceProfile.fromManufacturer(null))
    }

    // ── Listen-Integrität: je Profil × Feature ≥ 4 Kandidaten, keine Dupes ─

    @Test
    fun je_profil_je_feature_mindestens_vier_kandidaten() {
        for (profile in DeviceProfile.entries) {
            for (feature in DeviceFeature.entries) {
                val list = DeviceIntentResolver.candidates(profile, feature)
                assertTrue("$profile/$feature hatte nur ${list.size}", list.size >= 4)
            }
        }
    }

    @Test
    fun keine_duplikate_je_profil_und_feature() {
        for (profile in DeviceProfile.entries) {
            for (feature in DeviceFeature.entries) {
                val list = DeviceIntentResolver.candidates(profile, feature)
                val keys = list.map { it.identityKey() }
                assertEquals("$profile/$feature: Duplikat in $keys", keys.size, keys.toSet().size)
            }
        }
    }

    @Test
    fun jeder_kandidat_hat_action_oder_komponente() {
        for (profile in DeviceProfile.entries) {
            for (feature in DeviceFeature.entries) {
                DeviceIntentResolver.candidates(profile, feature).forEach { c ->
                    assertTrue(
                        "$profile/$feature: ${c.label} ohne Ziel",
                        (c.action != null && c.action!!.isNotBlank()) || (c.isComponent && c.cls!!.isNotBlank() && c.pkg!!.isNotBlank()),
                    )
                }
            }
        }
    }

    @Test
    fun xiaomi_autostart_bleibt_erster_kandidat_wie_der_ehemalige_hardcode() {
        val first = DeviceIntentResolver.candidates(DeviceProfile.XIAOMI, DeviceFeature.AUTOSTART).first()
        assertEquals("com.miui.securitycenter", first.pkg)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", first.cls)
        // Der alte Hardcode-Extra bleibt erhalten:
        assertEquals("extra_pkgname", first.extras.keys.first())
    }

    @Test
    fun generische_kandidaten_entsprechen_den_bekannten_aosp_wegen() {
        // Akku: erster generischer Kandidat = Systemdialog (altes Verhalten).
        val battery = DeviceIntentResolver.candidates(DeviceProfile.GENERIC, DeviceFeature.BATTERY)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery[0].action)
        assertEquals("package:$appPackage", battery[0].dataUri?.replace("\$app", appPackage))
        // Overlay: mit-URI → ohne-URI → App-Details → Startseite (v0.12.0-Fix).
        val overlay = DeviceIntentResolver.candidates(DeviceProfile.GENERIC, DeviceFeature.OVERLAY)
        assertEquals(4, overlay.size)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay[0].action)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay[1].action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, overlay[2].action)
    }

    @Test
    fun to_intent_setzt_komponente_data_und_extras_mit_app_paketnamen() {
        val component = DeviceIntentResolver.candidates(DeviceProfile.XIAOMI, DeviceFeature.AUTOSTART)[0]
        val intent = component.toIntent(appPackage)
        assertEquals("com.miui.securitycenter", intent.component?.packageName)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", intent.component?.className)
        assertEquals(appPackage, intent.getStringExtra("extra_pkgname"))

        val generic = DeviceIntentResolver.candidates(DeviceProfile.GENERIC, DeviceFeature.BATTERY)[0]
        val batteryIntent = generic.toIntent(appPackage)
        assertEquals("package:$appPackage", batteryIntent.data.toString())
    }

    // ── Fallback: informative Leit-Zeile je OEM (kein Crash) ───────────────

    @Test
    fun fallback_text_je_profil_gefuellt_und_oem_spezifisch() {
        for (feature in DeviceFeature.entries) {
            val texts = DeviceProfile.entries.map { DeviceIntentResolver.fallbackText(it, feature) }
            // je Profil EIN eigener Text (8 Profile = 8 verschiedene Zeilen):
            assertEquals("Feature $feature: Fallback-Texte nicht je Profil", DeviceProfile.entries.size, texts.toSet().size)
            texts.forEach { assertTrue("Leer-Text für $feature", it.isNotBlank()) }
        }
    }

    @Test
    fun launch_ohne_aufloesbaren_kandidat_liefert_false_und_leitzeile() {
        // PackageManager gemockt: NICHTS löst auf (Exoten-Gerät) → kein Crash,
        // stattdessen Fallback-Text + false.
        val pm = mockk<PackageManager> {
            every { resolveActivity(any<Intent>(), any<Int>()) } returns null
        }
        val context = mockk<Context> {
            every { packageManager } returns pm
            every { packageName } returns appPackage
        }
        val activity = mockk<Activity>(relaxed = true)

        var hint: String? = null
        val started = DeviceIntentResolver.launchFeature(context, activity, DeviceFeature.AUTOSTART) { hint = it }

        assertFalse(started)
        assertEquals(
            DeviceIntentResolver.fallbackText(DeviceIntentResolver.currentProfile(), DeviceFeature.AUTOSTART),
            hint,
        )
    }

    @Test
    fun launch_ohne_activity_liefert_fallback_ohne_crash() {
        // Keine Activity (z. B. Preview/Kontext ohne Window) — trotzdem sauber.
        val context = mockk<Context> {
            every { packageManager } returns mockk<PackageManager> {
                every { resolveActivity(any<Intent>(), any<Int>()) } returns null
            }
            every { packageName } returns appPackage
        }
        var hint: String? = null
        assertFalse(DeviceIntentResolver.launchFeature(context, null, DeviceFeature.NOTIFICATION) { hint = it })
        assertTrue(hint!!.isNotBlank())
    }

    @Test
    fun profile_erkennung_aktuelles_geraet_laeuft_nie_auf_exception() {
        // Was auch immer der Hersteller-String ist: IMMER ein Profil, nie null.
        listOf("Zufallshersteller", "  ", "NOKIA", "Fairphone", "🤖").forEach {
            assertTrue(DeviceIntentResolver.currentProfile(it) in DeviceProfile.entries)
        }
        assertNotEquals(null, DeviceIntentResolver.currentProfile(null))
    }
}