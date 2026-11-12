package com.hermesandroid.bridge.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.i18n.AppLanguage
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.server.ServerProfileRules
import com.hermesandroid.bridge.ui.BridgeStatus
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.FlipsiType

/**
 * Welle 4 / App-Lane D — Onboarding-Assistent ERSTSTART; Welle 13 (P3/P4/P7)
 * auf die 6-Schritt-Reihenfolge erweitert (Sir-Abnahme 11.11.2026).
 *
 * SCHRITTREIHENFOLGE (UX-Begründung je Position — Fixplan P3/P4/P7 + qa-
 * Checkliste B/C/H):
 *
 *  0 SPRACHE        — ERSTER Schritt (Checkliste B: „Sprachauswahl = erster
 *                     Schritt, vor allem anderen“). DE/EN-Tiles setzen
 *                     [AppLanguage] — der AppLanguageGate (MainActivity)
 *                     re-wrappt den GESAMTEN Baum live, ohne Activity-
 *                     Recreate: alle Screens wechseln sofort die Sprache.
 *  1 RECHTLICHES    — Accept-Gate VOR der Kopplung, weil die Kopplung die
 *                     erste Datenübermittlung ist (Checkliste C: „Keine
 *                     Datenabfragen vor dem Rechtliches-Accept“). Impressum/
 *                     Datenschutz/Nutzung ausklappbar (legal_*-Keys), Checkbox
 *                     „Ich akzeptiere“, Weiter bleibt bis zum Accept deaktiv-
 *                     iert, Zustand persistiert ([OnboardingLegalPrefs]).
 *  2 RELAY_URL      — Kopplung Teil 1 (Adresse, NSC-Pin-Validierung via
 *                     [OnboardingRules]).
 *  3 BRIDGE_CODE    — Kopplung Teil 2 (Code, Fehlerhilfe 401/429/Timeout;
 *                     Automatik-Sprung zu FINGERPRINT bei CONNECTED).
 *  4 FINGERPRINT    — E2E-Bestätigung (Knopf nur mit stehender Session,
 *                     W4-FIX-JAG2 S8).
 *  5 BERECHTIGUNGEN — optionale Feature-Rechte (je Zeile Zweck + Status +
 *                     Sprung in die Systemeinstellungen, Checkliste D) und
 *                     „Fertigstellen“ = completeOnboarding + onComplete.
 *                     Bewusst LETZT: alle Rechte sind freiwillig, die App ist
 *                     ohne jedes Recht vollständig koppelbar (Degradation statt
 *                     Zwang — niemand soll Rechte erteilen müssen, um die
 *                     Kopplung überhaupt zu Ende zu bringen).
 *
 * DRY-Pflicht eingelöst: die Wizard-Schale nutzt die BESTEHENDE Kopplungs-
 * logik — der getippte Code wird in die PairingManager-SSOT übernommen
 * (adoptActiveCode über RelayClient.connect), URL+Code landen im Profil-Store
 * (ServerProfiles, den A7 weiterverwaltet), verbindet wird über RelayClient
 * (SSOT-Lese-Pfad doConnect, FGS-Kette unverändert). Keine Zweit-Implementierung
 * von Code-Generierung/Validierung.
 *
 * Komposition-Schranke: MainActivity komponiert diesen Wizard statt
 * BridgeScreen, solange ServerProfiles.onboardingDone=false (Er-Start-Detect:
 * kein Paarung → Onboarding-Screen). Der „Fertigstellen“-Knopf (Schritt 5)
 * ruft onComplete → Flag → App mit initialRoute=2 = direkt im Chat.
 *
 * REDUCED-PASS-FASER: der Code läuft in beiden Eingaben-Kontexten (Wizard +
 * Profil-Dialog) über [PassCodeField] — verborgen, mit Einblenden-Schalter.
 *
 * DRILL-ACKER (Emulator, `pm clear` zuerst):
 *  1. Kaltstart nach pm clear → Wizard bei Schritt 0 SPRACHE (kein anderer
 *     Screen davor).
 *  2. EN-Tile → kompletter Wizard sofort englisch (Live-Rewrap, kein
 *     Flash/Recreate); DE-Tile → zurück.
 *  3. Rechtliches ohne Accept → „Weiter“ bleibt deaktiviert; Accept-Checkbox
 *     → Weiter aktiv; App-Neustart vor Accept zeigt Checkbox wieder leer,
 *     nach Accept dauerhaft gesetzt (Prefs legal_accepted_v1).
 *  4. Richtiger Code → CONNECTED → Sprung auf FINGERPRINT automatisch
 *     (logcat: `WebSocket connected` → Phase CONNECTED).
 *  5. Falscher Code → 401×3 → Phase PAIRING_BROKEN → rote Karte „Code ist
 *     falsch (401)“ (logcat: `Kopplungscode abgelehnt (HTTP 401)`).
 *  6. Fingerprint „Bestätigen“ → Schritt BERECHTIGUNGEN (NICHT direkt Chat).
 *  7. Berechtigungs-Zeilen zeigen Erteilt/Nicht erteilt korrekt; „System-
 *     einstellungen“-Knopf öffnet die App-Details; zurück im Wizard →
 *     Status neu bewertet (configVersion-Bump aus onResume).
 *  8. „Fertigstellen“ → sofort Chat (Route 2), kein Home-Stopp.
 *  9. Kaltstart nach Kopplung → App direkt, KEIN Wizard (Upgrade/Migration
 *     ebenso: bestehende Kopplung → kein Wizard, Profil #1 angelegt).
 */
enum class OnboardingSignal { NONE, WAITING, AUTH_STOPPED, FAILED, CONNECTED }

enum class OnboardingHelpKind { CODE_REJECTED, RELAY_BLOCKED, UNREACHABLE }

/**
 * Welle 13 (P7) — Rechtliches-Accept-Merker. Persistiert im gemeinsamen
 * Prefs-Haus („hermes_bridge_prefs“, dasselbe wie AppLanguage/Termux-Revival)
 * unter dem Key „legal_accepted_v1“ — bewusst versioniert: Ändern sich die
 * Dokumente wesentlich, setzt die nächste Welle einen v2-Key und das Gate
 * fragt erneut. Kein eigener Thread/Apparat: apply() reicht, der Wert wird
 * nur beim Wizard-Betreten gelesen.
 */
object OnboardingLegalPrefs {

    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY = "legal_accepted_v1"

    fun isAccepted(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }.getOrDefault(false)

    fun accept(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, true).apply()
        }
    }
}

/** Reine Regeln (JVM-testbar): Fehler-Taxonomie + Feldeingabe-Rechte. */
object OnboardingRules {

    /** 401/403 → Code falsch; 429 → Relay-Sperre; sonst → Timeout/Netz. */
    fun helpFor(signal: OnboardingSignal, lastHttpCode: Int): OnboardingHelpKind? =
        when (signal) {
            OnboardingSignal.NONE, OnboardingSignal.WAITING, OnboardingSignal.CONNECTED -> null
            OnboardingSignal.AUTH_STOPPED -> OnboardingHelpKind.CODE_REJECTED
            OnboardingSignal.FAILED -> when {
                lastHttpCode == 401 || lastHttpCode == 403 -> OnboardingHelpKind.CODE_REJECTED
                lastHttpCode == 429 -> OnboardingHelpKind.RELAY_BLOCKED
                else -> OnboardingHelpKind.UNREACHABLE
            }
        }

    /** URL-Feld: nichtleer + Host erkennbar + Transport-Verband (S1). */
    fun relayUrlProblem(input: String, debugBuild: Boolean): Boolean {
        val t = input.trim().trimEnd('/')
        if (t.isBlank() || ServerProfileRules.hostOf(t).isBlank()) return true
        // W4-FIX-JAG2 (S1): dashboard.lan als Relay-URL umging den NSC-Pin
        // (Klartext-WS mit dem Bearer-Kopplungscode). Die Validierung spiegelt
        // jetzt die Plattform-Wahrheit: https für Nicht-Ausnahme-Hosts,
        // Klartext nur dashboard.lan im Dev-Kontext ([debugBuild]).
        return !ServerProfileRules.relayUrlValidForConnect(t, debugBuild)
    }

    /** Code-Feld: nichtleer (Form wird vom Relay bewertet, nicht hier geraten). */
    fun codeProblem(input: String): Boolean = input.trim().isBlank()

    /** Automatik-Sprung Code→Fingerprint nur von der Code-Eingabe (Schritt 3). */
    fun advancesOnConnected(stepIndex: Int): Boolean = stepIndex == OnboardingStep.BRIDGE_CODE.ordinal
}

/**
 * Schritt-Maschine des Assistenten (Welle 13: 6 Schritte, Reihenfolge siehe
 * File-KDoc oben — die Ordinals sind bewusst Vertrag: OnboardingRulesTest
 * pinnt sie fest).
 */
enum class OnboardingStep { SPRACHE, RECHTLICHES, RELAY_URL, BRIDGE_CODE, FINGERPRINT, BERECHTIGUNGEN }

/**
 * Berechtigungs-Katalog des Onboarding-Schritts 5 (Welle 13, P4): je Recht
 * ein Zweck-Text (Checkliste D). Nur LAUFZEIT-Rechte — Sonderrechte ohne
 * Dialog (Overlay, MANAGE_EXTERNAL_STORAGE, Akku) bleiben bewusst draußen:
 * Für die gibt es keinen System-Dialog am Wizard-Pfad, sie leben in den
 * Einstellungen (P4-Settings-Verband derselben Welle).
 */
private data class OnbPermissionInfo(
    val permission: String,
    val titleRes: Int,
    val purposeRes: Int,
)

private val ONB_PERMISSIONS = listOf(
    OnbPermissionInfo(
        Manifest.permission.RECORD_AUDIO,
        R.string.onb_perm_title_mic,
        R.string.onb_perm_purpose_mic,
    ),
    OnbPermissionInfo(
        Manifest.permission.POST_NOTIFICATIONS,
        R.string.onb_perm_title_notifications,
        R.string.onb_perm_purpose_notifications,
    ),
    OnbPermissionInfo(
        Manifest.permission.CAMERA,
        R.string.onb_perm_title_camera,
        R.string.onb_perm_purpose_camera,
    ),
    OnbPermissionInfo(
        Manifest.permission.ACCESS_FINE_LOCATION,
        R.string.onb_perm_title_location,
        R.string.onb_perm_purpose_location,
    ),
    OnbPermissionInfo(
        Manifest.permission.READ_CONTACTS,
        R.string.onb_perm_title_contacts,
        R.string.onb_perm_purpose_contacts,
    ),
)

/**
 * Wizard-Haupt-Composable — 6 Steps als einfache Int-Index-Maschine
 * (rememberSaveable überlebt Rotation/Prozess-Restore).
 */
@Composable
fun OnboardingScreens(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val status by BridgeStatus.state.collectAsState()
    val e2eSessions by RelayClient.e2eSessions.collectAsState()
    // W13-P2: Prekey-Fehlerzeile des Relays (null = keiner) — im Fingerprint-
    // Schritt STATT der stillen Warte-Box, wenn nach allen Retries kein
    // Agent-Prekey geliefert wird (Jäger-Befund 12.11.26).
    val prekeyError by RelayClient.prekeyError.collectAsState()
    var step by rememberSaveable { mutableStateOf(OnboardingStep.SPRACHE.ordinal) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var codeText by rememberSaveable { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf(false) }
    // Welle 13 (P7): Accept-Zustand — initial aus den Prefs (wizard nach
    // Absturz/Rotation kommt nicht mit gefälschtem Leerzustand durch).
    var legalAccepted by rememberSaveable {
        mutableStateOf(OnboardingLegalPrefs.isAccepted(context))
    }

    // Rücksprung je Wizard-Stufe (Abbrechen ab Schritt 1 = zurück, nie raus).
    BackHandler(enabled = step > OnboardingStep.SPRACHE.ordinal) { step = step - 1 }

    val signal: OnboardingSignal = when (val s = status) {
        is BridgeStatus.ConnectionState.CONNECTED -> OnboardingSignal.CONNECTED
        is BridgeStatus.ConnectionState.CONNECTING, is BridgeStatus.ConnectionState.RECONNECTING ->
            OnboardingSignal.WAITING
        is BridgeStatus.ConnectionState.PAIRING_BROKEN -> OnboardingSignal.AUTH_STOPPED
        is BridgeStatus.ConnectionState.FAILED -> OnboardingSignal.FAILED
        is BridgeStatus.ConnectionState.IDLE -> OnboardingSignal.NONE
    }

    // Automatik-Sprung: Connect steht (Schritt-3-Verband) → Fingerprint-Karte.
    LaunchedEffect(signal, step) {
        if (signal == OnboardingSignal.CONNECTED && OnboardingRules.advancesOnConnected(step)) {
            step = OnboardingStep.FINGERPRINT.ordinal
        }
    }

    fun connectCodeEntry() {
        if (OnboardingRules.codeProblem(codeText)) {
            codeError = true
            return
        }
        codeError = false
        // Wizard = neue Schale BESTEHENDER Logik: Profil upsert (URL+Code je
        // Vault) + bewusstes connect — SSOT folgt dem Profil automatisch.
        val p = ServerProfiles.upsertFromOnboarding(urlText, codeText.trim())
        ServerProfiles.connectViaProfile(context, p.id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FlipsiColors.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.onb_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        when (step) {
            // ── Schritt 0: SPRACHE (Welle 13, P3 — vor allem anderen) ────────
            OnboardingStep.SPRACHE.ordinal -> {
                Text(
                    stringResource(R.string.onb_lang_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = FlipsiColors.SignalAmber,
                )
                Text(
                    stringResource(R.string.onb_lang_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                // Live-Wert aus dem i18n-SSOT: AppLanguage.current ist eine
                // Compose-State — set() re-wrappt den Baum sofort (kein
                // Recreate), die Zeilen darunter lesen NEU, inkl. dieser.
                val lang = AppLanguage.current.value
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OnbLanguageTile(
                        label = stringResource(R.string.onb_lang_de),
                        selected = lang == AppLanguage.DE,
                        modifier = Modifier.weight(1f),
                    ) {
                        AppLanguage.set(context, AppLanguage.DE)
                    }
                    OnbLanguageTile(
                        label = stringResource(R.string.onb_lang_en),
                        selected = lang == AppLanguage.EN,
                        modifier = Modifier.weight(1f),
                    ) {
                        AppLanguage.set(context, AppLanguage.EN)
                    }
                }
                TextButton(
                    onClick = { AppLanguage.set(context, AppLanguage.SYSTEM) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.onb_lang_system),
                        color = FlipsiColors.TextSecondary,
                    )
                }
                WizardButton(
                    label = stringResource(R.string.onb_next),
                    enabled = true,
                ) {
                    step = OnboardingStep.RECHTLICHES.ordinal
                }
            }

            // ── Schritt 1: RECHTLICHES (Welle 13, P7 — Accept-Gate) ──────────
            OnboardingStep.RECHTLICHES.ordinal -> {
                Text(
                    stringResource(R.string.onb_step_header_legal),
                    style = MaterialTheme.typography.labelLarge,
                    color = FlipsiColors.SignalAmber,
                )
                Text(
                    stringResource(R.string.onb_step_text_legal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                OnbLegalExpander(
                    title = stringResource(R.string.legal_imprint_title),
                    text = stringResource(R.string.legal_imprint_body),
                )
                OnbLegalExpander(
                    title = stringResource(R.string.legal_privacy_title),
                    text = stringResource(R.string.legal_privacy_body),
                )
                OnbLegalExpander(
                    title = stringResource(R.string.legal_terms_title),
                    text = stringResource(R.string.legal_terms_body),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { legalAccepted = !legalAccepted },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = legalAccepted,
                        onCheckedChange = { checked ->
                            legalAccepted = checked
                            // Sofort persistieren (P7: „Zustand persistiert“) —
                            // auch wenn der Nutzer danach überspringt: das Gate
                            // bleibt eingelöst, ein Re-Visit startet akzeptiert.
                            if (checked) OnboardingLegalPrefs.accept(context)
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = FlipsiColors.Ember,
                            checkmarkColor = FlipsiColors.OnEmber,
                        ),
                    )
                    Text(
                        stringResource(R.string.legal_accept),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                WizardButton(
                    label = stringResource(R.string.onb_next),
                    // P7-Gate: Weiter bleibt deaktiviert, bis akzeptiert.
                    enabled = legalAccepted,
                ) {
                    step = OnboardingStep.RELAY_URL.ordinal
                }
            }

            OnboardingStep.RELAY_URL.ordinal -> {
                Text(
                    stringResource(R.string.onb_step_header_url),
                    style = MaterialTheme.typography.labelLarge,
                    color = FlipsiColors.SignalAmber,
                )
                Text(
                    stringResource(R.string.onb_step_text_url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        urlError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = urlError,
                    label = { Text(stringResource(R.string.onb_url_label)) },
                    placeholder = { Text(stringResource(R.string.onb_url_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (urlError) {
                    Text(
                        stringResource(R.string.onb_url_blank_error),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.SignalRedText,
                    )
                }
                WizardButton(
                    label = stringResource(R.string.onb_next),
                    enabled = true,
                ) {
                    if (OnboardingRules.relayUrlProblem(urlText, BuildConfig.DEBUG)) {
                        urlError = true
                    } else {
                        urlText = urlText.trim().trimEnd('/')
                        urlError = false
                        step = OnboardingStep.BRIDGE_CODE.ordinal
                    }
                }
            }

            OnboardingStep.BRIDGE_CODE.ordinal -> {
                Text(
                    stringResource(R.string.onb_step_header_code),
                    style = MaterialTheme.typography.labelLarge,
                    color = FlipsiColors.SignalAmber,
                )
                Text(
                    stringResource(R.string.onb_step_text_code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                PassCodeField(
                    value = codeText,
                    onValueChange = {
                        codeText = it
                        codeError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (codeError) {
                    Text(
                        stringResource(R.string.onb_code_blank_error),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.SignalRedText,
                    )
                }
                val waiting = signal == OnboardingSignal.WAITING
                val connected = signal == OnboardingSignal.CONNECTED
                val helpKind = OnboardingRules.helpFor(signal, RelayClient.lastFailureHttpCode)
                WizardButton(
                    label = stringResource(R.string.onb_connect),
                    enabled = !waiting && !connected,
                ) {
                    connectCodeEntry()
                }
                if (waiting || connected) {
                    OnboardingInfoBox(stringResource(R.string.onb_connecting))
                }
                // Fehlerhilfe je Schritt (401 / Timeout / Netz / Sperre).
                when (helpKind) {
                    OnboardingHelpKind.CODE_REJECTED -> OnboardingHelpCard(
                        title = stringResource(R.string.onb_help_code_title),
                        text = stringResource(R.string.onb_help_code_text),
                    )
                    OnboardingHelpKind.UNREACHABLE -> OnboardingHelpCard(
                        title = stringResource(R.string.onb_help_unreachable_title),
                        text = stringResource(R.string.onb_help_unreachable_text),
                    )
                    OnboardingHelpKind.RELAY_BLOCKED -> OnboardingHelpCard(
                        title = stringResource(R.string.onb_help_blocked_title),
                        text = stringResource(R.string.onb_help_blocked_text),
                    )
                    null -> {}
                }
            }

            OnboardingStep.FINGERPRINT.ordinal -> {
                val session = e2eSessions.values.firstOrNull()
                Text(
                    stringResource(R.string.onb_step_header_fp),
                    style = MaterialTheme.typography.labelLarge,
                    color = FlipsiColors.SignalAmber,
                )
                Text(
                    stringResource(R.string.onb_step_text_fp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        session?.let { s ->
                            Text(
                                stringResource(R.string.onb_fp_phone_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = FlipsiColors.TextSecondary,
                            )
                            Text(s.fingerprintDisplay, style = FlipsiType.Code)
                            Text(
                                stringResource(R.string.onb_fp_agent_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = FlipsiColors.TextSecondary,
                            )
                            Text(s.agentFingerprintDisplay, style = FlipsiType.Code)
                        }
                        if (session == null) {
                            // W13-P2 (Jäger-Befund): leeres spk_pub vom Relay NIE
                            // mehr still in der Warte-Box versinken — nach
                            // aufgebrauchtem Retry-Budget steht hier eine klare
                            // Fehlerzeile mit Handlungshinweis.
                            val prekeyText = prekeyError
                            if (prekeyText != null) {
                                OnboardingHelpCard(
                                    title = stringResource(R.string.onb_help_prekey_title),
                                    text = prekeyText,
                                )
                            } else {
                                OnboardingInfoBox(stringResource(R.string.onb_fp_waiting))
                            }
                        }
                    }
                }
                OnboardingInfoBox(stringResource(R.string.onb_fp_hint))
                WizardButton(
                    label = stringResource(R.string.onb_confirm),
                    // W4-FIX-JAG2 (S8): Bestätigen nur MIT bestehender E2E-
                    // Session — davor war der Knopf immer enabled und der
                    // Nutzer konnte den Abgleich ohne gelieferten Fingerprint
                    // „bestätigen“. Gate: session != null = beide
                    // Fingerprints liegen vor, der Nutzer kann abgleichen; ohne
                    // Session bleibt der Knopf deaktiviert (Warten-Box leitet).
                    // Rotation-bewusst KEIN extra saveable — session kommt ohnehin
                    // aus dem RelayClient-StateFlow.
                    // Welle 13: der Knopf schließt NICHT mehr den Erststart ab,
                    // sondern führt zum Schritt BERECHTIGUNGEN — komplettiert
                    // wird erst „Fertigstellen“ am letzten Schritt.
                    enabled = session != null,
                ) {
                    step = OnboardingStep.BERECHTIGUNGEN.ordinal
                }
            }

            // ── Schritt 5: BERECHTIGUNGEN (Welle 13, P4) + Fertigstellen ─────
            OnboardingStep.BERECHTIGUNGEN.ordinal -> {
                Text(
                    stringResource(R.string.onb_step_header_perm),
                    style = MaterialTheme.typography.labelLarge,
                    color = FlipsiColors.SignalAmber,
                )
                Text(
                    stringResource(R.string.onb_step_text_perm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextSecondary,
                )
                ONB_PERMISSIONS.forEach { info ->
                    OnbPermissionRow(info)
                }
                WizardButton(
                    label = stringResource(R.string.onb_finish),
                    enabled = true,
                ) {
                    // Erststart-Flag (A6-Verband): ab hier NIE wieder Wizard,
                    // außer alle Profile werden gelöscht (Er-Start-Detect).
                    // Welle 13: Abschluss bewusst hier (nicht mehr am Finger-
                    // print) — der Nutzer hat die Rechte-Zeilen gesehen, bevor
                    // die App zum Chat entlässt.
                    ServerProfiles.completeOnboarding()
                    onComplete()
                }
            }
        }
        // Welle 12b (Sir): „Überspringen auf Wunsch" — der Assistent ist in
        // JEDEM Schritt abortierbar; die Entscheidung wird dauerhaft gemerkt
        // (kein Zwang-Neustart beim nächsten Kaltstart) und lässt sich über
        // die Server-Liste (Resume-Karte) rückgängig machen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > OnboardingStep.SPRACHE.ordinal) {
                TextButton(onClick = { step = step - 1 }) {
                    Text(stringResource(R.string.onb_back))
                }
            } else {
                Spacer(Modifier.size(1.dp))
            }
            TextButton(onClick = {
                ServerProfiles.skipOnboarding()
                onComplete()
            }) {
                Text(
                    stringResource(R.string.onb_skip),
                    color = FlipsiColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Welle 13 (P3) — DE/EN-Tile des Sprachschritts. Zustand nie nur über Farbe:
 * selektiert = Ember-Rand + EmberDim-Fläche (der Titel selbst bleibt
 * sprachneutral „Deutsch“/„English“ — kein Nutzer soll die eigene Sprache
 * übersetzt zurücklesen).
 */
@Composable
private fun OnbLanguageTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) FlipsiColors.EmberDim else FlipsiColors.Panel,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .height(72.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, FlipsiColors.Ember, MaterialTheme.shapes.large)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) FlipsiColors.TextPrimary else FlipsiColors.TextSecondary,
            )
        }
    }
}

/**
 * Welle 13 (P7) — ausklappbare Rechtstext-Karte. Auf/Zu über die ganze Kopf-
 * zeile (48 dp+ Touchziel inkl. Text-Padding), Zustand je Karte als
 * rememberSaveable (Rotation/Prozess-Restore), Chevron ▸/▾ als Zustands-
 * Anzeige ZUSAMMEN mit der Flächenänderung (nicht nur Farbe — A11y).
 */
@Composable
private fun OnbLegalExpander(title: String, text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = FlipsiColors.TextPrimary,
                )
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.titleSmall,
                    color = FlipsiColors.TextSecondary,
                )
            }
            if (expanded) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                )
            }
        }
    }
}

/**
 * Welle 13 (P4) — Berechtigungs-Zeile des Einrichtungsschritts: Zweck-Text +
 * Ist-Status (Erteilt/Nicht erteilt) + Knopf in die Systemeinstellungen.
 *
 * Status-Neubewertung OHNE Lifecycle-Import: MainActivity.onResume ruft
 * BridgeStatus.refreshConfig() → configVersion-Bump → diese Zeile re-
 * komponiert und liest checkSelfPermission NEU. Rückweg aus den System-
 * einstellungen zeigt also sofort den frischen Zustand.
 */
@Composable
private fun OnbPermissionRow(info: OnbPermissionInfo) {
    val context = LocalContext.current
    // Der wrapped Context ist KEIN Activity-Context — für startActivity hängt
    // das Gate die ORIGINAL Activity durch (AppLanguage.LocalAppActivity).
    val activity = AppLanguage.LocalAppActivity.current
    val configVersion by BridgeStatus.configVersion.collectAsState()
    val granted = remember(configVersion, info.permission) {
        ContextCompat.checkSelfPermission(context, info.permission) ==
            PackageManager.PERMISSION_GRANTED
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(info.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = FlipsiColors.TextPrimary,
                )
                Text(
                    stringResource(
                        if (granted) {
                            R.string.onb_perm_status_granted
                        } else {
                            R.string.onb_perm_status_denied
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    // Zustand nie nur über Farbe: Text Erteilt/Nicht erteilt
                    // trägt die Aussage, Grün/Bernstein führt das Auge.
                    color = if (granted) {
                        FlipsiColors.SignalGreen
                    } else {
                        FlipsiColors.SignalAmber
                    },
                )
            }
            Text(
                stringResource(info.purposeRes),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            TextButton(onClick = { openAppDetailsSettings(activity, context) }) {
                Text(stringResource(R.string.onb_perm_settings))
            }
        }
    }
}

/**
 * Sprung in die App-Details der Systemeinstellungen (je Berechtigungs-Zeile,
 * Checkliste D). Activity-Context wenn vorhanden (kein NEW_TASK-Zwang),
 * sonst Fallback mit Flag — der Aufruf darf nie still scheitern.
 */
private fun openAppDetailsSettings(activity: Activity?, context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching {
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun WizardButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FlipsiColors.Ember,
            contentColor = FlipsiColors.OnEmber,
            disabledContainerColor = FlipsiColors.PanelElev,
            disabledContentColor = FlipsiColors.TextSecondary,
        ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OnboardingInfoBox(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = FlipsiColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(FlipsiColors.PanelElev, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
private fun OnboardingHelpCard(title: String, text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = FlipsiColors.SignalRed.copy(alpha = 0.14f),
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = FlipsiColors.SignalRedText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * REDUCED-PASS-FASER: Code-Feld verborgen (PasswordVisualTransformation),
 * Einblenden nur per bewusstem Tipp auf den Auge-Schalter. Genutzt vom
 * Wizard-Schritt 3 UND dem Multi-Server-Dialog (eine Faser, kein Duplikat).
 * Kein Icon-Set-Extra: der Schalter ist ein Text-Knopf im trailingIcon-Slot
 * (material-icons-extended bleibt bewusst aus dem Haus).
 */
@Composable
fun PassCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.onb_code_label),
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(
                onClick = { revealed = !revealed },
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    stringResource(if (revealed) R.string.onb_hide else R.string.onb_reveal),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    )
}
