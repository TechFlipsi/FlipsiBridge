package com.hermesandroid.bridge.security

import android.content.Context
// Robolectric-eigener Application-Zugriff (androidx.test:core ist keine
// Dependency — vermeide den Zusatz und nutze RuntimeEnvironment):
import org.robolectric.RuntimeEnvironment
import com.hermesandroid.bridge.ui.components.CapabilityMaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Master-Schalter-Tests (v0.12.0): Snapshot/Restore-Identität + enableAll
 * mit/ohne invasive. CapabilityGate ist die SSOT (prefs) — Robolectric echte
 * SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class CapabilityMasterTest {

    private val allCaps = listOf(
        "screen", "screen_capture", "notifications", "clipboard", "files", "health",
        "interaction", "device", "notify_reply", "files_write", "selfupdate",
        "location", "sms", "calls", "contacts", "microphone",
    )
    private val invasive = setOf("location", "sms", "calls", "contacts", "microphone")

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        CapabilityGate.init(context)
        // sauberer Startzustand: alles aus
        allCaps.forEach { CapabilityGate.setEnabled(it, false) }
    }

    @Test
    fun enableAll_ohne_invasive_laesst_invasive_aus() {
        CapabilityMaster.enableAll(allCaps, includeInvasive = false, isInvasive = { it in invasive })
        val on = allCaps.filter { CapabilityGate.isEnabled(it) }
        assertEquals(11, on.size)
        assertTrue(invasive.none { CapabilityGate.isEnabled(it) })
    }

    @Test
    fun enableAll_mit_invasive_schaltet_alle_sechzehn() {
        CapabilityMaster.enableAll(allCaps, includeInvasive = true, isInvasive = { it in invasive })
        val on = allCaps.count { CapabilityGate.isEnabled(it) }
        assertEquals(16, on)
    }

    @Test
    fun disableAll_liefert_snapshot_und_undo_ist_identisch() {
        // Mischzustand bauen
        val before = allCaps.associateWith { (it.hashCode() % 3) != 0 }
        before.forEach { (id, enabled) -> CapabilityGate.setEnabled(id, enabled) }

        val snapshot = CapabilityMaster.disableAll(allCaps)
        assertEquals(before, snapshot)
        assertFalse(allCaps.any { CapabilityGate.isEnabled(it) })

        CapabilityMaster.restore(snapshot)
        val after = allCaps.associateWith { CapabilityGate.isEnabled(it) }
        assertEquals(before, after)
    }
}