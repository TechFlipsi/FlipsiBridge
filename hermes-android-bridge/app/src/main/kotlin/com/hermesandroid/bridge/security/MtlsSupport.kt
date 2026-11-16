package com.hermesandroid.bridge.security

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.security.auth.x500.X500Principal
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * FlipsiBridge mTLS v0.16.x (W16.3, Fixliste 41, Sir: „Zertifikat wählen …
 * passiert gar nichts“): der System-Dialog (KeyChain.choosePrivateKeyAlias)
 * war eine Sackgasse — ohne installiertes Client-Zertifikat schließt er
 * still. NEU: die App verwaltet Schlüssel + Zertifikat SELBST.
 *
 *  1. AndroidKeystore: EC-P-256-Device-Leg (non-exportable, bleibt im Gerät).
 *  2. CSR (PKCS#10, hand-kodierte DER — kein BouncyCastle-Dep) + Proof-of-
 *     Possession (Server-Nonce signiert).
 *  3. /mtls/enroll am Relay (code-gated, Einmaligkeit je Code-Epoch) stellt
 *     das Geräte-Zertifikat aus (Device-CA des Relays).
 *  4. decorate() legt das Zertifikat bei jedem TLS-Handshake vor — sobald der
 *     Server (nginx-Stufe 2) mTLS verlangt, ist die App bereit.
 *
 * Der KeyChain-Weg (User wählt ein systemweit installiertes Zertifikat)
 * bleibt als Fallback; app-verwaltete gehen VOR (kein Doppelflow).
 */
object MtlsSupport {
    private const val TAG = "MtlsSupport"
    private const val KEY_ALIAS_PREFIX = "flipsibridge-"
    private const val PREFS = "flipsibridge_mtls"
    private const val KEY_ALIAS = "client_alias"

    /** Alias des vom User gewählten KeyChain-Zertifikats (null = nicht gewählt). */
    @Volatile
    var selectedAlias: String? = null

    fun init(context: android.content.Context) {
        selectedAlias = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_ALIAS, null)
    }

    /** Systemdialog (Fallback): User wählt ein installiertes Client-Zertifikat. */
    fun chooseCertificate(activity: Activity) {
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias ->
                activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()?.putString(KEY_ALIAS, alias)?.apply()
                selectedAlias = alias
            },
            arrayOf("RSA", "EC"),
            null, null, null,
        )
    }

    /** KeyChain-Fallback konfiguriert? */
    fun isConfigured(): Boolean = !selectedAlias.isNullOrBlank()

    /** Baut einen OkHttpClient mit Client-Zertifikats-Auth (app-verwaltet VOR KeyChain). */
    fun decorate(builder: OkHttpClient): OkHttpClient {
        val km: X509ExtendedKeyManager =
            if (Enrollment.isDeviceCertConfigured()) {
                try {
                    Enrollment.deviceKeyManager()
                } catch (e: Exception) {
                    Log.w(TAG, "Device-KeyManager nicht baubar, KeyChain-Fallback", e)
                    val alias = selectedAlias ?: return builder
                    KeyChainKeyManager(alias)
                }
            } else {
                val alias = selectedAlias ?: return builder // kein mTLS konfiguriert
                KeyChainKeyManager(alias)
            }
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
        // v0.11.0 (Review): first() ohne Fallback hätte das OBJEKT beim ersten
        // decorate()-Aufruf mit ExceptionInInitializerError gekillt — graceful
        // ohne mTLS weitermachen statt crashen.
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("Kein X509TrustManager im System-Keystore gefunden")
    }

    /**
     * X509KeyManager, der Key + Kette via KeyChain liefert (Fallback-Pfad für
     * User-grantierte Client-Zertifikate). KeyChain-Aufrufe sind blockierend/
     * IPC: getPrivateKey wird von OkHttp auf Worker-Threads aufgerufen.
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

    /**
     * W16.3 Enrollment-Verband: App-verwaltetes Device-Zertifikat.
     * Schlüssel + Prefs + CSR + Nonce-PoP + Server-Ausstellung.
     */
    object Enrollment {
        private const val PREF_DEV_ID = "device_id"
        private const val PREF_DEV_CERT = "device_cert_pem"
        private const val PREF_DEV_CN = "device_cn"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val DEVICE_ALIAS = "flipsibridge-device"

        /** Enroll-Ergebnis für die UI (Snack + Statuszeile). */
        data class Result(
            val ok: Boolean,
            val cn: String? = null,
            val error: String? = null,
        )

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

        /** Stabile Device-ID (UUID, beim ersten Aufruf erzeugt). */
        @Synchronized
        fun deviceId(context: Context): String {
            val p = prefs(context)
            var id = p.getString(PREF_DEV_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                p.edit()?.putString(PREF_DEV_ID, id)?.apply()
            }
            return id
        }

        /** Anzeige-CN (null = kein Zertifikat installiert). */
        fun deviceCn(context: Context): String? = prefs(context).getString(PREF_DEV_CN, null)

        /** Gerätezertifikat installiert? */
        fun isDeviceCertConfigured(): Boolean =
            com.hermesandroid.bridge.BridgeApplication.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(PREF_DEV_CERT, null).isNullOrBlank().not()

        private fun ensureKeyPair(): java.security.KeyPair {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(DEVICE_ALIAS, null)
            if (existing is KeyStore.PrivateKeyEntry) {
                return java.security.KeyPair(existing.certificate.publicKey, existing.privateKey)
            }
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                DEVICE_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=flipsibridge-device"))
                .build()
            kpg.initialize(spec)
            val kp = kpg.generateKeyPair()
            Log.i(TAG, "Device-KeyPair erzeugt (EC, AndroidKeystore)")
            return kp
        }

        /** PKCS#10-CSR hand-codieren (DER, EC+SHA256, kein BouncyCastle-Dep). */
        private fun buildCsr(cn: String, keyPair: java.security.KeyPair): ByteArray {
            val spki = keyPair.public.encoded // X.509 SubjectPublicKeyInfo (DER)
            val cnSeq = Der.seq(Der.oid("2.5.4.3") + Der.utf8(cn))
            val name = Der.seq(cnSeq)
            // SignaturAlgorithmus: id-ecdsa-with-sha256 (1.2.840.10045.4.3.2)
            val alg = Der.seq(Der.oid("1.2.840.10045.4.3.2"))
            val tbs = Der.seq(
                Der.integer(0) + name + spki + Der.cnContext(0, Der.seq(ByteArray(0))),
            )
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(keyPair.private)
            signer.update(tbs)
            val sig = signer.sign()
            return Der.seq(tbs + alg + Der.bitString(sig))
        }

        /**
         * Komplett-Enrollment: Keygen → Nonce → PoP + CSR → cert_pem speichern.
         * Fehler als Result.error (kein Throw in die UI).
         */
        fun enrollDevice(context: Context, serverUrl: String, code: String): Result {
            val base = serverUrl.trim().trimEnd('/')
            val http = OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .readTimeout(java.time.Duration.ofSeconds(20))
                .build()
            val json = "application/json; charset=utf-8".toMediaType()
            val devId = deviceId(context)
            return try {
                // 1) Nonce holen
                val req = Request.Builder()
                    .url("$base/mtls/enroll/challenge")
                    .header("Authorization", "Bearer $code")
                    .post(("{\"device_id\":\"$devId\"}").toRequestBody(json))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return Result(false, error = "challenge:${'$'}${resp.code}")
                    val body = resp.body?.string() ?: return Result(false, error = "challenge:empty")
                    val nonce = try { org.json.JSONObject(body).optString("nonce") } catch (_: Exception) { "" }
                    if (nonce.isBlank()) return Result(false, error = "challenge:none")
                    // 2) PoP + CSR bauen
                    val kp = ensureKeyPair()
                    val signer = Signature.getInstance("SHA256withECDSA")
                    signer.initSign(kp.private)
                    signer.update(nonce.toByteArray(Charsets.UTF_8))
                    val popSig = signer.sign()
                    val csr = buildCsr("flipsibridge-$devId", kp)
                    // 3) Enrollment
                    val payload = org.json.JSONObject()
                        .put("device_id", devId)
                        .put("csr", Base64.getEncoder().encodeToString(csr))
                        .put("sig", Base64.getEncoder().encodeToString(popSig))
                        .put("nonce", nonce)
                    val req2 = Request.Builder()
                        .url("$base/mtls/enroll")
                        .header("Authorization", "Bearer $code")
                        .post(payload.toString().toRequestBody(json))
                        .build()
                    http.newCall(req2).execute().use { resp2 ->
                        if (!resp2.isSuccessful) {
                            val errBody = resp2.body?.string().orEmpty()
                            val detail = try {
                                org.json.JSONObject(errBody).optString("error")
                            } catch (_: Exception) { "" }
                            return Result(
                                ok = false,
                                error = "enroll:${'$'}${resp2.code}${if (detail.isNotBlank()) ":$detail" else ""}",
                            )
                        }
                        val data = try {
                            org.json.JSONObject(resp2.body?.string().orEmpty())
                        } catch (_: Exception) {
                            return Result(false, error = "enroll:parse")
                        }
                        val certPem = data.optString("cert_pem")
                        if (certPem.isBlank()) return Result(false, error = "enroll:nocert")
                        val cf = CertificateFactory.getInstance("X.509")
                        val cert = cf.generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate
                        val cn = cert.subjectX500Principal.getName()
                            .split(",").firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substring(3)
                            ?: "flipsibridge-device"
                        prefs(context).edit()
                            ?.putString(PREF_DEV_CERT, certPem)
                            ?.putString(PREF_DEV_CN, cn)
                            ?.apply()
                        Log.i(TAG, "Device-Zertifikat installiert: $cn")
                        Result(ok = true, cn = cn)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Enrollment fehlgeschlagen", e)
                Result(false, error = e.javaClass.simpleName + ": " + e.message.orEmpty().take(80))
            }
        }

        /** X509KeyManager über das app-eigene AndroidKeyStore-Device-Leg. */
        fun deviceKeyManager(): X509ExtendedKeyManager {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(DEVICE_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("Kein Device-Leg im Keystore")
            val key = entry.privateKey
            val chain: Array<X509Certificate> =
                entry.certificateChain?.mapNotNull { it as? X509Certificate }?.toTypedArray()
                    ?: arrayOf(entry.certificate as X509Certificate)
            val cnPref = prefs(com.hermesandroid.bridge.BridgeApplication.instance).getString(PREF_DEV_CN, "flipsibridge-device")
            return object : X509ExtendedKeyManager() {
                override fun chooseClientAlias(
                    keyType: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ): String = cnPref ?: DEVICE_ALIAS

                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                    arrayOf(cnPref ?: DEVICE_ALIAS)

                override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

                override fun getPrivateKey(alias: String?): PrivateKey = key

                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf()
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            }
        }
    }
}

/** Minimal-ASN.1/DER-Encoder für den hand-bauten CSR (kein BouncyCastle-Dep). */
private object Der {
    fun len(n: Int): ByteArray {
        var bytes: ByteArray
        if (n < 0x80) return byteArrayOf(n.toByte())
        val out = ByteArrayOutputStream()
        var v = n
        val stack = mutableListOf<Int>()
        while (v > 0) { stack.add(v and 0xFF); v = v shr 8 }
        out.write(0x80 or stack.size)
        for (i in stack.indices.reversed()) out.write(stack[i])
        bytes = out.toByteArray()
        return bytes
    }

    fun tag(t: Int, content: ByteArray): ByteArray = byteArrayOf(t.toByte()) + len(content.size) + content
    fun seq(content: ByteArray): ByteArray = tag(0x30, content)
    fun integer(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        if (v == 0L) {
            out.write(0)
        } else {
            var x = v
            var shift = 56
            while ((x ushr shift) == 0L) shift -= 8
            while (shift >= 0) { out.write(((x ushr shift)).toInt()); shift -= 8 }
        }
        var bytes = out.toByteArray()
        if (bytes.isNotEmpty() && bytes[0] < 0) bytes = byteArrayOf(0) + bytes // High-Bit → 0x00 davor
        return tag(0x02, bytes)
    }
    fun utf8(s: String): ByteArray = tag(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bitString(content: ByteArray): ByteArray = tag(0x03, byteArrayOf(0) + content)
    fun cnContext(n: Int, content: ByteArray): ByteArray = tag(0xA0 or n, content)

    /** OID als DER-EncodedBytes (Base-128-Komponenten). */
    fun oid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write(((parts[0] * 40 + parts[1])).toInt())
        for (part in parts.drop(2)) {
            var v = part
            val stack = mutableListOf<Int>()
            stack.add(((v and 0x7F)).toInt()); v = v shr 7
            while (v > 0) { stack.add((((v and 0x7F)) or 0x80).toInt()); v = v shr 7 }
            for (i in stack.indices.reversed()) out.write(stack[i])
        }
        return tag(0x06, out.toByteArray())
    }
}
