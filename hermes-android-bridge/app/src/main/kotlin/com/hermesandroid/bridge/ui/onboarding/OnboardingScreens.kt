package com.hermesandroid.bridge.ui.onboarding

import androidx.activity.compose.BackHandler
import com.hermesandroid.bridge.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.server.ServerProfileRules
import com.hermesandroid.bridge.service.BridgeConnectionService
import com.hermesandroid.bridge.ui.BridgeStatus
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.FlipsiType

/**
 * Welle 4 / App-Lane D — Onboarding-Assistent ERSTSTART (Sir-Spec verbatim:
 * „3 Screens (Relay-URL eintragen → Bridge-Code eintippen → Fingerprint-Karte
 * bestätigen) mit Fehlerhilfe je Schritt (401 = ‚Code falsch‘, Timeout =
 * ‚Relay nicht erreichbar‘), danach direkt im Chat“).
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
 * kein Paarung → Onboarding-Screen). Die Bestätigung der Fingerprint-Karte
 * (Schritt 3) ruft [OnboardingScreens]-onComplete → Flag → App mit
 * initialRoute=2 = direkt im Chat.
 *
 * REDUCED-PASS-FASER: der Code läuft in beiden Eingaben-Kontexten (Wizard +
 * Profil-Dialog) über [PassCodeField] — verborgen, mit Einblenden-Schalter.
 *
 * DRILL-ACKER (Emulator, `pm clear` zuerst):
 *  1. Kaltstart nach pm clear → Wizard, NICHT BridgeScreen (Schritt 1).
 *  2. Richtiger Code → CONNECTED → Sprung auf Schritt 3 automatisch
 *     (logcat: `WebSocket connected` → Phase CONNECTED).
 *  3. Falscher Code → 401×3 → Phase PAIRING_BROKEN → rote Karte „Code ist
 *     falsch (401)“ (logcat: `Kopplungscode abgelehnt (HTTP 401)`).
 *  4. Relay gestoppt → FAILED (HTTP 0) → Karte „Relay nicht erreichbar“.
 *  5. Relay gestoppt + gelockt (429) → Karte „Relay kurzzeitig gesperrt“.
 *  6. Fingerprint-Karte „Bestätigen“ → sofort Chat (Route 2), kein Home-Stopp.
 *  7. Kaltstart nach Kopplung → App direkt, KEIN Wizard (Upgrade/Migration
 *     ebenso: bestehende Kopplung → kein Wizard, Profil #1 angelegt).
 */
enum class OnboardingSignal { NONE, WAITING, AUTH_STOPPED, FAILED, CONNECTED }

enum class OnboardingHelpKind { CODE_REJECTED, RELAY_BLOCKED, UNREACHABLE }

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

    /** Automatik-Sprung Code→Fingerprint nur von der Code-Eingabe (Schritt 2). */
    fun advancesOnConnected(stepIndex: Int): Boolean = stepIndex == OnboardingStep.BRIDGE_CODE.ordinal
}

enum class OnboardingStep { RELAY_URL, BRIDGE_CODE, FINGERPRINT }

/**
 * Wizard-Haupt-Composable — 3 Steps als einfache Int-Index-Maschine
 * (rememberSaveable überlebt Rotation/Prozess-Restore).
 */
@Composable
fun OnboardingScreens(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val status by BridgeStatus.state.collectAsState()
    val e2eSessions by RelayClient.e2eSessions.collectAsState()
    var step by rememberSaveable { mutableStateOf(0) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var codeText by rememberSaveable { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf(false) }

    // Rücksprung je Wizard-Stufe (Abbrechen im Schritt 2/3 = zurück, nie raus).
    BackHandler(enabled = step > 0) { step = step - 1 }

    val signal: OnboardingSignal = when (val s = status) {
        is BridgeStatus.ConnectionState.CONNECTED -> OnboardingSignal.CONNECTED
        is BridgeStatus.ConnectionState.CONNECTING, is BridgeStatus.ConnectionState.RECONNECTING ->
            OnboardingSignal.WAITING
        is BridgeStatus.ConnectionState.PAIRING_BROKEN -> OnboardingSignal.AUTH_STOPPED
        is BridgeStatus.ConnectionState.FAILED -> OnboardingSignal.FAILED
        is BridgeStatus.ConnectionState.IDLE -> OnboardingSignal.NONE
    }

    // Automatik-Sprung: Connect steht (Schritt-2-Verband) → Fingerprint-Karte.
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

            else -> {
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
                            OnboardingInfoBox(stringResource(R.string.onb_fp_waiting))
                        }
                    }
                }
                OnboardingInfoBox(stringResource(R.string.onb_fp_hint))
                WizardButton(
                    label = stringResource(R.string.onb_confirm),
                    enabled = true,
                ) {
                    // Erststart-Flag (A6-Verband): ab hier NIE wieder Wizard,
                    // außer alle Profile werden gelöscht (Er-Start-Detect).
                    ServerProfiles.completeOnboarding()
                    onComplete()
                }
            }
        }
        if (step > OnboardingStep.RELAY_URL.ordinal) {
            TextButton(onClick = { step = step - 1 }) {
                Text(stringResource(R.string.onb_back))
            }
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
 * Wizard-Schritt 2 UND dem Multi-Server-Dialog (eine Faser, kein Duplikat).
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
