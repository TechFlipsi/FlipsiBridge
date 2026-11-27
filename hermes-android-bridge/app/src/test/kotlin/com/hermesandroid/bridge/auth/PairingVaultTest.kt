package com.hermesandroid.bridge.auth

import android.content.Context
// Robolectric-eigener Application-Zugriff (androidx.test:core ist keine
// Dependency — vermeide den Zusatz und nutze RuntimeEnvironment):
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v0.11.0: Tests für die Pairing-Persistenz (Vorfall "Verbindung stirbt,
 * plötzlich ein neuer Kopplungscode nötig") — Robolectric, echte Dateien.
 *
 * Kernregeln, die diese Tests festzurren:
 *  1. Prefs geleert (HyperOS-Cleaner) + Vault vorhanden → RESTORE, gleicher Code.
 *  2. Prefs UND Vault leer → NEU-GENERIEREN (Sichtbar-Event REGENERATED).
 *  3. regenerateCode() aktualisiert prefs UND Vault (SSOT).
 */
@RunWith(RobolectricTestRunner::class)
class PairingVaultTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getDir("vault", Context.MODE_PRIVATE)
            .resolve("pairing_code.v1").delete()
    }

    @Test
    fun `prefs und vault leer → sichtbar neu generiert, zwölf zeichen`() {
        PairingManager.init(context)
        val code = PairingManager.getCode()
        assertEquals(12, code.length)
        assertEquals(PairingManager.CodeEvent.REGENERATED, PairingManager.getLastCodeEvent())
    }

    @Test
    fun `prefs geleert + vault vorhanden → wiederhergestellt, gleicher code`() {
        PairingManager.init(context)
        val erster = PairingManager.getCode()

        // simuliere HyperOS-Cleaner: prefs leeren, Vault bleibt
        context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        PairingManager.init(context)
        assertEquals(erster, PairingManager.getCode())
        assertEquals(PairingManager.CodeEvent.RESTORED, PairingManager.getLastCodeEvent())
    }

    @Test
    fun `regenerateCode aktualisiert prefs und vault`() {
        PairingManager.init(context)
        val alt = PairingManager.getCode()
        val neu = PairingManager.regenerateCode()
        assertNotEquals(alt, neu)
        assertEquals(neu, PairingManager.getCode())
        assertEquals(PairingManager.CodeEvent.REGENERATED, PairingManager.getLastCodeEvent())

        // auch nach komplettem Prefs-Verlust kommt der neue Code aus dem Vault
        context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        PairingManager.init(context)
        assertEquals(neu, PairingManager.getCode())
    }

    @Test
    fun `code ist aus verwechslungsfreiem alphabet`() {
        PairingManager.init(context)
        val code = PairingManager.getCode()
        assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        assertFalse(code.any { it in "0O1I" })
    }
}