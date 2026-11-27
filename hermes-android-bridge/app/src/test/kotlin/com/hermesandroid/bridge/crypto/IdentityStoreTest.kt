package com.hermesandroid.bridge.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PH-3 Welle 2 — IdentityStore (Keystore-sealed, fail-closed): Persistenz,
 * Fingerprint-Stabilität, Korruptionsfälle = Exception OHNE stillen Re-Key,
 * Klartext-Verbot im Persistenz-Speicher. Sealer/Store als Fakes injiziert
 * (gleiche Pfade wie die AndroidKeyStore-Produktions-Engine).
 */
class IdentityStoreTest {

    /** Simple Fake-Versiegelung (XOR 0x5a + Präfixbyte) — sealed ≠ plain. */
    private class FakeSealer(var broken: Boolean = false) : IdentitySealer {
        override fun seal(plain: ByteArray): ByteArray? {
            if (broken) return null
            return byteArrayOf(0x7f.toByte()) + plain.map { (it.toInt() xor 0x5a).toByte() }
        }

        override fun unseal(blob: ByteArray): ByteArray? {
            if (broken || blob.isEmpty()) return null
            return blob.copyOfRange(1, blob.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    /** In-Memory-KV-Store (Produktion: SharedPreferences). */
    private class MapStore : IdentityKeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun createPersistsIdentityAndFingerprintStable() {
        val store = MapStore()
        val identity = IdentityStore(store, FakeSealer()).loadOrCreate()
        val second = IdentityStore(store, FakeSealer()).loadOrCreate()
        assertEquals("gleiche Identity beim 2. Laden (kein stiller Re-Key)", identity.ikPrivHex, second.ikPrivHex)
        assertEquals(identity.spkPrivHex, second.spkPrivHex)
        assertEquals("Fingerprint stabil", identity.fingerprintHex, second.fingerprintHex)
        assertEquals(
            "Fingerprint = agent_fp(IK_pub)",
            CryptoEngine.fingerprintOfPub(CryptoEngine.fromHex(identity.ikPubHex)),
            identity.fingerprintHex,
        )
        // Klartext-Verbot: persistiertes Blob ist versiegelt (kein Key im Klartext).
        val blob = store.map.values.single()
        assertTrue(blob.isNotEmpty())
        assertNotEquals(identity.ikPrivHex, blob)
        assertNotEquals(identity.spkPrivHex, blob)
    }

    @Test
    fun unsealFailureFailsClosedWithoutRekey() {
        val store = MapStore()
        val sealer = FakeSealer()
        val original = IdentityStore(store, sealer).loadOrCreate()
        val originalBlob = store.map.values.single()
        // Keystore zurückgesetzt → unseal schlägt fehl → Exception, KEIN neuer Key.
        sealer.broken = true
        try {
            IdentityStore(store, sealer).loadOrCreate()
            fail("Unseal-Fehler muss fail-closed werfen")
        } catch (expected: IdentityStoreException) {
        }
        assertEquals("kein stiller Re-Key — Blob unverändert", originalBlob, store.map.values.single())
        // Reparatur: gleiche Keys kommen zurück (Fingerprint bleibt stabil).
        sealer.broken = false
        assertEquals(original.ikPrivHex, IdentityStore(store, sealer).loadOrCreate().ikPrivHex)
    }

    @Test
    fun sealFailureStoresNothing() {
        val store = MapStore()
        try {
            IdentityStore(store, FakeSealer(broken = true)).loadOrCreate()
            fail("Seal-Fehler muss fail-closed werfen")
        } catch (expected: IdentityStoreException) {
        }
        assertTrue("NICHTS gespeichert (kein Klartext-Fallback)", store.map.isEmpty())
    }

    @Test
    fun corruptBase64BlobFailsClosed() {
        val store = MapStore()
        store.put("identity_sealed", "!!!kein-base64!!!")
        try {
            IdentityStore(store, FakeSealer()).loadOrCreate()
            fail("Korruptes Blob muss fail-closed werfen")
        } catch (expected: IdentityStoreException) {
        }
    }

    @Test
    fun sealedGarbageJsonFailsClosed() {
        val sealer = FakeSealer()
        val store = MapStore()
        val garbagePlain = "kein json".toByteArray(Charsets.UTF_8)
        store.put("identity_sealed", java.util.Base64.getEncoder().encodeToString(sealer.seal(garbagePlain)!!))
        try {
            IdentityStore(store, sealer).loadOrCreate()
            fail("Ungültiges Identity-JSON muss fail-closed werfen")
        } catch (expected: IdentityStoreException) {
        }
    }
}