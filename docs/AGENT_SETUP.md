---
summary: "Komplette Installation von FlipsiBridge durch einen Hermes-Agenten — inklusive Copy-Paste-Prompt."
read_when:
  - "Ein anderer Hermes-Agent soll FlipsiBridge komplett aufsetzen"
  - "Manuelle Installation von Hand"
---

# Agent-Setup — FlipsiBridge in einem Rutsch installieren

Diese Anleitung ist dafür gedacht, dass ein **Hermes-Agent (J.A.R.V.I.S.) auf dem Server-Host** die
Installation **selbstständig** durchführt. Der Mensch wird nur an den Stellen gebraucht, die
physisch am Handy passieren müssen (APK installieren, Zertifikat installieren, Berechtigungen
erteilen, Pairing-Code ablesen).

---

## 1. Copy-Paste-Prompt für deinen Hermes-Agenten

Diesen Block dem Agenten als Auftrag geben — er führt alles selbst aus und meldet sich nur bei den
markierten Handanlege-Schritten:

```text
Richte FlipsiBridge komplett auf diesem Server ein. Führe alles selbst aus — frage mich nur bei
den mit [HAND] markierten Schritten. Prüfe vor jedem Schritt, ob er nicht schon erledigt ist
(idempotent arbeiten), und lege vor jeder Systemänderung ein Backup an.

1. Voraussetzungen prüfen: Hermes-Agent v0.3.0+, python3 >= 3.11, aiohttp installiert
   (sonst: pip install aiohttp), git, openssl.
2. Plugin installieren:
   git clone --depth 1 https://github.com/TechFlipsi/FlipsiBridge /tmp/flipsibridge
   rm -rf ~/.hermes/plugins/hermes-android
   cp -r /tmp/flipsibridge/hermes-android-plugin ~/.hermes/plugins/hermes-android
   rm -rf /tmp/flipsibridge
   Danach den Hermes-Gateway-Dienst neu starten und mit /plugins prüfen, dass das
   Plugin geladen ist (android_*-Tools sichtbar).
3. Relay als Dauerdienst: FlipsiBridge nach /opt/flipsibridge legen
   (git clone https://github.com/TechFlipsi/FlipsiBridge /opt/flipsibridge),
   systemd-Unit contrib/hermes-android-relay.service nach
   /etc/systemd/system/flipsibridge-relay.service installieren (Pfad im Unit-File auf
   /opt/flipsibridge anpassen), Unit enablen und starten. Das Relay lauscht auf Port 8766.
4. Verschluesselung (fuer Fernsteuerung ausserhalb des Heimnetzes, empfohlen):
   - Reverse-Proxy (NPM/nginx/caddy) mit Let's-Encrypt-Zertifikat auf eine Subdomain
     relay.<deine-domain> legen, WebSocket-Upgrade erlauben, Ziel
     http://127.0.0.1:8766 (bzw. Server-LAN-IP:8766).
   - mTLS: contrib/flipsibridge-ca.sh init  (CA einmalig erzeugen)
     contrib/flipsibridge-ca.sh issue <handy-name>  (Client-Zertifikat, P12 + Passwort)
     Den Proxy auf ssl_verify_client umstellen, CA als Client-CA eintragen.
   [HAND] Die P12-Datei auf das Handy bringen und dort installieren
     (Einstellungen -> Sicherheit -> Zertifikate installieren -> VPN & Apps).
   - In der App als Server-Adresse https://relay.<deine-domain> eintragen — die App
     baut daraus automatisch wss://. Klartext ist app-seitig gesperrt (Ausnahme:
     ein explizit konfigurierter LAN-Host).
5. Pairing:
   [HAND] Mich bitten, in der FlipsiBridge-App den Pairing-Code anzuzeigen (12 Zeichen)
   und mir den Code zu nennen.
   Dann: Code als ANDROID_BRIDGE_TOKEN=~/.hermes/.env eintragen (chmod 600),
   Relay-Dienst neu starten, Verbindung in der App aufbauen lassen.
6. Verifikation (ALLES muss gruen sein, erst dann fertig):
   - android_ping() liefert Antwort
   - Proxy-Endpunkt erreichbar: https://relay.<deine-domain>/ping mit Bearer-Token -> HTTP 200
   - /ws-Handshake via wss durchlaufen (HTTP 101)
   - android_read_screen() liefert die Accessibility-Struktur
   - Berechtigungen am Handy: Accessibility-Dienst + Notifications an (App-Fuehrung zeigt es)
   Berichte am Ende: welche Schritte gelaufen, welche Version installiert (APK-Version
   in der App sichtbar), welche Punkte gruen sind. Keine Secrets (Tokens, Passwoerter,
   Codes) im Chat ausgeben.
```

---

## 2. Was der Agent dabei konkret macht (Referenz)

| Schritt | Wo | Wichtig |
|---|---|---|
| Plugin | `~/.hermes/plugins/hermes-android` | liefert die `android_*`-Tools im Agent |
| Relay-Daemon | `/opt/flipsibridge/contrib/hermes-relay-daemon.py` | systemd-Unit `flipsibridge-relay.service`, Port 8766 |
| Token | `~/.hermes/.env` als `ANDROID_BRIDGE_TOKEN` | = der Pairing-Code aus der App (12 Zeichen) |
| TLS | Reverse-Proxy vor dem Relay | Let's Encrypt + WebSocket-Upgrade; Relay selbst bleibt im LAN |
| mTLS | `contrib/flipsibridge-ca.sh` | CA + pro Gerät ein Client-Zertifikat (P12); Proxy verlangt es (`ssl_verify_client`) |
| APK | GitHub-Releases oder `./gradlew assembleRelease` | eigener Keystore, siehe `docs/RELEASING.md` |

## 3. Sicherheitsmodell (Kurzfassung)

- Handy verbindet sich **ausgehend** zum Relay — kein Port-Forward am Handy, NAT-freundlich.
- Transport: `wss://` (TLS 1.3 via Proxy). Klartext ist in der App per
  `network_security_config.xml` verboten; einzige Ausnahme ist ein explizit konfigurierter
  LAN-Host (z. B. `jarvis.lan`).
- Zwei Faktoren: Client-Zertifikat (mTLS) + 12-Zeichen-Pairing-Code (Bearer-Header, nie in der URL).
- **Capability-Gating**: nur freigeschaltete Fähigkeiten aktiv; SMS senden, Anrufe, Kontakte
  und GPS sind bewusst gar nicht im Build.
- Rate-Limiting am WebSocket-Handshake (5 Fehlversuche / 60 s → 5 Min Sperre).

Details: [SECURITY.md](../SECURITY.md) · Architektur: [architecture.md](architecture.md)

## 4. Nach der Installation testen

```bash
# Vom Server-Host (Token in ~/.hermes/.env):
curl -H "Authorization: Bearer $ANDROID_BRIDGE_TOKEN" https://relay.<deine-domain>/ping
```

Erwartet: `pong` bzw. HTTP 200. Danach im Agent `android_ping()` und einen Read-Screen-Test
fahren. Wenn der Proxy mTLS erzwingt: Test ohne Client-Zertifikat muss abgewiesen werden
(400/403 im TLS-Handshake).