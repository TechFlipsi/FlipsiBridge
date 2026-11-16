# Arbeitsplan: Agenten-„Anruf" in FlipsiBridge (W17-Kandidat)

Stand: 16.11.2026 · Status: PLAN (wartet auf Sirs „mache"-Go) · Trigger: Sir-Idee im Discord-Chat,
Beispiel „He Jarvis rufe mich jeden Tag um 12 Uhr an und gib mir das Wetter durch".

## 1. Ziel (Besucher-Story)
- Agent kann den Nutzer ÜBER DIE APP anrufen: Vollbild „incoming call"-Screen (WhatsApp-Muster).
- Beim Annehmen redet der Nutzer frei; seine Stimme kommt beim Agenten ALS TEXT an (Sprach→Text).
- Der Agent antwortet GESPROCHEN (TTS) — nutzer kann mit SIE direkt reden, ohne Tastatur.
- Anrufe sind terminierbar: Agent-Cron jeden Tag 12:00 → Anruf → Wetter durchsagen.
- Chat-Protokoll: „Anruf (2:41)"-Eintrag wie WhatsApp, verpasste Anrufe als Systemzeile
  (WA-Referenz „Verpasster Sprachanruf · Zum Zurückrufen tippen").

## 2. Was heute schon existiert (Basis-Nachweis)
| Baustein | Status | Datei |
|---|---|---|
| E2E-Kanal App↔Relay↔Gateway | aktiv (Double Ratchet) | RelayClient.kt, adapter.py |
| Foreground-Service (App läuft dauerhaft) | aktiv | App-Service |
| Mikrofon-Aufnahme + RECORD_AUDIO | aktiv | VoiceMemoRecorder.kt |
| Agent-Antwort als Audio (TTS) | aktiv | ChatSpeechSpeaker.kt |
| Agent-seitige Zeitplanung (Cron) | aktiv (Gateway) | hermes cronjob |
| Wallpaper/Chat-Infra | aktiv | ChatWallpaper.kt |
| Neubau nötig | Call-Signaling, CallScreen, STT-Loop, Tool | — |

## 3. Architektur-Abwägung
### 3.1 Audioweg — bewusst KEIN WebRTC in v1
Anruf ist KEIN Telefonat Ton-zu-Ton: Nutzeraudio wird transkribiert (Text), Agentenantwort ist TTS.
Deshalb reicht der bestehende E2E-Text-/Datenkanal — kein P2P/TURN, keine Server-Komponente neu.

Turn-basiert (Half-Duplex, wie ChatGPT-Voice):
1. Nutzer redet → On-Device-Transkript läuft mit (Zwischenwort beim Agenten sichtbar).
2. Nutzstille (VAD ~700 ms) → Turn fertig → Agent denkt → sagt TTS-Sätze (chunked, flüssig).
3. Barge-in: Nutzer beginnt zu reden → Agent-Ausgabe stoppt (VAD am Mic aktiv).

### 3.2 Nutzeraudio → Text: zwei Varianten
| Variante | Vor | Nach | Empfehlung |
|---|---|---|---|
| A: On-Device `SpeechRecognizer` | kostenlos, sofort einsatzbereit, kein Ton verlässt Handy | Qualität geräteabhängig (HyperOS nutzt Google-Speech-Provider, dt. i.d.R. gut) | **v1 = A** (Feature-Flag) |
| B: Server-Whisper (faster-whisper am kibot) | beste Qualität, gleiche Stimmqualität überall | Latenz +0,3-1 s, Audio-Chunk verlässt Handy (verschlüsselt bis Relay), Server-Last | v2-Option hinter Flag |

Ton verlässt das Gerät in Variante A NIEMALS — passt zur Datenschutz-Linie (P3).

### 3.3 Agentenantwort → Stimme
- ChatSpeechSpeaker-Pfad erweitern: Agent kann im Call-Modus SÄTZE chunkweise als TTS senden
  (adapter markiert message mit call_chunk_seq) — erste Worte gehen raus, während der Agent
  noch denkt (kein langes Stillschweigen).
- Call-Fallback: fehlgeschlagene Calls bleiben als normale Chat-Audionachricht (Punkt 31-Bubble).

### 3.4 Anruf-Signaling (neu, klein)
Neue E2E-Nachrichtentypen über die bestehende WS-Verbindung:
`call_invite {agent, kind, reason}` → `call_ringing` (App quittiert)
→ `call_accepted | call_declined | call_missed | call_ended {duration}`.
Relay braucht KEINE neue Route für die Verbindung (Signaling läuft im bestehenden Kanal).

### 3.5 Agent-Tool + Terminierung
- Neues Bridge-Tool `call_user(reason: str, say: str | null)` je Agent (Capability „calls",
  gleiche Gate-Mechanik wie interaction/open_app).
- Terminierung: Agent nutzt seine Cron-Macht — „jeden Tag 12 Uhr" = hermes cronjob →
  call_user(reason="Wetter durchsagen"). Kein Extra-Scheduler in der App.
- Verpasster Anruf (App offline/abgewiesen): Agent fällt auf Chat-Nachricht zurück
  (Inhalt als Voice-Note + Text) — nichts geht verloren.

### 3.6 App-UI/Android-Integration
- Incoming: Full-Screen-Intent-Notification (sperrt über den Lock-Screen, WA-Muster) + Vibrieren.
- CallScreen: Profilbild (Punkt 32-Avatar), Name, Dauer, Mute/Ende + „Lautsprecher/Ohr" Wechsel.
- FGS-Typ-Erweiterung `microphone` (Android 14 Pflicht-Deklaration) damit Aufnahme auch
  bei gesperrtem Bildschirm weiterläuft; AudioManager MODE_IN_COMMUNICATION + AEC
  (Sprachqualität ohne Echo).
- Nutzeraufruf des Agenten (Rückrichtung): Button im WhatsApp-Composer (v1.1, gleicher Kanal).

## 4. Umsetzungs-Schritte
| Phase | Inhalt | Aufwand |
|---|---|---|
| P1 | Call-Signaling-Typen in App+Adapter+Relay-Log (kein UI) | S |
| P2 | Adapter: bridge_call-Tool + TTS-Chunk-Marker + Cron-Rezept | M |
| P3 | App: CallScreen + Full-Screen-Intent + Ring + FGS mic + AudioManager | M/L |
| P4 | STT-Loop (SpeechRecognizer) + VAD/Barge-in + Turn-Logik | M |
| P5 | Call-Verlauf im Chat (Anruf-Bubbles, „Zurückrufen"-Zeile) + Settings-Toggle | S |
| P6 | QA-Drill: Emulator-Ring/Annahme/Abweisung + Live-Anruf Sir + Wetter-Cron-Demo | M |

Abhängigkeiten: Chat-Redesign Welle 16 (30/31/32) zuerst — CallScreen erbt Header/Avatar/Bubbles.
Emulator-Limitation: Ring bei gesperrtem Gerät am Emulator nur bedingt beweisbar →
Live-Drill am Sir-Handy (Xiaomi 15U) ist Pflicht-P6.

## 5. Risiken
1. HyperOS-Batterie/App-Killer: FGS läuft heute zuverlässig (Bridge verbindet dauerhaft),
   Call braucht zusätzlich FGS-Typ mic — Autostart/Akkuspar-Whitelist beim Nutzer erklären (App-Hinweis).
2. STT-Qualität on-device variiert → Flag B (Server-Whisper) als Ausweg; Qualität messen, dann entscheiden.
3. Full-Screen-Intent wird von DND/„Nur-Benachrichtigungen"-Modi gedämpft — WA hat dasselbe
   Verhalten; Settings-Hinweis „Anrufe erlauben" bauen.
4. Play-Deklarationen: FGS-Typen + Berechtigungen sauber deklarieren (Play-Gate W4).
5. Latenz je Turn realistisch 1,5-3 s (Transkript + Denken + erste TTS-Chunks) — Erwartung setzen.

## 6. Offene Fragen an Sir (nur diese, dann Bau)
1. STT v1 = on-device (A) okay? (Ton verlässt Handy nicht — schneller Start, Qualität ggf. B danach)
2. Sollen verpasste Anrufe automatisch zurückrufen? Empfehlung: NEIN — Chat-Fallback + Tap „Zurückrufen".
3. „Chat löschen" im ⋮-Menü (Punkt 32) — sollen auch ANRUF-Verlaufszeilen mit verschwinden? (Emp: ja)
4. Reihenfolge: Welle 16 Chat-Redesign fertig bauen → dann Call-Feature (empfohlen, gleicher Code-Foundation).

---

## 7. PAKET „HAUPT-SPRACHASSISTENT" — Gemini-Parität (Sir-Zusatz 16.11.26)
Ziel: Wenn Leute den Agenten als ERNSTEN Tages-Assistenten führen, muss er das liefern,
was Gemini am Handy liefert — soweit ohne System-Rechte machbar. Nachtrag zum Call-Paket:

| # | Gemini-Funktion | Machbarkeit in FlipsiBridge | Phase |
|---|---|---|---|
| G1 | Standard-Assistent (Power-Taste halten) | Assistenten-Rolle vorhanden (Bug-Karte 12 wird gefixt) — nach Fix die ZWEITür: bei Anruf-Feature „Assistent-Modus" = Anruf direkt aus der Role-Invocation starten | C+ |
| G2 | Floating Bubble über allen Apps (Overlay) | SYSTEM_ALERT_WINDOW-Overlay-Bubble, Tap = Voiceturn öffnen (wie Gemini-Bubble) | C |
| G3 | Sperrbildschirm-Fragen | Role-Assistant gibt Sperrbildschirm-Invocations auf vielen Geräten — Test-Matrix nötig (HyperOS!) | C+ |
| G4 | Live-Gespräch ohne Aufwachen (Follow-ups) | Call-Feature P4 (Turn-Loop) — erweitern auf „Session bleibt offen, Frage hinten nach" | C |
| G5 | „Was ist DAS hier?" — Screen kontext | A11y interaction-Capability besteht schon: Screen-Content lesen → als Kontext an Agenten, explizite Zustimmung je Session (Gemini-Pattern) | C+ |
| G6 | Voice-Routinen/Abriefungen („jeden Morgen Briefing") | Agent-Cron + call_user / Voice-Note-Fallback — aus Call-Punkt 3.5 | C |
| G7 | Erinnerungen/Termine per Stimme | Agent hat Tools (HA/Kalender) — Voice-Ingest aus dem Call-Loop reicht | C |
| G8 | Apps öffnen/steuern per Stimme | /open_app + A11y interagieren via Agent schon da — Voice-Frontend draufsetzen | C |
| G9 | „Hey Jarvis"-Wake-Wort IMMER-LISTEN | Ehrlich: ohne System-Rechte auf Stock-Android/HyperOS NICHT verlässlich (Google-Hotwort ist systemseitig reserviert). Machbar: Mikrofon-Modus im App-Fokus + Wake für Autostart-Sessions. Wake-Experiment (on-device microWakeWord) = separates Experiment-L, erst nach C0-Funktion | P3/Experiment |
| G10 | Anruf/Telefon steuern (anrufen, auflegen) | call_user + Agent-Tool `call_user` — Telefon-App via interaction öffnen für echte Telefonate | C+ |
| G11 | Wisch-Access + „Seite fragen“ (Link schicken, Text/Stimme) | Gesture-Nav: Wisch von UNTEN-ECKE startet den Standard-Assistenten = unser Mini-Overlay über JEDEM Fenster (Browser!) → URL kriegen wir sauber via `AssistContent` (Chrome/Browser liefern die aktuelle Seiten-URL an den Assistenten) + Universal-Fallback: Browser-Teilen-Button → FlipsiBridge (Share-Target, geht auf jedem Gerät). Mini-Panel: Chat-Schnelleingabe Text + Mikro-Turn, Kontext-Option „Seite lesen“ (G5/A11y). Mid-Screen-Edgewis ohne Systemrechte: NICHT zuverlässig — Ecke-Wisch ist das Android-native Äquivalent | C+ |
| G12 | SCREENSHOT-AN-AGENT (nur für den Agenten, NICHT in Galerie) | Bedienungshilfen-Screenshot-API (Service.takeScreenshot, Android 11+): Fang in RAM → downscale + EXIF-Strip → JPEG → E2E-Sendung als Bild-Message → Agent-analyse via Vision → lokale Kopie SOFORT gelöscht; KEIN MediaStore/Galerie-Schreiben, KEIN Media-Projection-Dialog. Button im Mini-Panel (G11) + Chat-Attach + optional Quick-Tile; Standard = 3-s-Bestätigungs-Chip, Power-User-Toggle ohne Nachfrage (Capability-Verband) | C+ |

### 7.1 Wisch-Access & „Seite fragen“ (G11, Sir 16.11.)
- **Trigger:** Gesture-Nav-Navigation → Wisch von der unteren Ecke nach innen öffnet den
  Standard-Assistenten. Ist FlipsiBridge der digitale Assistent (G1), öffnet sich NIEDRIG unser
  Mini-Overlay (nicht die App!) über dem gerade offenen Fenster (Browser).
- **URL-Kontext:** Der Browser liefert die aktuelle Seiten-URL über `AssistContent` an den
  als Assistenten registrierten Prozess — kein URL-Abschreiben, kein A11y-Hack.
- **Universal-Fallback:** Share-Target (action.SEND http/https): In jedem Browser „Teilen →
  FlipsiBridge" — gleiche Mini-Panel-Ergebnis, geht auch wo Assistent-Rolle fehlt.
- **Mini-Panel (Overlay):** Profil-Avatar, Textfeld + Mikro-Turn-Button (P4-Loop), Kontext-Chip
  „Seite“ (Liest via G5/A11y wenn freigeschaltet), Buttons „Fragen" | „Zusammenfassen“ | „Link in Chat“.
  Antwort-Auswahl: Overlay-Antwort (kompakt) ODER weiter zum Chat.
- **Ehrliche Grenze:** Ein Wisch aus der BILDSCHIRM-MITTE (kein Ecke-Trigger) geht für Dritt-Apps
  nicht zuverlässig (System-Gestenzuordnung). Corner-Wisch = Android-native Funktion, die
  JEDEM Assistenten gehört; alles andere = Overlay-Bubble (G2) als Tap-Knopf.
### 7.2 Screenshot-Button NUR für den Agenten (G12, Sir 16.11.)
- **Ziel:** Wie Gemini — "Was ist das hier?" mit einem echten Screengrab, das NIE in die
  Galerie/Handy-Storage landet.
- **Weg:** Keine Media-Projection-Dialogkette nötig — die bestehende Bedienungshilfen-Service
  (interaction-Fähigkeit) nimmt ab Android 11 Screenshots selbst (Service.takeScreenshot).
  Bild lebt ausschließlich im RAM.
- **Privacy-Kette:** Downscale (max ~1400 px) → EXIF/Geo-Strip → JPEG q80 → verschlüsselt (E2E)
  als Bild-Message an den gewählten Agenten → Vision-Analyse → RAM-Kopie gelöscht. KEIN
  MediaStore-Insert, KEIN Storage-Write — die Galerie sieht die Datei NIE.
- **UI:** Button im G11-Mini-Panel + Chat-Attach-Menü + optional Quick-Tile. Standard: kurzer
  Bestätigungs-Chip (3 s, One-Tap) — Power-Toggle "ohne Nachfrage" im Capability-Verband.
- **Agent-Seite:** Gateway-Adapter empfängt Bild (opak), Typ image_screengrab; Antwort mit
  Vision direkt in der Konversation; Option "Bild aus Chat entfernen" nach der Antwort
  (Ein-Tap-Verbrauch, Gemini-Muster).
Legende: C = Call-Wellen-Phasen aus diesem Plan, C+ = Anschluss-Erweiterung direkt danach.
Reihenfolge-Basis: Welle 16 (Chat/Composer) → Anruf-Kern (P1-P6) → Assistent-Paket (G1-G8, G10) →
Wake-Experiment (G9) NUR wenn die Kern-Wellen sauber sitzen.

Zusätzliche Play-Hinweise: SYSTEM_ALERT_WINDOW (Overlay) = Extra-Deklaration im Gate;
A11y-Usage für G5 bleibt im bestehenden Capability-Freischalt-Muster (Nutzerklick, kein Silent).
