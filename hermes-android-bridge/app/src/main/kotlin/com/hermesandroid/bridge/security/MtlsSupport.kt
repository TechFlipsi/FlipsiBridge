package com.hermesandroid.bridge.security

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Client-certificate (mTLS) support (issue #108): offers a client certificate from the Android keystore during the TLS handshake when the server requests one.
 *
 * Der User installiert das P12-Client-Zertifikat einmalig in den Android-Keystore
 * (Einstellungen → Sicherheit → Zertifikate installieren → VPN & Apps). Der Alias
 * Only aliases with the prefix below are offered as client certificates;
 * angeboten. Der Server (NPMPlus ssl_verify_client) verlangt sie für relay.<domain>.
 *
 * Wichtig: Wer KEIN Zertifikat installiert hat, bekommt einen normalen Client ohne
 * mTLS — der Server weist die Verbindung dann ab (das ist der gewollte Effekt).
 */
object MtlsSupport {

    private const val KEY_ALIAS_PREFIX = "bridge-"

    /** Baut einen OkHttpClient mit Client-Zertifikats-Auth. */
    fun decorate(builder: OkHttpClient): OkHttpClient {
        val km = BridgeKeyManager()
        val tm = systemTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(km), arrayOf(tm), null)
        return builder.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, tm)
            .build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun androidKeystore(): KeyStore {
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        return ks
    }

    /**
     * KeyManager, der beim mTLS-Handshake the matching certificate from the
     * Android-System-Keystore anbietet (alias prefix "bridge-").
     */
    private class BridgeKeyManager : X509ExtendedKeyManager() {

        private var cachedAlias: String? = null

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? {
            cachedAlias?.let { return it }
            val aliases = androidKeystore().aliases()
            val match = aliases.toList().firstOrNull { it.startsWith(KEY_ALIAS_PREFIX) }
            cachedAlias = match
            return match
        }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            if (alias == null) return null
            return try {
                val chain = androidKeystore().getCertificateChain(alias) ?: return null
                @Suppress("UNCHECKED_CAST")
                chain as Array<X509Certificate>
            } catch (_: Exception) {
                null
            }
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            if (alias == null) return null
            return try {
                val entry = androidKeystore().getEntry(alias, null)
                (entry as? KeyStore.PrivateKeyEntry)?.privateKey
            } catch (_: Exception) {
                null
            }
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            chooseClientAlias(if (keyType != null) arrayOf(keyType) else null, null, null)?.let { arrayOf(it) }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}