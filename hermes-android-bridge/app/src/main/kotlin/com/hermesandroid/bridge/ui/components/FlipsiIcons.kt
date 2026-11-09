package com.hermesandroid.bridge.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * EINZIGER Icon-Zugriffspunkt der UI (v0.12.0).
 * Alle Icons sind eigene Vector-Drawables in res/drawable (Pfaddaten aus
 * google/material-design-icons, Apache-2.0) — kein zusätzliches Bibliotheks-
 * Artifact; kein icons-core-Import nötig. Tint über Icon(..., tint = ...).
 */
@DrawableRes
fun capIconRes(cap: String): Int = when (cap) {
    "screen" -> R.drawable.ic_monitor
    "screen_capture" -> R.drawable.ic_monitor_rec
    "notifications" -> R.drawable.ic_notifications
    "clipboard" -> R.drawable.ic_paste
    "files" -> R.drawable.ic_folder
    "health" -> R.drawable.ic_favorite
    "interaction" -> R.drawable.ic_touch
    "device" -> R.drawable.ic_build
    "notify_reply" -> R.drawable.ic_send
    "files_write" -> R.drawable.ic_delete
    "selfupdate" -> R.drawable.ic_refresh
    "location" -> R.drawable.ic_location
    "sms" -> R.drawable.ic_sms
    "calls" -> R.drawable.ic_call
    "contacts" -> R.drawable.ic_contacts
    "microphone" -> R.drawable.ic_mic
    else -> R.drawable.ic_placeholder
}

@Composable
fun CapIcon(cap: String, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Icon(
        painterResource(capIconRes(cap)),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** Status-Logos (Badge/Banner) — Zustand nie nur über Farbe. */
object StatusIcons {
    @DrawableRes val ok: Int = R.drawable.ic_check
    @DrawableRes val warn: Int = R.drawable.ic_info
    @DrawableRes val off: Int = R.drawable.ic_close
    @DrawableRes val error: Int = R.drawable.ic_warn
}

/** Nav-/Top-Bar-Icon (PH1-T1): Chat-Bubble als eigener Vector-Drawable. */
object NavIcons {
    @DrawableRes val chat: Int = R.drawable.ic_chat
}

/** Gruppen-Logos der Einstellungen (PH1-T2): Server, App, Agenten. */
object SettingsIcons {
    @DrawableRes val server: Int = R.drawable.ic_link_off
    @DrawableRes val app: Int = R.drawable.ic_build
    @DrawableRes val agent: Int = R.drawable.ic_power
    // PH-2X X-1: Gruppe 4 „App-Sperre“ (Schloss/Privacy-Drawable).
    @DrawableRes val security: Int = R.drawable.ic_privacy
    // PH-3 Welle 2: Gruppe 5 „Verschlüsselung“ (Schlüssel-Drawable).
    @DrawableRes val crypto: Int = R.drawable.ic_key
    // W4 App-Lane C: Gruppe „Sprache“ (Erde/Kugel-Drawable, haus-eigen).
    @DrawableRes val language: Int = R.drawable.ic_language
}

/** Berechtigungs-Icons (OS-Freigaben-Zeilen). */
object PermissionIcons {
    @DrawableRes val accessibility: Int = R.drawable.ic_accessibility
    @DrawableRes val allFiles: Int = R.drawable.ic_folder
    @DrawableRes val screenCapture: Int = R.drawable.ic_monitor_rec
    @DrawableRes val overlay: Int = R.drawable.ic_layers
    @DrawableRes val notifications: Int = R.drawable.ic_notifications
    @DrawableRes val battery: Int = R.drawable.ic_battery
    @DrawableRes val health: Int = R.drawable.ic_favorite
    @DrawableRes val autostart: Int = R.drawable.ic_restart
}

/** Gruppen-Logos der Fähigkeiten-Zentrale. */
object GroupIcons {
    @DrawableRes val ansehen: Int = R.drawable.ic_visibility
    @DrawableRes val bedienen: Int = R.drawable.ic_touch
    @DrawableRes val schreiben: Int = R.drawable.ic_save
    @DrawableRes val invasiv: Int = R.drawable.ic_privacy
    @DrawableRes val master: Int = R.drawable.ic_power

    @DrawableRes
    fun forGroupIndex(index: Int, totalGroups: Int = 4): Int = when (index) {
        0 -> ansehen
        1 -> bedienen
        2 -> schreiben
        else -> invasiv
    }
}

@Composable
fun ResourceIcon(@DrawableRes res: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Icon(
        painterResource(res),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}