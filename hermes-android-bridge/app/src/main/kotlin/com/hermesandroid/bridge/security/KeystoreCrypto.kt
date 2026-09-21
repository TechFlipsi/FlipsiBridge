package com.hermesandroid.bridge.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * FlipsiBridge: AES-GCM-Verschlüsselung mit Android-Keystore (Hardware-Backed).
 *
 * Schützt sensible App-Einstellungen (Chat-Passwort) gegen Auslesen aus
 * App-Daten (ADB-Backup, Root-Leaks). Der Schlüssel verlässt die Secure
 * Hardware nie — ohne das Gerät kann der Chiffretext nicht entschlüsselt werden.
 */
object KeystoreCrypto {

    private const val KEY_ALIAS = "flipsibridge_cfg_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun decrypt(encoded: String): String? {
        return try {
            val data = Base64.decode(encoded, Base64.DEFAULT)
            if (data.size <= IV_LEN) return null
            val iv = data.copyOfRange(0, IV_LEN)
            val ct = data.copyOfRange(IV_LEN, data.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        gen.init(spec)
        return gen.generateKey()
    }
}