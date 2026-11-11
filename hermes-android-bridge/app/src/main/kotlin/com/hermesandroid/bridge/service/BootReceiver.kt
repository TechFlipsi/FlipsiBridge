package com.hermesandroid.bridge.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hermesandroid.bridge.BuildConfig

/**
 * v0.11.0 (Sir-Requirement "jederzeit Zugriff"): Nach Handy-Neustart und
 * nach App-Update startet sich die Brücke von selbst — kein Sir-Eingriff,
 * kein App-Öffnen nötig. Beide Broadcasts sind geschützte System-Broadcasts.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i("BootReceiver", "Start after $action — bringe Bridge-Service hoch")
                val started = BridgeConnectionService.start(context)
                // PH-7/J1-F02 (play, targetSdk 36): FGS-Start aus BOOT_COMPLETED
                // ist ab targetSdk 35 verboten (ForegroundServiceStartNotAllowed-
                // Exception) — play zeigt statt stiller Nichtaktivität eine
                // Benachrichtigung mit App-Öffnen (Vordergrund-Start erlaubt).
                if (!started && BuildConfig.EDITION_PLAY) {
                    postManualOpenNotification(context)
                }
            }
        }
    }

    /** Play-Edition Fallback: Hinweis statt stiller Nichtaktivität (J1-F02). */
    private fun postManualOpenNotification(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            // V-J4-F02 (11.11.2026): ohne POST_NOTIFICATIONS ist notify() ein
            // stiller No-Op — dann bleibt es beim Log (sichtbarer Hinweis
            // folgt beim nächsten App-Start über die Status-Karten).
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.w("BootReceiver", "POST_NOTIFICATIONS fehlt — Manual-open-Hinweis nicht zustellbar")
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        BridgeConnectionService.CHANNEL,
                        "FlipsiBridge",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, com.hermesandroid.bridge.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, BridgeConnectionService.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("FlipsiBridge nicht aktiv")
                .setContentText("Nach dem Neustart kurz öffnen, um die Brücke zu verbinden.")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            manager.notify(4101, notification)
        }.onFailure { Log.w("BootReceiver", "Manual-open-Notification fehlgeschlagen: ${it.message}") }
    }
}