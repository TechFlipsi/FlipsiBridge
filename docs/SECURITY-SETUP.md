# FlipsiBridge — Setup mit maximaler Sicherheit

> **Dokumente-Übersicht:** Diese Datei = Sicherheits-Einrichtung · `docs/INSTALL-GUIDE.md` = bebilderte Installation/Deinstallation · **Für KI-Agenten:** `skills/flipsibridge/` im Repo = fertiger Hermes-Skill mit dem kompletten Software-Wissen (ohne private Daten) — in dein Skills-Verzeichnis kopieren und der Agent kennt FlipsiBridge in und auswendig.

Diese Anleitung richtet FlipsiBridge **komplett** und mit der maximalen Sicherheitsstufe ein. Sie ist bewusst so geschrieben, dass sie **von Menschen** UND **von KI-Agenten** (Assistenten, die die Einrichtung übernehmen) Schritt für Schritt befolgt werden kann. Jeder Schritt hat einen prüfbaren Beweis-Befehl — erst wenn der Beweis stimmt, weitergehen.

> **Kurzform:** Der automatische Installer (`install-relay.sh`) installiert bereits die sichere Grundstellung: signierte Downloads (fail-closed), gehärtete systemd-Sandbox mit eigenem System-Benutzer, strenge Authentifizierungs-Limits und ein TLS-Pflicht-Gate im Relay selbst. Die restlichen Schichten (TLS-Zertifikat, mTLS-Gerätezertifikate, Reverse-Proxy-Regeln, Agenten-Anbindung) folgen unten Schritt für Schritt.

---

## 0) Bedrohungsmodell — was diese Einrichtung verhindert

| Angreifer | Verhindert durch |
|---|---|
| Fremder im Internet, der Chat-Daten mitliest | TLS + mTLS-Gerätezertifikate (jedes Handy braucht ein Zertifikat der eigenen CA) |
| Fremder, der sich als dein Handy/Agent ausgibt | Kopplungscode + Token + Client-Zertifikat + IP-Sperre nach Fehlversuchen |
| Manipulierter Download (Supply-Chain) | SHA-256-Integritätsprüfung, fail-closed bei Mismatch |
| Kompromittierter Server-Prozess | systemd-Sandbox (eigener Benutzer, read-only-Dateisystem, Syscall-Whitelist) |
| Token-Klau aus Protokollen | Token nie in Logs (maskiert), Auth-Header statt URL, URL-Parameter werden aktiv abgewiesen |
| Replay alter Nachrichten | Replay-Fenster + Zeitstempel-Prüfung im Protokoll |

**Architektur in einem Satz:** Handy-App → Internet → Reverse-Proxy (TLS + Client-Zertifikats-Zwang, Standard „alles verweigert") → Relay (zweites Zert-Gate + Token + Fehlversuch-Sperren) → Agenten (token-authentifizierte WebSocket-Kanäle).

---

## 1) Grundinstallation (automatisch, schon maximal gehärtet)

```bash
# Empfohlen: Integrität erzwingen (Werte stehen im Release unter backend.sha256)
sudo bash install-relay.sh
```

Der Installer erledigt automatisch:

1. **Signierter Download:** Jede Backend-Datei wird gegen das SHA-256-Manifest des Releases geprüft. Stimmt ein Hash nicht (oder fehlt eine Datei im Manifest), bricht die Installation ab und verwirft die Downloads („fail-closed"). Nicht geprüfte Installationen sind nur mit bewusster Warnung möglich — für Produktion immer den Manifest-Weg nutzen.
2. **Eigener System-Benutzer** `flipsibridge` (keine Shell, kein Login) — der Relay läuft **nicht** als root.
3. **systemd-Sandbox:** `ProtectSystem=strict` (ganzes Dateisystem read-only außer den Relay-Ordnern), `PrivateTmp`, `NoNewPrivileges`, `ProtectKernel*`, Syscall-Whitelist `@system-service`, UMask 0077 (neue Dateien nur für den Service lesbar).
4. **Sichere Secrets-Ablage:** Kopplungs-Token in `/etc/flipsibridge/relay.env`, Rechte 0640 `root:flipsibridge` — systemd liest sie als root ein, sie liegen nie im Relay-Ordner und nie im Repo.
5. **Strikte Authentifizierungs-Limits** (aktiv ab Installation): 3 Fehlversuche → 10 Minuten IP-Sperre; Kopplungsfenster 5 Minuten; Replay-Fenster 64.
6. **TLS-Pflicht im Relay selbst:** Der Relay verweigert den Start auf einer Netz-IP ohne TLS (fail-closed) — ein versehentlich offener Klartext-Dienst ist ausgeschlossen.

**Beweis nach der Installation:**

```bash
systemctl status flipsibridge-relay --no-pager        # muss "active (running)" zeigen
ps -o user= -p "$(systemctl show -p MainPID --value flipsibridge-relay)"   # muss "flipsibridge" zeigen
systemd-analyze security flipsibridge-relay.service   # Exposure-Level sollte niedrig sein (~1.x)
```

### 1b) Reverse-Proxy-Frage — der Installer installiert nie blind

Am Ende fragt der Installer interaktiv (und fragt wirklich — jede Aktion braucht deine Antwort):

1. **„Gibt es bereits einen Nginx Proxy Manager?"**
   - **Nein** → „Soll der Installer NPM jetzt automatisch auf diesem Server mitinstallieren (Docker nötig)?" — Standard **nein**. Nur bei „ja": Docker-Compose-Check, Port-Freiheits-Check (80/81/443), Installation nach `/opt/nginx-proxy-manager`, Admin-Konto mit generiertem Passwort (liegt in `/etc/flipsibridge/npm-admin.txt`, Rechte 600 — Passwort danach ändern!).
   - **Ja** → „Soll der Relay-Host jetzt automatisch im NPM eingerichtet werden?" — dann werden NPM-Adresse, Login, Relay-Domain, Let's-Encrypt-E-Mail und der NPM-Datenordner abgefragt, und der Installer legt Zertifikat + Proxy-Host + mTLS-Zwang (421 ohne Geräte-Zertifikat) automatisch an. **Die Geräte-CA erzeugt der Installer dabei selbst** (falls noch keine da ist): eine echte, frisch generierte 4096-Bit-eigene CA — kein Standard-Zertifikat; der Fingerprint wird angezeigt und ist in der App prüfbar. **Geräte-Zertifikate legt der Installer bewusst NICHT an** — die stellst du (oder dein Agent) gezielt aus: `bash contrib/flipsibridge-ca.sh issue <gerätename>` (siehe §3a). Mit „nein" bekommst du stattdessen eine fertig konfigurierte Vorlagendatei (`/etc/flipsibridge/npm-advanced-config.txt`) für Copy-Paste in die NPM-Weboberfläche — inklusive der CA-Befehle zum Ausfüllen.

**Ohne Terminal (Agent/CI) passiert gar nichts am Proxy**, außer du gibst es vor: `FLIPSI_NPM_MODE=none` überspringt den Teil, `FLIPSI_NPM_INSTALL=1` installiert NPM automatisch, `FLIPSI_NPM_AUTOSETUP=1` richtet den Host automatisch ein (dazu `FLIPSI_NPM_URL/EMAIL/PASSWORD`, `FLIPSI_RELAY_DOMAIN`, `FLIPSI_LE_EMAIL`). Jede NPM-Aktion bricht bei Fehlern sauber ab und nennt den manuellen Ausweg — es bleibt nichts Halbfertiges zurück.

---

## 2) TLS — zwei Wege, beide gültig

### Weg A: Reverse-Proxy vor dem Relay (empfohlen für Internet-Zugriff)
Der Proxy (nginx / Nginx Proxy Manager / Traefik) übernimmt das öffentliche Zertifikat (z. B. Let's Encrypt) und reicht an den Relay weiter. **Pflicht:** dem Relay die Proxy-IP nennen, sonst teilen sich alle Clients einen Rate-Limit-Bucket:

```bash
# in /etc/flipsibridge/relay.env ergänzen:
ANDROID_RELAY_TRUSTED_PROXY_IP=<IP-des-Proxys>   # z. B. 192.0.2.10
ANDROID_RELAY_HOST=127.0.0.1                     # Relay nur noch lokal lauschen lassen
sudo systemctl restart flipsibridge-relay
```

### Weg B: Natives TLS am Relay (ohne Proxy, z. B. direkter Domain-Betrieb)
```bash
sudo mkdir -p /etc/flipsibridge/tls && sudo chmod 750 /etc/flipsibridge/tls
sudo openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
  -keyout /etc/flipsibridge/tls/relay.key -out /etc/flipsibridge/tls/relay.crt \
  -subj '/CN=relay.example.com' -addext 'subjectAltName=DNS:relay.example.com'
# in /etc/flipsibridge/relay.env:
ANDROID_RELAY_TLS=1
ANDROID_RELAY_CERT=/etc/flipsibridge/tls/relay.crt
ANDROID_RELAY_KEY=/etc/flipsibridge/tls/relay.key
sudo systemctl restart flipsibridge-relay
```

**Beweis:** `curl -v https://relay.example.com/` muss ein gültiges Zertifikat zeigen; `http://` (Klartext) darf auf einer Netz-IP nicht funktionieren.

---

## 3) mTLS-Gerätezertifikate — die wichtigste Schicht

Ohne ein Zertifikat deiner eigenen Geräte-CA kommt **kein** Handy an die Chat-Routen. Das ist ein zweischichtiges System:

- **Schicht 1 (Proxy):** Der Proxy verlangt das Client-Zertifikat bei jedem Chat-Pfad — ohne Zertifikat: HTTP 421, der Request erreicht den Relay nie.
- **Schicht 2 (Relay):** Mit `ANDROID_MTLS_ENFORCE=1` prüft der Relay zusätzlich den vom Proxy durchgereichten Zertifikats-Namen gegen seine Geräte-Registry (fail-closed bei Registry-Fehlern). Headers von Nicht-Proxy-Adressen werden ignoriert (nur die `TRUSTED_PROXY_IP` darf sie setzen).

### 3a) Geräte-CA anlegen und Handy-Zertifikat ausstellen

```bash
sudo contrib/flipsibridge-ca.sh init              # einmalig: eigene CA erzeugen
sudo contrib/flipsibridge-ca.sh issue meinhandy    # Client-Zertifikat (PKCS#12) ausstellen
```
Das Passwort für die `.p12`-Datei liegt bei (Datei `<name>.p12pass`, Rechte 600). Die CA-Dateien **niemals** committen oder auf den Server-Relay legen — sie bleiben auf deinem Verwaltungsgerät.

### 3b) Proxy auf Client-Zertifikats-Zwang + „alles verweigert" stellen

Muster für nginx (direkt oder als „Advanced"-Konfiguration im Nginx Proxy Manager):

```nginx
# Client-Zertifikat verlangen (optional = fordern an, ablehnen regelt die Map unten)
ssl_client_certificate /etc/flipsibridge/device-ca.pem;
ssl_verify_client optional;
ssl_verify_depth 2;

# STANDARD ZU: alle Chat-Routen ohne gültiges Zert → 421 (Default-Deny-Allowlist)
map "$host:$request_uri" $fb_mtls_block {
    default                                  421;
    "~^relay\.example\.com:/mtls/enroll"     0;    # einzig freie Route: Geräteeinschreibung
    "~^relay\.example\.com:/agent_ws"         0;    # Agenten-Kanal (Token-Auth im Relay)
    "~^relay\.example\.com"                  421;  # alles andere: zu
}
# im server{}-Block:
#   if ($fb_mtls_block = 421) { return 421; }
```

**Wichtige Regeln:**
- **Jede neue Route ist automatisch gesperrt** (Default-Deny). Neue freie Routen nur bewusst mit einer eigenen Zeile freigeben.
- Query-Parameter sind Teil des Musters: `/agent_ws?token=…` wird abgewiesen — Tokens gehören in den `Authorization`-Header, nie in URLs (sonst landen sie in Access-Logs).
- `/mtls/enroll` muss zert-frei bleiben (Henne-Ei: ein neues Handy hat noch kein Zertifikat). Es ist durch den Kopplungscode authentifiziert und prüft den Code **vor** dem Lesen des Bodys — Massen-Anfragen können keine Einschreibungs-Slots belegen.

### 3c) Relay-Gate aktivieren (Schicht 2)

```bash
# in /etc/flipsibridge/relay.env (NACHDEM der Proxy steht):
ANDROID_MTLS_ENFORCE=1
sudo systemctl restart flipsibridge-relay
```

**Beweis (alle vier müssen stimmen):**

```bash
curl -i https://relay.example.com/                 # → 421 (kein Zertifikat)
curl -i https://relay.example.com/mtls/enroll      # → 405 (existiert, nur POST)
curl -i https://relay.example.com/agent_ws         # → 401 (Token-Prüfung, kein Zert nötig)
# mit gültigem Handy-Zertifikat + App-Kopplung: Chat verbunden (App zeigt „verbunden")
```

### 3d) Handy einschreiben (Enrollment)
In der App: Zertifikat importieren (`.p12` + Passwort), Server-Adresse und Kopplungscode eingeben, „Zertifikat anfordern". Das Gerät landet in der Relay-Geräte-Registry; der Alias kann in der App gesetzt werden. **Empfehlung:** nach der Ersteinrichtung den Kopplungscode rotieren (App-Seite: neue Kopplung erzeugen), damit der Erstcode aus dem Setup nirgendwo mehr gilt.

---

## 4) Agenten anbinden (Drei-Agenten-Muster)

Agenten verbinden sich über `/agent_ws` mit einem **servergenerierten Token** (kein Zertifikat, weil Agenten keine Geräte sind):

1. **Einladung (Admin-Gerät):** In der App als Admin einen Einladungscode erzeugen (TTL standardmäßig 60 Minuten, begrenzte Anzahl offener Einladungen).
2. **Agent verbindet:** `Authorization: Bearer <Einladungscode>` an `/agent_ws` → der Relay erstellt nach der Registrierung einen dauerhaften Agenten-Token (`ANDROID_AGENT_TOKEN_<ID>` in der Relay-Config, Rechte 600) und antwortet mit dem Token.
3. **Dauerbetrieb:** Der Agent nutzt den dauerhaften Token. Tokens stehen nur in der Config-Datei (0600) und im Speicher — Protokolle zeigen ausschließlich Masken.

**Sicherheits-Eigenschaften des Agenten-Kanals (im Code verankert):**
- Token-Vergleich constant-time (kein Timing-Orakel), Fehlermeldung uniform („Invalid agent token") — fehlender und falscher Token sind nicht unterscheidbar.
- Fehlversuche zählen pro IP → Sperre; Brute-Force wird aktiv gebremst.
- Tokens servergeneriert mit kryptografischem Zufall (32 Zeichen URL-safe).

---

## 5) Fein-Härtung — Env-Variablen (nur verschärfbar)

Alle Werte sind nach oben begrenzt (Clamping) — man kann sie nur **strenger**, nie schwächer als die Defaults stellen:

| Variable | Sicherer Wert | Bedeutung |
|---|---|---|
| `ANDROID_RELAY_AUTH_MAX_ATTEMPTS` | 3 | Fehlversuche bis IP-Sperre |
| `ANDROID_RELAY_AUTH_BLOCK_SECONDS` | 600 | Sperrdauer |
| `ANDROID_RELAY_AUTH_WINDOW_SECONDS` | 60 | Zeitfenster der Zählung |
| `ANDROID_RELAY_PAIRING_TTL_SECONDS` | 300 | Kopplungsfenster (Werte 60–600 erlaubt) |
| `ANDROID_RELAY_PAIRING_PENDING_MAX` | 4 | max. gleichzeitige Kopplungsanfragen |
| `ANDROID_RELAY_REPLAY_WINDOW` | 64 | Replay-Schutz-Fenster |
| `ANDROID_RELAY_FRAME_MAX_AGE_MS` | 120000 | max. Alter von Frames |
| `ANDROID_RELAY_CHAT_SPOOL_MAXLEN` / `..._MAX_BYTES` | 200 / 4 MiB | Offline-Spool-Deckel |
| `ANDROID_RELAY_INVITE_TTL_MIN` | 60 (5–480) | Agenten-Einladungsgültigkeit |
| `ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS` | 14 (0=aus, max 365) | Auto-Löschung inaktiver Geräte (Admin-Geräte bleiben geschützt) |
| `ANDROID_RELAY_DEBUG_BODY_LOGGING` | nicht setzen | Nur für punktuelle Diagnose — in Produktion AUS lassen |

---

## 6) Verifikations-Checkliste (am Ende einmal komplett durch)

```bash
□ systemctl is-active flipsibridge-relay                                   # active
□ ps -o user= -p "$(systemctl show -p MainPID --value flipsibridge-relay)" # flipsibridge
□ systemd-analyze security flipsibridge-relay.service                       # niedrige Exposure
□ curl -i https://relay.example.com/                                        # 421
□ curl -i https://relay.example.com/mtls/enroll                             # 405 (nur POST)
□ curl -i https://relay.example.com/agent_ws                                # 401 (Token-Welt)
□ openssl s_client -connect relay.example.com:443 </dev/null | head        # gültiges Zert
□ stat -c '%a %U:%G' /etc/flipsibridge/relay.env                            # 640 root:flipsibridge
□ journalctl -u flipsibridge-relay -n 50 --no-pager                         # keine Permission-Fehler
```

---

## 7) Anweisungen für KI-Agenten (wenn ein Agent die Pflege übernimmt)

1. **Default-Deny ist heilig:** Jede neue zert-freie Route braucht eine bewusste Allowlist-Zeile im Proxy. Nie „zum Test" den 421-Zwang aufheben.
2. **Tokens niemals** in Chat, Logs, Doku oder Commits ausgeben — nur Pfade und Längen. Log-Ausgaben des Relays sind maskiert, so lassen.
3. **Registry-Edits nur bei gestopptem Relay** (`systemctl stop` → edit → `start`), sonst wirft der laufende Prozess die Änderung beim nächsten Speichern weg. Backups vor jedem Edit.
4. **421-Antworten auf Chat-Routen sind der erwünschte Zustand** — nicht als Fehler „reparieren".
5. **Kopplungstest-Budget:** Authentifizierungs-Fehlversuche sperren echte IPs (300 s Standard). Proben am Live-System minimal halten (max. 2 je Endpunkt).
6. **Hotfix-Falle:** Nach `.py`-Patches `__pycache__` löschen und neu starten, sonst läuft alter Bytecode; Jeder Fix bekommt eine temporäre Debug-Zeile als Wirksamkeits-Beweis.
7. **Updates über den signierten Weg** (Installer mit Manifest) — nie unversionierte Dateien direkt auf die Produktion legen.
8. **Gateway-/Service-Neustarts** beeinflussen laufende Agent-Verbindungen — prüfen und transparent melden.

---

## 8) Betriebshygiene

- **Zertifikate:** Der Proxy erneuert seine Zertifikate automatisch (z. B. Let's Encrypt). Tote Domains/Zertifikate aus der Erneuerungs-Rotation entfernen — sie verursachen Fehlerlärm, der echte Erneuerungsfehler überdeckt.
- **Geräte-Hygiene:** Inaktive Geräte löschen sich nach `DEVICE_MAX_IDLE_DAYS` selbst; Admin-Geräte bleiben geschützt.
- **Backups:** Relay-Config (`relay-config.json`, `relay-keys.json`, `relay-devices.json`) regelmäßig sichern — Rechte 600/640 beibehalten. **Ohne** die Key-Datei sind alte Ende-zu-Ende-Archive nicht lesbar.
- **Updates:** Neue Version über den Installer (signiert) einspielen, `systemctl restart`, dann die Verifikations-Checkliste einmal durchlaufen.

*Stand: 28.11.2026 — entspricht dem Relay-Stand 0.18.2 (Release `v0.18.2`). Änderungen an dieser Datei gehören in denselben Commit wie die Code-Änderungen, auf die sie sich beziehen.*

## 9) Deinstallation — mit Auswahl, nie blind

```bash
sudo bash install-relay.sh uninstall
```

Der Deinstallations-Modus fragt **jeden Teil einzeln** ab (und ohne Terminal/Env-Vorgabe löscht er gar nichts):

1. **FlipsiBridge-Relay entfernen?** (Service + Unit + Installationsordner + Secrets + System-User)
2. **Relay-Host im NPM mit löschen?** (via NPM-API, falls der Installer ihn eingerichtet hat — der Domain-Eintrag wird aus dem Gedächtnis gelesen)
3. **Nginx Proxy Manager entfernen?** — wird NUR angeboten/ausgeführt, wenn der Installer ihn selbst installiert hat (Gedächtnis-Merker `npm=self-installed`); danach wahlweise Container stoppen oder Daten+Verzeichnis komplett löschen. NPM, das du selbst installiert hattest, bleibt unangetastet. Docker selbst wurde nie vom Installer installiert und bleibt.
4. **Geräte-CA + Zertifikate löschen?** (Standard: bleiben liegen — für spätere Neuinstallation nutzbar)

Beispiele: „nur FlipsiBridge weg, NPM behalten" = Frage 1 ja, 2–4 nein. Für Agenten/CI: `FLIPSI_UNINSTALL="relay host"` (Leerzeichen-getrennt: `relay host npm ca`) oder `FLIPSI_UNINSTALL=all`.

Das Installations-Gedächtnis liegt in `/etc/flipsibridge/.installer-state` (was der Installer selbst angelegt hat) — danach richtet sich, was der Deinstallations-Modus anbietet.
