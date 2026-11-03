package com.hermesandroid.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hermesandroid.bridge.security.AppLockGate
import com.hermesandroid.bridge.security.AppLockManager
import com.hermesandroid.bridge.ui.BridgeScreen
import com.hermesandroid.bridge.ui.FlipsiTheme

/**
 * FlipsiBridge v0.11.0 — Komplett-Redesign: die ganze UI lebt jetzt in
 * Jetpack Compose (ui/BridgeScreen.kt). Die alte XML-Views-Endlosliste ist
 * ersetzt; Logik-Schicht (PairingManager/RelayClient/CapabilityGate) blieb.
 *
 * Kamera-Runtime-Recht (v0.10.8-Verhalten): nur anfragen, wenn der User die
 * "device"-Capability freizuschalten beginnt — keine Kamera-Frage beim Start.
 *
 * PH-2X X-1 (v0.13): AppLockGate VOR dem Content — solange eine PIN gesetzt
 * und nicht entsperrt ist, wird BridgeScreen GAR NICHT komponiert (Chat-
 * Inhalte sind hinter dem Gate nicht renderbar, auch kein Preview-Leak).
 * onPause stempelt die Hintergrund-Zeit für die Auto-Sperre (30 s/3 min/Nie).
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * PH-FIX-CHAT-A (F4): Foreground-Merker der App — RelayClient
         * unterdrückt Chat-Notifications für die gerade OFFENE Conversation,
         * solange die App im Vordergrund steht (im Hintergrund ist die
         * Notification gewollt, auch bei offener Conversation).
         */
        @Volatile
        var isForeground: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SEC-W3 (W3-1): FLAG_SECURE — Task-Switcher/Recents + Screenshots zeigen
        // NIEMALS App-Inhalt (Chat-Verlauf/Fingerprint-Hex). Ohne dieses Flag
        // bliebe der Recents-Snapshot am System-Switcher hängen, auch wenn die
        // App-Lock-Sperre aktiv ist.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        // X-1: App-Sperre initialisieren (Prefs + AndroidKeyStore-Engine,
        // idempotent) — VOR setContent, damit das Gate sofort weiß, ob eine
        // PIN existiert (App-Restart sperrt immer).
        AppLockManager.init(applicationContext)
        requestCameraIfNeeded()
        setContent {
            FlipsiTheme {
                // X-1: Gate VOR dem Nav-Host-Inhalt — Chat-Route NIE umgehbar.
                AppLockGate {
                    BridgeScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // PH-FIX-CHAT-A (F4): Foreground-Merker für den Notification-Spam-Schutz.
        isForeground = true
        // X-1: Rückkehr aus dem Hintergrund → Gate neu bewerten (Auto-Sperr-
        // Frist abgelaufen?); v0.12.0 QA-Fix (CHEF-03): Freigabe-Badges neu
        // ziehen statt „fehlt“ stehen zu lassen.
        AppLockManager.get().onAppForeground()
        com.hermesandroid.bridge.ui.BridgeStatus.refreshConfig()
    }

    override fun onPause() {
        super.onPause()
        // PH-FIX-CHAT-A (F4): siehe onResume — Merker zurücksetzen.
        isForeground = false
        // X-1: Hintergrund-Zeitstempel für die Auto-Sperre (30 s/3 min/Nie).
        AppLockManager.get().onAppBackground()
    }

    private fun requestCameraIfNeeded() {
        if (!com.hermesandroid.bridge.security.CapabilityGate.isEnabled("device")) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 4101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Ergebnis reicht — DeviceHardware prüft vor jedem Foto selbst.
    }
}