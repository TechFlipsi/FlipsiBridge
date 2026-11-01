package com.hermesandroid.bridge.client

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.event.EventStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import com.hermesandroid.bridge.files.DeviceFiles
import com.hermesandroid.bridge.security.CapabilityGate
import com.hermesandroid.bridge.security.MtlsSupport
import com.hermesandroid.bridge.server.CommandDispatcher
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeConnectionService
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WebSocket client that connects OUT to the FlipsiBridge relay server.
 * Receives commands over WebSocket, dispatches them to [CommandDispatcher],
 * and sends results back.
 *
 * v0.11.0 (Sir-Requirement "jederzeit Zugriff") — Verbindungs-Rebuild:
 *  - KEIN Terminalzustand mehr: nach dem Retry-Budget geht die Verbindung in
 *    den Low-Rate-Modus (5-Minuten-Takt, endlos) statt aufzugeben.
 *  - Auth-Fehler (401/403) STOPPEN die Wiederholungsversuche (Relay lockt die
 *    IP nach 5 Fehlversuchen für 300 s — wir stoppen nach 3), persistenter
 *    "Neu koppeln"-Zustand statt Endlos-Retry-Spirale.
 *  - 429 (Relay-Block-Fenster) erzwingt Blockdauer-Warten, ohne Budget zu
 *    verbrennen.
 *  - Code-SSOT: der Code wird bei JEDEM Versuch frisch aus [PairingManager]
 *    gelesen (kein zweiter gespeicherter Code mehr — das war die Vorfall-
 *    wurzel: abweichende Kopien nach einem UI-Regenerate).
 *  - FGS [BridgeConnectionService] wird ab connect() hochgefahren — nicht
 *    mehr vom optionalen Accessibility-Service abhängig.
 *  - NetworkCallback: bei wieder verfügbarer Netzwerk sofort wieder verbinden.
 *  - AlarmManager (setAndAllowWhileIdle) für Low-Rate-Versuche und lange
 *    Backoffs — Doze-sicher, ohne SCHEDULE_EXACT_ALARM.
 */
object RelayClient {

    private const val TAG = "RelayClient"
    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY_SERVER_URL = "relay_server_url"
    private const val KEY_AUTH_BROKEN = "bridge_auth_broken"
    private const val KEY_AUTH_FAILURES = "auth_failure_streak"
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_RETRIES = 5

    /** Low-Rate-Modus nach Budgetverbrauch: endloser 5-Minuten-Takt. */
    private const val LOW_RATE_BACKOFF_MS = 300_000L
    /** Backoff ab dieser Dauer als Doze-sicheren Alarm legen (kein Coroutine-Sleep). */
    private const val ALARM_THRESHOLD_MS = 60_000L
    /** 429 → Relay-Block (300 s) abwarten + Puffer, Budget bleibt unberührt. */
    private const val BLOCK_WAIT_MS = 310_000L
    /** Auth-Fehlversuche, bevor die Versuche stoppen (Relay blockt bei 5). */
    private const val MAX_AUTH_FAILURES = 3

    // --- Termux revival watchdog ---
    // When the relay is unreachable past these thresholds, fire the
    // Termux RUN_COMMAND intent to restart the whole stack (start-zee.sh).
    // (Upstream-Überbleibsel; Standard: AUS, opt-in in der UI.)
    private const val WATCHDOG_FAILURES_TO_TRIGGER = 3
    private const val WATCHDOG_COOLDOWN_MS = 600_000L  // 10 min
    private val revivalPolicy = RevivalPolicy(WATCHDOG_FAILURES_TO_TRIGGER, WATCHDOG_COOLDOWN_MS)
    private const val KEY_FAILURES = "watchdog_failures"
    private const val KEY_LAST_FIRE = "watchdog_last_fire_ms"
    private const val KEY_REVIVAL_ENABLED = "termux_revival_enabled"

    private val gson = Gson()
    private val client = MtlsSupport.decorate(
        OkHttpClient.Builder()
            .pingInterval(java.time.Duration.ofSeconds(45))   // v0.12.0 (Akku P1-1): war 20 — NAT/NPM hält der 15-s-Relay-Heartbeat; nur Toterkennungslatenz 20-40s → 45-90s
            .build()
    )

    /** Status-Zuhörer — MainActivity/Service/UI hören je selbst zu (keine Slot-Race). */
    private val statusListeners = CopyOnWriteArrayList<(connected: Boolean, message: String) -> Unit>()

    /** Veralteter Einzel-Slot (Upstream-kompatibel); vorzugsweise addStatusListener nutzen. */
    @Deprecated("Stattdessen addStatusListener — der Einzel-Slot ersetzt andere Zuhörer.")
    @Volatile
    var onStatusChanged: ((connected: Boolean, message: String) -> Unit)? = null

    fun addStatusListener(listener: (connected: Boolean, message: String) -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: (connected: Boolean, message: String) -> Unit) {
        statusListeners.remove(listener)
    }

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var watchdogScope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val reconnectPolicy = ReconnectPolicy(maxRetries = MAX_RETRIES, maxBackoffMs = MAX_BACKOFF_MS)

    /** True between scheduling a reconnect and that attempt firing. Guards against
     *  onClosed + onFailure both scheduling for the same dead connection. */
    @Volatile
    private var reconnectPending: Boolean = false

    /** Bumped per connect attempt; callbacks from superseded sockets are ignored. */
    @Volatile
    private var generation: Int = 0

    /** `System.nanoTime()` at the last onOpen, or 0 when no session is open. */
    @Volatile
    private var sessionStartedNs: Long = 0L

    /** Persistenter "Kopplung kaputt"-Zustand — UI zeigt Handlungsbedarf. */
    @Volatile
    private var authBroken: Boolean = false

    /**
     * User-Intent-Merker (Detektiv F3/F1-Unterscheidung): true NUR in
     * disconnect() (bewusstes Trennen), false bei connect(). Die F1-Sonde
     * testet NUR im echten Kopplungsfehler (403-Stopp-Pfad), nie nach
     * bewusstem Trennen — sonst holt der Weckruf die Trennung wieder zurück.
     */
    private var userDisconnected: Boolean = false

    /** Letzter Status-Text (Service-Notification liest ihn nach). */
    @Volatile
    var currentStatusMessage: String = "Inaktiv"
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var shouldReconnect: Boolean = false

    var serverUrl: String?
        get() = prefs?.getString(KEY_SERVER_URL, null)
        set(value) {
            prefs?.edit()?.putString(KEY_SERVER_URL, value)?.apply()
            // F2: URL-Spiegelung im Vault bei jedem legitimen Write —
            // Prefs-Wipes (HyperOS-Cleaner) dürfen die Adresse nicht töten.
            value?.let { PairingManager.saveUrlToVault(it) }
        }

    fun isAuthBroken(): Boolean = authBroken

    /**
     * v0.11.0: Code wird NICHT mehr zweitgespeichert — immer frisch aus dem
     * PairingManager (SSOT) gelesen, damit ein UI-Regenerate sofort wirksam
     * ist (Vorfall-Fix gegen die abweichende Code-Kopie `relay_pairing_code`).
     */
    val pairingCode: String
        get() = PairingManager.getCode()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appContext = context.applicationContext
        authBroken = prefs?.getBoolean(KEY_AUTH_BROKEN, false) ?: false
        registerNetworkCallback()
    }

    /** Kopplung kaputt — wird bei erfolgreichem onOpen oder explizitem connect() geräumt. */
    private fun setAuthBroken(broken: Boolean) {
        authBroken = broken
        prefs?.edit()?.putBoolean(KEY_AUTH_BROKEN, broken)?.apply()
        // Final-Fix (Detektiv-F1): Selbstheilungs-Sonde. Ohne sie blieb die App
        // nach 401×3 ENDGÜLTIG still, selbst wenn Sir den Server repariert
        // (z. B. alten Code wieder einträgt) — nur ein manuelles App-Öffnen
        // hätte geholfen. Jetzt: alle ~45 min ein EINZELNER Testversuch
        // (setAndAllowWhileIdle; meilenweit unter der Relay-Sperre 5/300 s).
        // onOpen räumt den Zustand sowieso; FLAG_UPDATE_CURRENT dedupliziert.
        if (broken) scheduleAuthProbe() else cancelAuthProbe()
    }

    /**
     * F1-Selbstheilung: kopplungsfehler-Zustand periodisch anprobe-EN statt
     * endlos still bleiben. Nur aktiv, wenn der User nicht bewusst getrennt hat
     * (shouldReconnect bleibt beim 403-Stopp unangetastet = true).
     */
    private fun scheduleAuthProbe() {
        scheduleDozeAlarm(45 * 60_000L, probe = true)
    }

    private fun cancelAuthProbe() {
        val ctx = appContext ?: return
        runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(
                PendingIntent.getBroadcast(
                    ctx, 2,
                    Intent(ctx, ReconnectAlarmReceiver::class.java)
                        .putExtra(ReconnectAlarmReceiver.EXTRA_AUTH_PROBE, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun bumpAuthFailureStreak(): Int {
        val v = (prefs?.getInt(KEY_AUTH_FAILURES, 0) ?: 0) + 1
        prefs?.edit()?.putInt(KEY_AUTH_FAILURES, v)?.apply()
        return v
    }

    private fun clearAuthFailureStreak() {
        prefs?.edit()?.putInt(KEY_AUTH_FAILURES, 0)?.apply()
    }

    // Shares the monitor with scheduleReconnect/beginReconnectAttempt: these run
    // on the main thread while callbacks schedule reconnects on OkHttp threads,
    // and an interleaving there can strand reconnectPending set with no
    // coroutine left to clear it, killing auto-reconnect for the process.
    @Synchronized
    fun connect(serverUrl: String, pairingCode: String) {
        disconnect()

        // v0.10.8 (Review-Blocking-3-Fix): Merker für die Capability-Migration —
        // wer je eine Relay-Verbindung einrichtet, hat "vor dem Gating" genutzt.
        prefs?.edit()?.putBoolean("legacy_had_relay", true)?.apply()

        this.serverUrl = serverUrl
        // Bewusste Neu-Kopplung: gesperrten Auth-Zustand räumen — der User gibt
        // den Code bewusst eine Chance (SSOT macht den Code ohnehin aktuell).
        setAuthBroken(false)
        userDisconnected = false
        clearAuthFailureStreak()
        shouldReconnect = true
        reconnectPolicy.reset()

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        if (watchdogScope == null) {
            watchdogScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        // FGS ab connect() — der Prozess ist sofort hyperOS-sicheres Hintergrundlicht.
        BridgeConnectionService.start(appContext ?: return)

        doConnect(serverUrl, PairingManager.getCode())
    }

    @Synchronized
    fun disconnect() {
        // F3-Fix: bewusstes Trennen als User-Intent merken — Dud-Alarms (vor dem
        // Trennen gesetzte 5-min/429-Wachrufe) dürfen das TRENNDEN nicht rückgängig
        // machen (autoConnect-Gate respektiert userDisconnected).
        userDisconnected = true
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectPending = false
        cancelAuthProbe()
        reconnectPolicy.reset()
        sessionStartedNs = 0L
        generation++
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        scope?.cancel()
        scope = null
        isConnected = false
        BridgeAccessibilityService.instance?.stopForeground()
        notifyStatus(false, "Verbindung geschlossen")
        // v0.11.0-QA-LEARNING (Emulator-Repro 30.10., crash-buffer):
        // KEIN stopService() hier! connect() ruft disconnect() zuerst — ein
        // stopService innerhalb des 5-s-FGS-Regelfensters produzierte
        // ForegroundServiceDidNotStartInTimeException-Crashes (die App starb
        // exakt beim Verbinden/Trennungs-Flatter). Der FGS bleibt bestehen mit
        // der "Getrennt"-Anzeige; bewusstes Trennen = nur WS aus.
    }

    /**
     * Idempotenter Auto-Connect (Service-Restart-safe): verbindet NICHT neu,
     * wenn die Schleife bereits aktiv ist (shouldReconnect = verbindet gerade /
     * verbunden / im Low-Rate-Modus); und NICHT, wenn der persistente
     * Kopplungs-Kaputt-Zustand aktiv ist (der wird über die UI-Neukopplung
     * geräumt, sonst Retry-Block-Spirale).
     */
    fun autoConnect() {
        if (authBroken || userDisconnected) {
            // authBroken → 403-Stopp (Neu-Kopplung-Schutz vor Retry-Spirale);
            // userDisconnected → F3: der Weckruf darf ein Trennden nicht umdrehen.
            Log.w(TAG, "autoConnect übersprungen: authBroken=$authBroken userDisconnected=$userDisconnected")
            return
        }
        if (shouldReconnect) return
        val code = PairingManager.getCode()
        // F2: URL-Prefs-Wipe? Vault-Rettung, dann verbinden (statt stummem Still).
        val url = serverUrl ?: PairingManager.restoreUrlIfPrefsLost()
        if (!url.isNullOrBlank() && code.isNotBlank()) {
            Log.i(TAG, "Auto-connecting to ${safeUrlForLog(url)}")
            connect(url, code)
        }
    }

    /** Von Alarm/NetworkCallback ausgelöste Versuche.
     *  v0.11.0-QA-LEARNING (Emulator-Repro 30.10.): Die Wache trat NICHT ein,
     *  weil scheduleReconnect den Alarm plant, während reconnectPending=true
     *  bleibt — der alte reconnectPending-Guard schluckte den Feuer-Ruf still.
     *  Jetzt: beginReconnectAttempt räumt reconnectPending selbst (erste Zeile);
     *  doppelte Feuer-Rufe sind über die @Synchronized-Bahnhofswache geschützt.
     */
    /**
     * F1-Selbstheilungs-Sonde (vom AuthProbe-Alarm gefeuert): EIN kopplungs-
     * Verbindungsversuch, obwohl authBroken — genau was autoConnect verweigert.
     * Scheitert der Versuch wieder 401/403, setzt der normale Auth-Prüfpfad
     * authBroken ohnehin neu (und die Sonde wird in setAuthBroken re-geplant).
     * onOpen räumt den Zustand komplett.
     */
    @Synchronized
    fun authProbe() {
        if (!authBroken || isConnected || userDisconnected) return
        val url = serverUrl ?: return
        val code = PairingManager.getCode()
        if (code.isBlank()) return
        // v0.12.0 QA-Fix (SK-03): Radio-Bewusstsein — ohne Netz ist die Sonde
        // reiner Radio-Burst; die Selbstheilung bleibt bestehen (45-min-Takt).
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (cm?.activeNetwork == null) return
        Log.i(TAG, "Auth-Sonde: Kopplungstest (Selbstheilung nach Server-Freigabe)")
        clearAuthFailureStreak()
        doConnect(url, code)
    }

    @Synchronized
    fun reconnectNow() {
        if (!shouldReconnect || authBroken || isConnected) return
        // v0.12.0 (Akku P2-5): kein Versuch bei TOTAL fehlendem Netz
        // (Flugmodus) — die Rückkehr übernimmt ohnehin der NetworkCallback
        // mit frischem Budget; ein DNS-Timeout wäre reiner Radio-Burst.
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (cm?.activeNetwork == null) return
        val url = serverUrl ?: return
        if (PairingManager.getCode().isBlank()) return
        beginReconnectAttempt(url, PairingManager.getCode())
    }

    /** NetworkCallback: Netz (wieder) da → sofortiger Versuch, Budget frisch. */
    private fun onNetworkAvailable() {
        if (!(shouldReconnect && !authBroken && !isConnected)) return
        Log.i(TAG, "Netz (wieder) verfügbar — sofortiger Verbindungsversuch")
        // Netzwechsel = umweltbedingte Ausfälle → Budget zurücksetzen.
        reconnectPolicy.reset()
        reconnectNow()
    }

    private fun registerNetworkCallback() {
        val ctx = appContext ?: return
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // v0.11.0-QA-LEARNING (Emulator-Repro): NUR onAvailable —
            // onCapabilitiesChanged feuert bei jeder Metrik-Änderung und
            // produzierte einen Log-/Wiederversuch-Sturm.
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    notifyNetwork()
                }
            })
        }.onFailure { Log.w(TAG, "NetworkCallback nicht registriert: ${it.message}") }
    }

    private fun notifyNetwork() {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post { onNetworkAvailable() }
        } catch (_: Exception) {}
    }

    /**
     * Schnüffler-F1: Release-Logcat-URLs bleiben HOST-FREI (das APK ist
     * öffentlich; jede adb-Logzeile trägt sonst die private Infra nach außen).
     * Debug-Bauten zeigen die echten Werte für die Entwicklerdiagnose.
     */
    private fun safeUrlForLog(url: String?): String =
        if (BuildConfig.DEBUG) (url ?: "null") else "<relay>"

    private fun doConnect(serverUrl: String, pairingCode: String) {
        val myGeneration = ++generation
        // Schnüffler-F1: Release-Logcat bleibt HOST-FREI (BuildConfig.DEBUG
        // zeigt die echten Zeilen nur am Entwickler-Rechner).
        Log.i(TAG, "Connecting to ${safeUrlForLog(buildWsUrl(serverUrl))}")
        // Schnüffler-F2: die FGS-Notification (Sperrbildschirm!) nennt den
        // Relay-Host NIEMALS — nur der App-Screen zeigt die konkrete Adresse.
        notifyStatus(false, "Verbinde mit Relay ...")

        // v0.12.0 (QA-T20): INVALID-URL darf die App NICHT abschießen —
        // OkHttp HttpUrl wirft bei defekter host/port-Form synchron
        // (IllegalArgumentException): Tippfehler im Feld = Statusmeldung +
        // ordentlicher Abbruch, kein Crash-Dialog.
        val request = runCatching {
            Request.Builder()
                .url(buildWsUrl(serverUrl))
                .header("Authorization", "Bearer $pairingCode")
                .build()
        }.getOrElse { e ->
            Log.w(TAG, "Ungültige Server-Adresse: ${e.javaClass.simpleName}")
            notifyStatus(false, "Server-Adresse ungültig — Format: https://dein-relay.example")
            generation = myGeneration - 1
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGeneration != generation) {
                    webSocket.cancel()
                    return
                }
                Log.i(TAG, "WebSocket connected to ${safeUrlForLog(serverUrl)}")
                isConnected = true
                // success resets the revival watchdog + Auth-Fehlserie
                prefs?.edit()?.putInt(KEY_FAILURES, 0)?.apply()
                clearAuthFailureStreak()
                setAuthBroken(false)
                // NOT a policy reset: the budget is only refilled once this
                // session proves stable (see endSession), so a relay that
                // accepts and instantly drops us can't retry forever.
                sessionStartedNs = System.nanoTime()
                runCatching {
                    // v0.12.0 (Akku P1-3): KEINE zweite dauerhafte FGS-Promotion
                    // mehr — die Prozess-Priorität trägt der dataSync-FGS
                    // (BridgeConnectionService); die mediaProjection-Promotion
                    // übernimmt ScreenRecorder bei /screen_record selbst.
                }.onFailure { logW("A11y-Foreground-Promotion übersprungen: ${it.message}") }
                notifyStatus(true, "Verbunden mit Relay")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope?.launch {
                    handleMessage(webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                endSession()
                // v0.12.0 (Akku P0-2): Leakschutz — ein gestorbener Stream darf
                // den Vollcapture-Zustand nicht dauerhaft stehen lassen.
                EventStore.highVolumeEvents = false
                EventStore.streamingEnabled = false
                // v0.12.0 QA-Fix (SK-02/CHEF-02): Service-QUELLE drosseln —
                // sonst bleibt der A11y-Service bis Reboot auf VOLLEM Set.
                BridgeAccessibilityService.instance?.applyEventThrottle(false)
                notifyStatus(false, "Getrennt (code=$code)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGeneration != generation) return
                val httpCode = response?.code ?: 0
                val errorDetail = "Verbindungsfehler: ${t.javaClass.simpleName} (HTTP $httpCode)"
                Log.e(TAG, "WebSocket failure: $errorDetail")
                isConnected = false
                endSession()
                // v0.12.0 (Akku P0-2): Leakschutz — wie onClosed.
                EventStore.highVolumeEvents = false
                EventStore.streamingEnabled = false
                // v0.12.0 QA-Fix (SK-02/CHEF-02): Service-QUELLE drosseln.
                BridgeAccessibilityService.instance?.applyEventThrottle(false)
                notifyStatus(false, errorDetail)

                // Auth-Fehler: kurze Serie zulassen, dann STOPP (Relay blockt
                // 5 Fehlversuche für 300 s — Endlos-Retry lockt die eigene IP,
                // exakt die Vorfallskette).
                if (httpCode == 401 || httpCode == 403) {
                    val streak = bumpAuthFailureStreak()
                    if (streak >= MAX_AUTH_FAILURES) {
                        setAuthBroken(true)
                        shouldReconnect = false
                        generation++
                        sessionStartedNs = 0L
                        this@RelayClient.webSocket?.cancel()
                        this@RelayClient.webSocket = null
                        notifyStatus(false, "Kopplungscode abgelehnt (HTTP $httpCode) — in der App neu koppeln")
                        return
                    }
                }
                // 429: Relay-Blockfenster abwarten, Budget NICHT verbrennen.
                if (httpCode == 429) {
                    scheduleReconnect(forceBackoffMs = BLOCK_WAIT_MS)
                    return
                }
                scheduleReconnect()
            }
        })
    }

    private fun logW(message: String) {
        Log.w(TAG, message)
    }

    // Invoked from OkHttp callback threads; the policy and the pending flag are
    // read-modify-written together, so serialize the whole decision.
    @Synchronized
    private fun scheduleReconnect(forceBackoffMs: Long? = null) {
        if (!shouldReconnect) return
        val url = serverUrl ?: return
        // Resolve the scope BEFORE burning budget or raising reconnectPending:
        // a late callback after disconnect() finds a null scope, and committing
        // that state with no coroutine to clear it would stall reconnects for good.
        val activeScope = scope ?: return

        // onClosed and onFailure can both fire for one dead socket; only one of
        // them should turn into an attempt. This must come BEFORE the exhausted
        // check: otherwise the second callback sees the budget the first one
        // just spent and declares failure while that final attempt is still
        // waiting out its backoff, silently skipping the last retry.
        if (reconnectPending) return

        // v0.11.0: NICHT mehr abbrechen nach Budgetverbrauch — Low-Rate-Modus
        // (endlos alle ~5 min neu verbinden) statt Terminalzustand.
        val enteringLowRate = reconnectPolicy.isExhausted

        val backoff = when {
            forceBackoffMs != null -> forceBackoffMs
            enteringLowRate -> LOW_RATE_BACKOFF_MS
            else -> reconnectPolicy.nextBackoffMs()
        }
        val attempt = reconnectPolicy.attempts
        val label = if (enteringLowRate) {
            maybeFireTermuxRevival()
            "Wiederverbindung fährt im 5-min-Modus"
        } else {
            "Neuverbindung"
        }

        if (backoff >= ALARM_THRESHOLD_MS) {
            // Doze-sicher: Alarm legen statt Coroutine-Sleep.
            // Final-Fix (K1): Der "pending"-Merker kommt erst NACH einer
            // erfolgreichen Alarm-Planung rein — scheitert die Planung
            // (OEM-Drossel, SecurityException, Kontext fehlt), fällt dieser
            // Zweig auf den Coroutine-Versuch unten zurück, statt einen
            // pending-Merker OHNE jeden geplanten Versuch zu hinterlassen
            // (stiller Verbindungstod der v0.11.0-Klasse).
            if (scheduleDozeAlarm(backoff)) {
                reconnectPending = true
                reconnectJob?.cancel()
                reconnectJob = null
                notifyStatus(false, "$label: nächster Versuch in ${backoff / 60_000} min.")
                return
            }
            Log.w(TAG, "Doze-Alarm nicht planbar — Rückfall auf Coroutine-Warte (${backoff}ms)")
        }

        reconnectPending = true
        reconnectJob = activeScope.launch {
            Log.i(TAG, "Reconnecting in ${backoff}ms... (attempt $attempt/${reconnectPolicy.limit})")
            notifyStatus(false, "$label in ${backoff / 1000}s (Versuch $attempt/${reconnectPolicy.limit})")
            delay(backoff)
            beginReconnectAttempt(url, PairingManager.getCode())
        }
    }

    /**
     * Doze-sicherer Weckruf: setAndAllowWhileIdle (ohne SCHEDULE_EXACT_ALARM-
     * Pflicht; Ungenauigkeit genügt beim 5-min-Takt). Empfänger → [reconnectNow]
     * bzw. bei probe=true → [authProbe]. requestCode trennt die zwei Alarmarten.
     */
    private fun scheduleDozeAlarm(delayMs: Long, probe: Boolean = false): Boolean {
        val ctx = appContext
        if (ctx == null) {
            Log.w(TAG, "Alarm ohne appContext unplanbar")
            return false
        }
        return runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, if (probe) 2 else 1,
                Intent(ctx, ReconnectAlarmReceiver::class.java)
                    .putExtra(ReconnectAlarmReceiver.EXTRA_AUTH_PROBE, probe),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi
            )
            true
        }.onFailure { Log.w(TAG, "Alarm nicht planbar: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Clearing [reconnectPending] and starting the attempt must happen as one
     * step under the same monitor as [scheduleReconnect]; clearing it earlier
     * lets a callback slip through and launch a second reconnect coroutine.
     */
    @Synchronized
    private fun beginReconnectAttempt(url: String, code: String) {
        reconnectPending = false
        if (!shouldReconnect || authBroken || isConnected) return
        // Supersede the old listener BEFORE cancelling its socket: cancel()
        // drives that listener's onFailure, and if it still matched the current
        // generation it would re-enter scheduleReconnect and burn an attempt on
        // our own teardown.
        generation++
        // Retired listener => no endSession() for it; drop the clock so the
        // next session is measured from its own start, not this one's.
        sessionStartedNs = 0L
        // Cancel the previous WebSocket before opening a new one, otherwise its
        // listener stays active and can fire out-of-order callbacks
        // (onOpen/onFailure) that set isConnected or push duplicate statuses.
        webSocket?.cancel()
        // Code IMMER frisch aus der SSOT (UI-Regenerate wirkt sofort).
        doConnect(url, PairingManager.getCode().ifBlank { code })
    }

    /** A session that had opened is over — refill the budget only if it was stable. */
    private fun endSession() {
        val startedNs = sessionStartedNs
        if (startedNs == 0L) return
        sessionStartedNs = 0L
        reconnectPolicy.onSessionEnded((System.nanoTime() - startedNs) / 1_000_000L)
    }

    /**
     * Termux revival watchdog: after repeated relay connection failures
     * (Termux stack likely dead), fire the RUN_COMMAND intent to restart
     * the whole stack via start-zee.sh. Cooldown prevents loops.
     */
    private fun maybeFireTermuxRevival() {
        val p = prefs ?: return
        val enabled = p.getBoolean(KEY_REVIVAL_ENABLED, false)
        val failures = p.getInt(KEY_FAILURES, 0)
        val lastFire = p.getLong(KEY_LAST_FIRE, 0L)
        val now = System.currentTimeMillis()
        when (val d = revivalPolicy.onFailure(enabled, failures, lastFire, now)) {
            is RevivalPolicy.Decision.Wait -> {
                p.edit().putInt(KEY_FAILURES, d.failures).apply()
                if (d.failures > failures) {
                    Log.i(TAG, "Revival watchdog: ${d.failures}/$WATCHDOG_FAILURES_TO_TRIGGER failures")
                }
            }
            is RevivalPolicy.Decision.Fire -> {
                p.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LAST_FIRE, now).apply()
                fireTermuxRestart()
            }
        }
    }

    private fun fireTermuxRestart() {
        val ctx = appContext ?: return
        try {
            val intent = android.content.Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/home/.termux/boot/start-zee.sh")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 0)
            }
            ctx.startService(intent)
            Log.i(TAG, "Termux revival: RUN_COMMAND fired (start-zee.sh)")
            notifyStatus(false, "Stack nicht erreichbar — Termux-Revival ausgelöst …")
            // The watchdog fired but the app itself must keep trying to
            // reconnect: without this the relay stays dark even after a
            // successful revival (review point: "fires but never reconnects").
            reconnectDelayed()
        } catch (e: SecurityException) {
            Log.e(TAG, "Termux revival BLOCKED: RUN_COMMAND permission missing", e)
            notifyStatus(false, "Revival blockiert — Termux RUN_COMMAND fehlt als Erlaubnis")
        } catch (e: Exception) {
            Log.e(TAG, "Termux revival intent failed", e)
            notifyStatus(false, "Revival fehlgeschlagen: ${e.message?.take(80) ?: "unbekannt"}")
        }
    }

    /**
     * v0.11.0-Fix (Review: Selbst-Cancel-Falle). Bisher lief der 30s-Delay im
     * Verbindungs-Scope und riss connect() beim eigenen Cleanup mit ab. Jetzt
     * läuft er im eigenen Watchdog-Scope.
     */
    private fun reconnectDelayed() {
        try {
            val url = prefs?.getString(KEY_SERVER_URL, null)
            val code = PairingManager.getCode()
            if (url.isNullOrBlank() || code.isBlank()) {
                Log.w(TAG, "Delayed reconnect skipped: no saved server/code")
                return
            }
            if (watchdogScope == null) {
                watchdogScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            }
            watchdogScope?.launch {
                kotlinx.coroutines.delay(30_000L)  // 30s later, bounded by the same cooldown
                if (shouldReconnect && !isConnected) connect(url, code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delayed reconnect not scheduled: $e")
        }
    }

    private fun buildWsUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val useTls = trimmed.startsWith("https://") || trimmed.startsWith("wss://")
        var base = trimmed
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://")
        // v0.12.3-Fix (Sirs Kopplungs-Befund 01.11.): TLS-Route NIE Port anbauen.
        // https://relay.example.com = Reverse-Proxy auf 443; der Port-8766-Anbau
        // schlug im Handshake fehl (403 vom Proxy) = die Kopplung starb laut.
        if (!useTls && !base.contains(":")) {
            base = "$base:8766"   // Plaintext-LAN: Standard-Port bleibt
        }
        val scheme = if (useTls) "wss" else "ws"
        if (BuildConfig.DEBUG) Log.d(TAG, "Built WebSocket URL: $scheme://$base/ws")
        return "$scheme://$base/ws"
    }

    private suspend fun handleMessage(ws: WebSocket, text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val requestId = json.get("request_id")?.asString ?: ""
            val method = json.get("method")?.asString?.uppercase() ?: "GET"
            val path = json.get("path")?.asString ?: ""
            val params = json.getAsJsonObject("params") ?: JsonObject()
            val body = json.getAsJsonObject("body") ?: JsonObject()

            if (BuildConfig.DEBUG) Log.d(TAG, "Received command: $method $path (id=$requestId)")

            if (requestId.isBlank()) {
                throw IllegalArgumentException("Command is missing request_id")
            }
            if (method == "GET" && path == "/mic_file") {
                // v0.10.8 (Review-Blocking-2-Fix): Gate-Prüfung vor dem Stream
                // (die Route umgeht den Dispatcher, daher hier manuell).
                CapabilityGate.checkEndpoint(method, path)?.let { msg ->
                    sendCommandResult(ws, requestId, mapOf("error" to msg), status = 403)
                    return
                }
                streamMicrophoneRecording(
                    ws,
                    requestId,
                    params.get("name")?.asString,
                )
                return
            }
            if (method == "GET" && path == "/file") {
                streamDeviceFile(
                    ws,
                    requestId,
                    params.get("path")?.asString ?: "",
                )
                return
            }
            if (method == "GET" && (path == "/files" || path == "/files_search" || path == "/files_count")) {
                // Capability-Gate auch hier — /files* laufen NUR über den WS-Pfad.
                CapabilityGate.checkEndpoint(method, path)?.let { msg ->
                    sendCommandResult(ws, requestId, mapOf("error" to msg), status = 403)
                    return
                }
            }
            // v0.11.0: Health-Connect (POST /health_data) — Gate wie bei den
            // Datei-Routen manuell vorab (Dispatcher-Umgehung wie Streams).
            if (method == "POST" && path == "/health_data") {
                CapabilityGate.checkEndpoint(method, path)?.let { msg ->
                    sendCommandResult(ws, requestId, mapOf("error" to msg), status = 403)
                    return
                }
                sendCommandResult(ws, requestId, HealthDataBridge.handleRequest(params), status = 200)
                return
            }

            // The relay connection is authenticated at connect time (Bearer token on the WS handshake),
            // so commands arriving here are already authenticated.
            val response = CommandDispatcher.dispatch(method, path, params, body, authenticated = true)

            val responseJson = JsonObject().apply {
                addProperty("request_id", requestId)
                add("result", gson.toJsonTree(response.first))
                addProperty("status", response.second)
            }
            ws.send(responseJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}", e)
            try {
                val json = JsonParser.parseString(text).asJsonObject
                val requestId = json.get("request_id")?.asString ?: ""
                val errorResponse = JsonObject().apply {
                    addProperty("request_id", requestId)
                    add("result", gson.toJsonTree(mapOf("error" to e.message)))
                    addProperty("status", 500)
                }
                ws.send(errorResponse.toString())
            } catch (_: Exception) {}
        }
    }

    /**
     * Streamt eine Datei aus dem gemeinsamen Speicher (Capability "files") über
     * das WebSocket-Streaming-Protokoll (start/chunks/end wie mic_file).
     * MIME-Typ anhand der Endung, SHA-256 mitgeliefert.
     */
    private suspend fun streamDeviceFile(
        ws: WebSocket,
        requestId: String,
        relativePath: String,
    ) {
        // Capability-Gate auch hier (Dispatcher umgeht den Normalpfad absichtlich)
        CapabilityGate.checkEndpoint("GET", "/file")?.let { msg ->
            sendCommandResult(ws, requestId, mapOf("error" to msg), status = 403)
            return
        }
        val resolved = withContext(Dispatchers.IO) {
            DeviceFiles.resolveForRead(relativePath)
        }
        if (resolved == null) {
            sendCommandResult(
                ws,
                requestId,
                mapOf("error" to "Datei nicht lesbar: $relativePath (nicht gefunden, Ordner oder zu groß)"),
                status = 404,
            )
            return
        }
        val (file, fileName) = resolved

        val startMessage = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("status", 200)
            add("stream", JsonObject().apply {
                addProperty("event", "start")
                addProperty("filename", fileName)
                addProperty("mimeType", mimeForName(fileName))
                addProperty("size", file.length())
            })
        }
        if (!ws.send(startMessage.toString())) return

        val digest = MessageDigest.getInstance("SHA-256")
        var bytesSent = 0L
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    while (ws.queueSize() > 1024L * 1024L) {
                        delay(10L)
                    }
                    digest.update(buffer, 0, read)
                    if (!ws.send(buildStreamFrame(requestId, buffer, read))) {
                        throw IllegalStateException("WebSocket rejected file stream data")
                    }
                    bytesSent += read
                }
            }

            ws.send(buildStreamEnd(requestId, bytesSent, digest))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ws.send(buildStreamError(requestId, "File stream fehlgeschlagen (${error.javaClass.simpleName})"))
        }
    }

    private suspend fun streamMicrophoneRecording(
        ws: WebSocket,
        requestId: String,
        requestedName: String?,
    ) {
        val file = MicrophoneRecordingFiles.resolve(
            BridgeApplication.instance,
            requestedName,
        )
        if (file == null) {
            sendCommandResult(
                ws,
                requestId,
                mapOf("error" to "Aufnahme nicht gefunden"),
                status = 404,
            )
            return
        }

        val startMessage = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("status", 200)
            add("stream", JsonObject().apply {
                addProperty("event", "start")
                addProperty("filename", file.name)
                addProperty("mimeType", "audio/wav")
                addProperty("size", file.length())
            })
        }
        if (!ws.send(startMessage.toString())) return

        val digest = MessageDigest.getInstance("SHA-256")
        var bytesSent = 0L
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    while (ws.queueSize() > 1024L * 1024L) {
                        delay(10L)
                    }
                    digest.update(buffer, 0, read)
                    if (!ws.send(buildStreamFrame(requestId, buffer, read))) {
                        throw IllegalStateException("WebSocket rejected microphone stream data")
                    }
                    bytesSent += read
                }
            }

            ws.send(buildStreamEnd(requestId, bytesSent, digest))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ws.send(buildStreamError(requestId, "Mikrofon-Stream fehlgeschlagen (${error.javaClass.simpleName})"))
        }
    }

    /** Stream-End-Frame (SHA-256 hex + Byte-Anzahl). */
    private fun buildStreamEnd(
        requestId: String,
        bytesSent: Long,
        digest: MessageDigest,
    ): String {
        val endMessage = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("status", 200)
            add("stream", JsonObject().apply {
                addProperty("event", "end")
                addProperty("bytes", bytesSent)
                addProperty(
                    "sha256",
                    digest.digest().joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 0xff)
                    },
                )
            })
        }
        return endMessage.toString()
    }

    /** Stream-Error-Frame. */
    private fun buildStreamError(requestId: String, message: String): String {
        val errorMessage = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("status", 500)
            add("stream", JsonObject().apply {
                addProperty("event", "error")
                addProperty("message", message)
            })
        }
        return errorMessage.toString()
    }

    /** MIME nach Dateiendung — eine Stelle, für Datei- und Mikro-Streams gleich. */
    private fun mimeForName(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "txt", "log", "csv" -> "text/plain"
            "zip", "apk" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "mp4", "mov", "mkv", "webm" -> "video/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun buildStreamFrame(
        requestId: String,
        payload: ByteArray,
        payloadLength: Int,
    ): okio.ByteString {
        val requestIdBytes = requestId.toByteArray(Charsets.UTF_8)
        require(requestIdBytes.size in 1..128) { "request_id is too long" }
        require(payloadLength in 0..payload.size) { "Invalid payload length" }

        val frame = ByteArray(2 + requestIdBytes.size + payloadLength)
        frame[0] = ((requestIdBytes.size ushr 8) and 0xff).toByte()
        frame[1] = (requestIdBytes.size and 0xff).toByte()
        requestIdBytes.copyInto(frame, destinationOffset = 2)
        payload.copyInto(
            frame,
            destinationOffset = 2 + requestIdBytes.size,
            endIndex = payloadLength,
        )
        return frame.toByteString()
    }

    private fun sendCommandResult(
        ws: WebSocket,
        requestId: String,
        result: Any,
        status: Int,
    ) {
        val response = JsonObject().apply {
            addProperty("request_id", requestId)
            add("result", gson.toJsonTree(result))
            addProperty("status", status)
        }
        ws.send(response.toString())
    }

    /** Status an alle Hörer (Service-Notification, UI) — immer am Main-Thread. */
    private fun notifyStatus(connected: Boolean, message: String) {
        currentStatusMessage = message
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                for (listener in statusListeners) {
                    runCatching { listener(connected, message) }
                }
                onStatusChanged?.let { callback ->
                    runCatching { callback(connected, message) }
                }
            }
        } catch (_: Exception) {}
    }
}

/**
 * BroadcastReceiver für doze-sichere Low-Rate-Reconnects: AlarmManager weckt
 * den Prozess, hier läuft ein einzelner Verbindungsversuch (idempotent —
 * doppelte/dudelige Alarms erzeugen keinen Flap).
 */
class ReconnectAlarmReceiver : BroadcastReceiver() {
    /** Extras-Flag: Sonde (AuthProbe) statt Regular-Reconnect. */
    companion object {
        const val EXTRA_AUTH_PROBE = "auth_probe"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_AUTH_PROBE, false)) {
            Log.i("ReconnectAlarm", "Auth-Sonde kam — Kopplungstest")
            RelayClient.authProbe()
        } else {
            Log.i("ReconnectAlarm", "Doze-Alarm kam — Verbindungsversuch")
            RelayClient.reconnectNow()
        }
        // Service anheben, falls er inzwischen verstorben ist (FGS-Anzeige).
        if (BridgeConnectionService.instance == null) {
            // v0.12.0 (Akku P2-1): nur je Start, wenn verstorben — sonst
            // startForegroundService-Call + FGS-Neu-Anmeldung alle 5 min ohnehin.
            BridgeConnectionService.start(context)
        }
    }
}