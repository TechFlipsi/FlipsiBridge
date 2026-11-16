package com.hermesandroid.bridge.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.PinSource
import com.hermesandroid.bridge.server.ServerProfileRules
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Update-Check (Fixliste-Punkt 26, Welle 16) — Nachfolger der gelöschten
 * UpdateInstaller-Kette. Es existiert KEIN In-App-Download/Install mehr:
 * kein APK-Greif im Code, keine Installer-Fähigkeit (REQUEST_INSTALL_PACKAGES
 * ist aus beiden Manifesten raus), keine Capability „selfupdate“.
 *
 * Was neu ist: eine dezente PUSH-Benachrichtigung (Channel „Updates“,
 * „FlipsiBridge 0.16.0 verfügbar“) mit Tap-Ziel je Edition — play öffnet den
 * Play-Store-Eintrag, github die GitHub-Release-Seite (kein Download-Button).
 *
 * Quelle (fail-closed, Reihenfolge):
 *  1. Relay: HEAD < relay >/apk/latest (Auth = derselbe Bearer wie die
 *     WS-Verbindung; https-Zwang + Pin-Verband wie beim Connect). Der Relay
 *     antwortet auf HEAD mit dem X-APK-Version-Header (neueste APK je
 *     apk-Verzeichnis) OHNE APK-Body — kein Download, kein Server-Hash.
 *  2. Nur github-Edition: GitHub latest release (Versions-Triplett zuerst
 *     aus dem github-APK-Asset-Namen, sonst aus dem Tag).
 *  - play-Edition ohne nutzbarem Relay: KEINE Quelle → still nichts tun
 *    (keine Version erraten, keine Notification).
 *
 * Rate/Dedupe: max. 1 Check je 24 h (App-Start-Aufrufe laufen im selben
 * Fenster) + Dedupe je gefundener Version (kein tägliches Wiederholen für
 * dieselbe „verfügbar“-Version).
 */
object UpdateCheck {

    private const val TAG = "UpdateCheck"

    /** Notification-Channel „Updates“ (Punkt 26b). */
    internal const val CHANNEL = "flipsibridge_updates"
    // W16.3 (F9): 4220 statt 4201 — 4201 ist bereits PairingManager
    // (NOTIFY_ID_CODE_EVENT): dieselbe ID hätte ein Update-Push die
    // Kopplungs-Notification ersetzen (und umgekehrt) lassen.
    private const val NOTIFY_ID = 4220

    /** Dezent: max. 1 Check je 24 h (App-Start + Alarm-Hook teilen das Fenster). */
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** github-Edition: Tap-Ziel = GitHub-Release-Seite (Punkt 26b). */
    private const val GITHUB_RELEASE_URL =
        "https://github.com/TechFlipsi/FlipsiBridge/releases/latest"
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/TechFlipsi/FlipsiBridge/releases/latest"

    private const val PREFS = "flipsibridge_updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_LAST_NOTIFIED = "last_notified_version"

    /** Eigener Daemon-Thread — nie auf dem Main-Thread, kein Coroutine-Zwang. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UpdateCheck").apply { isDaemon = true }
    }

    /** App-Start-Hook (BridgeApplication.onCreate) — geht ins 24h-Fenster. */
    fun afterAppStart(context: Context) {
        schedule(context)
    }

    /** Alarm-Hook (ReconnectAlarmReceiver, bestehender Weckrhythmus). */
    fun periodic(context: Context) {
        schedule(context)
    }

    private fun schedule(context: Context) {
        val appCtx = context.applicationContext
        executor.execute {
            runCatching { performCheck(appCtx) }
                .onFailure { Log.w(TAG, "Update-Check fehlgeschlagen: ${it.message}") }
        }
    }

    private fun performCheck(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        // Fenster schon beim ANSTOSS stempeln — das bleibt dezent (1×/Tag),
        // auch wenn die Quelle gerade unerreichbar war (Retry am Folge-Tick).
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val newest = newestVersion() ?: run {
            // Fail-closed: keine Quelle / nicht parsebare Version → still
            // nichts tun (keine „verfügbar“-Info aus geratenen Werten).
            Log.i(TAG, "Keine Update-Quelle lesbar — still (fail-closed)")
            return
        }
        val installed = parseTriplet(BuildConfig.VERSION_NAME) ?: return

        // installierte vs. neueste — nur eine WIRKLICH neuere Version meldet.
        if (newest[0] <= installed[0] && newest[1] <= installed[1] && newest[2] <= installed[2]) return

        // Dedupe je Version: dieselbe „verfügbar“-Version nagt NICHT jeden Tag.
        val key = "${newest[0]}.${newest[1]}.${newest[2]}"
        if (prefs.getString(KEY_LAST_NOTIFIED, null) == key) return

        if (!postNotification(context, key)) return
        prefs.edit().putString(KEY_LAST_NOTIFIED, key).apply()
    }

    /** Quelle-Verband: Relay-HEAD zuerst, github-Edition fällt auf GitHub zurück. */
    private fun newestVersion(): IntArray? {
        relayVersion()?.let { return it }
        return githubVersion()
    }

    /**
     * HEAD <relay>/apk/latest mit Bearer — NUR Header lesen (X-APK-Version),
     * kein Body-Download. Quelle: der NUR vom Relay gesetzte Header, nicht vom
     * Aufrufer steuerbar. Fehler/Parsing-Miss → null → Fallback/fail-closed.
     */
    private fun relayVersion(): IntArray? {
        val baseUrl = com.hermesandroid.bridge.client.RelayClient.serverUrl
            ?.trim()?.trimEnd('/') ?: return null
        if (!ServerProfileRules.relayUrlValidForConnect(baseUrl, BuildConfig.DEBUG)) {
            return null
        }
        // PIN-Verband wie der alte Updater-Client (PinSource) — der eigene
        // Relay-Host bleibt der EINE gepinnte Host, andere reine hostname-only.
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .apply { PinSource.pinner()?.let { certificatePinner(it) } }
            .build()
        val request = Request.Builder()
            .url("$baseUrl/apk/latest")
            .head()
            .header(
                "Authorization",
                "Bearer " + com.hermesandroid.bridge.auth.PairingManager.getCode(),
            )
            .build()
        client.newCall(request).execute().use { resp ->
            val version = if (resp.isSuccessful) resp.header("X-APK-Version")?.trim() else null
            return parseTriplet(version)
        }
    }

    /**
     * github-Edition-Fallback: GitHub latest release. Der Versions-Triplett
     * kommt bevorzugt aus einem github-APK-Asset-Namen („flipsibridge-…“),
     * sonst aus dem Tag. Play-Edition → null (keine GitHub-Quelle dort).
     */
    private fun githubVersion(): IntArray? {
        if (!BuildConfig.EDITION_GITHUB) return null
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val root = JSONObject(body)
            root.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val name = assets.optJSONObject(i)?.optString("name") ?: continue
                    if (name.contains("github") && name.endsWith(".apk")) {
                        parseTriplet(name)?.let { return it }
                    }
                }
            }
            // Tag kann „latest-build“ o. ä. heißen und NICHTS versionslesbares
            // tragen — dann bleibt es null (fail-closed, kein Erraten).
            return parseTriplet(root.optString("tag_name"))
        }
    }

    /** Erstes \d+\.\d+\.\d+-Triplett als int[3]; null = nicht parsebar. */
    private fun parseTriplet(raw: String?): IntArray? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(raw?.trim() ?: "") ?: return null
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }

    /**
     * Push „FlipsiBridge <v> verfügbar“ — Channel „Updates“, Tap je Edition:
     * play → Play-Store-Eintrag, github → GitHub-Release-URL.
     * false = nichts gepostet (Permission fehlt / System-Fehler) → kein
     * Dedupe-Stempel, der nächste Tages-Tick versucht es erneut.
     */
    private fun postNotification(context: Context, versionText: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Android 13+ ohne POST_NOTIFICATIONS bleibt still — kein Nudge
            // aus dem Hintergrund (bestehendes Muster der Chat-Notification).
            Log.i(TAG, "POST_NOTIFICATIONS nicht gegeben — still")
            return false
        }
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.update_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.update_channel_desc)
                        setShowBadge(true)
                    },
                )
            }
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle(context.getString(R.string.update_notify_title))
                .setContentText(context.getString(R.string.update_notify_text, versionText))
                .setSmallIcon(R.drawable.ic_refresh)
                .setAutoCancel(true)
                .setContentIntent(tapIntent(context))
            nm.notify(NOTIFY_ID, builder.build())
            true
        }.getOrElse {
            Log.w(TAG, "Update-Notification fehlgeschlagen: ${it.message}")
            false
        }
    }

    /** Tap-Ziel je Edition (Punkt 26b): play → Store-Eintrag, github → Release-URL. */
    private fun tapIntent(context: Context): PendingIntent {
        // Kette mit Resolve-Probe: market:// kann auf Store-losen Geräten
        // auflösen-los sein; die letzte Stufe (App öffnen) existiert IMMER
        // (eigener Launcher-Entry) — der Tap stirbt nie ins Leere.
        val intent = if (BuildConfig.EDITION_PLAY) {
            val market = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + context.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                market.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
            if (resolvable) market
            else Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://play.google.com/store/apps/details?id=" +
                        context.packageName,
                ),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Erst den Editions-Intent proben (ActivityNotFoundException-Gefahr),
        // sonst MainActivity als stets existierende Endstufe.
        return runCatching { PendingIntent.getActivity(context, 4202, intent, flags) }
            .getOrElse {
                Log.w(TAG, "Tap-Intent nicht auflösbar — öffne die App stattdessen")
                val app = Intent(context, com.hermesandroid.bridge.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                PendingIntent.getActivity(context, 4202, app, flags)
            }
    }
}
