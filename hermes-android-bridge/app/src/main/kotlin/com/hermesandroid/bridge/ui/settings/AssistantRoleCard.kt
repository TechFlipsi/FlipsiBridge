package com.hermesandroid.bridge.ui.settings

import android.app.Activity
import android.app.RoleManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.components.StatusBadge
import com.hermesandroid.bridge.ui.components.BadgeState

/**
 * Welle 13 / P10: Einstellungen → „Standard-Assistent“.
 *
 * Der Koordinator hält die RoleManager-Fragen (ROLE_ASSISTANT): Verfügbarkeit,
 * aktueller Halter und die Anfrage an Android (createRequestRoleIntent →
 * System-Default-Screen „Digitaler Assistent-App“ statt Gemini). Die Activity
 * BAUT den Koordinator (Konstruktor nimmt die Activity) — die Karte bekommt
 * ihn + den Launch-Callback und bleibt damit frei von Android-API-Details.
 * KEINE neue Manifest-Permission: RoleManager ist ein System-Rollenspiel,
 * kein uses-permission.
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
     * Rollen-Anfrage starten (Öffnen des System-Default-Screens; der Nutzer
     * bestätigt dort — wir schreiben NICHT selbst). Ergebnis via
     * Activity.onActivityResult (REQUEST_ROLE_ASSISTANT).
     */
    fun launchRequest(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val rm = activity.getSystemService(RoleManager::class.java) ?: return false
        return runCatching {
            val intent = rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            activity.startActivityForResult(intent, REQUEST_ROLE_ASSISTANT)
        }.isSuccess
    }

    companion object {
        /** onActivityResult-Code der Rollen-Anfrage (P10-Drill-Beweis). */
        const val REQUEST_ROLE_ASSISTANT = 4711
    }
}

/**
 * Karte „Standard-Assistent“: Status (hält/verfügbar/Android < 10) +
 * Einrichtungs-Button (48 dp, Ember — die einzige Aktion der Karte).
 */
@Composable
fun AssistantRoleCard(
    coordinator: DefaultAssistantCoordinator?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = coordinator?.let { Triple(it.supported, it.isRoleAvailable(), it.isHeld()) }

    SettingsCard(
        title = stringResource(R.string.settings_assistant_title),
        subtitle = stringResource(R.string.settings_assistant_desc),
        iconRes = SettingsIcons.assistant,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
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
                        stringResource(R.string.assistant_unavailable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                Button(
                    onClick = { coordinator?.launchRequest() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlipsiColors.Ember,
                        contentColor = FlipsiColors.OnEmber,
                    ),
                ) {
                    Text(stringResource(R.string.assistant_action))
                }
            }
        }
    }
}
