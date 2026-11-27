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
// W24 (Lane B): die Rail-Fläche füllt die Content-Höhe (Scaffold-Nachlauf).
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
// W24 (Lane B): die Agenten-Wahl der 2-Spalten-Chat-Verbindung (Own-Fläche
// auf der RelayClient.agents-SSOT, ChatScreen unberührt).
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
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
// W24 (Lane B): die Rail-Fläche je Falt-Verband (medium/Handy-Landschaft/
// expanded) — Parität der Bottom-Bar-Items auf der Schiene.
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
// W24 (Lane B, T7-F1): der Pane-Holder der je-Zweig Provider (kein Top-Level-
// SaveableStateProvider je dieser compose-Faser — holder-Schiene).
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
// W24 (Lane B): Rail-Wurzel als ANGESAGTE Fläche (rail_a11y je Brief).
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
// W24 (Lane B): Namen-Überlauf je Wahl-Reih (nie abschneiden am Balken).
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.billing.SupporterCard
import com.hermesandroid.bridge.policy.DisclosureState
import com.hermesandroid.bridge.policy.DisclosureDialogHost
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.HealthDataBridge
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.client.AgentDoneNotifyRules  // W23-11-App TEIL 3: Fertigmeldungs-Toggles (Prefs-SSOT)
import com.hermesandroid.bridge.crypto.CryptoEngine
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.overlay.StatusOverlay
import com.hermesandroid.bridge.permissions.DeviceFeature
import com.hermesandroid.bridge.permissions.DeviceIntentResolver
import com.hermesandroid.bridge.security.AppLockSettingsCard
import com.hermesandroid.bridge.security.CapabilityGate
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.ui.chat.ChatScreen
import com.hermesandroid.bridge.ui.dashboard.DashboardScreen
import com.hermesandroid.bridge.ui.cron.CronScreen  // W23-11-App: 5. Tab „Geplante Aufgaben“ (Route 6)
import com.hermesandroid.bridge.ui.settings.AboutSheetHost
import com.hermesandroid.bridge.ui.settings.AboutState
import com.hermesandroid.bridge.security.MtlsSupport
import com.hermesandroid.bridge.ui.settings.PairingPasswordCard
import com.hermesandroid.bridge.ui.settings.PairingPasswordPromptFlow
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
// W24 (Lane B): Konsum der Lane-A-Lieferung — LocalWindowSizeClass am
// setContent-Wurzel-Provider (MainActivity, calculateWindowSizeClass) +
// WindowFoldRules = die EINE Falt-Fibel (useRail/useTwoPane/Content-Cap).
import com.hermesandroid.bridge.ui.rules.LocalWindowSizeClass
import com.hermesandroid.bridge.ui.rules.SnackContrastRules
import com.hermesandroid.bridge.ui.rules.WindowFoldRules
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
 * W15 (Fixliste 9+8): Route 0 ist das STATUS-DASHBOARD
 * (ui/dashboard/DashboardScreen.kt — Agenten-Slots mit Live-Status, alte
 * Start-Elemente als „Verbindung“-Karte) und die Navigation ist eine echte
 * TAB-LEISTE in Besitzers Tab-Order [Dashboard | Chat | Agenten | E2E] (Routes
 * 0/2/4/5). Die früheren Einstellungen-Gruppen „Agenten“ und
 * „Verschlüsselung“ sind als eigene Tabs nach vorn gewandert (gleiche
 * Composables + SSOT, keine Kopie); Einstellungen (1) und Impressum (3)
 * bleiben Sprünge oben rechts. „Setup überspringen“ startet IMMER auf dem
 * Dashboard (Fix 8), der Wizard-Abschluss bleibt direkt im Chat.
 *
 * W24 (Lane B, Tablet-Verband): die Falt-Konsum-Ebene — BridgeScreen liest die
 * Lane-A-Lieferung (LocalWindowSizeClass → WindowFoldRules) EINMAL je
 * Komposition (KEINE zweite Faltung, null = fail-soft Bestand) und faltet die
 * Navigation: NavigationRail (BridgeNavRail) je medium/Handy-Landschaft-Verband
 * mit den 5 echten Tabs (Bestand-Keys, rail_a11y nur je Fläche), je EXPANDED
 * bleibt das Rail IMMER (T7-F2-Konsum). Das W16-Gate (route==2 &&
 * chatConversationOpen) versteckt die Navigation NUR noch je Handy-Portrait-
 * Bestand (bottomBarHost — T7-F2-Zweig-Trennung). Je EXPANDED lebt der Chat
 * als 2-Spalten-Verband (AgentListPane ~300dp + Conversation-Kappe 640dp,
 * ChatScreen UNBERÜHRT — Lane-D-Zone), und JE Route-Zweig läuft hinter einem
 * EIGENEN SaveableStateProvider (Pane-Holder je stabilem Content-Key —
 * T7-F1: kein Rail-/Tab-Wechsel wirft den rememberSaveable-Stand fort).
 */

private enum class HealthDialogCause { NOT_INSTALLED, NEEDS_UPDATE, DENIED_OR_SILENT }

// ── W24 (Lane B, T7-F1): je Route-Zweig ein EIGENER SaveableStateProvider ───
// Der Rail-/Tab-Wechsel LÖST den Route-Zweig aus der Komposition (und der
// Falt-Wechsel Rail↔Bottom-Bar wandert den Zweig in der Layout-Hülle) —
// ohne Provider stirbt der rememberSaveable-Stand je verlassene Faser
// (Klappen, Composer, Scroll, offen gemeldete Sheet-/Dialog-Merker). Der
// Pane-Holder speichert ihn je STABILEM Pane-Key und setzt ihn je Rück-Wechsel
// fort; die Keys sind bewusst content-fest (nicht route-abhängig), also
// identisch je Falt-Verband. Diese compose-Faser kennt keinen Top-Level-
// SaveableStateProvider — die holder-Schiene (rememberSaveableStateHolder
// → holder.SaveableStateProvider) ist derselbe Verband.
private const val W24_PANE_KEY_DASH = "bridge-pane-dash"
private const val W24_PANE_KEY_SETTINGS = "bridge-pane-settings"
private const val W24_PANE_KEY_CHAT = "bridge-pane-chat"
private const val W24_PANE_KEY_CHAT_EXPANDED = "bridge-pane-chat-expanded"
private const val W24_PANE_KEY_AGENTS = "bridge-pane-agents"
private const val W24_PANE_KEY_E2E = "bridge-pane-e2e"
private const val W24_PANE_KEY_CRON = "bridge-pane-cron"

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
fun BridgeScreen(
    initialRoute: Int = 0,
    // W28 (Lane B, Toast-Räumung): EIN-Slot-Merker für Zustell-Ergebnisse aus
    // der ACTIVITY-Ebene (Share-Dialog-G11 — dort lebt kein Snackbar-Host);
    // der Konsum-Effekt unten meldet ihn an DIE eine W19-Zentral-Snackbar und
    // räumt ihn je Merker (kein Wiederhol-Snack je Recomposition).
    pendingSnack: String? = null,
    onSnackConsumed: (String) -> Unit = {},
) {
    val context = LocalContext.current
    // W4 (App-Lane C): Activity-Quelle über die CompositionLocal — der
    // AppLanguageGate-Wrapped-Context ist KEIN Activity-Context (context as?
    // Activity wäre bei DE/EN-Wahl null und würde ALLE Berechtigungs-Sprünge
    // killen). Der Gate liefert die ORIGINAL Activity (AppLanguage.LocalAppActivity).
    val activity = com.hermesandroid.bridge.i18n.AppLanguage.LocalAppActivity.current
    // W15: status/statusMessage/der Verbindungszustand leben jetzt im
    // Dashboard (ui/dashboard/DashboardScreen.kt sammelt selbst).
    // W16 P21-Nachtrag: serverUrlText/serverUrl-Sammel-Faden ENTFERNT —
    // das extra „Server-Adresse“-Feld unter der Server-Liste (der einzige
    // Konsument) fliegt mit weg; Server verwalten sich NUR über die
    // Profil-Liste (RelayServerListCard/connectViaProfile).
    val configVersion by BridgeStatus.configVersion.collectAsState()
    // PH-3 F6: aktive E2E-Sessions für die Header-Kleppe (sichtbarer
    // Verschlüsselungs-Zustand statt unsichtbarem Legacy-Klartext).
    val e2eSessions by RelayClient.e2eSessions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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

    // ── W15 (Fixliste 9): Tap auf einen Agenten-Slot im Dashboard
    // öffnet die Conversation dieses Agenten — einmalig (der Konsum-
    // Callback aus dem ChatScreen räumt den Merker direkt ab, ein erneuter
    // Chat-Tab-Besuch landet wieder auf der Agenten-Liste).
    var pendingChatAgentId by rememberSaveable { mutableStateOf<String?>(null) }

    // W26 (Agenten-Zentrale): der Cron-Sprung je Agent-Kachel — der
    // ausgewählte Agent ist die cron.remove/pause-Adresse des Tabs
    // (Pending-Verband wie der Chat-Sprung; je Konsumierung geräumt).
    var pendingCronAgentId by rememberSaveable { mutableStateOf<String?>(null) }

    // W24 (Lane B, 2-Spalten-Verband): Spiegel-Merker der Agenten-Wahl — der
    // ausgewählte Stand der Wahl-Fläche links am Chat. Ehrliche Grenze:
    // ChatScreen hält die WAHRE selectedAgentId INTERN (Lane-D-Zone unberührt),
    // der Spiegel korrigiert sich je Wahl-Tap selbst (ein Listen-Tap rechts
    // zeigt bis zum nächsten Wahl-Tap einen älteren Balken — HOOK-BEDARF je
    // Lane-D-Report: onSelectedAgent-Rück-Verband = wahre Spiegel-Ebene).
    var chatAgentChoice by rememberSaveable { mutableStateOf<String?>(null) }


    // W16 (Fixliste 15): „Conversation offen“ der Chat-Route — die
    // NavigationBar unten verschwindet, SOLANGE eine Conversation mit einem
    // Agenten offen ist (mehr Platz für Bubbles + Composer) und kommt ZURÜCK,
    // sobald man die Conversation verlässt (Liste/Tab). ChatScreen meldet
    // den vorzeichenfesten Stand (AttachSheetRules-Verband) je Composition
    // per SideEffect; hier lebt nur der gespiegelte Schalter.
    var chatConversationOpen by rememberSaveable { mutableStateOf(false) }
    // Verband: der Open-Flag gehört ZUR Route 2 — ein Sprung auf eine andere
    // Route räumt ihn ab, damit auf fremden Routen nie eine versteckte Leiste
    // hängt (ChatScreen meldet beim nächsten Verband-Besuch ohnehin frisch).
    LaunchedEffect(route) {
        if (route != 2 && chatConversationOpen) {
            chatConversationOpen = false
        }
    }

    // ── W24 (Lane B): KONSUM-SEITE der Lane-A-Lieferung (Grundlagen 4/6) ──
    // EIN Konsum-Punkt: die MainActivity-Lieferung (LocalWindowSizeClass,
    // null = fail-soft für Alt-Kontexte/Previews ohne Provider) faltet über
    // WindowFoldRules.compose zu EINEM Falt-Verband; die je-Route-Resolvers
    // von WindowFoldRules (chatTwoPane/settingsContentCapDp/cronContentCapDp)
    // sind die EINEN Verbands-Richter — BridgeScreen erfindet KEINE zweite
    // Faltung, KEIN remember-Wrapper (Lane-A-Befund T1/T7: Configuration-
    // Sprünge = Activity-Recreate → frische Klasse je Provider).
    val windowClass = LocalWindowSizeClass.current
    val fold = windowClass?.let {
        WindowFoldRules.compose(it.widthSizeClass, it.heightSizeClass)
    }
    // T7-F2 (Rail-Konsum): der Lane-A-Merker trägt EXPANDED bewusst NICHT
    // (dort lebt useTwoPane) — die Nav-Fläche entscheidet der Rail-Konsum:
    // das Rail bleibt je Rail-Verband UND je Expanded IMMER stehen (Brief
    // „expanded = Rail bleibt IMMER“); nur die kompakten Portrait-Fälle
    // (Rail-Merker aus, TwoPane aus) behalten die Bottom-Bar.
    // W28 (Lane B, Inset-SSOT): bottomBarHost je Rail-Verband folgt der
    // EINEN Faser ([WindowFoldRules.bottomBarHost]) — dieselbe Regel lesen
    // Dashboard/Settings bei ihrem Boden-Padding (kein zweiter Verbands-
    // Richter je Konsum-Stelle, NO-DOPPEL).
    val bottomBarHost = WindowFoldRules.bottomBarHost(fold)
    val railHost = !bottomBarHost
    // Bottom-Bar-Pfad = alles ohne Rail-Fläche; das W16-Gate (route==2 &&
    // chatConversationOpen) gilt bewusst NUR diesem Pfad (T7-F2-Zweig).
    // Chat-Zwei-Spalt-Verband über Resolver (null-fold = Bestand einspaltig).
    // Settings/Cron-Kappen konsumiert Lane C IN DEN Screen-Fibern selbst
    // (3861d9f) — BridgeScreen packt KEINE zweite Kappe darüber.
    val chatTwoPane = fold?.let(WindowFoldRules::chatTwoPane) ?: false

    // W19 (Apple-Audit): Snackbar ist das EINHEITLICHE Feedback — kein
    // Toast/Snackbar-Mix mehr. long = die früheren Toast.LENGTH_LONG-Fälle
    // (Health partial; Text mit Liste braucht mehr Lesezeit).
    fun snack(text: String, long: Boolean = false) {
        scope.launch {
            snackbarHostState.showSnackbar(
                text,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }

    // ── W28 (Lane B, Toast-Räumung): Activity-Zustell-Ergebnisse ────────────
    // (Share-Dialog, Mount außerhalb von BridgeScreen) laufen über den
    // pendingSnack-Merker in DIE eine Snackbar — konsumieren und RÄUMEN je
    // Merker-Wert (kein Wiederhol-Snack je Recomposition; die laufende
    // showSnackbar-Koroutine lebt am HostState und überlebt die Räumung).
    LaunchedEffect(pendingSnack) {
        pendingSnack?.let { text ->
            snack(text)
            onSnackConsumed(text)
        }
    }

    // ── Bildschirmaufnahme: MediaProjection-Consent ────────────────────────
    val screenRecordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        if (ok && result.data != null) {
            ScreenRecorder.setProjectionPermission(result.resultCode, result.data!!)
            // W19: Grant-Feedback über die Snackbar (einheitlich mit feedback.md).
            snack(context.getString(R.string.toast_record_granted))
        } else {
            snack(context.getString(R.string.toast_record_denied))
        }
        BridgeStatus.refreshConfig()
    }

    // ── Benachrichtigungen (Android 13+ Runtime-Permission) ────────────────
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // W19: Snackbar statt Toast (einheitliches Feedback).
        snack(
            if (granted) context.getString(R.string.toast_notifications_granted)
            else context.getString(R.string.toast_notifications_denied),
        )
        BridgeStatus.refreshConfig()
    }

    // ── Health Connect: Consent-Dialog (nur lesen, differenziert) ──────────
    val healthLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val requested = HealthDataBridge.readPermissions
        when {
            granted.containsAll(requested) ->
                // W19: Snackbar statt Toast (einheitliches Feedback).
                snack(context.getString(R.string.toast_health_granted, granted.size))
            granted.isNotEmpty() -> {
                val fehlt = requested.filter { it !in granted }
                    .joinToString { HealthDataBridge.metricLabel(it) }
                snack(context.getString(R.string.toast_health_partial, fehlt), long = true)
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

    /**
     * W16 P11 (Interaction-Einzeltoggle ließ sich nicht wiedereinschalten
     * — nur der Master-AN wirkte): EIN gekoppelter Write-Pfad für die
     * Capability-Kette. Gate-Write und der sofortige refreshConfig-Bump sind
     * UNTERTEILBAR — nach einem Einzel-AN ist enabledIds (remember
     * (configVersion)) im selben Bump frisch gelesen: kein Stale-Disabled-
     * Zustand in der Zeile, der Master-Schalter rechnet den Einzeltoggle mit
     * (enabledCount) und der /open_app-Pfad sieht die Capability sofort.
     * Kein Aufrufer darf die Hälfte vergessen — deshalb existiert die
     * Kette nur HIER (Master-, Toggles-, Health- und Undo-Pfade rufen an).
     */
    fun writeCapabilityAndRefresh(capId: String, on: Boolean) {
        CapabilityGate.setEnabled(capId, on)
        BridgeStatus.refreshConfig()
    }

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
     * W15 (Fixliste 9): Tab „Agenten“ (Route 4) — der Master-Schalter +
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
                // W28 (Lane F, Reviewer-B-Auflage P2): Boden NUR je Zustand
                // OHNE Leistenslot — dort hebt das Scaffold den Boden NICHT
                // (contentWindowInsets = 0) und die letzten Karten endeten
                // hart am Fensterboden IN der Gesture-Säule (K4-Verstoß,
                // play-36 Rail-Boden = 48 dp + navigationBars via SSOT-Helfer).
                // Sitzt die Leiste (kompaktes Portrait), trägt SIE den Boden —
                // ein Extra läge doppelt; github-34 = 0-dp-Delta bleibt.
                .then(
                    if (WindowFoldRules.bottomBarHost(fold)) {
                        Modifier
                    } else {
                        Modifier.padding(
                            bottom = WindowFoldRules.bottomBarFloorPadding(fold),
                        )
                    }
                )
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
                        // W16 P11: alle Pfade gehen durch
                        // writeCapabilityAndRefresh (Gate-Write + Bump
                        // unterteilbar) — nach Einzel-AN wirkt der UI-Status
                        // sofort, kein totes TAN mehr über den Einzeltoggle.
                        if (BuildConfig.EDITION_PLAY && on && cap.id in com.hermesandroid.bridge.policy.SENSITIVE_TOGGLES) {
                            DisclosureState.request(context, cap.id) {
                                writeCapabilityAndRefresh(cap.id, true)
                            }
                        } else {
                            writeCapabilityAndRefresh(cap.id, on)
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
     * W15 (Fixliste 9): Tab „E2E“ (Route 5) — die Ende-zu-Ende-Sicht:
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
                // W28 (Lane F, Reviewer-B-Auflage P2): Boden NUR je Zustand
                // OHNE Leistenslot — dieselbe Faser wie der Agenten-Tab (K4:
                // play-36 Rail-Boden = 48 dp + navigationBars via SSOT-Helfer);
                // mit Leiste trägt SIE den Boden, github-34 = 0-dp-Delta.
                .then(
                    if (WindowFoldRules.bottomBarHost(fold)) {
                        Modifier
                    } else {
                        Modifier.padding(
                            bottom = WindowFoldRules.bottomBarFloorPadding(fold),
                        )
                    }
                )
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
     * überlebt Route-Wechsel): Server (Relay-URL, Kopplungscode),
     * App (Berechtigungen inkl. Autostart/Akku, Termux, Selbst-Update je
     * Edition) und Agenten (Master-Schalter + 4 klapptbare Fähigkeiten-
     * Gruppen, PH1-T5). Die langen Listen aus dem alten Home — gleiche
     * Composables, gleiche SSOT (CapabilityGate, Prefs), KEINE neue Logik;
     * W16 P21: das serverUrlText-Sammelfeld ist entfallen (Profil-Liste ist
     * die einzige Server-Verwaltung).
     */
    @Composable
        fun LegacySettingsGroups() {  // WELLE 13 P2: alter Gruppen-Inhalt im neuen SettingsHome-Rahmen
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Gruppe 1: Server (Relay-Liste, Relay-URL, Kopplungscode) ──
            
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
                    // W16 P21-Nachtrag: das extra „Server-Adresse“-Feld
                    // (serverUrlText) ENTFERNT — es zeigte/schrieb denselben
                    // Wert wie die aktive Kachel oben (Redundanz). Server
                    // hinzufügen = „+ Server hinzufügen“, Ändern = Stift an
                    // der Kachel; Connect/Wechsel = Profil-Liste
                    // (connectViaProfile). Kein toter Code-Faden: der
                    // serverUrlText-Status ist mit dem Feld gone.
                    // W16 (Fixliste 17): das bewusste Trennen lebt jetzt HIER
                    // (Einstellungen › Server) — die alte Verbindungskarte am
                    // Dashboard ist entfallen; gleiche Aktion/Faser wie deren
                    // Trennen-Button (RelayClient.disconnect + Snack).
                    // W16.2 (P39, 16.11.): die Taste FOLGT dem LIVE-Zustand
                    // (BridgeStatus.state) — verbunden = „Trennen" (rot),
                    // getrennt = „Verbinden" (neutral; Re-Connect aufs AKTIVE
                    // Profil via connectViaProfile). Vorher: dauerhaft
                    // „Trennen" — nach dem Trennen eine Sackgasse ohne
                    // sichtbare Verbinden-Fläche. „Verbinde …" (disabled)
                    // solange der Client selbst CONNECTED/connectiert.
                    val connState by BridgeStatus.state.collectAsState()
                    val connConnected = connState is BridgeStatus.ConnectionState.CONNECTED
                    val connInFlight = connState is BridgeStatus.ConnectionState.CONNECTING ||
                        connState is BridgeStatus.ConnectionState.RECONNECTING
                    OutlinedButton(
                        onClick = {
                            if (connConnected) {
                                RelayClient.disconnect()
                                snack(context.getString(R.string.snack_disconnected))
                            } else {
                                val activeProfileId = ServerProfiles.activeId.value
                                if (activeProfileId.isNullOrBlank()) {
                                    snack(context.getString(R.string.server_no_active_snack))
                                } else {
                                    ServerProfiles.connectViaProfile(context, activeProfileId)
                                    snack(context.getString(R.string.snack_connecting))
                                }
                            }
                        },
                        enabled = !connInFlight,
                        modifier = Modifier.fillMaxWidth(),
                        // Destruktive Richtung (Trennen) = dezent rot; Connect-
                        // Richtung = Ember (positiver Weg, Muster Nav-Tabs).
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (connConnected) FlipsiColors.SignalRedText
                            else FlipsiColors.Ember,
                        ),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    connInFlight -> R.string.conn_connecting
                                    connConnected -> R.string.conn_disconnect
                                    else -> R.string.conn_connect
                                },
                            ),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    // W17-F2 (Besitzer-Idee, Umfang FIX): Optionales Kopplungs-
                    // Passwort — Schalter + maskiertes Feld (min 8) +
                    // „Passwortschutz aktiv/inaktiv“ (Wahrheit = /password_status).
                    PairingPasswordCard(
                        onSnack = { message -> snack(message) },
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
                    // mTLS (W16.3, Fixliste 41): Karte zeigt den app-verwalteten
                    // Device-Leg + Enrollment-Button (Server stellt das
                    // Zertifikat aus). Der tote KeyChain-Systemdialog ist rauf —
                    // KeyChain bleibt nur als Fallback in MtlsSupport.decorate().
                    // P42: Ausgänge kommen als Snack am Bildschirm (Einstellungen
                    // rendert die Bridge-Statuszeile nie — s. MtlsResultRules-Doku).
                    MtlsCard(configVersion = configVersion, onResult = { message -> snack(message) })
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
                            // W16 P11: Write+Bump über die EINE Kette.
                            writeCapabilityAndRefresh("health", true)
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
                    // ── W23-11-App (TEIL 3): Fertigmeldungen der Agenten ──
                    // Zwei Toggles (Default beides AN — Brief) je Stufe:
                    // Sub-Agent fertig (agent.subagent_done) + Agent fertig
                    // (agent.idle_transition). SSOT = [AgentDoneNotifyRules]
                    // keys/marks (RelayClient notifiziert nach denselben
                    // Prefs); Write + Bump unterteilt (W16-P11-Muster).
                    Card(
                        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.settings_notify_group),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                            )
                            val notifySubagentOn = remember(configVersion) {
                                runCatching {
                                    context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                                        .getBoolean(AgentDoneNotifyRules.PREF_ENABLE_SUBAGENT, AgentDoneNotifyRules.DEFAULT_ENABLED)
                                }.getOrDefault(AgentDoneNotifyRules.DEFAULT_ENABLED)
                            }
                            val notifyAgentOn = remember(configVersion) {
                                runCatching {
                                    context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                                        .getBoolean(AgentDoneNotifyRules.PREF_ENABLE_AGENT, AgentDoneNotifyRules.DEFAULT_ENABLED)
                                }.getOrDefault(AgentDoneNotifyRules.DEFAULT_ENABLED)
                            }
                            PermissionRow(
                                iconRes = PermissionIcons.subagentDone,
                                title = stringResource(R.string.settings_notify_subagent_title),
                                description = stringResource(R.string.settings_notify_subagent_sub),
                                badge = if (notifySubagentOn) (stringResource(R.string.badge_on) to BadgeState.OK)
                                else (stringResource(R.string.badge_off) to BadgeState.OFF),
                                onAction = PermissionAction.Toggle(checked = notifySubagentOn),
                                onToggle = { on ->
                                    context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                                        .edit()
                                        .putBoolean(AgentDoneNotifyRules.PREF_ENABLE_SUBAGENT, on)
                                        .apply()
                                    BridgeStatus.refreshConfig()
                                },
                            )
                            androidx.compose.material3.HorizontalDivider(color = FlipsiColors.LineStrong, modifier = Modifier.padding(start = 52.dp))
                            PermissionRow(
                                iconRes = PermissionIcons.agentDone,
                                title = stringResource(R.string.settings_notify_agent_title),
                                description = stringResource(R.string.settings_notify_agent_sub),
                                badge = if (notifyAgentOn) (stringResource(R.string.badge_on) to BadgeState.OK)
                                else (stringResource(R.string.badge_off) to BadgeState.OFF),
                                onAction = PermissionAction.Toggle(checked = notifyAgentOn),
                                onToggle = { on ->
                                    context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                                        .edit()
                                        .putBoolean(AgentDoneNotifyRules.PREF_ENABLE_AGENT, on)
                                        .apply()
                                    BridgeStatus.refreshConfig()
                                },
                            )
                        }
                    }
                    // Termux-Wiederanlauf (ehem. „Erweitert", unverändert).
                    // PH-7/PS-G-7 (play): RUN_COMMAND-Permission entfällt —
                    // keine Revival-UI im play-Build (Watchdog ist ebenfalls
                    // play-no-op via BuildConfig in RelayClient).
                    if (BuildConfig.EDITION_GITHUB) AdvancedCard(configVersion)
                    // W16 (Punkt 26): SelfUpdateCard entfällt — kein In-App-
                    // Install mehr; der Update-Check läuft als Info-Push über
                    // UpdateCheck (App-Start + Tagesrhythmus), ohne UI-Karte.
                    // W16 (Fixliste 34): Supporter-IAP (PH-9-Route) PLAY-ONLY —
                    // die GitHub-Edition hat KEINE Play-Billing-Route und damit
                    // bewusst KEINE Karte in Einstellungen (auch kein leerer
                    // Stub-Aufruf); play zeigt die Karte hier UND direkt im
                    // Unterstützen-Sheet des Chat-Herzens.
                    if (BuildConfig.EDITION_PLAY) SupporterCard(activity)
                }


            // ── W15 (Fixliste 9): Gruppe „Agenten“ ist der eigene TAB
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


            // ── W15 (Fixliste 9): Gruppe „Verschlüsselung“ ist der eigene
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

    /**
     * W24 (Lane B): die CHAT-Fläche — EINE Aufruf-Faser für beide Falt-Zweige:
     * der ChatScreen-Verband (pendingChatAgentId-Seed, Open-Meldung) läuft
     * 1:1 wie der Handy-Bestand; der TwoPane-Zweig umhüllt NUR.
     */
    @Composable
    fun ChatPane(padding: PaddingValues) {
        ChatScreen(
            padding,
            openAgentId = pendingChatAgentId,
            onOpenedAgent = { pendingChatAgentId = null },
            onConversationOpen = { open -> chatConversationOpen = open },
            // W24-Spiegel-Hook (Jäger-2-Hinweis A, 24.11.2026): die rechte
            // Conversation meldet die wahre Auswahl (Wechsel im Gespräch,
            // Back-Schluss) zurück an die linke AgentListPane — deren
            // chatAgentChoice bleibt nicht mehr hängen.
            onSelectedAgentChange = { agentId -> chatAgentChoice = agentId },
        )
    }

    /**
     * W24 (Lane B): die Screen-Route je Tab — 0=Dashboard (else-Zweig =
     * defensiver Rückfall auch für ungültige Route-Werte), 1=Einstellungen,
     * 2=Chat, 3=Impressum (entfallen — altes Prozesstod-Rückfall-Verband),
     * 4=Agenten-Tab, 5=E2E-Tab, 6=Cron (W23-11). Als eigene Fläche
     * extrahiert, damit der Falt-Hülle (Rail links / Content rechts) am
     * Scaffold NUR dieses eine Element folgt — die Faltung bleibt in EINEM
     * Element, der Handy-Bestand läuft durch dieselbe Fläche unverändert.
     */
    @Composable
    fun RoutePanes(padding: PaddingValues) {
        // W24 T7-F1: EIN Pane-Holder je BridgeScreen — die je-Zweig Provider
        // (Stabiler Content-Key je Tab, NICHT der Route-Wert) bewahren den
        // rememberSaveable-Stand je Rail-/Tab-Wechsel UND je Falt-Wechsel fort.
        val paneHolder = rememberSaveableStateHolder()
        when (route) {
            1 -> paneHolder.SaveableStateProvider(W24_PANE_KEY_SETTINGS) {
                SettingsHome(
                    padding = padding,
                    // WELLE 13 P2: das Redesign-Home (Darstellung/Accordions) — die
                    // bewährten Server/App-Gruppen wandern als legacyGroups
                    // (gleiche Composables, gleiche SSOT, KEINE neue Logik) hinein;
                    // der alte eigene Accordion-Kopf entfällt (SettingsHome ist der
                    // neue Rahmen mit Darstellung + Berechtigungen +
                    // Standard-Assistent). W16 P20+27: ℹ️-About + Rechtliches =
                    // About-Sheet am dauerhaften TopBar-Button (nicht mehr hier).
                    legacyGroups = { LegacySettingsGroups() },
                    // W23-9 (Lane U9): DIE eine Snackbar — die Umbenennen-Antworten
                    // (Alias gespeichert/„später“/abgelehnt) melden sich wie jedes
                    // andere Feedback bei der W19-Zentral-Snackbar (kein Toast).
                    onFeedback = { text -> snack(text) },
                )
            }
            2 -> paneHolder.SaveableStateProvider(
                // W25-Pane-Fix: EIGENER Key je Falt-Zweig — der geteilte
                // Chat-Key fraß die Pane-Komposition an der Saveable-State-
                // Naht (0-Item-Freeze am Emulator, beide Builds). Der Falt-
                // Wechsel bekommt damit einen frischen Pane-Holder-Stand.
                if (chatTwoPane) W24_PANE_KEY_CHAT_EXPANDED else W24_PANE_KEY_CHAT,
            ) {
                // W24 (Lane B, Brief 3): je EXPANDED (chatTwoPane je Lane-A-
                // Resolver) lebt der Chat als 2-Spalten-Verband — Agenten-Wahl
                // ~300dp FIX links + Conversation-Fläche rechts unter der
                // Kappe 640dp (Band-Mitte, EINE Breiten-Fibel aus
                // WindowFoldRules.EXPANDED_CONTENT_CAP_DP). ChatScreen bleibt
                // GANZ UNBERÜHRT (Lane-D-Zone): rechts läuft er 1:1 wie am
                // Handy (Liste/Conversation je internem Stand, gleiche
                // padding-Quelle), die Wahl links öffnet über die BESTAND-
                // Faser (pendingChatAgentId → openAgentId-Seed je ChatScreen-
                // Effect — der Re-Seed je openAgentId-Änderung faltet JE
                // Wahl-Tap, inklusive Agenten-Wechsel OHNE Route-Sprung).
                // Grenze im Lane-Report (ehrlich): die volle AgentListBody-
                // Parität (Suche/Filter-Chips/Vorschau) lebt im ChatScreen —
                // die Wahl-Fläche trägt bewusst das Minimum (Name + Online-
                // Text-Badge, Zustand nie nur Farbe); HOOK-BEDARF (Lane D):
                // onSelectedAgent-Rück-Verband = wahre Spiegel-Ebene am
                // Wahl-Merker.
                if (chatTwoPane) {
                    // W25-PANE-WURZEL: die TwoPane-Row füllte den GANZEN
                    // Scaffold-Bereich OHNE das TopBar-Padding — die Pane-
                    // Liste klebte HINTER der TopBar (Maß-Beweis am Emulator:
                    // Column 598x1800 statt 1624, Agent-Zeile y=24..120 =
                    // verdeckt; deshalb optisch leer in JEDEM Bau, auch
                    // LazyColumn/Box/Key-Varianten). Die Row respektiert
                    // jetzt das Scaffold-Padding; der ChatPane im Zweig
                    // braucht es dann nicht mehr (sonst doppelt), der
                    // einspaltige Weg behält sein padding unverändert.
                    Row(Modifier.fillMaxSize().padding(padding)) {
                        AgentListPane(
                            selectedAgentId = chatAgentChoice,
                            onPick = { agentId ->
                                chatAgentChoice = agentId
                                pendingChatAgentId = agentId
                            },
                            modifier = Modifier.width(300.dp).fillMaxHeight(),
                        )
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(Modifier.widthIn(max = WindowFoldRules.EXPANDED_CONTENT_CAP_DP.dp)) {
                                ChatPane(PaddingValues(0.dp))
                            }
                        }
                    }
                } else {
                    ChatPane(padding)
                }
            }
            // W16.1 (P38): Route 3 (Legal-Vollansicht) ENTFERNT — eine aus
            // einem alten Prozesstod wiederhergestellte Route 3 fällt auf das
            // else-Dashboard zurück (kein Crash, Sheet bleibt der EINE Ort).
            4 -> paneHolder.SaveableStateProvider(W24_PANE_KEY_AGENTS) {
                AgentsContent(padding)
            }
            5 -> paneHolder.SaveableStateProvider(W24_PANE_KEY_E2E) {
                E2eContent(padding)
            }
            // W23-11-App (TEIL 1): Route 6 = der 5. Tab (CronScreen) — die
            // Snackbar-Rolle (W19-Zentral-Snackbar) läuft über onSnack (TEIL 2
            // bindet die Löschen-Ausgänge, Parität SettingsHome.onFeedback).
            6 -> paneHolder.SaveableStateProvider(W24_PANE_KEY_CRON) {
                // W26 (Agenten-Zentrale): der Agent-Kontext je Dashboard-Sprung
                // (der ausgewählte Agent = die cron.remove/pause-Adresse) —
                // die Wahl konsumiert der Screen EINMAL (Kein-Hängen).
                CronScreen(
                    padding,
                    onSnack = { text -> snack(text) },
                    agentFocusId = pendingCronAgentId,
                    onAgentFocusConsumed = { pendingCronAgentId = null },
                )
            }
            else -> paneHolder.SaveableStateProvider(W24_PANE_KEY_DASH) {
                DashboardScreen(
                    padding = padding,
                    // W16 (Fixliste 17): Verbindungskarte/Fähigkeiten-Kompakt/LAN-
                    // Fußzeile RAUS — Startseite = NUR Agenten-live (Fixliste 14).
                    // Trennen lebt exklusiv in Einstellungen › Server (siehe
                    // RelayServerListCard-Gruppe unten), Status-Banner = SSOT.
                    // W15: Tap auf den Agenten-Slot → Chat-Tab mit der
                    // Conversation DIESES Agenten (Pending-Merker oben).
                    onOpenAgentChat = { agentId ->
                        pendingChatAgentId = agentId
                        route = 2
                    },
                    // W26 (Agenten-Zentrale): Tap auf das Uhr-Icon der Kachel →
                    // Cron-Tab je Agent-Kontext (Pending-Merker, Parität Chat).
                    onOpenAgentCron = { agentId ->
                        pendingCronAgentId = agentId
                        route = 6
                    },
                )
            }
        }
    }


    Scaffold(
        containerColor = FlipsiColors.Void,
        // ── W28 (Lane B, Inset-SSOT): EINE Inset-Quelle je Verband ──────────
        // Der M3-Scaffold-Kontrakt (material3-Scaffold-Quelltext): die
        // Leisten-Slots tragen ihre System-Säulen SELBST (TopAppBar = Status-
        // zeile oben, NavigationBar = Gesture-Säule unten — M3-Defaults) und
        // das Scaffold rechnet contentWindowInsets NUR in Zustände OHNE
        // Leistenslot (Rail-Verband, Chat-Konversation offen). Mit dem
        // Default (systemBars) stakelte auf Play-36 (targetSdk 36 = Play-Gate
        // → erzwungenes edge-to-edge, Insets liefern REAL-Werte; der github-
        // Flavor targetSdk 34 bleibt Decor-fits = 0 dp) die Gesture-Säule
        // DOPPELT: Säule in der Leisten-/Scaffold-Rechnung + Säule nochmals
        // im Screen-contentPadding. Der Null-Verband macht das Scaffold-
        // Padding zur reinen Bar-Geometrie; die Säule lebt JEDESMAL an GENAU
        // EINER Stelle: an der Leiste (kompakt-Portrait, intern) oder am
        // Content (Screens je Rail: 48 dp + navigationBars; Composer je
        // Chat-Verband: eigenes navigationBarsPadding). Der W19-Snack sitzt
        // ohne Bar-Slot bündig am Fensterboden — DAS ist der eingerichtete
        // github-Bestand (dort liefen Insets schon immer 0 dp), Play-36
        // folgt derselben EINEN Optik statt einer zweiten.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // W15/P7: Pfad statt doppelter Titel — im Settings-
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
                    // WELLE 13 P2: Version NICHT dauerhaft sichtbar —
                    // sie lebt ausschliesslich im About-Sheet.
                    // W15: Chat/Agenten/E2E/Navigation leben in der TAB-Leiste
                    // unten (NavigationBar) — hier bleiben Einstellungen
                    // (Zahnrad) + das dauerhafte ℹ️.
                    IconButton(onClick = { route = 1 }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.common_settings),
                            tint = FlipsiColors.TextPrimary,
                        )
                    }
                    // W16 P27 (Doppel-ℹ️ audit): DAUERHAFTEN TopBar-ℹ️
                    // BEHALTEN — er öffnet das About-Sheet (Version +
                    // Rechtliches am EINEN Ort). Die SettingsHead-Kachel in
                    // Einstellungen ist entgangen (P27). W16.1 (P38): die
                    // frühere Route-3-Vollansicht-Tiefe ist VOLL ENTFERNT —
                    // Sheet = einzige Quelle („komplett raus“).
                    IconButton(onClick = { AboutState.open() }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.settings_info_a11y),
                            tint = FlipsiColors.TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FlipsiColors.Void),
            )
        },
        // ── W15 (Fixliste 9): TAB-NAVIGATION — sauber als eigene Leiste
        // in Besitzers Tab-Order: Dashboard | Chat | Agenten | E2E. Einstellungen
        // bleibt ein Sprung oben rechts; auf ihm ist kein Tab selektiert.
        // (W16.1 P38: der alte „Impressum (Route 3)“-Sprung ist mit der
        // Vollansicht entfallen.) Material3-NavigationBar ohne neue
        // Dependency (Compose-BOM), Haus-Farbrollen (Ember = aktiver Tab).
        // W16 (Fixliste 15): die Leiste verschwindet auf Route 2, SOLANGE
        // eine Conversation offen ist (mehr Platz für Bubbles + Composer) und
        // kommt zurueck, sobald die Conversation verlassen wird (Liste/Tab-
        // Wechsel). Leerer Scaffold-Slot = bottomBar-Höhe 0 — der Chat-
        // Composer trägt seine eigenen Insets (imePadding/navigationBars),
        // kein Composer-Flackern, KEIN Doppel-NavBar-Padding: der W28-Scaffold-
        // Vertrag (contentWindowInsets = 0 — das Padding am Wrap wird reine
        // Bar-Geometrie) räumt das Inset beim leeren Slot weg, der Composer
        // behält seine EINE Säulen-Rechnung — auf BEIDEN Flavors (github-34:
        // Insets liefen ohnehin 0 dp; Play-36: vorher Scaffold-Säule +
        // Composer-Säule = doppelter Grund). Siehe ChatScreen-Verband.
        // W24 (Lane B, T7-F2): der Gate-Zweig ist je Falt-Verband GETRENNT —
        // bottomBarHost = NUR der kompakte Portrait-Bestand: nur dort gilt
        // das Open-Flag (route==2 && chatConversationOpen). Je Rail-Verband
        // (medium oder Handy-Landschaft) und je EXPANDED lebt das Rail
        // IMMER — die Schiene sitzt SEITLICH, sie raubt dem Composer keine
        // Leiste; das Gate gehört nur der UNTEREN Leiste des Handy-Bestands.
        bottomBar = {
            if (bottomBarHost && !(route == 2 && chatConversationOpen)) {
                NavigationBar(
                    containerColor = FlipsiColors.Void,
                    contentColor = FlipsiColors.TextPrimary,
                ) {
                NavigationBarItem(
                    selected = route == 0,
                    onClick = { route = 0 },
                    icon = { Icon(painterResource(R.drawable.ic_dashboard), contentDescription = null) },
                    label = { NavTabLabel(stringResource(R.string.nav_tab_dashboard)) },
                    colors = navTabColors(),
                )
                NavigationBarItem(
                    selected = route == 2,
                    onClick = { route = 2 },
                    icon = { Icon(painterResource(NavIcons.chat), contentDescription = null) },
                    label = { NavTabLabel(stringResource(R.string.common_chat)) },
                    colors = navTabColors(),
                )
                NavigationBarItem(
                    selected = route == 4,
                    onClick = { route = 4 },
                    icon = { Icon(painterResource(SettingsIcons.agent), contentDescription = null) },
                    label = { NavTabLabel(stringResource(R.string.chat_list_title)) },
                    colors = navTabColors(),
                )
                NavigationBarItem(
                    selected = route == 5,
                    onClick = { route = 5 },
                    icon = { Icon(painterResource(SettingsIcons.crypto), contentDescription = null) },
                    label = { NavTabLabel(stringResource(R.string.nav_tab_e2e)) },
                    colors = navTabColors(),
                )
                // W23-11-App (TEIL 1): 5. Tab „Geplante Aufgaben“ (Route 6,
                // Uhr-Symbol) — der E2E-Tab (Route 5, W21/M4-Bestand) bleibt
                // unangetastet (Brief: „kein E2E-Verband brechen“); die Tab-
                // Label-Ressource teilt dash_cron_title (KEIN Doppel-Key).
                NavigationBarItem(
                    selected = route == 6,
                    onClick = { route = 6 },
                    icon = { Icon(painterResource(R.drawable.ic_clock), contentDescription = null) },
                    label = { NavTabLabel(stringResource(R.string.dash_cron_title)) },
                    colors = navTabColors(),
                )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                // ── W27 (K1, Design-Kontrast): der EINE Haus-Snack-Verband ──
                // Bestand malte den M3-Default (inverseSurface #E6E0E9-hell +
                // M3-Action-Ton) — das Audit 24.11.2026 maß das Paar hell zu
                // Ember mit 2,20:1 (bold-Soll 3:1 = FAIL). Statt des hellen
                // Bars trägt der Snack die Ember-Fläche (Haus-Rolle „Ember =
                // Aktion/Ergebnis“) mit dem dunklen Action-Label Void (#0E1013,
                // Brief-Kandidat, 6,70:1) und dem Body in Haus-OnEmber
                // (#1A1208, 6,51:1) — Kontraste je SnackContrastRules-KDoc,
                // Werte je ThemeMath (JVM-getestet). EIN Mount = alle Snack-
                // Stellen (cron/invite/error/copy/caps-undo) mit einem Griff;
                // hardcoded Snack-Flächen gibt es daneben nicht (Grep-Beweis).
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(SnackContrastRules.CONTAINER),
                    contentColor = Color(SnackContrastRules.CONTENT),
                    actionColor = Color(SnackContrastRules.ACTION),
                    actionContentColor = Color(SnackContrastRules.ACTION),
                    dismissActionContentColor = Color(SnackContrastRules.CONTENT),
                )
            }
        },
    ) { padding ->
        // W24 (Lane B, Rail-Verband): die NavigationRail sitzt als eigene
        // Fläche LINKS im Content-Nachlauf (kein bottomBar-Slot am Scaffold)
        // — die Route-Fläche folgt in der Rest-Breite. Der Handy-Bestand
        // (bottomBarHost) läuft durch dieselbe neutrale Row/Box-Hülle: eine
        // fillende Box = derselbe Scaffold-Nachlass, KEINE Zweit-Faltung,
        // KEIN Inset-Doppel (padding bleibt die einzige Quelle).
        // (T7-F1: die je-Zweig SaveableStateProvider-Ebene folgt am
        // RoutePanes-Verband im eigenen Schritt.)
        Row(Modifier.fillMaxSize()) {
            if (railHost) {
                // W28 (Lane G, Jäger-F1 (HOCH)): der Rail-Node zog vorher an (0,0)
                // — seine Items SATTEN HINTER der TopAppBar (der oberste Rail-
                // Eintrag war unerreichbar: w26_tv_dash.png bewies 4/5 sichtbare
                // Tabs). Der SSOT-Offset [WindowFoldRules.railTopPadding] = TopBar-
                // Geometrie MINUS Status-Säule: das Rail trägt seine System-Säulen
                // SELBST (M3-Default-Insets), das Scaffold-Oben enthält die Status
                // SCHON (TopAppBar wächst um sie) — KEIN Doppel auf BEIDEN Flavors
                // (github-34: Status ≡ 0 → volle TopBar-Geometrie; play-36: das
                // Status-Delta hebt sich auf → Rail-Items exakt auf Screen-Content-
                // Linie). Die Screens im Box daneben konsumieren das padding je
                // selbst (KEIN zweiter Konsum am Box) — kein Inset-Doppel.
                BridgeNavRail(
                    selectedRoute = route,
                    onSelect = { route = it },
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(
                            top = WindowFoldRules.railTopPadding(padding.calculateTopPadding()),
                        ),
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                RoutePanes(padding)
            }
        }
    }

    // ── W16 P20+P27: About-Sheet (EIN Ort für Version + Rechtliches) ──────
    // Das ℹ️-TopBar-Action öffnet das Sheet (AboutState.open) — der Host
    // rendert es hier einmalig, auf JEDEM Route-Level (nicht nur Einstellungen).
    AboutSheetHost()
    // W16.1 (P38): der frühere LaunchedEffect legalDepthRequest → route = 3
    // ist mit dem Vollansicht-Sprung ENTFERNT (AboutState trägt den Merker
    // nicht mehr) — Rechtstexte leben exklusiv im About-Sheet.
    // W16 P11 (play-Schiene): EIN Host für alle Disclosure-Dialoge — bewusst
    // am SCREEN-Level (vorher: nur in PermissionsSections/Einstellungen), denn
    // im Agenten-Tab (Route 4) lief ein request() sonst in den LEERAUM:
    // pending gesetzt, kein Dialog, Toggle tot ohne jede Meldung.
    DisclosureDialogHost()

    // ── W17 (F1 + F2): code-in-use-Snack + Kopplungs-Passwort-Prompt ────────
    // EIN Mount am Screen-Level (wie die anderen Dialoge): der Prompt geht
    // bei 403 password-required/-invalid auf, der Snack je code-in-use-403.
    PairingPasswordPromptFlow(
        onSnack = { message -> snack(message) },
    )

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
            // W19: Kopplungscode-Tap = GANZE Zeile als Touchfeld (Apple-HIG
            // ≥44 pt) — der bloße Text-Tap bot nur ~30 dp Höhe.
            Text(
                code,
                style = FlipsiType.Code,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
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
 * W15b (Fix B, Befund): Route 4 (Agenten-Tab) bleibt TITELLOS — das
 * Tab-Label unten trägt den Namen, ein doppelter Titel oben wirkte wie
 * ein Festhängen des Screens.
 */
@Composable
private fun topBarTitle(route: Int): String = when (route) {
    2 -> stringResource(R.string.common_chat)
    // W15b: Route 4 bewusst leer — Tab-Label reicht (kein Doppel-Titel).
    4 -> ""
    5 -> stringResource(R.string.common_e2e_title)
    // W23-11-App (TEIL 1): Route 6 ebenso TITELLOS — der Tab trägt den
    // Namen („Geplante Aufgaben“), der CronScreen führt seine Sektion-Titel
    // selbst (W15b-Muster).
    6 -> ""
    // W26 (Audit „Route-3-Titel-Rest“): der Legal-Titel-Twig (3 →
    // common_legal_title) ist entfernt — Route 3 ist unerreichbar seit
    // 0.16.1 (Rechtstexte leben exklusiv im About-Sheet, W16.1/P38). Der
    // Rest-Fall bleibt sauber: unbekannte Route = Dashboard-Titel
    // (App-Namen), NIE der Legal-Titel.
    // 0 = Dashboard; unbekannte Werte fallen auf den App-Namen zurück.
    else -> stringResource(R.string.app_name)
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

/**
 * W24-Besitzer-Fix (24.11., Befund Besitzers Xiaomi): Nav-Tab-Label-SSOT für Bottom-Bar
 * UND Rail — das Material-Item-Label-Default (12sp, kein maxLines-Deckel)
 * bricht lange Tab-Texte in eine 2. Zeile; an abgerundenen Display-Ecken
 * rutscht das äußere Zeichen in die Rundung („einzelner Buchstabe steht
 * unterhalb/verschwindet im Nirgendwo"). Label-Zug KLEINER: 10sp, einzeilig
 * gezwungen (maxLines = 1), Ellipsis-Auslauf statt lautlosem Clip. Bewusst
 * KEIN Icons-Only-Wechsel — die Funktion bleibt lesbar. Farbe bewusst NICHT
 * gesetzt: der Text erbt LocalContentColor je Item-Verband (selectedTextColor/
 * unselectedTextColor über navTabColors/navRailColors — Zustand nie nur Farbe
 * bleibt intakt). lineHeight 12sp: proportional mitgezogen, keine tote Zeile.
 */
@Composable
private fun NavTabLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── W24 (Lane B): NavigationRail je Falt-Verband ─────────────────────────────

/**
 * W24 (Lane B, Brief 2/4): die Rail-Fläche der 5 ECHTEN Tabs — 1:1 die
 * Bottom-Bar-Parität (gleiche Routen 0/2/4/5/6, gleiche bestehende Tab-
 * Ressourcen: nav_tab_dashboard / common_chat / chat_list_title / nav_tab_e2e
 * + dash_cron_title, Wiederverwendung je Brief, KEIN Doppel-/Neu-Key).
 * Die Fläche selbst trägt die EINE neue Ressource rail_a11y (Lane A,
 * Grundlagen 6/6: „Hauptnavigation“/„Primary navigation“) als
 * contentDescription am Rail-Wurzel-KNOTEN — die Items bleiben Label-tragend
 * (immer sichtbar, M3 alwaysShowLabel), der Zustand ist nie nur Farbe:
 * NavigationRailItem = selectable → Selected-Semantik je Item AM State (wie
 * die Bottom-Bar), aktiv = Ember + Pillen-Indikator (navRailColors-Parität).
 * Je Item die Reih-Minimum-Höhe (FlipsiSpacing.RowMin, ≥48dp).
 *
 * Verband (Konsum-Stelle BridgeScreen): railHost = Lane-A-UseRail (medium
 * ODER Handy-Landschaft) ODER UseTwoPane (EXPANDED — Lane A trägt den
 * Rail-Merker dort bewusst NICHT, die Nav-Fläche entscheidet dieser Konsum,
 * T7-F2: Rail bleibt je Expanded IMMER).
 */
@Composable
private fun BridgeNavRail(
    selectedRoute: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // stringResource ist eine Composable-Aufruf-Ebene — je Komposition HIER
    // lösen und in die semantics-Fläche geben (kein Composable-Aufruf im
    // Non-Composable-Lambda, das navTabColors-Paritäts-Muster).
    val railA11y = stringResource(R.string.rail_a11y)
    NavigationRail(
        containerColor = FlipsiColors.Void,
        contentColor = FlipsiColors.TextPrimary,
        modifier = modifier.semantics { contentDescription = railA11y },
    ) {
        NavigationRailItem(
            selected = selectedRoute == 0,
            onClick = { onSelect(0) },
            icon = { Icon(painterResource(R.drawable.ic_dashboard), contentDescription = null) },
            label = { NavTabLabel(stringResource(R.string.nav_tab_dashboard)) },
            colors = navRailColors(),
            modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
        )
        NavigationRailItem(
            selected = selectedRoute == 2,
            onClick = { onSelect(2) },
            icon = { Icon(painterResource(NavIcons.chat), contentDescription = null) },
            label = { NavTabLabel(stringResource(R.string.common_chat)) },
            colors = navRailColors(),
            modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
        )
        NavigationRailItem(
            selected = selectedRoute == 4,
            onClick = { onSelect(4) },
            icon = { Icon(painterResource(SettingsIcons.agent), contentDescription = null) },
            label = { NavTabLabel(stringResource(R.string.chat_list_title)) },
            colors = navRailColors(),
            modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
        )
        NavigationRailItem(
            selected = selectedRoute == 5,
            onClick = { onSelect(5) },
            icon = { Icon(painterResource(SettingsIcons.crypto), contentDescription = null) },
            label = { NavTabLabel(stringResource(R.string.nav_tab_e2e)) },
            colors = navRailColors(),
            modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
        )
        // W23-11-Parität (TEIL 1): der 5. Tab (CronScreen, Route 6, Uhr) —
        // die Label-Ressource teilt dash_cron_title (KEIN Doppel-Key).
        NavigationRailItem(
            selected = selectedRoute == 6,
            onClick = { onSelect(6) },
            icon = { Icon(painterResource(R.drawable.ic_clock), contentDescription = null) },
            label = { NavTabLabel(stringResource(R.string.dash_cron_title)) },
            colors = navRailColors(),
            modifier = Modifier.heightIn(min = FlipsiSpacing.RowMin),
        )
    }
}

/**
 * W24 (Lane B): Farbwerte der Rail-Items — Parität navTabColors (Ember =
 * aktive Fläche, EmberDim-Pillen-Indikator, TextSecondary inaktive). Die
 * Material-Item-Defaults sind selbst @Composable — derselbe Verband wie in
 * navTabColors (kein remember { } um den Composable-Aufruf).
 */
@Composable
private fun navRailColors(): NavigationRailItemColors {
    return NavigationRailItemDefaults.colors(
        selectedIconColor = FlipsiColors.Ember,
        selectedTextColor = FlipsiColors.Ember,
        unselectedIconColor = FlipsiColors.TextSecondary,
        unselectedTextColor = FlipsiColors.TextSecondary,
        indicatorColor = FlipsiColors.EmberDim,
    )
}

/**
 * W24 (Lane B, Brief 3): die Agenten-Wahl des 2-Spalten-Chat-Verbands
 * (~300dp FIX links). EIGENE Fläche auf der GLEICHEN SSOT (RelayClient.agents)
 * — KEINE Kopie der ChatScreen-Liste (Lane-D-Zone unberührt): ehrlicher
 * Leerzustand ohne Kopplung (Bestand-Texte wie am ChatScreen-Liste-Stand),
 * je Agent Name + ONLINE-Text-Badge („Online“/„Offline“ — Zustand nie nur
 * Farbe, Bestand-Keys chat_filter_online/offline) je Reih (min. RowMin).
 * Die Wahl je Row = selectable-Feld — Selected-Semantik AM State + Ember-
 * Balken am offenen Slot; der Spiegel-Merker kommt aus dem BridgeScreen
 * (chatAgentChoice — HOOK-BEDARF je Lane-D-Report: onSelectedAgent-Rück-
 * Verband liefert die WAHRE Auswahl). Die Trenn-Kante zur Conversation ist
 * die dünne Haus-Linie (LineStrong), keine Zweit-Komponente.
 */
@Composable
private fun AgentListPane(
    selectedAgentId: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agents by RelayClient.agents.collectAsState()
    Row(modifier) {
        // W25-Pane-Fix (ENDFASSUNG): Column + verticalScroll statt LazyColumn
        // (die Lazy-Variante blieb am EXPANDED-Verband still leer — die echte
        // Wurzel war das fehlende Scaffold-Padding der TwoPane-Row, siehe
        // Zweig oben; die direkte Zeichnung bleibt als robuste Fassung).
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            // HIG-W19-Parität (Demo-Befund): ehrlicher Leerzustand statt
            // erfundener Agenten — gleiche Texte wie am ChatScreen-Stand.
            if (agents.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.chat_empty_no_agents),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = FlipsiColors.TextPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.chat_empty_no_agents_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FlipsiColors.TextSecondary,
                        )
                    }
            }
            // Reihen direkt gezeichnet (Column statt LazyColumn, W25-Pane-
            // Fix) — keine Items-Keys nötig, die Duplikat-/Leerzeilen-Sorge
            // (Alt-""-Zeilen je W23-Befund) entfällt mit der Lazy-Liste.
            agents.forEach { entry ->
                val open = entry.agentId == selectedAgentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = FlipsiSpacing.RowMin)
                        .selectable(selected = open) { onPick(entry.agentId) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Offener Slot = Ember-Balken + Selected-Semantik (nie nur Farbe).
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .background(if (open) FlipsiColors.Ember else Color.Transparent)
                    )
                    Spacer(Modifier.width(10.dp))
                    ResourceIcon(SettingsIcons.agent, tint = FlipsiColors.TextSecondary, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    // Online-Stand als TEXT — Farbe allein trägt nie den Zustand.
                    Text(
                        stringResource(
                            if (entry.online) R.string.chat_filter_online else R.string.chat_filter_offline,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlipsiColors.TextSecondary,
                    )
                }
            }
        }
        // Trenn-Kante zur Conversation-Fläche (dünne, stille Haus-Linie).
        Box(Modifier.width(1.dp).fillMaxHeight().background(FlipsiColors.LineStrong))
    }
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
 * P42 (AKTIONEN-MIT-ERGEBNIS): Ausgang der Zertifikat-Einschreibung →
 * Anzeige-Ressource. Rein — JVM-getestet (MtlsResultRulesTest). Die Rück-
 * meldung hing vorher an der BRIDGE-Statuszeile (BridgeStatus.showText),
 * die auf der Einstellungen-Route NIE rendert (unsichtbar); jetzt kommt die
 * Antwort als Snack mit klarem Text je Ausgang (Erfolg/Mißerfolg) NACH dem
 * Aktionsende (MtlsCard → onResult → BridgeScreen.snack → SnackbarHost).
 */
internal object MtlsResultRules {
    /** Profil ohne Kopplungscode (ServerProfil-Wert). */
    const val ERR_NO_CODE = "no-code"

    /** Codes, die der Server schon einmal ausgestellt hat (403-Präfix). */
    const val ERR_CODE_USED_PREFIX = "enroll:403"

    /** Ausgang → Anzeige-Ressource (Snack-Text-Ressource je Ausgang). */
    fun resultRes(ok: Boolean, error: String?): Int = when {
        ok -> R.string.mtls_enroll_ok
        error == ERR_NO_CODE -> R.string.mtls_no_code
        error != null && error.startsWith(ERR_CODE_USED_PREFIX) -> R.string.mtls_code_used
        else -> R.string.mtls_enroll_error
    }
}

/**
 * mTLS-Zertifikats-Bereich (W16.3, Fixliste 41): Statuszeile + EIN Button, der
 * wirklich etwas tut — Enrollment am Relay (app-verwalteter AndroidKeystore-
 * Leg, Server stellt das Kette via Device-CA aus). Der KeyChain-Systemdialog
 * ist raus (Sackgasse: ohne installiertes Zertifikat schließt er still).
 */
@Composable
private fun MtlsCard(configVersion: Int, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var cn by remember(configVersion) { mutableStateOf(MtlsSupport.Enrollment.deviceCn(context)) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Card(
        colors = CardDefaults.cardColors(containerColor = FlipsiColors.Panel),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.mtls_title), fontWeight = FontWeight.SemiBold)
            Text(
                text = if (cn != null)
                    stringResource(R.string.mtls_device_ok, cn!!)
                else
                    stringResource(R.string.mtls_device_none),
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    android.util.Log.i("MtlsCard", "click: running=" + running +
                        ", profile=" + (ServerProfiles.activeProfile()?.id ?: "-null-"))
                    if (running) return@OutlinedButton
                    val profile = ServerProfiles.activeProfile()
                    if (profile == null) {
                        // P42: erkennbares Feedback (Snack am Bildschirm statt
                        // der unsichtbaren Bridge-Statuszeile).
                        onResult(context.getString(R.string.mtls_no_profile))
                        return@OutlinedButton
                    }
                    running = true
                    scope.launch {
                        val res = kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO,
                        ) {
                            val code = ServerProfiles.codeOf(profile.id)
                            if (code.isBlank()) {
                                MtlsSupport.Enrollment.Result(ok = false, error = "no-code")
                            } else {
                                MtlsSupport.Enrollment.enrollDevice(context, profile.url, code)
                            }
                        }
                        running = false
                        // P42 (AKTIONEN-MIT-ERGEBNIS): die Rückmeldung kommt
                        // NACH dem Aktionsende als Snack mit klarem Text je
                        // Ausgang (Erfolg/Mißerfolg) — Ausgang → Ressource via
                        // MtlsResultRules (JVM-getestet). Die alte Bridge-
                        // Statuszeile rendert hier nicht (unsichtbar → raus).
                        when {
                            res.ok -> {
                                cn = res.cn
                                onResult(
                                    context.getString(
                                        MtlsResultRules.resultRes(true, null),
                                        res.cn ?: "?",
                                    )
                                )
                            }
                            res.error == MtlsResultRules.ERR_NO_CODE ->
                                onResult(context.getString(R.string.mtls_no_code))
                            res.error != null &&
                                res.error!!.startsWith(MtlsResultRules.ERR_CODE_USED_PREFIX) ->
                                onResult(context.getString(R.string.mtls_code_used))
                            else -> onResult(
                                context.getString(
                                    MtlsResultRules.resultRes(false, res.error),
                                    res.error ?: "?",
                                )
                            )
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier.padding(top = 10.dp).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlipsiColors.TextPrimary),
            ) {
                Text(
                    if (running) stringResource(R.string.mtls_enrolling)
                    else stringResource(R.string.mtls_request)
                )
            }
        }
    }
}

/**
 * PH-3 Welle 2 — Verschlüsselungs-Karte (Gruppe 5, append-only):
 * Session-Fingerprint 8×4 Hex-Gruppen read-only (Klartext-Abgleich ist
 * Hex-Gleichheit, Anzeige gruppiert/Groß), Agent-Identity-Fingerprint
 * daneben. Abweichung/Reset = rot-Banner (RelayClient.e2eBanner). Der
 * eigentliche Abgleich läuft über den FP-ABGLEICH-Button im Chat (Besitzers
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
