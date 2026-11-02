package com.hermesandroid.bridge.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.JsonObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * PH-3 Welle 2 — Identity-Speicher des Phones (X25519 Identity + PreKey).
 *
 * Klartext-Verbot + fail-closed (PH3-CRYPTO-SPEC T5, Muster AppLockManager):
 * - Private Keys NUR versiegelt im AndroidKeyStore (AES-256-GCM-Wrap-Schlüssel
 *   "flipsibridge_identity_wrap", nicht exportierbar) → Persistent-Speicher
 *   (SharedPreferences) enthält ausschließlich Base64(IV‖ct‖Tag).
 * - Versiegeln beim Erzeugen fehlgeschlagen → [IdentityStoreException], NICHTS
 *   wird gespeichert, kein Klartext-Fallback.
 * - Öffnen einer vorhandenen Identity fehlgeschlagen (Keystore-Reset, korruptes
 *   Blob) → [IdentityStoreException], KEIN stiller Re-Key (der Fingerprint
 *   bleibt stabil oder der Fehler ist sichtbar — nie unbemerkt neu).
 *
 * JVM-Tests: Store + Sealer injizierbar (Fake-Implementierungen, gleiche
 * Fail-closed-Pfade wie Produktion).
 */
class IdentityStoreException(message: String) : RuntimeException(message)

/** Minimaler String-KV-Speicher (Produktion: SharedPreferences). */
interface IdentityKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** SharedPreferences-Adapter (Produktion). */
class PrefsIdentityStore(context: Context) : IdentityKeyValueStore {
    private val prefs = context.getSharedPreferences("flipsibridge_crypto", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/** Versiegelungs-Engine: AndroidKeyStore (Produktion) oder Fake (Tests). */
interface IdentitySealer {
    /** Versiegelt plain. null = Keystore nicht verfügbar. */
    fun seal(plain: ByteArray): ByteArray?

    /** Öffnet ein versiegeltes Blob. null = Keystore kaputt/Blob unlesbar. */
    fun unseal(blob: ByteArray): ByteArray?
}

/** Produktion: AES-256-GCM-Schlüssel im AndroidKeyStore, Blob = [12-Byte-IV][ct‖Tag]. */
class AndroidKeystoreIdentitySealer : IdentitySealer {

    private fun keyOrNull(): javax.crypto.SecretKey? = runCatching {
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
        private const val KEY_ALIAS = "flipsibridge_identity_wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

/**
 * Identity-Verwaltung: laden oder erzeugen (X25519 Identity + PreKey).
 * Ablageformat = versiegeltes JSON {v, identity_priv, identity_pub, prekey_priv,
 * prekey_pub, created} — Pubs passen zu Priv-Keys per Erzeugung; ein Bestands-
 * Mismatch ist nach dem GCM-Integritätsschutz nicht mehr erreichbar, ein
 * manipuliertes Blob scheitert am Tag. Private Keys werden NIE geloggt.
 */
class IdentityStore(
    private val store: IdentityKeyValueStore,
    private val sealer: IdentitySealer,
) {

    data class Identity(
        val ikPrivHex: String,
        val ikPubHex: String,
        val spkPrivHex: String,
        val spkPubHex: String,
    ) {
        /** Identity-Fingerprint (öffentliche Info, Registry-kompatibel). */
        val fingerprintHex: String =
            CryptoEngine.fingerprintOfPub(CryptoEngine.fromHex(ikPubHex))
    }

    /**
     * Lädt die Identity oder erzeugt sie EINMAL (persistent, versiegelt).
     * Fail-closed: existierendes, unlesbares/ungültiges Blob → Exception,
     * KEIN stiller Neuschlüssel.
     */
    @Synchronized
    fun loadOrCreate(nowMs: Long = System.currentTimeMillis()): Identity {
        val existing = store.get(KEY_BLOB)
        if (existing != null) {
            return loadExisting(existing)
        }
        val ik = CryptoEngine.x25519Generate()
        val spk = CryptoEngine.x25519Generate()
        val json = JsonObject().apply {
            addProperty("v", 1)
            addProperty("identity_priv", CryptoEngine.hex(ik.priv))
            addProperty("identity_pub", CryptoEngine.hex(ik.pub))
            addProperty("prekey_priv", CryptoEngine.hex(spk.priv))
            addProperty("prekey_pub", CryptoEngine.hex(spk.pub))
            addProperty("created", java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT
            ).format(java.util.Date(nowMs)))
        }
        val sealed = sealer.seal(json.toString().toByteArray(Charsets.UTF_8))
            ?: throw IdentityStoreException(
                "Identity-Versiegelung fehlgeschlagen (Keystore nicht verfügbar) — nichts gespeichert")
        store.put(KEY_BLOB, Base64.getEncoder().encodeToString(sealed))
        return Identity(
            ikPrivHex = CryptoEngine.hex(ik.priv),
            ikPubHex = CryptoEngine.hex(ik.pub),
            spkPrivHex = CryptoEngine.hex(spk.priv),
            spkPubHex = CryptoEngine.hex(spk.pub),
        )
    }

    private fun loadExisting(blobB64: String): Identity {
        val sealed = try {
            Base64.getDecoder().decode(blobB64)
        } catch (e: IllegalArgumentException) {
            throw IdentityStoreException("Identity-Blob unlesbar (Base64)")
        }
        val plain = sealer.unseal(sealed)
            ?: throw IdentityStoreException(
                "Identity-Versiegelung nicht öffnenbar (Keystore zurückgesetzt/Blob korrupt) — " +
                    "App-Daten zurücksetzen ist die letzte Rettung, kein stiller Re-Key")
        val json = try {
            val parsed = com.google.gson.JsonParser.parseString(String(plain, Charsets.UTF_8))
            if (!parsed.isJsonObject) throw IdentityStoreException("Identity-JSON unlesbar")
            parsed.asJsonObject
        } catch (e: IdentityStoreException) {
            throw e
        } catch (e: Exception) {
            throw IdentityStoreException("Identity-JSON unlesbar")
        }
        val fields = listOf("identity_priv", "identity_pub", "prekey_priv", "prekey_pub")
        if (!fields.all { f ->
                val v = json.get(f)
                v != null && v.isJsonPrimitive && v.asString.matches(Regex("^[0-9a-f]{64}$"))
            }
        ) {
            throw IdentityStoreException("Identity-Blob ungültige Felder")
        }
        return Identity(
            ikPrivHex = json.get("identity_priv").asString,
            ikPubHex = json.get("identity_pub").asString,
            spkPrivHex = json.get("prekey_priv").asString,
            spkPubHex = json.get("prekey_pub").asString,
        )
    }

    companion object {
        private const val KEY_BLOB = "identity_sealed"
    }
}