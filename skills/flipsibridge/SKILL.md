---
name: flipsibridge
description: "FlipsiBridge-Softwarewissen für Agenten: Relay-Architektur, Endpunkte, Auth-Schichten, Installation/Update/Deinstallation, Debug- und Verify-Rezepte. Enthält KEINE privaten Daten."
version: 1.0.0
author: TechFlipsi
license: MIT
tags: [flipsibridge, relay, android, websocket, security, install]
---

# FlipsiBridge — Software-Referenz für KI-Agenten

Dieser Skill enthält das vollständige Wissen über die FlipsiBridge-Software (Android-App + Server-Relay + Agenten-Anbindung), damit Agenten Installation, Betrieb, Härtung und Fehlersuche selbstständig und korrekt durchführen. Er enthält bewusst **keine privaten Daten** (keine Domains, IPs, Tokens, Gerätenamen oder personenbezogenen Angaben) — trage Betriebsdaten NIE hier ein, sondern nur in die Server-Konfiguration.

Begleit-Dokumente im Repo: `docs/SECURITY-SETUP.md` (Sicherheits-Einrichtung Schritt für Schritt) · `docs/INSTALL-GUIDE.md` (bebilderte Installation/Deinstallation) · `deployment/` (Vorlagen) · `contrib/` (Utilities).

## Was FlipsiBridge ist
FlipsiBridge verbindet eine **Android-App** (Handy-Oberfläche) über einen **Server-Relay** (Python/aiohttp) mit **AI-Agenten** (z. B. Hermes-Instanzen): Chat-Nachrichten, Medien, Cron-Verwaltung und Geräteverwaltung laufen über WebSocket-Kanäle, Ende-zu-Ende verschlüsselt (Double-Ratchet-Prekeys). Der Relay ist die zentrale Drehscheibe — die App fragt nie Agenten direkt ab.

## Architektur (Bausteine)
- **App (Android/Kotlin):** UI + Chat + Cron-Verwaltung; verbindet per WebSocket + mTLS-Gerätezertifikat.
- **Relay (`tools/android_relay.py`, ~9000 Z. Python):** WebSocket-Hub, Geräte-Registry, Pairing, Enrollment, Spool (Offline-Nachrichten), Rate-Limits, TLS-Enforcement, E2E-Key-Verteilung. Läuft als systemd-Dienst im eigenen System-User (Sandbox: ProtectSystem=strict, Syscall-Whitelist @system-service, UMask 0077).
- **Daemon (`contrib/hermes-relay-daemon.py`):** dünner Watchdog-Wrapper (Start/Restart des Relay-Threads).
- **Agenten-Adapter (`contrib/hermes-chat-plugin/adapter.py`):** verbindet Agenten per Token an `/agent_ws` (kein Client-Zertifikat — Agenten sind keine Geräte).
- **CA-Utility (`contrib/flipsibridge-ca.sh`):** erzeugt die Geräte-CA (4096 Bit, CA:TRUE critical) und stellt Client-Zertifikate (PKCS#12, ~825 Tage) aus.
- **Reverse-Proxy (empfohlen: Nginx Proxy Manager oder nginx):** TLS-Terminierung + Client-Zertifikats-Zwang (mTLS) vor dem Relay.

## Ports & Pfade (Standardwerte)
- Relay lauscht auf `localhost:8766` (im Proxy-Betrieb NUR lokal; Netz-IP ohne TLS = Startverweigerung, fail-closed).
- Installation: `/opt/flipsibridge` (`tools/`, `contrib/`) · Secrets: `/etc/flipsibridge/relay.env` (0640) · mTLS-Registry: `/etc/flipsibridge/tls/` (je Request frisch gelesen — Edits wirken ohne Restart) · Geräte-CA: `/root/mtls-ca/` (chmod 700).
- State-Dateien im Relay-Ordner: `relay-config.json`, `relay-devices.json`, `relay-keys.json`, `pairing_password.json` (0600) — Backup-pflichtig, NIE committen.

## Routen- und Auth-Modell (2 Welten + 2 Schichten)
1. **Geräte-Welt (App):** Routen `/ws`, `/chat`, `/chat_event`, `/media`, `/key_publish`, `/key_get`, `/crypto_hello` — hinter **mTLS** (Proxy-Client-Zert-Zwang: ohne Zert = 421; Relay-Gate prüft zusätzlich CN gegen Registry, `ANDROID_MTLS_ENFORCE=1`).
2. **Einschreibung:** `/mtls/enroll` (+ `/challenge`) — einzig zert-freie Route (Henne-Ei); auth per Kopplungscode, Code wird VOR dem Body-Parsing geprüft (kein Enrollment-DoS möglich).
3. **Agenten-Welt:** `/agent_ws` — zert-frei, Token-Auth (constant-time, uniforme Fehlermeldung „Invalid agent token", IP-Sperre nach Fehlversuchen). Tokens servergeneriert (32 Z. URL-safe), nur in 0600-Config + RAM, Logs maskiert.
4. **Default-Deny:** Jede neue Route ist gesperrt — Proxy-Allowlist mit exakten Ausnahmen (Query-Parameter werden abgewiesen: Tokens gehören in den `Authorization`-Header, nie in URLs).

## Wichtige Env-Variablen (nur verschärfbar — Clamping fail-closed)
`ANDROID_BRIDGE_TOKEN` (Kopplungscode) · `ANDROID_RELAY_TLS=1` + `CERT/KEY` (Domain-TLS) · `ANDROID_RELAY_TRUSTED_PROXY_IP` (sonst teilen alle IPs einen Rate-Limit-Bucket) · `ANDROID_MTLS_ENFORCE=1` (Relay-Gate) · `AUTH_MAX_ATTEMPTS=3` / `AUTH_BLOCK_SECONDS=600` / `AUTH_WINDOW_SECONDS=60` · `PAIRING_TTL_SECONDS` (60–600) / `PAIRING_PENDING_MAX` · `REPLAY_WINDOW` · `FRAME_MAX_AGE_MS` · `CHAT_SPOOL_MAXLEN`/`MAX_BYTES` · `INVITE_TTL_MIN` (5–480) · `DEVICE_MAX_IDLE_DAYS` (14 Default; Admin-Geräte bleiben geschützt) · `DEBUG_BODY_LOGGING` nur für punktuelle Diagnose, Produktion AUS.

## Installation / Update / Deinstallation (alles abgefragt — nie blind)
- **Install:** `sudo bash install-relay.sh` — signierter Download (SHA-256-Manifest, fail-closed), System-User, Sandbox-Unit, Secrets 0640, Limits aktiv. Interaktiv: NPM vorhanden? → Auto-Einrichtung (Zert + Host + mTLS) oder manuelle Vorlage. Die **Geräte-CA erzeugt der Installer** (echte 4096-Bit-CA, kein Standard); **Geräte-Zertifikate stellt bewusst der Admin/Agent aus** (`flipsibridge-ca.sh issue <name>`).
- **Agent/CI ohne Terminal:** `FLIPSI_NPM_MODE=none|ask`, `FLIPSI_NPM_INSTALL=1`, `FLIPSI_NPM_AUTOSETUP=1`, `FLIPSI_NPM_URL/EMAIL/PASSWORD`, `FLIPSI_RELAY_DOMAIN`, `FLIPSI_LE_EMAIL`, `FLIPSI_TARGET`, `FLIPSI_ENV` — ohne Terminal UND ohne Env passiert am Proxy nichts.
- **Uninstall:** `sudo bash install-relay.sh uninstall` — fragt 4 Teile einzeln (Relay / NPM-Host / NPM falls selbst installiert / CA), Gedächtnis `/etc/flipsibridge/.installer-state`; Env: `FLIPSI_UNINSTALL="relay host npm ca"` oder `all`. Ohne Terminal + Env wird NICHTS gelöscht.

## Verify-Rezepte (nach JEDEM Eingriff)
- Service: `systemctl is-active flipsibridge-relay`; User-Check: `ps -o user= -p $(systemctl show -p MainPID --value flipsibridge-relay)` = `flipsibridge`; `systemd-analyze security` = niedrige Exposure.
- mTLS-Matrix (erwartet): `/ `→ 421 ohne Zert · `/agent_ws` → 401 · `GET /mtls/enroll` → 405 (allow: POST) · mit Zert + App: Chat verbunden.
- Logs: `journalctl -u flipsibridge-relay -f` („Relay started on port 8766", „mTLS-Enrollment-Routen aktiv", „Agent … connected", „Phone connected").

## Betriebs- und Fehler-Regeln (hart)
1. **Default-Deny ist heilig:** Neue freie Routen nur bewusst mit Allowlist-Zeile; 421 auf Chat-Routen = erwünschter Zustand, kein „Fehler".
2. **Registry-Edits nur bei gestopptem Relay** (`systemctl stop` → edit → `start`), sonst wirft der Prozess die Änderung beim nächsten Persist weg; Backup vorher.
3. **Hotfix-Falle:** Nach `.py`-Patches `__pycache__` löschen (`rm tools/__pycache__/android_relay.cpython-*.pyc`) + jede Änderung mit TEMP-DEBUG-Zeile beweisen (feuert sie nicht, läuft alter Bytecode).
4. **Token-Disziplin:** Tokens/Codes nie in Chat, Logs, Doku oder Commits — nur Pfade + Längen. Tokens liegen in der `relay.env`/`relay-config.json` (0600).
5. **Auth-Fehlbudget:** Fehlversuche sperren IPs (Standard 300 s / konfiguriert 600 s) — Proben am Live-System minimal halten (max 2 je Endpunkt), niemals fluten.
6. **Updates signiert:** Installer mit Manifest (`FLIPSI_SHA256_ASSET_URL`) — nie unversionierte Dateien direkt auf Produktion.
7. **Enrollment-Fenster epoch-blind:** Neu-Enroll nur bei leerer Registry oder nach bewusstener Code-Rotation; Fenster nicht mit Test-Enrolls verbrennen.
8. **Drei Registry-Welten nicht verwechseln:** Geräte-Registry (Pairing-ID, `X-Device-Id`) ≠ mTLS-Registry (`/etc/flipsibridge/tls/`, Zert-CN-ID) — Kopplungsfehler je nach Symptom in der RICHTIGEN Datei fixen (Diagnostic: „N Gerät(e) bekannt" im Start-Log).
9. **NPM-API-Fallen:** PUT akzeptiert nur Feld-Whitelists (Voll-Body = 400); Login kann Token als Cookie statt im Body liefern; vor jedem Host-Save den Altwert lesen (advanced_config sonst verloren); Einzeldatei-Bind-nginx: Edits brauchen Container-Reload, um zu wirken.
10. **Zertifikate:** Proxy erneuert automatisch (Let's Encrypt) — Tote Domains aus der Renewal-Rotation entfernen (sonst Fehlerlärm, der echte Erneuerungsfehler überdeckt).

## Typische Fehlerbilder → Ursache
| Symptom | Ursache/Weg |
|---|---|
| Relay startet nicht, „without TLS is refused" | `ANDROID_RELAY_TLS=1` ohne CERT/KEY — beides setzen oder localhost-Betrieb |
| Handy abgewiesen trotz gültigem Zert | mTLS-Registry: `epoch_of_enroll` ≠ `epoch` (Epoch-Divergenz) oder Pairing-ID nicht in Geräte-Registry |
| Enrollment OFF im Log | `/etc/flipsibridge/tls` für den Service-User nicht schreibbar (Parent-Betretbarkeit! 0750 root:flipsibridge + 0770 tls) oder Unit-`ReadWritePaths` fehlt |
| Agent-WS still tot | `FLIPSI_*`-Zeilen in der Agent-Profile-.env fehlen/veraltet → Token aus relay-config (0600) prüfen, Adapter liest je Reconnect frisch |
| „immer derselbe Fehler" am Handy | okhttp-Backoff — App KOMPLETT schließen + neu öffnen erzwingt frischen Versuch |
| 401 „Invalid agent token" | Token falsch/rotiert — Wert aus `ANDROID_AGENT_TOKEN_<ID>` in relay-config, Adapter-.env aktualisieren |

*Stand: 28.11.2026, Repo `TechFlipsi/FlipsiBridge` (Release v0.18.2). Änderungen an diesem Skill gehören in denselben Commit wie die Code-Änderungen.*
