package com.hermesandroid.bridge.ui.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Welle 13 / P10: Koordinator der RoleManager-Fragen (ROLE_ASSISTANT):
 * Verfügbarkeit, aktueller Halter und das Erzeugen der Anfrage an Android
 * (createRequestRoleIntent → System-Default-Screen „Digitaler Assistent-App"
 * statt Gemini). Die Activity BAUT den Koordinator (Konstruktor nimmt die
 * Activity) — die Karte bekommt ihn und bleibt damit frei von
 * Android-API-Details. KEINE neue Manifest-Permission: RoleManager ist ein
 * System-Rollenspiel, kein uses-permission.
 *
 * Welle 16 / P12 (Xiaomi 15U/HyperOS: „Einrichten" tat sichtbar nichts):
 * der Coordinator trägt das roleAvailable-GATE — der anfragende Button wird
 * von der Karte disabled + mit Begründungs-Zeile gerendert, wenn die Rolle
 * auf dem Gerät nicht angeboten wird (kein toter Button).
 *
 * W23-7 (Besitzer-KEIN-DOPPEL, Lane U7): die Klasse ist aus der entfernten
 * Einzel-Karte (W13 AssistantRoleCard) HIERHER umgezogen — EIN Ort: die
 * Assistent-Paket-Karte ([AssistantCard.kt AssistantPackageCard]) trägt
 * Status + Anfrage-Weg (G1) NUR HIER.
 */
class DefaultAssistantCoordinator(private val activity: Activity) {

    /** RoleManager existiert erst ab Android 10 (API 29) — minSdk ist 26. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29

    /** Gerät hat die Assistenten-Rolle überhaupt im Angebot. */
    fun isRoleAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val rm = activity.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    /** FlipsiBridge HÄLT die Rolle schon (Statuszeile der Karte). */
    fun isHeld(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val rm = activity.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    /**
     * Rollen-Anfrage-Intent bauen (Öffnen des System-Default-Screens; der
     * Nutzer bestätigt dort — wir schreiben NICHT selbst). null = RoleManager
     * fehlt/Intent nicht baubar → Aufrufer zeigt den Fallback.
     */
    fun createRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val rm = activity.getSystemService(RoleManager::class.java) ?: return null
        return runCatching { rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) }.getOrNull()
    }
}
