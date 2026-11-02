package com.hermesandroid.bridge.crypto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.security.KeyFactory
import java.security.KeyFactorySpi
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * P1-Regression (vc36-Drill): KeyFactory.getInstance("XDH") OHNE Provider-
 * Pinning stirbt, sobald ein VOR dem funktionsfähigen XDH-Provider liegen-
 * der Provider KeyFactory.XDH anbietet und JEDE (oder nur die XEC-)Spec
 * ablehnt — auf Android die libcore-Delegate + AndroidKeyStoreKeyFactorySpi
 * (JDI-Beweis scratch/e2e/ph3-evidence/fund1-jdi-stack.txt: Conscrypt-Spi
 * wirft auf XECPrivateKeySpec → Delegate failovert PERMANENT auf AndroidKey-
 * Store → PKCS8-Fallback auf derselben Delegate wirft bei JEDEM Spec →
 * InvalidKeySpecException entkam → Handshake tot).
 *
 * Der Fix (CryptoEngine.xdhProviderName()-Probe + Pin) überspringt unge-
 * eignete Provider und instanziiert ÜBERALL provider-gepinnt. Diese Tests
 * bauen beide Android-Topologien mit Mimic-Providern auf der JVM nach:
 *
 * 1. reject-all-Provider an Position 1 (alte Code-Basis: InvalidKeySpec-
 *    Exception → rot; mit Pin: Probe überspringt ihn → grün).
 * 2. Conscrypt-Topologie (XEC nein / PKCS8+SPKI ja vor reject-all) —
 *    zwingt den XEC→PKCS8/SPKI-Fallback-Weg UNTER dem Pin her.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CryptoEngineProviderPinTest {

    private fun h(hex: String): ByteArray = CryptoEngine.fromHex(hex)
    private fun hex(bytes: ByteArray): String = CryptoEngine.hex(bytes)

    // RFC 7748 §6.1 (Alice/Bob) — identisch zu CryptoEngineFixtureTest.
    private val alicePriv =
        h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val alicePub =
        h("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val bobPriv =
        h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val bobPub =
        h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    private val rfcShared =
        "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

    @Before
    fun registerRejectAllProviderFirst() {
        Security.removeProvider(AKS_NAME)
        Security.insertProviderAt(AndroidKeyStoreMimic(), 1)
    }

    @After
    fun removePoisonProviders() {
        Security.removeProvider(AKS_NAME)
        Security.removeProvider(CONSCRYPT_MIMIC_NAME)
    }

    /**
     * P1-Kernfall: ein Provider, der JEDE KeySpec ablehnt (wie AndroidKeyStore
     * im App-Prozess), liegt an Position 1. Ohne Pin (alte Basis) greift
     * KeyFactory.getInstance("XDH") genau diesen Provider → XEC-Throw →
     * PKCS8-Fallback auf demselben Objekt wirft ebenfalls → InvalidKeySpec-
     * Exception. Mit Pin überspringt die Probe ihn und DH liefert das
     * RFC-7748-Ergebnis.
     */
    @Test
    fun test1_rejectAllProviderFirst_x25519DhStillComputesRfcVector() {
        CryptoEngine.reprobeXdhProviderForTest()
        assertEquals(rfcShared, hex(CryptoEngine.x25519Dh(alicePriv, bobPub)))
        assertEquals(rfcShared, hex(CryptoEngine.x25519Dh(bobPriv, alicePub)))
    }

    /**
     * Android-Topologie in echt: Conscrypt-artiger Provider an Position 1
     * (XEC-Specs abgelehnt, PKCS8/SPKI funktioniert), dahinter der reject-
     * all-Keystore. Die Probe pinnt den ERSTEN PKCS8-fähigen Provider (= den
     * Conscrypt-Mimic) — der XEC→PKCS8/SPKI-Fallback läuft damit zwingend
     * UNTER dem Pin (derselbe Weg wie am Gerät) und das Ergebnis bleibt das
     * RFC-7748-Ergebnis.
     */
    @Test
    fun test2_conscryptTopology_x25519DhExercisesPkcs8SkiFallbacksUnderPin() {
        Security.insertProviderAt(ConscryptMimic(), 1) // schiebt AKSMimic auf Position 2
        CryptoEngine.reprobeXdhProviderForTest()
        assertEquals(rfcShared, hex(CryptoEngine.x25519Dh(alicePriv, bobPub)))
        assertEquals(rfcShared, hex(CryptoEngine.x25519Dh(bobPriv, alicePub)))
    }

    // ── Mimic-Provider ───────────────────────────────────────────────────────

    /** Mimickt die AndroidKeyStoreKeyFactorySpi: lehnt JEDE KeySpec ab. */
    class AndroidKeyStoreMimic : Provider(AKS_NAME, 1.0, "P1-Regression: XDH reject-all") {
        init {
            put("KeyFactory.XDH", AksMimicSpi::class.java.name)
        }
    }

    class AksMimicSpi : KeyFactorySpi() {
        private fun reject(): Nothing = throw InvalidKeySpecException(
            "To generate a key pair in Android Keystore, use KeyPairGenerator " +
                "initialized with android.security.keystore.KeyGenParameterSpec")

        override fun engineGeneratePublic(keySpec: KeySpec): PublicKey = reject()

        override fun engineGeneratePrivate(keySpec: KeySpec): PrivateKey = reject()

        override fun <T : KeySpec> engineGetKeySpec(key: java.security.Key, keySpec: Class<T>): T =
            reject()

        override fun engineTranslateKey(key: java.security.Key): java.security.Key = reject()
    }

    /** Mimickt Conscrypt: XEC-Specs abgelehnt, PKCS8/SPKI an den echten JVM-Provider delegiert. */
    class ConscryptMimic : Provider(CONSCRYPT_MIMIC_NAME, 1.0, "P1-Regression: XEC nein, PKCS8/SPKI ja") {
        init {
            put("KeyFactory.XDH", ConscryptMimicSpi::class.java.name)
        }
    }

    class ConscryptMimicSpi : KeyFactorySpi() {
        private val real: KeyFactory by lazy {
            var found: KeyFactory? = null
            for (p in Security.getProviders()) {
                if (p.name == AKS_NAME || p.name == CONSCRYPT_MIMIC_NAME) continue
                val kf = runCatching { KeyFactory.getInstance("XDH", p) }.getOrNull() ?: continue
                found = kf
                break
            }
            checkNotNull(found) { "kein echter XDH-Provider in der Test-JVM" }
        }

        override fun engineGeneratePublic(keySpec: KeySpec): PublicKey =
            if (keySpec is X509EncodedKeySpec) real.generatePublic(keySpec)
            else throw InvalidKeySpecException("XECPublicKeySpec nicht unterstuetzt (Conscrypt-Mimic)")

        override fun engineGeneratePrivate(keySpec: KeySpec): PrivateKey =
            if (keySpec is PKCS8EncodedKeySpec) real.generatePrivate(keySpec)
            else throw InvalidKeySpecException("XECPrivateKeySpec nicht unterstuetzt (Conscrypt-Mimic)")

        override fun <T : KeySpec> engineGetKeySpec(key: java.security.Key, keySpec: Class<T>): T =
            real.getKeySpec(key, keySpec)

        override fun engineTranslateKey(key: java.security.Key): java.security.Key =
            real.translateKey(key)
    }

    companion object {
        private const val AKS_NAME = "AKSMimic"
        private const val CONSCRYPT_MIMIC_NAME = "AndroidOpenSSL"
    }
}