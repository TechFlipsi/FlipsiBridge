package com.hermesandroid.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hermesandroid.bridge.ui.BridgeScreen
import com.hermesandroid.bridge.ui.FlipsiTheme

/**
 * FlipsiBridge v0.11.0 — Komplett-Redesign: die ganze UI lebt jetzt in
 * Jetpack Compose (ui/BridgeScreen.kt). Die alte XML-Views-Endlosliste ist
 * ersetzt; Logik-Schicht (PairingManager/RelayClient/CapabilityGate) blieb.
 *
 * Kamera-Runtime-Recht (v0.10.8-Verhalten): nur anfragen, wenn der User die
 * "device"-Capability freizuschalten beginnt — keine Kamera-Frage beim Start.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCameraIfNeeded()
        setContent {
            FlipsiTheme {
                BridgeScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // v0.12.0 QA-Fix (CHEF-03): Rückkehr aus System-Settings (Eingabehilfe/
        // Overlay/Alle-Dateien/Health) → Freigabe-Badges sofort neu ziehen,
        // statt „fehlt" stehen zu lassen bis zur nächsten UI-Aktion.
        com.hermesandroid.bridge.ui.BridgeStatus.refreshConfig()
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