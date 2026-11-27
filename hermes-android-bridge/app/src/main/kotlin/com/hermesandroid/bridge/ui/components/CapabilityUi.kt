package com.hermesandroid.bridge.ui.components

import androidx.annotation.StringRes
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.R

/**
 * UI-Datenmodell der Fähigkeiten-Zentrale (v0.12.0).
 * Die Capabilities selbst bleiben im CapabilityGate gespeichert (SSOT der Flags);
 * dieses Modell ist NUR die Anzeige-Schicht (Label/Beschreibung/Gruppierung/Invasiv-Markierung).
 * PH-7 (11.11.2026, Jäger J3-F1/J4-F1/F2): die Anzeige-Schicht ist edition-aware —
 * play benennt SMS ehrlich um (Intent-Entwurf statt Direktversand, auch im Text).
 * W16 (Punkt 26): „selfupdate“ entfällt ganz (Kein In-App-Install mehr —
 * Updates laufen je Edition über Play Store/GitHub, App zeigt nur die Info-Push).
 *
 * W15 (I18n-Fix): Label/Beschreibung als @StringRes statt hartkodierter
 * deutscher Strings — stringResource unter dem AppLanguageGate löst live in der
 * gewählten App-Sprache auf (DE/EN), ohne Neustart.
 */
data class CapabilityUi(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val invasive: Boolean = false,
)

/** Sensible Fähigkeiten — beim Master-„Alle an“ mit Bestätigungsdialog.
 *  N2 (11.11.2026, play): der SMS-Entwurf (Intent-Route ohne Direktversand) ist
 *  kein invasiver Pfad — play filtert sms aus der Bestätigungsliste raus. */
val INVASIVE_CAPS: Set<String> by lazy {
    if (BuildConfig.EDITION_PLAY) {
        setOf("location", "calls", "contacts", "microphone")
    } else {
        setOf("location", "sms", "calls", "contacts", "microphone")
    }
}

/** Alle nutzersteuerbaren Fähigkeiten (GitHub 15, play 14 — Zählerbasis des Master-Schalters). */
val ALL_CAPABILITY_UIS: List<CapabilityUi> by lazy {
    val base = listOf(
        CapabilityUi("screen", R.string.cap_label_screen, R.string.cap_desc_screen),
        CapabilityUi("screen_capture", R.string.cap_label_screen_capture, R.string.cap_desc_screen_capture),
        CapabilityUi("notifications", R.string.cap_label_notifications, R.string.cap_desc_notifications),
        CapabilityUi("clipboard", R.string.cap_label_clipboard, R.string.cap_desc_clipboard),
        CapabilityUi("files", R.string.cap_label_files, R.string.cap_desc_files),
        CapabilityUi("health", R.string.cap_label_health, R.string.cap_desc_health),
        CapabilityUi("interaction", R.string.cap_label_interaction, R.string.cap_desc_interaction),
        CapabilityUi("device", R.string.cap_label_device, R.string.cap_desc_device),
        CapabilityUi("notify_reply", R.string.cap_label_notify_reply, R.string.cap_desc_notify_reply),
        CapabilityUi("files_write", R.string.cap_label_files_write, R.string.cap_desc_files_write, invasive = false),
        CapabilityUi("location", R.string.cap_label_location, R.string.cap_desc_location, invasive = true),
        CapabilityUi("sms", R.string.cap_label_sms, R.string.cap_desc_sms, invasive = true),
        CapabilityUi("calls", R.string.cap_label_calls, R.string.cap_desc_calls, invasive = true),
        CapabilityUi("contacts", R.string.cap_label_contacts, R.string.cap_desc_contacts, invasive = true),
        CapabilityUi("microphone", R.string.cap_label_microphone, R.string.cap_desc_microphone, invasive = true),
    )
    if (BuildConfig.EDITION_PLAY) {
        base.map { ui ->
            // PS-A-14: ehrlicher Text — play öffnet nur den Entwurf.
            if (ui.id == "sms") ui.copy(
                labelRes = R.string.cap_label_sms_draft,
                descriptionRes = R.string.cap_desc_sms_draft,
                invasive = false,
            ) else ui
        }
    } else base
}

/** Vier Gruppen der Fähigkeiten-Zentrale (Reihenfolge = Anzeige-Reihenfolge).
 *  N1 (11.11.2026, play): der play-SMS-Entwurf ist nicht invasiv — er lebt
 *  in der Gruppe „Gerät bedienen“, damit er EIN schaltbarer Ort bleibt.
 *  W15: Titel als @StringRes (Auflösung per stringResource in der UI). */
fun capabilityGroups(): List<Pair<Int, List<CapabilityUi>>> = listOf(
    R.string.caps_group_view to ALL_CAPABILITY_UIS.filter { it.id in setOf("screen", "screen_capture", "notifications", "clipboard", "files", "health") },
    R.string.caps_group_control to ALL_CAPABILITY_UIS.filter {
        it.id in (
            if (BuildConfig.EDITION_PLAY) {
                setOf("interaction", "device", "notify_reply", "sms")
            } else {
                setOf("interaction", "device", "notify_reply")
            }
            )
    },
    R.string.caps_group_write to ALL_CAPABILITY_UIS.filter { it.id in setOf("files_write") },
    R.string.caps_group_invasive to ALL_CAPABILITY_UIS.filter { it.invasive },
)
