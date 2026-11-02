package com.hermesandroid.bridge.security

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * PH-2X X-1 — AppLockManager-Tests (JVM/Robolectric): Hash-Logik (PBKDF2 +
 * Keystore-Engine abstrahiert über LockCryptoEngine-Fake), Rate-Zähl-Logik
 * (5 Fehlversuche → progressiv 30/60/120 s, persistente Counter-Datei),
 * Auto-Sperre. Keystore selbst GEMOCKT (AndroidKeyStore ist auf der JVM
 * nicht vorhanden — genau deshalb ist die Engine injizierbar).
 */
@RunWith(RobolectricTestRunner::class)
class AppLockManagerTest {

    /** Deterministische Fake-Engine: seal = Marker-Byte anhängen, unseale invers. */
    private class FakeLockCryptoEngine : LockCryptoEngine {
        var broken = false
        override fun seal(plain: ByteArray): ByteArray? =
            if (broken) null else plain + byteArrayOf(0x5A)

        override fun unseal(blob: ByteArray): ByteArray? =
            if (broken || blob.isEmpty() || blob.last() != 0x5A.toByte()) null
            else blob.dropLast(1).toByteArray()
    }

    private lateinit var prefs: SharedPreferences
    private val engine = FakeLockCryptoEngine()
    private var now = 1_000_000L

    /** Frische Manager-Instanz auf GEMEINSAMEN prefs (Restart = neue Instanz). */
    private fun manager() = AppLockManager(prefs, engine, { now }, iterations = 1_000)

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("flipsibridge_applock_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        now = 1_000_000L
        engine.broken = false
    }

    // ── PIN-Format (4–6 Ziffern) ───────────────────────────────────────────

    @Test
    fun pin_format_akzeptiert_nur_4_bis_6_ziffern() {
        val m = manager()
        assertFalse(m.isValidPinFormat("123"))        // zu kurz
        assertFalse(m.isValidPinFormat("1234567"))    // zu lang
        assertFalse(m.isValidPinFormat("12a4"))       // keine Ziffer
        assertFalse(m.isValidPinFormat(""))          // leer
        assertTrue(m.isValidPinFormat("1234"))        // 4-stellig OK
        assertTrue(m.isValidPinFormat("12345"))       // 5-stellig OK
        assertTrue(m.isValidPinFormat("123456"))      // 6-stellig OK
        assertEquals(PinChangeResult.InvalidFormat, m.setPin("123"))
        assertEquals(PinChangeResult.InvalidFormat, m.setPin("12a4"))
        assertEquals(PinChangeResult.Ok, m.setPin("1234"))
        assertEquals(PinChangeResult.Ok, m.setPin("123456"))
        assertTrue(m.isPinSet())
    }

    @Test
    fun ohne_pin_ist_nie_eine_gesetzt_und_gate_bleibt_zu() {
        val m = manager()
        assertFalse(m.isPinSet())
        assertFalse(m.evaluateGate())
    }

    // ── Hash + Verifikation ────────────────────────────────────────────────

    @Test
    fun verify_unterscheidet_richtig_und_falsch() {
        val m = manager()
        m.setPin("482615")
        assertTrue(m.verifyPin("482615") is PinVerifyResult.Success)
        val m2 = manager() // neue Instanz = neue Session, Hash bleibt gleich
        assertTrue(m2.verifyPin("482615") is PinVerifyResult.Success)
        assertFalse(m2.verifyPin("482614") is PinVerifyResult.Success)
    }

    @Test
    fun hash_ist_gesalzen_zwei_gleiche_pins_unterschiedlicher_blob() {
        val m = manager()
        m.setPin("1234")
        val blob1 = prefs.getString("pin_hash_sealed", null)
        m.setPin("1234")
        val blob2 = prefs.getString("pin_hash_sealed", null)
        assertTrue(blob1 != null && blob2 != null)
        // Gleiche PIN, frischer Salt + frisches AES-Blob → nie identisch.
        assertTrue(blob1 != blob2)
    }

    // ── Rate-Limit + progressiv 30/60/120 s ───────────────────────────────

    @Test
    fun fuenf_fehlversuche_sperren_30_s_auch_fuer_die_richtige_pin() {
        val m = manager()
        m.setPin("1357")
        repeat(4) { assertTrue(m.verifyPin("0000") is PinVerifyResult.Failure) }
        val fifth = m.verifyPin("0000")
        assertTrue("5. Fehlversuch sperrt sofort, war aber: $fifth", fifth is PinVerifyResult.LockedOut)
        assertEquals(30L, (fifth as PinVerifyResult.LockedOut).remainingSeconds)
        // RICHTIGE PIN geht während der Sperre NICHT rein:
        assertTrue(m.verifyPin("1357") is PinVerifyResult.LockedOut)
        // Nach Ablauf: richtige PIN geht wieder.
        now += 30_001
        assertTrue(m.verifyPin("1357") is PinVerifyResult.Success)
    }

    @Test
    fun sperre_ist_progressiv_30_60_120_und_gedeckelt() {
        val m = manager()
        m.setPin("1357")
        fun lockCycle() {
            var result: PinVerifyResult = m.verifyPin("0000")
            var attempts = 1
            while (result !is PinVerifyResult.LockedOut) {
                result = m.verifyPin("0000")
                attempts++
            }
            assertTrue("Zuviele Versuche bis zur Sperre: $attempts", attempts <= 5)
            return@lockCycle
        }
        lockCycle()
        assertEquals(30L, m.lockoutRemainingSeconds())
        now += 30_001
        assertTrue(m.verifyPin("1357") is PinVerifyResult.Success) // Frist abgelaufen
        lockCycle()
        assertEquals(60L, m.lockoutRemainingSeconds())
        now += 60_001
        assertTrue(m.verifyPin("1357") is PinVerifyResult.Success)
        lockCycle()
        assertEquals(120L, m.lockoutRemainingSeconds())
        now += 120_001
        assertTrue(m.verifyPin("1357") is PinVerifyResult.Success)
        lockCycle()
        assertEquals("Deckel bleibt 120 s", 120L, m.lockoutRemainingSeconds())
        assertEquals("lockoutMillis-Doku: 30/60/120", listOf(30_000L, 60_000L, 120_000L, 120_000L), listOf(1, 2, 3, 4).map { m.lockoutMillis(it) })
    }

    @Test
    fun erfolg_resettet_den_fehlversuchszaehler() {
        val m = manager()
        m.setPin("1357")
        repeat(3) { m.verifyPin("0000") }
        assertTrue(m.verifyPin("1357") is PinVerifyResult.Success)
        // Nach Erfolg: wieder volle 5 Versuche bis zur Sperre.
        repeat(4) { assertTrue(m.verifyPin("0000") is PinVerifyResult.Failure) }
        assertTrue(m.verifyPin("0000") is PinVerifyResult.LockedOut)
    }

    @Test
    fun fehlversuchszaehler_ueberlebt_app_restart() {
        manager().setPin("1357")
        repeat(3) { manager().verifyPin("0000") }
        // „Restart“: neue Manager-Instanz auf denselben persistenten Prefs.
        val restarted = manager()
        assertTrue(restarted.isPinSet())
        repeat(1) { assertTrue(restarted.verifyPin("0000") is PinVerifyResult.Failure) }
        assertTrue(restarted.verifyPin("0000") is PinVerifyResult.LockedOut)
        assertEquals(30L, restarted.lockoutRemainingSeconds())
    }

    // ── Ändern/Löschen nur mit aktueller PIN ──────────────────────────────

    @Test
    fun change_pin_braucht_die_alte_pin() {
        val m = manager()
        m.setPin("1111")
        assertEquals(PinChangeResult.WrongCurrentPin, m.changePin("0000", "2222"))
        assertTrue(m.verifyPin("1111") is PinVerifyResult.Success)
        assertEquals(PinChangeResult.Ok, m.changePin("1111", "2222"))
        assertFalse(m.verifyPin("1111") is PinVerifyResult.Success)
        assertTrue(m.verifyPin("2222") is PinVerifyResult.Success)
    }

    @Test
    fun clear_pin_braucht_die_aktuelle_pin_und_raeumt_auf() {
        val m = manager()
        m.setPin("1111")
        m.setBiometricUnlockEnabled(true)
        assertFalse(m.clearPin("0000"))
        assertTrue(m.isPinSet())
        assertTrue(m.clearPin("1111"))
        assertFalse(m.isPinSet())
        assertFalse("Biometrie-Toggle stirbt mit der PIN", m.isBiometricUnlockEnabled())
        assertFalse(m.evaluateGate())
        // Nach dem Löschen KEINES Fehlversuch: NoPinSet statt Failure.
        assertTrue(m.verifyPin("1111") is PinVerifyResult.NoPinSet)
    }

    // ── Keystore-Fälle (fail-closed, kein Crash) ──────────────────────────

    @Test
    fun keystore_defekt_bei_setzen_fail_closed_ohne_speicher() {
        engine.broken = true
        val m = manager()
        assertEquals(PinChangeResult.KeystoreUnavailable, m.setPin("1234"))
        assertFalse("Ohne Keystore wird NICHTS (kein Klartext-Fallback) gespeichert", m.isPinSet())
    }

    @Test
    fun keystore_defekt_bei_verify_zieht_als_fehlversuch_und_verweigert() {
        val m = manager()
        m.setPin("1234")
        engine.broken = true // Keystore „verliert“ den Schlüssel
        assertTrue(m.verifyPin("1234") is PinVerifyResult.Failure)
        assertTrue(m.isLockedOut().not())
        repeat(4) { m.verifyPin("1234") }
        // 5. „Fehlversuch“ (alle fail-closed) sperrt — kein Crash, kein Einlass.
        assertTrue(m.verifyPin("1234") is PinVerifyResult.LockedOut)
    }

    // ── Auto-Sperre (30 s / 3 min / Nie) + Gate ────────────────────────────

    @Test
    fun autolock_default_ist_3_minuten_und_roundtrip_klappt() {
        val m = manager()
        assertEquals(AutoLockOption.AFTER_3_MINUTES, m.autoLockOption())
        m.setAutoLockOption(AutoLockOption.AFTER_30_SECONDS)
        assertEquals(AutoLockOption.AFTER_30_SECONDS, manager().autoLockOption())
        m.setAutoLockOption(AutoLockOption.NEVER)
        assertEquals(AutoLockOption.NEVER, manager().autoLockOption())
    }

    @Test
    fun autolock_greift_nach_frist_und_gate_schliesst() {
        val m = manager()
        m.setPin("1234")
        assertTrue("Frischer Prozess = Gate zu", m.evaluateGate())
        assertTrue(m.verifyPin("1234") is PinVerifyResult.Success)
        assertFalse(m.evaluateGate())
        m.onAppBackground(now)
        assertFalse("179,999 s < 3-min-Frist", m.evaluateGate(now + 179_999))
        assertTrue("Frist abgelaufen = gesperrt", m.evaluateGate(now + 180_000))
    }

    @Test
    fun autolock_30s_option_sperrt_schneller() {
        val m = manager()
        m.setPin("1234")
        m.setAutoLockOption(AutoLockOption.AFTER_30_SECONDS)
        assertTrue(m.verifyPin("1234") is PinVerifyResult.Success)
        m.onAppBackground(now)
        assertFalse(m.evaluateGate(now + 29_999))
        assertTrue(m.evaluateGate(now + 30_000))
    }

    @Test
    fun autolock_nie_sperrt_nie_per_zeitstempel() {
        val m = manager()
        m.setPin("1234")
        m.setAutoLockOption(AutoLockOption.NEVER)
        assertTrue(m.verifyPin("1234") is PinVerifyResult.Success)
        m.onAppBackground(now)
        assertFalse(m.evaluateGate(now + 86_400_000))
    }

    @Test
    fun app_restart_ohne_background_stempelt_sofort_gesetzt() {
        manager().setPin("1234")
        // Prozess-Neustart: unlocked=false → Gate sichtbar ohne jede Interaktion.
        assertTrue(manager().evaluateGate())
    }

    @Test
    fun evaluateGate_spiegeltGateVisible_im_selben_aufruf() {
        // PH-3 F5 (Jäger): AppLockGate ruft evaluateGate SYNCHRON in der
        // Komposition (kein LaunchedEffect — das lief erst nach dem ersten
        // Frame, content() hätte 1 Frame komponiert). Beweis: evaluateGate
        // muss gateVisible IM SELBEN Aufruf aktualisieren, damit die erste
        // Frame-Entscheidung auf frischem Stand läuft.
        val m = manager()
        m.setPin("1234")
        assertTrue(m.evaluateGate())
        assertTrue(
            "gateVisible muss nach evaluateGate synchron true sein (erster Frame)",
            m.gateVisible.value,
        )
        m.unlock()
        assertFalse("unlock synchron = Gate weg", m.gateVisible.value)
        // Auto-Sperre: Frist abgelaufen → evaluateGate sperrt UND spiegelt.
        m.onAppBackground(now)
        assertTrue(m.evaluateGate(now + 180_000))
        assertTrue("Frist-Re-Sperre ebenfalls synchron gespiegelt", m.gateVisible.value)
    }

    // ── Biometrie-Option (persistenter Schalter) ──────────────────────────

    @Test
    fun biometrie_schalter_ist_persistiert() {
        val m = manager()
        assertFalse(m.isBiometricUnlockEnabled())
        m.setPin("1234")
        m.setBiometricUnlockEnabled(true)
        assertTrue(manager().isBiometricUnlockEnabled())
        m.setBiometricUnlockEnabled(false)
        assertFalse(manager().isBiometricUnlockEnabled())
    }
}