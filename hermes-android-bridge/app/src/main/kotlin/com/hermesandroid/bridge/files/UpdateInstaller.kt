package com.hermesandroid.bridge.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * FlipsiBridge APK-Selbstupdate (Phase 4): Der Agent sagt der App,
 * wo ein neues APK liegt (HTTPS-URL + SHA-256). Die App lädt selbst
 * herunter, prüft die Checksumme und stößt den Android-Installer an.
 *
 * Sicherheitsregeln:
 *  - Capability "selfupdate" muss aktiv sein (Default AUS).
 *  - Nur https:// URLs. SHA-256 ist Pflicht — ein Download ohne
 *    verifizierte Checksumme wird verworfen.
 *  - Installiert wird NICHT still: Android zeigt den Installations-
 *    Dialog, Sir bestätigt am Gerät (unbekannte Quellen-Flow).
 *  - APK landet im app-eigenen Cache (kein öffentlicher Speicher).
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

    fun start(
        context: Context,
        url: String,
        expectedSha256: String?,
    ): Result {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) {
            return Result(false, 400, "Nur https:// URLs sind erlaubt")
        }
        val expected = expectedSha256?.trim()?.lowercase()
        if (expected.isNullOrEmpty()) {
            return Result(false, 400, "expectedSha256 ist Pflicht (Integritätsprüfung)")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(trimmed)
            // Der eigene Pairing-Code als Bearer — erlaubt dem Phone, das eigene
            // Update vom token-geschützten Relay-/apk/latest zu laden.
            .header("Authorization", "Bearer " + (com.hermesandroid.bridge.client.RelayClient.pairingCode
                ?: com.hermesandroid.bridge.auth.PairingManager.getCode()))
            .build()
        val tempFile: File
        val digest: String
        var bytes = 0L
        try {
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return Result(false, 502, "Download fehlgeschlagen: HTTP ${resp.code}")
                }
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
        } catch (e: IOException) {
            return Result(false, 502, "Download fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        } catch (e: Exception) {
            return Result(false, 500, "Update-Fehler: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        }

        if (bytes == 0L) {
            tempFile.delete()
            return Result(false, 502, "Download leer (0 Bytes)")
        }
        if (digest != expected) {
            tempFile.delete()
            return Result(
                false, 400,
                "SHA-256 stimmt nicht überein (erwartet $expected, erhalten $digest) — APK verworfen",
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