package com.hermesandroid.bridge.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.client.PinSource
import com.hermesandroid.bridge.server.ServerProfileRules
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * FlipsiBridge APK-Selbstupdate (Phase 4, v0.10.8 hartening).
 *
 * Update-Quelle ist FIX: das konfigurierte Relay (RelayClient-Server-URL),
 * Pfad /apk/latest. Der Agent kann NUR den Update-Check anstoßen — er kann
 * KEINE URL mehr vorgeben. Der Pairing-Token wird ausschließlich an diese
 * eine, vom Nutzer konfigurierte Gegenstelle gesendet (wie beim WS-Handshake).
 *
 * Ablauf:
 *  1. App fragt <relay>/apk/latest mit Bearer<Pairing-Code> ab (dieselbe
 *     Authentifizierung wie jede andere geroutete Verbindung — der Token
 *     verlässt das Gerät ausschließlich Richtung eigener Relay-Host).
 *  2. Server liefert APK + X-APK-SHA256-Header (Hex, vom Server gesetzt,
 *     NICHT vom Aufrufer kontrollierbar). W4-FIX-JAG2 (S2): die Quelle ist
 *     https-Zwangspflichtig (S1-Verband) — der Header ist damit
 *     transportgeschützt und kein Klartext-MITM kann ihn mitschreiben.
 *  3. App lädt herunter, prüft SHA-256 gegen den Header — bei Abweichung
 *     wird die Datei verworfen.
 *  4. Installiert wird NICHT still: Android zeigt den Installationsdialog,
 *     der User bestätigt am Gerät (unbekannte Quellen-Flow).
 *
 * Entfernt gegenüber v0.10.7: caller-supplied `url`/`expectedSha256`
 * Parameter (Credential-Exfiltration-Vektor, Review-Blocking-1).
 */
object UpdateInstaller {

    private const val MAX_APK_BYTES = 256L * 1024 * 1024

    data class Result(
        val ok: Boolean,
        val status: Int,
        val message: String,
        val bytesDownloaded: Long = 0,
        val sha256: String? = null,
    )

    /** Holt das APK vom konfigurierten Relay. Kein caller-supplied URL mehr. */
    fun start(context: Context): Result {
        val baseUrl = com.hermesandroid.bridge.client.RelayClient.serverUrl
            // v0.11.0 (Review R12): der Fallback griff NIE (falscher prefs-Namen
            // "flipsibridge" statt "hermes_bridge_prefs" + falscher Key) —
            // richtig name + richtiger Schlüssel, dann greift er wirklich.
            ?: com.hermesandroid.bridge.BridgeApplication.instance
                ?.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                ?.getString("relay_server_url", null)
            ?: return Result(false, 400, "Kein Relay-Server konfiguriert — zuerst verbinden")

        val base = baseUrl.trim().trimEnd('/')
        // W4-FIX-JAG2 (S2): https-Zwang analog S1 — der Update-Download trug
        // Bearer-Pairing-Code + APK über den Transport; http:// war zugelassen
        // (Klartext = Hash-Header fälschbar). Verband = dieselbe Regel wie
        // Connect (ServerProfileRules.relayUrlValidForConnect): https/wss immer,
        // Klartext nur dashboard.lan im Dev-Kontext (NSC-Ausnahme).
        if (!ServerProfileRules.relayUrlValidForConnect(base, BuildConfig.DEBUG)) {
            return Result(false, 400, "Update-Quelle nicht zulässig — https:// Relay-URL nötig (Klartext nur dashboard.lan im Dev-Build)")
        }
        // Upgrade-Pfad: apk/latest ist fest, kein Aufrufer-Einfluss möglich.
        val downloadUrl = "$base/apk/latest"

        // W4-01b (W3-03, App-Lane A): eigener Client = PINNING-VERBAND
        // mitversorgen — /apk/latest (Self-Update-Loop) lädt über den gepinnten
        // Relay-Host. Der Pinner kommt aus PinSource (kein Default-Host im Build):
        // Nutzer-Relay-URLs laden unverändert hostname-only. Bemerkung
        // (Bestands-Lücke, W4-12/13-Lane): der Client hat noch KEIN
        // MtlsSupport.decorate() — mTLS-Verband folgt in der mTLS-Welle.
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .apply { PinSource.pinner()?.let { certificatePinner(it) } }
            .build()

        val request = Request.Builder()
            .url(downloadUrl)
            // Der Pairing-Code geht NUR an den konfigurierten Relay-Host —
            // exakt dieselbe Gegenstelle wie der WS-Handshake.
            .header("Authorization", "Bearer " + com.hermesandroid.bridge.auth.PairingManager.getCode())
            .build()

        val tempFile: File
        val digest: String
        var bytes = 0L
        var declaredSha: String? = null
        try {
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return Result(false, 502, "Download fehlgeschlagen: HTTP ${resp.code}")
                }
                declaredSha = resp.header("X-APK-SHA256")?.trim()?.lowercase()
                val declared = resp.body?.contentLength() ?: -1L
                if (declared > MAX_APK_BYTES) {
                    return Result(false, 413, "APK zu groß (${declared} Bytes, Limit $MAX_APK_BYTES)")
                }
                tempFile = File.createTempFile("update_", ".apk", context.cacheDir)
                java.io.FileOutputStream(tempFile).use { out ->
                    val source = resp.body!!.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        bytes += read
                        if (bytes > MAX_APK_BYTES) {
                            tempFile.delete()
                            return Result(false, 413, "APK größer als Limit $MAX_APK_BYTES Bytes")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            val md = MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(tempFile).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            digest = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
            // W4-01b (W3-03, App-Lane A): Pin-Verstoß ehrlich benennen (VOR dem
            // IOException-Griff, da SSLPeerUnverifiedException ⊂ IOException).
            // Beweis-Marker: Negativ-Drill (falscher Pin) → dieser Text statt
            // des generischen Exception-Griffs; Logcat-Sieb CertificatePinner.
            return Result(false, 502, "Zertifikats-Pin fehlgeschlagen — Update-Download verweigert (${e.javaClass.simpleName})")
        } catch (e: IOException) {
            return Result(false, 502, "Download fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        } catch (e: Exception) {
            return Result(false, 500, "Update-Fehler: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        }

        if (bytes == 0L) {
            tempFile.delete()
            return Result(false, 502, "Download leer (0 Bytes)")
        }

        // Integrität: SHA-256 MUSS vom Server als Header kommen (Server kontrolliert
        // die Quelle, nicht der Aufrufer). Fehlt der Header oder passt er nicht → verwerfen.
        val expected = declaredSha
        if (expected.isNullOrEmpty()) {
            tempFile.delete()
            return Result(false, 400, "Server liefert keinen X-APK-SHA256-Header — Update abgelehnt (Integrität nicht prüfbar)")
        }
        if (!java.util.regex.Pattern.matches("[0-9a-f]{64}", expected) || digest != expected) {
            tempFile.delete()
            return Result(
                false, 400,
                "SHA-256 stimmt nicht überein (erwartet $expected, erhalten $digest) — APK verworfen",
            )
        }

        // PH-7/J2-F4 (11.11.2026): Editions-/Paket-Abgleich vor dem Installer —
        // die gelieferte APK muss DIESE App sein (GitHub-Edition; eine Play-APK
        // mit anderer applicationId würde als Zweit-App neben der Brücke
        // installieren statt zu updaten). Defense-in-Depth zum Relay-Filter.
        val archiveInfo = context.packageManager.getPackageArchiveInfo(tempFile.path, 0)
        if (archiveInfo?.packageName != context.packageName) {
            tempFile.delete()
            return Result(
                false, 400,
                "Editions-Fehler: gelieferte APK gehört zu einem anderen Paket (${archiveInfo?.packageName ?: "unbekannt"}) — Update verworfen",
            )
        }

        // "Apps aus unbekannten Quellen" für diese App gesetzt? Wenn nicht:
        // Einstellungsseite öffnen (einmalige Freigabe, Android-Pflicht).
        val appCtx = context.applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !appCtx.packageManager.canRequestPackageInstalls()) {
            val perm = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + appCtx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { appCtx.startActivity(perm) } catch (_: Exception) {}
            return Result(
                false, 403,
                "Bitte 'Apps aus unbekannten Quellen' für FlipsiBridge erlauben (Einstellungsseite geöffnet) — dann Update erneut anstoßen",
            )
        }

        // Installations-Intent (User-Dialog, kein stiller Pfad)
        return try {
            val fileToInstall = File(context.cacheDir, "flipsibridge-update.apk")
            tempFile.renameTo(fileToInstall) // gleiche Partition, sollte klappen
            if (!fileToInstall.exists()) fileToInstall.copyFrom(tempFile, overwrite = true)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fileToInstall,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result(
                ok = true,
                status = 202,
                message = "APK geladen (${bytes} Bytes, SHA-256 ok) — Android-Installer gestartet, bitte bestätigen",
                bytesDownloaded = bytes,
                sha256 = digest,
            )
        } catch (e: Exception) {
            Result(false, 500, "Installer-Start fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        }
    }

    private fun File.copyFrom(src: File, overwrite: Boolean) {
        if (!overwrite && exists()) return
        src.copyTo(this, overwrite = true)
    }
}