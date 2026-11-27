package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.server.ServerProfileRules
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.onboarding.PassCodeField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * W17-F2 (Besitzer-Idee): Optionales Extra-Passwort für die KOPLUNG — Bedienung
 * in „Einstellungen › Server“.
 *
 *  - Statusanzeige „Passwortschutz aktiv/inaktiv“ (Wahrheit = /password_status
 *    des AKTIVEN Relays; null = nicht erreichbar → Beschriftung bleibt, der
 *    Status-Aufruf läuft je Öffnen der Karte frisch).
 *  - Schalter AN = Passwort setzen (maskiertes Eingabefeld, mind. 8 Zeichen —
 *    der Server speichert NUR den PBKDF2-Hash, die App erhält nie Klartext
 *    zurück); Schalter AUS = Schutz entfernen (/password_clear).
 *  - Die HTTP-Rufe (pairingPasswordSet/Clear/Status) laufen NUR auf IO —
 *    der OkHttp-Client blockt synchron (bestehendes Muster key_get/postJson).
 *
 * Zusätzlich lebt hier [PairingPasswordPromptFlow]: das Passwort-Prompt,
 * wenn das Relay einen Kopplungs-Handshake mit 403 password-required/
 * password-invalid abgewiesen hat (RelayClient.pairingPasswordNeeded), und
 * der code-in-use-Snack (W17-F1, RelayClient.codeInUseObserved-Zähler).
 * Eingang: „Einstellungen › Server“-Gruppe (BridgeScreen) + einmalig am
 * Screen-Level (Dialoge wie showRegenDialog).
 */
@Composable
fun PairingPasswordCard(onSnack: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = Status unbekannt (Server nicht erreichbar / kein Profil).
    var serverActive by remember { mutableStateOf<Boolean?>(null) }
    var pwText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var shortNote by remember { mutableStateOf(false) }

    // Status je Karten-Einblenden frisch holen (Wahrheit am Server).
    LaunchedEffect(Unit) {
        serverActive = withContext(Dispatchers.IO) {
            RelayClient.pairingPasswordStatus()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.pw_card_title),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.pw_card_hint),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(
                        if (serverActive == true) R.string.pw_status_on
                        else R.string.pw_status_off,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = serverActive == true,
                    // AN = Passwort setzen (Feld + Wert nötig); AUS = Clear.
                    onCheckedChange = { target ->
                        if (busy) return@Switch
                        if (target) {
                            // Bewusster Set-Pfad: erst speichern, dann Status.
                            if (!ServerProfileRules.passwordSatisfiesServer(pwText)) {
                                shortNote = true
                                return@Switch
                            }
                            shortNote = false
                            busy = true
                            scope.launch {
                                val pw = pwText
                                val ok = withContext(Dispatchers.IO) {
                                    RelayClient.pairingPasswordSet(password = pw)
                                }
                                serverActive = withContext(Dispatchers.IO) {
                                    RelayClient.pairingPasswordStatus()
                                }
                                busy = false
                                pwText = ""
                                if (ok) {
                                    // W17-F2 Hygiene: Set-Erfolg → dieselbe
                                    // Zeichenkette in den Profil-Vault — die
                                    // EIGENEN Reconnects tragen sie mit (kein
                                    // zweites Abtippen, Besitzer-Prompt nur bei
                                    // ANDEREN Geräten nötig).
                                    withContext(Dispatchers.IO) {
                                        ServerProfiles.writeActivePassword(pw)
                                    }
                                }
                                onSnack(
                                    context.getString(
                                        if (ok) R.string.pw_saved else R.string.pw_error,
                                    ),
                                )
                            }
                        } else {
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    RelayClient.pairingPasswordClear()
                                }
                                serverActive = withContext(Dispatchers.IO) {
                                    RelayClient.pairingPasswordStatus()
                                }
                                busy = false
                                onSnack(
                                    context.getString(
                                        if (ok) R.string.pw_cleared else R.string.pw_error,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
            // Maskiertes Eingabefeld für den AN-Pfad (min 8 — Hinweis live).
            PassCodeField(
                value = pwText,
                onValueChange = {
                    pwText = it
                    shortNote = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.pw_input_label),
            )
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    if (!ServerProfileRules.passwordSatisfiesServer(pwText)) {
                        shortNote = true
                        return@OutlinedButton
                    }
                    shortNote = false
                    busy = true
                    scope.launch {
                        val pw = pwText
                        val ok = withContext(Dispatchers.IO) {
                            RelayClient.pairingPasswordSet(password = pw)
                        }
                        serverActive = withContext(Dispatchers.IO) {
                            RelayClient.pairingPasswordStatus()
                        }
                        busy = false
                        pwText = ""
                        if (ok) {
                            // W17-F2 Hygiene: Set-Erfolg → dieselbe Zeichen-
                            // kette in den Profil-Vault (eigenes Gerät
                            // reconnectet mit Passwort — kein zweites Abtipp).
                            withContext(Dispatchers.IO) {
                                ServerProfiles.writeActivePassword(pw)
                            }
                        }
                        onSnack(
                            context.getString(
                                if (ok) R.string.pw_saved else R.string.pw_error,
                            ),
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = FlipsiColors.TextPrimary,
                ),
            ) {
                Text(stringResource(R.string.pw_button_set), fontWeight = FontWeight.Medium)
            }
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            RelayClient.pairingPasswordClear()
                        }
                        serverActive = withContext(Dispatchers.IO) {
                            RelayClient.pairingPasswordStatus()
                        }
                        busy = false
                        if (ok) {
                            // W17-F2 Hygiene: Clear-Erfolg → Kopie im Profil-
                            // Vault der App MIT räumen (nichts Totes senden;
                            // der Handshake schickt dann wieder Code-only).
                            val activeId = ServerProfiles.activeId.value
                            if (activeId != null) {
                                withContext(Dispatchers.IO) {
                                    ServerProfiles.clearPassword(activeId)
                                }
                            }
                        }
                        onSnack(
                            context.getString(
                                if (ok) R.string.pw_cleared else R.string.pw_error,
                            ),
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = FlipsiColors.SignalRedText,
                ),
            ) {
                Text(stringResource(R.string.pw_button_clear), fontWeight = FontWeight.Medium)
            }
            if (shortNote) {
                Text(
                    stringResource(R.string.pw_min_error),
                    style = MaterialTheme.typography.labelMedium,
                    color = FlipsiColors.SignalRedText,
                )
            }
        }
    }
}

/**
 * Screen-Level-Verband (einmalig in BridgeScreen gemountet):
 *  - W17-F1: code-in-use → EIN Snack je Anstieg des Zählers
 *    („Dieser Code ist bereits mit einem Gerät verbunden …“);
 *  - W17-F2: Passwort-Prompt, sobald RelayClient.pairingPasswordNeeded true
 *    meldet — Speichern geht in den Vault des AKTIVEN Profils, danach direkt
 *    ein bewusstes connectViaProfile (der Handshake trägt das Passwort mit).
 */
@Composable
fun PairingPasswordPromptFlow(onSnack: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // W17-F1: Snack je Zähler-Anstieg (einmalig je Kopplungs-Abweisung).
    val codeInUse by RelayClient.codeInUseObserved.collectAsState()
    var lastSeenInUse by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(codeInUse) {
        if (codeInUse > lastSeenInUse) {
            lastSeenInUse = codeInUse
            onSnack(context.getString(R.string.snack_code_in_use))
        }
    }

    // W17-F2: das Prompt — NUR solange der Merker steht (connect/onOpen/
    // acknowledge räumen ihn; kein Zustands-Kleben über App-Reste).
    val pwNeeded by RelayClient.pairingPasswordNeeded.collectAsState()
    if (!pwNeeded) return

    var pwText by rememberSaveable { mutableStateOf("") }
    var shortNote by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { RelayClient.acknowledgePasswordPrompt() },
        title = { Text(stringResource(R.string.pw_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pw_prompt_text))
                PassCodeField(
                    value = pwText,
                    onValueChange = {
                        pwText = it
                        shortNote = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.onb_pw_label),
                )
                if (shortNote) {
                    Text(
                        stringResource(R.string.pw_min_error),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.SignalRedText,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (busy) return@TextButton
                    if (!ServerProfileRules.passwordSatisfiesServer(pwText)) {
                        shortNote = true
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val pw = pwText
                        val activeId = ServerProfiles.activeId.value
                        val saved = withContext(Dispatchers.IO) {
                            if (activeId != null) {
                                ServerProfiles.writeActivePassword(pw)
                            }
                            activeId != null
                        }
                        busy = false
                        RelayClient.acknowledgePasswordPrompt()
                        if (saved && activeId != null) {
                            ServerProfiles.connectViaProfile(context, activeId)
                        } else {
                            onSnack(context.getString(R.string.pw_error))
                        }
                    }
                },
            ) {
                Text(
                    stringResource(R.string.pw_prompt_confirm),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { RelayClient.acknowledgePasswordPrompt() }) {
                Text(stringResource(R.string.pw_prompt_dismiss))
            }
        },
    )
}
