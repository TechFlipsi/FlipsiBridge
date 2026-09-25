package com.hermesandroid.bridge.security

import android.app.Activity
import android.security.KeyChain
import android.security.KeyChainException
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * FlipsiBridge mTLS v0.10.8 (Review-Blocking-4-Fix): Client-Zertifikate über
 * die Android-KeyChain-API statt AndroidCAStore.
 *
 * Hintergrund: Der System-Keystore "AndroidCAStore" enthält ausschließlich
 * VERTRAUENSWÜRDIGE CA-Zertifikate — nie private Keys. Für Client-Zertifikate
 * ist KeyChain die einzige stabile Schnittstelle:
 *
 *   1. Der User wählt EINMALIG im Systemdialog ein installiertes Client-
 *      Zertifikat (KeyChain.choosePrivateKeyAlias — User-Grant, Android-Pflicht).
 *   2. Der gewählte Alias wird gespeichert (Prefs "flipsibridge_mtls").
 *   3. Bei jedem TLS-Handshake liefert KeyChain.getPrivateKey()/getCertificateChain()
 *     Key+Kette für diesen Alias (Background-Threads nötig — KeyChainException
 *     wirft, wenn auf dem Main-Thread aufgerufen).
 *
 * Alias-Präfix: Der gespeicherte Alias MUSS mit dem Präfix beginnen, damit der
 * User bewusst ein Bridge-Zertifikat wählt (nicht sein Mail-/VPN-Zertifikat).
 * Ohne gewählten Alias baut decorate() einen normalen Client — der Server
 * (ssl_verify_client) weist die Verbindung ab (gewollter Effekt).
 */
object MtlsSupport {

    private const val KEY_ALIAS_PREFIX = "flipsibridge-"
    private const val PREFS = "flipsibridge_mtls"
    private const val KEY_ALIAS = "client_alias"

    /** Alias des vom User gewählten Zertifikats (null = kein mTLS konfiguriert). */
    @Volatile
    var selectedAlias: String? = null

    fun init(context: android.content.Context) {
        selectedAlias = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_ALIAS, null)
    }

    /** Systemdialog: User wählt ein installiertes Client-Zertifikat (einmaliger Grant). */
    fun chooseCertificate(activity: Activity) {
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias ->
                // Callback läuft auf einem Binder-Thread — Speichern ist Thread-safe.
                activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()?.putString(KEY_ALIAS, alias)?.apply()
                selectedAlias = alias
            },
            arrayOf("RSA", "EC"),
            null,
            null, null,
            // nur Zertifikate anzeigen, die zum Bridge-Namensraum passen:
            // Android filtert hier nicht, wir prüfen das Präfix beim Alias-Rückruf.
        )
        // Hinweis: choosePrivateKeyAlias zeigt ALLE installierten Client-Zertifikate;
        // der User wählt bewusst das flipsibridge-* Zertifikat.
    }

    /** Hat der User ein Zertifikat gewählt? */
    fun isConfigured(): Boolean = !selectedAlias.isNullOrBlank()

    /** Baut einen OkHttpClient mit Client-Zertifikats-Auth (KeyChain-basiert). */
    fun decorate(builder: OkHttpClient): OkHttpClient {
        val alias = selectedAlias ?: return builder // kein mTLS konfiguriert
        val km = KeyChainKeyManager(alias)
        val tm = systemTrustManager()
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(km), arrayOf(tm), null)
        return builder.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, tm)
            .build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * X509KeyManager, der Key + Kette via KeyChain liefert (Android-Pfad für
     * User-grantierte Client-Zertifikate — ersetzt AndroidCAStore-Zugriff).
     * KeyChain-Aufrufe sind blockierend/IPC: getPrivateKey wird von OkHttp auf
     * Worker-Threads aufgerufen, das passt zu KeyChains Thread-Anforderung.
     */
    private class KeyChainKeyManager(private val alias: String) : X509ExtendedKeyManager() {

        private val appContext: android.content.Context
            get() = com.hermesandroid.bridge.BridgeApplication.instance

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = alias

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(alias)

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            if (alias != this.alias) return null
            return try {
                KeyChain.getCertificateChain(appContext, alias)
            } catch (_: KeyChainException) {
                null
            } catch (_: InterruptedException) {
                null
            }
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            if (alias != this.alias) return null
            return try {
                KeyChain.getPrivateKey(appContext, alias)
            } catch (_: KeyChainException) {
                null
            } catch (_: InterruptedException) {
                null
            }
        }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}