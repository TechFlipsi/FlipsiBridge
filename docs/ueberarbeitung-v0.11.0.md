# FlipsiBridge Überarbeitung v0.11.0 — Recherche & Fix-Plan

Stand: 30.10.2026 · Vorbefund von Agent Voll-Leselauf über den Verbindungskern).
Die 5 Review-Subagenten (Verbindung/Pairing, Security, Kotlin-Crashes, Relay-Python, UI) ergänzen
ihre Befunde anschließend in `/root/flipsibridge-review-20261030/*.md` — Konsolidierung unten.

## Ziel von Sir

1. **Jederzeitiger Zugriff**: Verbindung darf NIE unbemerkt sterben; nach App-Kill / Doze /
   Handy-Neustart / Netzwechsel muss sie ohne Sir-Aktion wiederkehren — KEIN neues
   Kopplungscode-Eingeben nötig.
2. Health-Connect-Anbindung (Gesundheitsdaten, read-only).
3. Ordentliches UI-Redesign (Jetpack Compose, Material 3, dunkel).

## Subagenten-Reports konsolidiert (5 Berichte, /root/flipsibridge-review-20261030/)

**Neu bestätigt/ergänzt durch die Flotte:**

| ID | Fund (Quelle: Report) | Schwere | in v0.11.0? |
|----|----------------------|---------|-------------|
| R1 | **Code-SSOT-Verstoß:** `pairing_code` (PairingManager) ≠ `relay_pairing_code` (RelayClient) — UI-Regenerate aktualisiert nur ersteren → WS sendet für immer den ALTEN Code (Verbindung-Vorfall-Hauptwurzel!) | kritisch | JA |
| R2 | 403/429 wie Netzfehler behandelt → 5-Versuch-Fenster (35 s) liegt vollständig im Relay-Block-Fenster (60 s) → garantierte 300-s-IP-Sperre pro falschem Code | hoch | JA |
| R3 | Kein BOOT_COMPLETED, kein onTaskRemoved, keine NetworkCallback, kein AlarmManager — Reboot/Ausfall nur per Zufalls-Prozess-Restart | hoch | JA |
| R4 | Rate-Limit zählt `request.remote` — hinter NPM teilen ALLE externen Clients eine IP: gezielter Lockout des legitimen Handys (live belegtes Internet-Scanning auf der Relay-Domain) | hoch | JA (Relay: X-Forwarded-For-Keying) |
| R5 | Proxy→Relay plaintext (ANDROID_RELAY_CERT/KEY nicht gesetzt) — Kopplungscode mitschnittbar | hoch | DEFERRED (NPM-Seite, eigener Infra-Auftrag) |
| R6 | `hmac.compare_digest` TypeError bei non-ASCII-Token → live bestätigter 500er + Fehlversuch NICHT gezählt → Rate-Limit umgehbar | mittel | JA (Bytes-Vergleich) |
| K6 | **BLOCKER für künftigen targetSdk-Sprung auf 35+:** FGS-Typ `dataSync` kriegt dort ein 6-h-Limit (onTimeout stoppt den FGS) + Background-FGS-Start (BOOT_COMPLETED!) wird verboten → „jederzeitiger Zugriff" bricht auf zwei Wegen. Beim Sprung: auf `specialUse` migrieren (Permission im Manifest schon drin). | info | Doku (kein Sprung geplant) |
| R7 | Binary-Stream-Producer friert WS-Read-Loop ein (HOL-Blocking: langsamer /file-Download blockt alle Requests) | mittel | JA |
| R8 | Relay-Thread-Crash unsichtbar für systemd (kein Watchdog-Check) | mittel | JA |
| R9 | `capturePhoto`: `onDisconnected` ohne countDown + Timeout schließt Kamera nie → Kamera dauerhaft blockiert | hoch | JA |
| R10 | `ensureTts` Check-then-act-Race → doppelte TTS/Leak; kein Init-Timeout | hoch | JA |
| R11 | MainActivity-Leaks: onStatusChanged hält Activity-Ref, kein onDestroy-Cleanup; StatusOverlay hält Activity-Context im Singleton | hoch | JA |
| R12 | Kleinere: negative durationMs-Clamp, ScreenRecorder-Single-Flight, FGS-Typ-Race, Gson 500/400, UpdateInstaller falscher prefs-Name, mtime-vs-Version-Sort /apk/latest, SHA-Ganzlesung in RAM, /apk/latest-Log, 404-vor-Auth-Enumeration, Alarm-Stunde unvalidiert | niedrig | JA (günstige) |
| R13 | Route-Abgleich 55=55 sauber ✅; Sync Repo↔/opt byte-identisch ✅; deny-unknown dicht ✅ | info | — |

**Deferred (nach v0.11.0):** Backend-TLS R5 (NPM-Konfigurationsauftrag), targetSdk-Anhebung auf 36, APK-Authentizität jenseits der Android-Signatur, Code-Scope-Anpassung (App 512 MB vs Relay 256 MB — Doku-Abgleich).

## Kernbefunde des eigenen Durchgangs (Kotlin/Manifest)

| # | Befund | Datei:Stelle | Schwere |
|---|--------|--------------|---------|
| B1 | Stiller Pairing-Regenerate: `init()` generiert bei leerem prefs sofort neu (HyperOS-Cleaner leert `hermes_bridge_prefs` → neue Code → 403-Kaskade) | `auth/PairingManager.kt:33-39` | kritisch |
| B2 | Reconnect gibt PERMANENT auf: nach 5 Versuchen `shouldReconnect=false` + „Tap Connect"; Budget-Refill nur nach 60s stabiler Session → nach nachtlichem WiFi-Ausfall ist die Brücke tot | `client/RelayClient.kt:36-37,240-255` + `ReconnectPolicy.kt` | kritisch |
| B3 | Kein Start nach Handy-Neustart: kein BOOT_COMPLETED-Empfänger, kein Launcher-Service — nach Reboot ist die Brücke tot, bis Sir die App öffnet | `AndroidManifest.xml`, fehlt komplett | kritisch |
| B4 | Foreground-Bindung am Accessibility-Service: `RelayClient` promoted `BridgeAccessibilityService` zum Foreground; wenn die A11y-Berechtigung fehlt, ist `instance=null` → KEIN Foreground → HyperOS killt den Prozess | `client/RelayClient.kt:178-181`, `BridgeAccessibilityService.kt:46-96` | kritisch |
| B5 | Kein eigener Verbindungs-Foreground-Service (z. B. type=dataSync) — der Prozess ist mit nur der Accessibility-Weiche nicht überlebensfest ausgelegt | fehlt | hoch |
| B6 | Kein Doze-sicheres Weck-Intervall für langfristige Reconnects (Coroutine-`delay` schläft im Deep-Doze evtl. lang) | `RelayClient.kt:262-267` | mittel |
| B7 | Termux-Revival ist Upstream-Überbleibsel (fired Intents nach `com.termux`, bei Sirs Setup nicht installiert), Default-aus — harmlos, aber toter Code | `RelayClient.kt:39-47,307-351` | niedrig |
| B8 | `WakeLockManager` nutzt veraltete `SCREEN_BRIGHT_WAKE_LOCK`-API | `power/WakeLockManager.kt:49-54` | niedrig |
| B9 | `POST_NOTIFICATIONS` (Android 13+) und `RECEIVE_BOOT_COMPLETED` fehlen im Manifest; FGS-Notification erscheint sonst evtl. nicht in der Leiste | `AndroidManifest.xml` | mittel |

## Fix-Plan (Kern-Neubau Verbindung)

1. **PairingManager härten**:
   - Code ZUSÄTZLICH als Keystore-gesicherte Kopie in `context.getDir("vault", MODE_PRIVATE)`
     (nicht-prefs-Datei; Cleaner leert in der Regel prefs/cache, `getDir`-Datei überlebt).
   - `init()`: bei leerem prefs → **Restore aus Vault-Datei**; nur wenn auch die fehlt:
     Code erzeugen + **sichtbare Notification** („Kopplungscode neu erstellt — Bridge neu koppeln").
     Nie still.
   - UI-Status „Kopplung: aktiv / Code neu erstellt / Code verloren" im Redesign.
2. **403-Policy**: nach 3× WS-Handshake 401/403 in Folge → Reconnect STOPPEN (kein
   IP-Block-Spirale), persistente „Handlungsbedarf"-Notification + Status; NICHT die
   Reconnect-Loop killen für Netzfehler (dort weiterlaufen).
3. **BridgeConnectionService (NEU, FGS type=dataSync)**: hält die WS-Loop, startet sich
   bei App-Start, bei BOOT_COMPLETED, und (MyPackageReplaced bei Update). Besitzt den
   persistenten Status-Notification-Auftritt (verbunden/verbinde/Fehlershow).
4. **ReconnectPolicy**: nach Budget-Verbrauch → LowRate-Modus (unendlich, 5-min-Intervall
   über AlarmManager `setAndAllowWhileIdle`+`setExactAndAllowWhileIdle` im Doze) statt
   Terminalzustand. Nur der 403-Pfad terminiert (siehe 2).
5. Manifest: `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`,
   BootReceiver eintragen.
6. HyperOS-Hinweis (Doku im Redesign-Screen): Akku-Optimierung „Keine Einschränkungen"
   als Setup-Schritt (Sirs Handy ist schon gerichtet — Verify per UI-Status).

## Health Connect (read-only, Capability `health`)

- Dependency `androidx.health.connect:connect-client` (stable), compileSdk 34 ok.
- Route `POST /health` in `CommandDispatcher.kt` + Capability `health` (Default AUS,
  UI-Schalter via `CapabilityGate.userFacing` — Pflicht-Eintrag!) + Relay-Allowlist
  `_ROUTES` (beide Kopien!) + Tools (`android_tool.py` + Plugin-Kopie) +
  Zähler in `tests/test_android_tool.py`.
- Permission-Flow: `PermissionController.createRequestPermissionResultContract`.
- Metriken (Start-Umfang): Schritte, Herzfrequenz (Min/Max/Avg), Schlaf, Distanz, Kalorien,
  SpO₂, Atemfrequenz, Gewicht. Aggregationen über `HealthConnectClient`, Zeitfenster via
  Parameter `days`/`hours`.
- Manifest: `android.permission.health.*`-Permissions, `intent-filter ACTION_SHOW_PERMISSION_RATIONALE`,
  Play-Store-Ziel-Attribut, Health-Permissions-Intent für den Consent-Dialog.

## UI-Redesign (Jetpack Compose, Material 3, dunkel)

- Vorbereitung: AGP 8.3.0 + Kotlin 1.9.22 → Compose-Compiler 1.5.8, BOM 2024.02.x.
- Screens: Verbindungs-Dashboard (Status + Server + Code-Status + Log-Ausschnitt),
  Capabilities als Gruppenlisten (aus `CapabilityGate.userFacing` generiert),
  Berechtigungen (A11y, MediaProjection, Aufnahmen, Kamera, Speicher), Einstellungen
  (Server-URL, mTLS, Termux-Watchdog), Onboarding (Pairing → Berechtigungen → Schalter).
- Konzept-Detail folgt aus UI-Subagenten-Report; Umsetzung im selben Release.

## Deploy

- versionCode 24, versionName 0.11.0, CHANGELOG im selben Commit, signiert,
  nach `/opt/flipsibridge/`, `/apk_install` (parameterlos) — Sir tippt „Installieren".
- Live-Verifikation: `/ping` 200, `/health` 403/200 je nach Capability, files-Roundtrip.