package com.hermesandroid.bridge.ui.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.client.RelayClient

/**
 * W22 LANE B — G2 Floating Bubble (docs/roadmap-anruf.md §7 Z.109): kleiner
 * runder Overlay-Knopf über JEDER App (SYSTEM_ALERT_WINDOW, gleiche Freigabe
 * wie der HUD — perm overlay ist im Manifest bereits deklariert, das ist ein
 * Sonderrecht OHNE Laufzeit-Dialog: die Freigabe passiert in den System-
 * einstellungen über [Settings.ACTION_MANAGE_OVERLAY_PERMISSION]).
 *
 * Bauweise (bewusst einfachster stabiler Weg — Plattform-Views statt Compose
 * im WindowManager: ein ComposeView bräuchte außerhalb einer Activity einen
 * eigenen Recomposer-/Lifecycle-Verband, der fragile Eigenbau wäre):
 *  - Foreground-Service (FGS-Typ specialUse, BEIDE Editionen — Manifest-
 *    Deklaration und startForeground-Typ sind deckungsgleich, sonst wirft
 *    Android 14 IllegalArgumentException, J4-Lektion aus BridgeConnection-
 *    Service). Die Blase selbst ist nur solange da, wie die Freigabe steht.
 *  - Blase: Kreis in Agenten-Farb-Hash ([AgentColorRules.bubbleColor]) mit
 *    Initiale — draggable (WindowManager-Parameter x/y je Fingerbewegung),
 *    Tap ohne Ziehen = Voice-Turn: [com.hermesandroid.bridge.AssistLaunch]
 *    .pendingVoice + Trampoline in die Chat-Route, exakt der bestehende
 *    G1-Start-Weg (AssistEntryActivity-Muster). Das Routing ist JVM-gepinnt:
 *    AssistantEntryRules.resolve(OVERLAY_BUBBLE) = CHAT_VOICE_TURN — die
 *    Blase startet NIE einen Anruf, auch nicht bei aktivem Assistent-Modus.
 *  - Mini-Panel-Host (G11): [showPanel] hängt das Mini-Panel (AssistantMini-
 *    Panel.kt) über das aktuelle Fenster — derselbe Overlay-Freigabe-Weg.
 *
 * Lifecycle-Sicherheit: Overlay-Freigabe fehlt beim Start → Dienst ruft
 * stopSelf() (nach startForeground — FGS-5-s-Regel), kein Crash, kein still
 * leeres Fenster. Konfigurationswechsel (Drehen) reißt den Dienst NICHT neu
 * auf (Services werden nicht recreated), die WindowManager-Parameter
 * überleben; bei Kleben der Parameter nach Rotation bleibt die Blase greifbar
 * (drag). onTaskRemoved hält den Dienst (FGS, wie BridgeConnectionService).
 */
class AssistantBubbleService : Service() {

    companion object {
        private const val TAG = "AssistantBubbleSvc"
        private const val NOTIFICATION_ID = 4160
        private const val CHANNEL_ID = "flipsibridge_assistant_bubble"
        private const val PREFS_NAME = "assistant_bubble"
        private const val KEY_DESIRED = "bubble_desired"

        /** Start-Befehl: Mini-Panel (statt nur Blase) zeigen — G11-Einstieg. */
        const val ACTION_SHOW_PANEL =
            "com.hermesandroid.bridge.action.ASSISTANT_SHOW_PANEL"

        /** Kontext-URL fürs Mini-Panel (Kontext-Chip), null = ohne Chip. */
        const val EXTRA_CONTEXT_URL = "assistant_context_url"

        /** Live-Instanz (nur lesen) — für „läuft der Dienst schon“-Fragen der UI. */
        @Volatile
        var instance: AssistantBubbleService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** Persistierter Nutzer-Wunsch (Toggle-Stand in den Einstellungen). */
        fun isDesired(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESIRED, false)

        fun setDesired(context: Context, desired: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DESIRED, desired).apply()
        }

        /**
         * Startet den Bubble-Dienst. VORHERiger Freigabe-Gate: ohne Overlay-
         * Freigabe wird NICHT gestartet (false) — die UI leitet den Nutzer
         * dann in den Permission-Flow (OverlayToggleRules, kein stiller
         * runCatching-Pfad).
         */
        fun start(context: Context): Boolean {
            if (!runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) {
                return false
            }
            return runCatching {
                val intent = Intent(context, AssistantBubbleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.onFailure { Log.w(TAG, "Bubble-Start fehlgeschlagen: ${it.message}") }
                .getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AssistantBubbleService::class.java))
            }.onFailure { Log.w(TAG, "Bubble-Stop fehlgeschlagen: ${it.message}") }
        }

        /**
         * G11: Mini-Panel über das aktuelle Fenster hängen. false = Overlay-
         * Freigabe fehlt (Aufrufer zeigt seinen Fallback, z. B. Trampoline in
         * den Chat — der AssistEntry-Hook bleibt damit verhaltenstreu).
         * Läuft der Dienst schon, wird das Panel direkt angehängt; sonst
         * startet der Dienst mit [ACTION_SHOW_PANEL] (Dienst-Start aus dem
         * App-Vordergrund heraus, FGS-Regeln eingehalten).
         */
        fun showPanel(context: Context, contextUrl: String?): Boolean {
            if (!runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) {
                return false
            }
            instance?.let { running ->
                running.attachPanel(contextUrl)
                return true
            }
            return runCatching {
                val intent = Intent(context, AssistantBubbleService::class.java).apply {
                    action = ACTION_SHOW_PANEL
                    putExtra(EXTRA_CONTEXT_URL, contextUrl)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.onFailure { Log.w(TAG, "Panel-Start fehlgeschlagen: ${it.message}") }
                .getOrDefault(false)
        }

        /** Panel weg (Blase bleibt, wenn der Dienst läuft). */
        fun hidePanel() {
            instance?.detachPanel()
        }
    }

    private var bubbleView: View? = null
    private var panelController: MiniPanelController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        // FGS-5-s-Regel: ZUERST startForeground anmelden, DANN Overlay bauen —
        // fällt der Overlay-Weg weg, stoppen wir sauber und nicht in der Frist.
        promoteForeground()
        attachBubble()
        Log.i(TAG, "Assistant-Bubble-Service erstellt")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        if (intent?.action == ACTION_SHOW_PANEL) {
            // G11-Start: Blase sicherstellen (Sticky-Restart), dann Panel drüber.
            if (bubbleView == null) attachBubble()
            attachPanel(intent.getStringExtra(EXTRA_CONTEXT_URL))
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App weg gewischt → Blase bleibt (FGS, gleiche Haltung wie die Brücke).
        Log.i(TAG, "Task entfernt — Bubble bleibt im Foreground bestehen")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        detachPanel()
        detachBubble()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.i(TAG, "Assistant-Bubble-Service gestoppt")
        super.onDestroy()
    }

    // ── Blase (G2) ─────────────────────────────────────────────────────────

    /** Hängt die Blase ans Fenster — ohne Overlay-Freigabe stopSelf (ehrlich). */
    private fun attachBubble() {
        if (bubbleView != null) return
        if (!runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)) {
            Log.w(TAG, "Overlay-Freigabe fehlt — Bubble-Service stoppt ehrlich")
            stopSelf()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val sizePx = (BUBBLE_SIZE_DP * density + 0.5f).toInt()
        val agentName = activeAgentName()
        val bubbleColor = AgentColorRules.bubbleColor(agentName)

        // Kreis: GradientDrawable oval in Agenten-Farb-Hash, Initiale zentriert.
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bubbleColor)
        }
        val label = TextView(this).apply {
            text = AgentColorRules.avatarInitial(agentName, fallback = "F")
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val bubble = FrameLayout(this).apply {
            background = circle
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            // A11y: die Blase ist ein Sprach-Knopf, keine Deko-Fläche.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            // STRING: assistant_bubble_a11y — de "Assistenten-Spracheingabe starten",
            // en "Start assistant voice input"
            contentDescription = "Assistenten-Spracheingabe starten"
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 220
        }

        wireDragAndTap(bubble, params, wm)
        runCatching { wm.addView(bubble, params) }.onFailure {
            Log.w(TAG, "Bubble-Attach fehlgeschlagen: ${it.message}")
            return
        }
        bubbleView = bubble
    }

    private fun detachBubble() {
        bubbleView?.let { view ->
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            }.onFailure { Log.w(TAG, "Bubble-Detach fehlgeschlagen: ${it.message}") }
        }
        bubbleView = null
    }

    /**
     * Ziehen übernimmt die Fingerbewegung in WindowManager-x/y (Blase folgt
     * ohne Neubau); ein Tap OHNE Ziehen (unterhalb Touch-Slop) startet den
     * Voice-Turn über den BESTEHENDEN Weg (AssistLaunch.pendingVoice +
     * Chat-Route), identisch zum AssistEntryActivity-Trampoline.
     */
    private fun wireDragAndTap(
        bubble: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var moved = false
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (moved || dx * dx + dy * dy > touchSlop * touchSlop) {
                        moved = true
                        params.x = downX + dx.toInt()
                        params.y = downY + dy.toInt()
                        runCatching { wm.updateViewLayout(bubble, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        startVoiceTurn()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** G1-Bestandsweg, über die JVM-gepinnte Regel: Bubble-Tap = Voice-Turn. */
    private fun startVoiceTurn() {
        val entry = AssistantEntryRules.resolve(
            source = AssistantEntryRules.Source.OVERLAY_BUBBLE,
            roleHeld = false, // Eigenes Fenster — die Rolle ist irrelevant (Regel liest sie nicht).
            callAssistantMode = false,
        )
        if (entry != AssistantEntryRules.Entry.CHAT_VOICE_TURN) {
            Log.w(TAG, "Bubble-Tap-Routing unerwartet: $entry — kein Start")
            return
        }
        com.hermesandroid.bridge.AssistLaunch.pendingVoice = true
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Voice-Turn-Trampoline fehlgeschlagen: ${it.message}") }
        // Panel zu — die Antwort/Spracheingabe lebt im Chat.
        detachPanel()
    }

    // ── Mini-Panel (G11) ────────────────────────────────────────────────────

    private fun attachPanel(contextUrl: String?) {
        if (panelController?.isAttached == true) {
            panelController?.updateContext(contextUrl)
            return
        }
        runCatching {
            panelController = MiniPanelController(this).also { it.show(contextUrl) }
        }.onFailure { Log.w(TAG, "Panel-Attach fehlgeschlagen: ${it.message}") }
    }

    private fun detachPanel() {
        panelController?.hide()
        panelController = null
    }

    // ── Hilfswege ──────────────────────────────────────────────────────────

    /** Aktiver Agent (Anzeige-Name) für Farb-Hash/Initiale; null = App-Fallback. */
    private fun activeAgentName(): String? {
        val agents = runCatching { RelayClient.agents.value }.getOrDefault(emptyList())
        return agents.firstOrNull { it.online }?.displayName
            ?: agents.firstOrNull()?.displayName
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                // STRING: assistant_bubble_channel_name — de "Assistenten-Blase",
                // en "Assistant bubble"
                "Assistenten-Blase",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                // STRING: assistant_bubble_channel_desc — de "Hält die Overlay-
                // Blase über allen Apps — Tippen startet die Spracheingabe", en
                // "Keeps the overlay bubble above all apps — tapping starts voice
                // input"
                description = "Hält die Overlay-Blase über allen Apps — Tippen startet die Spracheingabe"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * FGS-Anmeldung. Typ specialUse in BEIDEN Editionen (Manifest-Knoten und
     * Code sind deckungsgleich — die FGS-Typ-Prüfung ab Android 14 verlangt
     * exakte Parität; FOREGROUND_SERVICE_SPECIAL_USE ist im Haupt-Manifest
     * deklariert und wird im play-Overlay NICHT entfernt).
     */
    private fun promoteForeground() {
        runCatching {
            val tapIntent = PendingIntent.getActivity(
                this,
                12,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // minSdk 26 (= O): Kanal-Pflicht — Builder mit Kanal ist immer der Weg.
            val builder = Notification.Builder(this, CHANNEL_ID)
            builder
                // STRING: assistant_bubble_notif_title — de "Assistenten-Blase aktiv",
                // en "Assistant bubble active"
                .setContentTitle("Assistenten-Blase aktiv")
                // STRING: assistant_bubble_notif_text — de "Tippen auf die Blase startet
                // die Spracheingabe", en "Tap the bubble to start voice input"
                .setContentText("Tippen auf die Blase startet die Spracheingabe")
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentIntent(tapIntent)
                .setOngoing(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, builder.build())
            }
        }.onFailure { Log.w(TAG, "startForeground fehlgeschlagen: ${it.message}") }
    }
}

/** Blasengröße — Touch-Ziel ≥ 48 dp (HIG), etwas Luft darüber hinaus. */
private const val BUBBLE_SIZE_DP = 52
