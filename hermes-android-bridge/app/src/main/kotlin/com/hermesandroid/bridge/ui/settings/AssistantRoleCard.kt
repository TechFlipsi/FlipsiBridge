package com.hermesandroid.bridge.ui.settings

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.components.StatusBadge
import com.hermesandroid.bridge.ui.components.BadgeState

/**
 * Welle 13 / P10: Einstellungen → „Standard-Assistent“.
 *
 * Der Koordinator hält die RoleManager-Fragen (ROLE_ASSISTANT): Verfügbarkeit,
 * aktueller Halter und das Erzeugen der Anfrage an Android
 * (createRequestRoleIntent → System-Default-Screen „Digitaler Assistent-App“
 * statt Gemini). Die Activity BAUT den Koordinator (Konstruktor nimmt die
 * Activity) — die Karte bekommt ihn und bleibt damit frei von
 * Android-API-Details. KEINE neue Manifest-Permission: RoleManager ist ein
 * System-Rollenspiel, kein uses-permission.
 *
 * Welle 16 / P12 (Sir, Xiaomi 15U/HyperOS: „Einrichten“ tat sichtbar nichts):
 * das LAUNCH-Ergebnis wurde nie verarbeitet (startActivityForResult lief in
 * den Leerraum, kein onActivityResult-Handler in src/main; runCatching
 * verschluckte Fehlstarts). Neu:
 *  - Start über ActivityResultContracts.StartActivityForResult (Compose-
 *    Launcher) — der ERFOLGS-/ABBRUCHS-Pfad behandelt das Ergebnis konkret.
 *  - roleAvailable-GATE: Button disabled + Begründungs-Zeile, wenn die Rolle
 *    auf dem Gerät nicht angeboten wird (kein toter Button mehr).
 *  - Kein Ergebnis/Abbruch → Fallback-Dialog mit DIREKT-Sprung
 *    (Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS → „Digitale Assistent-App“).
 *  - Rolle wird bei Rückkehr (Launcher-Ergebnis UND ON_RESUME) frisch gelesen
 *    — der „Held“-Status klebt nicht mehr.
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

/**
 * Karte „Standard-Assistent“: Status (hält/verfügbar/Android < 10) +
 * Einrichtungs-Button (48 dp, Ember — roleAvailable-gate't) + Fallback-Dialog
 * bei fehlendem/abgebrochenem Ergebnis.
 */
@Composable
fun AssistantRoleCard(
    coordinator: DefaultAssistantCoordinator?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // P12: Status-Tick — der RoleManager-Zustand wird NICHT als Kleber gelesen:
    // neu gerechnet bei 1) Launcher-Rückkehr, 2) ON_RESUME, 3) Kartenöffnung.
    var statusTick by remember { mutableIntStateOf(0) }
    val status = remember(statusTick) {
        coordinator?.let { Triple(it.supported, it.isRoleAvailable(), it.isHeld()) }
    }

    // P12-Handler: das Ergebnis der Rollen-Anfrage (Rückkehr vom System-
    // Default-Screen). RESULT_OK = Rolle angenommen/abgebrochen mit Ergebnis —
    // in beiden Fällen Rolle frisch lesen; KEIN Ergebnis-Fall (Cancel-Code,
    // Dialog nicht verstanden) → Fallback-Dialog mit Direkt-Sprung.
    var showFallback by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        statusTick++
        if (result.resultCode != Activity.RESULT_OK) {
            // Abbruch/konfuses Ergebnis: kein stiller Tod (Sir-Fixliste 12) —
            // der Fallback-Freiweg (Standard-Apps → Digitale Assistent-App)
            // wird prominent angeboten.
            Toast.makeText(
                context,
                context.getString(R.string.assistant_dialog_failed),
                Toast.LENGTH_SHORT,
            ).show()
            showFallback = true
        }
    }

    // Rückkehr aus der Systemeinstellung (Fallback-Pfad): Rolle neu lesen.
    // Keyset mit `expanded`: der Observer liest den Klampe-Zustand nicht als
    // Stale-Capture aus der Install-Composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, expanded) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && expanded) statusTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsCard(
        titleRes = R.string.settings_assistant_title,
        iconRes = SettingsIcons.assistant,
        expanded = expanded,
        onExpandedChange = { open ->
            // Kartenöffnung und Rückkehr-Weg: Frische-Bump (P12).
            if (open) statusTick++
            onExpandedChange(open)
        },
        modifier = modifier,
    ) {
        // W16 P19: Beschreibung lebt IN der geöffneten Karte (Kachel-Untertitel sind entfernt).
        Text(
            stringResource(R.string.settings_assistant_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = FlipsiColors.TextSecondary,
        )
        val supported = status?.first ?: false
        val roleAvailable = status?.second ?: false
        val held = status?.third ?: false
        when {
            !supported -> Text(
                stringResource(R.string.assistant_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
            )
            held -> StatusBadge(
                text = stringResource(R.string.assistant_status_held),
                state = BadgeState.OK,
            )
            else -> {
                Text(
                    if (roleAvailable) {
                        stringResource(R.string.assistant_status_other)
                    } else {
                        stringResource(R.string.assistant_role_unavailable_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                // P12-GATE: ohne roleAvailable bleibt der Knopf disabled —
                // kein tot-wirkendes „tut nichts“ mehr.
                Button(
                    onClick = {
                        val intent = coordinator?.createRequestIntent()
                        if (intent == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.assistant_dialog_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                            showFallback = true
                        } else {
                            runCatching { roleLauncher.launch(intent) }.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.assistant_dialog_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showFallback = true
                            }
                        }
                    },
                    enabled = roleAvailable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlipsiColors.Ember,
                        contentColor = FlipsiColors.OnEmber,
                        disabledContainerColor = FlipsiColors.PanelElev,
                        disabledContentColor = FlipsiColors.TextSecondary,
                    ),
                ) {
                    Text(stringResource(R.string.assistant_action))
                }
            }
        }
    }

    if (showFallback) {
        // P12-Fallback: „Systemeinstellungen → Digitale Assistent-App“ mit
        // DIREKT-Intent (kein Snackbar-Host in der Karte — der Dialog ist der
        // prominente Weg, der Sprung bleibt ein bewusster Nutzer-Tap).
        AlertDialog(
            onDismissRequest = { showFallback = false },
            title = { Text(stringResource(R.string.assistant_dialog_failed)) },
            text = { Text(stringResource(R.string.assistant_dialog_failed_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showFallback = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.assistant_dialog_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text(stringResource(R.string.assistant_open_default_apps)) }
            },
            dismissButton = {
                TextButton(onClick = { showFallback = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = FlipsiColors.PanelElev,
        )
    }
}
