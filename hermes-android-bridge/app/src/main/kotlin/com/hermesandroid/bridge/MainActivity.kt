package com.hermesandroid.bridge

import android.Manifest
import android.app.assist.AssistContent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hermesandroid.bridge.i18n.AppLanguageGate
import com.hermesandroid.bridge.security.AppLockGate
import com.hermesandroid.bridge.security.AppLockManager
import com.hermesandroid.bridge.server.ServerProfiles
import com.hermesandroid.bridge.ui.BridgeScreen
import com.hermesandroid.bridge.ui.FlipsiTheme
import com.hermesandroid.bridge.ui.assistant.AssistContentRules
import com.hermesandroid.bridge.ui.assistant.AssistantShareBus
import com.hermesandroid.bridge.ui.assistant.AssistantShareDialog
import com.hermesandroid.bridge.ui.assistant.PanelActionRules
import com.hermesandroid.bridge.ui.assistant.ShareAction
import com.hermesandroid.bridge.ui.assistant.ShareIntake
import com.hermesandroid.bridge.ui.assistant.SharePayload
import com.hermesandroid.bridge.ui.onboarding.OnboardingScreens
import com.hermesandroid.bridge.ui.rules.LocalWindowSizeClass

/**
 * FlipsiBridge v0.11.0 — Komplett-Redesign: die ganze UI lebt jetzt in
 * Jetpack Compose (ui/BridgeScreen.kt). Die alte XML-Views-Endlosliste ist
 * ersetzt; Logik-Schicht (PairingManager/RelayClient/CapabilityGate) blieb.
 *
 * Kamera-Runtime-Recht (v0.10.8-Verhalten): nur anfragen, wenn der User die
 * "device"-Capability freizuschalten beginnt — keine Kamera-Frage beim Start.
 *
 * PH-2X X-1 (v0.13): AppLockGate VOR dem Content — solange eine PIN gesetzt
 * und nicht entsperrt ist, wird BridgeScreen GAR NICHT komponiert (Chat-
 * Inhalte sind hinter dem Gate nicht renderbar, auch kein Preview-Leak).
 * onPause stempelt die Hintergrund-Zeit für die Auto-Sperre (30 s/3 min/Nie).
 *
 * W4-D (A6-Verband, Er-Start-Detect): GATE nach dem AppLockGate — solange
 * ServerProfiles.onboardingDone=false („kein Paarung“: weder Migration aus
 * der bestehenden Kopplung noch Wizard-Abschluss), komponiert die Activity
 * den Onboarding-Wizard (ui/onboarding/OnboardingScreens) statt BridgeScreen.
 * AppLockGate bleibt bewusst AUSSEN (PIN-Schranke gilt auch am Erststart —
 * beim echten Erststart existiert keine PIN, das Gate ist dann pass-through).
 * Nach der Fingerprint-Bestätigung: onComplete → App mit initialRoute=2
 * („danach direkt im Chat“).
 */
class MainActivity : ComponentActivity() {

    /**
     * W22 LANE B (G11): geparste Share-Target-Zustellung (ACTION_SEND aus
     * JEDEM Browser-„Teilen“). null = nichts da → kein Dialog. Als Compose-
     * Zustand an der Activity, damit der Dialog im setContent reagiert.
     */
    private var pendingShare by mutableStateOf<SharePayload?>(null)

    companion object {
        /**
         * PH-FIX-CHAT-A (F4): Foreground-Merker der App — RelayClient
         * unterdrückt Chat-Notifications für die gerade OFFENE Conversation,
         * solange die App im Vordergrund steht (im Hintergrund ist die
         * Notification gewollt, auch bei offener Conversation).
         */
        @Volatile
        var isForeground: Boolean = false
            private set
    }

    // W24 (Lane A): calculateWindowSizeClass ist Experimental-Material3-API —
    // Opt-In an der Einstiegs-Funktion (der Lesepunkt bleibt hier: Activity-API,
    // NICHT LocalConfiguration, T1/T7-Befund).
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WELLE 13 P1 („Screenshots nerven mega"): FLAG_SECURE nicht mehr
        // ACTIVITY-WEIT — der ganze Chat war damit unfähig, einen Screenshot
        // zu machen. Neu: das Flag wird DYNAMISCH gesetzt, NUR solange das
        // PIN-Verriegelungs-Gate sichtbar ist (recents/snapshot dort bleibt
        // geschwärzt); jeder andere Screen (Chat, Settings) ist screenshotbar.
        // X-1: App-Sperre initialisieren (Prefs + AndroidKeyStore-Engine,
        // idempotent) — VOR setContent, damit das Gate sofort weiß, ob eine
        // PIN existiert (App-Restart sperrt immer).
        AppLockManager.init(applicationContext)
        // W4 (App-Lane C): App-Sprache VOR setContent laden (idempotent) — der
        // AppLanguageGate-Leser bekommt den persistierten Tag (SYSTEM/DE/EN).
        com.hermesandroid.bridge.i18n.AppLanguage.init(applicationContext)
        requestCameraIfNeeded()
        // W22 LANE B (G11): Share-Target — ACTION_SEND (text/plain) erreicht
        // uns aus jedem Browser („Teilen → FlipsiBridge“); onCreate deckt den
        // Standard-LaunchMode-Pfad ab (neue Instanz), onNewIntent die
        // erneute Zustellung an eine laufende Instanz.
        pendingShare = parseShareIntent(intent)
        setContent {
            // Root-Cause-Fix (L8-Crash „IllegalStateException … No ActivityResult
            // RegistryOwner“ beim Sprachwechsel): activity-compose 1.9.2 resolved
            // die Activity-Owner NICHT über einen CompositionLocal-Provide am
            // setContent, sondern via findOwner(LocalContext.current) — und
            // findOwner läuft NUR ContextWrapper-Ketten. Der AppLanguageGate-Wrap
            // (createConfigurationContext = ContextImpl, kein Wrapper, keine
            // Owner-Interfaces) machte nach dem Live-Rewrap den
            // ActivityResultRegistryOwner-Local null → rememberLauncherForActivity
            // Result (screenRecordLauncher, BridgeScreen:240) warf beim Re-Compose.
            // Explizite Provide-Zone an der Wurzel (LocalComposition gewinnt vor
            // dem Fallback): RegistryOwner + OnBackPressedDispatcherOwner sind
            // von jedem Context-Wrap unabhängig (BackHandler wirft in 1.9.2
            // ebenfalls checkNotNull, und setOwners() setzt keinen Dispatcher-
            // Owner am decorView — derselbe Crashpfad ohne diesen Provide).
            // W24 (Lane A, Tablet-Unterstützung): WindowSizeClass EINMAL an der
            // Wurzel über die offizielle Activity-API — calculateWindowSizeClass
            // liest die Fenster-Metriken DIESES Activity-Fensters (T7-Befund:
            // die Faltung lebt auf der Window-Ebene, NICHT auf der
            // LocalConfiguration-Ebene; KEIN remember-Wrapper, KEIN Konstrukt
            // je Screen). Drehen/Split-Screen = Activity-Recreate → frische
            // Klasse. Weiterreichen via LocalWindowSizeClass — die Screens
            // falten über WindowFoldRules (reine Verband-Logik, je Route).
            // 1.3.0: top-level-Funktion mit Activity-Parameter (NICHT Extension).
            val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity,
                LocalWindowSizeClass provides windowSizeClass,
            ) {
                FlipsiTheme {
                // WELLE 13 P1: FLAG_SECURE dynamisch — NUR am PIN-Gate gesetzt
                // (Recents/Screenshot dort geschwärzt), sonst frei: Chat- und
                // Settings-Screenshots sind ab jetzt möglich (Befund).
                val lockGateVisible by com.hermesandroid.bridge.security.AppLockManager
                    .get().gateVisible.collectAsState()
                LaunchedEffect(lockGateVisible) {
                    runCatching {
                        if (lockGateVisible) {
                            window.setFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                            )
                        } else {
                            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
                // W4 (App-Lane C): Sprache-Verband (Locale-Wrap JE Activity-
                // Komposition) UMS GANZE Baum-Gerüst — Onboarding UND
                // BridgeScreen beide drunter; set() re-wrapped live, kein
                // Activity-Recreate. Die ORIGINAL Activity läuft über
                // AppLanguage.LocalAppActivity mit (der wrapped Context ist
                // kein Activity-Context — context as? Activity wäre null).
                AppLanguageGate {
                    // X-1: Gate VOR dem Nav-Host-Inhalt — Chat-Route NIE umgehbar.
                    AppLockGate {
                        // W4-D (A6): Er-Start-Verband — kein Paarung = Wizard.
                        val onboarded by ServerProfiles.onboardingDone.collectAsState()
                        // Merker „gerade erst den Wizard bestanden“ → BridgeScreen
                        // startet DANN bewusst auf Route 2 (Chat). Nach Prozess-
                        // Restore gilt 0 (Home) — der Merker ist frischer Zustand.
                        var onboardingJustDone by remember { mutableStateOf(false) }
                        if (!onboarded) {
                            OnboardingScreens(
                                onComplete = {
                                    onboardingJustDone = true
                                },
                                // W15 (Fixliste 8): „Setup überspringen“
                                // startet auf dem STATUS-DASHBOARD (Route 0),
                                // NICHT im Chat — onboardingJustDone bleibt
                                // false, nur der Wizard-Abschluss setzt ihn.
                                onSkip = { },
                            )
                        } else {
                            BridgeScreen(
                                initialRoute = if (onboardingJustDone) 2 else 0,
                                // W28 (Lane B, Toast-Räumung): die Share-Dialog-
                                // Zustell-Ergebnisse (Activity-Ebene, KEIN
                                // Snackbar-Host dort) melden sich an DIE eine
                                // W19-Zentral-Snackbar — Merker räumen hier.
                                pendingSnack = pendingShareSnack,
                                onSnackConsumed = { pendingShareSnack = null },
                            )
                        }
                        // W22 LANE B (G11): Share-Target-Dialog — bewusst
                        // INNERHALB des AppLockGate (gesperrte App zeigt KEINE
                        // Vorschau geteilter Inhalte) und als eigener Block
                        // NACH dem Onboarding-Zweig (Lane-E-Zeilen unberührt).
                        AssistantShareDialog(
                            payload = pendingShare,
                            onAction = { action -> handleShareAction(action) },
                            onDismiss = { pendingShare = null },
                        )
                    }
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // PH-FIX-CHAT-A (F4): Foreground-Merker für den Notification-Spam-Schutz.
        isForeground = true
        // X-1: Rückkehr aus dem Hintergrund → Gate neu bewerten (Auto-Sperr-
        // Frist abgelaufen?); v0.12.0 QA-Fix (CHEF-03): Freigabe-Badges neu
        // ziehen statt „fehlt“ stehen zu lassen.
        AppLockManager.get().onAppForeground()
        com.hermesandroid.bridge.ui.BridgeStatus.refreshConfig()
    }

    override fun onPause() {
        super.onPause()
        // PH-FIX-CHAT-A (F4): siehe onResume — Merker zurücksetzen.
        isForeground = false
        // X-1: Hintergrund-Zeitstempel für die Auto-Sperre (30 s/3 min/Nie).
        AppLockManager.get().onAppBackground()
    }

    private fun requestCameraIfNeeded() {
        if (!com.hermesandroid.bridge.security.CapabilityGate.isEnabled("device")) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 4101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Ergebnis reicht — DeviceHardware prüft vor jedem Foto selbst.
    }

    // ── W22 LANE B (G11): AssistContent + Share-Empfang — eigene Blöcke, ────
    // NUR Intent-Handling (Besitz lt. Lane-Brief); Onboarding-Zeilen (Lane E)
    // bleiben unangetastet.

    /**
     * ACTION_SEND an eine LAUFENDE Instanz (Standard-LaunchMode erzeugt
     * sonst eine zweite Instanz — onCreate deckt den Hauptpfad ab).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingShare = parseShareIntent(intent)
    }

    /**
     * G11-Kontext-Schiene: ÜBER onProvideAssistContent liefern Chrome/
     * Browser die aktuelle Seiten-URL an den als Assistenten registrierten
     * Prozess — FlipsiBridge spielt dasselbe Protokoll für die eigenen
     * Inhalte mit (strukturierte JSON-LD-Metadaten; ein privater Chat hat
     * bewusst KEINE öffentliche Web-URI, deshalb ehrlich ohne setWebUri).
     * Der Seiten-Kontext fürs Mini-Panel kommt über den Share-Target-Weg
     * (ACTION_SEND) bzw. den AssistEntry-Hook (HOOK-BEDARF im Lane-Report).
     */
    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        val structured = AssistContentRules.structuredDataJson(
            appName = "FlipsiBridge",
            // W22 MERGE-2 (i18n): Beschreibung als Ressource (DE/EN).
            description = getString(R.string.assistant_assist_content_desc),
        )
        runCatching { outContent.setStructuredData(structured) }
            .onFailure {
                android.util.Log.w("MainActivity", "AssistContent-Setup fehlgeschlagen: ${it.message}")
            }
    }

    /** Share-Intent → [SharePayload] (Whitelist/ Parsing in ShareIntake, JVM-getestet). */
    private fun parseShareIntent(intent: Intent?): SharePayload? {
        if (intent == null || intent.action != ShareIntake.ACTION_SEND) return null
        return ShareIntake.parse(
            action = intent.action,
            mimeType = intent.type,
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
        )
    }

    /**
     * W28 (Lane B, Toast-Räumung): Ergebnis-Zustellung des Share-Dialogs als
     * EIN-Slot-Merker — BridgeScreen konsumiert ihn für DIE eine W19-
     * Zentral-Snackbar und räumt ihn (KEIN System-Toast mehr). Derselbe
     * assistant_share_delivered_toast-Text, gleicher Haus-Ton, keine neuen
     * Strings (strings.xml bleibt Lane C).
     */
    var pendingShareSnack by mutableStateOf<String?>(null)
        private set

    /**
     * Dialog-Aktion → Zustell-Bus + sichtbares Ergebnis (Snack über den
     * [pendingShareSnack]-Merker, W28-Räumung statt System-Toast). Die
     * Übernahme in den Komposer (inputText-Vorlage/Kontext-Chip, autoSend)
     * ist der Chat-Hook (HOOK-BEDARF, Lane A/C — ChatScreen/Composer sind
     * fremde Zone); bis dahin bleibt die Zustellung IM Bus stehen
     * (verlustfrei, kein stilles Wegwerfen).
     */
    private fun handleShareAction(action: ShareAction) {
        val payload = pendingShare ?: return
        val prompt = PanelActionRules.buildPrompt(
            action,
            payload,
            // W22 MERGE-2 (i18n): Seiten-Vorlagen je Locale; die %1$s-Platzhalter
            // füllt PanelActionRules (Host/URL) — Roh-Vorlage ohne Args lesen.
            askTemplate = getString(R.string.assistant_share_ask_template),
            summarizeTemplate = getString(R.string.assistant_share_summarize_template),
        ) ?: return
        AssistantShareBus.deliver(prompt)
        pendingShare = null
        pendingShareSnack = getString(R.string.assistant_share_delivered_toast)
    }
}