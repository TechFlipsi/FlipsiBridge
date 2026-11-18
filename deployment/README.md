# P23 — Deployment-Härtung: Relay als eigener System-User + Sandbox-Vollset

**PH-4-T3 — Signed-Deployment (Integrität, fail-closed):** Der
Einzeiler-Installer (`install-relay.sh` am Repo-Root) prüft jede
heruntergeladene Backend-Datei gegen ein SHA-256-Manifest. Zwei dokumentierte
Wege:

1. **Release-Metadaten-Manifest (Produktion, PFLICHT):** Das Release-Asset
   `backend.sha256` (koordinator-seitig je Release neben dem Backend-Tar
   hochgeladen) im `sha256sum`-Format `<hex>  <name>`; jede Zeile deckt eine
   Downloaddatei ab. Installer-Verband: Asset-URL
   `https://github.com/TechFlipsi/FlipsiBridge/releases/latest/download/backend.sha256`
   (Override `FLIPSI_SHA256_ASSET_URL`); Mismatch oder fehlender Eintrag =
   Abbruch mit erwartet/erhalten (nur Hash-Präfixe).
2. **Einzel-Hash (Quickstart):** `INSTALL_SHA256=<hex>` prüft die Hauptdatei
   `tools/android_relay.py` gegen den erwarteten Hash.

Kein Weg verfügbar → Installer läuft mit `UNVERIFIZIERT`-Warnung weiter
(Dokument-Verband bewusst: fehlende Quelle ist kein Mismatch, verboten ist
nur der verifizierbar falsche Deploy). Release-Pflicht des Koordinators:
`backend.sha256` mit den exakt gepackten Dateien (`tools/android_relay.py`
+ `contrib/hermes-relay-daemon.py`) je Release erzeugen und hochladen
(`sha256sum <dateien> > backend.sha256`).

**Status der oben stehenden P23-Anleitung: REPO-VORLAGE — NICHT am Live-System ausgeführt.** Diese Anleitung
beschreibt die Migration des systemd-Relays von `User=root` auf den
dedizierten System-User `flipsibridge` plus geschlossenem Sandbox-Vollset.
Ausführung = bewusster root-Eingriff am Host, den der Koordinator/Sir mit
Freigabe durchführt (Live-Daemon = TABU bis dahin, W1A-1-Prinzip).

Vorlage: [`flipsibridge-relay.service`](./flipsibridge-relay.service)

---

## 1. Ist-Zustand (Grund der Migration)

- Die live-Unit `/etc/systemd/system/flipsibridge-relay.service` läuft mit
  `User=root` (Härtung-Grundstock vom 02.11.26: NoNewPrivileges, ProtectSystem=full,
  PrivateTmp, CapabilityBoundingSet leer, RestrictAddressFamilies, Restart=always).
- `ProtectSystem=full` schützt nur /usr,/boot,/etc — der Relay-Prozess könnte
  als root überall sonst schreiben (Home, /root-Secrets, /var ganz frei).
- Ein Relay-Compromisering wäre damit root = Server-Kompromiss.
- Ziel: Blast-Radius auf den Service-Account eingrenzen + FS strikt read-only
  mit Ausnahmeliste (ProtectSystem=strict + ReadWritePaths).

## 2. Vorbereitung — Drei-Sync-Pflicht (B3) VOR der Migration

1. Repo-Stand auf den Host-Sync-Pfad bringen (backend-Parität, 4 Kopien =
   byte-identisch: `tools/android_relay.py`, `hermes-android-plugin/android_relay.py`,
   `/opt/flipsibridge/tools/android_relay.py`, Gateway-Plugin-Kopie).
2. Erst NACH bestandener Parität (`md5sum` je Paar) die Unit migrieren.
3. Test-Parität: `pytest tests/test_android_relay.py -k byte_identical` (grün).

## 3. System-User anlegen (Migrations-Schritt 1, als root)

```bash
# nologin, keine Home-Shell, kein Passwort-Login möglich:
adduser --system --group --home-dir /opt/flipsibridge \
        --no-create-home --shell /usr/sbin/nologin flipsibridge
```

Auswirkung: Der User kann sich nicht anmelden, kein Interaktiv-Login, kein
sudo; nur systemd führt Prozesse unter dieser Kennung aus.

## 4. Secret-Env-Datei versetzen (Migrations-Schritt 2)

Neue Lage statt `/root/.secrets/flipsibridge-relay.env`:

```bash
install -d -m 0750 /etc/flipsibridge
install -m 0640 -o root -g flipsibridge /root/.secrets/flipsibridge-relay.env \
        /etc/flipsibridge/relay.env
```

- `0640 root:flipsibridge`: Service-User darf LESen, nicht schreiben; root
  behält die Schreibverantwortung (Konfig-Drift = root-Task).
- systemd liest `EnvironmentFile` selbst als root — Werte gelangen nur in den
  Unit-Prozess, nicht in Journals (`systemctl show` zeigt keine Werte).

## 5. TLS-Keys + Rechte-Tabelle (Migrations-Schritt 3)

Der Relay-Prozess liest das TLS-Keyfile selbst (User-Wechsel ⇒ Lese-Rechte nötig):

| Pfad | Rechte | Begründung |
|---|---|---|
| `/etc/flipsibridge/relay.env` | `0640 root:flipsibridge` | Secrets, Service liest |
| `/etc/flipsibridge/tls/relay.crt` | `0644 root:root` | Public-Material |
| `/etc/flipsibridge/tls/relay.key` | `0640 root:flipsibridge` | Private Key — Gruppe kann lesen, Welt nicht |
| `/opt/flipsibridge/**` (State) | `0755`-Dir / `0600`-Dateien, Besitzer `flipsibridge:flipsibridge` | Relay schreibt State (relay-keys.json, relay-devices.json, pairing_password.json, relay-config.json, Prekey-Backups) |
| Unit-Datei | `0644 root:root` | keine Secrets im Unit-File — Token/Einstellungen nur über relay.env |

```bash
# Besitzverhältnisse auf State-Verzeichnis umstellen (einmalig, Migration):
chown -R flipsibridge:flipsibridge /opt/flipsibridge
# TLS-Schlüssel lesbar fürs Service-Konto machen:
chgrp flipsibridge /etc/flipsibridge/tls/relay.key
chmod 0640 /etc/flipsibridge/tls/relay.key
```

**Hinweis root→user-Zwangsumzug:** Dateien, die der bisherige root-Relay in
`/opt/flipsibridge` angelegt hat, sind root-owned. Ohne `chown` startet der
User-Relay mit Permission-Fehlern (Fail-closed: Registry/Prekey nicht
schreibbar = Fehler im Log). Nach der Migration werden NEUE Dateien dank
`UMask=0077` automatisch mit `0600`/Dirs `0700` angelegt.

## 6. Unit installieren + aktivieren (Migrations-Schritt 4)

```bash
cp deployment/flipsibridge-relay.service /etc/systemd/system/flipsibridge-relay.service
chmod 0644 /etc/systemd/system/flipsibridge-relay.service
systemctl daemon-reload
systemctl restart flipsibridge-relay.service
systemctl --no-pager status flipsibridge-relay.service
```

Vorlagen-Platzhalter vor dem Einsetzen anpassen: `ANDROID_RELAY_HOST`
(Ziel-IP), Proxy-IP, Port, Zertifikatspfade, `ExecStart`-Python-Pfad und
`ReadWritePaths`.

## 7. Verifizierung nach der Migration (Beweispflicht)

```bash
# 1) User-Verband — keins dieser Felder darf root bleiben:
systemctl show flipsibridge-relay.service -p User -p Group -p NoNewPrivileges -p ProtectSystem -p CapabilityBoundingSet -p RestrictAddressFamilies -p SystemCallFilter
# 2) Sandbox-Score (systemd-analyze security; Ziel = "exposed": niedrig):
systemd-analyze security flipsibridge-relay.service
# 3) Prozess-Besitzer live belegen:
ps -o user= -p "$(systemctl show -p MainPID --value flipsibridge-relay.service)"
# 4) Funktion: ws-Verbindung Phone/Agent + key_get/crypto_hello 200 (Drill),
#    + Journalfenster prüfen: journalctl -u flipsibridge-relay.service -b
```

Abnahme-Kriterien: User=flipsibridge · ProtectSystem=strict · SystemCallFilter
gesetzt · Prozess-Owner flipsibridge · Drill grün · keine Permission-Errors
im Journal.

## 8. Rollback (beweisbar)

```bash
# Vor der Migration Unit sichern:
cp /etc/systemd/system/flipsibridge-relay.service \
   /etc/systemd/system/flipsibridge-relay.service.pre-p23.bak
# Rollback: Backup zurück, daemon-reload, restart, Besitzverhältnisse belassen:
cp /etc/systemd/system/flipsibridge-relay.service.pre-p23.bak \
   /etc/systemd/system/flipsibridge-relay.service
systemctl daemon-reload && systemctl restart flipsibridge-relay.service
```

- Die (root-Zugang ermöglichende) `chown`-Umstellung BLIEBT im Rollback-Stand —
  root kann auch über flipsibridge-eigene Dateien schreiben; Rückstellung ist
  optional und nicht funktionstragend.
- EnvironmentFile-Pfad im Rollback: Alt-Unit liest `/root/.secrets/...` — muss
  dort existieren (nicht löschen bis nach dem Rollback-Fenster).

## 9. Auswirkungen / Einschränkungen (ehrlich)

- **Funktional:** Relay braucht keine Sonderrechte (TCP-Listen auf hohem Port
  >1024, keine Device-Zugriffe, kein CapNetBind) ⇒ kein Capability-Set nötig
  ⇒ leeres `CapabilityBoundingSet` ist funktionsneutral.
- **Sandbox-Optionen:** Die Vorlage läuft ab systemd 247 (Ubuntu 22.04/24.04,
  Debian 12); auf älteren Units unbekannte Direktiven = Warnung, nicht Start-
  verweigerung. `SystemCallFilter=@system-service` ist die breite Referenz-
  whitelist für Netz-Daemons (CPython/aiohttp bewegt sich darin komplett).
- **O3 (keine Security-Interna öffentlich):** Diese Vorlage nutzt bewusst
  Platzhalter (RFC 5737 `192.0.2.x`, `.invalid`-Domain). Echte IPs/Domänen/
  Hostnamen NUR in der lokalen relay.env — nie in Commits, README, Issues.
- **Nicht-Teil dieser Migration:** App-Code, Adapter, Gateway-Plugin (separate
  Deploy-Schiene); `/opt`-Sync bleibt Koordinator-Aufgabe (W1A-1).
