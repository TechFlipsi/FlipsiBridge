# FlipsiBridge — Projekt-Spec

> Android-Geräte-Node für J.A.R.V.I.S. (Hermes Agent) — Fork von [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android) (MIT).
> Ziel: Eine App, die dem Agent **Hände** gibt — Screen, Tippen, Kamera, Sprache, Notifications — so sicher wie möglich, so selbstbestimmt wie möglich. Richtung: „wie die Gemini-App, nur mehr".

## Architektur

```
Handy (FlipsiBridge-App)  ──wss://+mTLS──>  Server (Relay :8766)  ──localhost──>  Hermes Agent
       outgoing, NAT-frei              NPM-Proxy + Let's Encrypt        42 android_* Tools
```

- Handy verbindet sich **ausgehend** (funktioniert in jedem Netz, kein Port-Forward am Handy).
- Erreichbar unterwegs **ohne VPN** über `relay.<deine-domain>` (NPM → wss://, Let's Encrypt).
- **Zugang nur mit Client-Zertifikat (mTLS):** NPM verlangt ein von unserer Server-CA ausgestelltes Zertifikat. Ohne Zertifikat → Abweisung vor dem HTTP-Dialog. Zwei Faktoren: Zertifikat + Pairing-Code.
- Das Hermes-Dashboard bleibt wie gehabt hinter NPM + IP-Allowlist — wird NICHT geöffnet.

## Sicherheits-Entscheidungen (bewusst anders als das Original)

| Thema | Original (hermes-android) | FlipsiBridge |
|---|---|---|
| Berechtigungen | Alles im Manifest, alles aktiv | **Capability-Gating:** nur freigeschaltete Fähigkeiten aktiv; SMS/Kontakte/GPS bleiben KOMPLETT draußen |
| Transport | ws:// möglich (Klartext) | **Nur wss://** + Client-Zertifikat, Klartext wird verweigert |
| APK | Unsignierter Debug-Build | **Eigener Release-Keystore**, SHA-256-Fingerprint dokumentiert |
| Mikrofon | Ambient-Audio im Hintergrund | **Nur auf Befehl** (kein Dauer-Mikrofon in Phase A–C) |
| Tools | Alle 42 immer registriert | Relay registriert nur Tools der freigeschalteten Capabilities |

**Fähigkeiten-Liste (Phase A):** Screen lesen · Tippen/Swipen · Apps öffnen · Notifications lesen · Kamera · Screenshots.
**Bewusst weggelassen:** SMS senden, Anrufe, Kontakte, GPS, QUERY_ALL_PACKAGES.

## UI-Neubau

Original-UI: Terminal/Cyberpunk-Stil (Pixel-Font, Neon-Grün/Orange, Circuit-Hintergrund). Konsistent, aber für eine täglich genutzte Assistent-App zu unruhig.

**Neu:** Sauberer Material-3-Dunkelstil.
- Flacher dunkler Hintergrund (#111318), Karten statt Circuit-Muster
- Moderne Sans-Serif (Roboto/system), Monospace NUR für Code/Pairing
- Orange als einziger Akzent (#ED7931, an Flipsi-Brand angelehnt)
- Status als kompakte Chip-Leiste oben, Berechtigungen als klare Liste mit Erklärtext
- Assistenten-Panel später als Overlay (Phase C)

## Phasen

- [x] **Phase 0 — Vorarbeiten:** Fork, Clone, SDK-Check, Baseline-Build, Plugin serverseitig installiert
- [ ] **Phase 1 — Baseline:** Rebranding (FlipsiBridge), neue UI, mTLS-Support im Client (OkHttp + Client-Zertifikat), signierter Build, Relay auf Server (systemd), Capability-Gating Grundgerüst
- [ ] **Phase 2 — Assistent:** VoiceInteractionService, Assistenz-Rolle (Ecken-Swipe öffnet uns), AssistStructure-Screen-Kontext
- [ ] **Phase 3 — Overlay:** Gemini-artiges Overlay-Panel über jeder App, Chat mit dem Agent
- [ ] **Phase 4 — Kamera + Dateien:** CameraX-Foto auf Befehl, Datei-Zugriff
- [ ] **Phase 5 — Sprache:** Porcupine-Wake-Word lokal, Streaming-Sprachdialog
- [ ] **Phase 6 — Polish:** HyperOS-3-Batterie-/Autostart-Tuning, Overlay-Polish, Icons (textfrei, Flipsi-Stil)

## Test-Gerät

Xiaomi 15 Ultra, HyperOS 3. Berechtigungen werden von Sir manuell erteilt; Installation per ADB vom Server.

## Repo-Regeln (Fork)

- Upstream: raulvidis/hermes-android — Bugfixes upstream-würdig werden als PR/Issue zurückgemeldet (Issues #107–#109 sind gestellt).
- Eigenes Rebranding + Sicherheits-Features bleiben in diesem Fork; keine Aufspaltung der Historie.
- Build: `./gradlew assembleRelease` mit eigenem Keystore (`keystore/flipsibridge.jks`, Passwort in Server-Env, nie im Repo).