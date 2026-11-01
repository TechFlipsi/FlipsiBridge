package com.hermesandroid.bridge.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermesandroid.bridge.client.RelayClient

/**
 * Eigener Verbindungs-Foreground-Service (v0.11.0, Sir-Requirement "jederzeit
 * Zugriff"): hält die Bridge-Verbindung in einem echten FGS (type=dataSync),
 * unabhängig vom optionale Accessibility-Service. Vorher hing die Foreground-
 * Promotion an BridgeAccessibilityService — ohne A11y-Berechtigung kein
 * Foreground → HyperOS killte die Brücke. Das ist ab jetzt entkoppelt.
 *
 * Startpfade:
 *  - BOOT_COMPLETED / MY_PACKAGE_REPLACED → BootReceiver (Handy-Neustart/
 *    App-Update: Brücke kommt von selbst zurück — kein Sir-Eingriff).
 *  - App-Start (BridgeApplication.onCreate) — hier startet nur der erste
 *    WS-Aufbau aus onCreate heraus; der Service selbst wird zusätzlich
 *    hochgefahren, sobald connected wird (siehe RelayClient.connect).
 *
 * START_STICKY: tötet HyperOS den Prozess, wird der Service vom System
 * wiederhergestell → onCreate → autoConnect (idempotent, kein Flap).
 */
class BridgeConnectionService : Service() {

    companion object {
        private const val TAG = "BridgeConnService"
        private const val FOREGROUND_ID = 4100
        private const val CHANNEL = "flipsibridge_connection"

        /** Live-Instanz — der Alarms-Weckpfad startet den Service nur, wenn er verstorben ist (Akku P2-1). */
        @Volatile
        var instance: BridgeConnectionService? = null
            private set

        @JvmStatic
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, BridgeConnectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "Service-Start fehlgeschlagen: ${it.message}") }
        }
    }

    // v0.12.0 (Akku P2-1): FGS-Anmeldungs-Zustand — update-Pfaden laufen per notify().
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        promoteForeground("Bridge startet …")
        RelayClient.addStatusListener(serviceListener)
        // v0.11.0-QA-LEARNING: HIER KEIN autoConnect()! Es landete intern in
        // connect() → disconnect() → stopService() — der Service stoppte sich
        // kurz nach dem eigenen Start (Flatter + FGS-5-s-Regel-Crash, Emulator-
        // Repro 30.10.). autoConnect läuft in BridgeApplication.onCreate und
        // damit auch bei jedem Sticky-Restart des Prozesses.
        Log.i(TAG, "Connection-Service gestartet")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground je Start wieder anbieten (5-s-Regel nach startForegroundService)
        promoteForeground(RelayClient.currentStatusMessage)
        // Nach System-Wiederherstellung/Sticky-Restart verbinden, falls es
        // wirklich aus ist (autoConnect ist idempotent).
        RelayClient.autoConnect()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Sir wisch die App weg → Service läuft WEITER (persistent, FGS).
        // Weder Reconnect stoppen noch Notification entfernen — Zugriff muss
        // überleben. (HyperOS kann trotzdem killen; START_STICKY holt es zurück.)
        Log.i(TAG, "Task removed — Bridge bleibt im Foreground bestehen")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        RelayClient.removeStatusListener(serviceListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.i(TAG, "Connection-Service gestoppt")
        super.onDestroy()
    }

    private val serviceListener: (connected: Boolean, message: String) -> Unit =
        { _, message -> promoteForeground(message) }

    private fun promoteForeground(message: String) {
        runCatching {
            // Detektiv-F4: die Status-Notification muss TAPPBAR sein —
            // der Einzige dauerhafte Fehlerhinweis ("in der App neu koppeln")
            // führte sonst nirgendwohin.
            val tapIntent = android.app.PendingIntent.getActivity(
                this, 11,
                Intent(this, com.hermesandroid.bridge.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("FlipsiBridge")
                .setContentText(message.take(180))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(tapIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            // v0.12.0 (Akku P2-1): startForeground ist die ANMELDUNG als FGS
            // (Pflicht beim 5-s-Fenster nach startForegroundService); Updates
            // der gleichen Notification laufen danach per notify() — je
            // Statuswechsel/5-min-Alarm spart der ein System-Neu-Anmeldung.
            if (!isForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FOREGROUND_ID,
                        builder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(FOREGROUND_ID, builder.build())
                }
                isForeground = true
            } else {
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(FOREGROUND_ID, builder.build())
            }
        }.onFailure {
            // Ohne Permission/FGS-Verhalten darf die UI-Verbindung nicht sterben:
            Log.w(TAG, "startForeground fehlgeschlagen: ${it.message}")
            isForeground = false
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = android.app.NotificationChannel(
                CHANNEL,
                "FlipsiBridge Verbindung",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält die FlipsiBridge-Verbindung stabil und sichtbar"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}