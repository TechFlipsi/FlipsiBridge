package com.hermesandroid.bridge.policy

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * PH-7/PS-G-2 (11.11.2026, Play-Requirement „deutliche Offenlegung + ausdrückliche
 * Einwilligung" — Belegt aus support.google.com/googleplay/android-developer:
 * Accessibility API, Notification-Listener, Mikrofon/Audio, Health Connect,
 * MANAGE_EXTERNAL_STORAGE, SYSTEM_ALERT_WINDOW): JEDE sensible Freigabe bekommt
 * EINE EIGENE Disclosure im normalen App-Fluss (nicht versteckt im Settings-
 * Accordion-Text) VOR dem System-Sprung, mit ausdrücklicher Einwilligung und
 * Consent-Protokoll (Zeitstempel + Versionsmarke = Review-Beweis).
 *
 * Play-Edition pflichtig — GitHub-Edition (targetSdk 34, sideload, bestehende
 * Installiert-Basis): request() ruft onProceed() DIREKT, UX unverändert
 * (kein Dialog, kein Zwang). Texte = DE-SSOT für Listing/Dutzerklärungspiegel
 * (Einstellungen → Rechtliches, PH-8): die gleichen Sätze stehen im Play-Listing.
 */
object DisclosureStore {
    const val PREFS = "flipsibridge_disclosures"
    const val VERSION = 1

    fun consented(context: Context, id: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("d_$id", false)

    fun stamp(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("d_$id", true)
            .putString("d_${id}_ts", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .putInt("d_${id}_v", VERSION)
            .apply()
    }
}

/** Sensible Capability-IDs, deren Einschalten eine Disclosure auslöst. */
val SENSITIVE_TOGGLES = setOf("microphone", "notifications", "location", "files", "contacts", "calls", "health")

object Disclosures {
    const val A11Y_TITLE = "Eingabehilfe-Freigabe"
    const val A11Y =
        "FlipsiBridge liest die Bildschirmstruktur und führt Tippen/Swipen strikt auf deine ausdrücklichen " +
            "Befehle aus — kein autonomes Handeln, keine Trigger, kein Verhaltensmonitoring. Bildschirminhalte " +
            "gehen an deinen eigenen, von dir betriebenen Server (Ende-zu-Ende verschlüsselt); kein Dritter " +
            "erhält Zugriff. Du kannst die Eingabehilfe jederzeit in den Systemeinstellungen entziehen."

    const val NOTIFICATIONS_TITLE = "Benachrichtigungszugriff"
    const val NOTIFICATIONS =
        "FlipsiBridge überträgt den Inhalt deiner Benachrichtigungen an deinen eigenen, von dir betriebenen " +
            "Server, damit dein persönlicher Assistent sie dir anzeigen und beantworten kann. Kein Dritter " +
            "erhält Zugriff. Du aktivierst den Benachrichtigungszugriff selbst und kannst ihn jederzeit entziehen."

    const val MICROPHONE_TITLE = "Mikrofon-Aufnahme"
    const val MICROPHONE =
        "FlipsiBridge nimmt Audio nur auf ausdrücklichen Agent-Befehl auf — die Aufnahme ist als aktive " +
            "Benachrichtigung sichtbar, wird an deinen eigenen Server übertragen (E2E verschlüsselt) und in " +
            "dessen Dateiablage abgelegt. Kein Dritter erhält Zugriff; die Freigabe kannst du jederzeit entziehen."

    const val HEALTH_TITLE = "Health-Connect-Daten"
    const val HEALTH =
        "FlipsiBridge liest Health-Connect-Daten (Schritte, Puls, Schlaf …) ausschließlich lesend und " +
            "überträgt sie an deinen eigenen, von dir betriebenen Server — kein TechFlipsi-Cloud-Dienst, " +
            "kein Dritter. Die Android-Freigaben wählst du selbst; Widerruf jederzeit möglich."

    const val FILES_TITLE = "Zugriff auf alle Dateien"
    const val FILES =
        "FlipsiBridge fordert den vollen Dateizugriff, damit dein Assistent Downloads, Dokumente und Fotos " +
            "auf dein Verlangen ablegen und lesen kann — ein Fern-Dateimanager des Gerät-Eigentümers. " +
            "Kein Dritter erhält Zugriff; die Freigabe schaltest du selbst in den Einstellungen um."

    const val OVERLAY_TITLE = "HUD-Overlay"
    const val OVERLAY =
        "Das Overlay-HUD zeigt Verbindungs- und Befehlsstatus über anderen Apps an — ehrlich beschriftet, " +
            "sammelt keine Daten und lässt sich jederzeit in den Systemeinstellungen ausschalten."

    const val FALLBACK_TITLE = "Berechtigungs-Offenlegung"
    const val FALLBACK =
        "Die zugehörigen Daten (z. B. Kontakte, Standort, Anrufe) gehen an deinen eigenen, von dir " +
            "betriebenen Server (E2E verschlüsselt) — kein Dritter, keine TechFlipsi-Cloud. Du kannst die " +
            "Freigabe jederzeit entziehen."

    fun titleFor(id: String): String = when (id) {
        "a11y" -> A11Y_TITLE
        "notifications" -> NOTIFICATIONS_TITLE
        "microphone" -> MICROPHONE_TITLE
        "health" -> HEALTH_TITLE
        "files" -> FILES_TITLE
        "overlay" -> OVERLAY_TITLE
        else -> FALLBACK_TITLE
    }

    fun bodyFor(id: String): String = when (id) {
        "a11y" -> A11Y
        "notifications" -> NOTIFICATIONS
        "microphone" -> MICROPHONE
        "health" -> HEALTH
        "files" -> FILES
        "overlay" -> OVERLAY
        else -> FALLBACK
    }
}

/**
 * Ein request-Slot + ein Host: die onClick-Stellen rufen request(...) auf
 * (Compose-observabel), der Host rendert EINEN AlertDialog und führt die
 * gespeicherte Aktion nach der Einwilligung aus.
 */
object DisclosureState {
    class Pending(
        val context: Context,
        val id: String,
        val title: String,
        val body: String,
        val onProceed: () -> Unit,
    )

    var pending: Pending? by mutableStateOf(null)
        private set

    /** Play: Disclosure vor dem Original-Fluss (einmalig je Konsent).
     *  GitHub: onProceed() DIREKT. */
    fun request(context: Context, id: String, onProceed: () -> Unit) {
        if (!com.hermesandroid.bridge.BuildConfig.EDITION_PLAY || DisclosureStore.consented(context, id)) {
            onProceed()
            return
        }
        pending = Pending(
            context = context,
            id = id,
            title = Disclosures.titleFor(id),
            body = Disclosures.bodyFor(id),
            onProceed = onProceed,
        )
    }
}

/** Einmalige Einbettung in die Settings-Composition (render als Overlay). */
@Composable
fun DisclosureDialogHost() {
    val pending = DisclosureState.pending ?: return
    AlertDialog(
        onDismissRequest = { DisclosureState.pending = null },
        title = { Text(pending.title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(pending.body, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Deine Einwilligung wird mit Datum protokolliert — jederzeit widerrufbar in den Systemeinstellungen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = com.hermesandroid.bridge.ui.FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    DisclosureStore.stamp(pending.context, pending.id)
                    val proceed = pending.onProceed
                    DisclosureState.pending = null
                    proceed()
                },
            ) { Text("Verstanden — aktivieren") }
        },
        dismissButton = {
            // Kein Zwang: Verweigern = ohne Aktion zuwerfen (Richtlinientreu).
            TextButton(onClick = { DisclosureState.pending = null }) { Text("Nicht jetzt") }
        },
    )
}
