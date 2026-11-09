package com.hermesandroid.bridge.client

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.crypto.AndroidKeystoreIdentitySealer
import com.hermesandroid.bridge.crypto.CryptoEngine
import com.hermesandroid.bridge.crypto.IdentityStore
import com.hermesandroid.bridge.crypto.PrefsIdentityStore
import com.hermesandroid.bridge.crypto.PhoneCryptoPeer
import com.hermesandroid.bridge.event.EventStore
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import com.hermesandroid.bridge.chat.ChatHistoryStore
import com.hermesandroid.bridge.files.DeviceFiles
import com.hermesandroid.bridge.media.MediaAssembler
import com.hermesandroid.bridge.media.MediaChunker
import com.hermesandroid.bridge.media.MediaException
import com.hermesandroid.bridge.media.MediaProtocol
import com.hermesandroid.bridge.security.CapabilityGate
import com.hermesandroid.bridge.security.MtlsSupport
import com.hermesandroid.bridge.server.CommandDispatcher
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeConnectionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update  // SEC-W1 W1C-4: atomare CAS-Updates
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

    // ── PH-FIX-CHAT-A (F4): Chat-Benachrichtigungen ──────────────────────
    // Eigener HIGH-Importance-Kanal — die FGS-Dauernotification
    // (BridgeConnectionService) ist IMPORTANCE_LOW + setOngoing und darf
    // als einzige laufende Notification bei Chat-Empfang nicht mitmischen.
    private const val CHAT_NOTIFY_CHANNEL = "fb_chat"

    /** Basis der Notification-IDs (plus ts % 100k — mehrere pro Tag kollidieren nie). */
    private const val CHAT_NOTIFY_ID_BASE = 9000
    /** PH-FIX-BACKEND-B: Backoff je hello_ack-Retry (failures × Basis, 1 s / 2 s). */
    private const val HELLO_ACK_RETRY_BACKOFF_MS = 1_000L

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
            // PH-FIX-CHAT-A (F6, 30-s-Keepalive): WS-Ping-Frames (opcode 0x9)
            // im 30-s-Raster — Spiegel zum 30-s-Keepalive-Raster der Agenten.
            // Bewusst Protokoll-Ping statt App-Frame: der Relay allowlistet
            // {"type":"keepalive"} nur auf der AGENTEN-WS; ein Phone-Frame
            // ohne request_id erzeugt im Journal eine rate-limited Warnung
            // je 30 s (Log-Rauschen, PIPELINE-Doc „Kosmetik“-Notiz), WS-Pings
            // beantwortet aiohttp still (autoping). Wirkung: NAT bleibt
            // offen, halboffene Sockets (Doze/Netzwechsel, die 3 Live-
            // Disconnects 02.11.) entlarven sich ~2× schneller als bei 45 s.
            // (v0.12.0 Akku P1-1 hatte 20→45 s erhöht, weil der Relay eh alle
            // 15 s heartbeatet — die App-EIGENE Toterkennung war der Engpass.)
            .pingInterval(java.time.Duration.ofSeconds(30))
            // W4-01b (W3-03, App-Lane A): SPKI-PINNING VOR decorate() — der
            // Pin-Verband hängt am Client, BEVOR MtlsSupport.decorate() über
            // newBuilder() das mTLS-Socket einbaut (OkHttp newBuilder() erbt
            // certificatePinner — decorate() kann das Pinning nicht umgehen).
            // Nur der eigene Relay-Host ist gepinnt (PinSource/SF5); Nutzer-
            // Relays laufen hostname-only. Debug-Kill-Switch für Drills: siehe
            // PinSource.setPinningDisabledForDrill (nimmt NUR die PIN-Schicht).
            .apply { PinSource.pinner()?.let { certificatePinner(it) } }
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

    /**
     * Strukturierte Verbindungs-Phase (PH1-T3, v0.12.5): die UI leitet daraus
     * BridgeStatus.ConnectionState ab — KEIN String-Matching auf Status-Texten
     * mehr. notifyStatus setzt sie, bevor die Hörer gerufen werden.
     */
    enum class Phase { IDLE, CONNECTING, CONNECTED, RECONNECTING, PAIRING_BROKEN, FAILED }

    /** Aktuelle Verbindungs-Phase — strukturierter Zustand für die UI. */
    @Volatile
    var currentPhase: Phase = Phase.IDLE
        private set

    /**
     * W4-D (Onboarding-Fehler-Taxonomie, je Verband: Wizard-Schritt 2):
     * HTTP-Code des LETZTEN WS-Upgrade-Fehlers —
     *   401/403 = „Code falsch“, 429 = „Relay kurz gesperrt“, 0 = Timeout/Netz
     *   („Relay nicht erreichbar“), -1 = kein Fehler (Reset je onOpen/connect).
     * Strukturiertes Signal statt Text-Grep (PH1-T3-Muster); gesetzt in
     * onFailure, geräumt in onOpen und connect(). Nur-Lesen für die UI.
     */
    @Volatile
    var lastFailureHttpCode: Int = -1
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * PH-FIX-CHAT-A (F4): agentId der OFFENEN Conversation — ChatScreen
     * setzt ihn via DisposableEffect beim Betreten der Conversation und
     * räumt beim Verlassen. Zusammen mit [MainActivity.isForeground]
     * unterdrückt es Benachrichtigungs-Spam für die Unterhaltung, die Sir
     * gerade liest (App im Hintergrund + Chat offen = Notification trotzdem
     * gewollt).
     */
    @Volatile
    var chatVisibleAgentId: String? = null

    // ── PH2-MERGE: Chat-Daten-Modelle + StateFlows (UI bindet live daran) ────

    /**
     * PH2-MERGE: Agenten-Eintrag aus dem relay.state-Hallo-Frame des Relays
     * ({agent_id, display_name, online} je Agent). Fehlt das agents-Feld,
     * bleibt die Liste leer → ChatScreen zeigt seinen Demo-Fallback.
     */
    data class AgentEntry(
        val agentId: String,
        val displayName: String,
        val online: Boolean,
    )

    /** PH2-MERGE: Eingehende Agenten-Antwort (chat.event-Frame des Relays).
     * PH2-T11: elapsedMs = Empfang→Antwort (vom Adapter gemessen, optional). */
    data class ChatMessageEvent(
        val chatId: String,
        val agentId: String,
        val text: String,
        val ts: Long,
        val elapsedMs: Long? = null,
    )

    /**
     * PH2-T10 (F2-S1/S2): Zustell-/Lese-Status je eigener Nachricht —
     * chat.status-Frames des Relays (queued/delivered/read), Key = msg_id
     * (die App bekommt ihn aus der POST-/chat-Antwort zurück).
     */
    data class ChatStatusEvent(
        val msgId: String,
        val chatId: String,
        val status: String,
        val ts: Long,
    )

    /**
     * PH2-T10 (F2-S3): Arbeitsstatus je Agent — chat.activity-Frames des
     * Relays (je Wechsel 1×, serverseitig dedupliziert). startedAt =
     * Empfangszeit der Nachricht beim Agenten (Unix-ms) für die mm:ss-Anzeige.
     */
    data class AgentActivity(
        val agentId: String,
        val chatId: String,
        val working: Boolean,
        val startedAt: Long,
    )

    // ── MEDIA-T1: Medien-/Datei-Versand im Chat (chunked, E2E-only) ───────

    /**
     * MEDIA-T1: Vollständig empfangenes Medium vom Agenten (chat.media.enc
     * Chunks reassembliert, Datei liegt app-privat unter filesDir/chat_media).
     * Die UI hängt es als Media-Bubble in den Verlauf (kein Text-Ersatz).
     */
    data class ChatMediaEvent(
        val chatId: String,
        val agentId: String,
        val mediaId: String,
        val kind: String,
        val name: String,
        val mime: String,
        val size: Long,
        val localPath: String,
        val ts: Long,
    )

    /**
     * MEDIA-T1: Upload-Zustand je media_id (eigene Medien-Bubble zeigt den
     * Fortschritt). sentChunks = vom Relay angenommene Chunks, ackedChunks =
     * vom Agenten quittierte Chunks (chat.media.chunk_ack), failed != null =
     * endgültig gescheitert (Chunk-POST abgelehnt).
     */
    data class MediaUploadState(
        val agentId: String,
        val kind: String,
        val name: String,
        val totalChunks: Int,
        val sentChunks: Int = 0,
        val ackedChunks: Int = 0,
        val failed: String? = null,
        val done: Boolean = false,
    )

    /** Obergrenze des Empfangs-Puffers (Chat-Verlauf ist kein Dauerarchiv). */
    private const val MAX_CHAT_EVENTS = 200

    /** Obergrenze der Status-Map (Status ist transient, kein Archiv). */
    private const val MAX_CHAT_STATUS = 500

    /** Agenten-Liste (relay.state) — letzte Meldung des Relays. */
    private val _agents = MutableStateFlow<List<AgentEntry>>(emptyList())
    val agents: StateFlow<List<AgentEntry>> = _agents.asStateFlow()

    /** Empfangene Agenten-Antworten (chat.event) in Empfangs-Reihenfolge. */
    private val _chatEvents = MutableStateFlow<List<ChatMessageEvent>>(emptyList())
    val chatEvents: StateFlow<List<ChatMessageEvent>> = _chatEvents.asStateFlow()

    /** PH2-T10: Zustell-/Lese-Status je msg_id (queued/delivered/read). */
    private val _chatStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val chatStatus: StateFlow<Map<String, String>> = _chatStatus.asStateFlow()

    /** PH2-T10: Arbeitsstatus je agent_id (letzter chat.activity-Frame). */
    private val _agentWorking = MutableStateFlow<Map<String, AgentActivity>>(emptyMap())
    val agentWorking: StateFlow<Map<String, AgentActivity>> = _agentWorking.asStateFlow()

    /** MEDIA-T1: Empfangene Agenten-Medien (vollständig reassembliert). */
    private val _chatMediaEvents = MutableStateFlow<List<ChatMediaEvent>>(emptyList())
    val chatMediaEvents: StateFlow<List<ChatMediaEvent>> = _chatMediaEvents.asStateFlow()

    /** MEDIA-T1: Upload-Fortschritt je media_id (eigene Medien-Bubbles). */
    private val _mediaUploads = MutableStateFlow<Map<String, MediaUploadState>>(emptyMap())
    val mediaUploads: StateFlow<Map<String, MediaUploadState>> = _mediaUploads.asStateFlow()

    // ── PH-3 Welle 2: E2E-Verschlüsselung (fb-v1, Double Ratchet) ──────────

    /**
     * PH-3: aktive E2E-Sitzung je Agent — Anzeige in Einstellungen →
     * Verschlüsselung (Fingerprint-Hex 8×4, read-only).
     */
    data class E2eSessionInfo(
        val agentId: String,
        val sessionId: String,
        val chatId: String,
        val fingerprintHex: String,
        val fingerprintDisplay: String,
        val agentFingerprintDisplay: String,
    )

    /** Aktive E2E-Sitzungen (agentId → Info; leer = Klartext v0.14-kompatibel). */
    private val _e2eSessions = MutableStateFlow<Map<String, E2eSessionInfo>>(emptyMap())
    val e2eSessions: StateFlow<Map<String, E2eSessionInfo>> = _e2eSessions.asStateFlow()

    /**
     * PH-3: rot-Banner (Fingerprint-ABWEICHUNG / Session-Reset) — null = keins.
     * Chat + Settings binden daran; Abweichung = möglicher Man-in-the-Middle.
     */
    private val _e2eBanner = MutableStateFlow<String?>(null)
    val e2eBanner: StateFlow<String?> = _e2eBanner.asStateFlow()

    /** agentId → aktive (hello_ack-verifizierte) E2E-Session. Keys nur Heap. */
    private val cryptoSessions = ConcurrentHashMap<String, PhoneCryptoPeer>()

    /** session_id → Peer, der auf hello_ack wartet (Wrong-Key = verwerfen). */
    private val pendingCryptoPeers = ConcurrentHashMap<String, PhoneCryptoPeer>()

    /** agentId → Handshake läuft gerade (kein Doppel-Hellos). */
    private val handshakesInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * PH-FIX-BACKEND-B (Bug-1-Härtung): agentId → hello_ack-Verify-Fehlschläge
     * (max. HelloAckFailurePolicy.MAX_RETRIES Retries, danach Banner).
     * Zurückgesetzt je erfolgreichem consumeAck + je frischem connect()-Zyklus.
     */
    private val helloAckFailures = ConcurrentHashMap<String, Int>()

    /** Phone-Identity (X25519, aus dem Keystore-sealed IdentityStore geladen). */
    @Volatile
    private var phoneIdentity: CryptoEngine.X25519KeyPair? = null

    @Volatile
    private var phonePrekey: CryptoEngine.X25519KeyPair? = null

    @Volatile
    private var identityReady: Boolean = false

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

    /** Kopplung kaputt? (UI-Zugriff) */
    fun isAuthBroken(): Boolean = authBroken

    /**
     * JÄGER-J10 (P2): true = Sir hat BEWUSST getrennt (disconnect()) — der
     * ReconnectAlarmReceiver darf dann KEINEN FGS-Zombie hochholen.
     */
    fun isUserDisconnected(): Boolean = userDisconnected

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

    /**
     * JÄGER-J10 (P2, JAEGE-RFULL-20261102): geplanten Reconnect-Weckruf
     * (requestCode 1) canceln. disconnect() räumte bisher NUR die AuthProbe
     * (requestCode 2) — ein vor dem Trennen geplanter 5-min/429-Alarm überlebte
     * das bewusste Trennen. reconnectNow() ist korrekt No-op, aber der Receiver
     * hob den FGS („Getrennt“-Notification) zurück = Zombie gegen Sirs
     * ausdrücklichen Trenn-Wunsch.
     */
    private fun cancelReconnectAlarm() {
        val ctx = appContext ?: return
        runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(
                PendingIntent.getBroadcast(
                    ctx, 1,
                    Intent(ctx, ReconnectAlarmReceiver::class.java),
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

        // W4-D (Multi-Server-Profil-Wechsel + Onboarding-Wizard): ein abweichend
        // mitgelieferter Code wird BEWUSST in die EINE SSOT adoptiert
        // (PairingManager.adoptActiveCode) — der Singleton-Pärchen-Verband
        // (PairingManager/RelayClient/MtlsSupport) folgt dem AKTIVEN Profil,
        // doConnect liest weiter frisch aus der SSOT. Bestehende Aufrufer
        // übergeben PairingManager.getCode() → Adoption ist ein No-op-Write.
        if (pairingCode.isNotBlank() && pairingCode.trim() != PairingManager.getCode()) {
            PairingManager.adoptActiveCode(pairingCode, "relay_connect")
        }
        // Fehler-Taxonomie des VORHERIGEN connect-Zyklus räumen (Wizard-Hilfe).
        lastFailureHttpCode = -1

        // v0.10.8 (Review-Blocking-3-Fix): Merker für die Capability-Migration —
        // wer je eine Relay-Verbindung einrichtet, hat "vor dem Gating" genutzt.
        prefs?.edit()?.putBoolean("legacy_had_relay", true)?.apply()

        // W4-01b (W3-03, App-Lane A): Info-Merker `pins_installed` — true,
        // wenn die konfigurierte Relay-URL den gepinnten Host trägt. Die Info
        // bleibt an die Verbindungs-ÜBERNAHME gebunden (je connect neu
        // geschrieben) — Profil-Wechsel auf ein Nutzer-Relay (ohne Pins,
        // SF5) setzt ihn korrekt auf false. Keine Verhaltens-Auswirkung —
        // nur ehrliche Status-/Diagnose-Auslage (Verbindungs-/Zertifikats-
        // Status-UI = W4-APP-14-Lane).
        prefs?.edit()
            ?.putBoolean(PinSource.PREF_PINS_INSTALLED, PinSource.isPinnedHost(serverUrl))
            ?.apply()

        this.serverUrl = serverUrl
        // Bewusste Neu-Kopplung: gesperrten Auth-Zustand räumen — der User gibt
        // den Code bewusst eine Chance (SSOT macht den Code ohnehin aktuell).
        setAuthBroken(false)
        userDisconnected = false
        clearAuthFailureStreak()
        shouldReconnect = true
        reconnectPolicy.reset()
        // PH-3 Welle 2: frische E2E-Sessions je WS-Connect (Session-Rotation je
        // Verbindung, Spec §2.5 — Session-Keys leben nur im RAM).
        cryptoSessions.clear()
        pendingCryptoPeers.clear()
        // PH-FIX-BACKEND-B: frisches Retry-Budget je Connect-Zyklus (Banner
        // oben wird ohnehin genullt — ein Reconnect ist die User-Heilung).
        helloAckFailures.clear()
        _e2eBanner.value = null
        publishE2eSessions()

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
        // JÄGER-J10: auch den Reconnect-Weckruf (requestCode 1) canceln —
        // sonst feuerte ein vor dem Trennen geplanter 5-min/429-Alarm nach
        // dem bewussten Trennen und holte den FGS zurück (Zombie-Notification).
        cancelReconnectAlarm()
        reconnectPolicy.reset()
        sessionStartedNs = 0L
        generation++
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        scope?.cancel()
        scope = null
        isConnected = false
        // PH-FIX-CHAT-A (F5): bewusstes Trennen räumt die working-States
        // ebenso — ohne Verbindung kann kein Agent „arbeiten“.
        _agentWorking.value = emptyMap()
        // PH-FIX-CHAT-A (F6): bewusstes Trennen markiert die Agenten ebenso
        // offline (onClosed läuft hier nicht — disconnect() bumpt generation,
        // die Callbacks kehren früh zurück).
        markAgentsOffline()
        // PH-3 Welle 2: Session-Keys verwerfen (RAM-only; frischer Handshake je
        // Verbindung — Spec §2.5 Rotation je Session).
        cryptoSessions.clear()
        pendingCryptoPeers.clear()
        publishE2eSessions()
        BridgeAccessibilityService.instance?.stopForeground()
        notifyStatus(false, "Verbindung geschlossen", Phase.IDLE)
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
        notifyStatus(false, "Verbinde mit Relay ...", Phase.CONNECTING)

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
            notifyStatus(false, "Server-Adresse ungültig — Format: https://dein-relay.example", Phase.FAILED)
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
                // W4-D: Fehler-Taxonomie-Räumer — der Connect steht, der alte
                // HTTP-Fehler darf die Wizard-Hilfe nicht mehr triggern.
                lastFailureHttpCode = -1
                // success resets the revival watchdog + Auth-Fehlserie
                prefs?.edit()?.putInt(KEY_FAILURES, 0)?.apply()
                clearAuthFailureStreak()
                setAuthBroken(false)
                // NOT a policy reset: the budget is only refilled once this
                // session proves stable (see endSession), so a relay that
                // accepts and instantly drops us can't retry forever.
                sessionStartedNs = System.nanoTime()
                // PH-3 Welle 2: Identity laden (Keystore-sealed) + PUBLIC Keys via
                // /key_publish; E2E-Handshake startet mit dem relay.state (Agenten).
                publishIdentity()
                runCatching {
                    // v0.12.0 (Akku P1-3): KEINE zweite dauerhafte FGS-Promotion
                    // mehr — die Prozess-Priorität trägt der dataSync-FGS
                    // (BridgeConnectionService); die mediaProjection-Promotion
                    // übernimmt ScreenRecorder bei /screen_record selbst.
                }.onFailure { logW("A11y-Foreground-Promotion übersprungen: ${it.message}") }
                notifyStatus(true, "Verbunden mit Relay", Phase.CONNECTED)
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
                // PH-FIX-CHAT-A (F5): working-States sind bei toter Verbindung
                // verwaist (activity-Reset-Frames laufen ins TCP-Nirvana) —
                // räumen, sonst countet der Kopf-Spinner ewig weiter.
                _agentWorking.value = emptyMap()
                // PH-FIX-CHAT-A (F6): die Agenten-Liste ist der letzte
                // relay.state-Snapshot — ohne Verbindung ist JEDE „online“-
                // Anzeige gelogen (Presence-Push ist Ein-Weg ohne Spool).
                markAgentsOffline()
                endSession()
                // v0.12.0 (Akku P0-2): Leakschutz — ein gestorbener Stream darf
                // den Vollcapture-Zustand nicht dauerhaft stehen lassen.
                EventStore.highVolumeEvents = false
                EventStore.streamingEnabled = false
                // v0.12.0 QA-Fix (SK-02/CHEF-02): Service-QUELLE drosseln —
                // sonst bleibt der A11y-Service bis Reboot auf VOLLEM Set.
                BridgeAccessibilityService.instance?.applyEventThrottle(false)
                notifyStatus(false, "Getrennt (code=$code)", Phase.IDLE)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGeneration != generation) return
                val httpCode = response?.code ?: 0
                // W4-D (Onboarding-Fehler-Taxonomie): strukturierter Code für die
                // Wizard-Fehlerhilfe (401/403 → Code falsch, 429 → Sperre,
                // 0 → Timeout/Netz) — kein Text-Grep über Statusmeldungen.
                lastFailureHttpCode = httpCode
                // W4-01b (W3-03, App-Lane A): Pin-Verstoß als EIGENE FAILED-
                // Ursache (Contract „Pin-Verstoß als FAILED-Ursache",
                // WELLE4-APP-02) — ehrlicher Text in Statuszeile/Banner statt
                // des generischen Exception-Rauschens; Logcat behält die
                // volle Klasse (Sieb-Terme: CertificatePinner|OkHttp|RelayClient).
                // W4-D-Koexistenz: der strukturerte HTTP-Code (Wizard-Taxonomie)
                // bleibt — bei 0 mit SSLPeerUnverifiedException zeigt der
                // Wizard trotzdem die Pin-Ursache-Meldung im Statusbanner.
                val pinFailure = PinSource.pinFailureText(t)
                val errorDetail = pinFailure
                    ?: "Verbindungsfehler: ${t.javaClass.simpleName} (HTTP $httpCode)"
                if (pinFailure != null) {
                    Log.e(TAG, "CertificatePinner FAILURE — $pinFailure " +
                        "(Quelle: ${t.javaClass.simpleName}, HTTP $httpCode)")
                } else {
                    Log.e(TAG, "WebSocket failure: $errorDetail")
                }
                isConnected = false
                // PH-FIX-CHAT-A (F5): wie onClosed — verwaiste working-States
                // räumen (kein Spinner-Ewigzähler auf toter Verbindung).
                _agentWorking.value = emptyMap()
                // PH-FIX-CHAT-A (F6): wie onClosed — Snapshot „online“ ist
                // ohne Verbindung gelogen.
                markAgentsOffline()
                endSession()
                // v0.12.0 (Akku P0-2): Leakschutz — wie onClosed.
                EventStore.highVolumeEvents = false
                EventStore.streamingEnabled = false
                // v0.12.0 QA-Fix (SK-02/CHEF-02): Service-QUELLE drosseln.
                BridgeAccessibilityService.instance?.applyEventThrottle(false)
                notifyStatus(false, errorDetail, Phase.FAILED)

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
                        notifyStatus(false, "Kopplungscode abgelehnt (HTTP $httpCode) — in der App neu koppeln", Phase.PAIRING_BROKEN)
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
                notifyStatus(false, "$label: nächster Versuch in ${backoff / 60_000} min.", Phase.RECONNECTING)
                return
            }
            Log.w(TAG, "Doze-Alarm nicht planbar — Rückfall auf Coroutine-Warte (${backoff}ms)")
        }

        reconnectPending = true
        reconnectJob = activeScope.launch {
            Log.i(TAG, "Reconnecting in ${backoff}ms... (attempt $attempt/${reconnectPolicy.limit})")
            notifyStatus(false, "$label in ${backoff / 1000}s (Versuch $attempt/${reconnectPolicy.limit})", Phase.RECONNECTING)
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

    /**
     * PH-FIX-CHAT-A (F6): Agenten offline markieren — die Liste ist der
     * letzte relay.state-Snapshot, Presence-Push ist Ein-Weg OHNE Spool:
     * Stirbt die Phone-WS (onClosed/onFailure) oder trennt Sir bewusst,
     * bleibt der Header sonst auf „online“ eingefroren (Live-Befund
     * 02.11.: Agenten tagelang „online“ bei toter Verbindung). Der nächste
     * relay.state-Hallo-Frame (Reconnect) ersetzt die Liste ohnehin
     * vollständig. update{} = atomar (SEC-W1 W1C-4-Muster).
     */
    private fun markAgentsOffline() {
        _agents.update { current ->
            if (current.isEmpty()) current else current.map { it.copy(online = false) }
        }
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
            notifyStatus(false, "Stack nicht erreichbar — Termux-Revival ausgelöst …", Phase.RECONNECTING)
            // The watchdog fired but the app itself must keep trying to
            // reconnect: without this the relay stays dark even after a
            // successful revival (review point: "fires but never reconnects").
            reconnectDelayed()
        } catch (e: SecurityException) {
            Log.e(TAG, "Termux revival BLOCKED: RUN_COMMAND permission missing", e)
            notifyStatus(false, "Revival blockiert — Termux RUN_COMMAND fehlt als Erlaubnis", Phase.FAILED)
        } catch (e: Exception) {
            Log.e(TAG, "Termux revival intent failed", e)
            notifyStatus(false, "Revival fehlgeschlagen: ${e.message?.take(80) ?: "unbekannt"}", Phase.FAILED)
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

            // PH2-MERGE: Relay-Eigenframes zuerst — relay.state (Agenten-Liste,
            // als allererster Frame je Phone-Connect) und chat.event (Agenten-
            // Antwort). Sie tragen kein request_id und sind KEINE Kommandos.
            val frameType = runCatching {
                json.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            }.getOrNull()
            when (frameType) {
                "relay.state" -> { handleRelayStateFrame(json); return }
                "chat.event" -> { handleChatEventFrame(json); return }
                "chat.status" -> { handleChatStatusFrame(json); return }
                "chat.activity" -> { handleChatActivityFrame(json); return }
                // PH-3 Welle 2: E2E-Frames (Relay = opaker Router, ct wird nie geöffnet)
                "chat.event.enc" -> { handleChatEventEnc(json); return }
                "chat.read.enc" -> { handleChatReadEnc(json); return }
                "chat.activity.enc" -> { handleChatActivityEnc(json); return }
                // MEDIA-T1: chunked Media (E2E-only) + Chunk-Acks (Progress).
                "chat.media.enc" -> { handleChatMediaEnc(json); return }
                "chat.media.chunk_ack" -> { handleMediaChunkAck(json); return }
                "crypto.hello_ack" -> { handleCryptoHelloAck(json); return }
                "crypto.session_reset" -> { handleCryptoSessionReset(json); return }
            }

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

    // ── PH2-MERGE: Relay-Eigenframes (relay.state + chat.event) ────────────────

    /**
     * PH2-MERGE: relay.state-Hallo-Frame → [agents]-Flow. Fehlt das agents-
     * Feld oder ist es kein Array, bleibt die Liste leer (Demo-Fallback der
     * UI, kein Crash). Fehlen je Eintrag Felder, wird der Eintrag übersprungen
     * bzw. mit sinnvollen Defaults gefüllt (display_name = agent_id).
     */
    private fun handleRelayStateFrame(json: JsonObject) {
        val agentsArray = json.get("agents") as? JsonArray
        if (agentsArray == null) {
            _agents.value = emptyList()
            return
        }
        val parsed = agentsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val agentId = runCatching {
                obj.get("agent_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            }.getOrNull().orEmpty()
            if (agentId.isEmpty()) return@mapNotNull null
            val displayName = runCatching {
                obj.get("display_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            }.getOrNull().orEmpty().ifEmpty { agentId }
            val online = runCatching {
                obj.get("online")?.takeIf { it.isJsonPrimitive }?.asBoolean
            }.getOrNull() ?: false
            // PH-3 F7 (Jäger): session_active = WAHRHEIT des Relay-Session-
            // Brokers (je Agent). relay.state kommt bei JEDEM Phone-(Re)Connect —
            // hier holt die App ein verschlucktes crypto.session_reset nach:
            // sagt der Relay session_active=false, während lokal noch eine
            // aktive Session lebt, war das Reset-Frame auf der toten WS
            // verloren (kein Spool für Eigenframes). Session verwerfen +
            // rot-Banner statt weiter auf toten Keys zu vertrauen. Fehlt das
            // Feld (älterer Relay), bleibt das Verhalten unverändert (null).
            val sessionActive = runCatching {
                obj.get("session_active")?.takeIf { it.isJsonPrimitive }?.asBoolean
            }.getOrNull()
            if (E2eSessionTruth.missedReset(sessionActive, cryptoSessions[agentId]?.isActive() == true)) {
                Log.w(
                    TAG,
                    "PH-3 F7: Session für Agent ${agentId.take(24)} laut relay.state " +
                        "inaktiv — verschlucktes crypto.session_reset nachgeholt",
                )
                cryptoSessions.remove(agentId)
                publishE2eSessions()
                _e2eBanner.value =
                    "E2E-Sitzung wurde serverseitig zurückgesetzt — bitte neu verbinden."
            }
            // PH-FIX-BACKEND-B (Bug-1-Spiegel-Branch): Relay hält eine aktive
            // E2E-Session (vom Agent geackt), das Phone hat LOKAL KEINE →
            // Deadlock-Zustand: enc unmöglich (keine Keys), Klartext = 403-
            // Downgrade-Verwurf am Relay. Neu-Handshake erzwingen; scheitert
            // der erneut an der KDF, läuft er in Banner/Retry statt Stillstand.
            if (E2eSessionTruth.needsHandshake(sessionActive, cryptoSessions[agentId]?.isActive() == true)) {
                Log.w(
                    TAG,
                    "PH-FIX-B: Relay hält aktive E2E-Session für Agent ${agentId.take(24)}, " +
                        "lokal inaktiv — Neu-Handshake erzwungen (Spiegel-Lücke)",
                )
                startHandshake(agentId)
            }
            AgentEntry(agentId, displayName, online)
        }
        _agents.value = parsed
        if (BuildConfig.DEBUG) Log.d(TAG, "relay.state: ${parsed.size} Agent(en)")
        // PH-3 Welle 2: E2E-Handshake je onlineem Agenten (crypto.hello über
        // POST /crypto_hello; Identity-Publish läuft parallel beim onOpen).
        maybeStartHandshakes()
    }

    /**
     * PH2-MERGE: chat.event-Frame (Agenten-Antwort) → [chatEvents]-Flow.
     * Ungültige Frames (chat_id/agent_id/text fehlt) werden still verworfen —
     * die Verbindung bleibt unberührt (Server entsorgt Müll ebenfalls nur).
     */
    private fun handleChatEventFrame(json: JsonObject) {
        val chatId = runCatching {
            json.get("chat_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull().orEmpty()
        val agentId = runCatching {
            json.get("agent_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull().orEmpty()
        // PH-3 F1 (Jäger P1): Downgrade-SYMMETRIE — ist für diesen Agenten
        // eine aktive E2E-Session bekannt, wird KLARTEXT chat.event verworfen
        // (der Agent sendet chat.event.enc; Klartext = Downgrade-Versuch).
        // Rot-Banner statt stiller Anzeige, Warnung im Log.
        if (agentId.isNotEmpty()) {
            cryptoSessions[agentId]?.let { peer ->
                if (peer.isActive()) {
                    Log.w(
                        TAG,
                        "chat.event (Klartext) für Agent ${agentId.take(24)} verworfen — " +
                            "E2E-Session aktiv (Downgrade-Schutz)",
                    )
                    _e2eBanner.value =
                        "Unverschlüsselte Antwort verworfen — E2E-Sitzung aktiv. Neu verbinden."
                    return
                }
            }
        }
        val body = runCatching {
            json.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
        if (chatId.isEmpty() || agentId.isEmpty() || body == null) return
        val ts = runCatching {
            json.get("ts")?.takeIf { it.isJsonPrimitive }?.asLong
        }.getOrNull() ?: System.currentTimeMillis()
        // PH2-T11: elapsed_ms (Empfang→Antwort, Adapter-Messung) — optional.
        val elapsedMs = runCatching {
            json.get("elapsed_ms")?.takeIf { it.isJsonPrimitive }?.asLong
        }.getOrNull()
        val event = ChatMessageEvent(chatId, agentId, body, ts, elapsedMs)
        // SEC-W1 W1C-4: Per-Frame IO-Coroutinen liefen parallel auf dem
        // Read-Modify-Write → StateFlow-Lost-Update = stiller Nachrichten-
        // schwund. update{} = CAS-Schleife, kein Frame geht mehr verloren.
        _chatEvents.update { (it + event).takeLast(MAX_CHAT_EVENTS) }
        // SIRS-02: eingehende Antwort persistieren (genau hier, genau einmal).
        persistChatBubble(agentId, chatId, fromAgent = true, body, ts, status = null)
        // PH-FIX-CHAT-A (F4): Notification am Empfangs-Punkt (Klartext-Zweig).
        notifyIncomingChat(agentId, body, ts)
        if (BuildConfig.DEBUG) Log.d(TAG, "chat.event von Agent ${agentId.take(24)}")
    }

    /**
     * PH2-T10 (F2-S1/S2): chat.status-Frame → [chatStatus]-Flow (Key msg_id).
     * Ungültige Frames (msg_id/status fehlt, unbekannter Status) werden still
     * verworfen — die Verbindung bleibt unberührt. Map bleibt begrenzt
     * (Status ist transient), älteste Keys fliegen zuerst raus.
     */
    private fun handleChatStatusFrame(json: JsonObject) {
        val msgId = runCatching {
            json.get("msg_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull().orEmpty()
        val status = runCatching {
            json.get("status")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull().orEmpty()
        if (msgId.isEmpty() || status !in setOf("queued", "delivered", "read")) return
        // PH-3 F4 (Jäger P2): Rang-Dedup — SPIEGEL des Relay-_set_chat_status:
        // existiert ein Eintrag mit Rang ≥ dem eingehenden, wird der Frame
        // verworfen (kein Chip-Downgrade: last-write-wins könnte einen 'read'-
        // Chip auf 'delivered' zurückkleben, wenn Frames die Reihenfolge
        // tauschen — Spool/Reconnect macht das real möglich).
        // SEC-W1 W1C-4: Rang-Prüfung + Schreiben atomar in EINEM CAS-Schritt
        // (update{}) — der externe Check war ein TOCTOU-Fenster, parallele
        // Frame-Coroutinen konnten den Chip-Status still überschreiben.
        _chatStatus.update { cur ->
            if (!ChatStatusRank.shouldApply(cur[msgId], status)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "chat.status $status für ${msgId.take(12)} verworfen — Rang ≤ existierend")
                }
                cur
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "chat.status: $status für msg ${msgId.take(12)}")
                val next = LinkedHashMap(cur)
                next[msgId] = status
                while (next.size > MAX_CHAT_STATUS) {
                    next.remove(next.keys.first())
                }
                next
            }
        }
    }

    /**
     * PH2-T10 (F2-S3): chat.activity-Frame → [agentWorking]-Flow (Key agent_id,
     * letzter Frame gewinnt — der Relay sendet je Wechsel genau 1×).
     */
    private fun handleChatActivityFrame(json: JsonObject) {
        val agentId = runCatching {
            json.get("agent_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull().orEmpty()
        val working = runCatching {
            json.get("working")?.takeIf { it.isJsonPrimitive }?.asBoolean
        }.getOrNull() ?: return
        if (agentId.isEmpty()) return
        val chatId = runCatching {
            json.get("chat_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull().orEmpty()
        val startedAt = runCatching {
            json.get("started_at")?.takeIf { it.isJsonPrimitive }?.asLong
        }.getOrNull() ?: System.currentTimeMillis()
        val activity = AgentActivity(agentId, chatId, working, startedAt)
        // SEC-W1 W1C-4: atomares update{} statt Read-Modify-Write.
        _agentWorking.update { it + (agentId to activity) }
        if (BuildConfig.DEBUG) Log.d(TAG, "chat.activity: agent ${agentId.take(24)} working=$working")
    }

    // ── PH-3 Welle 2: E2E-Handshake + chat.*.enc-Empfang ───────────────────

    /**
     * PH-3-T2: Identity laden (Keystore-sealed, fail-closed) + PUBLIC Keys
     * via POST /key_publish (Pairing-Bearer). Private Keys NIE geloggt.
     * Fehlschlag = E2E aus (Legacy-Klartext v0.14-kompatibel), kein Crash.
     */
    private fun publishIdentity() {
        val ctx = appContext ?: return
        val activeScope = scope ?: return
        activeScope.launch {
            runCatching {
                val identity = IdentityStore(
                    PrefsIdentityStore(ctx), AndroidKeystoreIdentitySealer()
                ).loadOrCreate()
                phoneIdentity = CryptoEngine.X25519KeyPair(
                    CryptoEngine.fromHex(identity.ikPrivHex),
                    CryptoEngine.fromHex(identity.ikPubHex),
                )
                phonePrekey = CryptoEngine.X25519KeyPair(
                    CryptoEngine.fromHex(identity.spkPrivHex),
                    CryptoEngine.fromHex(identity.spkPubHex),
                )
                identityReady = true
                val base = serverUrl?.trim()?.trimEnd('/') ?: return@launch
                val body = JsonObject().apply {
                    addProperty("ik_pub", identity.ikPubHex)
                    addProperty("spk_pub", identity.spkPubHex)
                    addProperty("device_id", "phone")
                }
                postJson(base, "/key_publish", body)
                Log.i(TAG, "PH-3: Identity bereit + veröffentlicht (nur Pubs, Keys nie geloggt)")
                // relay.state kann VOR der Identity angekommen sein — jetzt nachholen.
                maybeStartHandshakes()
            }.onFailure {
                Log.w(TAG, "PH-3: Identity nicht ladbar (${it.javaClass.simpleName}) — E2E aus (fail-closed)")
            }
        }
    }

    /** E2E-Handshake je onlineem Agenten starten (Idempotenz-Guards innen). */
    private fun maybeStartHandshakes() {
        if (!identityReady) return
        _agents.value.filter { it.online }.forEach { startHandshake(it.agentId) }
    }

    /**
     * PH-3-T2: crypto.hello je Agent — Agent-Keys via GET /key_get (fail-closed
     * 404 = kein E2E für diesen Agenten), frisches EK, POST /crypto_hello.
     * hello_ack kommt als Frame über die Phone-WS (Relay = opaker Router).
     */
    private fun startHandshake(agentId: String) {
        val activeScope = scope ?: return
        val ik = phoneIdentity ?: return
        val spk = phonePrekey ?: return
        cryptoSessions[agentId]?.let { if (it.isActive()) return }
        if (!handshakesInFlight.add(agentId)) return
        activeScope.launch {
            try {
                val base = serverUrl?.trim()?.trimEnd('/') ?: return@launch
                val agentKeys = keyGet(base, agentId) ?: return@launch
                val peer = PhoneCryptoPeer(
                    agentId = agentId,
                    agentIkPubHex = agentKeys.ikPub,
                    agentSpkPubHex = agentKeys.spkPub,
                    identityIk = ik,
                    identitySpk = spk,
                )
                val sessionId = "fb-" + CryptoEngine.hex(CryptoEngine.secureRandomBytes(8))
                val chatId = "chat-" + CryptoEngine.hex(CryptoEngine.secureRandomBytes(6))
                val hello = peer.makeHello(sessionId, chatId)
                pendingCryptoPeers[sessionId] = peer
                if (!postJson(base, "/crypto_hello", hello)) {
                    pendingCryptoPeers.remove(sessionId)
                    Log.w(TAG, "PH-3: crypto.hello für Agent ${agentId.take(24)} abgelehnt — kein E2E (Legacy)")
                } else {
                    Log.i(TAG, "PH-3: crypto.hello Session ${sessionId.take(8)} gesendet (Agent ${agentId.take(24)})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "PH-3: Handshake fehlgeschlagen (${e.javaClass.simpleName}) — nie Keys/Inhalt loggen")
            } finally {
                handshakesInFlight.remove(agentId)
            }
        }
    }

    /** PH-3: Agent-Public-Keys aus der Registry (404 = Agent ohne E2E-Keys). */
    private class AgentKeys(val ikPub: String, val spkPub: String)

    private fun keyGet(base: String, agentId: String): AgentKeys? = runCatching {
        val request = Request.Builder()
            .url(buildHttpUrl(base, "/key_get?agent_id=$agentId"))
            .header("Authorization", "Bearer ${PairingManager.getCode()}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) {
                Log.w(TAG, "PH-3: /key_get für Agent ${agentId.take(24)} → HTTP ${response.code} (fail-closed)")
                return null
            }
            val obj = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
            AgentKeys(obj.get("ik_pub").asString, obj.get("spk_pub").asString)
        }
    }.getOrNull()

    /** JSON-POST mit Pairing-Bearer (Key-Registry + crypto_hello-Routen). */
    private fun postJson(base: String, path: String, body: JsonObject): Boolean = runCatching {
        val request = Request.Builder()
            .url(buildHttpUrl(base, path))
            .header("Authorization", "Bearer ${PairingManager.getCode()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.code in 200..299 }
    }.getOrDefault(false)

    /** Frame-String-Feld (primitive) — null bei fehlendem/nicht-primitivem Feld. */
    private fun stringOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun longOf(json: JsonObject, field: String): Long? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    /** E2eSessionInfo-Flow neu veröffentlichen (Session kam/weg). */
    private fun publishE2eSessions() {
        _e2eSessions.value = cryptoSessions.entries.mapNotNull { (agentId, peer) ->
            val fp = peer.fingerprint ?: return@mapNotNull null
            E2eSessionInfo(
                agentId = agentId,
                sessionId = peer.sessionId ?: "",
                chatId = peer.chatId ?: "",
                fingerprintHex = fp,
                fingerprintDisplay = CryptoEngine.fingerprintDisplay(fp),
                agentFingerprintDisplay = CryptoEngine.fingerprintDisplay(peer.agentFingerprint),
            )
        }.associateBy { it.agentId }
    }

    /** Session-Rotate-Hinweis (hdr.rot = 1): frische crypto.hello (§5.4). */
    private fun handleRotateHint(frame: JsonObject, agentId: String, peer: PhoneCryptoPeer) {
        if (!peer.hasRotateHint(frame)) return
        Log.i(TAG, "PH-3: rotate-Hinweis (hdr.rot) — frischer Handshake für Agent ${agentId.take(24)}")
        cryptoSessions.remove(agentId)
        publishE2eSessions()
        startHandshake(agentId)
    }

    /** PH-3: hello_ack-Verify (k_init GENAU EINMAL) → Session aktiv. */
    private fun handleCryptoHelloAck(json: JsonObject) {
        val sessionId = stringOf(json, "session_id") ?: return
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = pendingCryptoPeers.remove(sessionId) ?: return
        try {
            peer.consumeAck(json)
            cryptoSessions[agentId] = peer
            // PH-FIX-BACKEND-B: Hello-Ack-Verifiziert = Retry-Budget zurücksetzen.
            helloAckFailures.remove(agentId)
            publishE2eSessions()
            Log.i(TAG, "PH-3: E2E-Session ${sessionId.take(8)} aktiv (Agent ${agentId.take(24)})")
        } catch (e: Exception) {
            // Wrong-Key/PSK → Session verwerfen, KEIN Cleartext-Fallback (Spec §6).
            Log.w(TAG, "PH-3: hello_ack-Verify fehlgeschlagen — Session verworfen (fail-closed)")
            // PH-FIX-BACKEND-B (Bug-1-Härtung): Stillstand kippen in sichtbaren
            // Fehler — max. HelloAckFailurePolicy.MAX_RETRIES Neu-Handshakes mit
            // Backoff, danach rot-Banner + Status-Zeile (analog FP-Mismatch).
            // Keine stillen Verwerfungen mehr: der User sieht, dass E2E klemmt.
            val failures = (helloAckFailures[agentId] ?: 0) + 1
            helloAckFailures[agentId] = failures
            if (HelloAckFailurePolicy.shouldRetry(failures)) {
                Log.i(
                    TAG,
                    "PH-3: hello_ack-Retry $failures/${HelloAckFailurePolicy.MAX_RETRIES} " +
                        "für Agent ${agentId.take(24)} (Backoff)",
                )
                scope?.launch {
                    delay(HELLO_ACK_RETRY_BACKOFF_MS * failures)
                    startHandshake(agentId)
                }
            } else {
                Log.w(
                    TAG,
                    "PH-3: hello_ack-Verify endgültig fehlgeschlagen " +
                        "(${HelloAckFailurePolicy.MAX_RETRIES} Retries aufgebraucht) — Banner",
                )
                _e2eBanner.value = HelloAckFailurePolicy.BANNER
                notifyStatus(false, HelloAckFailurePolicy.STATUS, Phase.PAIRING_BROKEN)
            }
        }
    }

    /**
     * PH-3 (Sirs Revision (iii)): Session-Reset vom Agenten — SessionKeys
     * verwerfen; das Verhalten entscheidet der REASON (SIRS-01, P0):
     * - "agent-session-lost" = BENIGN: Der Agent kennt die Sitzung nach einem
     *   Neustart nicht mehr (kein Sicherheitsereignis) — KEIN MitM-Alarm,
     *   Verbindungsstatus unberührt, stattdessen SOFORT ein neuer Handshake
     *   (früher: stille Tote-Session, Heilung nur per App-Restart).
     * - alles andere (fingerprint-mismatch) = Sicherheitsereignis: rot-Banner
     *   + PAIRING_BROKEN-artige Status-Zeile wie bisher.
     */
    private fun handleCryptoSessionReset(json: JsonObject) {
        val sessionId = stringOf(json, "session_id") ?: return
        val agentId = stringOf(json, "agent_id") ?: return
        val decision = sessionResetDecision(stringOf(json, "reason"))
        pendingCryptoPeers.remove(sessionId)
        cryptoSessions.remove(agentId)
        // PH-FIX-CHAT-A (F5): Session tot → jede folgende activity.enc dieses
        // Agenten landet ohnehin im Verwurfs-Pfad (kein Peer) — der Spinner
        // darf nicht auf dem letzten working=true stehen bleiben.
        _agentWorking.update { it - agentId }
        // FIX-WELLE-2 B8 (HERZ-Befund 8): Die tote Session kann für ihre
        // noch ungelesenen Nachrichten KEIN Read mehr liefern — Chips von
        // 'Zugestellt'/'Wartet' auf '✕ Fehlgeschlagen' fegen (stiller
        // Verwurf statt Endlos-Zustellt-Lüge).
        _chatStatus.update { ChatStatusRank.sweepUnreadToFailed(it) }
        publishE2eSessions()
        if (decision.isSecurityEvent) {
            // FIX-WELLE-2 B8: Grund ehrlich benennen — Takeover ist keine
            // Fingerprint-Abweichung (auch wenn der Banner-Pfad derselbe ist).
            val reasonText = if (stringOf(json, "reason") == REASON_CROSS_SESSION_TAKEOVER) {
                "Session-Übernahme"
            } else {
                "Fingerprint-Abweichung"
            }
            Log.w(
                TAG,
                "PH-3: Session ${sessionId.take(8)} vom Agenten zurückgesetzt ($reasonText)",
            )
            _e2eBanner.value = decision.banner
            notifyStatus(
                false,
                "E2E-Sitzung zurückgesetzt ($reasonText) — neu verbinden",
                Phase.PAIRING_BROKEN,
            )
        } else {
            // SIRS-01: benigne Zeile (Log) + Neu-Handshake-Anstoß — das
            // E2E-Badge (publishE2eSessions oben) verschwindet kurz und kommt
            // mit der bestätigten neuen Session zurück; der User bekommt KEIN
            // rote MitM-Warnung für einen normalen Agent-Neustart.
            Log.i(
                TAG,
                "SIRS-01: Session ${sessionId.take(8)} dem Agenten nicht mehr bekannt " +
                    "(Agent-Neustart) — Neu-Handshake sofort angestoßen",
            )
            startHandshake(agentId)
        }
    }

    /**
     * PH-3: chat.event.enc = verschlüsselte Agenten-Antwort → entschlüsseln
     * (Klartext intern-only, nie geloggt) → wie bisher in [chatEvents] (der
     * KI-Badge der Chat-UI bleibt, da fromAgent = true).
     */
    private fun handleChatEventEnc(json: JsonObject, isRetry: Boolean = false) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: run {
            // JÄGER-J5 (P2, JAEGE-RFULL-20261102): hello_ack und die erste
            // chat.event.enc treffen fast gleichzeitig ein — JE Frame läuft
            // in einer eigenen IO-Coroutine OHNE Abarbeitungs-Garantie.
            // Lief dieser Handler vor handleCryptoHelloAck, war die Session
            // noch nicht registriert → die ERSTE Antwort nach dem Handshake
            // fiel STILL auf den Boden (kein Retry, kein Banner). Läuft der
            // Handshake noch (in-flight/pending), den Frame GENAU EINMAL
            // verzögert nachstellen — bounded: ist der Ack dann da, wird der
            // Frame normal verarbeitet; scheiterte der Handshake, endet der
            // Retry im normalen Verwurfs-Pfad mit Warnung.
            val handshakeRunning = handshakesInFlight.contains(agentId) ||
                pendingCryptoPeers.values.any { it.agentId == agentId }
            if (!isRetry && handshakeRunning) {
                Log.i(
                    TAG,
                    "J5: chat.event.enc kam VOR hello_ack (Agent ${agentId.take(24)}) — einmalige Nachstellung",
                )
                scope?.launch {
                    delay(750L)
                    handleChatEventEnc(json, isRetry = true)
                }
            } else {
                Log.w(TAG, "PH-3: chat.event.enc ohne aktive E2E-Session verworfen")
            }
            return
        }
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "PH-3: chat.event.enc-Entschlüsselung fehlgeschlagen — Frame verworfen")
            return
        }
        handleRotateHint(json, agentId, peer)
        val text = runCatching {
            payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull() ?: return
        val chatId = stringOf(json, "chat_id") ?: return
        val ts = longOf(json, "ts") ?: System.currentTimeMillis()
        val elapsedMs = longOf(json, "elapsed_ms")
        // SEC-W1 W1C-4: atomares update{} (CAS) — kein Lost-Update mehr.
        _chatEvents.update { (it + ChatMessageEvent(chatId, agentId, text, ts, elapsedMs))
            .takeLast(MAX_CHAT_EVENTS) }
        // SIRS-02: verschlüsselte Antworten DENSELBEN Weg persistieren wie
        // Klartext-Ereignisse — der Verlauf kennt keinen E2E-Unterschied.
        persistChatBubble(agentId, chatId, fromAgent = true, text, ts, status = null)
        // PH-FIX-CHAT-A (F4): Notification am Empfangs-Punkt (E2E-Zweig) —
        // derselbe Weg wie Klartext, inkl. Sperrbildschirm-Schutz bei
        // aktiver Session (VISIBILITY_PRIVATE).
        notifyIncomingChat(agentId, text, ts)
        if (BuildConfig.DEBUG) Log.d(TAG, "chat.event.enc von Agent ${agentId.take(24)} entschlüsselt")
        // Sirs Revision (iii): Abweichungs-Antwort → rot-Banner in Chat + Settings.
        if (text.startsWith("Fingerprint-Abgleich: FEHLGESCHLAGEN")) {
            _e2eBanner.value = text
        }
    }

    /**
     * PH-3: chat.read.enc-Spiegel — nur entschlüsseln, damit die Empfangs-Kette
     * mit der Agenten-Sendekette synchron bleibt (jede enc-Nachricht zählt);
     * der Lese-Status selbst läuft über den KLAREN chat.status-Frame (PH2-T10).
     */
    private fun handleChatReadEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: return
        try {
            peer.decryptFrame(json)
            handleRotateHint(json, agentId, peer)
        } catch (e: Exception) {
            Log.w(TAG, "PH-3: chat.read.enc-Spiegel verworfen (Entschlüsselung fehlgeschlagen)")
        }
    }

    /**
     * PH-3: chat.activity.enc-Spiegel — entschlüsseln (Ketten-Sync), UI liest
     * die KLAREN working/started_at-Felder (Relay-Dedup PH2-T10 unverändert).
     */
    private fun handleChatActivityEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: return
        try {
            peer.decryptFrame(json)
            handleRotateHint(json, agentId, peer)
        } catch (e: Exception) {
            Log.w(TAG, "PH-3: chat.activity.enc-Spiegel verworfen (Entschlüsselung fehlgeschlagen)")
            return
        }
        handleChatActivityFrame(json)
    }

    /** PH-3: session_fp des Agenten (für den FP-Abgleich-Button im Chat). */
    fun sessionFingerprint(agentId: String): String? = cryptoSessions[agentId]?.fingerprint

    /**
     * SIRS-02 (P2, Volltest 03.11.2026): Klartext-Bubble in den Disk-Store
     * persistieren — der Verlauf überlebt App-Restart/force-stop (vorher nur
     * rememberSaveable: „Noch keine Nachrichten“ nach dem Neustart). Store =
     * app-private filesDir hinter dem AppLock-Gate; I/O NIE auf dem Caller-
     * Thread (appendAsync serialisiert über einen eigenen IO-Thread).
     * Persistiert wird EXAKT am Frame-/Sende-Ergebnis-Punkt (genau einmal).
     */
    private fun persistChatBubble(
        agentId: String,
        chatId: String,
        fromAgent: Boolean,
        text: String,
        ts: Long,
        status: String?,
        mediaKind: String? = null,
        mediaName: String? = null,
        mediaMime: String? = null,
        mediaSize: Long? = null,
        mediaPath: String? = null,
        mediaId: String? = null,
    ) {
        val root = appContext?.let { ChatHistoryStore.root(it) } ?: return
        ChatHistoryStore.appendAsync(
            root,
            ChatHistoryStore.Entry(
                seq = ts,
                agentId = agentId,
                chatId = chatId,
                fromAgent = fromAgent,
                text = text,
                time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ts)),
                status = status,
                mediaKind = mediaKind,
                mediaName = mediaName,
                mediaMime = mediaMime,
                mediaSize = mediaSize,
                mediaPath = mediaPath,
                mediaId = mediaId,
            ),
        )
    }

    /**
     * PH-FIX-CHAT-A (F4): Notification bei eingehender Agenten-Antwort —
     * aufgerufen GENAU an den beiden Empfangs-Punkten (Klartext + E2E)
     * direkt neben persistChatBubble. Vorher existierte im gesamten Chat-
     * Empfangspfad KEIN NotificationManager-Aufruf: Antworten erschienen
     * nur in der geöffneten App-UI — Sir verpasste Antworten, solange die
     * App zu war.
     *
     * Unterdrückt, wenn die Conversation DIESES Agenten offen und die App im
     * Vordergrund ist (Spam-Schutz, s. [chatVisibleAgentId]); abwesende
     * Permission (Android 13+) bleibt still statt zu crashen.
     */
    private fun notifyIncomingChat(agentId: String, text: String, ts: Long) {
        val ctx = appContext ?: return
        if (MainActivity.isForeground && agentId == chatVisibleAgentId) return
        // Android 13+: ohne erteilte POST_NOTIFICATIONS bleibt es still —
        // das Ereignis ist trotzdem in der App-UI sichtbar (Request-Lauf
        // existiert in BridgeScreen).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHAT_NOTIFY_CHANNEL,
                        "Chat-Nachrichten",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "Eingehende Antworten deiner Agenten" },
                )
            }
            val name = _agents.value.firstOrNull { it.agentId == agentId }?.displayName ?: agentId
            val tap = PendingIntent.getActivity(
                ctx, 12,
                Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val content = text.take(120)
            val builder = NotificationCompat.Builder(ctx, CHAT_NOTIFY_CHANNEL)
                .setContentTitle(name)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(R.drawable.ic_chat)
                .setAutoCancel(true)
                .setWhen(ts)
                .setContentIntent(tap)
            if (cryptoSessions[agentId]?.isActive() == true) {
                // Bewusste Grenze (Bugfund): bei aktiver E2E-Session ist der
                // Chat-Inhalt Klartext — der Sperrbildschirm zeigt nur die
                // Absender-Zeile, der volle Text bleibt hinter der Sperre.
                builder
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        NotificationCompat.Builder(ctx, CHAT_NOTIFY_CHANNEL)
                            .setContentTitle(name)
                            .setContentText("Neue Nachricht")
                            .setSmallIcon(R.drawable.ic_chat)
                            .setAutoCancel(true)
                            .setWhen(ts)
                            .setContentIntent(tap)
                            .build(),
                    )
            }
            nm.notify((CHAT_NOTIFY_ID_BASE + ts % 100_000L).toInt(), builder.build())
        }.onFailure { Log.w(TAG, "Chat-Notification fehlgeschlagen: ${it.message}") }
    }

    /** PH-3: rot-Banner wegdismissing (Chat/Settings rufen nach dem Lesen). */
    fun clearE2eBanner() {
        _e2eBanner.value = null
    }

    // ── MEDIA-T1: Medien-/Datei-Versand im Chat (chunked, E2E-only) ─────────

    /** Empfangs-Assembler je Agent (ein Upload je Agent auf einmal). */
    private val mediaInboundAssemblers = ConcurrentHashMap<String, MediaAssembler>()

    /** Empfangene Metadaten je media_id (Chunk 0: kind/name/mime/size). */
    private val mediaInboundMeta = ConcurrentHashMap<String, ChatMediaEvent>()

    /**
     * MEDIA-T1: Medium an einen Agenten senden — chunked über POST /media,
     * JE Chunk durch die E2E-Ratchet-Sitzung (chat.media.enc). Fail-closed:
     * ohne aktive E2E-Session gibt es KEINEN Versand (Medien sind niemals
     * Klartext — der Klartext-Fallback ist FORBIDDEN, Parität zu enc-Frames).
     * Fortschritt via [mediaUploads] (sentChunks + ackedChunks je media_id).
     */
    fun sendMedia(
        agentId: String,
        kind: String,
        name: String,
        mime: String,
        bytes: ByteArray,
        onResult: (ok: Boolean, error: String?, mediaId: String?) -> Unit,
    ) {
        val activeScope = scope
        val mediaId = MediaProtocol.newMediaId { CryptoEngine.secureRandomBytes(16) }
        val validationError = MediaProtocol.validateForSend(kind, name, bytes.size.toLong())
        when {
            activeScope == null || !isConnected -> {
                deliverMediaResult(onResult, false, "Keine Verbindung zum Relay", null)
            }
            validationError != null -> {
                deliverMediaResult(onResult, false, validationError, null)
            }
            else -> {
                val peer = cryptoSessions[agentId]?.takeIf { it.isActive() }
                if (peer == null) {
                    deliverMediaResult(
                        onResult, false,
                        "Keine aktive E2E-Sitzung — Medienversand nur verschlüsselt möglich",
                        null,
                    )
                    return
                }
                val base = serverUrl?.trim()?.trimEnd('/')
                if (base.isNullOrBlank()) {
                    deliverMediaResult(onResult, false, "Kein Relay-Server konfiguriert", null)
                    return
                }
                val chatId = peer.chatId
                if (chatId.isNullOrBlank()) {
                    deliverMediaResult(onResult, false, "E2E-Sitzung ohne chat_id", null)
                    return
                }
                activeScope.launch(Dispatchers.IO) {
                    val outcome = runCatching {
                        uploadMediaChunks(base, agentId, peer, chatId, mediaId, kind, name, mime, bytes)
                    }
                    outcome.onSuccess { sent ->
                        if (sent) {
                            // Upload angenommen — Bubble-Text für Verlauf/UI.
                            val label = MediaProtocol.kindLabel(kind)
                            val sizeText = MediaProtocol.formatBytes(bytes.size.toLong())
                            persistChatBubble(
                                agentId, chatId, fromAgent = false,
                                text = "[$label] $name ($sizeText)",
                                ts = System.currentTimeMillis(), status = null,
                                mediaKind = kind, mediaName = name, mediaMime = mime,
                                mediaSize = bytes.size.toLong(), mediaId = mediaId,
                            )
                            deliverMediaResult(onResult, true, null, mediaId)
                        } else {
                            markMediaUploadFailed(mediaId, "Relay lehnte Chunks ab")
                            deliverMediaResult(onResult, false, "Relay lehnte Chunks ab", null)
                        }
                    }.onFailure { e ->
                        markMediaUploadFailed(mediaId, e.message?.take(80) ?: "Senden fehlgeschlagen")
                        deliverMediaResult(onResult, false, e.message?.take(80) ?: "Senden fehlgeschlagen", null)
                    }
                }
            }
        }
    }

    /** Chunk-POSTs sequenziell (Reihenfolge = Relay-Spool-Vertrag). */
    private fun uploadMediaChunks(
        base: String,
        agentId: String,
        peer: com.hermesandroid.bridge.crypto.PhoneCryptoPeer,
        chatId: String,
        mediaId: String,
        kind: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        val chunks = MediaChunker.chunk(bytes)
        val sha = MediaProtocol.sha256Hex(bytes)
        _mediaUploads.update { it + (mediaId to MediaUploadState(agentId, kind, name, chunks.size)) }
        for ((index, chunk) in chunks.withIndex()) {
            val payload = buildMap {
                put("media_id", mediaId)
                put("seq", chunk.seq)
                put("total", chunk.total)
                put("data", chunk.b64)
                if (index == 0) put("sha256", sha)
            }
            val frame = peer.encryptFrame("chat.media.enc", chatId, payload)
            val body = JsonObject().apply {
                addProperty("agent_id", agentId)
                addProperty("chat_id", chatId)
                addProperty("session_id", frame.get("session_id").asString)
                addProperty("ts", frame.get("ts").asLong)
                add("hdr", frame.get("hdr"))
                addProperty("nonce", frame.get("nonce").asString)
                addProperty("ct", frame.get("ct").asString)
                addProperty("media_id", mediaId)
                addProperty("seq", chunk.seq)
                addProperty("total", chunk.total)
                if (index == 0) {
                    addProperty("kind", kind)
                    addProperty("name", name)
                    addProperty("mime", mime)
                    addProperty("size", bytes.size.toLong())
                }
            }
            val request = Request.Builder()
                .url(buildHttpUrl(base, "/media"))
                .header("Authorization", "Bearer ${PairingManager.getCode()}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    Log.w(TAG, "MEDIA-T1: /media Chunk ${chunk.seq} abgelehnt (HTTP ${response.code})")
                    return false
                }
                _mediaUploads.update {
                    it + ((it[mediaId] ?: return@update it).let { state ->
                        mediaId to state.copy(sentChunks = chunk.seq + 1)
                    })
                }
            }
        }
        return true
    }

    /** Media-Upload endgültig gescheitert → State + Verlauf-Chip 'failed'. */
    private fun markMediaUploadFailed(mediaId: String, error: String) {
        _mediaUploads.update { map ->
            map[mediaId]?.let { mediaId to it.copy(failed = error) }?.let { map + it }
                ?: map
        }
    }

    /** MEDIA-T1: chat.media.chunk_ack → Upload-Fortschritt (ackedChunks). */
    private fun handleMediaChunkAck(json: JsonObject) {
        val mediaId = stringOf(json, "media_id") ?: return
        val seq = longOf(json, "seq")?.toInt() ?: return
        val ok = runCatching {
            json.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean
        }.getOrNull() ?: true
        _mediaUploads.update { map ->
            val state = map[mediaId] ?: return@update map
            if (!ok) {
                map + (mediaId to state.copy(failed = "Agent lehnte Chunk $seq ab"))
            } else {
                val acked = maxOf(state.ackedChunks, seq + 1)
                val done = acked >= state.totalChunks
                map + (mediaId to state.copy(ackedChunks = acked, done = done))
            }
        }
    }

    /**
     * MEDIA-T1: chat.media.enc vom Agenten — Chunk entschlüsseln, reassemb-
     *lieren, bei Vollständigkeit: Datei app-privat speichern (filesDir/
     * chat_media), sha prüfen (fail-closed) und als [ChatMediaEvent] für die
     * Media-Bubble liefern (+ Verlauf + Notification).
     */
    private fun handleChatMediaEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId]?.takeIf { it.isActive() } ?: run {
            Log.w(TAG, "MEDIA-T1: chat.media.enc ohne aktive E2E-Session verworfen")
            return
        }
        val mediaId = stringOf(json, "media_id") ?: return
        val chatId = stringOf(json, "chat_id") ?: return
        val seq = longOf(json, "seq")?.toInt() ?: return
        val total = longOf(json, "total")?.toInt() ?: return
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA-T1: chat.media.enc-Entschlüsselung fehlgeschlagen — verworfen")
            return
        }
        handleRotateHint(json, agentId, peer)
        val data = runCatching {
            payload.get("data")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull() ?: return
        val sha = runCatching {
            payload.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
        // Metadaten des Chunk-0 (klar) für die fertige Bubble merken.
        if (seq == 0) {
            mediaInboundMeta[mediaId] = ChatMediaEvent(
                chatId = chatId,
                agentId = agentId,
                mediaId = mediaId,
                kind = stringOf(json, "kind") ?: "file",
                name = stringOf(json, "name") ?: mediaId,
                mime = stringOf(json, "mime") ?: "",
                size = longOf(json, "size") ?: 0L,
                localPath = "",
                ts = longOf(json, "ts") ?: System.currentTimeMillis(),
            )
        }
        val assembler = mediaInboundAssemblers.getOrPut(agentId) { MediaAssembler() }
        val blob = try {
            assembler.add(mediaId, seq, total, data, sha)
        } catch (e: MediaException) {
            Log.w(TAG, "MEDIA-T1: Assembly fehlgeschlagen — Upload verworfen (${e.message})")
            mediaInboundMeta.remove(mediaId)
            return
        } ?: return  // noch unvollständig
        mediaInboundMeta.remove(mediaId)?.let { meta ->
            val saved = runCatching { saveInboundMedia(mediaId, meta.name, blob) }
                .getOrNull()
            if (saved == null) {
                Log.w(TAG, "MEDIA-T1: empfangenes Medium konnte nicht gespeichert werden")
                return
            }
            val event = meta.copy(size = blob.size.toLong(), localPath = saved.absolutePath)
            _chatMediaEvents.update { (it + event).takeLast(MAX_CHAT_EVENTS) }
            val label = MediaProtocol.kindLabel(meta.kind)
            persistChatBubble(
                agentId, chatId, fromAgent = true,
                text = "[$label] ${meta.name} (${MediaProtocol.formatBytes(blob.size.toLong())})",
                ts = event.ts, status = null,
                mediaKind = meta.kind, mediaName = meta.name, mediaMime = meta.mime,
                mediaSize = blob.size.toLong(), mediaPath = saved.absolutePath,
                mediaId = mediaId,
            )
            notifyIncomingChat(agentId, "[$label] ${meta.name}", event.ts)
            if (BuildConfig.DEBUG) Log.d(TAG, "MEDIA-T1: ${meta.kind} von ${agentId.take(24)} empfangen")
        }
    }

    /**
     * MEDIA-T1: fertiges Medium app-privat speichern (filesDir/chat_media —
     * kein Backup-Restore, hinter dem AppLock-Gate). Dateiname: media_id +
     * gesäuberter Originalname.
     */
    private fun saveInboundMedia(mediaId: String, name: String, blob: ByteArray): java.io.File? {
        val ctx = appContext ?: return null
        val dir = java.io.File(ctx.filesDir, "chat_media")
        if (!dir.exists() && !dir.mkdirs()) return null
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "datei" }
        val target = java.io.File(dir, "${mediaId.take(32)}-$safe")
        val tmp = java.io.File(dir, ".${target.name}.part")
        tmp.writeBytes(blob)
        return if (tmp.renameTo(target)) target else {
            // rename kann plattformbedingt über Bestand scheitern — copy+delete.
            runCatching {
                target.writeBytes(blob)
                tmp.delete()
                target
            }.getOrNull()
        }
    }

    /** Media-Sende-Ergebnis immer auf den Main-Thread posten (UI-Bindung). */
    private fun deliverMediaResult(
        onResult: (ok: Boolean, error: String?, mediaId: String?) -> Unit,
        ok: Boolean,
        error: String?,
        mediaId: String?,
    ) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { onResult(ok, error, mediaId) }
            }
        } catch (_: Exception) {}
    }

    /**
     * PH2-MERGE: Nachricht an einen Agenten via POST /chat (gleiche Route wie
     * die Tools; Bearer = Kopplungs-Code, gleiche Gegenstelle wie der WS).
     * onResult läuft IMMER auf dem Main-Thread (UI darf direkt binden) —
     * ok=true heißt: vom Relay angenommen (delivered ODER queued).
     * PH2-T10: msgId = Broker-Nachrichten-ID aus der Relay-Antwort (null bei
     * Fehler/leerer Antwort) — die UI bindet den Zustell-/Lese-Status genau
     * an diese msg_id (RelayClient.chatStatus).
     */
    fun sendChat(agentId: String, text: String, onResult: (ok: Boolean, error: String?, msgId: String?) -> Unit) {
        val activeScope = scope
        if (activeScope == null || !isConnected) {
            // SIRS-02: auch der gescheiterte Versand bleibt im Verlauf (Chip
            // '✕ Fehlgeschlagen' überlebt den Restart statt zu verhungern).
            persistChatBubble(agentId, chatId = "", fromAgent = false, text,
                System.currentTimeMillis(), status = "failed")
            deliverChatResult(onResult, false, "Keine Verbindung zum Relay", null)
            return
        }
        val base = serverUrl?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            persistChatBubble(agentId, chatId = "", fromAgent = false, text,
                System.currentTimeMillis(), status = "failed")
            deliverChatResult(onResult, false, "Kein Relay-Server konfiguriert", null)
            return
        }
        activeScope.launch {
            // SIRS-02: chat_id des Sendens (E2E = Session-chat_id, Klartext
            // = leer — der Relay vergibt sie; sie wird je Eintrag mitge-
            // speichert, der Verlauf ist je agent+chat_id nachvollziehbar).
            var historyChatId = ""
            // Ganzer Sendeweg inkl. URL-Bau im runCatching — eine ungültige
            // Adresse wirft synchron (IllegalArgumentException) und darf die
            // Coroutine nicht reißen (App-Crash), sondern als Fehler melden.
            val outcome = runCatching {
                // PH-3 Welle 2: aktive E2E-Session → chat.send.enc (Klartext nur
                // im AES-GCM-ct, POST-Body OHNE text; chat_id vom PHONE gewählt —
                // AAD bindet daran, der Relay ersetzt sie im enc-Modus NICHT).
                val cryptoPeer = cryptoSessions[agentId]
                if (cryptoPeer != null && cryptoPeer.isActive()) {
                    val peerChatId = cryptoPeer.chatId
                        ?: throw IllegalStateException("E2E-Session ohne chat_id")
                    historyChatId = peerChatId
                    val frame = cryptoPeer.encryptFrame(
                        "chat.send.enc", peerChatId,
                        mapOf("msg_id" to "", "text" to text),
                    )
                    val encBody = JsonObject().apply {
                        addProperty("agent_id", agentId)
                        addProperty("chat_id", peerChatId)
                        addProperty("session_id", frame.get("session_id").asString)
                        add("hdr", frame.get("hdr"))
                        addProperty("nonce", frame.get("nonce").asString)
                        addProperty("ct", frame.get("ct").asString)
                        addProperty("ts", frame.get("ts").asLong)
                    }
                    val encRequest = Request.Builder()
                        .url(buildHttpUrl(base, "/chat"))
                        .header("Authorization", "Bearer ${PairingManager.getCode()}")
                        .post(encBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(encRequest).execute().use { response ->
                        val code = response.code
                        val msgId = if (code in 200..299) {
                            parseMsgIdFromBody(response.body?.string())
                        } else null
                        code to msgId
                    }
                } else {
                    val url = buildHttpUrl(base, "/chat")
                    val payload = JsonObject().apply {
                        addProperty("agent_id", agentId)
                        addProperty("text", text)
                    }
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer ${PairingManager.getCode()}")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val code = response.code
                        // PH2-T10: msg_id aus dem Body (nur bei 2xx lesbar sinnvoll).
                        val msgId = if (code in 200..299) {
                            parseMsgIdFromBody(response.body?.string())
                        } else null
                        code to msgId
                    }
                }
            }
            outcome.onSuccess { (code, msgId) ->
                // SIRS-02: eigener Versand persistieren (status-Snapshot:
                // null = gesendet, failed = endgültig gescheitert).
                persistChatBubble(agentId, historyChatId, fromAgent = false, text,
                    System.currentTimeMillis(),
                    if (code in 200..299) null else "failed")
                if (code in 200..299) {
                    deliverChatResult(onResult, true, null, msgId)
                } else {
                    deliverChatResult(onResult, false, "Relay lehnte ab (HTTP $code)", null)
                }
            }.onFailure { e ->
                persistChatBubble(agentId, historyChatId, fromAgent = false, text,
                    System.currentTimeMillis(), status = "failed")
                deliverChatResult(onResult, false, e.message?.take(80) ?: "Senden fehlgeschlagen", null)
            }
        }
    }

    /** msg_id aus der /chat-Antwort (nur bei 2xx sinnvoll; gehärtet). */
    private fun parseMsgIdFromBody(bodyText: String?): String? = bodyText?.let {
        runCatching {
            JsonParser.parseString(it).asJsonObject
                .get("msg_id")?.takeIf { p -> p.isJsonPrimitive }?.asString
        }.getOrNull()
    }

    /** Chat-Sende-Ergebnis immer auf den Main-Thread posten (UI-Bindung). */
    private fun deliverChatResult(
        onResult: (ok: Boolean, error: String?, msgId: String?) -> Unit,
        ok: Boolean,
        error: String?,
        msgId: String?,
    ) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { onResult(ok, error, msgId) }
            }
        } catch (_: Exception) {}
    }

    /**
     * PH2-MERGE: HTTP-URL zur Relay-Server-Adresse — gleiche Regeln wie
     * [buildWsUrl] (TLS-Route bekommt NIE einen Port, Plaintext-LAN den
     * Standard-Port 8766), nur mit http/https-Schema.
     */
    private fun buildHttpUrl(serverUrl: String, path: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val useTls = trimmed.startsWith("https://") || trimmed.startsWith("wss://")
        var base = trimmed
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://")
        if (!useTls && !base.contains(":")) {
            base = "$base:8766"
        }
        val scheme = if (useTls) "https" else "http"
        return "$scheme://$base$path"
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

    /**
     * Status an alle Hörer (Service-Notification, UI) — immer am Main-Thread.
     * PH1-T3: mit strukturierter Phase — die UI (BridgeStatus) leitet daraus
     * den ConnectionState ab, statt Status-Texte zu greppen.
     */
    private fun notifyStatus(connected: Boolean, message: String, phase: Phase) {
        currentStatusMessage = message
        currentPhase = phase
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
            // JÄGER-J10 (P2): bewusst getrennt (userDisconnected) = KEIN
            // FGS-Zombie — ein staler Doze-Alarm startet den Service nach
            // Sirs ausdrücklichem Trennen sonst zurück („Getrennt“-
            // Notification als Geist). reconnectNow() war ohnehin No-op,
            // nur der Service-Start fehlte im Gate.
            if (RelayClient.isUserDisconnected()) {
                Log.i("ReconnectAlarm", "FGS-Anhebung übersprungen — bewusst getrennt")
                return
            }
            // v0.12.0 (Akku P2-1): nur je Start, wenn verstorben — sonst
            // startForegroundService-Call + FGS-Neu-Anmeldung alle 5 min ohnehin.
            BridgeConnectionService.start(context)
        }
    }
}