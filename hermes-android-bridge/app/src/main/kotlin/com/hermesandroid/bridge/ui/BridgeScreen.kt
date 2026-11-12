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
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
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
import com.hermesandroid.bridge.billing.SupporterCard
import com.hermesandroid.bridge.policy.DisclosureState
import com.hermesandroid.bridge.policy.DisclosureDialogHost
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
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.ui.chat.ChatScreen
import com.hermesandroid.bridge.ui.dashboard.DashboardScreen
import com.hermesandroid.bridge.ui.settings.SettingsHome
import com.hermesandroid.bridge.ui.settings.SettingsNav
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
import com.hermesandroid.bridge.ui.components.NavIcons
import com.hermesandroid.bridge.ui.components.PermissionAction
import com.hermesandroid.bridge.ui.components.PermissionIcons
import com.hermesandroid.bridge.ui.components.PermissionRow
import com.hermesandroid.bridge.ui.components.ResourceIcon
import com.hermesandroid.bridge.ui.components.SectionTitle
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.components.LanguageSettingsCard
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
 *
 * W15 (Sir-Fixliste 9+8): Route 0 ist das STATUS-DASHBOARD
 * (ui/dashboard/DashboardScreen.kt — Agenten-Slots mit Live-Status, alte
 * Start-Elemente als „Verbindung“-Karte) und die Navigation ist eine echte
 * TAB-LEISTE in Sirs Tab-Order [Dashboard | Chat | Agenten | E2E] (Routes
 * 0/2/4/5). Die früheren Einstellungen-Gruppen „Agenten“ und
 * „Verschlüsselung“ sind als eigene Tabs nach vorn gewandert (gleiche
 * Composables + SSOT, keine Kopie); Einstellungen (1) und Impressum (3)
 * bleiben Sprünge oben rechts. „Setup überspringen“ startet IMMER auf dem
 * Dashboard (Fix 8), der Wizard-Abschluss bleibt direkt im Chat.
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
    // W15: status/statusMessage/der Verbindungszustand leben jetzt im
    // Dashboard (ui/dashboard/DashboardScreen.kt sammelt selbst); serverUrl
    // bleibt hier — das URL-Feld der Einstellungen teilt sich dieselbe SSOT.
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
    // Dependency): 0=Dashboard, 1=Einstellungen, 2=Chat, 3=Impressum/
    // Datenschutz, 4=Agenten-Tab, 5=E2E-Tab (W15). Back-Taste = Route
    // zurück aufs Dashboard.
    // W4-D (A6): initialRoute=2 nach der Wizard-Fingerprint-Bestätigung —
    // „danach direkt im Chat“; normale Starts starten auf 0 (Dashboard).
    // W15 (Fix 8): Setup ÜBERSPRINGEN startet IMMER auf 0 (Dashboard),
    // nie im Chat — der Skip-Pfad setzt onboardingJustDone nicht mehr.
    var route by rememberSaveable {
        // WELLE 13 P10: ASSISTENTEN-Start = direkt in den Chat (Voice-Verband
        // folgt im ChatScreen) — sonst Standart-Start bei Route 0.
        mutableStateOf(if (com.hermesandroid.bridge.AssistLaunch.pendingVoice) 2 else initialRoute)
    }
    BackHandler(enabled = route != 0) { route = 0 }

    // ── PH1-T2: Einstellungen-Accordion — je Gruppe ein EIGENER Klappe-
    // Zustand (rememberSaveable: überlebt Route-Wechsel und Prozess-Restore;
    // bewusst getrennte Flags, damit mehrere Gruppen offen sein können).
    var settingsServerOpen by rememberSaveable { mutableStateOf(true) }
    var settingsAppOpen by rememberSaveable { mutableStateOf(false) }
    // W15: die Gruppen „Agenten“ (Route 4) und „Verschlüsselung“ (Route 5)
    // sind eigene TABS geworden — ihre settingsAgentOpen/settingsCryptoOpen-
    // Klappe-Zustände entfallen hier (der Agenten-Tab trägt seine eigenen).
    // PH-2X X-1: Gruppe 4 „App-Sperre“ — append-only, startet zu.
    var settingsSecurityOpen by rememberSaveable { mutableStateOf(false) }
    // W4 (App-Lane C): Gruppe „Sprache“ (DE/EN) — append-only, startet zu.
    var settingsLanguageOpen by rememberSaveable { mutableStateOf(false) }
    // ── PH1-T5: je Fähigkeiten-Gruppe ein eigener Klappe-Zustand (im
    // Agenten-Tab, Route 4); die invasive Gruppe startet bewusst zu.
    var capGroupAnsehenOpen by rememberSaveable { mutableStateOf(true) }
    var capGroupBedienenOpen by rememberSaveable { mutableStateOf(true) }
    var capGroupSchreibenOpen by rememberSaveable { mutableStateOf(true) }
    var capGroupInvasivOpen by rememberSaveable { mutableStateOf(false) }

    // ── W15 (Sir-Fixliste 9): Tap auf einen Agenten-Slot im Dashboard
    // öffnet die Conversation dieses Agenten — einmalig (der Konsum-
    // Callback aus dem ChatScreen räumt den Merker direkt ab, ein erneuter
    // Chat-Tab-Besuch landet wieder auf der Agenten-Liste).
    var pendingChatAgentId by rememberSaveable { mutableStateOf<String?>(null) }

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
            Toast.makeText(context, context.getString(R.string.toast_record_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.toast_record_denied), Toast.LENGTH_SHORT).show()
        }
        BridgeStatus.refreshConfig()
    }

    // ── Benachrichtigungen (Android 13+ Runtime-Permission) ────────────────
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) context.getString(R.string.toast_notifications_granted)
            else context.getString(R.string.toast_notifications_denied),
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
                    context.getString(R.string.toast_health_granted, granted.size),
                    Toast.LENGTH_SHORT,
                ).show()
            granted.isNotEmpty() -> {
                val fehlt = requested.filter { it !in granted }
                    .joinToString { HealthDataBridge.metricLabel(it) }
                Toast.makeText(context, context.getString(R.string.toast_health_partial, fehlt), Toast.LENGTH_LONG).show()
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
                snack(context.getString(R.string.snack_caps_all_on, allCaps.size))
            }
            else -> {
                masterUndoSnapshot = CapabilityMaster.disableAll(allCaps.map { it.id })
                BridgeStatus.refreshConfig()
                scope.launch {
                    val r = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.snack_caps_all_off),
                        actionLabel = context.getString(R.string.action_undo),
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
     * W15 (Sir-Fixliste 9): Tab „Agenten“ (Route 4) — der Master-Schalter +
     * die 4 klapptbaren Fähigkeiten-Gruppen. Der Inhalt ist 1:1 die frühere
     * Einstellungen-Gruppe „Agenten“ (gleiche Composables, gleiche SSOT:
     * CapabilityGate, DisclosureState, BridgeStatus.refreshConfig) — sie ist
     * aus den Einstellungen hierher gewandert, KEINE Kopie.
     */
    @Composable
    fun AgentsContent(padding: PaddingValues) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MasterCapabilityCard(
                enabledCount = enabledIds.size,
                totalCount = allCaps.size,
                onCheckedChange = onMasterCheckedChange,
            )
            SectionTitle(
                stringResource(R.string.caps_section_title),
                sub = stringResource(R.string.caps_section_sub),
            )
            groups.forEachIndexed { index, (titleRes, caps) ->
                CapabilityGroupCard(
                    title = stringResource(titleRes),
                    caps = caps,
                    enabledIds = enabledIds,
                    onToggle = { cap, on ->
                        // v0.12.0 QA-SK-01: die Einzeltoggles MÜSSEN ins Gate
                        // schreiben. PH-7/PS-G-2 (play): sensible Toggles
                        // bekommen VOR dem Einschalten ihre EIGENE Disclosure.
                        if (BuildConfig.EDITION_PLAY && on && cap.id in com.hermesandroid.bridge.policy.SENSITIVE_TOGGLES) {
                            DisclosureState.request(context, cap.id) {
                                CapabilityGate.setEnabled(cap.id, true)
                                BridgeStatus.refreshConfig()
                            }
                        } else {
                            CapabilityGate.setEnabled(cap.id, on)
                            BridgeStatus.refreshConfig()
                        }
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

    /**
     * W15 (Sir-Fixliste 9): Tab „E2E“ (Route 5) — die Ende-zu-Ende-Sicht:
     * Warn-Zeile (XDH fehlt), Abweichungs-Banner (dismissbar) und die
     * Fingerprints je aktiver Sitzung. Inhalt = die frühere Einstellungen-
     * Gruppe „Verschlüsselung“ (CryptoSettingsCard, gleiche SSOT:
     * RelayClient.e2eSessions/e2eBanner) — gewandert, nicht kopiert.
     */
    @Composable
    fun E2eContent(padding: PaddingValues) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CryptoSettingsCard()
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
        fun LegacySettingsGroups() {  // WELLE 13 P2: alter Gruppen-Inhalt im neuen SettingsHome-Rahmen
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Gruppe 1: Server (Relay-Liste, Relay-URL, Kopplungscode, mTLS) ──
            
                AccordionCard(
                    titleRes = R.string.settings_group_server,
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
                        label = { Text(stringResource(R.string.conn_url_label)) },
                        // Bewusst generisch — keine private Domain im Quellcode.
                        placeholder = { Text(stringResource(R.string.conn_url_placeholder)) },
                    )
                    // Kopplungscode: die bestehende PairingCard (Anzeige,
                    // Kopieren mit SENSITIVE-Markierung, Neu-Erzeugen-Dialog).
                    PairingCard(
                        onCopy = { code ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val data = ClipData.newPlainText(context.getString(R.string.pairing_card_title), code)
                            // SENSITIVE-Markierung (API 33+) — die Clipboard-Vorschau
                            // /Klartext-Anzeige unterbleibt.
                            if (Build.VERSION.SDK_INT >= 33) {
                                data.description.extras = android.os.PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            cm.setPrimaryClip(data)
                            snack(context.getString(R.string.snack_code_copied))
                        },
                        onRegenerateRequest = { showRegenDialog = true },
                    )
                    // mTLS: nur Anzeige + Öffnen der BESTEHENDEN MtlsSupport-
                    // Route (Systemdialog via choosePrivateKeyAlias) — keine
                    // neue Logik (PH1-T2-Vorgabe).
                    MtlsCard(configVersion = configVersion, activity = activity)
                }


            // ── Gruppe 2: App (Berechtigungen, Termux, Selbst-Update) ──────
            
                AccordionCard(
                    titleRes = R.string.settings_group_app,
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
                                snack(context.getString(R.string.snack_a11y_first))
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
                                            context.getString(R.string.toast_health_dialog_error, it.javaClass.simpleName),
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
                    // PH-7/PS-G-7 (play): RUN_COMMAND-Permission entfällt —
                    // keine Revival-UI im play-Build (Watchdog ist ebenfalls
                    // play-no-op via BuildConfig in RelayClient).
                    if (BuildConfig.EDITION_GITHUB) AdvancedCard(configVersion)
                    // Selbst-Update: github-Edition aktiv, play-Edition
                    // bewusst disabled mit Hinweis (FLAG_FULL_EDITION).
                    SelfUpdateCard()
                    // Supporter-IAP (PH-9-Route): play = echte Billing-Karte,
                    // github = Stub aus src/github/ (gleiche Signatur).
                    SupporterCard(activity)
                }


            // ── W15 (Sir-Fixliste 9): Gruppe „Agenten“ ist der eigene TAB
            // geworden (Route 4) — derselbe Inhalt (Master-Schalter + die 4
            // Fähigkeiten-Gruppen, gleiche SSOT) lebt jetzt vorne in der
            // Tab-Navigation, nicht mehr als Einstellungs-Accordion.


            // ── Gruppe 4: App-Sperre (PH-2X X-1) — APPEND-ONLY am Ende ────
            
                AccordionCard(
                    titleRes = R.string.settings_group_lock,
                    iconRes = SettingsIcons.security,
                    expanded = settingsSecurityOpen,
                    onExpandedChange = { settingsSecurityOpen = it },
                ) {
                    AppLockSettingsCard()
                }


            // ── W15 (Sir-Fixliste 9): Gruppe „Verschlüsselung“ ist der eigene
            // TAB „E2E“ geworden (Route 5) — Fingerprints + Banner leben
            // jetzt vorne in der Tab-Navigation, nicht mehr als Accordion.


            // ── Gruppe Sprache (W4, App-Lane C) — Einstellungen-Zeile
            // DE/EN: AppLanguage (Prefs „app_language") + AppLanguageGate am
            // Activity-Kompositions-Punkt re-wrapped die App live (kein
            // Activity-Recreate), alle stringResource-Auflösungen wechseln.
            
                AccordionCard(
                    // W15b (Fix D): Sektions-Label als eigene String-Ressource
                    // (common_sprache) — der Top-Bar-Pfad löst sie live auf,
                    // settings_language_title bleibt Titel der Karte darin.
                    titleRes = R.string.common_sprache,
                    iconRes = SettingsIcons.language,
                    expanded = settingsLanguageOpen,
                    onExpandedChange = { settingsLanguageOpen = it },
                ) {
                    LanguageSettingsCard()
                }

        }
    }


    Scaffold(
        containerColor = FlipsiColors.Void,
        topBar = {
            TopAppBar(
                title = {
                    // W15/P7 (Sir): Pfad statt doppelter Titel — im Settings-
                    // Screen zeigt die Top-Bar „Einstellungen › <Sektion>“,
                    // der Screen-Kopf selbst ist titellos.
                    Text(
                        if (route == 1) {
                            SettingsNav.topBarPath(stringResource(R.string.settings_home_title))
                        } else {
                            topBarTitle(route)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
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
                    // WELLE 13 P2 (Sir): Version NICHT dauerhaft sichtbar —
                    // sie lebt ausschliesslich im Settings-About-Info-Sheet.
                    // W15: Chat/Agenten/E2E/Navigation leben in der TAB-Leiste
                    // unten (NavigationBar) — hier bleiben nur Einstellungen
                    // (Zahnrad) und Impressum/Datenschutz (Info) als Sprünge.
                    IconButton(onClick = { route = 1 }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.common_settings),
                            tint = FlipsiColors.TextPrimary,
                        )
                    }
                    IconButton(onClick = { route = 3 }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.common_legal_title),
                            tint = FlipsiColors.TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FlipsiColors.Void),
            )
        },
        // ── W15 (Sir-Fixliste 9): TAB-NAVIGATION — sauber als eigene Leiste
        // in Sirs Tab-Order: Dashboard | Chat | Agenten | E2E. Einstellungen
        // (Route 1) und Impressum (Route 3) bleiben Sprünge oben rechts; auf
        // ihnen ist kein Tab selektiert. Material3-NavigationBar ohne neue
        // Dependency (Compose-BOM), Haus-Farbrollen (Ember = aktiver Tab).
        bottomBar = {
            NavigationBar(
                containerColor = FlipsiColors.Void,
                contentColor = FlipsiColors.TextPrimary,
            ) {
                NavigationBarItem(
                    selected = route == 0,
                    onClick = { route = 0 },
                    icon = { Icon(painterResource(R.drawable.ic_dashboard), contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_tab_dashboard)) },
                    colors = navTabColors(),
                )
                NavigationBarItem(
                    selected = route == 2,
                    onClick = { route = 2 },
                    icon = { Icon(painterResource(NavIcons.chat), contentDescription = null) },
                    label = { Text(stringResource(R.string.common_chat)) },
                    colors = navTabColors(),
                )
                NavigationBarItem(
                    selected = route == 4,
                    onClick = { route = 4 },
                    icon = { Icon(painterResource(SettingsIcons.agent), contentDescription = null) },
                    label = { Text(stringResource(R.string.chat_list_title)) },
                    colors = navTabColors(),
                )
                NavigationBarItem(
                    selected = route == 5,
                    onClick = { route = 5 },
                    icon = { Icon(painterResource(SettingsIcons.crypto), contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_tab_e2e)) },
                    colors = navTabColors(),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // W15: Screen-Route — 0=Dashboard (else-Zweig = defensiver Rückfall
        // auch für ungültige Route-Werte), 1=Einstellungen, 2=Chat,
        // 3=Impressum, 4=Agenten-Tab, 5=E2E-Tab.
        when (route) {
            1 -> SettingsHome(
                padding = padding,
                // WELLE 13 P2: das Redesign-Home (Themen/Accordions/ℹ️) — die
                // bewährten Server/App/Agenten-Gruppen wandern als legacyGroups
                // (gleiche Composables, gleiche SSOT, KEINE neue Logik) hinein;
                // der alte eigene Accordion-Kopf entfällt (SettingsHome ist der
                // neue Rahmen mit ℹ️-About + Rechtliches + Berechtigungen +
                // Standard-Assistent).
                legacyGroups = { LegacySettingsGroups() },
            )
            2 -> ChatScreen(
                padding,
                onOpenSettings = { route = 1 }, // Welle 13: Herz = Einstellungen (Supporter-Karte)
                // W15: Dashboard-Slot-Tap → Conversation des Agenten (einmalig).
                openAgentId = pendingChatAgentId,
                onOpenedAgent = { pendingChatAgentId = null },
            )
            3 -> LegalScreenPlaceholder(padding)
            4 -> AgentsContent(padding)
            5 -> E2eContent(padding)
            else -> DashboardScreen(
                padding = padding,
                serverUrlText = serverUrlText,
                onUrlChange = { serverUrlText = it },
                onConnect = {
                    // W4-D (A7): die Schnellverbindung folgt dem AKTIVEN
                    // Profil — die Liste bleibt wahr (URL+Vault-Spiegel
                    // wandern mit), verbindet wird wie bisher über
                    // RelayClient.connect + SSOT-Code.
                    val url = serverUrlText.trim()
                    if (url.isBlank()) {
                        snack(context.getString(R.string.snack_url_missing))
                    } else {
                        ServerProfiles.syncActiveUrl(url)
                        BridgeConnectionService.start(context)
                        RelayClient.connect(url, PairingManager.getCode())
                    }
                },
                onDisconnect = {
                    RelayClient.disconnect()
                    snack(context.getString(R.string.snack_disconnected))
                },
                enabledCapabilityCount = enabledIds.size,
                totalCapabilityCount = allCaps.size,
                // W15: Tap auf den Agenten-Slot → Chat-Tab mit der
                // Conversation DIESES Agenten (Pending-Merker oben).
                onOpenAgentChat = { agentId ->
                    pendingChatAgentId = agentId
                    route = 2
                },
                onSnack = { snack(it) },
            )
        }
    }

    // ── Dialoge ────────────────────────────────────────────────────────────
    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text(stringResource(R.string.pairing_regen_title)) },
            text = {
                Text(
                    stringResource(R.string.pairing_regen_text),
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
                        snack(context.getString(R.string.snack_code_regen))
                    }
                ) { Text(stringResource(R.string.pairing_regen_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showMasterDialog) {
        AlertDialog(
            onDismissRequest = { showMasterDialog = false },
            // Bestätigungs-Button nennt, was passiert („Alle einschalten",
            // nie „OK") — invasive kommen NAMENTLICH in die Liste.
            title = { Text(stringResource(R.string.caps_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.caps_dialog_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    invasivePending.forEach { cap ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CapIcon(cap.id, tint = FlipsiColors.SignalAmber, size = 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(cap.labelRes), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (BuildConfig.EDITION_PLAY) {
                            stringResource(R.string.caps_dialog_sensitive_play)
                        } else {
                            stringResource(R.string.caps_dialog_sensitive_github)
                        },
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
                            snack(context.getString(R.string.snack_caps_partial, n, allCaps.size))
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                    ) { Text(stringResource(R.string.caps_dialog_without_invasive)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMasterDialog = false
                    CapabilityMaster.enableAll(allCaps.map { it.id }, includeInvasive = true, INVASIVE_CAPS::contains)
                    BridgeStatus.refreshConfig()
                    snack(context.getString(R.string.snack_caps_all_on, allCaps.size))
                }) {
                    Text(stringResource(R.string.caps_dialog_enable_all), color = FlipsiColors.Ember, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMasterDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
            containerColor = FlipsiColors.PanelElev,
        )
    }

    showHealthDialog?.let { cause ->
        val (title, text) = when (cause) {
            HealthDialogCause.NOT_INSTALLED ->
                stringResource(R.string.health_missing_title) to
                    stringResource(R.string.health_missing_text)
            HealthDialogCause.NEEDS_UPDATE ->
                stringResource(R.string.health_update_title) to
                    stringResource(R.string.health_update_text)
            HealthDialogCause.DENIED_OR_SILENT ->
                stringResource(R.string.health_denied_title) to
                    if (Build.VERSION.SDK_INT >= 34)
                        stringResource(R.string.health_denied_text_v34)
                    else
                        stringResource(R.string.health_denied_text_old)
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
                }) { Text(stringResource(R.string.health_open)) }
            },
            dismissButton = {
                TextButton(onClick = { showHealthDialog = null }) { Text(stringResource(R.string.common_later)) }
            },
            containerColor = FlipsiColors.PanelElev,
        )
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
            Text(stringResource(R.string.pairing_card_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.pairing_card_desc),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            Text(
                code,
                style = FlipsiType.Code,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable(onClickLabel = stringResource(R.string.pairing_copy_a11y)) { onCopy(code) },
            )
            Text(
                stringResource(R.string.pairing_change_hint),
                style = MaterialTheme.typography.labelSmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Code-Ereignis = graue Info (Grün = NUR Erfolg, Rot = NUR Ablehnung)
            when (lastEvent) {
                PairingManager.CodeEvent.RESTORED -> Text(
                    stringResource(R.string.pairing_event_restored),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                PairingManager.CodeEvent.REGENERATED -> Text(
                    stringResource(R.string.pairing_event_regenerated),
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
            ) { Text(stringResource(R.string.pairing_regen)) }
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
                    stringResource(R.string.perm_basis_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
                PermissionRow(
                    iconRes = PermissionIcons.accessibility,
                    title = stringResource(R.string.perm_a11y_row_title),
                    description = stringResource(R.string.perm_a11y_row_desc),
                    badge = if (a11yOk) (stringResource(R.string.badge_active) to BadgeState.OK) else (stringResource(R.string.badge_missing) to BadgeState.ERROR),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    // PH-7/PS-G-2 (play): prominente A11y-Disclosure + Consent
                    // VOR dem System-Sprung (GitHub: Direktdurchgriff).
                    DisclosureState.request(context, "a11y") {
                        if (!startFirstResolvable(
                                context, activity,
                                listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                            )
                        ) {
                            BridgeStatus.showText(context.getString(R.string.perm_a11y_fallback))
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.battery,
                    title = stringResource(R.string.perm_battery_row_title),
                    description = stringResource(R.string.perm_battery_row_desc),
                    badge = if (batteryOk) (stringResource(R.string.badge_active) to BadgeState.OK) else (stringResource(R.string.badge_needed) to BadgeState.WARN),
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
                    title = stringResource(R.string.perm_notifications_row_title),
                    description = stringResource(R.string.perm_notifications_row_desc),
                    badge = if (notificationsOk) (stringResource(R.string.badge_active) to BadgeState.OK) else (stringResource(R.string.badge_missing) to BadgeState.ERROR),
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
                    title = stringResource(R.string.perm_autostart_row_title),
                    description = stringResource(R.string.perm_autostart_row_desc, DeviceIntentResolver.currentProfile().oemLabel),
                    badge = stringResource(R.string.badge_check) to BadgeState.WARN,
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
                    stringResource(R.string.perm_data_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
                PermissionRow(
                    iconRes = PermissionIcons.allFiles,
                    title = stringResource(R.string.perm_files_row_title),
                    description = stringResource(R.string.perm_files_row_desc),
                    badge = if (allFilesOk) (stringResource(R.string.badge_active) to BadgeState.OK) else (stringResource(R.string.badge_missing) to BadgeState.ERROR),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    // PH-7/PS-G-2 (play): All-Files-Disclosure VOR dem Special-
                    // Access-Sprung (Console-Erklärung folgt in PH-8).
                    DisclosureState.request(context, "files") {
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
                                context.getString(R.string.perm_files_fallback),
                            )
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.screenCapture,
                    title = stringResource(R.string.perm_record_row_title),
                    description = stringResource(R.string.perm_record_row_desc),
                    badge = if (recordOk) (stringResource(R.string.badge_active) to BadgeState.OK) else (stringResource(R.string.badge_not_active) to BadgeState.OFF),
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    if (!recordOk) onScreenRecord()
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.health,
                    title = stringResource(R.string.perm_health_row_title),
                    description = stringResource(R.string.perm_health_row_desc),
                    badge = when {
                        !healthCapOn -> stringResource(R.string.badge_health_default_off) to BadgeState.OFF
                        healthGrantState == null -> stringResource(R.string.badge_check) to BadgeState.WARN
                        healthGrantState == -1 -> stringResource(R.string.badge_health_missing) to BadgeState.ERROR
                        else -> {
                            val n = healthGrantState ?: 0
                            stringResource(R.string.badge_health_rights, n, HealthDataBridge.readPermissions.size) to
                                if (n == HealthDataBridge.readPermissions.size) BadgeState.OK else BadgeState.WARN
                        }
                    },
                    onAction = PermissionAction.SettingsSprung,
                ) {
                    // PH-7/PS-G-2 + R1.1 (play): Health-Disclosure VOR dem
                    // Health-Connect-Consent-Lauf (Art. 9 = explizite Einwilligung).
                    DisclosureState.request(context, "health") {
                        onHealthGrant()
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                PermissionRow(
                    iconRes = PermissionIcons.overlay,
                    title = stringResource(R.string.perm_overlay_row_title),
                    description = stringResource(R.string.perm_overlay_row_desc),
                    badge = if (overlayOk) (stringResource(R.string.badge_possible) to BadgeState.OK) else (stringResource(R.string.badge_missing) to BadgeState.OFF),
                    onAction = PermissionAction.Toggle(checked = overlayOk),
                    onToggle = { show ->
                        // PH-7/PS-G-2 (play): ehrliche HUD-Disclosure VOR dem
                        // Overlay-Freigabe-Weg (Deceptive-Behavior-policy).
                        fun showOverlayAfterConsent() {
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
                        }
                        if (!show) {
                            showOverlayAfterConsent()
                        } else {
                            DisclosureState.request(context, "overlay") { showOverlayAfterConsent() }
                        }
                    },
                )
            }
        }
    }

    // PH-7/PS-G-2 (play): EIN Host für alle prominenten Offenlegungs-Dialoge.
    DisclosureDialogHost()
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
                title = stringResource(R.string.termux_title),
                description = stringResource(R.string.termux_desc),
                badge = if (enabled) (stringResource(R.string.badge_on) to BadgeState.OK) else (stringResource(R.string.badge_off) to BadgeState.OFF),
                onAction = PermissionAction.Toggle(checked = enabled),
                onToggle = { on ->
                    context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("termux_revival_enabled", on).apply()
                    BridgeStatus.refreshConfig()
                },
            )
            Text(
                stringResource(R.string.termux_hint),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ── PH1-T1: Top-Bar-Titel je Route ──────────────────────────────────────────

/**
 * Titel der Top-Bar je Route (W15/P7: Route 1 = Einstellungen wird NICHT hier
 * behandelt — sie zeigt den Sektions-Pfad via SettingsNav, „Einstellungen › …“).
 * 0 = Dashboard mit dem App-Namen; unbekannte Werte fallen darauf zurück.
 * W15b (Fix B, Sir-Befund): Route 4 (Agenten-Tab) bleibt TITELLOS — das
 * Tab-Label unten trägt den Namen, ein doppelter Titel oben wirkte wie
 * ein Festhängen des Screens.
 */
@Composable
private fun topBarTitle(route: Int): String = when (route) {
    2 -> stringResource(R.string.common_chat)
    3 -> stringResource(R.string.common_legal_title)
    // W15b: Route 4 bewusst leer — Tab-Label reicht (kein Doppel-Titel).
    4 -> ""
    5 -> stringResource(R.string.common_e2e_title)
    // 0 = Dashboard; unbekannte Werte fallen auf den App-Namen zurück.
    else -> "FlipsiBridge"
}

// ── W15: Tab-Navigations-Farben (Haus-Rollen: Ember = aktiv) ─────────────────

/**
 * Farbwerte der Tab-Leiste — aktiver Tab in Ember (EmberDim als Pille), inaktive
 * Tabs in TextSecondary; NIE nur Farbe als Träger: das Label steht immer daneben.
 */
@Composable
private fun navTabColors(): NavigationBarItemColors {
    // Die Werte sind konstant; NavigationBarItemDefaults.colors ist selbst
    // @Composable — ein remember { }-Block darum wäre ein Composable-Aufruf
    // im Non-Composable-Lambda (Compile-Fehler).
    return NavigationBarItemDefaults.colors(
        selectedIconColor = FlipsiColors.Ember,
        selectedTextColor = FlipsiColors.Ember,
        unselectedIconColor = FlipsiColors.TextSecondary,
        unselectedTextColor = FlipsiColors.TextSecondary,
        indicatorColor = FlipsiColors.EmberDim,
    )
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
    // W15b (Fix D): Titel als @StringRes — SettingsNav speichert die ID, der
    // Top-Bar-Pfad löst das Sektions-Label live auf (kein eingefrorener
    // englischer Titel mehr nach Sprach-Rückwechsel, siehe SettingsNav).
    @StringRes titleRes: Int,
    @DrawableRes iconRes: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val title = stringResource(titleRes)
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
                        onValueChange = {
                            // W15/P7: Top-Bar-Pfad — die zuletzt aufgeklappte
                            // Sektion gewinnt (gleicher Vertrag wie SettingsCard).
                            // W15b: ID statt Text (Fix D, siehe SettingsNav).
                            SettingsNav.onSectionToggle(titleRes, it)
                            onExpandedChange(it)
                        },
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
 * mTLS-Zertifikats-Bereich (PH1-T2): Statusanzeige + Öffnen der BESTEHENEN
 * MtlsSupport-Route (KeyChain-Systemdialog via choosePrivateKeyAlias) —
 * bewusst KEINE neue Logik, nur Sichtbarmachung.
 */
@Composable
private fun MtlsCard(configVersion: Int, activity: Activity?) {
    val context = LocalContext.current
    val configured = remember(configVersion) { MtlsSupport.isConfigured() }
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.mtls_title), fontWeight = FontWeight.SemiBold)
            Text(
                if (configured)
                    stringResource(R.string.mtls_configured)
                else
                    stringResource(R.string.mtls_not_configured),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    if (activity != null) {
                        MtlsSupport.chooseCertificate(activity)
                    } else {
                        BridgeStatus.showText(context.getString(R.string.mtls_unavailable))
                    }
                },
                modifier = Modifier.padding(top = 10.dp).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
            ) { Text(if (configured) stringResource(R.string.mtls_choose_other) else stringResource(R.string.mtls_choose)) }
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
            Text(stringResource(R.string.update_title), fontWeight = FontWeight.SemiBold)
            Text(
                if (BuildConfig.FLAG_FULL_EDITION)
                    stringResource(R.string.update_github_desc)
                else
                    stringResource(R.string.update_play_desc),
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
            ) { Text(stringResource(R.string.update_search)) }
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
        // W15: Text als Ressource (DE/EN) statt E2eUi-Konstante.
        if (!CryptoEngine.xdhAvailable) {
            Card(
                colors = CardDefaults.cardColors(containerColor = FlipsiColors.SignalRed.copy(alpha = 0.16f)),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    stringResource(R.string.e2e_xdh_unavailable),
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
                            contentDescription = stringResource(R.string.crypto_banner_close_a11y),
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
                Text(stringResource(R.string.crypto_title), fontWeight = FontWeight.SemiBold)
                Text(
                    if (e2eSessions.isEmpty())
                        stringResource(R.string.crypto_no_session)
                    else
                        stringResource(R.string.crypto_active_session),
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
                        stringResource(R.string.crypto_session_fp, session.agentId),
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
                        stringResource(R.string.crypto_agent_fp),
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
                        stringResource(R.string.crypto_compare_hint),
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
        SectionTitle(stringResource(R.string.legal_imprint_title))
        LegalSectionCard()
        SectionTitle(stringResource(R.string.legal_privacy_title))
        LegalSectionCard()
        SectionTitle(stringResource(R.string.legal_terms_title))
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
                stringResource(R.string.legal_placeholder_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextSecondary,
            )
            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = FlipsiColors.LineStrong,
            )
            Text(
                stringResource(R.string.legal_placeholder_text),
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
