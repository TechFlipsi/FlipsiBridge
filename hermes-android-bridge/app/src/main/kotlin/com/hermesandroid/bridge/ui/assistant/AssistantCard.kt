package com.hermesandroid.bridge.ui.assistant

import android.app.Activity
import android.content.Context
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.permissions.DeviceFeature
import com.hermesandroid.bridge.permissions.DeviceIntentResolver
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.components.StatusBadge
import com.hermesandroid.bridge.ui.settings.SettingsCard

/**
 * ASSISTENT-PAKET-KARTE (G1/G2/G5, docs/Anruf-Konzept, Besitzer-Zusatz
 * 16.11.26) — ÜBERBLICK über die App-seitigen Assistenten-Bausteine EINER
 * Karte (app-generisch: keine Geräte-/Modell-Namen, keine Literale). Regeln
 * in [AssistantRules] (JVM-getestet, AssistantRulesTest).
 *
 * W23-7 (Besitzer-KEIN-DOPPEL, Lane U7): die Karte ist der EINIGE Assistent-
 * Eintrag in Einstellungen — die frühere Einzel-Karte „Standard-Assistent"
 * (W13 AssistantRoleCard) ist aus dem Menü entgangen, ihr Inhalt lebt hier:
 * G1 rendert Status UND Rollen-Anfrage (Einrichten-Button mit
 * roleAvailable-GATE + Fallback-Dialog, P12-Verband) aus dem
 * [DefaultAssistantCoordinator], den die ACTIVITY baut (SettingsHome).
 *
 * Verdrahtung (Stand W23):
 *  - Einstellungen: SettingsHome hängt EINE Karte (AssistantPackageCard,
 *    item „assistant“) — der Rollen-Anfrage-Weg ist seit dem U7-Merge
 *    Teil dieser Karte, keine zweite Einzel-Karte mehr. Die G2/G5-Zeilen-Texte kommen per [AssistantCardTexts] aus den
 *    inzwischen gemergten assistant_pkg_*-Strings (strings.xml bleibt in
 *    Koordinator-Hand, i18n ×3 Locales); NEUE Texte dieser Welle sind
 *    Code-Literale mit STRING-Markern (Sammel-Liste im Lane-Report).
 *  - G2: W22 LANE B — der Bubble-Dienst ist gebaut (AssistantBubbleService:
 *    Overlay-Blase über allen Apps, Tap = Voice-Turn); die Karte schaltet
 *    ihn EINEM Ort: dem Toggle hier. Tap ohne Overlay-Freigabe öffnet
 *    ACTION_MANAGE_OVERLAY_PERMISSION mit Rückweg (ON_RESUME startet die
 *    Blase, sobald die Freigabe steht); der Fail-Weg hat eine Ergebnis-Zeile
 *    (perm overlay — der Manifest-Eintrag SYSTEM_ALERT_WINDOW ist gesetzt).
 *  - G5: Session-Schlüssel + gespeicherte Zustimmung kommen aus der Verdrahtung;
 *    das GATE im screen-Capability-Pfad ist Anschluss-Lane (CapabilityGate/
 *    Session-Verband — die Karte entscheidet nicht über das reale Lesen).
 */

/** Texte der G2/G5-Zeilen — NEUE String-Keys (Bedarf notiert, nicht in strings.xml). */
data class AssistantCardTexts(
    /** G2: Zeilen-Titel der Overlay-Bubble („Overlay-Blase über allen Apps“). */
    val bubbleTitle: String? = null,
    /** G2: Beschreibung im READY-Zustand („Tippen öffnet die Spracheingabe“). */
    val bubbleReadyDesc: String? = null,
    /** G2: Beschreibung bei fehlender Freigabe. */
    val bubbleNeedPermDesc: String? = null,
    /** G2: Button-Label des Overlay-Freigabe-Wegs. */
    val bubbleGrantAction: String? = null,
    /** G2: A11y-Beschreibung des Buttons. */
    val bubbleGrantA11y: String? = null,
    /** G2: Leit-Zeile, wenn der OEM-Weg nichts auflöst. */
    val bubbleGrantUnavailable: String? = null,
    /** G5: Zeilen-Titel des Screen-Kontexts („Screen-Kontext“). */
    val screenContextTitle: String? = null,
    /** G5: Beschreibung (Zustimmung je Session, Nutzerklick). */
    val screenContextDesc: String? = null,
    /** G5: Hinweis, wenn keine Session trägt (Demo/ohne Backend). */
    val screenContextNoSession: String? = null,
    /** G5: A11y-Beschreibung der Zeile im Granted-Zustand. */
    val screenContextGrantA11y: String? = null,
    /** G5: A11y-Beschreibung der Zeile im Fragen-Zustand. */
    val screenContextAskA11y: String? = null,
)

/**
 * @param coordinator RoleManager-Koordinator der ACTIVITY (W4-Muster: die
 *   Activity baut ihn und reicht ihn herein — der wrapped Sprach-Context ist
 *   KEIN Activity-Context). null = Anfrage-Weg aus: die ON_RESUME/Status-
 *   Zeilen bleiben, aber kein „Einrichten"-Knopf (Preview/Fallback-Sicherheit).
 * @param screenContextSessionKey aktueller Session-Schlüssel (agentId#epoch,
 *   [ScreenContextConsentRules.consentKey]); null = keine Session → die Zeile
 *   ehrlich „ohne Session“ statt eines Schalters fürs Leere.
 * @param screenContextConsent gespeicherter Zustimmungs-Stand (Key oder null).
 * @param onGrantScreenContext Nutzer-Klick AN — die Verdrahtung persistsiert
 *   [ScreenContextConsentRules.grant]; die Karte schreibt NIE still.
 * @param onRevokeScreenContext Nutzer-Klick AUS ([ScreenContextConsentRules.revoke]).
 * @param onFeedback W28 (Lane B): ehrliche Ausgang-Meldungen (Rollen-Anfrage
 *   gescheitert/Fallback-Sprung verwehrt) laufen über DIE eine W19-Zentral-
 *   Snackbar (Verdrahtung: SettingsHome.onFeedback → BridgeScreen.snack —
 *   dasselbe Muster wie die Umbenennen-Antworten). KEIN android.widget.Toast
 *   mehr (W20-Räumung, hier war der letzte Rest); leerer Default = still
 *   (kein Pflicht-Param für bestehende Aufrufer/Preview-Fasern).
 */
@Composable
fun AssistantPackageCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    texts: AssistantCardTexts = AssistantCardTexts(),
    coordinator: DefaultAssistantCoordinator? = null,
    screenContextSessionKey: String? = null,
    screenContextConsent: String? = null,
    onGrantScreenContext: () -> Unit = {},
    onRevokeScreenContext: () -> Unit = {},
    onFeedback: (String) -> Unit = {},
) {
    val context = LocalContext.current

    // P12-Muster: RoleManager/Overlay-Stand NICHT als Kleber — neu gelesen
    // bei Kartenöffnung (Tick).
    var statusTick by remember { mutableIntStateOf(0) }
    val roleStatus = remember(statusTick) { readAssistantRoleStatus(context) }
    val overlayOk = remember(statusTick) {
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    }
    // Ergebnis-Zeile des G2-Freigabe-/Start-Wegs (sichtbarer Fail-Weg) —
    // Karten-Ebene, damit der Rückweg-Effekt sie räumen kann.
    var permFlowLine by remember { mutableStateOf<String?>(null) }

    // P12-Handler (G1, aus der W13-Einzel-Karte konsolidiert): das Ergebnis
    // der Rollen-Anfrage (Rückkehr vom System-Default-Screen). RESULT_OK =
    // Rolle angenommen — Tick liest frisch; kein Ergebnis/Abbruch → Snack
    // (W19-Zentral-Snackbar, W28-Räumung statt System-Toast) +
    // Fallback-Dialog mit DIREKT-Sprung (Standard-Apps → Digitale
    // Assistent-App), KEIN stiller Tod (Fixliste 12).
    var showFallback by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        statusTick++
        if (result.resultCode != Activity.RESULT_OK) {
            // W28 (Lane B): die Ausgang-Meldung läuft über DIE eine W19-
            // Zentral-Snackbar (onFeedback) — System-Toast geräumt.
            onFeedback(context.getString(R.string.assistant_dialog_failed))
            showFallback = true
        }
    }
    // Anfrage-Intent EINMAL je Karten-Zustand (Tick/Koordinator): die
    // enabled-Logik des Einrichten-Knopfs fragt den RoleManager nicht je
    // Recomposition an; der ON_RESUME-Tick unten erneuert ihn mit.
    val roleIntent = remember(statusTick, coordinator) {
        coordinator?.createRequestIntent()
    }

    // W22 LANE B (G2-Rückweg): ON_RESUME nach ACTION_MANAGE_OVERLAY_PERMISSION —
    // steht die Freigabe JETZT, startet die Blase sichtbar (das Ergebnis des
    // Freigabe-Wegs wird erkennbar, statt dass der Schalter still klebt).
    // Gleiches P12-Muster (Zustand frisch lesen bei Rückkehr) — der ON_RESUME-
    // Tick erneuert Rolle (G1) UND Overlay/Bubble (G2) mit EINEM Tick.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, expanded) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && expanded) {
                val granted = runCatching { Settings.canDrawOverlays(context) }
                    .getOrDefault(false)
                if (OverlayToggleRules.shouldAutoStartOnResume(
                        desired = AssistantBubbleService.isDesired(context),
                        overlayGranted = granted,
                        serviceRunning = AssistantBubbleService.isRunning(),
                    )
                ) {
                    AssistantBubbleService.start(context)
                }
                // Rückkehr: die „Freigabe offen“-Zeile ist abgearbeitet —
                // der neu gelesene Zustand (Badge/Beschreibung) übernimmt.
                permFlowLine = null
                statusTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A11y-Labels einmal hoisten (stringResource lebt nicht im semantics-Lambda).
    val activeLabel = stringResource(R.string.badge_active)
    val missingLabel = stringResource(R.string.badge_missing)

    SettingsCard(
        titleRes = R.string.settings_assistant_title,
        iconRes = SettingsIcons.assistant,
        expanded = expanded,
        onExpandedChange = { open ->
            if (open) statusTick++
            onExpandedChange(open)
        },
        modifier = modifier,
    ) {
        // ── G1: Standard-Assistent (W13 P10/P12; W23-7 U7: Status UND
        // Anfrage-Weg in EINER Karte — die Einzel-Karte ist entgangen,
        // Besitzers KEIN-DOPPEL-Grundsatz „EIN Ort je Inhalt") — Beschreibung
        // (aus der Einzel-Karte, W16-P19-Ort: in der geöffneten Karte) +
        // Status-Zeile + roleAvailable-gate'ter Einrichten-Knopf. ──
        Text(
            stringResource(R.string.settings_assistant_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = FlipsiColors.TextSecondary,
        )
        when (roleStatus) {
            RoleStatus.UNSUPPORTED -> Text(
                stringResource(R.string.assistant_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
            )
            RoleStatus.HELD -> StatusBadge(
                text = stringResource(R.string.assistant_status_held),
                state = BadgeState.OK,
            )
            RoleStatus.HELD_BY_OTHER -> Text(
                stringResource(R.string.assistant_status_other),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
            )
            RoleStatus.UNAVAILABLE -> Text(
                stringResource(R.string.assistant_role_unavailable_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
            )
        }
        // P12-GATE + Anfrage-Weg (aus der Einzel-Karte): der Knopf lebt NUR
        // bei angebotener/unbeschlagener Rolle (HELD_BY_OTHER, enabled) und
        // bei nicht offerierter Rolle (UNAVAILABLE, disabled + Begründungs-
        // Zeile oben — kein tot-wirkendes „tut nichts"). UNSUPPORTED = gar
        // kein Knopf, HELD = nichts einzurichten.
        if (roleStatus == RoleStatus.HELD_BY_OTHER || roleStatus == RoleStatus.UNAVAILABLE) {
            Button(
                onClick = {
                    if (roleIntent == null) {
                        // W28 (Lane B): Snack statt System-Toast (W19-Verband).
                        onFeedback(context.getString(R.string.assistant_dialog_failed))
                        showFallback = true
                    } else {
                        runCatching { roleLauncher.launch(roleIntent) }.onFailure {
                            // W28 (Lane B): Snack statt System-Toast — derselbe
                            // Haus-Ton wie der Launcher-Rückweg oben.
                            onFeedback(context.getString(R.string.assistant_dialog_failed))
                            showFallback = true
                        }
                    }
                },
                enabled = roleStatus == RoleStatus.HELD_BY_OTHER && roleIntent != null,
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

        // ── G2: Floating Bubble über allen Apps — W22 LANE B: der Dienst ist
        // gebaut (AssistantBubbleService), die Aktivierung lebt EIN Ort: dieser
        // Toggle. Tap ohne Freigabe öffnet ACTION_MANAGE_OVERLAY_PERMISSION
        // MIT Rückweg (ON_RESUME oben startet die Blase, sobald die Freigabe
        // steht) — kein stiller runCatching, der Fail-Weg hat eine Zeile. ──
        val bubbleTitle = texts.bubbleTitle
        if (bubbleTitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                bubbleTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextPrimary,
            )
            // Ergebnis-Zeile des Freigabe-/Start-Wegs lebt jetzt auf Karten-
            // Ebene (permFlowLine) — der Rückweg-Effekt räumt sie bei Rückkehr.
            val bubbleEligibility = AssistantBubbleRules.eligibility(
                overlayPermissionGranted = overlayOk,
                supported = true,
            )
            val bubbleDesired = remember(statusTick) { AssistantBubbleService.isDesired(context) }
            // Der sichtbare Zustand kommt aus Wunsch + Freigabe; ob der Dienst
            // wirklich läuft, prüft der Rückweg-Effekt oben (Auto-Start).
            val bubbleState = OverlayToggleRules.resolveUiState(bubbleDesired, overlayOk)
            val toggleA11y = stringResource(R.string.assistant_pkg_bubble_toggle_a11y)

            val handleBubbleToggle: (Boolean) -> Unit = { wantOn ->
                when (OverlayToggleRules.onToggleTap(wantOn, overlayOk)) {
                    OverlayToggleRules.TapDecision.START_SERVICE -> {
                        AssistantBubbleService.setDesired(context, true)
                        val started = AssistantBubbleService.start(context)
                        if (!started) {
                            permFlowLine =
                                context.getString(R.string.assistant_pkg_bubble_start_failed)
                        }
                    }
                    OverlayToggleRules.TapDecision.OPEN_PERMISSION_SETTINGS -> {
                        AssistantBubbleService.setDesired(context, true)
                        permFlowLine = null
                        openOverlayPermissionFlow(context, context as? Activity) { line ->
                            permFlowLine = line
                        }
                    }
                    OverlayToggleRules.TapDecision.STOP_SERVICE -> {
                        AssistantBubbleService.setDesired(context, false)
                        AssistantBubbleService.stop(context)
                        permFlowLine = null
                    }
                }
                statusTick++
            }

            if (bubbleEligibility == AssistantBubbleRules.Eligibility.NOT_SUPPORTED) {
                if (texts.bubbleGrantUnavailable != null) {
                    Text(
                        texts.bubbleGrantUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                }
            } else {
                when (bubbleState) {
                    OverlayToggleRules.UiState.OFF -> {
                        if (texts.bubbleReadyDesc != null) {
                            Text(
                                texts.bubbleReadyDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = FlipsiColors.TextSecondary,
                            )
                        }
                    }
                    OverlayToggleRules.UiState.ON -> {
                        if (texts.bubbleReadyDesc != null) {
                            Text(
                                texts.bubbleReadyDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = FlipsiColors.TextSecondary,
                            )
                        }
                        StatusBadge(
                            text = stringResource(R.string.badge_possible),
                            state = BadgeState.OK,
                        )
                    }
                    OverlayToggleRules.UiState.WAITING_FOR_PERMISSION -> {
                        if (texts.bubbleNeedPermDesc != null) {
                            Text(
                                texts.bubbleNeedPermDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = FlipsiColors.TextSecondary,
                            )
                        }
                        StatusBadge(
                            text = missingLabel,
                            state = BadgeState.OFF,
                        )
                    }
                }
                if (permFlowLine != null) {
                    Text(
                        permFlowLine ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.SignalAmber,
                    )
                }
                // Toggle-Zeile: EIN Aktivierungs-Ort, Touch-Ziel ≥ 48 dp; die
                // Bedeutung trägt die Zeile (contentDescription + stateDescription),
                // nie nur die Schalter-Farbe.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = toggleA11y
                            stateDescription =
                                if (bubbleState == OverlayToggleRules.UiState.ON) {
                                    activeLabel
                                } else {
                                    missingLabel
                                }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = bubbleState != OverlayToggleRules.UiState.OFF,
                        onCheckedChange = handleBubbleToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FlipsiColors.OnEmber,
                            checkedTrackColor = FlipsiColors.Ember,
                            uncheckedTrackColor = FlipsiColors.PanelElev,
                            uncheckedThumbColor = FlipsiColors.TextSecondary,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    if (bubbleState == OverlayToggleRules.UiState.WAITING_FOR_PERMISSION &&
                        texts.bubbleGrantAction != null
                    ) {
                        // Leit-Text des Freigabe-Wegs neben dem Schalter (der
                        // Tap auf AN öffnet die Systemseite — dieser Text sagt,
                        // was dort zu tun ist).
                        Text(
                            texts.bubbleGrantAction ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.SignalAmber,
                        )
                    }
                }
            }
        }

        // ── G5: Screen-Kontext — Zustimmung JE Session („Nutzerklick, kein
        // Silent“). Ohne Session-Schlüssel KEIN Schalter (keine Zustimmung
        // fürs Leere); das reale Lesen bleibt im Capability-Gate. ──
        val g5Title = texts.screenContextTitle
        if (g5Title != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                g5Title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextPrimary,
            )
            if (texts.screenContextDesc != null) {
                Text(
                    texts.screenContextDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                )
            }
            val granted = ScreenContextConsentRules.isGrantedForSession(
                screenContextConsent,
                screenContextSessionKey,
            )
            // HIG: Touch-Ziel ≥ 48 dp; Bedeutung nie nur über Farbe/Schalter —
            // die Zeile trägt contentDescription + stateDescription mit.
            val rowA11y = if (granted) texts.screenContextGrantA11y else texts.screenContextAskA11y
            val rowState = if (granted) activeLabel else missingLabel
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp)
                    .then(
                        if (rowA11y != null) {
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = rowA11y
                                stateDescription = rowState
                            }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = granted,
                    // Kein stiller Schreibpfad: Zustimmung setzt der Nutzerklick
                    // — die Verdrahtung persistediert über die Regeln (grant/
                    // revoke), die Karte selbst hält keinen Stand.
                    onCheckedChange = { want ->
                        if (want) onGrantScreenContext() else onRevokeScreenContext()
                    },
                    enabled = screenContextSessionKey != null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FlipsiColors.OnEmber,
                        checkedTrackColor = FlipsiColors.Ember,
                        uncheckedTrackColor = FlipsiColors.PanelElev,
                        uncheckedThumbColor = FlipsiColors.TextSecondary,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                val noSessionText = texts.screenContextNoSession
                if (screenContextSessionKey == null && noSessionText != null) {
                    Text(
                        noSessionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                }
            }
        }
    }

    if (showFallback) {
        // P12-Fallback (G1, aus der Einzel-Karte): „Systemeinstellungen →
        // Digitale Assistent-App“ mit DIREKT-Intent — der prominente Weg nach
        // verlorenem/abgebrochenem Ergebnis. W28 (Lane B, W19-Kommentar
        // präzisiert): die FEHLER-Meldung des verweigerten DIREKT-Sprungs
        // läuft über DIE eine W19-Zentral-Snackbar (onFeedback → Settings-
        // Scaffold) — die Karte trägt bewusst KEINEN eigenen Snackbar-Host
        // (EIN Mount im App-Verband, derselbe Haus-Verband wie jede andere
        // Feedback-Stelle). Der DIALOG bleibt der FÜHRER: er schlägt die
        // Route vor, der Sprung bleibt ein bewusster Tap — der Snack meldet
        // das gescheiterte Ergebnis, der Dialog erklärt den Ausweg.
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
                        // W28 (Lane B): verweigerte Ziel-App-Öffnung ehrlich
                        // snacken (kein System-Toast mehr) — der Dialog ist
                        // mit dem Tap bereits gegangen, die zentrale Meldung
                        // bleibt sichtbar (W19-Haus-Verband).
                        onFeedback(context.getString(R.string.assistant_dialog_failed))
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

/**
 * G2-Setup-Weg (W22 Lane B): Direkt-Start von ACTION_MANAGE_OVERLAY_PERMISSION
 * mit Paket-URI — bewusst NICHT still: der Erfolg hat eine Ergebnis-Zeile
 * („Freigabe offen — Rückkehr aktiviert die Blase“), ein Fehlstart fällt in
 * die OEM-Resolver-Kette (GLEICHE Kette wie der HUD —
 * DeviceIntentResolver.launchFeature(DeviceFeature.OVERLAY)), und löst auch
 * die nichts, bleibt die Leit-Zeile (manuelle Route erklärt).
 */
private fun openOverlayPermissionFlow(
    context: Context,
    activity: Activity?,
    onResultLine: (String) -> Unit,
) {
    val openedDirect = runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName),
        )
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
    if (openedDirect) {
        onResultLine(
            context.getString(R.string.assistant_pkg_bubble_perm_pending),
        )
    } else {
        // Fallback: OEM-Kette + Leit-Zeile (löst nichts → manuelle Route).
        val launched = DeviceIntentResolver.launchFeature(
            context,
            activity,
            DeviceFeature.OVERLAY,
        ) { hint -> onResultLine(hint) }
        if (!launched) {
            // launchFeature ruft onUnavailable bereits mit der OEM-Leit-Zeile —
            // dieser Zweig ist die letzte Sicherheitsnetz-Zeile (leerer Fallback
            // darf nie still sein).
            onResultLine(
                context.getString(R.string.assistant_pkg_bubble_perm_unreachable),
            )
        }
    }
}

/** G1-Rollen-Status (P12-Muster): ehrlich je Gerätelage. */
enum class RoleStatus { UNSUPPORTED, HELD, HELD_BY_OTHER, UNAVAILABLE }

private fun readAssistantRoleStatus(context: Context): RoleStatus {
    if (Build.VERSION.SDK_INT < 29) return RoleStatus.UNSUPPORTED
    val rm = context.getSystemService(RoleManager::class.java) ?: return RoleStatus.UNAVAILABLE
    val held = runCatching { rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    if (held) return RoleStatus.HELD
    val available = runCatching { rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }
        .getOrDefault(false)
    return if (available) RoleStatus.HELD_BY_OTHER else RoleStatus.UNAVAILABLE
}
