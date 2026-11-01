package com.hermesandroid.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
                BridgeConnectionService.start(context)
            }
        }
    }
}