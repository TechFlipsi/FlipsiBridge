package com.hermesandroid.bridge.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.service.BridgeConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Welle 4 / App-Lane D — Relay-Profil-Store (Multi-Server) + Er-Start-Schranke.
 *
 * Datenmodell (Plan A6/A7): je Relay-Server ein Profil mit Label + URL + Code;
 * der CODE lebt NICHT in dieser Liste, sondern NUR im App-Vault je Profil
 * (Datei `relay_code.<id>.v1` im getDir("vault")-Haus — exakt das
 * PairingManager-Muster: Code zusätzlich im vault, nie in Logs/Telemetrie,
 * HyperOS-Cleaner-sicher). Liste/Metadaten (Label, URL, Letzt-Verbunden,
 * aktive ID, Erststart-Flag) in einer versionierten JSON-Datei im app-privaten
 * getDir — Upgrade-Pfad: fehlendes Artefakt = Leer-Start, MIGRATION deckt die
 * bestehende Kopplung (RelayClient.serverUrl-Prefs + PairingManager-Code) ab,
 * damit Upgrade-Installationen KEINEN Wizard sehen (QA J2).
 *
 * SINGLETONS-FASER (Sir-Verband): RelayClient object + MtlsSupport +
 * PairingManager folgen dem AKTIVEN Profil — es gibt bewusst GENAU EINE
 * WebSocket-Kette, kein Multi-WS:
 *  - Profil-Wechsel = [connectViaProfile]: SSOT-Adoption des Profil-Codes in
 *    den PairingManager (adoptActiveCode) + bewusstes disconnect()/connect()
 *    über RelayClient (der FGS bleibt bestehen — QA-J2-Kette).
 *  - Der OkHttpClient-Konstrukt in RelayClient (der Lane-A-Pin/Pins-Verband)
 *    bleibt UNBERÜHRT — Profile wechseln NUR URL+Code, niemals die
 *    Client-Fabrrik (Lane-A-Zone).
 *  - mTLS-Alias bleibt Geräte-Ebene (MtlsSupport.selectedAlias) — je-Profil-
 *    Zertifikate wären eine Lane-A-Kette und sind hier bewusst nicht angerührt.
 *
 * ER-START-DETECT (A6): onboardingDone ist ein persistenter Merker im
 * JSON-Speicher. false → MainActivity zeigt den Onboarding-Wizard statt der
 * App („kein Paarung = Onboarding-Screen“). true setzen NUR: Migration
 * (bestehende Kopplung) und die Fingerprint-Bestätigung im Wizard-Schritt 3.
 * Beim Init gilt zusätzlich die Start-Detektion: Flag true, aber Liste leer
 * (alle Profile gelöscht) → Flag zurück auf false → wieder Wizard.
 *
 * DRILL-ACKER (Emulator-Beweis-Stellen je Profil-Wechsel / Erststart):
 *  1. `pm clear` + Kaltstart → Wizard statt BridgeScreen (Er-Start-Verband).
 *  2. Falscher Code → logcat `auth_failure_streak` + Phase PAIRING_BROKEN →
 *     Wizard zeigt 401-Hilfe („Code ist falsch“).
 *  3. Gestopptes/offenes Relay → FAILED mit HTTP 0 → Timeout-Hilfe.
 *  4. Fingerprint-Karte bestätigen → direkt Chat (initialRoute=2), Kaltstart
 *     danach → KOPPLUNG bestand, kein Wizard zweiter Lauf.
 *  5. „+ Server hinzufügen“ (2. Relay-Port) → Zeile mit URL/Code(verdeckt)/
 *     Status/Letzt-Verbunden; Umschalten mit Bestätigungs-Dialog → logcat
 *     `Connecting to ^ws://<2. Port>` UND der Relay-Kette genau EINE
 *     `Phone connected`-Zeile (kein Multi-WS-Beweis).
 *  6. Nach Wechsel: `Letzt-Verbunden` der neuen Zeile springt auf „Gerade
 *     eben“, alte Zeile zeigt den früheren Zeitpunkt.
 *  7. Aktives Profil löschen → WS getrennt („Getrennt“-Banner), kein
 *     Auto-Weiter auf ein anderes Profil (Umschalten bleibt bewusst).
 */
data class RelayProfile(
    val id: String,
    val label: String,
    val url: String,
    val lastConnectedMs: Long = 0L,
)

/**
 * Reine Regeln des Stores (JVM-testbar, keine Android-Imports) — Pflichtenheft
 * QA J4: Profil-Switch-State + Migration + Letzt-Verbunden-Bucket als Unit-Tests.
 */
object ServerProfileRules {

    /** Host aus einer Relay-URL (mit/ohne Scheme, mit Pfad/Port/Userinfo). */
    fun hostOf(url: String): String {
        var s = url.trim()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/')
        // Userinfo ABSTREIFEN: der Host folgt NACH dem @
        // („https://user@foo.example:9/pfad“ → „foo.example“).
        s = s.substringAfter('@', s)
        return s.substringBefore(':').trim()
    }

    /** Leer-Label fällt auf den Host zurück (sichtbare Zeile, nie leer). */
    fun labelOrHost(label: String, url: String): String =
        label.trim().ifBlank { hostOf(url).ifBlank { "Relay-Server" } }

    // ── W4-FIX-JAG2 (S1): Relay-URL-Transport-Verband ────────────────────────

    /**
     * Die EINE Klartext-Ausnahme der NSC (network_security_config.xml):
     * GENAU `dashboard.lan` (statischer Heim-DNS/hosts-Name, HTTP-Direktmodus
     * des lokalen Dashboards). JEDER andere Host ist plattformseitig
     * cleartext-blockiert — und `dashboard.lan` selbst trägt im Release KEINE
     * Bearer-Codes mehr über ws:// (die URL-Validierung hält es im
     * Dashboard-Kontext, siehe [relayUrlValidForConnect]).
     */
    const val CLEARTEXT_EXCEPTION_HOST = "dashboard.lan"

    /** Klartext-Ausnahme-HOST? (hostOf-basiert, Scheme/Port egal, case-sensitiv toleriert). */
    fun isCleartextExceptionHost(url: String): Boolean =
        hostOf(url).equals(CLEARTEXT_EXCEPTION_HOST, ignoreCase = true)

    /**
     * Relay-URL-Verband für Connect/Persist (S1, JVM-testbar):
     *  - https:// bzw. wss:// → immer zulässig (NSC-Pin-/System-Verband trägt).
     *  - http://, ws:// oder scheme-los (buildWsUrl baut darauf `ws://`) →
     *    NUR für die Cleartext-Ausnahme `dashboard.lan` und nur im DEV-Kontext
     *    ([debugBuild] = BuildConfig.DEBUG). Release erzwingt https für ALLE
     *    Nicht-Ausnahme-Hosts — die NSC-Schanze bleibt dahinter Wahrheit,
     *    aber das Scheitern wird sichtbar (Feld-/Status-Fehler) statt stiller
     *    Sockel-Sperre (Schnüffler-F5-Falle).
     */
    fun relayUrlValidForConnect(url: String, debugBuild: Boolean): Boolean {
        val t = url.trim().trimEnd('/')
        if (hostOf(t).isBlank()) return false
        if (t.startsWith("https://") || t.startsWith("wss://")) return true
        return debugBuild && isCleartextExceptionHost(t)
    }

    /** Letzt-Verbunden-Bucket — die UI beschriftet, die Regel rechnet. */
    enum class LastConnectedBucket { NEVER, NOW, MINUTES, HOURS, HISTORY }

    fun lastConnectedBucket(last: Long, now: Long): LastConnectedBucket = when {
        last <= 0L -> LastConnectedBucket.NEVER
        now - last < 60_000L -> LastConnectedBucket.NOW
        now - last < 60L * 60_000L -> LastConnectedBucket.MINUTES
        now - last < 24L * 60L * 60_000L -> LastConnectedBucket.HOURS
        else -> LastConnectedBucket.HISTORY
    }

    /** Migration nur belegt, wenn URL (RelayClient-Prefs) UND Code (SSOT) da. */
    fun migrationAllowed(url: String?, code: String?): Boolean =
        !url.isNullOrBlank() && !code.isNullOrBlank()

    /** Bestehende Kopplung → Profil anlegen + Wizard überspringen (QA J2). */
    fun onboardingAfterMigration(url: String?, code: String?): Boolean =
        migrationAllowed(url, code)

    /** Er-Start-Start-Detektion: alle Profile gelöscht → Wizard kehrt zurück. */
    fun shouldResetOnboardingFlag(profileCount: Int, flag: Boolean): Boolean =
        flag && profileCount == 0

    /** Wizard-Gate: true = App zeigen, false = Wizard zeigen. */
    fun showWizard(onboardingDone: Boolean): Boolean = !onboardingDone
}

/**
 * Persistenter Profil-Store (object = Singleton, kein DI-Apparat — passt zur
 * PairingManager/RelayClient-Faser). Alle Mutations-Pfade persistieren sofort
 * (File-JSON); die UI hängt an den drei StateFlows.
 */
object ServerProfiles {

    private const val TAG = "ServerProfiles"
    private const val PROFILE_DIR = "flipsibridge_profiles"
    private const val PROFILE_FILE = "relay_profiles.v1"
    /** Code je Profil im PairingManager-Vault-Haus (getDir("vault")). */
    private const val CODE_VAULT_DIR = "vault"
    private const val CODE_FILE_PREFIX = "relay_code."
    private const val CODE_FILE_SUFFIX = ".v1"

    /** Versionierter Inhalt der JSON-Datei. */
    private data class ProfileStoreDto(
        val version: Int = 1,
        var onboardingDone: Boolean = false,
        // Welle 12b: „Einrichtung überspringen“ — Merker überlebt den
        // Er-Start-Detect (kein Profil = Wizard-Rückkehr) bewusst NICHT:
        // Übersprungen bleibt übersprungen, bis der Nutzer den Assistenten
        // aus der Server-Liste bewusst wieder aufruft.
        var onboardingSkipped: Boolean = false,
        var activeId: String? = null,
        var profiles: List<RelayProfile> = emptyList(),
    )

    private val gson = Gson()
    @Volatile
    private var appContext: Context? = null

    private var dto = ProfileStoreDto()

    private val _profiles = MutableStateFlow<List<RelayProfile>>(emptyList())
    val profiles: StateFlow<List<RelayProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    /** Er-Start-Verband: Wizard statt BridgeScreen, solange false. */
    fun isOnboardingDone(): Boolean = _onboardingDone.value

    /** Welle 12b: Wizard-Aufrufer merkt sich das Überspringen dauerhaft. */
    @Synchronized
    fun skipOnboarding() {
        dto.onboardingSkipped = true
        persist()
        publish()
    }

    /** Welle 12b: Assistent später fortsetzen (Skip zurücknehmen). */
    @Synchronized
    fun resumeOnboarding() {
        dto.onboardingSkipped = false
        persist()
        publish()
    }

    /** Welle 12b: true = Wizard wurde bewusst übersprungen (kein Profil nötig). */
    fun isOnboardingSkipped(): Boolean = dto.onboardingSkipped

    fun init(context: Context) {
        appContext = context.applicationContext
        load()
        // GELADENEN dto-Stand lesen, NICHT die (noch leeren) StateFlows —
        // publish() läuft erst am Ende von init. Mit dem Flow-Read blieb die
        // frisch geladene Datei unsichtbar: `_profiles.value.isEmpty()` war je
        // Kaltstart true → Migration lief JE Start neu (je Start neue Profil-
        // IDs, persistierte relay_profiles.v1-Flag im Persist-Verband
        // überschrieben, onboardingDone=false) und
        // shouldResetOnboardingFlag(0, true) setzte den Wizard-Kaltstart zurück
        // (A6-Fall 7 verletzt). load() ist OK (keine 'nicht lesbar'-Warn) —
        // das Lesen des FALSCHEN Zustands war der Defekt.
        if (dto.profiles.isEmpty()) {
            // Upgrade-Migration (QA J2): bestehende Kopplung (URL im
            // RelayClient-Prefs/Vault-Rettung, Code in der SSOT) → EIN Profil.
            val url = RelayClient.serverUrl ?: PairingManager.restoreUrlIfPrefsLost()
            val code = PairingManager.getCode()
            if (ServerProfileRules.migrationAllowed(url, code)) {
                migrateExistingPairing(url!!, code)
            }
        }
        if (ServerProfileRules.shouldResetOnboardingFlag(dto.profiles.size, dto.onboardingDone)) {
            // Er-Start-Detect: Flag true, aber kein Profil mehr → wieder Wizard.
            dto.onboardingDone = false
            persist()
        }
        publish()
        registerConnectedTracker()
        Log.i(TAG, "Profile: ${_profiles.value.size} (aktiv=${dto.activeId ?: "-"}, " +
            "onboardingDone=${dto.onboardingDone})")
    }

    /** Aktives Profil (null = noch keins bewusst gesetzt). */
    fun activeProfile(): RelayProfile? =
        _profiles.value.firstOrNull { it.id == dto.activeId }

    fun profile(id: String): RelayProfile? =
        _profiles.value.firstOrNull { it.id == id }

    /** Profil-Code (nur UI-Verband, Feld zeigt ihn bewusst verborgen). */
    fun codeOf(id: String): String = readCodeVault(id) ?: ""

    /**
     * Regenerate-Verband („Neuen Code erzeugen“ in der Kopplungskarte): der
     * neue SSOT-Code wird in den Vault des AKTIVEN Profils nachgezogen, sonst
     * belebte ein späterer Profil-Wechsel den ALTERN Code (401-Falle).
     */
    @Synchronized
    fun writeActiveCode(code: String) {
        val id = dto.activeId ?: return
        if (code.isBlank()) return
        if (!_profiles.value.any { it.id == id }) return
        writeCodeVault(id, code)
        persist()
        publish()
    }

    /** Neues Profil anlegen (Code wandert NUR in den Vault). */
    @Synchronized
    fun add(label: String, url: String, code: String): RelayProfile {
        val normalized = url.trim().trimEnd('/')
        val p = RelayProfile(
            id = UUID.randomUUID().toString().substring(0, 8),
            label = ServerProfileRules.labelOrHost(label, normalized),
            url = normalized,
        )
        writeCodeVault(p.id, code)
        dto.profiles = dto.profiles + p
        persist()
        publish()
        return p
    }

    /** Profil bearbeiten; codeOrNull = null/blank → Code unverändert. */
    @Synchronized
    fun update(id: String, label: String, url: String, codeOrNull: String?) {
        val normalized = url.trim().trimEnd('/')
        dto.profiles = dto.profiles.map {
            if (it.id != id) it else it.copy(
                label = ServerProfileRules.labelOrHost(label, normalized),
                url = normalized,
            )
        }
        if (!codeOrNull.isNullOrBlank()) writeCodeVault(id, codeOrNull)
        persist()
        publish()
    }

    /**
     * Profil löschen. Wird das AKTIVE Profil gelöscht: bewusstes Trennen
     * (disconnect) + Server-URL räumen — KEIN Auto-Weiter auf ein anderes
     * Profil (das Umschalten bleibt eine Bestätigungs-Aktion). Leere Liste
     * → Flag bleibt in dieser Session true (keine Mid-Settings-Zwangsumleitung);
     * die Er-Start-Detektion bei Init holt den Wizard bei Kaltstart zurück.
     */
    @Synchronized
    fun remove(id: String) {
        dto.profiles = dto.profiles.filterNot { it.id == id }
        deleteCodeVault(id)
        if (dto.activeId == id) {
            dto.activeId = null
            RelayClient.disconnect()
            RelayClient.serverUrl = null
        }
        persist()
        publish()
    }

    /** Aktives Profil setzen (persistiert; Connect passiert bewusst separat). */
    @Synchronized
    fun setActive(id: String) {
        if (!_profiles.value.any { it.id == id }) return
        dto.activeId = id
        persist()
        publish()
    }

    /**
     * Fingerprint-Bestätigung des Wizards (Schritt 3) = Erststart-Abschluss —
     * der EINZIGE Pfad (neben der Migration), der onboardingDone setzt.
     */
    @Synchronized
    fun completeOnboarding() {
        dto.onboardingDone = true
        persist()
        publish()
    }

    /**
     * Wizard-Verband (A6): Profil je Relay-URL anlegen/aktualisieren + aktives
     * Profil setzen. Der Connect läuft danach über [connectViaProfile] —
     * die SSOT folgt mit (adoptActiveCode im connect-Verband).
     */
    @Synchronized
    fun upsertFromOnboarding(url: String, code: String): RelayProfile {
        val normalized = url.trim().trimEnd('/')
        val existing = _profiles.value.firstOrNull {
            it.url.equals(normalized, ignoreCase = true)
        }
        val p = if (existing != null) {
            update(existing.id, existing.label, normalized, code)
            profile(existing.id) ?: existing
        } else {
            add(label = "", url = normalized, code = code)
        }
        setActive(p.id)
        return p
    }

    /**
     * Profil-Wechsel-Verband (A7): aktives Profil setzen + Profil-Code in den
     * PairingManager-SSOT adoptieren + FGS sichern + bewusstes
     * disconnect/connect (connect() trennt zuerst — Singletons-Faser:
     * genau EIN WebSocket, die Client-Fabrik (Lane-A-Pin) bleibt an).
     */
    fun connectViaProfile(context: Context, profileId: String) {
        val p = profile(profileId) ?: return
        // W4-FIX-JAG2 (S1): Transport-Verband am Connect-Pfad — auch LEGACY
        // gespeicherte Profile tragen den Verband danach nach (Klartext nur
        // dashboard.lan im Dev-Kontext, sonst https). Kein Connect-Versuch auf
        // verbotene Transporte — die Stille-Sockel-Sperre der NSC wird damit
        // vorgezogen und sichtbar.
        if (!ServerProfileRules.relayUrlValidForConnect(p.url, BuildConfig.DEBUG)) {
            Log.w(TAG, "Profil ${p.id} (${p.label}): URL verletzt den Transport-Verband (S1) — kein Connect")
            return
        }
        val code = readCodeVault(p.id)
        if (code.isNullOrBlank()) {
            Log.w(TAG, "Profil $p.label ohne Code im Vault — kein Connect-Versuch")
            return
        }
        setActive(p.id)
        PairingManager.adoptActiveCode(code, "profil:${p.id}")
        BridgeConnectionService.start(context)
        RelayClient.connect(p.url, code)
    }

    /**
     * Home-Schnellverbindung folgt dem AKTIVEN Profil: URL-Änderung am
     * Home-Feld aktualisiert die aktive Zeile (Listen-Wahrheit + Vault-Spiegel
     * über den RelayClient.serverUrl-Setter).
     */
    @Synchronized
    fun syncActiveUrl(url: String) {
        val id = dto.activeId ?: return
        if (_profiles.value.none { it.id == id }) return
        val old = _profiles.value.firstOrNull { it.id == id } ?: return
        val normalized = url.trim().trimEnd('/')
        if (old.url == normalized) return
        // Label NUR dann neu ableiten, wenn es der Automatik entstammt
        // (== alter Host) — ein manuell gesetzter Name bleibt bestehen.
        val hostDerivedOld = ServerProfileRules.labelOrHost("", old.url)
        val nextLabel =
            if (old.label == hostDerivedOld) ServerProfileRules.labelOrHost("", normalized) else old.label
        val changed = dto.profiles.map {
            if (it.id != id) it else it.copy(label = nextLabel, url = normalized)
        }
        dto.profiles = changed
        persist()
        publish()
    }

    /** Letzt-Verbunden: Connected-Meldung des aktiven Profils stempeln. */
    private fun registerConnectedTracker() {
        RelayClient.addStatusListener { connected, _ ->
            if (!connected) return@addStatusListener
            val id = dto.activeId ?: return@addStatusListener
            val now = System.currentTimeMillis()
            dto.profiles = dto.profiles.map {
                if (it.id != id) it else it.copy(lastConnectedMs = now)
            }
            persist()
            publish()
        }
    }

    /** Upgrade-Migrations-Pfad: bestehende Kopplung wird Profil #1. */
    private fun migrateExistingPairing(url: String, code: String) {
        val p = RelayProfile(
            id = UUID.randomUUID().toString().substring(0, 8),
            label = ServerProfileRules.labelOrHost("", url),
            url = url.trim().trimEnd('/'),
        )
        writeCodeVault(p.id, code)
        dto.profiles = listOf(p)
        dto.activeId = p.id
        dto.onboardingDone = ServerProfileRules.onboardingAfterMigration(url, code)
        persist()
        Log.i(TAG, "Migration bestehender Kopplung in Profil ${p.id}")
    }

    // ── Persistenz: versionierte JSON-Datei app-privat (Fehl-Artefakt = Leer-Start) ──

    private fun storeFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.getDir(PROFILE_DIR, Context.MODE_PRIVATE), PROFILE_FILE)
    }

    private fun load() {
        val f = storeFile() ?: return
        runCatching {
            if (!f.exists()) return
            val parsed = gson.fromJson(f.readText(), ProfileStoreDto::class.java) ?: return
            dto = ProfileStoreDto(
                version = parsed.version,
                onboardingDone = parsed.onboardingDone ?: false,
                activeId = parsed.activeId,
                profiles = parsed.profiles.orEmpty()
                    .filter { it.id.isNotBlank() && it.url.isNotBlank() },
            )
        }.onFailure { Log.w(TAG, "Profil-Datei nicht lesbar: ${it.message}") }
    }

    private fun persist() {
        val f = storeFile() ?: return
        runCatching {
            // W4-FIX-JAG2 (C9): ATOMAR schreiben (Temp + rename) — das alte
            // delete+writeText hatte ein Crash-Fenster zwischen delete und
            // writeText (oder mitten im writeText): App-Rauswurf/force-stop dort
            // = leere Datei = kompletter Profil-Store weg (Kopplung, Wizard-Rot).
            // Temp-Datei im selben Dir (rename bleibt im Dateisystem atomar);
            // scheitert das rename (plattformabhängig über existierender Datei),
            // klassischer copy+delete-Fallback (Alt-Bestand bleibt bis zum
            // Schluss lesbar, Parität zu writeAtomic im ChatHistoryStore).
            val tmp = File(f.parentFile, ".${f.name}.part")
            tmp.writeText(gson.toJson(dto))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Profil-Write fehlgeschlagen: ${it.message}") }
    }

    // ── Code-Vault je Profil (PairingManager-Muster: getDir("vault")) ──

    private fun codeFile(id: String): File? {
        val ctx = appContext ?: return null
        return File(ctx.getDir(CODE_VAULT_DIR, Context.MODE_PRIVATE), CODE_FILE_PREFIX + id + CODE_FILE_SUFFIX)
    }

    private fun readCodeVault(id: String): String? {
        return runCatching {
            val f = codeFile(id) ?: return@runCatching null
            if (!f.exists()) return@runCatching null
            f.readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun writeCodeVault(id: String, code: String) {
        if (code.isBlank()) return
        runCatching {
            val f = codeFile(id) ?: return
            f.delete()
            f.writeText(code.trim())
        }.onFailure { Log.w(TAG, "Code-Vault-Write fehlgeschlagen: ${it.message}") }
    }

    private fun deleteCodeVault(id: String) {
        runCatching { codeFile(id)?.delete() }
            .onFailure { Log.w(TAG, "Code-Vault-Delete fehlgeschlagen: ${it.message}") }
    }

    /** Flows auffrischen — JE Mutation endet hier (UI sieht jeden Write). */
    private fun publish() {
        _profiles.value = dto.profiles
        _activeId.value = dto.activeId
        _onboardingDone.value = dto.onboardingDone || dto.onboardingSkipped
    }
}
