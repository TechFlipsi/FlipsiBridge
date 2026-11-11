package com.hermesandroid.bridge.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.i18n.AppLanguage
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.PermissionIcons
import com.hermesandroid.bridge.ui.components.ResourceIcon
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.components.StatusBadge

/**
 * Welle 13 / P4: Einstellungen → „Berechtigungen“.
 *
 * JE App-Berechtigung eine Zeile mit Zweck + Status + „Systemeinstellungen“-
 * Button (ACTION_APPLICATION_DETAILS_SETTINGS auf das eigene Paket — der
 * Standard-Weg, KEINE neue Manifest-Permission, KEINE Runtime-Request-Kaskade).
 *
 * Editions-Ehrlichkeit: angezeigt wird nur, was die Manifest-Merge der
 * gebauten Edition wirklich anfordert (z. B. SEND_SMS nur github-Edition) —
 * die Liste wird aus PackageManager.requestedPermissions abgeleitet statt
 * hart zu pflegen.
 *
 * Status-Auffrischung: Rückkehr aus den Systemeinstellungen (ON_RESUME)
 * erhöht [refreshTick] → Badges rechnen neu; Degradation bleibt sichtbar
 * („Nicht erteilt“ ist Text + Icon, nie nur Farbe — QA D/F).
 */
@Composable
fun PermissionsSettingsCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // W4-Muster: Activity über die CompositionLocal (wrapped Context ist
    // KEIN Activity-Context — context as? Activity wäre bei DE/EN null).
    val activity = AppLanguage.LocalAppActivity.current

    // ON_RESUME-Tick: nach dem Systemeinstellungen-Rückweg Status neu lesen.
    var refreshTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requested = remember(refreshTick) { requestedPermissions(context) }

    SettingsCard(
        title = stringResource(R.string.settings_permissions_title),
        subtitle = stringResource(R.string.settings_permissions_desc),
        iconRes = SettingsIcons.permissions,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        PERM_ROWS
            .filter { it.manifestPermission == null || it.manifestPermission in requested }
            .forEach { row ->
                val granted = remember(refreshTick, row.id) { row.isGranted(context) }
                PermissionSettingsRow(
                    iconRes = row.iconRes,
                    title = stringResource(row.titleRes),
                    description = stringResource(row.descRes),
                    granted = granted,
                    onOpenSystemSettings = {
                        openAppSystemSettings(context, activity)
                    },
                )
            }
    }
}

/** Zeile je Berechtigung: Icon + Zweck (AutoWrap) + StatusBadge + Sprung-Chip. */
@Composable
private fun PermissionSettingsRow(
    iconRes: Int,
    title: String,
    description: String,
    granted: Boolean,
    onOpenSystemSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Apple-Pass: Zeilen-Mindesthöhe 48 dp (keine Mini-Touchfelder).
            .heightIn(min = 48.dp),
    ) {
        ResourceIcon(iconRes, tint = FlipsiColors.TextSecondary, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        // Zwecke brechen mehrzeilig um (P2a: kein Text-Stacking mehr).
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            StatusBadge(
                text = stringResource(
                    if (granted) R.string.perm_status_granted else R.string.perm_status_missing
                ),
                state = if (granted) BadgeState.OK else BadgeState.OFF,
            )
        }
        Spacer(Modifier.width(8.dp))
        AssistChip(
            onClick = onOpenSystemSettings,
            label = { Text(stringResource(R.string.perm_open_system_settings)) },
            border = AssistChipDefaults.assistChipBorder(
                borderColor = FlipsiColors.LineStrong,
                enabled = true,
            ),
        )
    }
}

/** Manifest-Soll (gemergte Edition): angeforderte Permission-Namen. */
private fun requestedPermissions(context: Context): Set<String> = runCatching {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        android.content.pm.PackageManager.GET_PERMISSIONS,
    )
    info.requestedPermissions?.toSet() ?: emptySet()
}.getOrDefault(emptySet())

/** Sprung in die App-Systemeinstellungen (ACTION_APPLICATION_DETAILS_SETTINGS). */
private fun openAppSystemSettings(context: Context, activity: Activity?) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching {
        when (activity) {
            null -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> activity.startActivity(intent)
        }
    }
}

/** Eine sichtbare Berechtigungs-Zeile (Zweck + Status-Prüfung). */
private data class PermUi(
    val id: String,
    val iconRes: Int,
    val titleRes: Int,
    val descRes: Int,
    /** null = Sonderrecht (kein uses-permission) → immer sichtbar. */
    val manifestPermission: String?,
    val isGranted: (Context) -> Boolean,
)

/**
 * Die nutzerstellbaren OS-Freigaben der App (QA D). Sonderrechte
 * (Bedienungshilfe, Overlay, Alle Dateien, Akku) haben eigene Status-
 * Prüfungen; Runtime-Permissions laufen über checkSelfPermission.
 */
private val PERM_ROWS = listOf(
    PermUi(
        id = "accessibility",
        iconRes = PermissionIcons.accessibility,
        titleRes = R.string.perm_a11y_title,
        descRes = R.string.perm_a11y_desc,
        manifestPermission = null, // Sonderrecht: Accessibility-Service, kein uses-permission
        isGranted = { BridgeAccessibilityService.instance != null },
    ),
    PermUi(
        id = "overlay",
        iconRes = PermissionIcons.overlay,
        titleRes = R.string.perm_overlay_title,
        descRes = R.string.perm_overlay_desc,
        manifestPermission = "android.permission.SYSTEM_ALERT_WINDOW",
        isGranted = { Settings.canDrawOverlays(it) },
    ),
    PermUi(
        id = "notifications",
        iconRes = PermissionIcons.notifications,
        titleRes = R.string.perm_notifications_title,
        descRes = R.string.perm_notifications_desc,
        manifestPermission = "android.permission.POST_NOTIFICATIONS",
        isGranted = {
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(it, "android.permission.POST_NOTIFICATIONS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    ),
    PermUi(
        id = "microphone",
        iconRes = R.drawable.ic_mic,
        titleRes = R.string.perm_mic_title,
        descRes = R.string.perm_mic_desc,
        manifestPermission = "android.permission.RECORD_AUDIO",
        isGranted = {
            ContextCompat.checkSelfPermission(it, "android.permission.RECORD_AUDIO") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    ),
    PermUi(
        id = "camera",
        iconRes = PermissionIcons.screenCapture,
        titleRes = R.string.perm_camera_title,
        descRes = R.string.perm_camera_desc,
        manifestPermission = "android.permission.CAMERA",
        isGranted = {
            ContextCompat.checkSelfPermission(it, "android.permission.CAMERA") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    ),
    PermUi(
        id = "location",
        iconRes = R.drawable.ic_location,
        titleRes = R.string.perm_location_title,
        descRes = R.string.perm_location_desc,
        manifestPermission = "android.permission.ACCESS_COARSE_LOCATION",
        isGranted = {
            ContextCompat.checkSelfPermission(it, "android.permission.ACCESS_COARSE_LOCATION") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    ),
    PermUi(
        id = "allfiles",
        iconRes = PermissionIcons.allFiles,
        titleRes = R.string.perm_allfiles_title,
        descRes = R.string.perm_allfiles_desc,
        manifestPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
        isGranted = { Environment.isExternalStorageManager() },
    ),
    PermUi(
        id = "sms",
        iconRes = R.drawable.ic_sms,
        titleRes = R.string.perm_sms_title,
        descRes = R.string.perm_sms_desc,
        manifestPermission = "android.permission.SEND_SMS", // play-Edition: Manifest-Merge entfernt → Zeile entfällt
        isGranted = {
            ContextCompat.checkSelfPermission(it, "android.permission.SEND_SMS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    ),
    PermUi(
        id = "calls",
        iconRes = R.drawable.ic_call,
        titleRes = R.string.perm_calls_title,
        descRes = R.string.perm_calls_desc,
        manifestPermission = "android.permission.CALL_PHONE",
        isGranted = {
            ContextCompat.checkSelfPermission(it, "android.permission.CALL_PHONE") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    ),
    PermUi(
        id = "contacts",
        iconRes = R.drawable.ic_contacts,
        titleRes = R.string.perm_contacts_title,
        descRes = R.string.perm_contacts_desc,
        manifestPermission = "android.permission.READ_CONTACTS",
        isGranted = {
            ContextCompat.checkSelfPermission(it, "android.permission.READ_CONTACTS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    ),
    PermUi(
        id = "battery",
        iconRes = PermissionIcons.battery,
        titleRes = R.string.perm_battery_title,
        descRes = R.string.perm_battery_desc,
        manifestPermission = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", // play: entfernt
        isGranted = {
            val pm = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(it.packageName)
        },
    ),
)
