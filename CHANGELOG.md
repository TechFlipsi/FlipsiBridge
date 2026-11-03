## [0.14.1] - 2026-11-03 — Medien/Datei-Versand E2E + Fix-Welle 2 (B1–B8) + TLS-nativ am Relay
- **Medien-/Datei-Versand E2E (MEDIA-T1):** Anhang-Sheet im Agent-Chat (Foto/Video, Datei, Sprachnachricht) mit E2E-verschlüsseltem Chunk-Upload (POST /media, Double-Ratchet-verschlüsselt, Chunk-Ack je Block), Medien-Bubbles mit Übertragungs-Chip, empfangsseitige Medien-Verweise im Verlauf; Agent-Adapter assembliert und speichert md5-paritätisch. Live bewiesen im echt gepaarten Chat: Foto 10 561 B, 4-stufige MD5-Parität PC→Gerät→TLS/E2E→Agent.
- **Fix-Welle 2 (B1–B8, Her-Test 40er + Redrills):** AttachSheet-ID-Vorzeichen-Bug (B7, P2) — Live-Agent-IDs sind hashCode-Werte und können negativ sein; Vertrag vorzeichenfest (AttachSheetRules.isConversationOpen), damit öffnet das Anhang-Sheet auch im Live-Chat und der System-BackHandler greift; Chat-Status-Chip-/Takeover-/Spool-Fixes (B1–B8) inkl. cross-session-takeover-Session-Reset-Heilung; AppLock-Autolock-Test-Semantik (J7) aktualisiert; _bridge_url-Fallback korrigiert (RF-01).
- **TLS-nativ am Relay (W3-01):** ANDROID_RELAY_TLS-Schalter — natives TLS auf dem 8766-Socket mit fail-closed RuntimeError statt stillem Plaintext-Fallback (D5b); Live-Relay läuft mit TLS + NPM-HTTPS-Front, tcpdump-Beweis 0 Klartext (nur TLS-Records) auf 8766 und 443.
- Backend-Suite 357 pytest grün (env-clean); JVM-Suite am Stand-PC 273 Tests / 2 bekannte Windows-Symlink-Baseline.
- versionCode 37.

## [0.14.0] - 02.11.2026 — PH-2X+PH-3: App-Sperre, OEM-Profile, E2E-Verschlüsselung
- **E2E-Verschlüsselung volles Double-Ratchet Handy↔Agent (PH-3):** X25519-X3DH-Handshake (fb-v1) mit Identity+PreKey je Seite, volles Double Ratchet nach Signal-Spec (KDF_RK/KDF_CK, Skipped-Keys-Cache, Replay-Fenster 100 + 10 min Freshness, Nonce-Zähler-Bindung, Rotation je Peer-Announcement und je Session), AES-256-GCM-Frames (chat.send.enc/chat.event.enc/chat.reply.enc) — der Relay sieht nur Chiffre. Fingerprint (8×4 Hex) in der App (Settings-Gruppe 5 „Verschlüsselung“) sichtbar, FP-Abgleich-Button im Chat-Header sendet den Abgleich verschlüsselt, der Agent gleicht automatisch ab und antwortet „Fingerprint-Abgleich: OK — Code ist gleich“ (bei Abweichung: Session sofort invalidiert, fail-closed). Keys der App versiegelt über AndroidKeyStore (IdentityStore), Klartext-Fallback existiert nicht.
- **App-Sperre PIN+Biometrie+Auto-Sperre (PH-2X X-1):** App-weite PIN (4–6 Ziffern, 2×-Bestätigung), Gate VOR dem Nav-Host (Chat-Inhalte werden im gesperrten Zustand nie gerendert), PIN-Hash mit Keystore-versiegeltem Salt, 5 Fehlversuche → progressiv 30/60/120 s Sperre (persistent über App-Restart), Biometrie-Unlock über Plattform-Keyguard (kein Crash ohne Hardware), Auto-Sperre 30 s/3 min/Nie. Alles lokal, kein Netz-Sync.
- **OEM-Kompatibilitätsprofile 8 Hersteller (PH-2X X-2):** DeviceIntentResolver mit Profilen (Xiaomi/HyperOS, Samsung/OneUI, OPPO+realme+OnePlus, VIVO/iQOO, Huawei+Honor, Motorola, Nothing, generisch-AOSP) — je Feature (Autostart/Akku/Overlay/Benachrichtigungen) Kandidaten-Intents, erster auflösbare gewinnt, ohne Treffer informative Leit-Zeile statt Crash; generischer AOSP-Fallback auf jedem Gerät.
- versionCode 36.

## [0.13.0] - 02.11.2026 — PH-2-Complete: Chat-Status-Chips + Presence-Live + Chat-Fehler-Banner
- **Chat-Status-Chips (F2 S1–S3, PH2-T10/T11/T12):** Je eigener Nachricht Status-Chip-Sequenz ✓ Gesendet → ✓✓ Zugestellt (Relay-Broker-Status je msg_id) → ✓✓ Gelesen in BLAU (chat.read vom Adapter, sofort nach Empfang); ⏱ Wartet bei Offline-Spool. Header zeigt bei Agent-Arbeit „arbeitet … (mm:ss)" mit Spinner (chat.activity working=true + started_at), die Antwort-Bubble zeigt „Antwort nach X s" (elapsed_ms im Reply-Envelope, Empfang→Antwort-Send inkl. Gateway — ehrlichkeits-Shim dokumentiert). Presence-Wechsel (Online-Punkt/„Offline") kommen live ohne App-Restart.
- **Chat-Fehler-Banner sichtbar (E2E-Fund e2e-d P1):** Sende-Fehler (z. B. HTTP 429 des Relays) waren im verbundenen Zustand unsichtbar (Route 2 hatte keine Statusanzeige). Eigenes dismissbares Fehler-Banner direkt unter dem Conversation-Kopf, Fehlertext aus dem RelayClient-Result; Demo-Fallback unberührt.
- **Presence-Live-Update ohne App-Restart (E2E-Fund e2e-c P2):** Relay pusht jetzt bei JEDEM Agent-Presence-Wechsel (Connect/Disconnect) einen relay.state-Frame an die offene Phone-WS — der Chat-Header zeigt Online/Offline live, ohne Neustart.
- **Jäger-Fixrunde (90dfe0b) integriert:** Presence-Guard + Spinner-Reset bei Agent-Disconnect, Owner-Check auf chat.read (kein Cross-Agent-Lese-Orakel), Read-/Chip-Semantik-Verfeinerungen.
- Basiseinträge 0.12.9 (Relay WS-Backbridge, ChatScreen-Realtime, KI-Badge) siehe unten.
- versionCode 35.

## [0.12.9] - 02.11.2026 — PH-2-Merge: Chat-Anbindung (Relay WS-Backbridge + ChatScreen-Realtime)
- 0.12.9 (versionCode 34): PH-2-Merge — Relay WS-Backbridge (chat.reply→Phone-Broker inkl. Spool/Retention), relay.state-Agenten-Liste beim Phone-Connect, ChatScreen-Realtime (sendChat → POST /chat, chatEvents → Conversation, Demo-Fallback), KI-Badge bleibt.
- versionCode 34.

## [0.12.8] - 02.11.2026 — Welle-3-Abnahme-Fund: FAILED/RECONNECTING-Banner sichtbar machen (PH1-W3)
- **Fix Banner-Verdeckung (P1, Drill-Beweis):** Der Statusbanner zeigte bei vorhandenem Code-Event (REGENERATED — persistiert in den Prefs seit dem allerersten Code-Anlegen) per early-return dauerhaft „Kopplungscode wurde neu erstellt" und verdeckte damit auf jedem Normalgerät die FAILED- und RECONNECTING-Banner. Der komplette PH1-T3/T4-Banner-Zustands-Text (z. B. „Server-Adresse ist ungültig — URL in den Einstellungen prüfen und erneut verbinden.") war im Normalbetrieb unsichtbar — Drill-Beweis: FAILED-State aktiv, ConnectionCard zeigte die rohe Ursache, der Banner aber den Code-Hinweis. Fix: FAILED + RECONNECTING in die Ausschlussliste des Code-Event-Banners (wie CONNECTED/PAIRING_BROKEN); verdeckt bleibt nur die Erstnutzer-Führung bei IDLE/CONNECTING.
- versionCode 33.

## [0.12.7] - 02.11.2026 — QA-Jagd-Fixes (PH1, Paranoiker P1+P2)
- **Fix ic_monitor_rec.xml Subpfad-Geometrie:** Der Welle-2-Bulk-Transform (Y+960) hatte das degenerierte Original-Subpfad-Ende `v-400 400` (fc8b693 — Linie ohne sichtbares Etwas) fälschlich zu `v-400l400` umgeschrieben = gefülltes Dreieck (160,600)(160,200)(560,200) im Monitor-Screen. Restauriert originalgetreu `v-400 400Z`; Beweis: Python-Pfad-Parser VORHER Dreiecksfläche 80000 + malformedes `l400` (eine statt x-y-Koordinate), NACHHER 0/degeneriert wie Original.
- **Doku-Kommentar build.gradle.kts präzisiert (Bump-Regel):** Skeleton-Phase kein Bump; spätere Wellen bumped regulär (30/0.12.5, 31/0.12.6) — Regel: versionCode-Bump + CHANGELOG im selben Commit je App-Änderung.
- versionCode 32.

## [0.12.6] - 02.11.2026 — Einstellungen-Accordion + Icons-Fix + Fehler-Texte (PH1-Welle 2)
- **Einstellungen-Screen (PH1-T2):** Route 1 = 3 ausklappbare Gruppen (Accordion je Gruppe mit Pfeil, eigener rememberSaveable-Klappe-Zustand je Gruppe): **Server** (Relay-URL-Feld = gleiche SSOT wie Home, Kopplungscode = bestehende PairingCard, mTLS-Bereich = bestehende MtlsSupport-Route nur angezeigt/geöffnet), **App** (Autostart, Akku-Optimierungs-Hinweis via System-Intent, Benachrichtigungen/Overlay/Datei/Aufnahme/Health-Routen = bestehende Berechtigungs-Karten, Termux-Toggle, Selbst-Update: github-Edition aktiv, play-Edition disabled mit Hinweis „nur in der GitHub-Edition" per BuildConfig.FLAG_FULL_EDITION), **Agenten** (Master-Schalter + die 16 Capability-Toggles in 4 klapptbaren Gruppen-Karten; JEDE Toggle-Änderung schreibt CapabilityGate + BridgeStatus.refreshConfig() unverändert weiter). Home schlank: Banner + Verbindungs-Status + Master-Status-Kompakt + Fußzeile — die langen Listen wanderten in die Einstellungen.
- **Fähigkeiten-Gruppen-Accordion (PH1-T5):** CapabilityGroupCard hat jetzt optional expand/onExpandedChange (Kopf = Touch-Feld, Pfeil, Zeilen nur ausgeklappt); je Gruppe eigener rememberSaveable-Zustand.
- **Fehler-Texte (PH1-T4):** FAILED(ursache)/RECONNECTING(ursache) zeigen im Banner Ursache + Nutzer-Handlung in generischem Deutsch (z. B. „Server nicht erreichbar — URL in den Einstellungen prüfen und Verbindung wiederherstellen."); die rohe Relay-Meldung bleibt in der Statuszeile der Verbindungskarte.
- **Impressum-Rahmen (PH1-T6):** je Rechts-Abschnitt (Impressum/Datenschutz/Nutzungsbedingungen) eine strukturierte Inhalts-Karte mit „Inhalte folgen in Phase 8" — keine privaten Daten im Gerüst.
- **Icons-Fix (PH1-W2-A):** 29 Haus-Drawables ic_* trugen Material-Symbols-Originalpfade in der 0,-960-Konvention (`M440-280` — Minus als Koordinaten-Trenner, vom einfachen `M-`-Grep nicht erkannt) → Android clippt negative Viewport-Bereiche, die Icons renderten unsichtbar (Welle-1-Pixel-Beweis für StatusBanner/Badge). Fix: absolute Y-Koordinaten je Pfad um +960 geshiftet (226 Koordinaten; derselbe Mechanismus wie der ic_chat-Fix); ic_chat war bereits gefixt, ic_placeholder (Viewport 24) war sauber.
- versionCode 31.

## [0.12.5] - 02.11.2026 — Navigation-Grundgerüst + Status-Migration + Doku-Fixes (PH1-Welle 1)
- **Navigation-Grundgerüst (PH1-T1):** BridgeScreen = einfacher Nav-Host per rememberSaveable-Int-Route (0=Home mit dem BESTEHENDEN Funktionsumfang unverändert, 1=Einstellungen-Platzhalter „Welle 2", 2=Chat-Platzhalter „Direkt-Chat — Phase 2", 3=Impressum/Datenschutz-Gerüst mit 3 Abschnitts-Headern + „Wird in Phase 8 mit Inhalten hinterlegt"). Top-Bar mit Einstellungen-/Chat-/Info-Icons (Material-Icons; KEINE neue Dependency, KEIN androidx.navigation — Settings/Info aus dem Compose-BOM-Icons-Set, Chat-Bubble als eigener Vector-Drawable im Hausstil); Back-Taste = Route zurück.
- **Status-Migration (PH1-T3):** ui/BridgeStatus.ConnectionState = sealed interface (IDLE, CONNECTING, CONNECTED, RECONNECTING(ursache), PAIRING_BROKEN, FAILED(ursache)) + StateFlow<ConnectionState> (+ eigene Flows für Server-Adresse und Anzeige-Text). RelayClient meldet strukturiert (neues Phase-Enum an notifyStatus); die String-Matching-Route (message.contains("Verbinde") …) ist KOMPLETT raus — Banner und Verbindungskarte matchen exhaustiv auf den Zustand (when ohne else).
- **Doku-/Konfig-Fixes (PH1-T0):** alte gradle-Task-Namen in README.md/docs/install.md/docs/AGENT_SETUP.md/FLIPSI_BRIDGE_SPEC.md auf die Flavor-Tasks umgestellt (assembleGithubDebug/assembleGithubRelease, Pfade outputs/apk/github/…); APK-Dateiname je Flavor `flipsibridge-<version>-<flavor>.apk` (Relay-/apk/latest-Parser bleibt kompatibel); build.yml EOF-Newline.
- versionCode 30.

## [0.12.4] - 02.11.2026 — App generisch machen (Play-Store-Vorbereitung)
- Agenten-Namen entfernt: accessibility_description + caps_section_hint sagen jetzt nur "Agent" (keine Produkt-/Agenten-Namen nutzersichtbar).
- **Ehemalige LAN-Ausnahme aus network_security_config entfernt** → `dashboard.lan` (generischer Beispiel-Hostname; die eigene Route bleibt funktional identisch für den lokalen Cleartext-ausfall).
- **Mikro-Notification** (MicrophoneRecorderService): "Hermes Bridge microphone" → "FlipsiBridge Mikrofon" + deutsche Beschreibung.
- **BridgeServer-Hint:** "Hermes Bridge app" → "the app" (Play-Store-Neutralität).
- **CommandDispatcher-Dokumentstring:** "Hermes drives the device through" → "the server agent drives the device through".
- **ClipData-Marker** "hermes" → "bridge" + TTS-ID-Marker "hermes_speak_..." → "bridge_speak_..." (kein nutzersichtbarer Effekt).
- Rest-Referenzen sind nur noch interne Package-/PREFS-Namen (com.hermesandroid / hermes_bridge_prefs) — bewusst beibehalten, da Package-Umbenennung = Reinstall +Prefs-Datenverlust; für die Play-Store-Phase wird ein Package-Rename geplant.

## [0.12.3] - 01.11.2026 — WS-Kopplung über TLS-Domain gefixt (Sirs-Befund: App verbindet nicht)
- buildWsUrl baut bei https-WSS-Route NIE PORT 8766 an — https://relay.example.com = Reverse-Proxy auf 443; Port-Anbau = 403 vom Proxy = laut Kopplungstod. Plaintext-LAN (http): Port 8766 bleibt. Beweis: WS-Handshake via 443 = 101 Switching Protocols.

- Neue HealthRationaleActivity (Kotlin/Compose, deutsch) + Manifest-Deklaration: androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE + Android-14 activity-alias (VIEW_PERMISSION_USAGE / HEALTH_PERMISSIONS + START_HEALTH_PERMISSION_RATIONALE). Erklärt LESEN-only-Route (Schritte/Puls/Schlaf/Gewicht...) + Zurück-Button. Health Connect zeigt die App NUR mit der Rationale-Activity in der Connected-Apps-Liste.

### P0: Einzeltoggles schrieben nicht ins Gate (dreifach belegt: PAR-1/SK-01/CHEF-01)
- Die Gruppenkarten-`onToggle` verwurfen cap+Zustand (`{ _, _ -> refreshConfig() }` — Redesign-Verlust aus v0.11) → alle 16 Fähigkeiten-Switches in den Gruppenkarten stumm. Jetzt: `CapabilityGate.setEnabled` + Refresh.
### Akku: A11y-Drossel-Rückweg (PAR/chef-02/SK-02)
- WS-Tod (`onClosed`/`onFailure`) resetted nur EventStore-Flags, nicht die Service-QUELLE → A11y-Service blieb nach jeder Stream-Session bis Reboot auf VOLLEM Event-Set. Jetzt `applyEventThrottle(false)` in beide Callbacks.
### Absturz: `/files_permission` auf Android 8–11 (SK-10)
- `Environment.isExternalStorageManager()` = API 30+ ungeschützt beim GET/POST → `NoSuchMethodError` (kein Exception-Fang!) = remote-crashbar. Jetzt SDK-30-Guard (GET mit älterem READ_EXTERNAL-Fallback, POST mit klarer Route).
### UI-Richtigkeit
- **Zeilenhöhe ohne Beschreibungs-Cut (F01, KRIT):** `height(48dp)` klemmte 2-zeiligen Text → Beschreibungen optisch abgeschnitten (PIL: Sub-Bänder 6–9 px). Jetzt `heightIn(min = 48dp)` in CapabilityRow/PermissionRow/MasterCard.
- **Banner-Glyph bei GETRENNT (CHEF-05):** StateBanner-When hatte OFF nicht → else lief auf das Warn-Glyph. Jetzt explizit Off-Glyph.
- **Roter Banner-Titel (F04):** „Kopplungscode abgelehnt" in SignalRed (4,31:1 < AA) → SignalRedText.
- **Busy-Detektor (F05):** „Neuverbindung …"-Texte erkannte weder Banner- noch Karten-Zustandsroute → Spinner/Pill zeigten bei der Wiederholungs-Leiter nichts. Beide Routen ergänzt.
- **Divider-Kontrast (F11):** Trenner/Feld-Border Line→LineStrong.
- **Onboarding-Refresh (CHEF-03):** Rückkehr aus System-Settings bumpt jetzt configVersion (MainActivity `onResume` → `refreshConfig`) — Freigabe-Badges sind sofort frisch.
- Master-Dialog: „Ohne invasive"-Meldung nennt die ECHTE Zahl nach Write (SK-11); toter `masterChecked`-State entfernt.
### Verbindungs-Selbstheilung (SK-03)
- Auth-Sonde (45-min) macht keinen Radio-Burst mehr bei Flugmodus (Verbindungsscheck vor dem Versuch); Takt bleibt bestehen.
### Security
- Logcat leakt nicht mehr den Relay-Host: WebSocket-Log via `safeUrlForLog`, onFailure wirft die Throwable-Message raus (PAR-P2/F1-Politik).
- Undo-Restore mit Frische-Check: bewusste Änderungen im 10-s-Snackbar-Fenster werden nicht überschrieben (SK-04).
### Deutsch-Einheit
- Foreground-Service-Notification des Eingabehilfe-Pfads deutsch („Brücke aktiv"/Kanal-Beschreibung) statt „Connected to server" (F08).
### Bewusst NICHT geändert (dokumentiert)
- PAR-2 (setPackage-Pins auf Settings-Intents): brüchig auf OEM-Varianten (MIUI-Securitycenter et al.), Angriffs-Vektor erfordert bereits installierte Fake-App mit Intent-Filter — resolveActivity-Precheck + Kette bleiben.
- SK-08: Health-Cap-Advertisement ohne Rechte = Design-Natur; Server erhält ordentliche 403-Fehlerantwort mit Rechte-Liste, UI zeigt „0/6 Rechte".
- CHEF-06: Notification-Events kommen seit Android 8 über den NotificationListener-Store; A11y-Mirror war die veraltete Route.
- PAR-3 (LAN-Klartext-Bearer): Bestands-Design (nur Heim-LAN-Bewusstsein im Footer) — TLS/mTLS auf der Handy-Server-Seite = v0.13.0-Kandidat.

### Crashes am Berechtigungs-Fluss (Sir-Meldung: App-„Fehler"-Protokoll bei „Alle Dateien zugreifen")
- **UI „Alle Dateien": deterministischer Crash gefixt.** Die Zeile startete die Listen-Action `MANAGE_ALL_FILES_ACCESS_PERMISSION` MIT package-URI — AOSP-Settings deklariert den package-Filter nur an der per-App-Action; `IntentFilter.matchData` matcht URI↔Filter-ohne-Data NIE → ActivityNotFoundException → ungeschützter Crash. Neu: `startFirstResolvable`-Helper (resolveActivity-Precheck + runCatching) mit Kette per-App-Action → Liste ohne URI → App-Details, Hinweis statt Crash.
- **HUD-Overlay:** dieselbe Fehlerklasse (Listen-Action + package-URI) — gleiche Kette, App-Action/Listen/Details.
- **Eingabehilfe:** gleicher Schutz (OEM-Varianten ohne die Action).
- **Server-Weg `POST /files_permission`:** `startActivity` dort war ungeschützt (ANFE = ganzer Prozess inkl. Bridge) — jetzt `runCatching` + ordnungsgemäße 503-Fehlermeldung.
- **Verbinden mit defekter URL = Crash (QA-Drill T20 am Emulator):** OkHttp `Request.Builder().url()` wirft bei ungültiger host/port-Form synchron (IllegalArgumentException) — Tippfehler im Server-Addr-Feld riss die App ab. Neu: `runCatching` um die Request-Bau + klare Statusmeldung „Server-Adresse ungültig — Format: https://dein-relay.example", kein Reconnect-Loop mit der falschen Adresse, normaler Neuanlauf nach Korrektur.
### Health Connect: „keine Berechtigung erteilt" diagnostizierbar + heilbar
- **Pre-Flight:** Capability-AN schattete noch nichts; `getSdkStatus` BEVOR der Consent-Lauf startet — nicht installiert → Ursachen-Dialog (mit Health-Connect-Systemeinstellungen-API 34+) + Play-Store-Fallback (market→https); Update nötig → eigener Dialog; verfügbar → Consent-Lauf.
- **Differenzierter Callback:** vollständig erteilt (Zahl) / TEILWEISE (fehlende Metriken NAMENTLICH) / leer („still blockiert oder abgebrochen"-Dialog mit Ausweigen — kein Bare-Toast mehr).
- **Zeile zeigt echten Zustand**: Grant-Status via client.permissionController (LaunchedEffect) — „0/6 Rechte" statt vernebelndes „Schalter AN"; Health-Connect fehlt → eigener Badge.
### UI v0.12.0 — „Fähigkeiten-Zentrale" (Material 3 dunkel, Apple-HIG-Qualität)
- **MASTER-SCHALTER „Alle Fähigkeiten"**: ein Schalter für alle 16 — beim AN mit Bestätigungsdialog, der die invasiven 5 NAMENTLICH listet + „Ohne invasive einschalten"-Weg; Master-AUS mit Undo-Snackbar (exakter Zustands-Snapshot + Restore). CapabilityGate bleibt unverändert (nur öffentliche API, neue ui/components/CapabilityMaster).
- **Gruppen-Karten mit Icons + Zähler-Badges** statt unsichtbare Gruppierung: Ansehen & Zustände / Gerät bedienen / Schreiben & Selbstpflege / Invasiv (roter 1dp-Rand — Warnung über FORM). Komplett neue Zeilen (Icon 24dp, ≥48dp ganze Zeile, Switch als Anzeige `onCheckedChange=null` + `toggleable(Role.Switch)`), StatusBadge (Icon+Text+Ton statt reiner Farbe), 30 Vector-Drawables (google/material-design-icons-Pfade, Apache-2.0).
- **Berechtigungen = 2 Zweck-Karten** („Grundlage der Fernsteuerung" / „Daten & Anzeige") statt 8-Zeilen-Monokarte; „Öffnen"-Chips (Sentence-Case); Akku-Zeile mit dem HAUPTGRUND im Text.
- **Tokens:** `SignalRedText` (roter TEXT — SignalRed war 4,31:1 auf Panel, unter 4,5:1-AA), `LineStrong` (Trenner — Line war 1,26:1), `OnEmber`; Shape-Scale (20dp-Hauptkarten), Spacing-Objekt, `FlipsiType.Code`; TalkBack-Headings + stateDescription; keine hartcodierten fontSize/Farben mehr im Screen.
- **Aufräum:** totes Legacy-UI entfernt (`CapabilitySwitches.kt`, `activity_main.xml`, vestigiale toml-Versionen, `caps_section_title`).
### Akku-Optimierung — ohne eine einzige Absenkung der Verbindungswachstufe
- **P0 A11y-Event-Sturm beendet:** Hochfrequenz-Events (CONTENT_CHANGED/SCROLL/FOCUSED/TEXT…) werden im Standardzustand bereits IM SERVICE verworfen (vor Allokation/IPC) und die Event-Quellen per dynamischem serviceInfo im System gedrosselt (DEFAULT-Set = Fenster-/Klick-Events, notificationTimeout 300) — bis 40 Events/s beim Scrollen wecken den Prozess nicht mehr. Volles Set NUR bei aktivem /events/stream.
- **P0 Stream-Leak:** WS-Tod setzt `highVolumeEvents`/`streaming` zurück (vorher blieb Dauer-Vollcapture bis zum Prozesstod).
- **P1 Doppel-Keepalive:** Client-Ping 20→45 s — NAT/NPM hält der 15-s-Server-Heartbeat; −100 Client-Frames/h, Mobilfunk-Radio-Wakes −56 %. Toterkennung stiller Drops 20-40 → 45-90 s (Netzwechsel deckt NetworkCallback sofort ab).
- **P1 Doppel-FGS:** zweite Dauer-Promotion im A11y-Service („Connected to server") gestrichen — die Priorität trägt der dataSync-FGS; ScreenRecorder hebt mediaProjection selbst.
- **P2:** FGS-Notification-Updates per notify() statt voller startForeground()-Neu-Anmeldung + 5-min-Alarms startet den Service nur, wenn er verstorben ist (`instance`-Guard); ScreenRecorder-HandlerThread lazy (lebt nur bei echter Aufnahme, release() räumt ab); ungenutzte SCHEDULE_EXACT_ALARM-Permission entfernt; 5-min-Alarms zünden keinen Versuch bei total fehlendem Netz (Flugmodus — NetworkCallback holt die Rückkehr).
- **P2 Telemetrie:** `/network` liefert `batteryUnrestricted` — verliert das Gerät die Akku-Freistellung, sieht es der Agent sofort.
- Unangetastet (Erreichbarkeit zuerst): Retry-Ladder 1→30 s, 5-min-Low-Rate-Alarm, NetworkCallback-Sofortversuch, 401/403-Stopp, 45-min-Auth-Sonde, Wakelock-Regeln.
### Version
- versionCode 25, versionName 0.12.0. TargetSdk bleibt 34 (Android-15-6-h-FGS-Falle für dataSync ist im Audit dokumentiert; onTimeout-Fallback kommt mit dem 35er-Sprung).

## [0.11.0] - 30.10.2026
### Verbindungskern — Rebuild (Sir-Auftrag: „jederzeit Zugriff, nie neuer Kopplungscode")
- **Kopplungscode-SSOT**: ZWEI divergierende Code-Kopien (PairingManager `pairing_code` vs. RelayClient `relay_pairing_code`) entfernt — UI-Regenerate wirkte sonst nie am WebSocket (die Vorfall-Wurzel: 403 → Relay-Block → „neuer Kopplungscode nötig"). Code wird bei JEDEM Versuch frisch gelesen.
- **Vault-Persistenz**: Code liegt zusätzlich in app-privatem `getDir("vault")` — ein Prefs-Wipe (HyperOS-Cleaner) wird SICHTBAR WIEDERHERGESTELLT (Event RESTORED + Notification), nie mehr still neu generiert. Nur wenn Prefs UND Vault leer sind: Neuanlage, und das mit sichtbarer Meldung.
- **Kein Terminalzustand mehr**: nach dem Retry-Budget läuft die Verbindung im endlosen 5-Min-Modus (Low-Rate) statt „Tap Connect to retry" — nach nachtlichem WLAN-Ausfall kommt die Brücke von allein zurück.
- **Wache (Alarm)**: Versuche ≥60 s laufen als AlarmManager `setAndAllowWhileIdle` statt Coroutine-Sleep — Doze-überlebensfähig. Alarmlauf-Ruf räumt `reconnectPending` selbst (der Stuck-Guard schluckte vorher den Alarmschuss — Emulator-Repro).
- **Auth-Stop-Politik**: 401/403 stoppt nach 3 Versuchen (Relay blockt bei 5 — Endlos-Retry lockte die eigene IP), persistenter `auth_broken`-Zustand + klare UI-Anzeige. 429 = Blockfenster abwarten, ohne Budget zu verbrennen.
- **NetworkCallback**: Netz wieder da → sofortiger Versuch. NUR `onAvailable` (Capabilities-Sturm entfernt — Emulator-Repro).
- **Boot-Restart**: BOOT_COMPLETED/MY_PACKAGE_REPLACED/QUICKBOOT-Receiver + `BridgeConnectionService` (echter FGS, Type dataSync) — nach Handy-Neustart und App-Update startet die Brücke selbst; nicht mehr vom optionalen Accessibility-Service abhängig.
- **FGS-Regel-Crash behoben**: connect()-internes disconnect() rief stopService() im 5-s-FGS-Fenster → `ForegroundServiceDidNotStartInTimeException` (die App starb exakt beim Verbinden — Emulator-Repro). Trennen = nur WS aus, FGS bleibt mit „Getrennt"-Anzeige.
### Health Connect (read-only)
- Route `POST /health_data` (App: `HealthDataBridge`): Aggregate über 1..30 Tage — Schritte, Distanz, Kalorien, Herzfrequenz (min/avg/max), Schlafdauer, jüngstes Gewicht. Keine Rohdaten-Streams (PII-Minimierung). Capability `health`, Default AUS, UI-Schalter + Consent-Dialog im Permissions-Bereich.
- Relay-Allowlist `_ROUTES` + docs/architecture.md + Tool `android_health` (tools/ + /opt-Kopie) + Test-Zähler.
### UI-Redesign (Jetpack Compose, Material 3, dunkel)
- Komplett neue UI (XML-Endlosliste ersetzt): Statusbanner (immer oben, Farbe + Text), geführter Fluss (Schritt 1 Kopplung → Schritt 2 Verbinden), Berechtigungskarte mit HyperOS-Akku-/Autostart-Zeilen, Fähigkeiten in 4 Gruppen (Ansehen/Bedienen/Schreiben/Invasiv), Inline-Ladeanzeige, 48dp-Touch-Ziele, „öffnen"-Chips, kein Sprachmix.
### Relay (Server)
- `hmac.compare_digest`-TypeError-Fall beseitigt (non-ASCII-Token war ein 500er UND ungezählt → Rate-Limit umgehbar): UTF-8-Bytes-Vergleich über `_constant_time_eq`.
- Rate-Limit-Keying per **X-Forwarded-For** (letzter Eintrag = vom trusted Proxy angehängt) — hinter dem NPM teilen sich sonst ALLE externen Clients eine IP (gezielter Lockout des legitimen Handys möglich).
- `/apk/latest`: versionsbewusste APK-Auswahl (0.10.10 > 0.10.7), Chunked-SHA-256 (kein Ganzlesen in RAM), Rejekt-Logging.
- **HOL-Blocking behoben**: Stream-Puffer 8→256, WS-Read-Loop blockiert nie mehr auf einem langsamen HTTP-Konsumenten (put_nowait + bewusster Abbruch bei Überlauf).
- Daemon-Watchdog: toter Relay-Thread beendet den Daemon (exit 2) → systemd startet neu (vorher: „active", aber dunkel).
### Toolchain
- Migration auf Kotlin 2.0.21 / AGP 8.9.1 / Gradle 8.11.1 / compileSdk 36 (Health Connect 1.1.0 verlangt das), targetSdk bewusst 34. ktor 2.3.13.
### Tests
- Kotlin: PairingVaultTest (Robolectric: Restore/Regenerate/SSOT) + Suite (105/107 grün; 2 = Windows-Symlink-Artefakte, Linux-Beleggrünlauf ok). Python: 175/175.
### Emulator-Simulation (Night-QA, Pixel-4-AVD)
- Verlust-Drill: Relay-Kill → Ladder → Wache → automatische Wiederverbindung ohne Eingriff (live belegt, 200). Update-Empfang (MY_PACKAGE_REPLACED → Service-Hochfahrt) live belegt.
### Version
- versionCode 24, versionName 0.11.0.

## [0.10.8] - 25.09.2026
### Security (upstream review, 6 Blocking-Punkte behoben)
- **/apk_install token leak behoben**: Update-Quelle ist FIX auf das konfigurierte Relay (/apk/latest) — kein Aufrufer-URL-Parameter mehr, der Pairing-Token geht nie an fremde Hosts. SHA-256 kommt als X-APK-SHA256-Header serverseitig, wird gegen DIESEN Wert geprüft.
- **CapabilityGate deny-unknown**: Unbekannte Routen sind abgelehnt statt erlaubt. /mic_file ist jetzt an das Gate gebunden (lokal + Relay).
- **Migration für Bestandsinstallationen**: Apps, die vor dem Gating liefen (Relay-URL vorhanden), behalten alles aktiv (legacy_had_relay-Flag bei connect). Neue Installationen starten default OFF.
- **mTLS korrekt über KeyChain**: KeyChain.choosePrivateKeyAlias + getPrivateKey statt der fehlerhaften CA-Store-Annahme.
- **Datei-Pfad-Sanitizing**: unbekanntes Top-Level-Segment wird abgelehnt (kein Root-Fallback mehr); /files_push + /files_delete unter separater Capability "files_write" (destruktiv getrennt vom Lesen).
- **/notify_reply**: eigene Capability "notify_reply" (Outbound-Action ≠ Lesen); RemoteInput.addResultsToIntent statt manueller Bundle-Zusammensetzung.
- **Relay /apk/latest**: Auth gegen denselben Pairing-Code wie alle Routen, konstante Zeit (hmac.compare_digest), Rate-Limiting. Kein separates Token mehr.
- **Kamera-Dialog** nur noch bei aktivierter device-Capability statt bei jedem App-Start.
- **Neue Security-Tests**: tests/test_security_v0_10_8.py (13 Tests: deny-unknown, sanitize, installer, migration, capability separation, relay auth).
### Fork-Hygiene
- Sir-/private-Kontext-Referenzen aus Quellcode entfernt.
### Version
- versionCode 23, versionName 0.10.8 (signierte Release-APK).

## [0.10.0-0.10.7-flipsi] - 21.09.2026
### Neu (Phase 10 - Geräte-Hardware)
- **device-Capability**: Foto auf Befehl (CameraX-frei, CameraManager + ImageReader, Retry-Loop, aufrecht: +90° CW byte-rotation ohne EXIF-Tag - Empirie über 5 Testfotos), Taschenlampe, Netz-Status (WiFi/BT/Internet/metered/transport, Permissions ACCESS_NETWORK_STATE + ACCESS_WIFI_STATE), Lautstärke lesen/setzen (media/ring/alarm/notification), Wecker & Timer (SET_ALARM-Permission, System-Clock-Intent).
- **notifications erweitert**: /notify_reply - Direkt-Antwort in Benachrichtigungen (RemoteInput, WhatsApp/Telegram/SMSe), Antwort über key oder package.
- CAMERA-Laufzeitrecht wird beim App-Start angefragt.
- Python-Tools: android_photo, android_torch, android_network_status, android_volume, android_alarm, android_timer, android_notify_reply.
### Fix
- Foto-Orientierung: 4 Versuche (EXIF-Override, +90, -90, roh) - finale Lösung: byte-level +90° CW, kein EXIF-Tag. Live verifiziert.

## [0.9.6-flipsi] - 21.09.2026
### Kritisch
- **Installer-Start schlug stumm fehl**: REQUEST_INSTALL_PACKAGES + canRequestPackageInstalls()-Check mit Auto-ffnung der "Apps aus unbekannten Quellen"-Einstellungsseite.

## [0.9.5-flipsi] - 21.09.2026
### Neu
- **Generelles Löschen** (Sir-Freigabe 21.09.): /files_delete akzeptiert path ODER paths[] (max 200/Call). Ordner bleiben gesperrt.
- UpdateInstaller sendet Pairing-Code als Bearer (RelayClient.pairingCode zuerst), Relay /apk/latest akzeptiert Token auch als Query-Param.

# Changelog

All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/); this project adheres to Conventional Commits.

## [0.9.2-flipsi] - 21.09.2026
### Kritisch
- **CapabilityGate.init() wurde NIE aufgerufen** — SharedPreferences blieb null: alle Agent-Fähigkeiten-Schalter waren funktionslos (das 403-Gate konnte nie offen sein) und alles fiel nach App-Neustart auf AUS zurück. Jetzt korrekt in BridgeApplication.onCreate initialisiert.
### Hinweis
- Bildschirmaufnahme-Berechtigung gilt laut Android nur pro Sitzung — nach App-Neustart fragt das Consent-Dialogfeld einmal neu (Android-Sicherheit, absichtlich kein Umweg). Der Schalter zeigt den echten Zustand.

## [0.9.0-flipsi] - 21.09.2026
### Neu (Phase 4 — Dateien + Selbstupdate)
- **Datei-Zugriff (Capability "files")**: /files (listen), /files_search (rekursiv), /files_count (Zähler je Endung), /file (Binär-Stream). Lese-only, Pfad-Whitelist, max 512 MB.
- **APK-Selbstupdate (Capability "selfupdate")**: /apk_install mit SHA-256-Pflicht, Installer-Start mit Nutzerbestätigung; Relay /apk/latest (token-geschützt).
- **Python-Tools**: android_files_list/search/count, android_file_get, android_apk_install.

## [0.9.1-flipsi] - 21.09.2026
### Fix
- **Duplikat-Bug:** Die Agent-Faehigkeiten-Liste wuchs bei jedem App-FFnen um einen kompletten Satz (bind() lief in onResume erneut, ohne die alte Liste zu entfernen). Jetzt idempotent ueber getaggten Container.

## [Unreleased]

### Added
- `GET /battery` — battery percentage and charge state straight from `BatteryManager` (`getIntProperty(BATTERY_PROPERTY_CAPACITY)`, API 21; `isCharging()`, API 23) instead of scraping the status-bar accessibility node, which mis-reads across OEM skins and device languages. Neither value depends on the accessibility service running, and at `minSdk 26` neither needs a version gate. Fixed response shape — `batteryPercentage` and `charging`, both always present — because the HTTP and relay transports serialise nulls differently (#103).

### Fixed
- Relay route allowlist lifted out of `_serve()` to module level (`_ROUTES`) so tests can assert on it. `docs/architecture.md` is now pinned to the allowlist in both directions, and the two `android_relay.py` copies are pinned byte-identical. An endpoint added to `CommandDispatcher` without the matching allowlist entry used to fail silently: reachable over direct USB/LAN, refused over the relay — which is the default transport (#103).

## [0.5.0] - 2026-09-05

### Added
- Termux auto-revival watchdog (#100): after repeated relay failures, an **opt-in** (default OFF) switch fires the Termux `RUN_COMMAND` intent to restart the stack, then schedules a delayed reconnect. Pure `RevivalPolicy` gate/counter/cooldown logic with unit tests; `SecurityException` surfaces visibly instead of being swallowed.
- `include_removed` option on `android_notifications` (#100): dismissed notifications are retained on-device but excluded by default — callers must opt in explicitly to read notifications the user has cleared.
- Notification buffer raised from 100 to 300 entries (#100).
- `android_press_key("wake")` turns the screen on via a short auto-releasing wake lock. `power` is unchanged and still opens the long-press power dialog (#101).
- `android_*` tools fall back to reading `ANDROID_BRIDGE_URL`/`ANDROID_BRIDGE_TOKEN` from `~/.hermes/.env` when the gateway process does not export them (`os.environ` still takes precedence) (#101).
- Four microphone tools (`android_mic_record`, `android_mic_stop`, `android_mic_status`, `android_mic_fetch`) with relay-routed binary WAV streaming and `MEDIA:` delivery.
- Recorder state reporting and atomic `.part` → `.wav` finalization so interrupted recordings are never advertised as complete.
- Keep-last-10 retention for completed WAV recordings.

### Fixed
- Mic recorder state machine can no longer stick at `starting` after an early STOP or service destruction — `/mic_start` returned 409 until app restart before (#98).
- Plugin now registers the android usage skill via `ctx.register_skill()`, so agents discover the accessibility-tree-first guidance instead of defaulting to screenshot + vision. Screenshot tool description defers to `android_read_screen`/`android_find_nodes`.
- Use one canonical microphone directory for recording, status, and download.
- Use the on-device-tested `VOICE_RECOGNITION` source with saturating 2.5x PCM gain and stop the recorder service after a STOP command.
- Register microphone routes, schemas, and handlers in both the standalone toolset and installable plugin.
- Replace deprecated aiohttp bare route handlers and add end-to-end binary-stream regression coverage.

### Security
- `android_mic_fetch` connection errors no longer echo the bridge host:port from `requests` exception text (#99).
- Regression coverage for the security-critical mic paths (#99): `MicrophoneRecordingFiles.resolve()` traversal/symlink defense, relay binary-stream negative paths (checksum/length/oversize/disconnect), and the 30-minute sample cap.
- Remove device-specific connection details and legacy SCP instructions from microphone tooling.
- Stop logging notification content and pairing-token prefixes, including in debug builds.

## [0.4.1] - 2026-08-09

### Fixed
- relay reconnect: an unreachable server address retried forever. Each failed attempt fired `onFailure`, which cancelled the retry coroutine and started a new one with a fresh counter, so the 5-attempt cap was never reached and the UI stayed stuck on "Connecting…". Attempt count and exponential backoff now live in a `ReconnectPolicy` whose budget survives across callbacks. The budget is refilled only by a user-initiated Connect or by a session that stayed up ≥60s — reaching `onOpen` is not enough, so a relay that accepts the socket and immediately drops it (wrong pairing code) also stops instead of flapping forever. Callbacks from superseded sockets are ignored via a generation guard, `onClosed`/`onFailure` for one dead socket can no longer schedule two attempts, and the exhaustion path retires the socket so a late callback can't overwrite the "Tap Connect to retry" status
- version string on the main view was hardcoded to `v0.2.0` in both portrait and landscape layouts; now dynamically reads from `BuildConfig.VERSION_NAME`

## [0.4.0] - 2026-08-03

### Security
- sendIntent: block dangerous activity-launch actions (CALL, CALL_PRIVILEGED, ~20 sensitive settings screens) plus an `android.settings.*` prefix catch-all so new settings actions are denied by default (#89, extended by audit)
- sendIntent: URI scheme denylist (`intent://`, `market://`, `tel:`, `smsto:`, `mmsto:`) and `content://settings` / contacts provider prefixes close scheme-based bypasses of the action blocklist; fixes single-colon scheme extraction that let `tel:123` through (#91)
- sendBroadcast: blocklist for dangerous broadcast actions (#80) incl. system-destructive ones (shutdown / master_clear / factory_reset) (#87)
- searchContacts: URL-encode the query to prevent URI injection (#84)
- pairing code now sent as `Authorization: *** header on the relay WebSocket handshake instead of a `?token=` query parameter (which leaks into reverse-proxy access logs); `?token=` fallback fully removed from relay WS auth and plugin copy (#78, #83)
- redact PII-bearing fields (recipient, phone number, message/typed/clipboard text, intent extras) from relay debug body logs
- unauthenticated `/ping` no longer discloses the bridge version (#81)

### Fixed
- recycle intermediate ancestor AccessibilityNodeInfo nodes on the path to a match in ScreenReader.findNodeByTextDfs and ActionExecutor.findNodeByIdInTree — previously leaked on every tap_text/tap-by-id call, exhausting the accessibility node pool over long sessions

### Changed
- docs/install.md is now the single end-to-end install doc: added the on-phone permission-grant checklist and a "Persistent relay (systemd)" section documenting the contrib/ daemon + unit file; README points there and tool count fixed (36 → 38)
- install.sh cleans its temp dir on all exit paths via trap
- document all 38 registered tools in the tools/android_tool.py module docstring; mark android_send_sms/android_call docstrings as destructive (confirm-first)
- `android_read_screen` now excludes System UI (status bar, nav bar) by default for token efficiency; pass `include_system_ui=true` to include it. Use `android_press_key` for back/home/recents (#34, @null-dev)
- screen hashes/diffs no longer churn on clock/battery updates since System UI is filtered from the tree by default (#34)

## [0.3.0]

### Added
- feat(bridge): add per-IP auth rate limiting to BridgeServer (#51)
- Notification listener — agent reads incoming notifications in real-time (`android_notifications` / `android_events`)
- Clipboard bridge — read/write clipboard between server and phone (`android_clipboard_read` / `android_clipboard_write`)
- Direct SMS and calls without UI navigation (`android_send_sms` / `android_call`)
- Location sharing — agent reads phone GPS location (`android_location`)
- hermes-agent v0.3 plugin system integration (`hermes-android-plugin/`, 38 tools)

### Changed
- wrap /wait handler in withContext(Dispatchers.Main) for thread-safety (#55)
- bind relay to localhost by default via ANDROID_RELAY_HOST env var (#38)
- cap ScreenRecorder duration to 30s to prevent OOM (#37)
- wrap ScreenRecorder.record() in Dispatchers.IO (#36)
- strip PII from makeCall and clipboardWrite responses (#35)
- downgrade relay body logging from INFO to DEBUG with truncation (#47, #33)
- strip recipient phone number from SMS success response (#28)
- convert ensureTts from CountDownLatch to suspend function (#27)

### Fixed
- fix(android_macro): halt on transport-level errors, not just success=false (#54)
- add runtime permission guard to location() (#49)
- null-check Bitmap.copy() result in takeScreenshot (#50)
- synchronize NotificationStore getAll/getSince/clear with lock (#45)
- replace ACTION_CUT with ACTION_SET_TEXT empty string in typeText clearFirst (#46)
- use exact-case comparison for pairing code auth (#43)
- align pressKey schema with actually supported keys (#44)
- synchronize EventStore clear(), getAll(), getSince() with lock (#41)
- synchronize NotificationStore.markRemoved() with lock (#42)
- fix ConcurrentLinkedDeque size/removeLast race condition (#39)
- remove dead code in diffScreen — unused currentTexts map (#40)
- recycle AccessibilityWindowInfo objects in /current_app handler (#30)
- recycle AccessibilityWindowInfo in findNodeById and readWidgets (#31)
- recycle AccessibilityWindowInfo in ScreenReader (#32)

[Unreleased]: https://github.com/raulvidis/hermes-android/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/raulvidis/hermes-android/releases/tag/v0.5.0
[0.4.1]: https://github.com/raulvidis/hermes-android/releases/tag/v0.4.1
[0.4.0]: https://github.com/raulvidis/hermes-android/releases/tag/v0.4.0
[0.3.0]: https://github.com/raulvidis/hermes-android/releases/tag/v0.3.0
