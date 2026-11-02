package com.hermesandroid.bridge.security

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors
import kotlinx.coroutines.delay

/**
 * PH-2X X-1 — LOCK-GATE VOR JEDEM CONTENT (Chat darf nie hinter dem Gate
 * rendern): Der Content-Slot wird NUR im entsperrten Zweig aufgerufen —
 * solange das Gate sichtbar ist, wird `content()` NICHT komponiert (kein
 * Preview-Leak: Home/Chat-Zustände sind erst nach erfolgreichem Unlock Teil
 * des Kompositions-Baums). MainActivity umschließt BridgeScreen damit.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val manager = remember { AppLockManager.get() }
    // PH-3 F5 (Jäger): Gate SYNCHRON evaluieren — ein LaunchedEffect läuft
    // erst NACH dem ersten Compose-Frame, content() hätte also einmal mit
    // dem Startzustand (visible=false) komponiert (Preview-Leak). evaluateGate
    // ist idempotent + billig (SharedPreferences-Cache, kein Zustands-
    // Konsum) — jede Frame-Entscheidung läuft auf frischem Stand, der
    // Claim 'Content wird nie hinter dem Gate komponiert' gilt wirklich.
    manager.evaluateGate()
    val visible by manager.gateVisible.collectAsState()
    if (visible && manager.isPinSet()) {
        AppLockScreen(manager)
    } else {
        content()
    }
}

/** Voll-Screen-PIN-Pad mit Fehler-/Sperre-Zähler (X-1: 5 Fehlversuche → Sperr-Frist). */
@Composable
private fun AppLockScreen(manager: AppLockManager) {
    val context = LocalContext.current
    var entered by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lockSeconds by remember { mutableStateOf(0L) }

    // Fehlversuchs-Sperre live herunterzählen, bis die Frist abgelaufen ist.
    // DRILL-FUND P0 (vc36): die alte Form brach bei s <= 0 SOFORT ab — beim
    // Mount des Gates (vor jedem Lockout) war die Restzeit 0, der Loop starb,
    // und der nach 5 Fehlversuchen von submit() gesetzte lockSeconds=30 tickte
    // NIE herunter: 'Gesperrt — noch 30 s' + deaktivierte Ziffern blieben
    // EWIG stehen (Beweis: lockout_until in den Prefs abgelaufen, UI frozen,
    // Unlock unmöglich, nur App-Restart half). Fix: endlos pollen — 1 s Takt
    // bei aktiver Sperre, 500 ms Idle sonst (Gate-Screen komponiert NUR im
    // gesperrten Zustand, Kosten ~0).
    LaunchedEffect(Unit) {
        while (true) {
            val s = manager.lockoutRemainingSeconds()
            if (s != lockSeconds) lockSeconds = s
            delay(if (s > 0L) 1_000L else 500L)
        }
    }

    fun submit(pin: String) {
        when (val r = manager.verifyPin(pin)) {
            is PinVerifyResult.Success -> {
                entered = ""
                errorText = null
            }
            is PinVerifyResult.LockedOut -> {
                entered = ""
                lockSeconds = r.remainingSeconds
                errorText = "Zu viele Fehlversuche — für ${r.remainingSeconds} s gesperrt"
            }
            is PinVerifyResult.Failure -> {
                entered = ""
                errorText = if (r.remainingAttempts > 1) {
                    "Falsche PIN — noch ${r.remainingAttempts} Versuche"
                } else {
                    "Falsche PIN — der nächste Fehlversuch sperrt kurzzeitig"
                }
            }
            PinVerifyResult.NoPinSet -> Unit
        }
    }

    fun typeDigit(digit: String) {
        if (entered.length < AppLockManager.PIN_MAX_LENGTH) entered += digit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FlipsiColors.Void)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "FlipsiBridge ist gesperrt",
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "PIN eingeben, um Chat und Einstellungen zu öffnen",
            style = MaterialTheme.typography.bodyMedium,
            color = FlipsiColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))

        // Eingabe-Anzeige: je PIN-Stelle ein Punkt (gefüllt = Ziffer getippt).
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(AppLockManager.PIN_MAX_LENGTH) { index ->
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            if (index < entered.length) FlipsiColors.Ember
                            else FlipsiColors.PanelElev,
                            CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (lockSeconds > 0) {
            Text("Gesperrt — noch $lockSeconds s", color = FlipsiColors.TextSecondary)
        }
        errorText?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = FlipsiColors.SignalRedText, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(24.dp))

        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        ).forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowDigits.forEach { digit ->
                    PinPadButton(digit, enabled = lockSeconds <= 0) { typeDigit(digit) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⌫/OK bewusst als Text-Glyphen — die Compose-Icons-Core hat kein
            // Backspace, und eine Icon-Erweiterungs-Dependency kommt nicht rein.
            PinPadButton("⌫", enabled = entered.isNotEmpty() && lockSeconds <= 0) {
                entered = entered.dropLast(1)
            }
            PinPadButton("0", enabled = lockSeconds <= 0) { typeDigit("0") }
            OutlinedButton(
                onClick = { submit(entered) },
                enabled = entered.length in AppLockManager.PIN_MIN_LENGTH..AppLockManager.PIN_MAX_LENGTH,
                // SIRS-06 (Volltest-Design-QA): OK exakt so breit wie die Ziffer-
                // Tasten (72 dp-Quadrat) — der inhaltsbreite OK-Button machte die
                // letzte Pad-Zeile asymmetrisch und die „0“ wanderte 7 px neben
                // das Spalten-Raster (309/540/771). Quadratisch = Reihe symmetrisch
                // = „0“ wieder exakt zentriert.
                modifier = Modifier.size(72.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
            ) { Text("OK") }
        }

        // Geräte-Sperre (Plattform-Builtin statt androidx.biometric — siehe
        // AppLockManager-Doku): nur sichtbar wenn aktiviert UND eine
        // Geräte-Sperre eingerichtet — kein Crash ohne Biometrie-Hardware.
        if (manager.isBiometricUnlockEnabled() && manager.isDeviceCredentialAvailable(context)) {
            val credentialLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result -> if (result.resultCode == Activity.RESULT_OK) manager.unlock() }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    manager.deviceCredentialIntent(context)?.let { credentialLauncher.launch(it) }
                },
                modifier = Modifier.height(48.dp),
            ) { Text("Mit Geräte-Sperre entsperren") }
        }
    }
}

@Composable
private fun PinPadButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FlipsiColors.PanelElev,
            contentColor = FlipsiColors.TextPrimary,
            disabledContainerColor = FlipsiColors.Panel,
            disabledContentColor = FlipsiColors.TextSecondary,
        ),
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

/**
 * Settings-Gruppe 4 „App-Sperre“ (PH-2X X-1) — INNERHALT der AccordionCard;
 * BridgeScreen hängt sie append-only an (Gruppen 1–3 unberührt).
 * PIN setzen/ändern/löschen (4–6 Ziffern, 2× bestätigen, Ändern/Löschen nur
 * mit aktueller PIN), Biometrie-Toggle, Auto-Sperr-Optionen (30 s/3 min/Nie).
 */
@Composable
fun AppLockSettingsCard() {
    val context = LocalContext.current
    val manager = remember { AppLockManager.get() }
    var pinSet by remember { mutableStateOf(manager.isPinSet()) }
    var biometric by remember { mutableStateOf(manager.isBiometricUnlockEnabled()) }
    var autoLock by remember { mutableStateOf(manager.autoLockOption()) }
    var dialog by remember { mutableStateOf<SecurityDialog?>(null) }
    var infoText by remember { mutableStateOf<String?>(null) }

    val deviceCredentialOk = remember { manager.isDeviceCredentialAvailable(context) }

    fun refresh() {
        pinSet = manager.isPinSet()
        biometric = manager.isBiometricUnlockEnabled()
        autoLock = manager.autoLockOption()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("PIN-Sperre", fontWeight = FontWeight.SemiBold)
            Text(
                if (pinSet)
                    "Aktiv — Chat-Inhalte sind erst nach PIN-/Geräte-Sperre-Eingabe sichtbar."
                else
                    "Aus — die App öffnet ohne Sperre. Mit PIN schützt du Chat und Einstellungen.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!pinSet) {
                    Button(
                        onClick = { dialog = SecurityDialog.SetPin },
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlipsiColors.Ember,
                            contentColor = FlipsiColors.OnEmber,
                        ),
                    ) { Text("PIN setzen") }
                } else {
                    OutlinedButton(
                        onClick = { dialog = SecurityDialog.ChangePin },
                        modifier = Modifier.height(48.dp),
                    ) { Text("PIN ändern") }
                    TextButton(onClick = { dialog = SecurityDialog.RemovePin }) {
                        Text("PIN löschen")
                    }
                }
            }

            infoText?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong)
            Spacer(Modifier.height(14.dp))

            // ── Biometrie-/Geräte-Sperre-Toggle ────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mit Geräte-Sperre öffnen", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            !pinSet -> "Zuerst eine PIN setzen."
                            !deviceCredentialOk ->
                                "Keine Geräte-Sperre eingerichtet — erst eine Bildschirmsperre im System anlegen."
                            else -> "Fingerabdruck/Gesicht/Geräte-PIN statt der App-PIN."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = biometric,
                    onCheckedChange = { on ->
                        manager.setBiometricUnlockEnabled(on)
                        biometric = on
                    },
                    enabled = pinSet && deviceCredentialOk,
                )
            }

            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong)
            Spacer(Modifier.height(14.dp))

            // ── Auto-Sperre nach Hintergrund (30 s / 3 min / Nie) ───────────
            Text("Automatisch sperren", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Sperrt die App, wenn sie so lange im Hintergrund war.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoLockOption.entries.forEach { option ->
                    FilterChip(
                        selected = autoLock == option,
                        onClick = {
                            manager.setAutoLockOption(option)
                            autoLock = option
                        },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "PIN und Salt bleiben lokal auf dem Gerät (Android-Keystore-versiegelt) — kein Netz-Sync.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
        }
    }

    // ── Dialoge ────────────────────────────────────────────────────────────
    dialog?.let { current ->
        PinEntryDialog(
            dialog = current,
            onDismiss = {
                dialog = null
                refresh()
            },
            onResult = { message ->
                dialog = null
                refresh()
                infoText = message
            },
        )
    }
}

/** Dialog-Arten der Gruppe 4. */
private sealed interface SecurityDialog {
    data object SetPin : SecurityDialog
    data object ChangePin : SecurityDialog
    data object RemovePin : SecurityDialog
}

/**
 * PIN-Dialog, 4–6 Ziffern, 2× bestätigen. Schritte:
 * SetPin:  0 = neue PIN → 3 = wiederholen
 * Change:  1 = aktuelle PIN (verify) → 2 = neue PIN → 3 = wiederholen
 * Remove:  1 = aktuelle PIN (verify → clearPin)
 */
@Composable
private fun PinEntryDialog(
    dialog: SecurityDialog,
    onDismiss: () -> Unit,
    onResult: (String?) -> Unit,
) {
    val manager = remember { AppLockManager.get() }
    var step by remember { mutableStateOf(if (dialog == SecurityDialog.SetPin) 0 else 1) }
    var first by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun done(text: String?) {
        message = null
        onResult(text)
    }

    fun commitSetPin() {
        when (val r = manager.setPin(first)) {
            PinChangeResult.Ok ->
                done(if (dialog == SecurityDialog.ChangePin) "PIN geändert." else "PIN gesetzt — die App sperrt ab jetzt.")
            PinChangeResult.InvalidFormat -> message = "4–6 Ziffern nötig."
            PinChangeResult.KeystoreUnavailable ->
                message = "Keystore nicht verfügbar — PIN konnte nicht gespeichert werden."
            else -> message = "Fehler (${r.javaClass.simpleName}) — erneut versuchen."
        }
    }

    val fieldLabel = when {
        dialog == SecurityDialog.RemovePin -> "Aktuelle PIN eingeben"
        dialog == SecurityDialog.ChangePin && step == 1 -> "Aktuelle PIN eingeben"
        dialog == SecurityDialog.ChangePin && step == 2 -> "Neue PIN (4–6 Ziffern)"
        step == 3 -> "Neue PIN wiederholen"
        else -> "Neue PIN (4–6 Ziffern)"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (dialog) {
                    SecurityDialog.SetPin -> "PIN setzen"
                    SecurityDialog.ChangePin -> "PIN ändern"
                    SecurityDialog.RemovePin -> "PIN löschen"
                }
            )
        },
        text = {
            Column {
                Text(fieldLabel, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        input = raw.filter { it in '0'..'9' }.take(AppLockManager.PIN_MAX_LENGTH)
                    },
                    singleLine = true,
                    isError = message != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    supportingText = {
                        message?.let { Text(it, color = FlipsiColors.SignalRedText) }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pin = input
                    when (dialog) {
                        SecurityDialog.RemovePin -> when (manager.verifyPin(pin)) {
                            is PinVerifyResult.Success -> {
                                if (manager.clearPin(pin)) {
                                    done("App-Sperre entfernt — die App öffnet wieder ohne PIN.")
                                } else {
                                    message = "Löschen fehlgeschlagen — bitte erneut versuchen."
                                }
                            }
                            is PinVerifyResult.LockedOut -> message = "Zu viele Fehlversuche — später erneut versuchen."
                            else -> message = "Falsche PIN."
                        }

                        SecurityDialog.ChangePin -> when (step) {
                            1 -> when (manager.verifyPin(pin)) {
                                is PinVerifyResult.Success -> {
                                    step = 2
                                    input = ""
                                }
                                is PinVerifyResult.LockedOut -> message = "Zu viele Fehlversuche — später erneut versuchen."
                                else -> message = "Falsche PIN."
                            }
                            2 -> if (!manager.isValidPinFormat(pin)) {
                                message = "4–6 Ziffern nötig."
                            } else {
                                first = pin
                                step = 3
                                input = ""
                            }
                            else -> if (pin != first) {
                                first = ""
                                input = ""
                                message = "PINs stimmen nicht überein — neu eingeben."
                            } else {
                                commitSetPin()
                            }
                        }

                        SecurityDialog.SetPin -> when (step) {
                            0 -> if (!manager.isValidPinFormat(pin)) {
                                message = "4–6 Ziffern nötig."
                            } else {
                                first = pin
                                step = 3
                                input = ""
                            }
                            else -> if (pin != first) {
                                first = ""
                                input = ""
                                message = "PINs stimmen nicht überein — neu eingeben."
                            } else {
                                commitSetPin()
                            }
                        }
                    }
                },
            ) { Text(if (step == 3) "Bestätigen" else "Weiter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}