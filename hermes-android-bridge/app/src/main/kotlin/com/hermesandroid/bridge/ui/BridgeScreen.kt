package com.hermesandroid.bridge.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.HealthDataBridge
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.crypto.CryptoEngine
import com.hermesandroid.bridge.files.UpdateInstaller
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.overlay.StatusOverlay
import com.hermesandroid.bridge.permissions.DeviceFeature
import com.hermesandroid.bridge.permissions.DeviceIntentResolver
import com.hermesandroid.bridge.security.AppLockSettingsCard
import com.hermesandroid.bridge.security.CapabilityGate
import com.hermesandroid.bridge.security.MtlsSupport
import com.hermesandroid.bridge.ui.chat.ChatScreen
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeConnectionService
import com.hermesandroid.bridge.ui.components.BadgeState
import com.hermesandroid.bridge.ui.components.CapabilityGroupCard
import com.hermesandroid.bridge.ui.components.CapabilityMaster
import com.hermesandroid.bridge.ui.components.CapIcon
import com.hermesandroid.bridge.ui.components.ALL_CAPABILITY_UIS
import com.hermesandroid.bridge.ui.components.CountBadge
import com.hermesandroid.bridge.ui.components.INVASIVE_CAPS
import com.hermesandroid.bridge.ui.components.GroupIcons
import com.hermesandroid.bridge.ui.components.MasterCapabilityCard
import com.hermesandroid.bridge.ui.components.NavIcons
import com.hermesandroid.bridge.ui.components.PermissionAction
import com.hermesandroid.bridge.ui.components.PermissionIcons
import com.hermesandroid.bridge.ui.components.PermissionRow
import com.hermesandroid.bridge.ui.components.ResourceIcon
import com.hermesandroid.bridge.ui.components.SectionTitle
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.components.LanguageSettingsCard
import com.hermesandroid.bridge.ui.components.StateBanner
import com.hermesandroid.bridge.ui.components.capabilityGroups
import androidx.compose.ui.res.stringResource
import com.hermesandroid.bridge.R
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
 *
 * PH1-Welle 2 (v0.12.6): Einstellungen-Screen mit 3 Accordion-Gruppen
 * (Server/App/Agenten — PH1-T2), Home schlank (lange Listen in Route 1),
 * Banner-Ursachen-Texte (PH1-T4), aufklappbare Fähigkeiten-Gruppen
 * (PH1-T5), Impressum-Inhaltsrahmen (PH1-T6) + 29 geshiftete Haus-
 * Drawables (Icons-Fix — Details im CHANGELOG).
 *
 * W4-D (A6/A7-Verband): Route 0–3 bleibt; [initialRoute] erlaubt den
 * Onboarding-Verband, NACH der Fingerprint-Bestätigung DIREKT IM CHAT
 * (Route 2) zu starten. Die Multi-Server-Liste (RelayServerListCard) lebt
 * in der Server-Gruppe der Einstellungen; die Home-Schnellverbindung folgt
 * dem aktiven Profil (ServerProfiles.syncActiveUrl), Umschalten zwischen
 * Profilen läuft bewusst über Bestätigung + disconnect/connect
 * (Singletons-Faser — genau ein WebSocket, OkHttpClient-Fabrik unangetastet).
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
fun BridgeScreen(initialRoute: Int = 0) {
    val context = LocalContext.current
    // W4 (App-Lane C): Activity-Quelle über die CompositionLocal — der
    // AppLanguageGate-Wrapped-Context ist KEIN Activity-Context (context as?
    // Activity wäre bei DE/EN-Wahl null und würde ALLE Berechtigungs-Sprünge
    // killen). Der Gate liefert die ORIGINAL Activity (AppLanguage.LocalAppActivity).
    val activity = com.hermesandroid.bridge.i18n.AppLanguage.LocalAppActivity.current
    val status by BridgeStatus.state.collectAsState()
    val statusMessage by BridgeStatus.message.collectAsState()
    val serverUrl by BridgeStatus.serverUrl.collectAsState()
    val configVersion by BridgeStatus.configVersion.collectAsState()
    // PH-3 F6: aktive E2E-Sessions für die Header-Kleppe (sichtbarer
    // Verschlüsselungs-Zustand statt unsichtbarem Legacy-Klartext).
    val e2eSessions by RelayClient.e2eSessions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var serverUrlText by remember(serverUrl) {
        mutableStateOf(serverUrl.orEmpty())
    }
    var showRegenDialog by remember { mutableStateOf(false) }
    var showMasterDialog by remember { mutableStateOf(false) }
    var masterUndoSnapshot by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showHealthDialog by remember { mutableStateOf<HealthDialogCause?>(null) }

    // ── PH1-T1: Nav-Route ──────────────────────────────────────────────
    // Einfache Int-Route per rememberSaveable (KEINE androidx.navigation-
    // Dependency): 0=Home, 1=Einstellungen, 2=Chat, 3=Impressum/Datenschutz.
    // Back-Taste = Route zurück auf Home.
    // W4-D (A6): initialRoute=2 nach der Wizard-Fingerprint-Bestätigung —
    // „danach direkt im Chat“; normale Starts starten auf 0 (Home).
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    BackHandler(enabled = route != 0) { route = 0 }

    // ── PH1-T2: Einstellungen-Accordion — je Gruppe ein EIGENER Klappe-
    // Zustand (rememberSaveable: überlebt Route-Wechsel und Prozess-Restore;
    // bewusst getrennte Flags, damit mehrere Gruppen offen sein können).
    var settingsServerOpen by rememberSaveable { mutableStateOf(true) }
    var settingsAppOpen by rememberSaveable { mutableStateOf(false) }
    var settingsAgentOpen by rememberSaveable { mutableStateOf(false) }
    // PH-2X X-1: Gruppe 4 „App-Sperre“ — append-only, startet zu.
    var settingsSecurityOpen by rememberSaveable { mutableStateOf(false) }
    // PH-3 Welle 2: Gruppe 5 „Verschlüsselung“ (Fingerprints) — append-only.
    var settingsCryptoOpen by rememberSaveable { mutableStateOf(false) }
    // W4 (App-Lane C): Gruppe „Sprache“ (DE/EN) — append-only, startet zu.
    var settingsLanguageOpen by rememberSaveable { mutableStateOf(false) }
    // ── PH1-T5: je Fähigkeiten-Gruppe ein eigener Klappe-Zustand (innerhalb
    // der Agenten-Einstellungen); die invasive Gruppe startet bewusst zu.
    var capGroupAnsehenOpen by rememberSaveable { mutableStateOf(true) }
    var capGroupBedienenOpen by rememberSaveable { mutableStateOf(true) }
    var capGroupSchreibenOpen by rememberSaveable { mutableStateOf(true) }
    var capGroupInvasivOpen by rememberSaveable { mutableStateOf(false) }

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

    /**
     * Home-Screen (Route 0, PH1-T2 schlank): Statusbanner + Verbindungs-
     * status + Master-Status-Kompakt + Fußzeile. Die langen Listen
     * (Kopplung, Fähigkeiten-Zentrale, Berechtigungen, Erweitert) leben
     * seit Welle 2 in den EINSTELLUNGEN (Route 1). Lokale Composable:
     * schließt über die BridgeScreen-Zustände.
     */
    @Composable
    fun HomeContent(padding: PaddingValues) {
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
            item(key = "banner") { screenStateBanner(status, serverUrl) }

            // ── PH1-T2: Kopplung, Fähigkeiten, Berechtigungen und Erweitert
            // sind in die EINSTELLUNGEN gewandert (Sir-Idee: Home schlank —
            // die langen Listen leben in Route 1). Der Kopplungszustand bleibt
            // über das Statusbanner sichtbar (NEUER CODE / KOPPLUNG).
            item(key = "step1label") { StepLabel(1, "Verbindung herstellen") }
            item(key = "step2card") {
                ConnectionCard(
                    status,
                    message = statusMessage,
                    serverUrlText = serverUrlText,
                    onUrlChange = { serverUrlText = it },
                    onConnect = {
                        val url = serverUrlText.trim()
                        if (url.isBlank()) {
                            snack("Server-Adresse eintragen")
                        } else {
                            // W4-D (A7): die Schnellverbindung folgt dem AKTIVEN
                            // Profil — die Liste bleibt wahr (URL+Vault-Spiegel
                            // wandern mit), verbindet wird wie bisher über
                            // RelayClient.connect + SSOT-Code.
                            ServerProfiles.syncActiveUrl(url)
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

            // ── Master-Status kompakt (PH1-T2): nur die Ansicht auf Home —
            // der Master-Schalter und die Einzeltoggles leben in den
            // Agenten-Einstellungen (Einstellungen → Agenten).
            item(key = "masterCompact") {
                MasterCompactCard(
                    enabledCount = enabledIds.size,
                    totalCount = allCaps.size,
                )
            }

            // ── Fußzeile: lokale LAN-Steuerung ─────────────────────────────
            item(key = "footer") { FooterCard() }
        }
    }

    /**
     * Einstellungen-Screen (Route 1, PH1-T2): drei ausklappbare Gruppen
     * (Accordion, je Gruppe eigener Klappe-Zustand oben in BridgeScreen —
     * überlebt Route-Wechsel): Server (Relay-URL, Kopplungscode, mTLS),
     * App (Berechtigungen inkl. Autostart/Akku, Termux, Selbst-Update je
     * Edition) und Agenten (Master-Schalter + 4 klapptbare Fähigkeiten-
     * Gruppen, PH1-T5). Die langen Listen aus dem alten Home — gleiche
     * Composables, gleiche SSOT (serverUrlText, CapabilityGate, Prefs),
     * KEINE neue Logik.
     */
    @Composable
    fun SettingsContent(padding: PaddingValues) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "settingsTitle") {
                SectionTitle(
                    "Einstellungen",
                    sub = "Server, App-Verhalten und Agenten-Fähigkeiten — je Gruppe auf- und zuklappbar.",
                )
            }

            // ── Gruppe 1: Server (Relay-Liste, Relay-URL, Kopplungscode, mTLS) ──
            item(key = "setServer") {
                AccordionCard(
                    title = "Server",
                    iconRes = SettingsIcons.server,
                    expanded = settingsServerOpen,
                    onExpandedChange = { settingsServerOpen = it },
                ) {
                    // W4-D (A7): Multi-Server-Liste — je Relay-Server URL+Code
                    // (verborgen, Reduced-Pass)+Status+Letzt-Verbunden;
                    // Umschalten bewusst mit Trenn-/Wechsel-Bestätigung
                    // (disconnect/connect im Profil-Store, Singletons-Faser).
                    RelayServerListCard()
                    // Relay-URL: GLEICHE SSOT wie das Home-Feld (serverUrlText)
                    // — beide Felder zeigen und schreiben denselben Wert.
                    OutlinedTextField(
                        value = serverUrlText,
                        onValueChange = { serverUrlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Server-Adresse (Relay-URL)") },
                        // Bewusst generisch — keine private Domain im Quellcode.
                        placeholder = { Text("https://dein-relay.example") },
                    )
                    // Kopplungscode: die bestehende PairingCard (Anzeige,
                    // Kopieren mit SENSITIVE-Markierung, Neu-Erzeugen-Dialog).
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
                    // mTLS: nur Anzeige + Öffnen der BESTEHENDEN MtlsSupport-
                    // Route (Systemdialog via choosePrivateKeyAlias) — keine
                    // neue Logik (PH1-T2-Vorgabe).
                    MtlsCard(configVersion = configVersion, activity = activity)
                }
            }

            // ── Gruppe 2: App (Berechtigungen, Termux, Selbst-Update) ──────
            item(key = "setApp") {
                AccordionCard(
                    title = "App",
                    iconRes = SettingsIcons.app,
                    expanded = settingsAppOpen,
                    onExpandedChange = { settingsAppOpen = it },
                ) {
                    // Die zwei Berechtigungs-Karten (Grundlage + Daten &
                    // Anzeige) — unverändert aus dem alten Home übernommen:
                    // Autostart, Akku-Optimierungs-Hinweis (System-Intent),
                    // Benachrichtigungs-, Overlay-, Datei-, Aufnahme- und
                    // Health-Routen.
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
                    // Termux-Wiederanlauf (ehem. „Erweitert", unverändert).
                    AdvancedCard(configVersion)
                    // Selbst-Update: github-Edition aktiv, play-Edition
                    // bewusst disabled mit Hinweis (FLAG_FULL_EDITION).
                    SelfUpdateCard()
                }
            }

            // ── Gruppe 3: Agenten (Master + 4 Fähigkeiten-Gruppen, PH1-T5) ─
            item(key = "setAgent") {
                AccordionCard(
                    title = "Agenten",
                    iconRes = SettingsIcons.agent,
                    expanded = settingsAgentOpen,
                    onExpandedChange = { settingsAgentOpen = it },
                ) {
                    MasterCapabilityCard(
                        enabledCount = enabledIds.size,
                        totalCount = allCaps.size,
                        onCheckedChange = onMasterCheckedChange,
                    )
                    SectionTitle(
                        "Fähigkeiten des Agenten",
                        sub = "Was der Agent am Gerät darf — je Gruppe auf- und zuklappbar (PH1-T5).",
                    )
                    groups.forEachIndexed { index, (title, caps) ->
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
                            // PH1-T5: je Gruppe eigener Klappe-Zustand.
                            expanded = when (index) {
                                0 -> capGroupAnsehenOpen
                                1 -> capGroupBedienenOpen
                                2 -> capGroupSchreibenOpen
                                else -> capGroupInvasivOpen
                            },
                            onExpandedChange = { open ->
                                when (index) {
                                    0 -> capGroupAnsehenOpen = open
                                    1 -> capGroupBedienenOpen = open
                                    2 -> capGroupSchreibenOpen = open
                                    else -> capGroupInvasivOpen = open
                                }
                            },
                        )
                    }
                }
            }

            // ── Gruppe 4: App-Sperre (PH-2X X-1) — APPEND-ONLY am Ende ────
            item(key = "setSecurity") {
                AccordionCard(
                    title = "App-Sperre",
                    iconRes = SettingsIcons.security,
                    expanded = settingsSecurityOpen,
                    onExpandedChange = { settingsSecurityOpen = it },
                ) {
                    AppLockSettingsCard()
                }
            }

            // ── Gruppe 5: Verschlüsselung (PH-3 Welle 2) — APPEND-ONLY ─────
            item(key = "setCrypto") {
                AccordionCard(
                    title = "Verschlüsselung",
                    iconRes = SettingsIcons.crypto,
                    expanded = settingsCryptoOpen,
                    onExpandedChange = { settingsCryptoOpen = it },
                ) {
                    CryptoSettingsCard()
                }
            }

            // ── Gruppe Sprache (W4, App-Lane C) — Einstellungen-Zeile
            // DE/EN: AppLanguage (Prefs „app_language") + AppLanguageGate am
            // Activity-Kompositions-Punkt re-wrapped die App live (kein
            // Activity-Recreate), alle stringResource-Auflösungen wechseln.
            item(key = "setLanguage") {
                AccordionCard(
                    title = stringResource(R.string.settings_language_title),
                    iconRes = SettingsIcons.language,
                    expanded = settingsLanguageOpen,
                    onExpandedChange = { settingsLanguageOpen = it },
                ) {
                    LanguageSettingsCard()
                }
            }
        }
    }

    Scaffold(
        containerColor = FlipsiColors.Void,
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle(route), fontWeight = FontWeight.SemiBold) },
                actions = {
                    // PH-3 F6: E2E-Kleppe im Header — sichtbar sobald aktive
                    // E2E-Sessions existieren (kein unsichtbarer Zustand).
                    E2eUi.headerBadge(e2eSessions.size)?.let { badge ->
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FlipsiColors.OnEmber,
                            modifier = Modifier
                                .background(FlipsiColors.Ember, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .align(Alignment.CenterVertically),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        BridgeStatus.appVersion(),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    // PH1-T1: Nav-Icons — Material-Icons ohne neue Dependency
                    // (Settings/Info aus dem Compose-BOM-Icons-Set; die Chat-
                    // Bubble als eigener Vector-Drawable im res/drawable-Hausstil).
                    IconButton(onClick = { route = 1 }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Einstellungen",
                            tint = FlipsiColors.TextPrimary,
                        )
                    }
                    IconButton(onClick = { route = 2 }) {
                        Icon(
                            painterResource(NavIcons.chat),
                            contentDescription = "Chat",
                            tint = FlipsiColors.TextPrimary,
                        )
                    }
                    IconButton(onClick = { route = 3 }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Impressum und Datenschutz",
                            tint = FlipsiColors.TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FlipsiColors.Void),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // PH1-T1: Screen-Route — 0=Home (else-Zweig = defensiver Rückfall
        // auch für ungültige Route-Werte), 1=Einstellungen, 2=Chat, 3=Impressum.
        // PH-2-T3: Route 2 zeigt jetzt das echte Chat-Gerüst (ui/chat/ChatScreen).
        when (route) {
            1 -> SettingsContent(padding)
            2 -> ChatScreen(padding)
            3 -> LegalScreenPlaceholder(padding)
            else -> HomeContent(padding)
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
                        // W4-D (A7): Regenerate folgt dem AKTIVEN Profil — der
                        // neue Code wandert in den Profile-Vault (ein späterer
                        // Profil-Wechsel darf den alten Code nicht beleben).
                        ServerProfiles.writeActiveCode(PairingManager.getCode())
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

/**
 * Banner-Verkabelung (PH1-T3): Zustands-Matching auf ConnectionState — die
 * frühere String-Grep-Route („Verbinde"/„Neuverbindung" im Message-Text) ist
 * komplett raus. Das Neu-Code-Ereignis überdeckt kurzzeitig die Zustands-
 * Anzeige, wenn weder verbunden noch die Kopplung abgelehnt ist.
 */
@Composable
private fun screenStateBanner(
    state: BridgeStatus.ConnectionState,
    serverUrl: String?,
) {
    val codeEvent = PairingManager.getLastCodeEvent()
    // PH1-W3-Fund (Drill-Beweis, P1): CodeEvent.REGENERATED persistiert in den
    // Prefs (beim allerersten Code-Anlegen gesetzt, nie zurückgesetzt) — der
    // Verdeck-Fall darf deshalb KEINE Fehler- oder Wiederverbindungs-Kommunikation
    // ausblenden (FAILED-Ursache-Banner PH1-T4 + RECONNECTING-Hinweis PH1-T3),
    // sonst ist die ganze Zustands-Banner-Logik im Normalbetrieb unsichtbar.
    // Verdeckt bleibt nur die Erstnutzer-Führung (IDLE/CONNECTING).
    if (codeEvent == PairingManager.CodeEvent.REGENERATED &&
        state !is BridgeStatus.ConnectionState.CONNECTED &&
        state !is BridgeStatus.ConnectionState.PAIRING_BROKEN &&
        state !is BridgeStatus.ConnectionState.FAILED &&
        state !is BridgeStatus.ConnectionState.RECONNECTING
    ) {
        StateBanner(
            tint = FlipsiColors.SignalAmber,
            title = "Kopplungscode wurde neu erstellt",
            text = "Schritt: Code in den Einstellungen kopieren → am Server eintragen → dann verbinden.",
            badge = "NEUER CODE" to BadgeState.WARN,
        )
        return
    }
    val visual = when (state) {
        is BridgeStatus.ConnectionState.PAIRING_BROKEN -> BannerVisual(
            tint = FlipsiColors.SignalRedText,
            title = "Kopplungscode wurde abgelehnt",
            // PH1-T4: Ursache + Handlung in Nutzbar-Deutsch (generisch).
            text = "Kopplung abgelehnt — Code in den Einstellungen neu erzeugen bzw. am Server hinterlegen, dann wieder verbinden.",
            busy = false,
            badge = "KOPPLUNG" to BadgeState.ERROR,
        )
        is BridgeStatus.ConnectionState.CONNECTED -> BannerVisual(
            tint = FlipsiColors.SignalGreen,
            title = "Verbunden — Brücke steht",
            text = "Der Agent kann das Gerät nutzen — Fähigkeiten in den Einstellungen anpassen.",
            busy = false,
            badge = "AKTIV" to BadgeState.OK,
        )
        is BridgeStatus.ConnectionState.CONNECTING -> connectingVisual()
        is BridgeStatus.ConnectionState.RECONNECTING -> reconnectingVisual(state.ursache)
        is BridgeStatus.ConnectionState.FAILED -> BannerVisual(
            tint = FlipsiColors.SignalAmber,
            title = "Nicht verbunden",
            // PH1-T4: Banner sagt Ursache + Handlung (generisch) — die rohe
            // Ursache bleibt in der Detailzeile der Verbindungskarte sichtbar.
            text = ursacheHinweis(state.ursache),
            busy = false,
            badge = "GETRENNT" to BadgeState.OFF,
        )
        is BridgeStatus.ConnectionState.IDLE -> BannerVisual(
            tint = FlipsiColors.TextSecondary,
            title = "Nicht verbunden",
            text = if (!serverUrl.isNullOrBlank())
                "Adresse unten eintragen und verbinden. Die Wiederverbindung läuft im 5-Min-Modus."
            else
                "Kopplungscode in den Einstellungen am Server einrichten, dann unten verbinden.",
            busy = false,
            badge = "GETRENNT" to BadgeState.OFF,
        )
    }
    StateBanner(
        tint = visual.tint,
        title = visual.title,
        text = visual.text,
        busy = visual.busy,
        badge = visual.badge,
    )
}

/** Sichtwerte des Status-Banners je Zustand (PH1-T3). */
private data class BannerVisual(
    val tint: Color,
    val title: String,
    val text: String,
    val busy: Boolean,
    val badge: Pair<String, BadgeState>,
)

/** Verbindungsaufbau (erstmalig oder in der Schleife): Spinner-Visual. */
private fun connectingVisual(): BannerVisual = BannerVisual(
    tint = FlipsiColors.SignalAmber,
    title = "Verbinde …",
    text = "Die Wiederverbindungs-Schleife läuft (1–30 s Abstand, danach alle 5 min).",
    busy = true,
    badge = "VERBINDE" to BadgeState.WARN,
)

/**
 * Wiederverbindungs-Schleife (PH1-T4): Spinner + Ursachen-Hinweis — die
 * rohe Ursache (Relay-Text) bleibt in der Statuszeile der Verbindungskarte
 * sichtbar, der Banner sagt, was der Nutzer tun kann (generisch, ohne
 * private Daten).
 */
private fun reconnectingVisual(ursache: String): BannerVisual {
    val hint = when {
        // Eigene RelayClient-Meldung (Termux-Revival-Auslösung).
        ursache.startsWith("Stack nicht erreichbar") ->
            "Server nicht erreichbar — der Hilfsstack wird gestartet, weitere Versuche laufen automatisch."
        else ->
            "Server nicht erreichbar — neue Versuche laufen automatisch. URL in den Einstellungen prüfen, falls es dauerhaft nicht klappt."
    }
    return BannerVisual(
        tint = FlipsiColors.SignalAmber,
        title = "Verbinde …",
        text = hint,
        busy = true,
        badge = "VERBINDE" to BadgeState.WARN,
    )
}

/**
 * PH1-T4: Ursachen-Text des Relais in nutzerseitige Handlungs-Hinweise
 * übersetzen. Die Präfixe stammen aus unseren EIGENEN RelayClient-
 * Meldungen (deterministische Konstanten) — unbekannte Fälle zeigen den
 * generischen Hinweis. Die Detailzeile der Verbindungskarte bleibt die
 * rohe Ursache-Auskunft für Fortgeschrittene.
 */
private fun ursacheHinweis(ursache: String): String = when {
    ursache.startsWith("Server-Adresse ungültig") ->
        "Server-Adresse ist ungültig — URL in den Einstellungen prüfen und erneut verbinden."
    ursache.startsWith("Verbindungsfehler") ->
        "Server nicht erreichbar — URL in den Einstellungen prüfen und Verbindung wiederherstellen."
    ursache.startsWith("Revival") ->
        "Termux-Hilfsstack konnte nicht gestartet werden — Termux öffnen und die Erlaubnis dafür prüfen."
    else ->
        "Verbindung fehlgeschlagen — Server-Adresse prüfen und erneut verbinden."
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
    state: BridgeStatus.ConnectionState,
    message: String,
    serverUrlText: String,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // PH1-T3: Zustands-Matching auf ConnectionState (exhaustive, ohne else) —
    // die frühere Text-Grep-Route („Verbinde"/„Neuverbindung") ist raus.
    val (dotColor, stateLabel) = when (state) {
        is BridgeStatus.ConnectionState.CONNECTED ->
            FlipsiColors.SignalGreen to "Verbunden"
        is BridgeStatus.ConnectionState.PAIRING_BROKEN ->
            FlipsiColors.SignalRed to "Kopplung nötig"
        is BridgeStatus.ConnectionState.CONNECTING ->
            FlipsiColors.SignalAmber to "Verbinde …"
        is BridgeStatus.ConnectionState.RECONNECTING ->
            FlipsiColors.SignalAmber to "Verbinde …"
        is BridgeStatus.ConnectionState.FAILED ->
            FlipsiColors.SignalAmber to "Getrennt"
        is BridgeStatus.ConnectionState.IDLE ->
            FlipsiColors.SignalAmber to "Getrennt"
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
            // Detail-Zeile (letzte Relay-Meldung ODER lokaler K5-Hinweis) —
            // OHNE die URL-Wiederholung: die URL lebt NUR im Eingabefeld.
            // PH1-T3: Sichtbarkeit über den Zustand statt Text-Präfix-Check.
            if (state !is BridgeStatus.ConnectionState.CONNECTED) {
                Text(
                    message.ifBlank { "Zustand der Bridge" },
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
                    enabled = state !is BridgeStatus.ConnectionState.CONNECTED,
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlipsiColors.Ember,
                        contentColor = FlipsiColors.OnEmber,
                        disabledContainerColor = FlipsiColors.PanelElev,
                        disabledContentColor = FlipsiColors.TextSecondary,
                    ),
                ) { Text("Verbinden") }
                if (state is BridgeStatus.ConnectionState.CONNECTED) {
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
                    // PH-2X X-2: Akku-Weg über den OEM-Resolver — erster
                    // generischer Kandidat bleibt der Systemdialog
                    // „Akku-Optimierung ignorieren“ (UX auf AOSP unverändert),
                    // davor die OEM-Kandidaten (z. B. MIUI PowerKeeper).
                    DeviceIntentResolver.launchFeature(context, activity, DeviceFeature.BATTERY) { hint ->
                        BridgeStatus.showText(hint)
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
                // PH-2X X-2: OEM-Resolver statt MIUI-Hardcode (früher
                // Zeilen 1149–1161). Xiaomi bleibt auf Xiaomi-Geräten der
                // ERSTE Kandidat (gleiche UX), alle anderen Hersteller
                // bekommen ihre Profile; löst nichts auf: informative
                // Leit-Zeile je OEM, KEIN Crash (K5/Play-Regel).
                PermissionRow(
                    iconRes = PermissionIcons.autostart,
                    title = "Autostart",
                    description = "Damit die Brücke nach Handy-Neustart von allein startet — öffnet die Autostart-Freigabe deines Herstellers (${DeviceIntentResolver.currentProfile().oemLabel}).",
                    badge = "prüfen" to BadgeState.WARN,
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    DeviceIntentResolver.launchFeature(context, activity, DeviceFeature.AUTOSTART) { hint ->
                        BridgeStatus.showText(hint)
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
                                // PH-2X X-2: Overlay-Weg über den OEM-Resolver —
                                // gleiche Kandidaten-Kette wie der v0.12.0-Crash-
                                // Fix (mit-URI → Liste → App-Details → Startseite),
                                // je Hersteller vorgelagerte OEM-Schalter.
                                DeviceIntentResolver.launchFeature(
                                    context, activity, DeviceFeature.OVERLAY,
                                ) { hint ->
                                    BridgeStatus.showText(hint)
                                }
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

// ── PH1-T1: Top-Bar-Titel je Route ──────────────────────────────────────────

/** Titel der Top-Bar je Route (0 = Home mit dem App-Namen). */
private fun topBarTitle(route: Int): String = when (route) {
    1 -> "Einstellungen"
    2 -> "Chat"
    3 -> "Impressum & Datenschutz"
    // 0 = Home; unbekannte Werte fallen auf den App-Namen zurück.
    else -> "FlipsiBridge"
}

// ── PH1-T2: Einstellungen-Bausteine (Accordion-Gruppenkarten) ──────────────

/**
 * Ausklappbare Gruppen-Karte der Einstellungen (PH1-T2): Kopf (Icon +
 * Titel + Pfeil) ist das ganze Touch-Feld (toggleable, Role.Button —
 * TalkBack meldet den Klappe-Zustand), der Inhalt erscheint nur im
 * ausgeklappten Zustand.
 */
@Composable
private fun AccordionCard(
    title: String,
    @DrawableRes iconRes: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = onExpandedChange,
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResourceIcon(iconRes, tint = FlipsiColors.TextSecondary, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = FlipsiColors.TextSecondary,
                )
            }
            if (expanded) {
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * Master-Status kompakt (PH1-T2): reine ANSICHT auf Home — Zähler-Badge +
 * Wegweiser zu den Agenten-Einstellungen. Schalter leben in Route 1.
 */
@Composable
private fun MasterCompactCard(enabledCount: Int, totalCount: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResourceIcon(GroupIcons.master, tint = FlipsiColors.TextSecondary, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Fähigkeiten des Agenten",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                CountBadge(enabled = enabledCount, total = totalCount, invasive = false)
            }
            Text(
                "$enabledCount von $totalCount Fähigkeiten aktiv — ein- und ausschalten unter Einstellungen → Agenten.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * mTLS-Zertifikats-Bereich (PH1-T2): Statusanzeige + Öffnen der BESTEHENEN
 * MtlsSupport-Route (KeyChain-Systemdialog via choosePrivateKeyAlias) —
 * bewusst KEINE neue Logik, nur Sichtbarmachung.
 */
@Composable
private fun MtlsCard(configVersion: Int, activity: Activity?) {
    val configured = remember(configVersion) { MtlsSupport.isConfigured() }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("mTLS-Zertifikat", fontWeight = FontWeight.SemiBold)
            Text(
                if (configured)
                    "Client-Zertifikat gewählt — die Verbindung legt es dem Relay vor."
                else
                    "Kein Client-Zertifikat gewählt. Nur nötig, wenn der Relay-Server Client-Zertifikate verlangt.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    if (activity != null) {
                        MtlsSupport.chooseCertificate(activity)
                    } else {
                        BridgeStatus.showText("Zertifikat-Auswahl hier nicht verfügbar")
                    }
                },
                modifier = Modifier.padding(top = 10.dp).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
            ) { Text(if (configured) "Anderes Zertifikat wählen" else "Zertifikat wählen") }
        }
    }
}

/**
 * Selbst-Update-Punkt (PH1-T2): github-Edition sichtbar und aktiv
 * (BuildConfig.FLAG_FULL_EDITION, Welle-0-Anlage); play-Edition bewusst
 * DISABLED mit Hinweis — dort aktualisiert der Play Store die App.
 * Die Aktion ist der BESTEHENDE UpdateInstaller (Relay /apk/latest,
 * SHA-256-geprüft, Installationsdialog) — Download auf IO-Thread.
 */
@Composable
private fun SelfUpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Selbst-Update", fontWeight = FontWeight.SemiBold)
            Text(
                if (BuildConfig.FLAG_FULL_EDITION)
                    "Sucht auf dem Relay nach einer neueren APK und zeigt den Installationsdialog (SHA-geprüft)."
                else
                    "Nur in der GitHub-Edition — die Play-Edition aktualisiert sich über den Play Store.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = {
                    scope.launch {
                        // Download gehört auf IO — der UI-Thread bleibt frei.
                        val result = withContext(Dispatchers.IO) { UpdateInstaller.start(context) }
                        BridgeStatus.showText(result.message)
                    }
                },
                enabled = BuildConfig.FLAG_FULL_EDITION,
                modifier = Modifier.padding(top = 10.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlipsiColors.Ember,
                    contentColor = FlipsiColors.OnEmber,
                    disabledContainerColor = FlipsiColors.PanelElev,
                    disabledContentColor = FlipsiColors.TextSecondary,
                ),
            ) { Text("Nach Update suchen") }
        }
    }
}

/**
 * PH-3 Welle 2 — Verschlüsselungs-Karte (Gruppe 5, append-only):
 * Session-Fingerprint 8×4 Hex-Gruppen read-only (Klartext-Abgleich ist
 * Hex-Gleichheit, Anzeige gruppiert/Groß), Agent-Identity-Fingerprint
 * daneben. Abweichung/Reset = rot-Banner (RelayClient.e2eBanner). Der
 * eigentliche Abgleich läuft über den FP-ABGLEICH-Button im Chat (Sirs
 * Revision (iii) — KEIN QR, KEIN Scan): App sendet 'FP-ABGLEICH: <hex>',
 * der Agent antwortet automatisch ✓/✗.
 */
@Composable
private fun CryptoSettingsCard() {
    val e2eSessions by RelayClient.e2eSessions.collectAsState()
    val e2eBanner by RelayClient.e2eBanner.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // PH-3 F6: XDH fehlt → E2E ist auf diesem Gerät unmöglich (fail-
        // closed — CryptoEngine.xdhAvailable ist derselbe Feature-Check,
        // der auch den Handshake blockt). Warn-Zeile statt stiller Legacy-
        // Klartext-Läuferei.
        E2eUi.settingsWarning(CryptoEngine.xdhAvailable)?.let { warning ->
            Card(
                colors = CardDefaults.cardColors(containerColor = FlipsiColors.SignalRed.copy(alpha = 0.16f)),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.SignalRedText,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        // Abweichung = rot-Banner (möglicher Man-in-the-Middle) — dismissbar.
        if (e2eBanner != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = FlipsiColors.SignalRed.copy(alpha = 0.16f)),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e2eBanner ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.SignalRedText,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { RelayClient.clearE2eBanner() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Hinweis schließen",
                            tint = FlipsiColors.SignalRedText,
                        )
                    }
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Ende-zu-Ende-Verschlüsselung", fontWeight = FontWeight.SemiBold)
                Text(
                    if (e2eSessions.isEmpty())
                        "Noch keine aktive E2E-Sitzung — der Handshake läuft beim Agenten-Verbinden automatisch."
                    else
                        "Aktive Sitzung je Agent: Fingerprints vergleichen (Chat → FP-Abgleich-Button).",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                e2eSessions.values.forEach { session ->
                    androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(vertical = 10.dp),
                        color = FlipsiColors.LineStrong,
                    )
                    Text(
                        "Sitzungs-Fingerprint (${session.agentId})",
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.TextSecondary,
                    )
                    // 8×4-Gruppen, read-only: Text NIE in ein editierbares Feld.
                    Text(
                        session.fingerprintDisplay,
                        style = FlipsiType.Code,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Agent-Identity-Fingerprint",
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        session.agentFingerprintDisplay,
                        style = FlipsiType.Code,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Abgleich: im Chat den FP-Abgleich-Button tippen — der Agent bestätigt automatisch (✓ gleich / ✗ Abweichung = rot).",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Chat-Screen: Platzhalter — der Direkt-Chat folgt mit Phase 2. */
@Composable
private fun ChatScreenPlaceholder(padding: PaddingValues) {
    ScreenPlaceholderBody(padding) {
        SectionTitle(
            "Chat",
            sub = "Direkt-Chat — Phase 2.",
        )
    }
}

/**
 * Impressum/Datenschutz (Route 3, PH1-T6): Gerüst mit den 3 Abschnitts-
 * Headern + je Abschnitt ein sauber strukturierter INHALTS-RAHMEN (Karte
 * mit Trennern und definierten Textbereichen) — Phase 8 setzt nur die
 * Inhalte ein. Bewusst keine privaten Daten im Gerüst.
 */
@Composable
private fun LegalScreenPlaceholder(padding: PaddingValues) {
    ScreenPlaceholderBody(padding) {
        SectionTitle("Impressum")
        LegalSectionCard()
        SectionTitle("Datenschutz")
        LegalSectionCard()
        SectionTitle("Nutzungsbedingungen")
        LegalSectionCard()
    }
}

/**
 * Inhalts-Rahmen je Rechts-Abschnitt (PH1-T6): Kartenstruktur mit klar
 * getrennten Textbereichen, damit Phase 8 die Inhalte einheitlich
 * einsetzen kann — ohne Inhalt, ohne private Daten.
 */
@Composable
private fun LegalSectionCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Inhalte folgen in Phase 8",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextSecondary,
            )
            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = FlipsiColors.LineStrong,
            )
            Text(
                "Dieser Abschnitt wird mit den rechtlichen Angaben hinterlegt.",
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
        }
    }
}

/** Gemeinsamer Rahmen der Platzhalter-Screens: füllt die Fläche, scrollbar. */
@Composable
private fun ScreenPlaceholderBody(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
