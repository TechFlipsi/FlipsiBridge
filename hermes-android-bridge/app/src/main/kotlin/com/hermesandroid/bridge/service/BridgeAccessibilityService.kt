package com.hermesandroid.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BridgeA11yService"

        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        // v0.12.0 (Akku P0-1): nüchterne Event-Quellen im Standardzustand —
        // /events als Brotkrumen behält App-Wechsel + Klicks. Das volle Set
        // (Scroll/Focus/Tastendruck) schaltet NUR /events/stream frei.
        private val DEFAULT_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        private val FULL_EVENT_TYPES = DEFAULT_EVENT_TYPES or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    }

    override fun onServiceConnected() {
        instance = this
        applyEventThrottle(fullSet = false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // v0.12.0 (Akku P0-1): Filter VOR jeder Allokation/IPC — ohne aktiven
        // Stream verwerfen wir die Hochfrequenz-Events bereits im Service statt
        // sie durch EventStore.add zu jagen (vorher bis 40 Events/s beim
        // Scrollen je einen Prozess-Wake + text-Join + Bounds-IPC).
        if (!com.hermesandroid.bridge.event.EventStore.highVolumeEvents) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                -> return
            }
        }
        com.hermesandroid.bridge.event.EventStore.add(event)
    }

    /**
     * Von /events/stream geschaltet (CommandDispatcher): das VOLLSET nur beim
     * aktiven Stream freimelden — die Quelle selbst wird im System gedrosselt
     * (Binder-Wakes vermieden statt nur gefiltert), notificationTimeout auf
     * den XML-Batchwert 300 ms.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun applyEventThrottle(fullSet: Boolean) {
        runCatching {
            serviceInfo = serviceInfo.apply {
                eventTypes = if (fullSet) FULL_EVENT_TYPES else DEFAULT_EVENT_TYPES
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.DEFAULT
                notificationTimeout = 300
            }
        }.onFailure { Log.w(TAG, "serviceInfo-Drossel unanwendbar: ${it.message}") }
    }

    private var isForeground = false
    private var foregroundTypes = 0

    // v0.11.0 (Review): startForeground/stopForeground synchronisieren —
    // der ungesicherte FGS-Typ-Zweikampf konnte eine laufende MediaProjection
    // auf Android 14+ abschießen.
    @Synchronized
    fun startForeground(includeMediaProjection: Boolean = false) {
        val requestedTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (includeMediaProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
        } else {
            0
        }

        if (isForeground && (foregroundTypes and requestedTypes) == requestedTypes) return

        val channelId = "hermes_bridge_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "FlipsiBridge",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält die Brücke zum Server aktiv"
            }
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
                .setContentTitle("FlipsiBridge")
                .setContentText("Brücke aktiv")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("FlipsiBridge")
                .setContentText("Brücke aktiv")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(TAG, "Starting foreground service with types=$requestedTypes")
            startForeground(1, notification, requestedTypes)
        } else {
            startForeground(1, notification)
        }
        isForeground = true
        foregroundTypes = requestedTypes
    }

    @Synchronized
    fun stopForeground() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
        foregroundTypes = 0
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        com.hermesandroid.bridge.executor.ActionExecutor.shutdownTts()
        instance = null
        super.onDestroy()
    }
}