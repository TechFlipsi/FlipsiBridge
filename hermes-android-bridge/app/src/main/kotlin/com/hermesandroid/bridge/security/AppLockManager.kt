package com.hermesandroid.bridge.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * PH-2X X-1 — APP-SPERRE: PIN-Hash + Rate-Limit + Auto-Sperre + Geräte-Sperre.
 *
 * DESIGN (Sir-Spec „App per PIN schützen wegen der Chat-Funktion“):
 *  - PIN 4–6 Ziffern; Hash = PBKDF2WithHmacSHA256 (JDK-Primitive, KEINE neue
 *    Library) mit JE Gerät frischem 16-Byte-Salt. BEWUSST dokumentiert:
 *    Salt + Iterationszahl liegen gemeinsam mit dem Hash im SharedPreferences
 *    („flipsibridge_applock“, MODE_PRIVATE) — der Hash selbst ist mit einem
 *    AES/GCM/NoPadding-Schlüssel im ANDROID-KEYSTORE VERSIEGelt, d. h. ein
 *    offline-angreifer ohne Keystore-Zugriff bekommt den Hash nicht zu sehen
 *    (der Keystore-Key ist nicht exportierbar).
 *  - Rate-Limit: 5 Fehlversuche → Sperre, PROGRESSIV 30 → 60 → 120 s (je
 *    Wiederholung eine Stufe, Deckel 120 s; die Eskalations-Stufe überlebt
 *    erfolgreiche Entsperren und fällt nur beim PIN-Setzen/Ändern/Löschen
 *    zurück). Zähler + Sperre liegen in der persistenten Counter-Datei
 *    (SharedPreferences „flipsibridge_applock“) → überlebt App-Restart.
 *  - Auto-Sperre: onPause-Zeitstempel; Optionen 30 s / 3 min / Nie (Default
 *    3 min). App-Restart sperrt IMMER (Prozess-Start = fresh un-unlocked).
 *  - „Biometrie“: BiometricPrompt wäre eine androidx-Bibliothek — Vorgabe ist
 *    aber KEINE neue Dependency. Deshalb der Plattform-Builtin:
 *    KeyguardManager.createConfirmDeviceCredentialIntent (entspricht
 *    BiometricPrompt mit setDeviceCredentialAllowed(true) — Geräte-PIN/Muster/
 *    Fingerabdruck). Feature-Check: hasKeyguardSecure (isKeyguardSecure) —
 *    ohne Geräte-Sperre erscheint der Knopf nicht, KEIN Crash ohne
 *    Biometrie-Hardware.
 *
 * KEYSTORE-FALL-DOKU (X-1-Beweispflicht):
 *  1. Normalfall: Keystore-Schlüssel da → Hash AES-GCM-versiegelt, alles gut.
 *  2. Keystore beim PIN-Setzen nicht verfügbar (seal → null): setPin liefert
 *     KeystoreUnavailable, es wird NICHTS gespeichert — FAIL-CLOSED, kein
 *     unverschlüsselter Fallback-Hash, kein Crash.
 *  3. Keystore beim Verifizieren kaputt (unseal → null): verifyPin zählt als
 *     FEHLVERSUCH und verweigert (deny) — Fail-closed, kein Crash. Der Nutzer
 *     kommt wieder rein, sobald der Keystore wieder geht (oder setzt die App
 *     per „Daten entfernen“ zurück — letzte Möglichkeit bei dauerhaft
 *     invalidiertem Keystore, bewusst in Kauf genommen statt Hintertür).
 *  4. Keystore-Key-Reset (z. B. Displaysperre entfernt) macht die versiegelten
 *     Blobs unlesbar → Fall 3 greift; die App bleibt stabil.
 *
 * TESTBARKEIT: Keystore + Uhr sind über den Konstruktor injizierbar
 * (LockCryptoEngine / clock) — die JVM-Tests nutzen eine Fake-Engine und eine
 * steuerbare Zeit; Produktionspfad geht über init(context)/get().
 */

/** Verschlüsselungs-Engine: AndroidKeyStore (Produktion) oder Fake (Tests). */
interface LockCryptoEngine {
    /** Versiegelt plain (AES/GCM/NoPadding). null = Keystore nicht verfügbar. */
    fun seal(plain: ByteArray): ByteArray?

    /** Öffnet ein versiegeltes Blob. null = Keystore kaputt/Blob unlesbar. */
    fun unseal(blob: ByteArray): ByteArray?
}

/**
 * Produktions-Engine: AES-256-GCM-Schlüssel im AndroidKeyStore (Android-
 * Builtin, nicht exportierbar). Blob-Format = [12-Byte-IV][Cipher+GCM-Tag].
 */
class AndroidKeystoreEngine : LockCryptoEngine {

    private fun keyOrNull(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
    }.getOrNull()

    override fun seal(plain: ByteArray): ByteArray? = runCatching {
        val key = keyOrNull() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.iv + cipher.doFinal(plain)
    }.getOrNull()

    override fun unseal(blob: ByteArray): ByteArray? = runCatching {
        if (blob.size <= GCM_IV_BYTES) return null
        val key = keyOrNull() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
        cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }.getOrNull()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "flipsibridge_applock_wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

/** Auto-Sperr-Optionen (Settings, Gruppe 4). Default: 3 Minuten. */
enum class AutoLockOption(val prefValue: String, val label: String, val millis: Long) {
    AFTER_30_SECONDS("30s", "Nach 30 Sekunden", 30_000L),
    AFTER_3_MINUTES("3m", "Nach 3 Minuten", 180_000L),
    NEVER("never", "Nie", Long.MAX_VALUE),
}

/** Ergebnis eines PIN-Prüfungs-/Setzungs-Vorgangs für die UI-Texte. */
sealed class PinVerifyResult {
    data object Success : PinVerifyResult()
    data object NoPinSet : PinVerifyResult()
    data class Failure(val remainingAttempts: Int) : PinVerifyResult()
    data class LockedOut(val remainingSeconds: Long) : PinVerifyResult()
}

sealed class PinChangeResult {
    data object Ok : PinChangeResult()
    data object InvalidFormat : PinChangeResult()
    data object WrongCurrentPin : PinChangeResult()
    data object KeystoreUnavailable : PinChangeResult()
    data class LockedOut(val remainingSeconds: Long) : PinChangeResult()
}

/**
 * Manager der App-Sperre. Produktionszugriff: [init] + [get]; Tests injizieren
 * prefs/engine/clock/iterations direkt über den Konstruktor.
 */
class AppLockManager internal constructor(
    private val prefs: SharedPreferences,
    private val crypto: LockCryptoEngine,
    private val clock: () -> Long,
    private val iterations: Int = PBKDF2_ITERATIONS,
) {

    // ── PIN-Verwaltung ─────────────────────────────────────────────────────

    /** 4–6 Ziffern (nur 0–9) — die komplette Format-Regel der X-1-Spec. */
    fun isValidPinFormat(pin: String): Boolean =
        pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it in '0'..'9' }

    /** PIN gesetzt (versiegelter Hash + Salt vorhanden)? */
    fun isPinSet(): Boolean = prefs.contains(KEY_HASH_SEALED) && prefs.contains(KEY_SALT)

    /** Setzt eine NEUE PIN (Salt frisch, alter Hash ersetzt, Zähler reset). */
    fun setPin(pin: String): PinChangeResult {
        if (!isValidPinFormat(pin)) return PinChangeResult.InvalidFormat
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derivePinHash(pin, salt, iterations) ?: return PinChangeResult.KeystoreUnavailable
        val sealed = crypto.seal(hash) ?: return PinChangeResult.KeystoreUnavailable
        prefs.edit()
            .putString(KEY_HASH_SEALED, Base64.getEncoder().encodeToString(sealed))
            .putString(KEY_SALT, Base64.getEncoder().encodeToString(salt))
            .putInt(KEY_ITERATIONS, iterations)
            .putInt(KEY_FAIL_COUNT, 0)
            .putInt(KEY_LOCKOUT_LEVEL, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
        return PinChangeResult.Ok
    }

    /** Ändert die PIN — NUR mit korrekter aktueller PIN (X-1-Spec: Change). */
    fun changePin(currentPin: String, newPin: String, now: Long = clock()): PinChangeResult {
        if (!isValidPinFormat(newPin)) return PinChangeResult.InvalidFormat
        return when (val r = verifyPin(currentPin, now)) {
            is PinVerifyResult.Success -> setPin(newPin)
            is PinVerifyResult.LockedOut -> PinChangeResult.LockedOut(r.remainingSeconds)
            else -> PinChangeResult.WrongCurrentPin
        }
    }

    /** Löscht die Sperre — NUR mit korrekter aktueller PIN (X-1: Delete). */
    fun clearPin(currentPin: String, now: Long = clock()): Boolean {
        if (verifyPin(currentPin, now) !is PinVerifyResult.Success) return false
        prefs.edit()
            .remove(KEY_HASH_SEALED)
            .remove(KEY_SALT)
            .remove(KEY_ITERATIONS)
            .putInt(KEY_FAIL_COUNT, 0)
            .putInt(KEY_LOCKOUT_LEVEL, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .putBoolean(KEY_BIOMETRIC, false)
            .apply()
        unlocked = false
        _gateVisible.value = false
        return true
    }

    // ── Verifikation + Rate-Limit ──────────────────────────────────────────

    /**
     * Prüft eine PIN. Gesperrt → LockedOut (auch für die RICHTIGE PIN).
     * Falsch → Failure mit Restversuchen; ab dem 5. Fehlversuch sperrt der
     * Manager progressiv (30/60/120 s, siehe [lockoutMillis]).
     */
    fun verifyPin(pin: String, now: Long = clock()): PinVerifyResult {
        if (!isPinSet()) return PinVerifyResult.NoPinSet
        lockoutUntil()?.let { until ->
            if (now < until) return PinVerifyResult.LockedOut(remainingSeconds(until, now))
        }
        val storedHash = storedHash()
        val salt = storedSalt()
        if (storedHash == null || salt == null || !isValidPinFormat(pin)) {
            // Fail-closed: kaputtes Keystore/Blob oder Müll-Eingabe = Fehlversuch.
            val locked = registerFailure(now)
            return if (locked) {
                PinVerifyResult.LockedOut(lockoutRemainingSeconds(now))
            } else {
                PinVerifyResult.Failure(remainingAttempts())
            }
        }
        val usedIterations = prefs.getInt(KEY_ITERATIONS, PBKDF2_ITERATIONS)
        val candidate = derivePinHash(pin, salt, usedIterations)
        if (candidate == null || !MessageDigest.isEqual(candidate, storedHash)) {
            val locked = registerFailure(now)
            return if (locked) {
                PinVerifyResult.LockedOut(lockoutRemainingSeconds(now))
            } else {
                PinVerifyResult.Failure(remainingAttempts())
            }
        }
        resetFailCount()
        unlock()
        return PinVerifyResult.Success
    }

    /** Restversuche bis zur Sperre (für die Fehler-Zeile des Lock-Screens). */
    fun remainingAttempts(): Int = (MAX_FAILED_ATTEMPTS - prefs.getInt(KEY_FAIL_COUNT, 0)).coerceAtLeast(0)

    fun isLockedOut(now: Long = clock()): Boolean =
        lockoutUntil()?.let { now < it } == true

    fun lockoutRemainingSeconds(now: Long = clock()): Long =
        lockoutUntil()?.let { remainingSeconds(it, now) } ?: 0L

    /** Progressiv 30 → 60 → 120 s (Deckel: 120 s, weitere Wiederholungen konstant). */
    fun lockoutMillis(level: Int): Long = 30_000L * (1L shl (level - 1).coerceIn(0, 2))

    /** Zählt den Fehlversuch; liefert true, wenn DIESER Versuch die Sperre auslöst. */
    private fun registerFailure(now: Long): Boolean {
        val count = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        return if (count >= MAX_FAILED_ATTEMPTS) {
            val level = prefs.getInt(KEY_LOCKOUT_LEVEL, 0) + 1
            prefs.edit()
                .putInt(KEY_LOCKOUT_LEVEL, level)
                .putLong(KEY_LOCKOUT_UNTIL, now + lockoutMillis(level))
                .putInt(KEY_FAIL_COUNT, 0)
                .apply()
            true
        } else {
            prefs.edit().putInt(KEY_FAIL_COUNT, count).apply()
            false
        }
    }

    /**
     * Erfolg (korrekte PIN): Fehlversuchs-Zähler zurücksetzen — die
     * ESKALATIONS-STUFE (30/60/120 s) bleibt bewusst stehen, damit wieder-
     * holte Rät-Angriffe je Runde länger sperren; sie fällt erst bei
     * PIN-Setzen/Ändern/Löschen zurück auf 0.
     */
    private fun resetFailCount() {
        prefs.edit()
            .putInt(KEY_FAIL_COUNT, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    private fun lockoutUntil(): Long? = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L).takeIf { it > 0 }

    private fun remainingSeconds(until: Long, now: Long): Long = ((until - now + 999) / 1000).coerceAtLeast(0)

    // ── Gate-Zustand (Auto-Sperre) ─────────────────────────────────────────

    private val _gateVisible = MutableStateFlow(false)

    /** Compose (AppLockGate) sammelt diesen Flow; true = Lock-Screen zeigen. */
    val gateVisible: StateFlow<Boolean> = _gateVisible

    @Volatile
    private var unlocked = false

    @Volatile
    private var backgroundSince: Long? = null

    /** onPause: Zeitstempel für die Auto-Sperr-Frist (30 s / 3 min / Nie). */
    fun onAppBackground(now: Long = clock()) {
        backgroundSince = now
    }

    /** onResume: Gate neu bewerten — sperrt, wenn die Frist abgelaufen ist. */
    fun onAppForeground(now: Long = clock()): Boolean = evaluateGate(now)

    /**
     * Kern-Berechnung: Ohne PIN nie sichtbar. Entsperrt bleibt entsperrt, bis
     * die Auto-Sperr-Frist abgelaufen ist (Nie-Option sperrt nie). Prozess-
     * Start = unlocked=false → Gate zeigt sofort (App-Restart sperrt immer).
     */
    fun evaluateGate(now: Long = clock()): Boolean {
        if (!isPinSet()) {
            _gateVisible.value = false
            return false
        }
        val bg = backgroundSince
        if (unlocked && bg != null) {
            val option = autoLockOption()
            if (option != AutoLockOption.NEVER && now - bg >= option.millis) unlocked = false
        }
        // JÄGER-J7 (P2, JAEGE-RFULL-20261102): Kurzer Hintergrund (onPause →
        // onResume INNERHALB der Frist) ließ backgroundSince am ALTEN Wert
        // stehen — ist die App danach Minuten offen in aktiver Nutzung,
        // sperrte jede Gate-Rekomposition (Rotation/Activity-Recreate) die
        // App MITTEN in der Bedienung. Hintergrund-Zeitraum ist mit dem
        // Resume ABGESCHLOSSEN: nicht gesperrt = Zeitstempel löschen (nur
        // unlock() räumte bisher).
        if (unlocked) backgroundSince = null
        val visible = !unlocked
        _gateVisible.value = visible
        return visible
    }

    /** Entsperrt (PIN korrekt oder Geräte-Sperre bestätigt). */
    fun unlock() {
        unlocked = true
        backgroundSince = null
        _gateVisible.value = false
    }

    // ── Auto-Sperre / Biometrie-Optionen (Settings, Gruppe 4) ─────────────

    fun autoLockOption(): AutoLockOption = when (prefs.getString(KEY_AUTOLOCK, null)) {
        AutoLockOption.AFTER_30_SECONDS.prefValue -> AutoLockOption.AFTER_30_SECONDS
        AutoLockOption.NEVER.prefValue -> AutoLockOption.NEVER
        else -> AutoLockOption.AFTER_3_MINUTES // Default
    }

    fun setAutoLockOption(option: AutoLockOption) {
        prefs.edit().putString(KEY_AUTOLOCK, option.prefValue).apply()
    }

    fun isBiometricUnlockEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    /** Feature-Check (hasKeyguardSecure): Geräte-Sperre eingerichtet? */
    fun isDeviceCredentialAvailable(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardSecure
    }.getOrDefault(false)

    /**
     * Plattform-Builtin statt androidx.biometric (KEINE neue Dependency):
     * KeyguardManager.createConfirmDeviceCredentialIntent =
     * BiometricPrompt-Äquivalent mit setDeviceCredentialAllowed(true).
     */
    @Suppress("DEPRECATION")
    fun deviceCredentialIntent(context: Context): Intent? = runCatching {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.createConfirmDeviceCredentialIntent("FlipsiBridge", "Geräte-Sperre bestätigen")
    }.getOrNull()

    // ── Internes ───────────────────────────────────────────────────────────

    private fun storedHash(): ByteArray? =
        prefs.getString(KEY_HASH_SEALED, null)?.let { crypted ->
            runCatching { crypto.unseal(Base64.getDecoder().decode(crypted)) }.getOrNull()
        }

    private fun storedSalt(): ByteArray? =
        prefs.getString(KEY_SALT, null)?.let { b64 ->
            runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
        }

    private fun derivePinHash(pin: String, salt: ByteArray, iterationsUsed: Int): ByteArray? =
        runCatching {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterationsUsed, 256)
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        }.getOrNull()

    companion object {
        private const val PREFS_NAME = "flipsibridge_applock"
        private const val KEY_HASH_SEALED = "pin_hash_sealed"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_FAIL_COUNT = "fail_count"
        private const val KEY_LOCKOUT_LEVEL = "lockout_level"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_BIOMETRIC = "biometric_unlock"
        private const val KEY_AUTOLOCK = "autolock_mode"
        private const val SALT_BYTES = 16

        /** Pin-Regel der X-1-Spec. */
        const val PIN_MIN_LENGTH = 4
        const val PIN_MAX_LENGTH = 6

        /** Rate-Limit der X-1-Spec: 5 Fehlversuche → Sperre. */
        const val MAX_FAILED_ATTEMPTS = 5

        /**
         * PBKDF2-Iterationen (bewusst dokumentiert, siehe Klassen-Doku):
         * hoher Standardwert für den Geräte-Fall; Tests injizieren kleinere
         * Werte über den Konstruktor, der GENUTZTE Wert wird mit dem Hash
         * gespeichert (lesbar für spätere Erhöhungen ohne Re-Set der PIN).
         */
        const val PBKDF2_ITERATIONS = 130_000

        @Volatile
        private var defaultInstance: AppLockManager? = null

        /** Produktions-Factory (idempotent): Prefs + AndroidKeyStore-Engine. */
        fun init(context: Context): AppLockManager {
            defaultInstance?.let { return it }
            synchronized(this) {
                if (defaultInstance == null) {
                    defaultInstance = AppLockManager(
                        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                        crypto = AndroidKeystoreEngine(),
                        clock = { System.currentTimeMillis() },
                    )
                }
                return defaultInstance!!
            }
        }

        fun get(): AppLockManager =
            defaultInstance ?: throw IllegalStateException(
                "AppLockManager.init(context) muss vor dem ersten Zugriff laufen (MainActivity.onCreate).",
            )
    }
}