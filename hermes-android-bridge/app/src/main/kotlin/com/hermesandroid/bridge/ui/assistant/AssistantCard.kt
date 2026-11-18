package com.hermesandroid.bridge.ui.assistant

import android.app.Activity
import android.content.Context
import android.app.role.RoleManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.permissions.DeviceFeature
import com.hermesandroid.bridge.permissions.DeviceIntentResolver
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.components.StatusBadge
import com.hermesandroid.bridge.ui.settings.SettingsCard

/**
 * ASSISTENT-PAKET-KARTE (G1/G2/G5, docs/roadmap-anruf.md §7, Sir-Zusatz
 * 16.11.26) — ÜBERBLICK über die App-seitigen Assistenten-Bausteine EINER
 * Karte (app-generisch: keine Geräte-/Modell-Namen, keine Literale). Regeln
 * in [AssistantRules] (JVM-getestet, AssistantRulesTest).
 *
 * Verdrahtung (Koordinator-Welle — hier bewusst NICHT angefasst):
 *  - Einstellungen: Karte einhängen (gleiches SettingsCard-Verband wie
 *    AssistantRoleCard, deren Anfrage-Weg für die Rolle bestehen bleibt).
 *  - G2/G5 brauchen NEUE Strings — strings.xml wurde in dieser Bauwelle NICHT
 *    angefasst (Bedarf notiert); die Karte nimmt die Texte daher als Parameter
 *    ([AssistantCardTexts]). Ohne Parameter zeigen G2/G5 KEINE Zeile (kein
 *    Fake-Text), G1 rendert vollständig aus BESTEHenden Assistenten-Strings.
 *  - G2: Die Bubble selbst (Overlay-Bubble, Status-Overlay-Tap = Voice-Turn)
 *    ist eine eigene Bauwelle; die Karte zeigt ehrlich die Freigabe-Lage und
 *    bietet denselben OEM-Resolver-Weg an wie der HUD (perm overlay — der
 *    Manifest-Eintrag SYSTEM_ALERT_WINDOW ist bereits gesetzt).
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
 * @param screenContextSessionKey aktueller Session-Schlüssel (agentId#epoch,
 *   [ScreenContextConsentRules.consentKey]); null = keine Session → die Zeile
 *   ehrlich „ohne Session“ statt eines Schalters fürs Leere.
 * @param screenContextConsent gespeicherter Zustimmungs-Stand (Key oder null).
 * @param onGrantScreenContext Nutzer-Klick AN — die Verdrahtung persistsiert
 *   [ScreenContextConsentRules.grant]; die Karte schreibt NIE still.
 * @param onRevokeScreenContext Nutzer-Klick AUS ([ScreenContextConsentRules.revoke]).
 */
@Composable
fun AssistantPackageCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    texts: AssistantCardTexts = AssistantCardTexts(),
    screenContextSessionKey: String? = null,
    screenContextConsent: String? = null,
    onGrantScreenContext: () -> Unit = {},
    onRevokeScreenContext: () -> Unit = {},
) {
    val context = LocalContext.current

    // P12-Muster (AssistantRoleCard): RoleManager/Overlay-Stand NICHT als
    // Kleber — neu gelesen bei Kartenöffnung (Tick).
    var statusTick by remember { mutableIntStateOf(0) }
    val roleStatus = remember(statusTick) { readAssistantRoleStatus(context) }
    val overlayOk = remember(statusTick) {
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
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
        // ── G1: Standard-Assistent (Bestand, W13 P10/P12) — Status-Zeile ohne
        // zweite Einrichtungs-Logik; der Anfrage-Weg lebt in AssistantRoleCard. ──
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

        // ── G2: Floating Bubble über allen Apps (Bubble-Dienst = eigene
        // Bauwelle) — ehrliche Freigabe-Lage + gleicher Setup-Weg wie der HUD. ──
        val bubbleTitle = texts.bubbleTitle
        if (bubbleTitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                bubbleTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextPrimary,
            )
            when (AssistantBubbleRules.eligibility(
                overlayPermissionGranted = overlayOk,
                supported = true,
            )) {
                AssistantBubbleRules.Eligibility.NOT_SUPPORTED -> {
                    if (texts.bubbleGrantUnavailable != null) {
                        Text(
                            texts.bubbleGrantUnavailable,
                            style = MaterialTheme.typography.bodySmall,
                            color = FlipsiColors.TextSecondary,
                        )
                    }
                }
                AssistantBubbleRules.Eligibility.READY -> {
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
                AssistantBubbleRules.Eligibility.NEEDS_OVERLAY_PERMISSION -> {
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
                    val grantLabel = texts.bubbleGrantAction
                    if (grantLabel != null) {
                        BubbleGrantButton(
                            label = grantLabel,
                            a11y = texts.bubbleGrantA11y,
                            fallbackUnavailableText = texts.bubbleGrantUnavailable,
                            context = context,
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
}

/**
 * G2-Setup-Weg: OEM-Resolver-Kette des Overlays (GLEICHE Kette wie der HUD —
 * DeviceIntentResolver.launchFeature(DeviceFeature.OVERLAY)); löst nichts,
 * folgt die Leit-Zeile (kein toter Knopf).
 */
@Composable
private fun BubbleGrantButton(
    label: String,
    a11y: String?,
    fallbackUnavailableText: String?,
    context: Context,
) {
    val activity = context as? Activity
    var unavailable by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.width(4.dp))
    Button(
        onClick = {
            val ok = DeviceIntentResolver.launchFeature(
                context,
                activity,
                DeviceFeature.OVERLAY,
            ) { hint -> unavailable = hint }
            if (!ok && unavailable == null) unavailable = fallbackUnavailableText
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (a11y != null) {
                    Modifier.semantics { contentDescription = a11y }
                } else {
                    Modifier
                },
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = FlipsiColors.Ember,
            contentColor = FlipsiColors.OnEmber,
        ),
    ) {
        Text(label)
    }
    if (unavailable != null) {
        Text(
            unavailable ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = FlipsiColors.SignalAmber,
        )
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
