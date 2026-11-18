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
import com.hermesandroid.bridge.security.MtlsSupport
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
import com.hermesandroid.bridge.server.CommandDispatcher
import com.hermesandroid.bridge.server.ServerProfileRules
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeConnectionService
import com.hermesandroid.bridge.ui.settings.DeviceRules  // APP-ANSCHLUSS: DeviceEntry des B7-Geräte-UI
import com.hermesandroid.bridge.ui.dashboard.DashboardRules  // F1: CronJobItem des B7/F1-cron.status-Verbands
import com.hermesandroid.bridge.ui.chat.ChatSpeechSpeaker  // W21 ANRUF P5: Call-Modus-TTS (§3.3)
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    // 0.16.4 (P42): Verbindungsversuche ENDLOS weiterlaufen lassen — der 5er-Cap
    // (MAX_RETRIES = 5) erzeugte nach Fail-Serien stille Stillstand-Fenster
    // (Frozen-Verband 16.11.2026, Handy verließ den Retry-Zustand nie wieder).

    /** Low-Rate-Modus nach Budgetverbrauch: endloser 5-Minuten-Takt. */
    private const val LOW_RATE_BACKOFF_MS = 300_000L
    /**
     * 0.16.4 (P42): Verbindungsversuche ohne Ende — MAX_RETRIES nur noch
     * theoretisch (Int.MAX_VALUE). Der 5er-Cap ließ die App nach 5 Fails
     * STILL stehen (kein weiterer Versuch, kein Netzwechsel-Reroute = das
     * Handy-Frozen am 16.11.2026). Rate-Disziplin = Low-Rate-Modus ab
     * [LOW_RATE_ATTEMPT_THRESHOLD] Fehlversuchen (0.16.4-Fixrunde: mit
     * Int.MAX_VALUE lieferte isExhausted NIE true — Low-Rate, Doze-Alarm im
     * Normalpfad und Termux-Revival waren tote Codepfade; Flagship-Prüfung
     * 16.11.2026).
     */
    private const val MAX_RETRIES = Int.MAX_VALUE
    /** Low-Rate (5-min-Takt, Doze-Alarm, Revival-Check) ab so vielen Fehlversuchen. */
    private const val LOW_RATE_ATTEMPT_THRESHOLD = 10
    /** Backoff ab dieser Dauer als Doze-sicheren Alarm legen (kein Coroutine-Sleep). */
    private const val ALARM_THRESHOLD_MS = 30_000L
    /** 429 → Relay-Block (300 s) abwarten + Puffer, Budget bleibt unberührt. */
    private const val BLOCK_WAIT_MS = 310_000L
    /** Auth-Fehlversuche, bevor die Versuche stoppen (Relay blockt bei 5). */
    private const val MAX_AUTH_FAILURES = 3

    // ── W17 (F1 + F2): Status-Texte der Kopplungs-Abweisung ──────────────────
    // RelayClient-Statusmeldungen sind bewusst Deutsch-Literale (bestehendes
    // Muster); die SNACK-/Dialog-Texte der UI laufen zusätzlich über
    // stringResource (3-fach symmetrisch in strings.xml).
    private const val CODE_IN_USE_MESSAGE =
        "Kopplung belegt: Dieser Code ist bereits mit einem Gerät verbunden — am verbundenen Gerät trennen, um hier zu koppeln."
    private const val PASSWORD_GATE_MESSAGE =
        "Kopplungspasswort nötig — Passwort in der Brücke eingeben (Einstellungen › Server)."

    // ── PH-FIX-CHAT-A (F4): Chat-Benachrichtigungen ──────────────────────
    // Eigener HIGH-Importance-Kanal — die FGS-Dauernotification
    // (BridgeConnectionService) ist IMPORTANCE_LOW + setOngoing und darf
    // als einzige laufende Notification bei Chat-Empfang nicht mitmischen.
    private const val CHAT_NOTIFY_CHANNEL = "fb_chat"

    /** Basis der Notification-IDs (plus ts % 100k — mehrere pro Tag kollidieren nie). */
    private const val CHAT_NOTIFY_ID_BASE = 9000
    /** PH-FIX-BACKEND-B: Backoff je hello_ack-Retry (failures × Basis, 1 s / 2 s). */
    private const val HELLO_ACK_RETRY_BACKOFF_MS = 1_000L

    // ── W21 ANRUF (P4-P6, roadmap-anruf.md §3.4/§3.6): Ring-Benachrichtigung ──
    // Klingel = eigener HIGH-Kanal MIT Vibrations-Muster (CallRules.RING_-
    // VIBRATION_PATTERN). Bewusst KANAL-Vibration statt Vibrator-API: die
    // Manifest-Erlaubnis android.permission.VIBRATE existiert NICHT (keine
    // Lane durfte sie nachtragen) — die Kanal-Schwingung läuft über die
    // erteilte POST_NOTIFICATIONS und stoppt mit dem Notification-Cancel
    // (Annahme/Ablehnen/Timeout/Ende). Heads-up (kein full-screen-lock):
    // USE_FULL_SCREEN_INTENT fehlt ebenfalls am Manifest — setFullScreenIntent
    // degradiert dort ehrlich auf Heads-up (§3.6-Wunsch, Bedarf notiert).
    private const val CALL_NOTIFY_CHANNEL = "fb_call"
    private const val CALL_NOTIFY_ID = 8000

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
    // W4-FIX-JAG2 (Drill-Hatch): lazy statt eager — der Trust-Schalter liest
    // Settings.Global beim ERSTEN Connect (BridgeApplication.instance + die
    // Drill-Einstellung sind dann gesetzt; beim Object-Init war beides null).
    // W17-WURZELFIX-4 (17.11. 13:5x, Emulator-Repro): lazy IST zu träge —
    // der Wert frisst sich beim ersten Connect fest. War da kein Device-Zert
    // (Migration/Erst-Start!), bleibt der Baublock OHNE mTLS-Socket für die
    // ganze Prozess-Lebensdauer: Zert kam zu spät (Renewal) → /ws verify=NONE
    // → Map-421. Neu: fresh-build je Zugriff (billig, Connect-Rhythmus ~50 s).
    private val client: OkHttpClient get() = MtlsSupport.decorate(
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
            // W4-FIX-JAG2 Drill-Hatch (Debug-BAUTEN ONLY): trust-all-Socket, wenn
            // Settings.Global `fb_debug_trust_drill` = 1 — der Emulator-Drill
            // braucht ein TLS-Testrelay mit eigener CA, das der App-Trust-Store
            // (conscrypt-APEX, SELinux-gesperrt) nicht aufnehmen kann. DOPPELT
            // verriegelt (BuildConfig.DEBUG + Settings-Knopf, OFF by default), im
            // Release-Zweig compilert der Zweig zu false → NIE aktiv. Release-TLS
            // bleibt unverändert (System-Trust + Pin-Verband); dies deckt NUR die
            // Zertifikats-VALIDATION des Drill-Sockets, NIEMALS NSC-Regeln und
            // NIEMALS den Pin-Verband eines https-Users. Vorbild-Muster:
            // PinSource.setPinningDisabledForDrill (Kill-Switch-Philosophie).
            .apply(fun okhttp3.OkHttpClient.Builder.() {
                if (!BuildConfig.DEBUG) return
                val appCtx = BridgeApplication.instance ?: return
                val drillTrust = runCatching {
                    android.provider.Settings.Global.getInt(
                        appCtx.contentResolver, "fb_debug_trust_drill", 0) == 1
                }.getOrDefault(false)
                if (!drillTrust) return
                val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sf = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tm, java.security.SecureRandom()) }.socketFactory
                sslSocketFactory(sf, tm[0] as javax.net.ssl.X509TrustManager)
                hostnameVerifier { _, _ -> true }
                Log.w(TAG, "DRILL-TRUST-ALL aktiv (Debug + Settings-Knopf) — NUR Drill-Socket")
            })
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

    /**
     * W4-FIX-JAG2 (C2): Frame-Queue mit Ordnungsgarantie. Vorher lief JE
     * WS-Frame in einer eigenen `scope.launch` auf dem Multi-Thread-IO-Pool —
     * jede Suspend-Stelle in [handleMessage] erlaubte Überholvorgänge (die
     * Narben J5/W1C-4 stammen genau daher). Jetzt: onMessage schreibt nur
     * (trySend, O(1)) in die FIFO-Queue, EIN Verbraucher-Lauf werkel die
     * Frames in Empfangs-Reihenfolge ab. UNLIMITED = ein voller Puffer
     * entwertet nichts (OkHttp liefern die Frames ohnehin sequenziell);
     * die Queue lebt pro Connect (doConnect baut sie, disconnect räumt).
     */
    private var inboundQueue: Channel<Pair<WebSocket, String>>? = null
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

    /**
     * W16 (Fixliste 14): Live-Arbeits-Kontext je Agent — Session-Quelle
     * (platform: bridge/discord/telegram/cron/…), das aktive Modell und
     * laufende Sub-Agenten. ALLE Felder optional: fehlt eines, lässt die
     * Dashboard-Zeile es weg (kein Platzhalter-Geraffel, Sir-Vorgabe).
     * Quelle: `session_meta`-Objekt am chat.activity-Frame (klare Felder,
     * enc-Spiegel liest es gleich mit) bzw. das eigenständige Frame
     * chat.session_meta / chat.session_meta.enc (Sub-Agent-Update MITTEN
     * im Turn — der activity-Wechsel ist dafür zu grob).
     */
    data class AgentSessionMeta(
        val platform: String? = null,
        val model: String? = null,
        val subAgents: List<AgentSubAgent> = emptyList(),
    )

    /** W16 (Fixliste 14): EIN Sub-Agent am Dashboard-Slot (eingerückte Zeile). */
    data class AgentSubAgent(
        val name: String,
        val status: String? = null,
        val model: String? = null,
    )

    // ── MEDIA-T1: Medien-/Datei-Versand im Chat (chunked, E2E-only) ───────

    /**
     * WELLE-13 P8 (Koordinator): lokale Auflösung eines empfangenen Mediums.
     * Die bytes kommen bereits vollständig aufs Handy (chat.media.enc-
     * Reassembly → filesDir/chat_media — saveInboundMedia), der Name-Teil
     * ist aber am Meta-Frame nicht trivial herleitbar → Verband: Scan nach
     * dem media_id-Präfix (32 Zeichen, saveInboundMedia-Konvention).
     * Rückgabe: absolutePath wenn vorhanden, sonst null (UI: „Nicht abrufbar").
     */
    fun resolveChatMediaPath(mediaId: String): String? {
        val ctx = appContext ?: return null
        if (mediaId.isBlank()) return null
        val dir = java.io.File(ctx.filesDir, "chat_media")
        if (!dir.exists()) return null
        val prefix = mediaId.take(32) + "-"
        val hit = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.maxByOrNull { it.lastModified() } ?: return null
        return hit.absolutePath
    }

    /**
     * WELLE-13 P8 (Koordinator): persistiert ein EIGENES Medium (bytes liegen
     * in-memory vom Rec-Picker/Recorder) app-privat ab — vorher hatte die
     * eigene Bubble localPath="" und Play schlug still fehl. Dateien landen
     * wie eingehende Medien unter filesDir/chat_media (hinter dem AppLock-Gate).
     * Rückgabe: absolutePath (null = Speichern gescheitert → Bubble ohne localPath).
     */
    fun persistOutboundMedia(name: String, blob: ByteArray): String? {
        val ctx = appContext ?: return null
        val dir = java.io.File(ctx.filesDir, "chat_media")
        if (!dir.exists() && !dir.mkdirs()) return null
        val mediaId = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "datei" }
        val target = java.io.File(dir, "$mediaId-$safe")
        val tmp = java.io.File(dir, ".${target.name}.part")
        return runCatching {
            tmp.writeBytes(blob)
            if (tmp.renameTo(target) || runCatching {
                    target.writeBytes(blob)
                    tmp.delete()
                    target
                }.getOrNull() === target
            ) target.absolutePath else null
        }.getOrNull()
    }

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

    /** W16 (Fixliste 14): max. Sub-Agenten-Zeilen je Agent-Slot (Meta ist kein Archiv). */
    private const val MAX_SESSION_META_SUB_AGENTS = 8

    /** Agenten-Liste (relay.state) — letzte Meldung des Relays. */
    private val _agents = MutableStateFlow<List<AgentEntry>>(emptyList())
    val agents: StateFlow<List<AgentEntry>> = _agents.asStateFlow()

    /**
     * APP-ANSCHLUSS-Verdrahtung (Multi-Gerät B4-Kontrakt, HEAD 733504c):
     * Geräte-Liste aus dem relay.state-Hallo-/Presence-Frame (devices[]) —
     * je Eintrag {device_id (NUR Maske "<masked:N>" — PII-Regel, _mask_id),
     * alias, admin, online, last_seen (Unix-ms, 0 = unbekannt), request_id
     * (B4-Revoke-Handle — einziges Zielträger-Konzept der App für das
     * device_revoke-Frame; Parität pairing.approve-Norm)}. REPLACE je Frame
     * (letzte Meldung des Relays, Parität agents); ohne devices-Feld
     * (alter Relay vor B4) bleibt die Liste leer (ehricher Leer-Hinweis).
     * Einträge tragen KEINE rohen Kennungen — nie (Server-PII-Regel).
     */
    private val _devices = MutableStateFlow<List<DeviceRules.DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceRules.DeviceEntry>> = _devices.asStateFlow()

    /** Empfangene Agenten-Antworten (chat.event) in Empfangs-Reihenfolge. */
    private val _chatEvents = MutableStateFlow<List<ChatMessageEvent>>(emptyList())
    val chatEvents: StateFlow<List<ChatMessageEvent>> = _chatEvents.asStateFlow()

    /** PH2-T10: Zustell-/Lese-Status je msg_id (queued/delivered/read). */
    private val _chatStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val chatStatus: StateFlow<Map<String, String>> = _chatStatus.asStateFlow()

    /**
     * ── B5 (Multi-Gerät, Sir-General-GO 17.11.): Sync-Echo-Empfang ───────────
     * chat.sync.enc = Echo des NUTZER-Texts eines ANDEREN Geräts (Backend B3:
     * je Geräte-Session verschlossen, Marker "sync" + "sync_of" = Ursprungs-
     * msg_id). INGEST-ONLY: Bubble + Platte, NIE sendChat (Loop-Schutz,
     * Roadmap Kap. 8 #6 — Beweis-Verband in ChatSyncRules-Doku + QA-Grep).
     */
    data class ChatSyncEvent(
        val chatId: String,
        val agentId: String,
        val text: String,
        val ts: Long,
        /** Ursprungs-msg_id des Posts (Platte + Chip-Verband + msg_id-Dedup). */
        val msgId: String?,
    )

    private val _chatSyncEvents = MutableStateFlow<List<ChatSyncEvent>>(emptyList())
    val chatSyncEvents: StateFlow<List<ChatSyncEvent>> = _chatSyncEvents.asStateFlow()

    /** B5: Anmelde-Tor — die ZULETZT empfangene pairing.request (UI/B7 bindet). */
    private val _pairingFrame = MutableStateFlow<PairingFrameRules.PairingFrame?>(null)
    val pairingFrame: StateFlow<PairingFrameRules.PairingFrame?> = _pairingFrame.asStateFlow()

    /**
     * ── W21 ANRUF (P4-P6 + G11/G12): die EINE Call-Session ───────────────────
     * Genau ein Anruf je Gerät (CallRules.inviteDecision weist Zweitrufe mit
     * call_declined.enc ab); null = kein Anruf. Die UI bindet: ankommender
     * Anruf = Vollbild-Karte (ChatScreen-Call-Mount), laufender Anruf =
     * Status-Zeile (Strip) mit Auflegen/Sprechen/Freigabe-Pill/Screenshot.
     */
    private val _callSession = MutableStateFlow<CallRules.CallSession?>(null)
    val callSession: StateFlow<CallRules.CallSession?> = _callSession.asStateFlow()

    /** Klingel-Fenster-Takt (RING → call_missed.enc, CallRules.RING_TIMEOUT_MS). */
    private var callRingTimeoutJob: Job? = null
    /** Sicherheits-Deckel des ACTIVEN Calls (CallRules.ACTIVE_CAP_MS → hangUp). */
    private var callDeckJob: Job? = null
    /** Turn-Zähler der Call-Aufnahme-Chunks (Name-Bindung, je Call neu). */
    private val callAudioChunkSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * ── B6 (Multi-Gerät): Backfill-Verband ───────────────────────────────────
     * backfillSyncing: Agent-ids, deren Verlauf gerade nachgeladen wird (dezente
     * Fortschritt-Zeile in der Conversation, KEIN Blockieren).
     * backfillEpochs: je Agent ein Zähler je gelandetem chat.backfill.enc —
     * ChatScreen liest die Platte (mergeBackfill fertig) und prepends.
     * statusSnapshot: ids-only Snapshot (msg_id→Status) — MONOTON gemerged
     * (ChatStatusRank.fuse, W20-Haken-Kette fällt nie zurück).
     */
    private val _backfillSyncing = MutableStateFlow<Set<String>>(emptySet())
    val backfillSyncing: StateFlow<Set<String>> = _backfillSyncing.asStateFlow()

    private val _backfillEpochs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val backfillEpochs: StateFlow<Map<String, Long>> = _backfillEpochs.asStateFlow()

    private val _statusSnapshot = MutableStateFlow<Map<String, String>>(emptyMap())
    val statusSnapshot: StateFlow<Map<String, String>> = _statusSnapshot.asStateFlow()

    /** B5/B6-Gate: je-Connect-Merker + Echo-FIFO (Frame-Coroutinen teilen sich). */
    private val frameGate = Any()
    private val backfillRequested = HashSet<String>()   // einmal je Connect × Agent
    private val syncEchoSeen = LinkedHashSet<String>()  // FIFO 256 (ChatSyncRules.SEEN_CAP)


    /** PH2-T10: Arbeitsstatus je agent_id (letzter chat.activity-Frame). */
    private val _agentWorking = MutableStateFlow<Map<String, AgentActivity>>(emptyMap())
    val agentWorking: StateFlow<Map<String, AgentActivity>> = _agentWorking.asStateFlow()

    /**
     * W16 (Fixliste 14): Session-Quelle + aktives Modell + Sub-Agenten je
     * agent_id — letzer bekannter chat.activity-`session_meta`-Wert bzw.
     * eigenständiges chat.session_meta(-enc)-Frame. Räumen am selben
     * Verbindungs-Punkt wie [agentWorking]: ohne Verbindung ist die
     * Session-Quelle gelogen (fehlende Daten = Zeile weglassen).
     */
    private val _agentMeta = MutableStateFlow<Map<String, AgentSessionMeta>>(emptyMap())
    val agentMeta: StateFlow<Map<String, AgentSessionMeta>> = _agentMeta.asStateFlow()

    /**
     * W16 (Fixliste 35): Modell-Katalog je agent_id — NUR die vom
     * Gateway/Adapter gelieferte Liste (agent.models / agent.models.enc).
     * REPLACE-Semantik je Frame; null = „models“-Feld fehlt → Bestand bleibt
     * (fail-soft), leere Liste = Adapter sagt explizit „keine Modelle“.
     * Räumen am selben Verbindungs-Punkt wie [agentMeta] (Parität).
     */
    private val _agentModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val agentModels: StateFlow<Map<String, List<String>>> = _agentModels.asStateFlow()

    /**
     * W19 (17.11.26, Sir-Befund): agent.models(-enc) liest ab jetzt AUCH
     * 'current' (das aktuell vom Gateway laufende Modell) je agent_id —
     * REPLACE je Frame; fehlernder/verletzter Key = Bestand bleibt
     * (fail-soft, Parität [AgentModelsPayload]). Räumen am selben
     * Verbindungs-Punkt wie [agentModels] (Parität). Der Wert ist REIN
     * serverseitig — die App kennt KEINE Modell-Namen und KEINE Defaults
     * (Sir-Generik-Regel: was der Frame nicht liefert, existiert app-seitig
     * nicht).
     */
    private val _agentModelCurrent = MutableStateFlow<Map<String, String>>(emptyMap())
    val agentModelCurrent: StateFlow<Map<String, String>> = _agentModelCurrent.asStateFlow()

    /**
     * W19 (17.11.26): Denk-Stufe des Gateways je agent_id (agent.models
     * 'effort', fail-soft REPLACE wie [agentModelCurrent]). Verbraucher:
     * Aktiv-Zeile im Modell-Sheet + Slider-Suggestion ohne App-Pref.
     */
    private val _agentModelEffort = MutableStateFlow<Map<String, String>>(emptyMap())
    val agentModelEffort: StateFlow<Map<String, String>> = _agentModelEffort.asStateFlow()

    /**
     * W18-Kette: Haupt-Arbeitsstatus je agent_id — agent.status-Frames des
     * Relays (parallel-gebaut: agent_id/working/started_at/label(≤64)/
     * sub{?id,name}/end). Letzter Frame gewinnt (chat.activity-Parität).
     * label = Server-Maschinenwort (z. B. „Sucht im Web“) — die UI stellt es
     * neben den Zeit-Timer, KEINE Client-Spezialfälle. Parsen+Faltung in
     * [AgentStatusRules] (JVM-getestet, fail-soft).
     */
    private val _agentStatus =
        MutableStateFlow<Map<String, AgentStatusRules.AgentStatusSlot>>(emptyMap())
    val agentStatus: StateFlow<Map<String, AgentStatusRules.AgentStatusSlot>> =
        _agentStatus.asStateFlow()

    /**
     * W18-Kette: laufende Sub-Agenten je agent_id (agent.status mit
     * sub != null): sub.end != true = Upsert, end == true = entfernen
     * (SIRS-Design — Slot verschwindet bei Ende). Key = sub.id (: sub.name).
     * Räumen am selben Verbindungs-Punkt wie [agentStatus] (Parität
     * agentWorking-Verband: ohne Verbindung lügt beides).
     */
    private val _agentSubs =
        MutableStateFlow<Map<String, Map<String, AgentStatusRules.SubAgentSlot>>>(emptyMap())
    val agentSubs: StateFlow<Map<String, Map<String, AgentStatusRules.SubAgentSlot>>> =
        _agentSubs.asStateFlow()

    /**
     * F1 (Sir, 18.11.2026, Verdrahtung MERGE-LANE): Cron-Jobs des GATEWAYS —
     * cron.status(-enc)-Frames des Adapters (Karte „Geplante Aufgaben“ am
     * Dashboard). APP-GENERISCH: der Frame trägt den GESAMT-Stand
     * ({jobs:[{name,status,next_at?}]}, next_at NUR planned — Contract
     * contrib/hermes-chat-plugin/adapter.py emit_cron_status); je Frame =
     * REPLACE. Klartext- UND enc-Pfad landen im selben Flow (enc: Payload
     * im ct, Kettensync-Pflicht wie agent.status.enc). Räumung an den 3
     * Verbindungspunkten (Parität markAgentsOffline).
     */
    private val _cronJobs =
        MutableStateFlow<List<DashboardRules.CronJobItem>>(emptyList())
    val cronJobs: StateFlow<List<DashboardRules.CronJobItem>> =
        _cronJobs.asStateFlow()

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

    /**
     * W18-1a: verdecktes FP-Abgleich-Ergebnis je agent_id — der chat.fp_check
     * (=enc)-Kontrollframe wird hier VERARBEITET (FP-Status), aber NIE als
     * Chat-Bubble persistiert/angezeigt (kein chatEvents-Eintrag, kein
     * Disk-Store, keine Notification). UI bindet die dismissbare Statuszeile
     * unter dem Chat-Kopf (ChatScreen FpCheckNotice) — keine Chat-Zeile.
     */
    data class FpCheckResult(
        val agentId: String,
        val ok: Boolean,
        val ts: Long,
    )

    private val _fpCheckResults = MutableStateFlow<Map<String, FpCheckResult>>(emptyMap())
    val fpCheckResults: StateFlow<Map<String, FpCheckResult>> = _fpCheckResults.asStateFlow()

    /** UI räumt das dismissbare Abgleich-Ergebnis ab (kein Dauerzustand). */
    fun clearFpCheckResult(agentId: String) {
        _fpCheckResults.update { it - agentId }
    }

    /**
     * W13-P2: sichtbarer Prekey-Fehler (null = keiner) — der Onboarding-
     * Fingerprint-Schritt zeigt diese Zeile STATT der stillen Warte-Box,
     * wenn das Relay dem Agenten nach allen Retries keinen spk_pub liefert.
     */
    private val _prekeyError = MutableStateFlow<String?>(null)
    val prekeyError: StateFlow<String?> = _prekeyError.asStateFlow()

    // ── W17 (F1 + F2): Kopplungs-Handshake-Signale für die UI ────────────────

    /**
     * W17-F2: Passwort-Tor des Servers — true, sobald der Kopplungs-Handshake
     * mit 403 password-required/password-invalid abgewiesen wurde. Die App
     * zeigt daraufhin ihr Passwort-Prompt; die Auto-Verbindungsversuche sind
     * bis zur Passwort-Eingabe gestoppt (sonst brennen Versuche im IP-Rate-
     * Chain-Schild des Relays). onOpen/connect räumen ihn.
     */
    private val _pairingPasswordNeeded = MutableStateFlow(false)
    val pairingPasswordNeeded: StateFlow<Boolean> = _pairingPasswordNeeded.asStateFlow()

    /** UI räumt den Prompt-Merker ab (Dialog verworfen/bedient). */
    fun acknowledgePasswordPrompt() {
        _pairingPasswordNeeded.value = false
    }

    /**
     * W17-F1: code-in-use-Beobachtungs-Zähler (Snack-Auslöser in der UI; Start
     * 0). Jede 403-code-in-use-Antwort (+ WS-Close 4403, Race-Slot) erhöht ihn:
     * die UI reagiert je Anstieg mit EINEM Snack — KEIN Auth-Fehlversuch (der
     * Code war korrekt), die Reconnect-Kette läuft zurückgesetzt weiter.
     */
    private val _codeInUseObserved = MutableStateFlow(0)
    val codeInUseObserved: StateFlow<Int> = _codeInUseObserved.asStateFlow()

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

    /**
     * W13-P2: agentId → key_get-Versuche mit leerem spk_pub (max.
     * AgentPrekeyPolicy.MAX_ATTEMPTS gesamt, danach sichtbare Fehlerzeile).
     * Zurückgesetzt je geliefertem Prekey + je frischem Connect-Zyklus.
     */
    private val prekeyFailures = ConcurrentHashMap<String, Int>()

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
        // (PairingManager/RelayClient) folgt dem AKTIVEN Profil,
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
        // W13-P2: frisches Prekey-Retry-Budget + Fehlerzeile je Connect-Zyklus
        // (ein Reconnect ist die User-Heilung, siehe Bug-1-Härtung).
        prekeyFailures.clear()
        _prekeyError.value = null
        // W17-F2: frischer Connect-Zyklus räumt den Passwort-Tor-Merker MIT —
        // ein bewusstes Verbinden (mit gespeichertem/neu eingegebenem Pass-
        // wort) ist die User-Heilung, der Prompt soll nicht kleben.
        _pairingPasswordNeeded.value = false
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
        // W4-FIX-JAG2 (C2): Queue mit dem Scope räumen — späte onMessage-
        // Einreihungen der alten Verbindung landen in der Tonne (der Socket
        // wird ohnehin gekappt und generation+1 bricht die Handler früh).
        inboundQueue?.close()
        inboundQueue = null
        isConnected = false
        // PH-FIX-CHAT-A (F5): bewusstes Trennen räumt die working-States
        // ebenso — ohne Verbindung kann kein Agent „arbeiten“.
        _agentWorking.value = emptyMap()
        // W16 (Fixliste 14): Session-Quelle/Modell/Sub-Agenten ebenso —
        // ohne Verbindung ist die Meta gelogen (Parität agentWorking).
        _agentMeta.value = emptyMap()
        // W16 (Fixliste 35): Modell-Katalog ebenso (Parität agentMeta)
        // + W19 (17.11.26): Gateway-Modell-/Denken-Stand ebenso.
        _agentModels.value = emptyMap()
        _agentModelCurrent.value = emptyMap()
        _agentModelEffort.value = emptyMap()
        // W18-Kette: agent.status-Hauptstatus + Sub-Slots ebenso — ohne
        // Verbindung lügt beides (Parität agentWorking-Verband).
        _agentStatus.value = emptyMap()
        _agentSubs.value = emptyMap()
        // F1 (cron.status-Verband): Cron-Jobs ebenso — ohne Verbindung lügt
        // der letzte Karten-Stand weiter (Parität markAgentsOffline).
        _cronJobs.value = emptyList()
        // PH-FIX-CHAT-A (F6): bewusstes Trennen markiert die Agenten ebenso
        // offline (onClosed läuft hier nicht — disconnect() bumpt generation,
        // die Callbacks kehren früh zurück).
        markAgentsOffline()
        // APP-ANSCHLUSS: Geräte-Liste ebenso — ohne Verbindung „online“ gelogen.
        markDevicesOffline()
        // W21 ANRUF (P6): bewusstes Trennen während eines Calls — sauber beenden
        // (genau wie bei totem Socket; kein Signal, kein Klingeln-Überbleibsel).
        endCallOnConnectionLost()
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
        // W17-F1/F2: Der Kopplungs-Handshake trägt Code (Bearer) + stabile
        // Gerätekennung (X-Device-Id — Server registriert sie bei der ersten
        // Annahme, desselben Geräts Reconnect geht damit IMMER durch) und —
        // nur wenn eins gesetzt ist — das Kopplungspasswort (X-Pairing-
        // Password; verlangt der Server es und fehlt es, antwortet er 403
        // password-required und die App zeigt ihr Passwort-Prompt).
        val storedPassword = ServerProfiles.activePassword()
        val request = runCatching {
            var builder = Request.Builder()
                .url(buildWsUrl(serverUrl))
                .header("Authorization", "Bearer $pairingCode")
                // W17-F1: per-Install stabile Gerätekennung (Vault-Verband).
                .header(
                    PairingHandshakePolicy.HEADER_DEVICE_ID,
                    PairingManager.deviceId(),
                )
            if (PairingHandshakePolicy.passwordAllowed(storedPassword)) {
                // W17-F2: exakt, ohne Normalisieren (kein trim — der Hash
                // am Server rechnet über die ROTE Zeichenkette).
                builder = builder.header(
                    PairingHandshakePolicy.HEADER_PASSWORD,
                    storedPassword,
                )
            }
            builder.build()
        }.getOrElse { e ->
            Log.w(TAG, "Ungültige Server-Adresse: ${e.javaClass.simpleName}")
            notifyStatus(false, "Server-Adresse ungültig — Format: https://dein-relay.example", Phase.FAILED)
            generation = myGeneration - 1
            return
        }

        // W4-FIX-JAG2 (C2): Queue + EIN Verbraucher je Connect — startet VOR
        // dem WebSocket (onMessage kann nie in eine leere Queue schreiben).
        // Vorherige Queue schließen (Auth-Sonde/Re-Dock laufen ohne disconnect):
        // deren Verbraucher endet mit dem Loop-Schluss, es bleibt genau EIN
        // lebender Verbraucher je Generation.
        inboundQueue?.close()
        val queue: Channel<Pair<WebSocket, String>> = Channel(Channel.UNLIMITED)
        inboundQueue = queue
        scope?.launch {
            for ((ws, frame) in queue) {
                try {
                    handleMessage(ws, frame)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "Frame-Verarbeitung fehlgeschlagen: ${e.message}")
                }
            }
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
                // W17-F2: Kopplung durch — Passwort-Tor räumen (frische
                // Session-Wahrheit; ein gespeichertes/richtiges Passwort
                // hat den Handshake durchgelassen).
                _pairingPasswordNeeded.value = false
                // B5/B6 (Multi-Gerät): frische Connect-Semantik — der Backfill-
                // Verlauf-Request-Spielraum gilt je Connect EINMAL je Agent.
                synchronized(frameGate) { backfillRequested.clear() }
                _backfillSyncing.value = emptySet()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // W4-FIX-JAG2 (C2): O(1)-Einreihung in die FIFO-Queue statt
                // je-Frame scope.launch — der EINe Verbraucher abarbeitet die
                // Frames in Empfangs-Reihenfolge (keine Überholemöglichkeit
                // mehr an Suspend-Stellen von handleMessage).
                inboundQueue?.trySend(webSocket to text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                if (PairingHandshakePolicy.closeCodeMeansCodeInUse(code)) {
                    // W17-F1 (Race-Slot): das Relay musste NACH dem Upgrade
                    // abweisen (Post-Precheck-Rennen) — gleiche Semantik wie
                    // der 403-Kanal: KEIN Auth-Fehlversuch, Snack-Auslöser,
                    // Reconnect-Backoff läuft normal weiter.
                    _codeInUseObserved.value = _codeInUseObserved.value + 1
                    notifyStatus(false, CODE_IN_USE_MESSAGE, Phase.FAILED)
                    scheduleReconnect()
                    return
                }
                // PH-FIX-CHAT-A (F5): working-States sind bei toter Verbindung
                // verwaist (activity-Reset-Frames laufen ins TCP-Nirvana) —
                // räumen, sonst countet der Kopf-Spinner ewig weiter.
                _agentWorking.value = emptyMap()
                // W16 (Fixliste 14): Meta ebenso (Parität agentWorking).
                _agentMeta.value = emptyMap()
                // W16 (Fixliste 35): Modell-Katalog ebenso (Parität agentMeta)
                // + W19 (17.11.26): Gateway-Modell-/Denken-Stand ebenso.
                _agentModels.value = emptyMap()
                _agentModelCurrent.value = emptyMap()
                _agentModelEffort.value = emptyMap()
                // W18-Kette: agent.status + Sub-Slots ebenso (Parität agentWorking).
                _agentStatus.value = emptyMap()
                _agentSubs.value = emptyMap()
                // F1 (cron.status-Verband): Cron-Jobs ebenso — ohne Verbindung
                // lügt der letzte Karten-Stand weiter (Parität markAgentsOffline).
                _cronJobs.value = emptyList()
                // PH-FIX-CHAT-A (F6): die Agenten-Liste ist der letzte
                // relay.state-Snapshot — ohne Verbindung ist JEDE „online“-
                // Anzeige gelogen (Presence-Push ist Ein-Weg ohne Spool).
                markAgentsOffline()
                // APP-ANSCHLUSS: Geräte-Liste ebenso (Parität markAgentsOffline).
                markDevicesOffline()
                endSession()
                // W21 ANRUF (P6): Verbindung weg während eines Calls — sauber
                // beenden (kein Senden an der toten WS möglich; Klingel-/Timer-
                // Räumung läuft mit, der Adapter holt das End-Signal selbst).
                endCallOnConnectionLost()
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
                // W16 (Fixliste 14): Meta ebenso (Parität agentWorking).
                _agentMeta.value = emptyMap()
                // W16 (Fixliste 35): Modell-Katalog ebenso (Parität agentMeta)
                // + W19 (17.11.26): Gateway-Modell-/Denken-Stand ebenso.
                _agentModels.value = emptyMap()
                _agentModelCurrent.value = emptyMap()
                _agentModelEffort.value = emptyMap()
                // W18-Kette: agent.status + Sub-Slots ebenso (Parität agentWorking).
                _agentStatus.value = emptyMap()
                _agentSubs.value = emptyMap()
                // F1 (cron.status-Verband): Cron-Jobs ebenso — ohne Verbindung
                // lügt der letzte Karten-Stand weiter (Parität markAgentsOffline).
                _cronJobs.value = emptyList()
                // PH-FIX-CHAT-A (F6): wie onClosed — Snapshot „online“ ist
                // ohne Verbindung gelogen.
                markAgentsOffline()
                // APP-ANSCHLUSS: Geräte-Liste ebenso (Parität markAgentsOffline).
                markDevicesOffline()
                endSession()
                // W21 ANRUF (P6): Verbindung weg während eines Calls — sauber
                // beenden (kein Senden an der toten WS möglich; Klingel-/Timer-
                // Räumung läuft mit, der Adapter holt das End-Signal selbst).
                endCallOnConnectionLost()
                // v0.12.0 (Akku P0-2): Leakschutz — wie onClosed.
                EventStore.highVolumeEvents = false
                EventStore.streamingEnabled = false
                // v0.12.0 QA-Fix (SK-02/CHEF-02): Service-QUELLE drosseln.
                BridgeAccessibilityService.instance?.applyEventThrottle(false)
                notifyStatus(false, errorDetail, Phase.FAILED)

                // ── W17 (F1 + F2): Maschinen-Code des Kopplungs-Handshakes ──
                // Das Relay trägt je abgewiesenem Handshake X-Bridge-Error
                // (Header-Kanal, auch für aiohttp-Clients lesbar). Diese zwei
                // Fälle sind KEINE Auth-Ausrutscher im hergebrachten Sinn:
                // code-in-use = der Code war KORREKT (kein Streak-Bump, keine
                // Sperre — ordentliches Warten auf die Wiederaufnahme);
                // password-* = Passwort-Tor (Versuche STOPPEN, App-Prompt).
                val handshakeError = response?.headers
                    ?.get(PairingHandshakePolicy.HEADER_ERROR)
                    ?.let { PairingHandshakePolicy.handshakeError(it) }
                if (PairingHandshakePolicy.isCodeInUse(handshakeError)) {
                    _codeInUseObserved.value = _codeInUseObserved.value + 1
                    notifyStatus(false, CODE_IN_USE_MESSAGE, Phase.FAILED)
                    // Reconnect-Backoff normal — ist der Inhaber tot, läuft
                    // die Wiederaufnahme ohne Neukopplung durch (kein Lock).
                    scheduleReconnect()
                    return
                }
                if (PairingHandshakePolicy.isPasswordGate(handshakeError)) {
                    // Versuche sofort STOPPEN (das Relay zählt Fehl-Eingaben
                    // in die IP-Rate-Chain — Endlos-Retry lockt die eigene
                    // IP exakt wie in der Vorfallskette). Die UI öffnet das
                    // Passwort-Prompt; connect() räumt den Merker neu.
                    _pairingPasswordNeeded.value = true
                    Log.w(TAG, "Passwort-Tor am Relay ($handshakeError) — Retry gestoppt, Prompt an UI")
                    shouldReconnect = false
                    generation++
                    sessionStartedNs = 0L
                    this@RelayClient.webSocket?.cancel()
                    this@RelayClient.webSocket = null
                    notifyStatus(false, PASSWORD_GATE_MESSAGE, Phase.FAILED)
                    return
                }

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
        // 0.16.4-Fixrunde: Schwellwert entkoppelt von isExhausted (mit
        // MAX_RETRIES = Int.MAX_VALUE wäre isExhausted nie wahr → Low-Rate,
        // Doze-Alarm im Normalpfad und Termux-Revival wären tot).
        val enteringLowRate = reconnectPolicy.attempts >= LOW_RATE_ATTEMPT_THRESHOLD

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
        // P3 (0.16.4-Fixrunde): bei Int.MAX_VALUE keine Absurdi-Ausgabe
        // ("Versuch 12/2147483647") — endlos = beschriftet.
        val limitLabel = if (reconnectPolicy.limit >= Int.MAX_VALUE) "läuft endlos weiter"
        else "/${reconnectPolicy.limit}"
        reconnectJob = activeScope.launch {
            Log.i(TAG, "Reconnecting in ${backoff}ms... (attempt $attempt$limitLabel)")
            notifyStatus(false, "$label in ${backoff / 1000}s (Versuch $attempt$limitLabel)", Phase.RECONNECTING)
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

    /**
     * APP-ANSCHLUSS-Verdrahtung: Geräte-„online“-Merker räumen (Parität
     * [markAgentsOffline]) — der devices[]-Snapshot ist an den Socket
     * gebunden; stirbt die Phone-WS (onClosed/onFailure) oder trennt Sir
     * bewusst, ist JEDE „online“-Anzeige der Liste gelogen (Presence-Push
     * ist Ein-Weg ohne Spool). Liste bleibt mit last_seen erhalten, der
     * nächste relay.state-Frame ersetzt sie ohnehin vollständig. update{}
     * = atomar (SEC-W1 W1C-4-Muster).
     */
    private fun markDevicesOffline() {
        _devices.update { current ->
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
        // PH-7/PS-G-7 (play-Edition): RUN_COMMAND-Permission ist im play-Manifest
        // entfernt — der Revival-Watchdog ist play-seitig bewusst ein no-op.
        if (BuildConfig.EDITION_PLAY) return
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
        var useTls = trimmed.startsWith("https://") || trimmed.startsWith("wss://")
        // W4-FIX-JAG2 (S1, Deep-Defense): ein Release-Bau baut Klartext-URLs
        // auf TLS — Klartext-Transport gehört NUR dashboard.lan (NSC-Ausnahme)
        // im Dev-Kontext (siehe ServerProfileRules.relayUrlValidForConnect);
        // die Validierung (Onboarding/Store) fängt das früher, das bleibt die
        // Backstop-Schiene am Socket-Bau.
        if (!useTls && !BuildConfig.DEBUG && !ServerProfileRules.isCleartextExceptionHost(trimmed)) {
            useTls = true
        }
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
                // W16 (Fixliste 14): Session-Meta eigenständig — Sub-Agenten/
                // Plattform-Update MITTEN im Turn (Aktivitätswechsel ist zu
                // grob dafür). Klartext = Legacy-Pfad, enc = E2E-Spiegel.
                "chat.session_meta" -> { handleChatSessionMetaFrame(json); return }
                "chat.session_meta.enc" -> { handleChatSessionMetaEnc(json); return }
                // W16 (Fixliste 35): Modell-Katalog eigenständig (klar + enc) —
                // Quelle des Modell&Denken-Pickers (nur Gateway-gelieferte IDs).
                "agent.models" -> { handleAgentModelsFrame(json); return }
                "agent.models.enc" -> { handleAgentModelsEnc(json); return }
                // W18-Kette: agent.status — Haupt-Arbeitsstatus + Sub-Agent-
                // Slots (klar + enc-Spiegel; enc: Kettensync PFLICHT, Felder
                // bleiben KLAR wie beim chat.activity.enc-Verband).
                "agent.status" -> { handleAgentStatusFrame(json); return }
                "agent.status.enc" -> { handleAgentStatusEnc(json); return }
                // F1 (18.11.2026): cron.status — Gateway-Cron-Jobs (Karte
                // „Geplante Aufgaben“; klar + enc, enc-Payload = {jobs: …} im ct).
                "cron.status" -> { handleCronStatusFrame(json); return }
                "cron.status.enc" -> { handleCronStatusEnc(json); return }
                // MEDIA-T1: chunked Media (E2E-only) + Chunk-Acks (Progress).
                "chat.media.enc" -> { handleChatMediaEnc(json); return }
                "chat.media.chunk_ack" -> { handleMediaChunkAck(json); return }
                // W18-1a: verdeckter FP-Abgleich-Kontrollframe — wird VERARBEITET
                // (FP-Status: Banner bei Abweichung + Ergebnis-Zeile), aber NIE
                // als Chat-Bubble persistiert/angezeigt (FpCheckRules-Doku).
                "chat.fp_check" -> { handleChatFpCheckFrame(json); return }
                "chat.fp_check.enc" -> { handleChatFpCheckEnc(json); return }
                "crypto.hello_ack" -> { handleCryptoHelloAck(json); return }
                "crypto.session_reset" -> { handleCryptoSessionReset(json); return }
                // B5 (Multi-Gerät): Sync-Echo fremder eigener Nachrichten —
                // INGEST-ONLY (Bubble + Platte, NIE sendChat: Loop-Schutz).
                "chat.sync.enc" -> { handleChatSyncEnc(json); return }
                // B5: Anmelde-Tor — pairing.request → StateFlow (UI/B7 bindet),
                // Senden über sendPairingApprove/sendPairingDeny (sendRawFrame).
                "pairing.request" -> { handlePairingRequestFrame(json); return }
                // W21 ANRUF (P4): Call-Signaling Agent→Phone — opak Pass-Through
                // des Relays (Inhalt NUR im ct; Klar = chat_id/call_id/agent_id/
                // session/hdr). Empfang fail-closed je E2E-Session (Kettensync).
                "call_invite.enc" -> { handleCallInviteEnc(json); return }
                "call_end.enc" -> { handleCallEndEnc(json); return }
                // B6: Backfill-Verlauf-Batch je frisches Gerät (K ≤ 200) +
                // ids-only Status-Snapshot — Chips über die msg_id-Bindung.
                "chat.backfill.enc" -> { handleChatBackfillEnc(json); return }
                "chat.status_snapshot" -> { handleStatusSnapshotFrame(json); return }
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
                // W18-B (Sir-Fund, Volltest 17.11.): AUTOMATISCHER Neuaufbau —
                // der WebSocket steht, nur die Session ist serverseitig weg
                // (Relay-Restart). Derselbe Verband wie beim benignen
                // crypto.session_reset (SIRS-01): sofort frischer crypto.hello-
                // Handshake; der User verbindet NICHT manuell für einen
                // Relay-Restart. Scheitert der Aufbau dauerhaft, übernimmt
                // der HelloAckFailurePolicy-Banner die ehrliche Meldung.
                startHandshake(agentId)
                // (C) Meldung nach der AKTIONEN-MIT-ERGEBNIS-Regel: WAS kaputt
                // ist + WAS passiert (Auto-Neuaufbau, kein manuelles Neuverbinden).
                publishE2eSessions()
                _e2eBanner.value =
                    "E2E-Sitzung wurde serverseitig zurückgesetzt — Sitzung wird automatisch neu aufgebaut."
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
        // APP-ANSCHLUSS-Verdrahtung: devices[] daneben mitlesen (B7-Geräte-
        // Karte bindet den [devices]-Flow; Felder oben im Frame-Verband).
        parseRelayStateDevices(json)
        // PH-3 Welle 2: E2E-Handshake je onlineem Agenten (crypto.hello über
        // POST /crypto_hello; Identity-Publish läuft parallel beim onOpen).
        maybeStartHandshakes()
    }

    /**
     * APP-ANSCHLUSS-Verdrahtung (Multi-Gerät, B4-Kontrakt aus dem Backend
     * GELESEN — HEAD 733504c, tools/android_relay.py _send_phone_state):
     * devices[] des relay.state-Frames → [devices]-Flow. Je Eintrag
     * {device_id, alias, admin, online, last_seen, request_id}; Felder
     * fail-soft (fehlende = ehrliche Defaults wie bei der agents-Parse),
     * Eintrag OHNE device_id wird übersprungen. Fehlt das devices-Feld
     * (alter Relay vor B4), bleibt die Liste LEER — die Geräte-Karte zeigt
     * ihren ehrlichen Leer-Hinweis, kein Fake-Bestand.
     */
    private fun parseRelayStateDevices(json: JsonObject) {
        val devicesArray = json.get("devices") as? JsonArray
        val parsed = if (devicesArray == null) {
            emptyList()
        } else {
            devicesArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                // device_id = PII-MASKE des Relays ("<masked:N>") — bewusst
                // das Einzige, was die App je hält (nie rohe Kennungen).
                val deviceId = stringOf(obj, "device_id")
                if (deviceId.isNullOrEmpty()) return@mapNotNull null
                DeviceRules.DeviceEntry(
                    deviceId = deviceId,
                    alias = stringOf(obj, "alias").orEmpty(),
                    admin = boolOf(obj, "admin") ?: false,
                    online = boolOf(obj, "online") ?: false,
                    lastSeenMs = longOf(obj, "last_seen") ?: 0L,
                    // B4-Revoke-Handle (Antwortträger des device_revoke-
                    // Frames, Parität pairing.approve). Leerer Handle =
                    // alter Relay → die UI verweigert das Senden.
                    requestId = stringOf(obj, "request_id").orEmpty(),
                )
            }
        }
        _devices.value = parsed
        if (BuildConfig.DEBUG) Log.d(TAG, "relay.state: ${parsed.size} Gerät(e)")
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
        // W18-1a: FP-Ergebnis als LEGACY-Klartext-Antwort ('Fingerprint-Abgleich:'
        // Präfix) — dieser Draht läuft auf den VERDECKTEN Kontrollframe-Weg
        // (Banner/Ergebnis-Zeile), KEINE Chat-Bubble.
        if (FpCheckRules.isFpResultText(body)) {
            handleChatFpCheckResult(agentId, chatId, ts, ok = null, text = body)
            return
        }
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
     *
     * HAKEN-FIX (1)+(2): JEDER RANG-GEWINNENDE Stand wird zusätzlich in den
     * Side-Car geschrieben (ChatHistoryStore.recordStatus — SYNCHRON, winzige
     * Append-Zeile: Write-Back kann nie hinter dem Rauswurf hinken). Die
     * Eviction ist rang-priorisiert (ChatStatusRank.evictionVictims: queued
     * vor delivered vor failed vor read, Insertion-Order-Tie-Break) und
     * schreibt jeden Rausschmeißer VOR dem Entfernen auf die Platte — die
     * >500-Eviction (Medien-Chunks!) drückt einen Chip dadurch NIE mehr auf
     * 1 Haken zurück, und 'read' wird praktisch nie ungepersisted evictet.
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
        val root = appContext?.let { ChatHistoryStore.root(it) }
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
                // HAKEN-FIX (2): rang-priorisierte Eviction mit Write-Back VOR
                // jeder Entfernung — der Side-Car hält jeden Stand, bevor die
                // Live-Map ihn verliert (>500-Eviction kippt Chips nie mehr);
                // ohne appContext (Fehl-Kontext) bleibt der Alt-Fallback
                // (RAM-Evict ohne Platte). CAS-Retries = Duplikat-Zeilen im
                // Side-Car — fold-idempotent, schadlos.
                while (next.size > MAX_CHAT_STATUS) {
                    val victimId = ChatStatusRank.evictionVictims(next).firstOrNull()
                        ?: break
                    if (root != null) {
                        runCatching {
                            ChatHistoryStore.recordStatus(root, victimId, next.getValue(victimId))
                        }
                    }
                    next.remove(victimId)
                }
                next
            }
        }
        // Der RANG-GEWINNENDE Frame selbst → Side-Car (Platte = höchster
        // bekannter Stand; Neustart stellt delivered/read daraus wieder her).
        // Duplikat- zur Eviction-Zeile ist fold-idempotent.
        if (root != null) {
            runCatching { ChatHistoryStore.recordStatus(root, msgId, status) }
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
        // W16 (Fixliste 14): session_meta am Activity-Frame (KLARE Felder —
        // im enc-Mirror liest der Phone-Handler das Klartext-Top-Level
        // gleich mit; der verschlüsselte Payload ist reiner Kettensync).
        applySessionMeta(agentId, parseSessionMeta(json.get("session_meta")))
        if (BuildConfig.DEBUG) Log.d(TAG, "chat.activity: agent ${agentId.take(24)} working=$working")
    }

    /**
     * W16 (Fixliste 14): `session_meta`-Objekt streng lesen — nur
     * wohlgeformte Felder übernehmen: platform/model sind kurze nicht-leere
     * Strings, Sub-Agenten brauchen einen Namen; Name-freie Einträge fliegen
     * (fehlende Daten = Zeile weglassen, nie Platzhalter). Ohne EINEN
     * gültigen Inhalt → null (es gibt nichts anzuzeigen).
     */
    private fun parseSessionMeta(raw: Any?): AgentSessionMeta? {
        val obj = raw as? JsonObject ?: return null
        val platform = runCatching {
            obj.get("platform")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull()?.ifBlank { null }
        val model = runCatching {
            obj.get("model")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        }.getOrNull()?.ifBlank { null }
        val subAgents = (runCatching {
            obj.get("sub_agents") as? JsonArray
        }.getOrNull())?.let { arr ->
            arr.mapNotNull { element ->
                val sa = element as? JsonObject ?: return@mapNotNull null
                val name = runCatching {
                    sa.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                }.getOrNull()?.ifBlank { null } ?: return@mapNotNull null
                val status = runCatching {
                    sa.get("status")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                }.getOrNull()?.ifBlank { null }
                val model = runCatching {
                    sa.get("model")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                }.getOrNull()?.ifBlank { null }
                AgentSubAgent(name, status, model)
            }
        } ?: emptyList()
        if (platform == null && model == null && subAgents.isEmpty()) return null
        return AgentSessionMeta(platform, model, subAgents.take(MAX_SESSION_META_SUB_AGENTS))
    }

    /**
     * W16: geparste session_meta veröffentlichen (atomar). REPLACE-Semantik:
     * das Frame trägt immer die VOLLSTÄNDIG gewollte Meta — ein Teil-Update
     * (nur Sub-Agenten) schreibt die anderen Felder bewusst mit.
     */
    private fun applySessionMeta(agentId: String, meta: AgentSessionMeta?) {
        if (agentId.isEmpty() || meta == null) return
        _agentMeta.update { it + (agentId to meta) }
    }

    /**
     * W16 (Fixliste 14): chat.session_meta → [agentMeta]-Flow. Der Adapter
     * hat keine session_meta am laufenden activity-Wechsel (z. B. Sub-Agent
     * spawnt mitten im Turn) — der Gateway-Hook schickt dieses Frame
     * eigenständig. Downgrade-Guard wie chat.event: bei aktiver E2E-Session
     * ist der Klartext-Frame verworfen (der Adapter sendet enc).
     */
    private fun handleChatSessionMetaFrame(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        runCatching { cryptoSessions[agentId] }.getOrNull()?.let { peer ->
            if (peer.isActive()) {
                Log.w(
                    TAG,
                    "chat.session_meta (Klartext) für Agent ${agentId.take(24)} verworfen — " +
                        "E2E-Session aktiv (Downgrade-Schutz)",
                )
                return
            }
        }
        applySessionMeta(agentId, parseSessionMeta(json.get("session_meta")))
    }

    /**
     * W16 (Fixliste 14): chat.session_meta.enc-Spiegel — Payload
     * entschlüsseln (Kettensync PFLICHT, sonst desync) und die
     * session_meta NUR aus dem Payload lesen (kein Klartext-Inhalt).
     */
    private fun handleChatSessionMetaEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: return
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "PH-3: chat.session_meta.enc verworfen (Entschlüsselung fehlgeschlagen)")
            return
        }
        handleRotateHint(json, agentId, peer)
        applySessionMeta(agentId, parseSessionMeta(payload.get("session_meta")))
    }

    /**
     * W16 (Fixliste 35): agent.models (Klartext-Legacy) → [agentModels]-Flow.
     * W19 (17.11.26): zusätzlich 'current' + 'effort' lesen →
     * [agentModelCurrent] / [agentModelEffort] ([AgentModelsPayload], fail-soft
     * je Key). Downgrade-Guard in chat.session_meta-Parität: bei aktiver
     * E2E-Session ist der Klartext-Frame verworfen (der Adapter sendet enc).
     */
    private fun handleAgentModelsFrame(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        runCatching { cryptoSessions[agentId] }.getOrNull()?.let { peer ->
            if (peer.isActive()) {
                Log.w(
                    TAG,
                    "agent.models (Klartext) für Agent ${agentId.take(24)} verworfen — " +
                        "E2E-Session aktiv (Downgrade-Schutz)",
                )
                return
            }
        }
        applyAgentModelsFrame(agentId, AgentModelsPayload.parse(json))
    }

    /**
     * W16 (Fixliste 35): agent.models.enc — Payload entschlüsseln (Kettensync
     * PFLICHT, sonst desync) und die Auswirkung NUR aus dem verschlüsselten
     * Payload lesen (kein Klartext-Fallback). W19 (17.11.26): derselbe Eintritt
     * wie Klartext — Katalog + 'current' + 'effort' ([AgentModelsPayload]).
     */
    private fun handleAgentModelsEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: return
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "PH-3: agent.models.enc verworfen (Entschlüsselung fehlgeschlagen)")
            return
        }
        handleRotateHint(json, agentId, peer)
        applyAgentModelsFrame(agentId, AgentModelsPayload.parse(payload))
    }

    /**
     * W19 (17.11.26): geparste agent.models-Auswirkung veröffentlichen —
     * atomar (update{}-CAS, W1C-4-StateFlow-Verband) und je Komponente
     * unabhängig fail-soft: null (Key fehlt/verletzt) = DIESES Feld bleibt
     * unangetastet; REPLACE je agent_id nur bei vorhandenem gültigem Wert
     * (letzter Frame gewinnt). Klartext- UND enc-Pfad laufen über denselben
     * Eintritt. KEIN Modell-Name kommt aus der App — ausschließlich aus dem
     * Payload ([AgentModelsPayload], Sir-Generik-Regel).
     */
    private fun applyAgentModelsFrame(agentId: String, frame: AgentModelsPayload.Frame?) {
        if (agentId.isEmpty() || frame == null) return
        frame.models?.let { models -> _agentModels.update { it + (agentId to models) } }
        frame.current?.let { current ->
            _agentModelCurrent.update { it + (agentId to current) }
        }
        frame.effort?.let { effort -> _agentModelEffort.update { it + (agentId to effort) } }
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
                // W13-P2: key_get unterscheidet jetzt die Eckfälle — Ok =
                // Handshake läuft weiter, MissingPrekey = Retry/Status statt
                // stiller CryptoFrameException, null = HTTP-/Netz-Fehler (wie bisher).
                val agentKeys = when (val keys = keyGet(base, agentId)) {
                    is AgentPrekeyPolicy.KeyGetResult.Ok -> {
                        // Erfolg = Prekey kam nach / Neu-Connect → Budget + alte
                        // Fehlerzeile zurücksetzen (Onboarding heilt sichtbar).
                        prekeyFailures.remove(agentId)
                        _prekeyError.value = null
                        keys
                    }
                    is AgentPrekeyPolicy.KeyGetResult.MissingPrekey -> {
                        onPrekeyMissing(agentId)
                        return@launch
                    }
                    null -> return@launch
                }
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

    /**
     * W13-P2: /key_get je Agent — Ok = Keys, MissingPrekey = HTTP 200 mit
     * leerem/fehlendem spk_pub (Relay kennt den Agent-Prekey nicht),
     * null = HTTP-/Netz-/Parse-Fehler (fail-closed, wie bisher).
     * Parse/Klassifikation steckt JVM-testbar in AgentPrekeyPolicy.
     */
    private fun keyGet(base: String, agentId: String): AgentPrekeyPolicy.KeyGetResult? = runCatching {
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
            val obj = runCatching {
                JsonParser.parseString(response.body?.string() ?: "").asJsonObject
            }.getOrNull()
            AgentPrekeyPolicy.classify(obj)
        }
    }.getOrNull()

    /**
     * W13-P2 (Jäger-Befund 12.11.26): key_get liefert 200 ohne spk_pub →
     * NIE mehr stiller Handshake-Tod im „Warte auf E2E-Handshake“-Kasten.
     * Max. [AgentPrekeyPolicy.MAX_ATTEMPTS] Versuche gesamt mit Abstand,
     * danach sichtbare deutsche Fehlerzeile im Onboarding-Status mit
     * Handlungshinweis (Relay-Konfiguration/Prekey-Eintrag prüfen).
     */
    private fun onPrekeyMissing(agentId: String) {
        val failures = (prekeyFailures[agentId] ?: 0) + 1
        prekeyFailures[agentId] = failures
        if (AgentPrekeyPolicy.shouldRetry(failures)) {
            Log.i(
                TAG,
                "W13-P2: Prekey fehlt am Relay (Versuch $failures/${AgentPrekeyPolicy.MAX_ATTEMPTS} " +
                    "für Agent ${agentId.take(24)}) — Retry mit Abstand",
            )
            scope?.launch {
                delay(AgentPrekeyPolicy.RETRY_BACKOFF_MS * failures)
                startHandshake(agentId)
            }
        } else {
            Log.w(
                TAG,
                "W13-P2: Prekey nach ${AgentPrekeyPolicy.MAX_ATTEMPTS} Versuchen leer — " +
                    "sichtbarer Fehler statt stiller Wartekasten",
            )
            _prekeyError.value = AgentPrekeyPolicy.statusText(agentId)
        }
    }

    /** JSON-POST mit Pairing-Bearer (Key-Registry + crypto_hello-Routen). */
    private fun postJson(base: String, path: String, body: JsonObject): Boolean = runCatching {
        val request = Request.Builder()
            .url(buildHttpUrl(base, path))
            .header("Authorization", "Bearer ${PairingManager.getCode()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.code in 200..299 }
    }.getOrDefault(false)

    // ── W17-F2: Kopplungs-Passwort-Routen (aufrufer wählt Thread — IO) ──────

    /**
     * Authentifizierter GET auf /password_status (aktiv/inaktiv) — KEINE
     * Hash-/Klartext-Rückgabe vorhanden; null = Netz-/Auth-/Form-Fehler
     * (fail-closed in der UI, Schalter bleibt in seinem Stand).
     */
    fun pairingPasswordStatus(base: String? = null, code: String? = null): Boolean? =
        runCatching {
            val targetBase = (base ?: serverUrl)?.trim().orEmpty()
            val token = (code ?: PairingManager.getCode()).trim()
            if (targetBase.isBlank() || token.isBlank()) return@runCatching null
            val json = pairingAuthGet(targetBase, "/password_status", token)
                ?: return@runCatching null
            json.get("active")?.takeIf { it.isJsonPrimitive }?.asBoolean
        }.onFailure {
            Log.w(TAG, "W17-F2: /password_status fehlgeschlagen: ${it.message}")
        }.getOrNull()

    /**
     * Authentifizierter POST /password_set (Form-Verband wie der Server:
     * 8..256 Zeichen). true = Server bestätigt aktiv; Klartext läuft nur im
     * Request-Body, nie in ein UI-Echo/Log.
     */
    fun pairingPasswordSet(base: String? = null, code: String? = null, password: String):
        Boolean = runCatching {
            if (!PairingHandshakePolicy.passwordAllowed(password)) return@runCatching false
            val targetBase = (base ?: serverUrl)?.trim().orEmpty()
            val token = (code ?: PairingManager.getCode()).trim()
            if (targetBase.isBlank() || token.isBlank()) return@runCatching false
            pairingAuthPost(targetBase, "/password_set", token, password)
        }.onFailure {
            Log.w(TAG, "W17-F2: /password_set fehlgeschlagen: ${it.message}")
        }.getOrDefault(false)

    /** Authentifizierter POST /password_clear — Schutz am Server entfernen. */
    fun pairingPasswordClear(base: String? = null, code: String? = null): Boolean =
        runCatching {
            val targetBase = (base ?: serverUrl)?.trim().orEmpty()
            val token = (code ?: PairingManager.getCode()).trim()
            if (targetBase.isBlank() || token.isBlank()) return@runCatching false
            pairingAuthPost(targetBase, "/password_clear", token, null)
        }.onFailure {
            Log.w(TAG, "W17-F2: /password_clear fehlgeschlagen: ${it.message}")
        }.getOrDefault(false)

    /** GET mit Caller-Bearer → JsonObject oder null (Status/Netz egal). */
    private fun pairingAuthGet(targetBase: String, path: String, token: String): JsonObject? {
        val request = Request.Builder()
            .url(buildHttpUrl(targetBase, path))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code != 200) null
            else runCatching {
                JsonParser.parseString(response.body?.string() ?: "").asJsonObject
            }.getOrNull()
        }
    }

    /** POST mit Caller-Bearer; password null = leerer JSON-Body (/password_clear). */
    private fun pairingAuthPost(
        targetBase: String,
        path: String,
        token: String,
        password: String?,
    ): Boolean {
        val body = JsonObject()
        if (password != null) body.addProperty("password", password)
        val request = Request.Builder()
            .url(buildHttpUrl(targetBase, path))
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { it.code in 200..299 }
    }

    /** Frame-String-Feld (primitive) — null bei fehlendem/nicht-primitivem Feld. */
    private fun stringOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun longOf(json: JsonObject, field: String): Long? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    /** APP-ANSCHLUSS: bool-Feld fail-soft lesen (devices[]: admin/online). */
    private fun boolOf(json: JsonObject, field: String): Boolean? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean
    }.getOrNull()

    /** Vorher-sichtbare Session-Menge (W18-B: Gain-Erkennung fürs Banner-Clear). */
    private var e2eSessionAgentsPreviouslyVisible: Set<String> = emptySet()

    /** E2eSessionInfo-Flow neu veröffentlichen (Session kam/weg + Banner-Livenness). */
    private fun publishE2eSessions() {
        val live = cryptoSessions.entries.mapNotNull { (agentId, peer) ->
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
        _e2eSessions.value = live
        // W18-B (Banner-LIVENESS, Sir-Fund 17.11.): das rot-Banner hängt am
        // LIVEN Zustand, nicht an einem einmaligen Event-Flag — eine frisch
        // VERIFIZIERTE Session (hello_ack-Erfolg, Session kam hier zurück)
        // ist der Beweis, dass E2E wieder steht: Banner weg. Vorher blieb
        // ein einmal gesetztes Banner (serverseitiger Session-Reset,
        // Abweichung) permanent kleben, obwohl die Sitzung längst wieder
        // aktiv war. Real-Abwägung: der hello_ack-Verify bindet die GESAMTE
        // Kette (X3DH + Ratchet + SPK-Verify) — eine wieder hochgekommene
        // Session ist das Heilungs-Ereignis, ältere Banner-Texte sind damit
        // veraltet (auch sicherheitsartige aus einem älteren Zustand).
        val gained = live.keys - e2eSessionAgentsPreviouslyVisible
        e2eSessionAgentsPreviouslyVisible = live.keys
        if (gained.isNotEmpty() && _e2eBanner.value != null) {
            Log.i(
                TAG,
                "W18-B: E2E-Session wieder aktiv ${gained.first().take(24)}" +
                    "+${gained.size - 1} — Banner geräumt (Liveness-Verband)",
            )
            _e2eBanner.value = null
        }
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
            // B5 (Multi-Gerät): frisches Gerät ohne Verlauf fordert den Gateway-
            // Verlauf an (Gate: leerer Verlauf, einmal je Connect × Agent).
            maybeRequestBackfill(agentId)
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
        // HAKEN-FIX (1): die Sweep-Diffs wandern auch in den Side-Car —
        // nach dem Neustart bleibt '✕ Fehlgeschlagen' ehrlich (Platte =
        // höchster bekannter Stand, inkl. der Sweep-Schranke); ein echtes
        // 'read' heilt später live UND auf der Platte (Parität shouldApply).
        _chatStatus.update { cur ->
            val swept = ChatStatusRank.sweepUnreadToFailed(cur)
            if (swept !== cur) {
                val root = appContext?.let { ChatHistoryStore.root(it) }
                if (root != null) {
                    for ((mid, status) in swept) {
                        if (status != cur[mid]) {
                            runCatching { ChatHistoryStore.recordStatus(root, mid, status) }
                        }
                    }
                }
            }
            swept
        }
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
        // W18-1a: FP-Ergebnis verdeckt — der enc-Antworttext mit dem
        // 'Fingerprint-Abgleich:'-Präfix ist KEINE Chat-Anzeige: er läuft
        // verdeckt (FP-Status) und erzeugt KEINE Chat-Bubble (siehe auch den
        // Legacy-Klartext-Zweig in handleChatEventFrame).
        if (FpCheckRules.isFpResultText(text)) {
            handleChatFpCheckResult(agentId, chatId, ts, ok = null, text = text)
            return
        }
        // ── W21 ANRUF (P5, roadmap-anruf.md §3.3): Call-Modus-TTS-Chunks ────
        // Der Adapter markiert Call-Antworten im ct mit call_chunk_seq (satz-
        // weise, §3.3 "früh raus") + call_id. Im AKTIVEN Anruf wird der Chunk
        // SOFORT GESPROCHEN (TTS-Verbund, QUEUE_ADD = satzweise Reihenfolge);
        // der normale Verlaufspfad (Bubble/Platte/Notification) bleibt — der
        // Chat-Verlauf zeigt die Antwort zusätzlich. Fremde/Spät-Marker sind
        // normale Chat-Antworten (kein Call-Bezug, kein Sprechen).
        val payloadCallId = runCatching {
            payload.get(CallRules.CALL_ID_FIELD)
                ?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
        val chunkSeq = runCatching {
            payload.get(CallRules.CALL_CHUNK_SEQ_FIELD)
                ?.takeIf { it.isJsonPrimitive }?.asInt
        }.getOrNull()
        if (CallRules.callTtsActive(_callSession.value, payloadCallId, chunkSeq) &&
            text.isNotBlank() && appContext != null
        ) {
            val ctx = appContext!!
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        ChatSpeechSpeaker.ensure(ctx)
                        ChatSpeechSpeaker.enqueue(
                            ctx,
                            CallRules.CALL_SPEAK_ID_BASE + (chunkSeq ?: 0),
                            text,
                        )
                    }
                }
            } catch (_: Exception) {}
        }
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
        // W18-1a: der Banner-Setz-Anteil lebt im verdeckten FP-Weg
        // (handleChatFpCheckResult) — der enc-Text läuft oben dorthin um,
        // BEVOR er eine Chat-Bubble werden könnte.
    }

    /**
     * B5 (Multi-Gerät): chat.sync.enc = Echo des NUTZER-Texts eines ANDEREN
     * Geräts desselben Accounts (Backend B3: Adapter verschließt je Geräte-
     * Session, Marker "sync" + "sync_of" = Ursprungs-msg_id — contrib/
     * hermes-chat-plugin/adapter.py B3_SYNC_ENC_FRAME/B3_ECHO_FIELD/
     * B3_SYNC_OF_FIELD). Verband:
     * - INGEST-ONLY (Loop-Schutz, Roadmap Kap. 8 #6): Bubble in chatSyncEvents
     *   + persistChatBubble — bewusst KEIN sendChat/POST-Nachschub, KEIN
     *   notifyIncomingChat (eigene Nachricht!), KEIN Vorlesen. Damit kann das
     *   Echo nie wieder in den Gateway-Verlauf fallen (kein Duplex-Loop).
     * - Dedup über Ursprungs-msg_id: FIFO 256 (Parität B3_SYNC_ECHO_DEDUP)
     *   fängt Spool-Re-Flush-Dubletten live; der UI-Zwilling-Filter
     *   (ChatTimelineMerge) + msg_id-Idempotenz der Platte frisst den Rest.
     */
    private fun handleChatSyncEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: run {
            Log.w(TAG, "B5: chat.sync.enc ohne aktive E2E-Session verworfen")
            return
        }
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "B5: chat.sync.enc-Entschlüsselung fehlgeschlagen — Frame verworfen")
            return
        }
        handleRotateHint(json, agentId, peer)
        val echo = ChatSyncRules.parseEcho(
            payload,
            json,
            agentId,
            stringOf(json, "chat_id") ?: "",
            longOf(json, "ts") ?: System.currentTimeMillis(),
        ) ?: run {
            // Kein Echo-Marker = kein Sync-Frame (Backend-B4-lose Struktur) —
            // NIE als Agenten-Antwort rendern, NIE senden.
            Log.w(TAG, "B5: chat.sync.enc ohne Echo-Marker verworfen")
            return
        }
        val memorable = synchronized(frameGate) {
            ChatSyncRules.isNewAndRemember(syncEchoSeen, echo.msgId)
        }
        if (!memorable) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "B5: sync-Echo-Duplikat (msg ${echo.msgId?.take(12)}) verworfen")
            }
            return
        }
        _chatSyncEvents.update { list ->
            (list + ChatSyncEvent(echo.chatId, echo.agentId, echo.text, echo.ts, echo.msgId))
                .takeLast(MAX_CHAT_EVENTS)
        }
        persistChatBubble(agentId, echo.chatId, fromAgent = false, echo.text, echo.ts, status = null,
            msgId = echo.msgId)
        // Bewusst ENDE — kein notifyIncomingChat, kein ChatSpeechSpeaker, kein
        // sendChat: der Ingest endet an der Platte (Kap. 8 #6, Loop-sicher).
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "B5: sync-Echo von Agent ${agentId.take(24)} geplattet (msg ${echo.msgId?.take(12)})")
        }
    }

    /**
     * B5: pairing.request (Anmelde-Tor, tools/android_relay.py B1) — nur die
     * zuletzt empfangene Anfrage im StateFlow (Kap. 8 #8: die App zeigt NUR
     * die letzte Anfrage; Kennung/IP maskiert, TTL je Frame). Die UI-Bindung
     * (Banner) kommt in der B7-Lane; das Senden läuft über
     * [sendPairingApprove]/[sendPairingDeny].
     */
    private fun handlePairingRequestFrame(json: JsonObject) {
        val frame = PairingFrameRules.parse(json) ?: run {
            Log.w(TAG, "B5: pairing.request ohne gültige Struktur verworfen")
            return
        }
        _pairingFrame.value = frame
        Log.i(TAG, "B5: pairing.request empfangen (${frame.deviceMasked}, TTL ${frame.ttlS}s)")
    }

    /** B5: Freigabe-Draht — NUR request_id (Relay löst die device_id selbst auf; die App hält bewusst NUR die maskierte Kennung). */
    fun sendPairingApprove(requestId: String): Boolean =
        sendRawFrame(PairingFrameRules.approveFrame(requestId))

    /** B5: Abweisungs-Draht (pairing.deny) — dieselbe Norm wie approve. */
    fun sendPairingDeny(requestId: String): Boolean =
        sendRawFrame(PairingFrameRules.denyFrame(requestId))

    /** B5: UI-Räumung der angezeigten Anfrage (B7 bindet; StateFlow-Reset). */
    fun dismissPairingFrame() {
        _pairingFrame.value = null
    }

    // ── W21 ANRUF (P4-P6 + G11/G12): Call-Signaling — Empfang/Senden ─────────
    // Frame-Verträge GELESEN (tools/android_relay.py + adapter.py, HEAD b063693):
    // - Agent→Phone call_invite.enc: ct = {kind, reason} (opak), Klar = ids.
    // - Phone→Agent call_ringing/accepted/declined/missed/ended.enc: leerer ct,
    //   call_id als Klar-Feld, je Geräte-Session (Relay löst agent_id aus dem
    //   Session-Broker, NIE aus dem Frame), Rate-Cap 6/s je Gerät.
    // - §3.4-Kette: Invite → sofort call_ringing (App quittiert Klingeln) →
    //   accept/decline; verpasster Ring (45 s) → call_missed.enc.

    /** call_invite.enc — Klingeln: ct entschlüsseln (Kettensync-PFLICHT), Entscheidung aus CallRules. */
    private fun handleCallInviteEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val callId = stringOf(json, "call_id")
        val chatId = stringOf(json, "chat_id")
        val peer = cryptoSessions[agentId]?.takeIf { it.isActive() } ?: run {
            Log.w(TAG, "ANRUF: call_invite.enc ohne aktive E2E-Session verworfen (fail-closed)")
            return
        }
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "ANRUF: call_invite.enc-Entschlüsselung fehlgeschlagen — verworfen")
            return
        }
        handleRotateHint(json, agentId, peer)
        val kind = runCatching {
            payload.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
        val reason = runCatching {
            payload.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
        val now = System.currentTimeMillis()
        val invite = CallRules.InviteSpec(callId, agentId, chatId, kind ?: "voice", reason)
        when (val decision = CallRules.inviteDecision(_callSession.value, invite, now)) {
            is CallRules.InviteDecision.Invalid -> {
                Log.w(TAG, "ANRUF: call_invite mit unbrauchbaren IDs verworfen (fail-closed, kein Signal)")
            }
            // Zweiter Call während RINGING/ACTIVE — ABWEISUNG des NEUEN Calls
            // (Parität Backend: ein Anruf je Gerät; der Adapter fällt auf
            // Chat-Fallback zurück und verliert nichts).
            CallRules.InviteDecision.Busy -> {
                Log.i(TAG, "ANRUF: Zweitanruf während laufendem Call abgewiesen (call_declined.enc)")
                if (validCallSignalFields(callId, chatId)) {
                    sendCallSignal(CallRules.FRAME_DECLINED, agentId, chatId ?: "", callId ?: "")
                }
            }
            // Duplikat-Invite (Spool-Flush) — Klingeln bleibt stehen, Fenster neu.
            CallRules.InviteDecision.Refreshed -> {
                Log.i(TAG, "ANRUF: Duplikat-Invite (call ${(callId ?: "").take(12)}) — Klingeln gehalten")
                scheduleRingTimeout(callId ?: "")
            }
            is CallRules.InviteDecision.Opened -> {
                _callSession.value = decision.session
                callAudioChunkSeq.set(0)
                startCallRingNotification(decision.session)
                Log.i(TAG, "ANRUF: Ankommender Anruf von ${agentId.take(24)} (call ${decision.session.callId.take(12)}, kind=${decision.session.kind})")
                // §3.4: Klingeln SOFORT quittieren — der Adapter wartet auf
                // ringing/accepted je Session (Signal-Verband wie chat.read).
                sendCallSignal(CallRules.FRAME_RINGING, agentId, decision.session.chatId, decision.session.callId)
                scheduleRingTimeout(decision.session.callId)
            }
        }
    }

    /** call_end.enc — Agent legt auf: opak ct entschlüsseln (Kettensync), Session beenden. */
    private fun handleCallEndEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val callId = stringOf(json, "call_id")
        val peer = cryptoSessions[agentId]?.takeIf { it.isActive() } ?: run {
            Log.w(TAG, "ANRUF: call_end.enc ohne aktive E2E-Session verworfen (fail-closed)")
            return
        }
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "ANRUF: call_end.enc-Entschlüsselung fehlgeschlagen — verworfen")
            return
        }
        handleRotateHint(json, agentId, peer)
        val session = _callSession.value ?: run {
            // Parität Adapter-Verwurf: fremdes/spätes Ende ohne lokalen Auftrag.
            Log.i(TAG, "ANRUF: call_end.enc ohne lokalen Anruf-Auftrag — verworfen (call ${(callId ?: "").take(12)})")
            return
        }
        val ended = CallRules.remoteEnd(session, callId, System.currentTimeMillis())
        if (ended == null) {
            Log.w(TAG, "ANRUF: call_end für fremde/laufende call_id ${(callId ?: "").take(12)} — verworfen")
            return
        }
        Log.i(TAG, "ANRUF: Agent hat aufgelegt (call ${session.callId.take(12)})")
        endCallLocalState(ended)
    }

    /** Form-Check für abweisende Signale auf defekten Klar-Feldern (kein Echo von Mist). */
    private fun validCallSignalFields(callId: String?, chatId: String?): Boolean =
        CallRules.validCallId(callId) && !chatId.isNullOrBlank()

    /**
     * Phone→Agent Call-Signum über die E2E-Session (WS, Klar = ids + Krypto-
     * Hülle; ct bleibt leer {}). Parität chat.send.enc-Bau: AAD bindet die
     * chat_id, der Relay prüft Session/Replay/Rate je SENDER-Gerät.
     */
    private fun sendCallSignal(frameType: String, agentId: String, chatId: String, callId: String): Boolean {
        val peer = cryptoSessions[agentId]?.takeIf { it.isActive() } ?: return false
        val frame = runCatching {
            peer.encryptFrame(frameType, chatId, emptyMap<String, String>())
        }.getOrNull() ?: return false
        // Klar-Feld des Signal-Vertrags (ids-only — kein Inhalt außerhalb des ct).
        frame.addProperty("call_id", callId)
        val ws = webSocket ?: return false
        val ok = runCatching { ws.send(frame.toString()) }.getOrDefault(false)
        if (BuildConfig.DEBUG) Log.d(TAG, "ANRUF: $frameType → Agent ${agentId.take(24)} (ok=$ok)")
        return ok
    }

    // ── Klingel-Benachrichtigung (Vibration über KANAL, kein VIBRATE-Perm) ──

    private fun startCallRingNotification(session: CallRules.CallSession) {
        val ctx = appContext ?: return
        // Android 13+ ohne POST_NOTIFICATIONS: Karte in der Chat-Route bleibt
        // die Anzeige (Request-Lauf existiert in BridgeScreen).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "ANRUF: Ring ohne Notification-Erlaubnis still — Vollbild-Karte in der Chat-Route")
            return
        }
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CALL_NOTIFY_CHANNEL,
                        // MERGE-LANE 18.11.: Klingel-Kanal-Texte je Locale (×3).
                        ctx.getString(R.string.call_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = ctx.getString(R.string.call_channel_description)
                        enableVibration(true)
                        vibrationPattern = CallRules.RING_VIBRATION_PATTERN
                    },
                )
            }
            val alias = CallRules.displayName(
                _agents.value.firstOrNull { it.agentId == session.agentId }?.displayName,
                session.agentId,
            )
            val tap = PendingIntent.getActivity(
                ctx, CALL_NOTIFY_ID,
                Intent(ctx, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(ctx, CALL_NOTIFY_CHANNEL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(alias)
                // MERGE-LANE 18.11.: Klingel-Fallback je Locale — aus den
                // call_*-Ressourcen komponiert (Kanal-Keys call_channel_name/
                // call_channel_description; der Fallback nennt die zwei Wege
                // der Klingel-Karte ehrlich: Annehmen / Ablehnen).
                .setContentText(
                    session.reason.ifBlank {
                        ctx.getString(R.string.call_ring_kind_generic) + " — " +
                            ctx.getString(R.string.call_action_accept) + " / " +
                            ctx.getString(R.string.call_action_decline)
                    },
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(tap)
                .setOngoing(true)
                // §3.6-FSI-Wunsch: ohne Manifest-Erlaubnis degradiert dies
                // ehrlich auf Heads-up (kein Crash, kein Stillstand).
                .setFullScreenIntent(tap, true)
                .build()
            nm.notify(CALL_NOTIFY_ID, notif)
        }.onFailure { Log.w(TAG, "ANRUF: Ring-Notification fehlgeschlagen: ${it.message}") }
    }

    private fun stopCallRingNotification() {
        val nm = appContext
            ?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(CALL_NOTIFY_ID) }
    }

    // ── Timeout-Takte (P6) ───────────────────────────────────────────────────

    private fun cancelCallTimers() {
        callRingTimeoutJob?.cancel()
        callRingTimeoutJob = null
        callDeckJob?.cancel()
        callDeckJob = null
    }

    private fun scheduleRingTimeout(callId: String) {
        callRingTimeoutJob?.cancel()
        callRingTimeoutJob = scope?.launch {
            kotlinx.coroutines.delay(CallRules.RING_TIMEOUT_MS)
            val session = _callSession.value ?: return@launch
            if (session.callId != callId ||
                !CallRules.ringWindowPassed(session, System.currentTimeMillis())
            ) {
                return@launch
            }
            val ended = CallRules.ringTimeout(session, System.currentTimeMillis())
            Log.i(TAG, "ANRUF: Klingel-Fenster aus — verpasst (call_missed.enc, call ${callId.take(12)})")
            if (ended != null) endCallLocalState(ended)
            sendCallSignal(CallRules.FRAME_MISSED, session.agentId, session.chatId, session.callId)
        }
    }

    private fun scheduleCallDeck(callId: String) {
        callDeckJob?.cancel()
        callDeckJob = scope?.launch {
            kotlinx.coroutines.delay(CallRules.ACTIVE_CAP_MS)
            val session = _callSession.value ?: return@launch
            if (session.callId != callId ||
                !CallRules.activeCapExceeded(session, System.currentTimeMillis())
            ) {
                return@launch
            }
            val ended = CallRules.hangUp(session, System.currentTimeMillis())
            Log.w(TAG, "ANRUF: Sicherheits-Deckel — Call sauber beendet (call ${callId.take(12)})")
            if (ended != null) {
                endCallLocalState(ended)
                sendCallSignal(CallRules.FRAME_ENDED, session.agentId, session.chatId, session.callId)
            }
        }
    }

    // ── UI-Aktionen (Call-Mount bindet sie an Annehmen/Ablehnen/Auflegen) ────

    /** Annehmen (48dp-Karte): RINGING → ACTIVE + call_accepted.enc (§3.4). */
    fun acceptCall() {
        val session = _callSession.value ?: return
        val next = CallRules.accept(session, System.currentTimeMillis()) ?: return
        _callSession.value = next
        callAudioChunkSeq.set(0)
        stopCallRingNotification()
        cancelCallTimers()
        scheduleCallDeck(session.callId)
        sendCallSignal(CallRules.FRAME_ACCEPTED, session.agentId, session.chatId, session.callId)
        Log.i(TAG, "ANRUF: Angenommen (call ${session.callId.take(12)}) — Call-Modus aktiv")
    }

    /** Ablehnen (48dp-Karte): RINGING → ENDED + call_declined.enc. */
    fun declineCall() {
        val session = _callSession.value ?: return
        val next = CallRules.decline(session, System.currentTimeMillis()) ?: return
        Log.i(TAG, "ANRUF: Abgelehnt (call ${session.callId.take(12)})")
        endCallLocalState(next)
        sendCallSignal(CallRules.FRAME_DECLINED, session.agentId, session.chatId, session.callId)
    }

    /** Auflegen (Status-Zeile): ACTIVE → ENDED + call_ended.enc. */
    fun hangUpCall() {
        val session = _callSession.value ?: return
        val next = CallRules.hangUp(session, System.currentTimeMillis()) ?: return
        Log.i(TAG, "ANRUF: Aufgelegt (call ${session.callId.take(12)}, dauer=${CallRules.activeSeconds(session, System.currentTimeMillis())}s)")
        endCallLocalState(next)
        sendCallSignal(CallRules.FRAME_ENDED, session.agentId, session.chatId, session.callId)
    }

    /** G11-Pill: Agent darf im Call den Screen steuern (bestehender interaction-Verband). */
    fun setCallAgentSteer(allowed: Boolean) {
        val session = _callSession.value ?: return
        val next = CallRules.setAgentSteer(session, allowed) ?: return
        _callSession.value = next
        Log.i(TAG, "ANRUF: Screen-Steuer-Freigabe $allowed (call ${session.callId.take(12)})")
    }

    /**
     * Lokales Call-Ende (ALLE Pfadenden): StateFlow, Klingel-Räumung, Timer,
     * Call-Modus-Sprachausgabe stoppen (barge-in/Ende §3.1). KEIN Senden — die
     * Aufrufer senden ihre Signale getrennt (Senden an toter WS = no-op false).
     */
    private fun endCallLocalState(ended: CallRules.CallSession) {
        _callSession.value = ended
        stopCallRingNotification()
        cancelCallTimers()
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { ChatSpeechSpeaker.stopAll() }
            }
        } catch (_: Exception) {}
    }

    /**
     * P6: Verbindungsweg stirbt (onClosed/onFailure/disconnect) während eines
     * Calls — sauber beenden OHNE Signal (WS tot; der Adapter holt das über
     * sein Ring-Fenster/TTL selbst). Klingel/Ausgabe/Aufnahme räumen die UI-
     * Verband-Beobachter mit (Session-Phase → ENDED).
     */
    private fun endCallOnConnectionLost() {
        val session = _callSession.value ?: return
        val ended = CallRules.connectionLost(session, System.currentTimeMillis()) ?: return
        Log.w(TAG, "ANRUF: Verbindung verloren (code-lose Trennung) — Call ${session.callId.take(12)} sauber beendet")
        endCallLocalState(ended)
    }

    // ── P5 (§3.1-§3.3): Call-Modus-Sprachweg OHNE WebRTC ────────────────────

    /**
     * Nutzersprache im aktiven Call: der Aufnahme-Chunk (AAC/M4A derselben
     * Voice-Memo-Pipeline) läuft als chat.media.enc-Chunk (kind "voice")
     * E2E zum Agenten — der Adapter transkribiert (ASR) und antwortet als
     * TTS-Chunks (call_chunk_seq-Marker, unten gesprochen). Bewusst KEINE
     * Chat-Bubble/Platte-Kopie für das Turn: es lebt im Call-Kontext.
     * onResult läuft IMMER auf dem Main-Thread (deliverMediaResult-Muster).
     */
    fun sendCallAudio(
        agentId: String,
        bytes: ByteArray,
        onResult: (ok: Boolean, error: String?) -> Unit,
    ) {
        val session = _callSession.value
        if (session == null || session.phase != CallRules.Phase.ACTIVE) {
            deliverMediaResult2(onResult, false, "kein aktiver Anruf")
            return
        }
        if (session.agentId != agentId) {
            deliverMediaResult2(onResult, false, "Anruf läuft mit einem anderen Agenten")
            return
        }
        val peer = cryptoSessions[agentId]?.takeIf { it.isActive() }
        val chatId = peer?.chatId
        if (peer == null || chatId.isNullOrBlank()) {
            deliverMediaResult2(onResult, false, "keine aktive E2E-Sitzung")
            return
        }
        if (bytes.isEmpty() || bytes.size.toLong() > MediaProtocol.MAX_BYTES) {
            deliverMediaResult2(onResult, false, "Call-Chunk leer oder zu groß")
            return
        }
        val base = serverUrl?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            deliverMediaResult2(onResult, false, "Kein Relay-Server konfiguriert")
            return
        }
        if (!isConnected) {
            deliverMediaResult2(onResult, false, "Keine Verbindung zum Relay")
            return
        }
        val seq = callAudioChunkSeq.getAndIncrement()
        val mediaId = MediaProtocol.newMediaId { CryptoEngine.secureRandomBytes(16) }
        val name = CallRules.callAudioName(session.callId, seq)
        activeScopeForCall()?.launch(Dispatchers.IO) {
            val outcome = runCatching {
                uploadMediaChunks(
                    base, agentId, peer, chatId, mediaId,
                    "voice", name, "audio/mp4", bytes,
                )
            }
            outcome.onSuccess {
                if (it.sent) {
                    Log.i(TAG, "ANRUF: Call-Turn hoch (turn#$seq, ${bytes.size} B, call ${session.callId.take(12)})")
                    deliverMediaResult2(onResult, true, null)
                } else {
                    deliverMediaResult2(onResult, false, "Relay lehnte Call-Chunk ab")
                }
            }.onFailure { e ->
                deliverMediaResult2(onResult, false, e.message?.take(80) ?: "Call-Chunk fehlgeschlagen")
            }
        }
    }

    private fun activeScopeForCall(): CoroutineScope? = scope?.takeIf { isConnected }

    /** Main-Thread-Zusteller der Call-Ergebnisse (deliverMediaResult ohne media_id). */
    private fun deliverMediaResult2(onResult: (ok: Boolean, error: String?) -> Unit, ok: Boolean, error: String?) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { onResult(ok, error) }
            }
        } catch (_: Exception) {}
    }

    // ── G12 (§7.2): Screenshot NUR für den Agenten ──────────────────────────

    /**
     * RAM-Bitmap (A11y-Screenshot-API, downscaled/EXIF-leer via Neukompression)
     * als Bild-Message kind "screengrab" E2E an den Agenten — KEIN MediaStore/
     * Galerie-Schreiben, KEINE lokale Kopie (uploadMediaChunks persistiert
     * nichts; nach dem POST existiert nichts mehr vom Bild). Der Aufrufer
     * (Call-Mount) hält die Capability-Schranke screen_capture + 3-s-Chip.
     */
    fun sendScreengrab(
        agentId: String,
        name: String,
        mime: String,
        bytes: ByteArray,
        onResult: (ok: Boolean, error: String?) -> Unit,
    ) {
        val peer = cryptoSessions[agentId]?.takeIf { it.isActive() }
        val chatId = peer?.chatId
        if (peer == null || chatId.isNullOrBlank()) {
            deliverMediaResult2(onResult, false, "keine aktive E2E-Sitzung")
            return
        }
        if (bytes.isEmpty() || bytes.size.toLong() > MediaProtocol.MAX_BYTES) {
            deliverMediaResult2(onResult, false, "Screengrab leer oder zu groß")
            return
        }
        val base = serverUrl?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            deliverMediaResult2(onResult, false, "Kein Relay-Server konfiguriert")
            return
        }
        if (!isConnected) {
            deliverMediaResult2(onResult, false, "Keine Verbindung zum Relay")
            return
        }
        val mediaId = MediaProtocol.newMediaId { CryptoEngine.secureRandomBytes(16) }
        activeScopeForCall()?.launch(Dispatchers.IO) {
            val outcome = runCatching {
                uploadMediaChunks(
                    base, agentId, peer, chatId, mediaId,
                    "screengrab", name, mime, bytes,
                )
            }
            outcome.onSuccess {
                if (it.sent) {
                    Log.i(TAG, "ANRUF: Screengrab an Agent ${agentId.take(24)} gesendet (RAM-only, kein Persist)")
                    deliverMediaResult2(onResult, true, null)
                } else {
                    deliverMediaResult2(onResult, false, "Relay lehnte Screengrab ab")
                }
            }.onFailure { e ->
                deliverMediaResult2(onResult, false, e.message?.take(80) ?: "Screengrab fehlgeschlagen")
            }
        }
    }


    /** APP-ANSCHLUSS: Frame-Typ des Entfernens (Kap. 4.2, B4-Kontrakt). */
    private const val DEVICE_REVOKE_FRAME = "device_revoke"

    /**
     * APP-ANSCHLUSS-Verdrahtung: device_revoke vom Phone zum Relay (B4-
     * Kontrakt aus dem Backend GELESEN — HEAD 733504c,
     * tools/android_relay.py _handle_phone_device_revoke):
     *
     *   {"type": "device_revoke", "request_id": <B4-Handle>}
     *
     * request_id = das Revoke-Handle aus relay.state devices[].request_id
     * ([DeviceRules.DeviceEntry.requestId]) — das Relay löst das Ziel AUCH
     * über das Handle auf (Parität pairing.approve-Norm: request_id ODER
     * raw device_id; die App hält bewusst NIE rohe Kennungen — PII-Regel).
     * Administrativ: FREMDE Geräte dürfen NUR Admin-Slots entfernen
     * (fail-closed am Relay, kein App-Fallback); OHNE Ziel (leeres Handle)
     * = das EIGENE Gerät abmelden (für JEDES Gerät erlaubt).
     *
     * Wirksamkeit am Ziel: Registry-Eintrag weg + WS-Kick des Relays mit
     * Close-Code 4404 ("device-revoked") — die App behandelt 4404 (nicht
     * Mitglied der code-in-use-4403-Familie) im generischen Getrennt-Pfad,
     * der Reconnect läuft danach ins Anmelde-Tor (fail-closed korrekt).
     *
     * @param requestId B4-Revoke-Handle des Ziel-Eintrags; Blank = Self-Revoke.
     */
    fun sendDeviceRevoke(requestId: String): Boolean {
        val id = requestId.trim()
        val frame = JsonObject().apply {
            addProperty("type", DEVICE_REVOKE_FRAME)
            if (id.isNotEmpty()) addProperty("request_id", id)
        }
        return sendRawFrame(frame)
    }

    /**
     * B6: chat.backfill.enc = Gateway-Verlauf-Batch je frisches Gerät (K ≤ 200
     * je Agent, je Frame max 64 Entries — B3_BACKFILL_BATCH_MAX). Einträge
     * CHRONOLOGISCH in die Platte einsortieren (ChatHistoryStore.mergeBackfill:
     * älter vor Bestand, msg_id-Idempotenz, Caps unverändert), dann der Chat-
     * Screen den Disk-Stand einmalig vor den Live-Stand setzen (Prepend).
     * Chips: KEIN erfundener Status — die msg_id-Bindung zieht den Broker-
     * Stand (Side-Car ⊔ chat.status ⊔ snapshot) monotone nach.
     */
    private fun handleChatBackfillEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: run {
            Log.w(TAG, "B6: chat.backfill.enc ohne aktive E2E-Session verworfen")
            return
        }
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "B6: chat.backfill.enc-Entschlüsselung fehlgeschlagen — Frame verworfen")
            return
        }
        handleRotateHint(json, agentId, peer)
        val chatId = stringOf(json, "chat_id") ?: ""
        val entries = ChatBackfillRules.parseEntries(payload, System.currentTimeMillis())
        val root = appContext?.let { ChatHistoryStore.root(it) } ?: run {
            _backfillSyncing.update { it - agentId }
            return
        }
        if (entries == null) {
            Log.w(TAG, "B6: chat.backfill.enc ohne Batch-Feld verworfen")
            return
        }
        if (entries.isEmpty()) {
            // Leere Batch = „Weiter gibt es ehrlich nichts" — Fortschritt aus.
            _backfillSyncing.update { it - agentId }
            return
        }
        ChatHistoryStore.mergeBackfillAsync(
            root,
            agentId,
            entries.map { entry ->
                ChatHistoryStore.Entry(
                    seq = entry.ts,
                    agentId = agentId,
                    chatId = chatId,
                    fromAgent = entry.fromAgent,
                    text = entry.text,
                    time = "",
                    status = null,
                    msgId = entry.msgId,
                )
            },
        ) { fresh ->
            // Die Fortschritt-Zeile DREHT NICHT ENLOS: gelandet = aus (auch bei
            // 0 neuen Zeilen — der Verlauf ist dann schon komplett geplattet).
            _backfillSyncing.update { it - agentId }
            if (fresh > 0) {
                _backfillEpochs.update { epochs ->
                    epochs + (agentId to (epochs[agentId] ?: 0L) + 1L)
                }
                Log.i(TAG, "B6: chat.backfill.enc geplattet — ${fresh} neue Zeilen (${agentId.take(24)})")
            } else {
                Log.d(TAG, "B6: chat.backfill.enc ohne neue Zeilen (${agentId.take(24)})")
            }
        }
    }

    /**
     * B6: chat.status_snapshot (ids-only, Roadmap Kap. 4.1) — msg_id→Status-
     * Mapping je Backfill/Connect. MONOTONE Übernahme: ChatStatusRank.fuse in
     * _statusSnapshot UND in der Live-Map (Parität handleChatStatusFrame, inkl.
     * Write-Back Side-Car vor der rang-priorisierten Eviction) — die W20-Haken-
     * Fusion fällt NIE zurück (ein verspätetes 'delivered' kippt keinen 'read').
     */
    private fun handleStatusSnapshotFrame(json: JsonObject) {
        val parsed = ChatBackfillRules.parseStatusSnapshot(json) ?: run {
            Log.w(TAG, "B6: chat.status_snapshot ohne gültige Felder verworfen")
            return
        }
        if (parsed.isEmpty()) return
        _statusSnapshot.update { current -> ChatBackfillRules.mergeStatuses(current, parsed) }
        val root = appContext?.let { ChatHistoryStore.root(it) }
        _chatStatus.update { cur ->
            val next = LinkedHashMap(cur)
            for ((msgId, status) in parsed) {
                if (!ChatStatusRank.shouldApply(next[msgId], status)) continue
                next[msgId] = status
                // rang-priorisierte Eviction mit Write-Back VOR jeder Entfernung
                // (Parität handleChatStatusFrame — >500-msg_ids-Kette, kein
                // Chip-Voll-Verlust).
                while (next.size > MAX_CHAT_STATUS) {
                    val victimId = ChatStatusRank.evictionVictims(next).firstOrNull() ?: break
                    root?.let { r ->
                        runCatching { ChatHistoryStore.recordStatus(r, victimId, next.getValue(victimId)) }
                    }
                    next.remove(victimId)
                }
            }
            next
        }
        // Snapshot-Stände auf die Platte (Side-Car = höchster bekannter Stand;
        // idempotent — Duplikat-Zeilen sind fold-unschädlich).
        root?.let { r ->
            runCatching {
                for ((msgId, status) in parsed) {
                    ChatHistoryStore.recordStatus(r, msgId, status)
                }
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "B6: status_snapshot übernommen (${parsed.size} ids)")
    }

    /**
     * B5: chat.backfill_request — beim ERFOLGREICHEN hello_ack je Agent (Roadmap
     * Kap. 4.1: „nach hello_ack: wenn kein lokaler Verlaufs-File für agentId →
     * sende backfill_request“). Gate-Verband: nur bei LEEREM Verlauf, einmal je
     * (Re)Connect × Agent (Frame [mayRequestOnce] — kein DoS-Hammer gegen das
     * B3_BACKFILL_RATE_S-Schild), ids-only über den Phone-WS (Klar-Steuer-Frame,
     * Senden-Muster = pairing.approve). Fortschritt-Zeile ehrlich mit Timeout.
     */
    private fun maybeRequestBackfill(agentId: String) {
        val activeScope = scope ?: return
        activeScope.launch {
            val root = appContext?.let { ChatHistoryStore.root(it) } ?: return@launch
            val has = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ChatHistoryStore.hasHistory(root, agentId)
            }
            if (!ChatBackfillRules.shouldRequest(has)) return@launch
            val chatId = cryptoSessions[agentId]?.chatId ?: return@launch
            val frame = ChatBackfillRules.requestFrame(chatId) ?: return@launch
            val ws = webSocket ?: return@launch
            val first = synchronized(frameGate) {
                ChatBackfillRules.mayRequestOnce(backfillRequested, agentId)
            }
            if (!first) return@launch
            val sent = runCatching { ws.send(frame.toString()) }.getOrDefault(false)
            if (!sent) {
                synchronized(frameGate) { ChatBackfillRules.unmarkRequested(backfillRequested, agentId) }
                Log.w(TAG, "B5: chat.backfill_request nicht gesendet (Agent ${agentId.take(24)})")
                return@launch
            }
            _backfillSyncing.update { it + agentId }
            scheduleBackfillProgressTimeout(agentId)
            Log.i(TAG, "B5: chat.backfill_request gesendet (Agent ${agentId.take(24)}, K=${ChatBackfillRules.BACKFILL_K})")
        }
    }

    /** B6: ehrliches Fortschritt-Ende — die Zeile bleibt ohne Antwort nie hängen. */
    private fun scheduleBackfillProgressTimeout(agentId: String) {
        scope?.launch {
            kotlinx.coroutines.delay(ChatBackfillRules.PROGRESS_TIMEOUT_MS)
            _backfillSyncing.update { cur ->
                if (agentId in cur) cur - agentId else cur
            }
        }
    }

    /** B5: roher Klar-Frame auf den Phone-WS (Anmelde-Tor-Gegenrichtung). */
    private fun sendRawFrame(frame: JsonObject?): Boolean {
        val json = frame ?: return false
        val ws = webSocket ?: return false
        val ok = runCatching { ws.send(json.toString()) }.getOrDefault(false)
        if (ok && BuildConfig.DEBUG) {
            Log.d(TAG, "B5: ${json.get("type")?.asString ?: "?"} über WS gesendet")
        }
        return ok
    }


    /**
     * W18-1a: verdeckter FP-Abgleich-Kontrollframe (chat.fp_check, klar) —
     * der Agent antwortet auf den 'FP-ABGLEICH'-Versand als Kontrollframe
     * (NICHT als Chat-Antwort): FP-Status aktualisieren (ok = Ergebnis-Zeile,
     * Abweichung = rot-Banner wie am alten Bubble-Pfad), NIE Chat-Bubble
     * (kein chatEvents/persist/notify — Beweis: dieser Verband hat KEINEN
     * dieser drei Aufrufe). Fail-soft: agent_id fehlt = Frame verwerfen.
     */
    private fun handleChatFpCheckFrame(json: JsonObject) {
        val parsed = FpCheckRules.parse(json) ?: run {
            Log.w(TAG, "W18-1a: chat.fp_check ohne agent_id verworfen")
            return
        }
        handleChatFpCheckResult(
            parsed.agentId ?: return,
            parsed.chatId ?: "",
            parsed.ts ?: System.currentTimeMillis(),
            ok = parsed.ok,
            text = parsed.text,
        )
    }

    /**
     * W18-1a: enc-Spiegel des Kontrollframes — Entschlüsselung PFLICHT
     * (Kettensync, Same-Verband wie chat.activity.enc), danach derselbe
     * verdeckte Auswertungspfad mit dem KLARTEXT-Feldern (ok/text).
     */
    private fun handleChatFpCheckEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: run {
            Log.w(TAG, "W18-1a: chat.fp_check.enc ohne aktive E2E-Session verworfen")
            return
        }
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "W18-1a: chat.fp_check.enc-Entschlüsselung fehlgeschlagen — Frame verworfen")
            return
        }
        handleRotateHint(json, agentId, peer)
        val parsed = FpCheckRules.parsePayload(payload)
        handleChatFpCheckResult(
            agentId,
            stringOf(json, "chat_id") ?: "",
            longOf(json, "ts") ?: System.currentTimeMillis(),
            ok = parsed?.second,
            text = parsed?.first,
        )
    }

    /**
     * W18-1a: EIN Auswertungspunkt für Klar-Frame, enc-Frame und Legacy-
     * Klartext-Antwort — FP-STATUS (Banner bei Abweichung + Ergebnis je
     * Agent), NIE Chat-Bubble (kein chatEvents/persistChatBubble/notify
     * hier: die einzige Zeile ist die dismissbare UI-Statuszeile).
     */
    private fun handleChatFpCheckResult(
        agentId: String,
        chatId: String,
        ts: Long,
        ok: Boolean?,
        text: String?,
    ) {
        val verdict = FpCheckRules.verdict(ok, text) ?: run {
            Log.w(TAG, "W18-1a: FP-Ergebnis für ${agentId.take(24)} nicht auswertbar — verworfen")
            return
        }
        if (!verdict) {
            // FP-STATUS: Abweichung = rot-Banner (Mitarbeiter-Warnung wie am
            // bisherigen Bubble-Pfad, Sirs Revision (iii)).
            _e2eBanner.value = text ?: FpCheckRules.FALLBACK_FAILED_TEXT
        }
        _fpCheckResults.update {
            it + (agentId to FpCheckResult(agentId, ok = verdict, ts = ts))
        }
        Log.i(
            TAG,
            "W18-1a: chat.fp_check für ${agentId.take(24)} verarbeitet " +
                "(ok=$verdict, chat=$chatId) — verdeckt, KEINE Chat-Bubble",
        )
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

    /**
     * W18-Kette: agent.status-Frame → [agentStatus]/[agentSubs]. Parsen
     * (Pflicht-Felder, fail-soft) + Faltung in [AgentStatusRules]:
     * sub == null → Haupt-Status-Update (last-write-wins), sub end != true →
     * Sub-Upert, end == true → Sub-Entfernung. working=false ohne sub setzt
     * den Haupt-Status ehrlich auf „fertig“.
     */
    private fun handleAgentStatusFrame(json: JsonObject) {
        val parsed = AgentStatusRules.parse(json, System.currentTimeMillis()) ?: return
        parsed.status?.let { slot ->
            // SEC-W1 W1C-4-Muster: atomares update{} (CAS) — kein Lost-Update.
            _agentStatus.update { AgentStatusRules.withStatus(it, parsed.agentId, slot) }
        }
        parsed.subUpsert?.let { sub ->
            _agentSubs.update { AgentStatusRules.withSubUpsert(it, parsed.agentId, sub) }
        }
        parsed.subRemoveKey?.let { key ->
            _agentSubs.update { AgentStatusRules.withSubRemove(it, parsed.agentId, key) }
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "agent.status: agent ${parsed.agentId.take(24)} " +
                    (parsed.status?.let { "working=${it.working}${it.label?.let { l -> " label=$l" } ?: ""} " } ?: "") +
                    (parsed.subUpsert?.let { "sub+${it.id.take(24)}" } ?: "") +
                    (parsed.subRemoveKey?.let { "sub-${it.take(24)}" } ?: ""),
            )
        }
    }

    /**
     * W18-Kette: agent.status.enc-Spiegel — entschlüsseln (Ketten-Sync
     * PFLICHT, sonst Ratchet-Desync) und DIESELBE klare Frame-Wirkung wie
     * agent.status (Klon des chat.activity.enc-Verbands: Top-Level-Felder
     * bleiben klar, ct ist reiner Kettensync).
     */
    private fun handleAgentStatusEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: return
        try {
            peer.decryptFrame(json)
            handleRotateHint(json, agentId, peer)
        } catch (e: Exception) {
            Log.w(TAG, "PH-3: agent.status.enc-Spiegel verworfen (Entschlüsselung fehlgeschlagen)")
            return
        }
        handleAgentStatusFrame(json)
    }

    /**
     * F1 (Verdrahtung MERGE-LANE 18.11.): cron.status-Frame → [cronJobs]-Flow.
     * Parsen fail-soft je Zeile (eine kaputte Zeile fällt, der Rest bleibt);
     * kein Downgrade-Guard nötig — der Adapter sendet enc-first, ein
     * Klartext-Frame existiert NUR ohne E2E-Session (Verband wie agent.status).
     */
    private fun handleCronStatusFrame(json: JsonObject) {
        val jobs = parseCronJobs(json)
        _cronJobs.value = jobs
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "cron.status: ${jobs.size} Jobs " +
                    "(agent ${stringOf(json, "agent_id")?.take(24) ?: "?"})",
            )
        }
    }

    /**
     * F1: cron.status.enc-Spiegel — Payload entschlüsseln (Kettensync
     * PFLICHT, sonst Desync) und DIESELBE klare Wirkung wie cron.status
     * (Klon des agent.status.enc-Verbands; Jobs = {jobs: …}-ct-Payload).
     */
    private fun handleCronStatusEnc(json: JsonObject) {
        val agentId = stringOf(json, "agent_id") ?: return
        val peer = cryptoSessions[agentId] ?: return
        val payload = try {
            peer.decryptFrame(json)
        } catch (e: Exception) {
            Log.w(TAG, "PH-3: cron.status.enc-Spiegel verworfen (Entschlüsselung fehlgeschlagen)")
            return
        }
        handleRotateHint(json, agentId, peer)
        val jobs = parseCronJobs(payload)
        _cronJobs.value = jobs
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "cron.status.enc: ${jobs.size} Jobs (agent ${agentId.take(24)})")
        }
    }

    /**
     * F1: cron.status-Jobs parsen — je Zeile name (Pflicht, sonst Zeile weg),
     * status (Protokoll-String, Unbekanntes = geplant per DashboardRules),
     * next_at (strikt numerisch — GSON-Koerzion als String-Primitiv bleibt
     * raus, statt still 0 zu erfinden).
     */
    private fun parseCronJobs(json: JsonObject): List<DashboardRules.CronJobItem> {
        val arr = json.get("jobs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = ArrayList<DashboardRules.CronJobItem>(arr.size())
        for (element in arr) {
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = row.get("name")
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (name.isEmpty()) continue
            val status = row.get("status")
                ?.takeIf { it.isJsonPrimitive }?.asString
            val nextAt = row.get("next_at")
                ?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asLong }.getOrNull() }
                ?.takeIf { it > 0L }
            out += DashboardRules.CronJobItem(
                name = name,
                state = DashboardRules.cronJobState(status),
                nextAtMs = nextAt,
            )
        }
        return out
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
        // HAKEN-FIX (1): Broker-msg_id des eigenen Sende-Ergebnisses — die
        // Platte bindet den Disk-Zwilling an die msg_id-Kette des Side-Cars
        // (null = Fehler-Pfad/Alt-Relay/Agenten-Bubble: kein Chip-Broker).
        msgId: String? = null,
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
                msgId = msgId,
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
    /**
     * MEDIA-T1: Medium als lokalen Chunk-Upload anstoßen + W4-01c-Chip-Bindung
     * (B3-Verband): [onResult] erhält nach dem FINALEN Chunk die Broker-msg_id
     * aus der Relay-Response (Relay W4-B3: je Chunk-POST eine Broker-msg_id,
     * chat_status queued/delivered/read keyen darauf; der Adapter nimmt die
     * msg_id des Completing-Chunks für chat.read.enc). [msgId] = null bei
     * Fehlern UND bei Relays ohne B3-Fix (Nutzer-Relays/Alt-Relay) — der Chip
     * bleibt in beiden Fällen ehrlich („✓ Gesendet"/„✕ Fehlgeschlagen").
     */
    fun sendMedia(
        agentId: String,
        kind: String,
        name: String,
        mime: String,
        bytes: ByteArray,
        // W16.3 (F2, C6-Verband): der UI-Bubble-Zeitstempel (ChatScreen
        // submitMedia tippt ihn EINMAL beim Anhängen) — der Persist-Verband
        // formatiert genau DIESEN ts, damit Disk-Restore-Dedupe
        // (ChatTimelineMerge.dedupeKey, Minuten-Auflösung) und Anzeige-Bubble
        // sich nicht an einem Minuten-Roll (Submit vs. Upload-Finale) zu
        // Dubletten verzählen (Parität zum sendChat-Pfad).
        sentAtMs: Long = System.currentTimeMillis(),
        onResult: (ok: Boolean, error: String?, mediaId: String?, msgId: String?) -> Unit,
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
                    outcome.onSuccess { result ->
                        if (result.sent) {
                            // Upload angenommen — Bubble-Text für Verlauf/UI.
                            val label = MediaProtocol.kindLabel(kind)
                            val sizeText = MediaProtocol.formatBytes(bytes.size.toLong())
                            // W16.3 (F1): app-privat Kopie persistieren wie
                            // incoming (2567) — ohne mediaPath stirbt der
                            // Play-Schalter der disk-restaurierten OWN-Bubble.
                            val localPath = runCatching {
                                persistOutboundMedia(name, bytes)
                            }.getOrNull()
                            persistChatBubble(
                                agentId, chatId, fromAgent = false,
                                text = "[$label] $name ($sizeText)",
                                ts = sentAtMs, status = null,
                                mediaKind = kind, mediaName = name, mediaMime = mime,
                                mediaSize = bytes.size.toLong(), mediaPath = localPath,
                                mediaId = mediaId,
                                // HAKEN-FIX (1): die Completing-Chunk-msg_id
                                // (B3-Verband) in den Disk-Zwilling — der
                                // Neustart bindet den Media-Chip wieder.
                                msgId = result.finalMsgId,
                            )
                            // W4-01c (B3-Verband): Broker-msg_id des Final-Chunks
                            // an die UI (ChatScreen-Bindung → msgIdByLocalId →
                            // ChatStatus-Chip-Kette queued → delivered → read);
                            // null bei Alt-/Nutzer-Relay ohne B3 → Chip ehrlich.
                            deliverMediaResult(onResult, true, null, mediaId, result.finalMsgId)
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
    ): MediaUploadOutcome {
        val chunks = MediaChunker.chunk(bytes)
        val sha = MediaProtocol.sha256Hex(bytes)
        _mediaUploads.update { it + (mediaId to MediaUploadState(agentId, kind, name, chunks.size)) }
        // W4-01c (B3-Verband): die Broker-msg_id des FINALEN Chunks (seq ==
        // total-1) aus der Relay-Response binden — der Relay W4-B3 erzeugt je
        // Chunk-POST eine Broker-msg_id (chat_status queued, Response-Feld,
        // Agent-Frame) und der Adapter nimmt die Completing-Chunk-ID für den
        // chat.read.enc-Receipt. Alt-/Nutzer-Relay ohne B3 → null → Chip
        // bleibt ehrlich „✓ Gesendet" (kein Bruch, kein Fake-Zustand).
        var finalMsgId: String? = null
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
                    return MediaUploadOutcome(sent = false, finalMsgId = null)
                }
                // W4-01c (B3-Verband): msg_id je Chunk-Response mitlesen —
                // gebunden wird NUR die des FINALEN Chunks (Adapter-Completing-
                // Chunk-Parität); früher Chunk-Response-IDs bleiben ungenutzt.
                val responseText = response.body?.string()
                if (index == chunks.lastIndex) {
                    finalMsgId = MediaProtocol.chunkResponseMsgId(responseText, chunk.seq, chunks.size)
                }
                _mediaUploads.update {
                    it + ((it[mediaId] ?: return@update it).let { state ->
                        mediaId to state.copy(sentChunks = chunk.seq + 1)
                    })
                }
            }
        }
        return MediaUploadOutcome(sent = true, finalMsgId = finalMsgId)
    }

    /** W4-01c (B3-Verband): Ergebnis je Chunk-Upload mit Broker-msg_id. */
    private data class MediaUploadOutcome(
        val sent: Boolean,
        val finalMsgId: String?,
    )

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

    /**
     * Media-Sende-Ergebnis immer auf den Main-Thread posten (UI-Bindung).
     * W4-01c: [msgId] = Broker-msg_id des Final-Chunks aus der Relay-Response
     * (B3-Verband) — null auf allen Fehlerpfaden UND bei Relays ohne
     * B3-Fix (Nutzer-/Alt-Relay) → Chip bleibt ehrlich, es gibt keinen
     * Broker-Status zu binden.
     */
    private fun deliverMediaResult(
        onResult: (ok: Boolean, error: String?, mediaId: String?, msgId: String?) -> Unit,
        ok: Boolean,
        error: String?,
        mediaId: String?,
        msgId: String? = null,
    ) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { onResult(ok, error, mediaId, msgId) }
            }
        } catch (_: Exception) {}
    }

    /**
     * W18-1a: FP-Abgleich-Senden — derselbe Draht wie chat.send (Agent-seitig
     * unverändert: 'FP-ABGLEICH: <hex>' als Klartext/chat.send.enc), aber
     * PERSIST=FALSE: der Sendeweg persistiert KEINE Verlaufs-Bubble, und die
     * Chat-UI hängt KEINE lokale Bubble an — der Abgleich taucht NUR als
     * verdecktes Ergebnis auf (chat.fp_check(.enc) → fpCheckResults), nie
     * als Chat-Zeile (W18-1a).
     */
    fun sendFpCheck(
        agentId: String,
        onResult: (ok: Boolean, error: String?, msgId: String?) -> Unit,
    ) {
        val fpHex = cryptoSessions[agentId]?.fingerprint
        if (fpHex.isNullOrBlank()) {
            deliverChatResult(onResult, false, "keine E2E-Session (kein session_fp)", null)
            return
        }
        sendChat(
            agentId,
            "${PhoneCryptoPeer.FP_PREFIX} $fpHex",
            persist = false,
            onResult = onResult,
        )
    }

    /**
     * PH2-MERGE: Nachricht an einen Agenten via POST /chat (gleiche Route wie
     * die Tools; Bearer = Kopplungs-Code, gleiche Gegenstelle wie der WS).
     * onResult läuft IMMER auf dem Main-Thread (UI darf direkt binden) —
     * ok=true heißt: vom Relay angenommen (delivered ODER queued).
     * PH2-T10: msgId = Broker-Nachrichten-ID aus der Relay-Antwort (null bei
     * Fehler/leerer Antwort) — die UI bindet den Zustell-/Lese-Status genau
     * an diese msg_id (RelayClient.chatStatus).
     * W4-FIX-JAG2 (C6): sentAtMs = der UI-Bubble-Zeitstempel (ChatScreen
     * submitQuoted tippt ihn EINMAL beim Anhängen) — der Persist-Verband
     * formatiert genau DIESEN ts, damit Disk-Restore-Dedupe (ChatTimelineMerge.
     * dedupeKey, Minuten-Auflösung) und Anzeige-Bubble sich nicht an einem
     * Minuten-Roll (Submit vs. HTTP-Ergebnis) zu Dubletten verzählen.
     */
    /**
     * Composer Welle 12: optionale Pro-Chat-Parameter (Modell + Denkaufwand)
     * reisen mit der Nachricht — E2E: in der VERSCHÜSSELten inneren Map,
     * Klartext: als optionale Felder. Der Relay/Plugin behandelt sie
     * backward-kompatibel (unbekannte Felder = kein Fehler); die Anwendung
     * auf Agent-Seite folgt in der Server-Welle.
     */

    fun sendChat(
        agentId: String,
        text: String,
        sentAtMs: Long = System.currentTimeMillis(),
        model: String? = null,
        effort: String? = null,
        // W18-1a: FALSE = verdeckter Versand (FP-Abgleich) — keine Verlaufs-
        // Bubble im Disk-Store; die UI hängt ohnehin keine Bubble an.
        persist: Boolean = true,
        onResult: (ok: Boolean, error: String?, msgId: String?) -> Unit,
    ) {
        val activeScope = scope
        if (activeScope == null || !isConnected) {
            // SIRS-02: auch der gescheiterte Versand bleibt im Verlauf (Chip
            // '✕ Fehlgeschlagen' überlebt den Restart statt zu verhungern).
            // W18-1a: persist=false (FP-Abgleich) — KEINE Verlaufs-Bubble.
            if (persist) persistChatBubble(agentId, chatId = "", fromAgent = false, text,
                sentAtMs, status = "failed")
            deliverChatResult(onResult, false, "Keine Verbindung zum Relay", null)
            return
        }
        val base = serverUrl?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            // W18-1a: persist=false — kein Verlaufseintrag (verdeckter Versand).
            if (persist) persistChatBubble(agentId, chatId = "", fromAgent = false, text,
                sentAtMs, status = "failed")
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
                        buildMap<String, String> {
                            put("msg_id", "")
                            put("text", text)
                            if (!model.isNullOrBlank()) put("model", model)
                            if (!effort.isNullOrBlank()) put("effort", effort)
                        },
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
                        if (!model.isNullOrBlank()) addProperty("model", model)
                        if (!effort.isNullOrBlank()) addProperty("effort", effort)
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
                // W18-1a: persist=false (verdeckter FP-Versand) — kein Eintrag.
                // HAKEN-FIX (1): die msg_id reist in den Disk-Zwilling —
                // Platte ↔ Side-Car-Kette (Neustart bindet restaurierte
                // Chips wieder an die msg_id).
                if (persist) persistChatBubble(agentId, historyChatId, fromAgent = false, text,
                    sentAtMs,
                    if (code in 200..299) null else "failed",
                    msgId = msgId)
                if (code in 200..299) {
                    deliverChatResult(onResult, true, null, msgId)
                } else {
                    deliverChatResult(onResult, false, "Relay lehnte ab (HTTP $code)", null)
                }
            }.onFailure { e ->
                // W18-1a: persist=false — auch der Fehler-Pfad persistiert im
                // verdeckten Versand NICHT (keine Chat-Zeile, W18-1a).
                if (persist) persistChatBubble(agentId, historyChatId, fromAgent = false, text,
                    sentAtMs, status = "failed")
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
 * W18-1a: verdeckter chat.fp_check-Kontrollframe — Auswertungsregeln (REIN,
 * ohne Android-Abhängigkeiten, JVM-getestet: FpCheckRulesTest).
 *
 * Vertrage: Der FP-Abgleich (⋮-Menü) darf in der App NIEMALS als Chat-Bubble
 * erscheinen. Der Agent antwortet als Kontrollframe — chat.fp_check (klar)
 * oder chat.fp_check.enc (E2E-Spiegel); die App VERARBEITET den Frame nur
 * (FP-Status: rot-Banner bei Abweichung + dismissbare Ergebnis-Zeile je
 * Agent) und persistiert/zeigt ihn NIE (kein chatEvents, kein Disk-Store,
 * keine Notification). Ältere Agenten antworten noch als Chat-Text mit dem
 * RESULT_PREFIX — isFpResultText() lenkt auch DIESER Weg auf den verdeckten
 * Pfad. Das Senden läuft unverändert als chat.send(.enc) mit dem Text
 * 'FP-ABGLEICH: <hex>' (PhoneCryptoPeer.FP_PREFIX) — Agent-seitig bleibt die
 * Erkennung identisch, nur die APP-seitige Anzeige bleibt stumm.
 */
object FpCheckRules {
    /** Frame-Typ des verdeckten Kontrollframes (klar + enc-Spiegel). */
    const val TYPE_CLEAR = "chat.fp_check"
    const val TYPE_ENC = "chat.fp_check.enc"

    /** Ergebnis-Prefix der Legacy-Klartext-Antwort (Sirs Revision (iii)). */
    const val RESULT_PREFIX = "Fingerprint-Abgleich:"

    /** Abweichungs-Markierung im Ergebnis-Text (→ rot-Banner). */
    const val FAILED_MARK = "FEHLGESCHLAGEN"

    /** Fehlertext, wenn ein FAILED-Frame ohne Text eintrudelt (fail-soft). */
    const val FALLBACK_FAILED_TEXT = "$RESULT_PREFIX $FAILED_MARK"

    data class Parsed(
        val agentId: String?,
        val chatId: String?,
        val ts: Long?,
        val ok: Boolean?,
        val text: String?,
    )

    /** Legacy-Antworttext = FP-Ergebnis? (nicht-leer + bekannter Präfix). */
    fun isFpResultText(text: String?): Boolean =
        !text.isNullOrBlank() && text.startsWith(RESULT_PREFIX)

    /**
     * Klar-Frame parsen (fail-soft): agent_id ist PFLICHT (null = Frame
     * verwerfen); ok/text/ts/chat_id sind optional — Legacy-Antworten
     * übertragen nur einen text, enc-Antworten tragen ok und/oder text.
     */
    fun parse(json: JsonObject): Parsed? {
        val agentId = json.string("agent_id")
            ?: return null
        return Parsed(
            agentId = agentId,
            chatId = json.string("chat_id"),
            ts = json.long("ts"),
            ok = json.bool("ok"),
            text = json.string("text"),
        )
    }

    /**
     * Enc-Nutzlast parsen (entschlüsselte innere Map): text (optional) + ok
     * (optional) — Rückgabe (text, ok) nur wenn mindestens eines gesetzt.
     */
    fun parsePayload(payload: JsonObject): Pair<String?, Boolean?>? {
        val text = payload.string("text")
        val ok = payload.bool("ok")
        return if (text == null && ok == null) null else text to ok
    }

    /**
     * FP-Verdict: das explizite ok-Feld GEWINNT (enc-Kontrollframe);
     * ohne ok entscheidet der Text — FEHLGESCHLAGEN direkt NACH dem Ergebnis-
     * Präfix = Abweichung (präzise Prefix-Prüfung wie am alten Bubble-Pfad,
     * kein contains-Fehlschlag bei anderen Textteilen), sonst bestanden;
     * beides ohne Aussagekraft = null (Frame verwerfen).
     */
    fun verdict(ok: Boolean?, text: String?): Boolean? = when {
        ok != null -> ok
        isFpResultText(text) ->
            !text!!.removePrefix(RESULT_PREFIX).trim().startsWith(FAILED_MARK)
        else -> null
    }

    // ── Fail-soft-Getter ─────────────────────────────────────────────────────
    private fun JsonObject.string(field: String): String? = runCatching {
        get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun JsonObject.long(field: String): Long? = runCatching {
        get(field)?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    private fun JsonObject.bool(field: String): Boolean? = runCatching {
        get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean
    }.getOrNull()
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
        // W16 (Fixliste-Punkt 26): Update-Check am bestehenden Weckrhythmus —
        // intern 24h-Fenster + Dedupe, eigener Thread, kein Extra-Alarm.
        com.hermesandroid.bridge.files.UpdateCheck.periodic(context)
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