package com.hermesandroid.bridge.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.HealthDataBridge
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.overlay.StatusOverlay
import com.hermesandroid.bridge.security.CapabilityGate
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeConnectionService
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.CapabilityGroupCard
import com.hermesandroid.bridge.ui.components.CapabilityMaster
import com.hermesandroid.bridge.ui.components.CapIcon
import com.hermesandroid.bridge.ui.components.ALL_CAPABILITY_UIS
import com.hermesandroid.bridge.ui.components.INVASIVE_CAPS
import com.hermesandroid.bridge.ui.components.GroupIcons
import com.hermesandroid.bridge.ui.components.MasterCapabilityCard
import com.hermesandroid.bridge.ui.components.PermissionAction
import com.hermesandroid.bridge.ui.components.PermissionIcons
import com.hermesandroid.bridge.ui.components.PermissionRow
import com.hermesandroid.bridge.ui.components.SectionTitle
import com.hermesandroid.bridge.ui.components.StateBanner
import com.hermesandroid.bridge.ui.components.capabilityGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FlipsiBridge-Redesign v0.12.0 — „Fähigkeiten-Zentrale":
 * Statusbanner (einzige Warn-Instanz, Icon-Chip + Status-Pill), Kopplung =
 * Schritt 1, Verbindung = Schritt 2, direkt danach die FÄHIGKEITEN-ZENTRALE
 * (Master-Schalter für alle 16 Fähigkeiten — invasive mit Bestätigungsdialog,
 * Master-AUS mit Undo — plus 4 Gruppen-Karten mit Icons/Zähler-Badges), dann
 * die Android-Berechtigungen als 2 Zweck-Karten („Grundlage"/„Daten &
 * Anzeige"), Erweitert, Fußzeile. Komponenten leben unter ui/components/.
 *
 * v0.12.0-Crash-Fixes (Doku: qa/bugs-plan.md):
 *  - startFirstResolvable-Helper (resolveActivity-Precheck + runCatching +
 *    Fallback-Kette): „Alle Dateien" (Bug 1 — Listen-Action matcht package-
 *    URI NIE, IntentFilter.matchData) und HUD-Overlay (gleiche Fehlerklasse)
 *    können die App nicht mehr abschießen.
 *  - Health: pre-flight getSdkStatus + differenzierter Launcher-Callback +
 *    Ursachen-Dialoge (nicht installiert / Update nötig / still blockiert)
 *    + echter Grant-Zustand in der Zeile („n/6 Rechte") statt „Schalter AN".
 */

private enum class HealthDialogCause { NOT_INSTALLED, NEEDS_UPDATE, DENIED_OR_SILENT }

/**
 * Startet den ersten Intent, der auf DIESEM Gerät auflöst (Filter-Realität
 * vor dem startActivity prüfen). Liefert false, wenn keiner ging — der
 * Aufrufer zeigt einen Hinweis statt zu crashen.
 */
private fun startFirstResolvable(context: Context, activity: Activity?, intents: List<Intent>): Boolean {
    if (activity == null) return false
    val pm = context.packageManager
    return intents.any { intent ->
        intent.resolveActivity(pm) != null &&
            runCatching { activity.startActivity(intent) }.isSuccess
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val status by BridgeStatus.state.collectAsState()
    val configVersion by BridgeStatus.configVersion.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var serverUrlText by remember(status.serverUrl) {
        mutableStateOf(status.serverUrl.orEmpty())
    }
    var showRegenDialog by remember { mutableStateOf(false) }
    var showMasterDialog by remember { mutableStateOf(false) }
    var masterUndoSnapshot by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showHealthDialog by remember { mutableStateOf<HealthDialogCause?>(null) }

    fun snack(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    // ── Bildschirmaufnahme: MediaProjection-Consent ────────────────────────
    val screenRecordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        if (ok && result.data != null) {
            ScreenRecorder.setProjectionPermission(result.resultCode, result.data!!)
            Toast.makeText(context, "Bildschirmaufnahme freigegeben", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Bildschirmaufnahme nicht freigegeben", Toast.LENGTH_SHORT).show()
        }
        BridgeStatus.refreshConfig()
    }

    // ── Benachrichtigungen (Android 13+ Runtime-Permission) ────────────────
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "Benachrichtigungen erlaubt" else "Benachrichtigungen verweigert",
            Toast.LENGTH_SHORT,
        ).show()
        BridgeStatus.refreshConfig()
    }

    // ── Health Connect: Consent-Dialog (nur lesen, differenziert) ──────────
    val healthLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val requested = HealthDataBridge.readPermissions
        when {
            granted.containsAll(requested) ->
                Toast.makeText(
                    context,
                    "Health-Berechtigungen erteilt (${granted.size})",
                    Toast.LENGTH_SHORT,
                ).show()
            granted.isNotEmpty() -> {
                val fehlt = requested.filter { it !in granted }
                    .joinToString { HealthDataBridge.metricLabel(it) }
                Toast.makeText(context, "Nur teilweise erteilt — fehlt: $fehlt", Toast.LENGTH_LONG).show()
            }
            // Leerer Grant = abgelehnt/abgebrochen ODER still blockiert
            // (HyperOS) — Ursache + Auswege als Dialog, nicht als Blind-Toast.
            else -> showHealthDialog = HealthDialogCause.DENIED_OR_SILENT
        }
        BridgeStatus.refreshConfig()
    }

    // ── Fähigkeiten-Zentrale: Zustand (CapabilityGate = SSOT) ──────────────
    val allCaps = remember { ALL_CAPABILITY_UIS }
    val groups = remember { capabilityGroups() }
    val enabledIds = remember(configVersion) {
        allCaps.filter { CapabilityGate.isEnabled(it.id) }.map { it.id }.toSet()
    }
    val invasivePending = allCaps.filter { it.invasive && it.id !in enabledIds }

    val onMasterCheckedChange: (Boolean) -> Unit = { target ->
        when {
            // AN und invasive stehen aus → ERST Dialog, KEIN CapabilityGate-Write davor
            target && invasivePending.isNotEmpty() -> showMasterDialog = true
            target -> {
                CapabilityMaster.enableAll(allCaps.map { it.id }, includeInvasive = true, INVASIVE_CAPS::contains)
                BridgeStatus.refreshConfig()
                snack("Alle ${allCaps.size} Fähigkeiten aktiv")
            }
            else -> {
                masterUndoSnapshot = CapabilityMaster.disableAll(allCaps.map { it.id })
                BridgeStatus.refreshConfig()
                scope.launch {
                    val r = snackbarHostState.showSnackbar(
                        message = "Alle Fähigkeiten ausgeschaltet",
                        actionLabel = "Rückgängig",
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed && masterUndoSnapshot.isNotEmpty()) {
                        CapabilityMaster.restore(masterUndoSnapshot)
                        BridgeStatus.refreshConfig()
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = FlipsiColors.Void,
        topBar = {
            TopAppBar(
                title = { Text("FlipsiBridge", fontWeight = FontWeight.SemiBold) },
                actions = {
                    Text(
                        BridgeStatus.appVersion(),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FlipsiColors.Void),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                // Karten enden nie hart an der Gesture-Navigationssäule
                bottom = 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Status: immer ERST, EINE Instanz ────────────────────────────
            item(key = "banner") { screenStateBanner(status) }

            // ── Schritt 1: Kopplung (Code zuerst am Server!) ───────────────
            item(key = "step1label") { StepLabel(1, "Kopplungscode am Server einrichten") }
            item(key = "step1card") {
                PairingCard(
                    onCopy = { code ->
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val data = ClipData.newPlainText("FlipsiBridge Kopplungscode", code)
                        // SENSITIVE-Markierung (API 33+) — die Clipboard-Vorschau
                        // /Klartext-Anzeige unterbleibt.
                        if (Build.VERSION.SDK_INT >= 33) {
                            data.description.extras = android.os.PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        cm.setPrimaryClip(data)
                        snack("Kopplungscode kopiert")
                    },
                    onRegenerateRequest = { showRegenDialog = true },
                )
            }

            // ── Schritt 2: Verbinden ───────────────────────────────────────
            item(key = "step2label") { StepLabel(2, "Verbindung herstellen") }
            item(key = "step2card") {
                ConnectionCard(
                    status,
                    serverUrlText = serverUrlText,
                    onUrlChange = { serverUrlText = it },
                    onConnect = {
                        val url = serverUrlText.trim()
                        if (url.isBlank()) {
                            snack("Server-Adresse eintragen")
                        } else {
                            BridgeConnectionService.start(context)
                            RelayClient.connect(url, PairingManager.getCode())
                        }
                    },
                    onDisconnect = {
                        RelayClient.disconnect()
                        snack("Verbindung getrennt")
                    },
                )
            }

            // ── Fähigkeiten-Zentrale (Master + 4 Gruppen) ───────────────────
            item(key = "capsTitle") {
                SectionTitle(
                    "Fähigkeiten des Agenten",
                    sub = "Was der Agent am Gerät darf — der Master-Schalter setzt alle auf einmal.",
                )
            }
            item(key = "master") {
                MasterCapabilityCard(
                    enabledCount = enabledIds.size,
                    totalCount = allCaps.size,
                    onCheckedChange = onMasterCheckedChange,
                )
            }
            groups.forEachIndexed { index, (title, caps) ->
                item(key = "group:$title") {
                    CapabilityGroupCard(
                        title = title,
                        caps = caps,
                        enabledIds = enabledIds,
                        onToggle = { cap, on ->
                            // v0.12.0 QA-Fix (SK-01, dreifach belegt): die
                            // Einzeltoggles MÜSSEN ins Gate schreiben — der
                            // v0.11.0-Write ging beim Redesign verloren.
                            CapabilityGate.setEnabled(cap.id, on)
                            BridgeStatus.refreshConfig()
                        },
                        iconRes = when (index) {
                            0 -> GroupIcons.ansehen
                            1 -> GroupIcons.bedienen
                            2 -> GroupIcons.schreiben
                            else -> GroupIcons.invasiv
                        },
                    )
                }
            }

            // ── Berechtigungen (Android) — 2 Zweck-Karten ──────────────────
            item(key = "permTitle") {
                SectionTitle(
                    "Berechtigungen",
                    sub = "Android-Freigaben: zuerst die Grundlage, dann die tiefen Anzeige-/Daten-Zugriffe.",
                )
            }
            item(key = "permCard") {
                PermissionsSections(
                    configVersion = configVersion,
                    context = context,
                    activity = activity,
                    onRequestNotifications = {
                        notificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onScreenRecord = {
                        val service = BridgeAccessibilityService.instance
                        if (service == null) {
                            snack("Zuerst die Eingabehilfe aktivieren (Bedienung & Lesen)")
                        } else {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                            screenRecordLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    onHealthGrant = {
                        // v0.12.0: pre-flight — SDK-Zustand prüfen BEVOR der
                        // Consent-Lauf startet.
                        CapabilityGate.setEnabled("health", true)
                        BridgeStatus.refreshConfig()
                        when (androidx.health.connect.client.HealthConnectClient.getSdkStatus(context)) {
                            androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE -> {
                                runCatching {
                                    healthLauncher.launch(HealthDataBridge.readPermissions)
                                }.onFailure {
                                    BridgeStatus.showText(
                                        "Health-Dialog nicht geöffnet (${it.javaClass.simpleName}) — Health Connect installieren/öffnen",
                                    )
                                }
                            }
                            androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                                showHealthDialog = HealthDialogCause.NEEDS_UPDATE
                            else ->
                                showHealthDialog = HealthDialogCause.NOT_INSTALLED
                        }
                    },
                )
            }

            // ── Erweitert ──────────────────────────────────────────────────
            item(key = "advTitle") { SectionTitle("Erweitert") }
            item(key = "advCard") { AdvancedCard(configVersion) }

            // ── Fußzeile: lokale LAN-Steuerung ─────────────────────────────
            item(key = "footer") { FooterCard() }
        }
    }

    // ── Dialoge ────────────────────────────────────────────────────────────
    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text("Kopplungscode neu erzeugen?") },
            text = {
                Text(
                    "Der Server kennt nur den eingerichteten Code. " +
                        "Erst den neuen Code am Server hinterlegen, " +
                        "dann kommt die Verbindung wieder zustande.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRegenDialog = false
                        PairingManager.regenerateCode()
                        BridgeStatus.refreshConfig()
                        snack("Neuer Code erzeugt — am Server eintragen")
                    }
                ) { Text("Erzeugen") }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) { Text("Abbrechen") }
            },
        )
    }

    if (showMasterDialog) {
        AlertDialog(
            onDismissRequest = { showMasterDialog = false },
            // Bestätigungs-Button nennt, was passiert („Alle einschalten",
            // nie „OK") — invasive kommen NAMENTLICH in die Liste.
            title = { Text("Alle Fähigkeiten einschalten?") },
            text = {
                Column {
                    Text(
                        "Damit werden auch diese invasiven Fähigkeiten aktiviert:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    invasivePending.forEach { cap ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CapIcon(cap.id, tint = FlipsiColors.SignalAmber, size = 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(cap.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Standort, SMS, Anrufe, Kontakte und Mikrofon geben der Agent Zugriff auf sensible Daten. " +
                            "Android-Freigaben (z. B. Health-Consent) bleiben davon unberührt und sind separat zu erteilen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                    OutlinedButton(
                        onClick = {
                            showMasterDialog = false
                            CapabilityMaster.enableAll(allCaps.map { it.id }, includeInvasive = false, INVASIVE_CAPS::contains)
                            BridgeStatus.refreshConfig()
                            // v0.12.0 QA-Fix (SK-11): ECHTE Zahl nach dem Write,
                            // statt Stur-Annahme (invasive können schon an sein).
                            val n = allCaps.count { CapabilityGate.isEnabled(it.id) }
                            snack("$n von ${allCaps.size} Fähigkeiten aktiv — invasive bleiben aus")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                    ) { Text("Ohne invasive einschalten") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMasterDialog = false
                    CapabilityMaster.enableAll(allCaps.map { it.id }, includeInvasive = true, INVASIVE_CAPS::contains)
                    BridgeStatus.refreshConfig()
                    snack("Alle ${allCaps.size} Fähigkeiten aktiv")
                }) {
                    Text("Alle einschalten", color = FlipsiColors.Ember, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMasterDialog = false }) { Text("Abbrechen") }
            },
            containerColor = FlipsiColors.PanelElev,
        )
    }

    showHealthDialog?.let { cause ->
        val (title, text) = when (cause) {
            HealthDialogCause.NOT_INSTALLED ->
                "Health Connect fehlt" to
                    "Auf diesem Gerät ist Health Connect nicht installiert oder nicht aktiv. Nach der Installation aus dem Play Store die Zeile „Health-Daten erlauben\" erneut antippen."
            HealthDialogCause.NEEDS_UPDATE ->
                "Health Connect veraltet" to
                    "Die Health-Connect-Komponente braucht ein Update (Play Store). Danach erneut versuchen."
            HealthDialogCause.DENIED_OR_SILENT ->
                "Keine Health-Berechtigungen erteilt" to
                    if (Build.VERSION.SDK_INT >= 34)
                        "Der Systemdialog wurde abgebrochen, abgelehnt oder still blockiert. Öffne die Health-Connect-Einstellungen und setze die Rechte für FlipsiBridge dort."
                    else
                        "Der Berechtigungsdialog wurde abgebrochen oder abgelehnt. Bitte erneut versuchen oder die Rechte in Health Connect manuell setzen."
        }
        AlertDialog(
            onDismissRequest = { showHealthDialog = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    showHealthDialog = null
                    // Health-Connect-Systemeinstellungen (API 34+ Plattform-Action),
                    // darunter Play-Store-Fallback (market → https).
                    val settingsAction = if (Build.VERSION.SDK_INT >= 34)
                        "android.health.connect.action.HEALTH_HOME_SETTINGS"
                    else
                        "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
                    if (!startFirstResolvable(context, activity, listOf(Intent(settingsAction)))) {
                        startFirstResolvable(
                            context, activity,
                            listOf(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")),
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")),
                            ),
                        )
                    }
                }) { Text("Health Connect öffnen") }
            },
            dismissButton = {
                TextButton(onClick = { showHealthDialog = null }) { Text("Später") }
            },
            containerColor = FlipsiColors.PanelElev,
        )
    }
}

// ── Bausteine ───────────────────────────────────────────────────────────────

/** Banner-Verkabelung: die bewährte when-Logik + Pill/Spinner-Wechsel. */
@Composable
private fun screenStateBanner(status: BridgeStatus.ConnectionState) {
    val codeEvent = PairingManager.getLastCodeEvent()
    val busyVerbinde = status.message.contains("Verbinde", ignoreCase = true) ||
        status.message.contains("Verbindungsversuch", ignoreCase = true) ||
        status.message.contains("Neuverbindung", ignoreCase = true)
    val (tint, title, text) = when {
        status.authBroken -> Triple(
            FlipsiColors.SignalRedText,
            "Kopplungscode wurde abgelehnt",
            "Schritt: Code unten kopieren → am Server eintragen → dann verbinden.",
        )
        codeEvent == PairingManager.CodeEvent.REGENERATED && !status.connected -> Triple(
            FlipsiColors.SignalAmber,
            "Kopplungscode wurde neu erstellt",
            "Schritt: Code unten kopieren → am Server eintragen → dann verbinden.",
        )
        status.connected -> Triple(
            FlipsiColors.SignalGreen,
            "Verbunden — Brücke steht",
            "Der Agent kann das Gerät nutzen (nach den Freigaben weiter unten).",
        )
        busyVerbinde -> Triple(
            FlipsiColors.SignalAmber,
            "Verbinde …",
            "Die Wiederverbindungs-Schleife läuft (1–30 s Abstand, danach alle 5 min).",
        )
        else -> Triple(
            FlipsiColors.TextSecondary,
            "Nicht verbunden",
            if (!status.serverUrl.isNullOrBlank()) "Adresse eintragen und verbinden. Die Wiederverbindung läuft im 5-Min-Modus."
            else "Schritt 1: Code am Server → Schritt 2: Adresse eintragen → verbinden.",
        )
    }
    val badge = when {
        status.connected -> "AKTIV" to BadgeState.OK
        status.authBroken -> "KOPPLUNG" to BadgeState.ERROR
        codeEvent == PairingManager.CodeEvent.REGENERATED && !status.connected ->
            "NEUER CODE" to BadgeState.WARN
        else -> "GETRENNT" to BadgeState.OFF
    }
    StateBanner(
        tint = tint,
        title = title,
        text = text,
        busy = busyVerbinde,
        badge = badge,
    )
}

/** Schritt-Kennzeichnung — der Ablaufläufer: 1 = Code am Server, 2 = Verbinden. */
@Composable
private fun StepLabel(number: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(FlipsiColors.PanelElev, CircleShape)
                .border(1.dp, FlipsiColors.LineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = FlipsiColors.Ember,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = FlipsiColors.TextSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ConnectionCard(
    status: BridgeStatus.ConnectionState,
    serverUrlText: String,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (dotColor, stateLabel) = when {
        status.connected -> FlipsiColors.SignalGreen to "Verbunden"
        status.authBroken -> FlipsiColors.SignalRed to "Kopplung nötig"
        status.message.contains("Verbinde", ignoreCase = true) ||
            status.message.contains("Neuverbindung", ignoreCase = true) -> FlipsiColors.SignalAmber to "Verbinde …"
        else -> FlipsiColors.SignalAmber to "Getrennt"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                Text(
                    stateLabel,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            // Message (aus RelayClient) — OHNE die URL-Wiederholung:
            // die URL lebt NUR im Eingabefeld.
            if (!status.connected && !status.message.startsWith("Verbunden")) {
                Text(
                    status.message.ifBlank { "Zustand der Bridge" },
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp), color = FlipsiColors.LineStrong)
            OutlinedTextField(
                value = serverUrlText,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Server-Adresse") },
                // Bewusst generisch — keine private Domain im Quellcode (GitHub-Hygiene)!
                placeholder = { Text("https://dein-relay.example") },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onConnect,
                    enabled = !status.connected,
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlipsiColors.Ember,
                        contentColor = FlipsiColors.OnEmber,
                        disabledContainerColor = FlipsiColors.PanelElev,
                        disabledContentColor = FlipsiColors.TextSecondary,
                    ),
                ) { Text("Verbinden") }
                if (status.connected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.height(48.dp),
                        // Destruktive Aktion = kenntlich dezent rot
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.SignalRedText),
                    ) { Text("Trennen", fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun PairingCard(
    onCopy: (String) -> Unit,
    onRegenerateRequest: () -> Unit,
) {
    val configVersion = BridgeStatus.configVersion.collectAsState().value
    val code = remember(configVersion) { PairingManager.getCode() }
    val lastEvent = remember(configVersion) { PairingManager.getLastCodeEvent() }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Kopplungscode", fontWeight = FontWeight.SemiBold)
            Text(
                "Authentifizierung: Bridge und App am Server.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            Text(
                code,
                style = FlipsiType.Code,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable(onClickLabel = "Kopplungscode kopieren") { onCopy(code) },
            )
            Text(
                "Nur ändern zusammen mit der Serverseite (neuer Code dort).",
                style = MaterialTheme.typography.labelSmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Code-Ereignis = graue Info (Grün = NUR Erfolg, Rot = NUR Ablehnung)
            when (lastEvent) {
                PairingManager.CodeEvent.RESTORED -> Text(
                    "Speicher wurde geleert — Code automatisch wiederhergestellt (keine Aktion nötig)",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                PairingManager.CodeEvent.REGENERATED -> Text(
                    "Code ist neu — am Server eintragen (Banner oben zeigt den Schritt)",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                else -> {}
            }
            OutlinedButton(
                onClick = onRegenerateRequest,
                modifier = Modifier.padding(top = 10.dp).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
            ) { Text("Neuen Code erzeugen") }
        }
    }
}

/**
 * Die zwei Berechtigungs-Karten (v0.12.0): „Grundlage" (das, ohne das nichts
 * läuft) + „Daten & Anzeige" (optionale Tief-Freigaben). Alle Settings-Sprünge
 * über das startFirstResolvable-Crash-Shield.
 */
@Composable
private fun PermissionsSections(
    configVersion: Int,
    context: Context,
    activity: Activity?,
    onRequestNotifications: () -> Unit,
    onScreenRecord: () -> Unit,
    onHealthGrant: () -> Unit,
) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val allFilesOk = remember(configVersion) { runCatching { Environment.isExternalStorageManager() }.getOrDefault(false) }
    val a11yOk = remember(configVersion) { BridgeAccessibilityService.instance != null }
    val recordOk = remember(configVersion) { ScreenRecorder.hasPermission() }
    val overlayOk = remember(configVersion) { Settings.canDrawOverlays(context) }
    val notificationsOk = remember(configVersion) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(configVersion) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    val healthCapOn = remember(configVersion) { CapabilityGate.isEnabled("health") }

    // v0.12.0: ECHTER Grant-Zustand („n/6 Rechte") — „Capability AN" sagte
    // nichts über die Health-Connect-Rechte (Verwirrungsquelle aus Bug 2).
    var healthGrantState by remember(configVersion) { mutableStateOf<Int?>(null) }
    LaunchedEffect(configVersion, healthCapOn) {
        healthGrantState = withContext(Dispatchers.IO) {
            runCatching {
                if (androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) !=
                    androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE
                ) {
                    -1
                } else {
                    val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
                    val granted = client.permissionController.getGrantedPermissions()
                    HealthDataBridge.readPermissions.count { it in granted }
                }
            }.getOrNull()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Grundlage der Fernsteuerung",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
                PermissionRow(
                    iconRes = PermissionIcons.accessibility,
                    title = "Eingabehilfe (Bedienung & Lesen)",
                    description = "Fernbedienung, Screen-Abfragen, Texterkennung",
                    badge = if (a11yOk) ("aktiv" to BadgeState.OK) else ("fehlt" to BadgeState.ERROR),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    if (!startFirstResolvable(
                            context, activity,
                            listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                        )
                    ) {
                        BridgeStatus.showText("Eingabehilfe-Einstellungen nicht erreichbar")
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.battery,
                    title = "Akku: keine Einschränkung",
                    description = "HAUPTGRUND für die stabile Verbindung — HyperOS/Doze dürfen die Brücke nicht stilllegen",
                    badge = if (batteryOk) ("aktiv" to BadgeState.OK) else ("nötig" to BadgeState.WARN),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    // OEM-abhängiger Intent — Crash-Shield statt App-Crash am Tap.
                    runCatching {
                        activity?.startActivity(
                            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }.onFailure {
                        BridgeStatus.showText("Akku-Einstellung auf diesem Gerät nicht verfügbar")
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.notifications,
                    title = "Benachrichtigungen zeigen",
                    description = "Damit Verbindungs-/Hinweis-Meldungen erscheinen (Android 13+)",
                    badge = if (notificationsOk) ("aktiv" to BadgeState.OK) else ("fehlt" to BadgeState.ERROR),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    if (!notificationsOk) onRequestNotifications()
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.autostart,
                    title = "Autostart (HyperOS)",
                    description = "Damit die Brücke nach Handy-Neustart von allein startet — Xiaomi-Sicherheits-Einstellung",
                    badge = "prüfen" to BadgeState.WARN,
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    // MIUI-Component ist auf Nicht-Xiaomi NICHT present →
                    // runCatching + Hinweis statt Crash (K5-Regel).
                    runCatching {
                        activity?.startActivity(
                            Intent().apply {
                                component = ComponentName(
                                    "com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                                )
                                putExtra("extra_pkgname", context.packageName)
                            }
                        )
                    }.onFailure {
                        BridgeStatus.showText("Autostart-Einstellung nicht gefunden — auf dem Gerät prüfen")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Daten & Anzeige",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
                PermissionRow(
                    iconRes = PermissionIcons.allFiles,
                    title = "Alle Dateien zugreifen",
                    description = "Voller Lese-/Schreibzugriff auf Downloads & Ordner",
                    badge = if (allFilesOk) ("aktiv" to BadgeState.OK) else ("fehlt" to BadgeState.ERROR),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    // v0.12.0 Bug 1: Listen-Action + package-URI kann auf AOSP
                    // NIE auflösen (IntentFilter.matchData) → das war der Crash.
                    // Reihenfolge: 1) per-App-Action (hat den package-Filter),
                    // 2) Liste ohne URI, 3) App-Details als letzter Ausweg.
                    val opened = startFirstResolvable(
                        context, activity,
                        listOf(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        ),
                    )
                    if (!opened) {
                        BridgeStatus.showText(
                            "Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Berechtigungen",
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.screenCapture,
                    title = "Bildschirmaufnahme (MediaProjection)",
                    description = "Der Android-Dialog erscheint pro Sitzung erneut",
                    badge = if (recordOk) ("aktiv" to BadgeState.OK) else ("nicht aktiv" to BadgeState.OFF),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    if (!recordOk) onScreenRecord()
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.health,
                    title = "Health-Daten erlauben",
                    description = "Health-Connect-Berechtigungen (Schritte/Puls/Schlaf …) — Capability wird mit angeschaltet",
                    badge = when {
                        !healthCapOn -> "Standard AUS" to BadgeState.OFF
                        healthGrantState == null -> "prüfen" to BadgeState.WARN
                        healthGrantState == -1 -> "Health Connect fehlt" to BadgeState.ERROR
                        else -> {
                            val n = healthGrantState ?: 0
                            "${n} / ${HealthDataBridge.readPermissions.size} Rechte" to
                                if (n == HealthDataBridge.readPermissions.size) BadgeState.OK else BadgeState.WARN
                        }
                    },
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    onHealthGrant()
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.overlay,
                    title = "HUD-Overlay",
                    description = "Kleines Status-Zeichen über allem (Optional)",
                    badge = if (overlayOk) ("möglich" to BadgeState.OK) else ("fehlt" to BadgeState.OFF),
                    onAction = PermissionAction.Toggle(checked = overlayOk),
                    onToggle = { show ->
                        if (show) {
                            if (Settings.canDrawOverlays(context)) {
                                StatusOverlay.show(context)
                            } else {
                                // v0.12.0: resolveActivity entscheidet die richtige
                                // Form — Overlay-Action mit package-URI (per-App,
                                // wenn der Filter das Schema trägt) → Liste →
                                // App-Details. Kein Crash mehr, welches OEM auch.
                                val opened = startFirstResolvable(
                                    context, activity,
                                    listOf(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    ),
                                )
                                if (!opened) BridgeStatus.showText("Overlay-Einstellung auf diesem Gerät nicht erreichbar")
                            }
                        } else StatusOverlay.hide(context)
                        BridgeStatus.refreshConfig()
                    },
                )
            }
        }
    }
}

@Composable
private fun AdvancedCard(configVersion: Int) {
    val context = LocalContext.current
    val enabled = remember(configVersion) {
        context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
            .getBoolean("termux_revival_enabled", false)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            PermissionRow(
                iconRes = PermissionIcons.autostart,
                title = "Termux-Wiederanlauf (Spezialfall)",
                description = "Nur bei einem Termux-Hilfsstack am Gerät — standardmäßig AUS",
                badge = if (enabled) ("AN" to BadgeState.OK) else ("AUS" to BadgeState.OFF),
                onAction = PermissionAction.Toggle(checked = enabled),
                onToggle = { on ->
                    context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("termux_revival_enabled", on).apply()
                    BridgeStatus.refreshConfig()
                },
            )
            Text(
                "Wiederverbindungs-Schleife: 1–30 s Abstand, danach endlos alle 5 min mit Weckalarm. Bei Kopplungsproblemen stoppt die Schleife — keine Spam-Warnungen.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun FooterCard() {
    val context = LocalContext.current
    val ip = remember { localIpOf(context) }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Lokale Steuerung (USB/LAN)", fontWeight = FontWeight.SemiBold)
            Text(
                "http://$ip:8765 — nur im Heim-WLAN",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Regelbetrieb läuft über den Relay-Server (oben) — damit der Agent auch unterwegs Zugriff hat.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun localIpOf(context: Context): String {
    return runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "—"
    }.getOrNull() ?: "—"
}