package com.hermesandroid.bridge.auth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Single Source of Truth für den Kopplungscode (v0.11.0).
 *
 * Vorher gab es ZWEI Kopien (PairingManager "pairing_code" und RelayClient
 * "relay_pairing_code") — ein UI-Regenerate aktualisierte nur den ersten,
 * der WebSocket sandte weiter den ALTEN Code → 401/403 → Relay blockiert
 * die IP → "Verbindung stirbt plötzlich, ein neues Kopplungscode nötig".
 * Genau dieser Fehler ist ab jetzt strukturell unmöglich: RelayClient liest
 * den Code IMMER frisch über getCode() beim Connect-Versuch, es gibt keinen
 * zweiten Code-Speicher mehr.
 *
 * Persistenz-Härtung gegen die Vorfallskette (HyperOS 2 leert
 * hermes_bridge_prefs → stiller Regenerate → 403-Kaskade):
 *  - Code liegt ZUSÄTZLICH als Datei in app-privatem `getDir("vault")`
 *    (der typische HyperOS-Cleaner überlebt einen prefs-Wipe dort; völlig
 *    app-privat = kein Fremdzugriff ohne Root).
 *  - Bei leerem prefs wird SICHTBAR WIEDERHERGESTELLT (Event RESTORED);
 *    generiert wird nur noch, wenn auch die Vault-Datei weg ist — und das
 *    NIE still: sichtbare Notification + lastCodeEvent für die UI.
 *
 * Kein Keystore-Wrapping: das hätte bei Keystore-Korruption erneut die
 * still-Regenerate-Falle eröffnet. Bedrohungsmodell des Vaults ist der
 * System-Cleaner, nicht Root (bei Root ist ohnehin alles offen).
 */
object PairingManager {

    private const val TAG = "PairingManager"
    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY_PAIRING_CODE = "pairing_code"
    private const val KEY_CODE_EVENT = "last_code_event"
    private const val KEY_CODE_EVENT_AT = "last_code_event_at_ms"
    private const val VAULT_DIR = "vault"
    private const val VAULT_FILE = "pairing_code.v1"
    /** F2: Server-URL spiegelt im Vault — Prefs-Wipe darf die Adresse nicht mehr töten. */
    private const val KEY_SERVER_URL = "relay_server_url"
    private const val URL_FILE = "relay_url.v1"
    private const val CODE_LENGTH = 12
    private const val NOTIFY_ID_CODE_EVENT = 4201
    private const val NOTIFY_CHANNEL = "flipsibridge_code_events"

    /** Was als Letztes mit dem Code passiert ist — liest die UI für den Status. */
    enum class CodeEvent { NONE, REGENERATED, RESTORED }

    private val secureRandom = SecureRandom()

    private var prefs: SharedPreferences? = null

    /** K2-Fix (Kobold): @Volatile — getCode() liest vom OkHttp-IO-Thread aus,
     *  regenerateCode() schreibt vom Main-Thread; ohne @Volatile sah der WS
     *  den ALTEN Code (R1 in neuem Gewand, ARM-Visibility-Lücke). */
    @Volatile
    private var cachedCode: String? = null
    private var appContext: Context? = null

    @Volatile
    private var lastEvent: CodeEvent = CodeEvent.NONE

    fun init(context: Context) {
        // Robust gegen Mocks/ungewöhnliche Contexte (Alt-Tests mocken strikt):
        // ApplicationContext darf den Init nie schmettern.
        runCatching { appContext = context.applicationContext }
            .onFailure { appContext = null; Log.w(TAG, "appContext nicht setzbar: ${it.message}") }
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ensureCodeExists()
    }

    /** Letztes Code-Ereignis + Zeitpunkt (UI-Status/Bericht). */
    fun getLastCodeEvent(): CodeEvent {
        if (lastEvent == CodeEvent.NONE) {
            // K7-Fix (Kobold): prefs-Korruption darf keine Exception bei JEDER
            // Composition werfen (Crash-Loop) — sauber auf NONE fallen.
            lastEvent = runCatching {
                CodeEvent.valueOf(
                    prefs?.getString(KEY_CODE_EVENT, CodeEvent.NONE.name) ?: CodeEvent.NONE.name
                )
            }.getOrDefault(CodeEvent.NONE)
        }
        return lastEvent
    }

    fun getLastCodeEventAtMs(): Long {
        return prefs?.getLong(KEY_CODE_EVENT_AT, 0L) ?: 0L
    }

    /**
     * Stellt das Feld des Codes sicher. Reihenfolge:
     * 1. Prefs gefüllt → gut, Vault nachziehen.
     * 2. Prefs leer, Vault gefüllt → WIEDERHERSTELLEN (sichtbares Event).
     * 3. Beide leer → NEU GENERIEREN (sichtbares Event, nie mehr still).
     */
    private fun ensureCodeExists() {
        val stored = prefs?.getString(KEY_PAIRING_CODE, "") ?: ""
        if (stored.isNotBlank()) {
            if (readVaultIfPossible().isNullOrBlank()) {
                writeVault(stored)
            }
            cachedCode = stored
            return
        }
        val vaultCode = readVaultIfPossible()
        if (!vaultCode.isNullOrBlank()) {
            prefs?.edit()?.putString(KEY_PAIRING_CODE, vaultCode)
                ?.putLong(KEY_CODE_EVENT_AT, System.currentTimeMillis())
                ?.apply()
            cachedCode = vaultCode
            lastEvent = CodeEvent.RESTORED
            prefs?.edit()?.putString(KEY_CODE_EVENT, CodeEvent.RESTORED.name)?.apply()
            Log.i(TAG, "Pairing code restored from vault (prefs were wiped)")
            notifyCodeEvent(
                "Kopplungscode wiederhergestellt",
                "Die Bridge kann sich erneut verbinden — kein erneutes Kopplungscode nötig."
            )
            return
        }
        val code = generateRandomCode()
        prefs?.edit()?.putString(KEY_PAIRING_CODE, code)?.apply()
        writeVault(code)
        cachedCode = code
        lastEvent = CodeEvent.REGENERATED
        persistEvent(CodeEvent.REGENERATED)
        Log.i(TAG, "Pairing code regenerated (prefs AND vault were empty) — notification sent")
        notifyCodeEvent(
            "Kopplungscode neu erstellt",
            "Prefs + Vault waren leer. Neue Kopplung mit dem Server nötig."
        )
    }

    fun getCode(): String {
        cachedCode?.let { return it }
        val code = prefs?.getString(KEY_PAIRING_CODE, "") ?: ""
        cachedCode = code
        return code
    }

    /**
     * Bewusstes Neu-Koppeln (nur durch den UI-Button). Aktualisiert die eine
     * SSOT + Vault + sichtbares Event. Die Serverseite (Token in der .env am
     * Relay-Host) muss den neuen Code im nächsten Schritt übernehmen.
     */
    @Synchronized
    fun regenerateCode(): String {
        val code = generateRandomCode()
        prefs?.edit()?.putString(KEY_PAIRING_CODE, code)?.apply()
        writeVault(code)
        cachedCode = code
        persistEvent(CodeEvent.REGENERATED)
        lastEvent = CodeEvent.REGENERATED
        notifyCodeEvent(
            "Neuer Kopplungscode erzeugt",
            "Der Code ist neu — am Server eintragen, damit die Bridge sich koppeln kann."
        )
        return code
    }

    /**
     * W4-D (Multi-Server/Onboarding, Verband je Profil-Wechsel): übernimmt den
     * Code eines Relay-Profils BEWUSST in die EINE SSOT (Prefs + Vault + Cache)
     * — der Singleton PairingManager folgt damit dem AKTIVEN Profil; es gibt
     * weiterhin keinen zweiten Code-Speicher (Vorfall-Wurzel bleibt verbannt).
     *
     * Kein CodeEvent/Notification BEWUSST: ein Profil-Wechsel ist ein
     * Nutzereingang, kein Storage-Vorfall — REGENERATED/RESTORED bleiben
     * Vorfall-Meldungen (die UI-Regenerate-Kette bleibt unverändert).
     *
     * Validierung: trim + nichtleer; die 12-Zeichen-Form von generateRandomCode
     * wird NICHT erzwungen — Relay-Tokens sind je Server eigen gewählt
     * (Literal-Preserve, kein stillschweigendes „Reparieren“ des Tokens).
     */
    @Synchronized
    fun adoptActiveCode(code: String, source: String) {
        val adopted = code.trim()
        if (adopted.isBlank()) return
        prefs?.edit()?.putString(KEY_PAIRING_CODE, adopted)?.apply()
        writeVault(adopted)
        cachedCode = adopted
        Log.i(TAG, "Code adoptiert (Quelle: $source) — SSOT folgt dem aktiven Profil")
    }

    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // kein 0/O/1/I (Verwechslung)
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) { sb.append(chars[secureRandom.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun persistEvent(event: CodeEvent) {
        prefs?.edit()
            ?.putString(KEY_CODE_EVENT, event.name)
            ?.putLong(KEY_CODE_EVENT_AT, System.currentTimeMillis())
            ?.apply()
    }

    // ── Vault: app-privates getDir, überlebt prefs-Wipe durch den Cleaner ──
    private fun vaultFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.getDir(VAULT_DIR, Context.MODE_PRIVATE), VAULT_FILE)
    }

    private fun readVaultIfPossible(): String? {
        return runCatching {
            val f = vaultFile() ?: return@runCatching null
            if (!f.exists()) return@runCatching null
            val content = f.readText().trim()
            if (content.isNotBlank()) content else null
        }.getOrNull()
    }

    private fun writeVault(code: String) {
        runCatching {
            val f = vaultFile() ?: return
            f.delete()
            f.writeText(code)
        }.onFailure { Log.w(TAG, "Vault-Write fehlgeschlagen: ${it.message}") }
    }

    // ── URL-Vault (Detektiv-F2): Prefs-Wipe überlebt die Server-Adresse mit ──
    // Exakt die Vorfall-Kette: Code im Vault, URL nur in den prefs → nach dem
    // Wipe stellte sich der Code wieder her, verband aber STUMM NICHTS mehr.
    // gleiche getDir-Werkzeuge wie der Code: app-privat, Cleaner-sicher.

    private fun urlVaultFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.getDir(VAULT_DIR, Context.MODE_PRIVATE), URL_FILE)
    }

    /** Spiegelt die Server-Adresse in den Vault (idempotent, bei jedem Setter-Write). */
    fun saveUrlToVault(url: String) {
        if (url.isBlank()) return
        runCatching {
            val f = urlVaultFile() ?: return
            f.delete()
            f.writeText(url.trim())
        }.onFailure { Log.w(TAG, "URL-Vault-Write fehlgeschlagen: ${it.message}") }
    }

    /**
     * F2-Restore: sind die URL-Prefs WEG (Wipe), hol die aus dem Vault — sichtbar
     * geloggt + benachrichtigt. Liefert die gerettete URL (oder null).
     */
    fun restoreUrlIfPrefsLost(): String? {
        val stored = prefs?.getString(KEY_SERVER_URL, "") ?: ""
        if (stored.isNotBlank()) {
            saveUrlToVault(stored) // Vault-Nachzieh bei jedem legitimen Read
            return null
        }
        val vaultUrl = runCatching {
            val f = urlVaultFile() ?: return@runCatching null
            if (!f.exists()) return@runCatching null
            f.readText().trim().isBlank().let { if (it) null else f.readText().trim() }
        }.getOrNull()
        if (!vaultUrl.isNullOrBlank()) {
            prefs?.edit()?.putString(KEY_SERVER_URL, vaultUrl)?.apply()
            Log.i(TAG, "Server-URL aus dem Vault wiederhergestellt (prefs were wiped)")
            persistEvent(CodeEvent.RESTORED)
            lastEvent = CodeEvent.RESTORED
            notifyCodeEvent(
                "Server-Adresse wiederhergestellt",
                "Die Bridge verbindet automatisch wieder — kein Neuausfüllen nötig."
            )
        }
        return vaultUrl
    }

    // ── Sichtbarkeit: Notification bei jedem Code-Vorgang ──────────────────
    private fun notifyCodeEvent(title: String, text: String) {
        val ctx = appContext ?: return
        runCatching {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFY_CHANNEL,
                    "Kopplungscode-Ereignisse",
                    IMPORTANCE_HIGH
                ).apply { description = "Code wiederhergestellt oder neu erstellt" }
                manager.createNotificationChannel(channel)
            }
            // Auf Android 13+ ohne POST_NOTIFICATIONS: bleibt die Notification
            // aus — das Ereignis ist trotzdem in der App-UI sichtbar (Banner).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val contentIntent = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, com.hermesandroid.bridge.MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification: Notification = NotificationCompat.Builder(ctx, NOTIFY_CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFY_ID_CODE_EVENT, notification)
        }.onFailure { Log.w(TAG, "Code-Event-Notification fehlgeschlagen: ${it.message}") }
    }

    /**
     * Validate an incoming request's Authorization header.
     * Expected format: "Bearer <code>".
     * Uses fixed-length padding + MessageDigest.isEqual for constant-time
     * comparison (isEqual short-circuits on unequal lengths, so pad first
     * and compare length after the comparison completes).
     */
    fun validateToken(authHeader: String?): Boolean {
        if (authHeader == null) return false
        // Nur das dokumentierte "Bearer <code>"-Format; nackter Token wird
        // abgelehnt (passt zum Client und zum Python-Relay, beides sendet so).
        if (!authHeader.startsWith("Bearer ")) return false
        val token = authHeader.removePrefix("Bearer ").trim()
        val expected = getCode()
        if (expected.isEmpty()) return false
        val a = token.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        val padLen = maxOf(a.size, b.size, 256)
        return MessageDigest.isEqual(a.copyOf(padLen), b.copyOf(padLen)) && a.size == b.size
    }
}