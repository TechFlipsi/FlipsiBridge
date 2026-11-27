#!/bin/bash
# flipsibridge-relay-setup.sh — Einzeiler-Installer fürs FlipsiBridge-Python-Relay (Server-Seite)
#
# SICHERHEIT (Standard = maximal, s. docs/SECURITY-SETUP.md):
#   - Signierter Download (SHA-256-Manifest, fail-closed bei Mismatch)
#   - Relay läuft als eigener System-User `flipsibridge` (keine Shell, kein Login)
#   - systemd-Sandbox-Vollset (deployment/flipsibridge-relay.service)
#   - Secrets in /etc/flipsibridge/relay.env (0640 root:flipsibridge)
#   - Strikte Auth-Limits AKTIV ab Installation
#   - TLS-Pflicht im Relay-Code (fail-closed auf Netz-IPs ohne TLS)
#
# REVERSE-PROXY (NPM) — NIE BLIND (jede Aktion wird gefragt):
#   Interaktiv (Terminal) fragt der Installer:
#     1. "Gibt es bereits einen Nginx Proxy Manager?" (ja/nein)
#        - nein → "NPM jetzt automatisch auf DIESEM Server mitinstallieren?" (ja/nein;
#          Standard: nein — ohne Zustimmung wird NICHTS installiert)
#        - ja   → "Relay-Host jetzt automatisch im NPM einrichten?" (ja/nein)
#                 - ja  → NPM-Zugangsdaten + Domain werden abgefragt, der Installer
#                         legt Zertifikat + Proxy-Host + mTLS-Zwang automatisch an
#                 - nein → fertig konfigurierte advanced_config-Vorlage wird als Datei
#                          bereitgestellt (Copy-Paste für die NPM-Weboberfläche)
#   Nicht-interaktiv (Agent/CI, kein Terminal): identisches Verhalten über Env:
#     FLIPSI_NPM_MODE      = ask (nur mit Terminal wirksam) | none (NPM-Teil überspringen)
#     FLIPSI_NPM_INSTALL   = 1  → NPM automatisch mitinstallieren (Docker nötig)
#     FLIPSI_NPM_AUTOSETUP = 1  → automatische Host-/Zert-Einrichtung im bestehenden NPM
#     FLIPSI_NPM_URL       = z. B. http://127.0.0.1:81
#     FLIPSI_NPM_EMAIL / FLIPSI_NPM_PASSWORD = API-Zugangsdaten des NPM
#     FLIPSI_NPM_DATA_DIR  = NPM-Datenordner (für die Geräte-CA-Datei; Default s. unten)
#     FLIPSI_RELAY_DOMAIN  = z. B. relay.example.com
#     FLIPSI_LE_EMAIL      = E-Mail für Let's-Encrypt-Zertifikat
#   Ohne Terminal UND ohne Env: Der NPM-Teil wird mit klarer Anleitung übersprungen —
#   es wird nie blind installiert oder konfiguriert.
#
# Nutzung:
#   bash install-relay.sh                                   → interaktive Vollinstallation
#   ANDROID_BRIDGE_TOKEN=XXX bash install-relay.sh         → Token nicht-interaktiv
#   FLIPSI_TARGET=/pfad bash install-relay.sh              → eigenes Install-Verzeichnis
#
# PH-4-T3 Signed-Deployment: Manifest (FLIPSI_SHA256_ASSET_URL) oder INSTALL_SHA256
# prüft jede Datei; Mismatch = Abbruch (fail-closed), nie Token/Secrets in Ausgaben.
#
# Voraussetzungen: python3 (≥3.11), systemd, Internetzugriff zu GitHub;
# für den automatischen NPM-Install zusätzlich Docker + Docker-Compose-Plugin.
set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/TechFlipsi/FlipsiBridge/main"
SRC_RELAY="$REPO_RAW/tools/android_relay.py"
SRC_MTLS="$REPO_RAW/tools/mtls_enroll.py"
SRC_DAEMON="$REPO_RAW/contrib/hermes-relay-daemon.py"
SRC_UNIT="$REPO_RAW/deployment/flipsibridge-relay.service"   # gehärtete Sandbox-Unit (P23)
TARGET="${FLIPSI_TARGET:-/opt/flipsibridge}"
ENV_FILE="${FLIPSI_ENV:-/etc/flipsibridge/relay.env}"
SERVICE_USER="flipsibridge"
SHA256_ASSET_URL="${FLIPSI_SHA256_ASSET_URL:-https://github.com/TechFlipsi/FlipsiBridge/releases/latest/download/backend.sha256}"
NPM_DIR="${FLIPSI_NPM_INSTALL_DIR:-/opt/nginx-proxy-manager}"
NPM_DATA_DIR="${FLIPSI_NPM_DATA_DIR:-$NPM_DIR/data}"
NPM_CREDS_FILE="/etc/flipsibridge/npm-admin.txt"
NPM_TEMPLATE_FILE="/etc/flipsibridge/npm-advanced-config.txt"
NPM_URL="${FLIPSI_NPM_URL:-http://127.0.0.1:81}"
CA_DATEI="${FLIPSI_CA_CRT:-/root/mtls-ca/flipsibridge-ca.crt}"

if [ "$(id -u)" -ne 0 ]; then
  echo "Bitte als root starten (sudo -i)." >&2
  exit 1
fi

# ── Deinstallation (mit Auswahl, nie blind): bash install-relay.sh uninstall ──
STATE_FILE="/etc/flipsibridge/.installer-state"

state_read() { [ -f "$STATE_FILE" ] && cat "$STATE_FILE" || echo ""; }

uninstall_f() {
  echo "FlipsiBridge-Deinstallation — was soll entfernt werden? (nie blind)"
  local state; state="$(state_read)"
  echo "  Installations-Gedächtnis (${STATE_FILE}): ${state:-keine Einträge}"
  local wahl_relay wahl_host wahl_npm wahl_ca domain
  wahl_relay="$(ask_yesno "  1/4 FlipsiBridge-Relay entfernen (Service + Dateien + Secrets)?" "ja")"
  wahl_host="$(ask_yesno "  2/4 Relay-Host im NPM mit löschen (falls automatisch eingerichtet)?" "ja")"
  wahl_npm="$(ask_yesno "  3/4 Nginx Proxy Manager entfernen (nur wenn von DIESEM Installer installiert)?" "nein")"
  wahl_ca="$(ask_yesno "  4/4 Geräte-CA + Zertifikate löschen (/root/mtls-ca — vom Installer NIE angelegt, nur falls du sie selbst nicht mehr brauchst)?" "nein")"
  if ! tty_ok && [ -z "${FLIPSI_UNINSTALL:-}" ]; then
    echo "  ✗ Kein Terminal und keine FLIPSI_UNINSTALL-Vorgabe — abgebrochen (nie blind löschen)." >&2
    echo "    Env: FLIPSI_UNINSTALL=\"relay host npm ca\" (Leerzeichen-getrennt)." >&2
    exit 1
  fi
  if [ -n "${FLIPSI_UNINSTALL:-}" ]; then
    case " ${FLIPSI_UNINSTALL} " in *" relay "*) wahl_relay=ja;; esac
    case " ${FLIPSI_UNINSTALL} " in *" host "*)   wahl_host=ja;; esac
    case " ${FLIPSI_UNINSTALL} " in *" npm "*)    wahl_npm=ja;; esac
    case " ${FLIPSI_UNINSTALL} " in *" ca "*)     wahl_ca=ja;; esac
    if [ "${FLIPSI_UNINSTALL}" = "all" ]; then
      wahl_relay=ja; wahl_host=ja; wahl_npm=ja; wahl_ca=ja
    fi
  fi
  domain="$(echo "$state" | grep '^npm_host=' | head -1 | cut -d= -f2 || true)"
  if [ "$wahl_host" = "ja" ] && [ -n "$domain" ]; then
    npm_url="$(ask_value "  NPM-Adresse (zum Host-Löschen)" "$NPM_URL")"
    npm_email="$(ask_value "  NPM-Login-E-Mail" "${FLIPSI_NPM_EMAIL:-}")"
    npm_pass="$(ask_value "  NPM-Login-Passwort" "${FLIPSI_NPM_PASSWORD:-}" 1)"
    tok="$(npm_api_login "$npm_url" "$npm_email" "$npm_pass")"
    if [ -n "$tok" ]; then
      hid="$(curl -sS -m 10 -b "$NPM_COOKIES" -H "Authorization: Bearer $tok" "$npm_url/api/nginx/proxy-hosts" 2>/dev/null \
        | python3 -c "import json,sys
try:
    hosts=json.load(sys.stdin)
    for h in hosts:
        if isinstance(h, dict) and '$domain' in (h.get('domain_names') or []):
            print(h.get('id')); break
except Exception: pass" 2>/dev/null || true)"
      if [ -n "$hid" ]; then
        curl -sS -m 10 -b "$NPM_COOKIES" -H "Authorization: Bearer $tok" \
          -X DELETE "$npm_url/api/nginx/proxy-hosts/$hid" >/dev/null 2>&1 \
          && echo "  ✓ Relay-Host #$hid ($domain) aus dem NPM gelöscht." \
          || echo "  ⚠ Host-Löschung im NPM fehlgeschlagen (manuell in der Weboberfläche)." >&2
      else
        echo "  → Kein Host für $domain im NPM gefunden (wohl schon weg)." >&2
      fi
    else
      echo "  ⚠ NPM-Login fehlgeschlagen — Host manuell in der Weboberfläche löschen." >&2
    fi
  fi
  if [ "$wahl_relay" = "ja" ]; then
    systemctl stop flipsibridge-relay.service 2>/dev/null || true
    systemctl disable flipsibridge-relay.service 2>/dev/null || true
    rm -f /etc/systemd/system/flipsibridge-relay.service
    systemctl daemon-reload
    rm -rf "$TARGET"
    rm -f "$ENV_FILE" "$NPM_TEMPLATE_FILE" "$NPM_CREDS_FILE" "$STATE_FILE"
    if id "$SERVICE_USER" >/dev/null 2>&1; then
      userdel "$SERVICE_USER" 2>/dev/null && echo "  ✓ System-User $SERVICE_USER entfernt." || true
    fi
    echo "  ✓ FlipsiBridge-Relay entfernt (Service, Unit, $TARGET, Secrets)."
  else
    echo "  → FlipsiBridge-Relay bleibt installiert."
  fi
  if [ "$wahl_npm" = "ja" ]; then
    if echo "$state" | grep -q '^npm=self-installed'; then
      if [ -f "$NPM_DIR/docker-compose.yml" ] && command -v docker >/dev/null; then
        ( cd "$NPM_DIR" && docker compose down >/dev/null 2>&1 ) || true
        echo "  ✓ NPM-Container gestoppt."
        rm_npm_data="$(ask_yesno "      NPM-Daten + Compose-Verzeichnis ($NPM_DIR) komplett löschen (Zertifikate/Hosts-GEDÄCHTNIS!)" "nein")"
        if [ "${FLIPSI_UNINSTALL:-}" = "all" ] || [ "$rm_npm_data" = "ja" ]; then
          rm -rf "$NPM_DIR"
          echo "  ✓ $NPM_DIR entfernt (inkl. Daten)."
        else
          echo "  → $NPM_DIR bleibt liegen (Container gestoppt)."
        fi
        echo "  (Docker selbst wurde von diesem Installer nicht installiert und bleibt.)"
      else
        echo "  ⚠ Kein Compose-Verzeichnis/Container gefunden — nichts zu tun." >&2
      fi
    else
      echo "  → NPM wurde NICHT von diesem Installer installiert (oder unbekannt) — bleibt unangetastet." >&2
      echo "    Falls du NPM selbst entfernen willst: docker compose -f $NPM_DIR/docker-compose.yml down" >&2
    fi
  else
    echo "  → NPM bleibt installiert (falls vorhanden)."
  fi
  if [ "$wahl_ca" = "ja" ]; then
    rm -rf /root/mtls-ca
    echo "  ✓ Geräte-CA + Zertifikate entfernt (/root/mtls-ca)."
  else
    [ -d /root/mtls-ca ] && echo "  → Geräte-CA bleibt liegen (/root/mtls-ca) — für spätere Neuinstallation nutzbar."
  fi
  echo "Deinstallation abgeschlossen."
  exit 0
}

# ── Interaktions-Helfer ──────────────────────────────────────────────────────
tty_ok() { [ -t 0 ] || [ -n "${FS_TTY:-}" ]; }

state_add() {  # $1=Zeile — Installations-Gedächtnis für granulare Deinstallation
  mkdir -p "$(dirname "$STATE_FILE")" 2>/dev/null || true
  touch "$STATE_FILE" && chmod 640 "$STATE_FILE"
  grep -q "^$1$" "$STATE_FILE" 2>/dev/null || echo "$1" >> "$STATE_FILE"
}

ask_yesno() {  # $1=Frage $2=Default(ja|nein) → Antwort auf stdout (ja/nein)
  local frage="$1" def="${2:-nein}" antwort
  if ! tty_ok; then echo "$def"; return; fi
  while true; do
    read -r -p "$frage [${def}] (ja/nein): " antwort || { echo "$def"; return; }
    antwort="${antwort:-$def}"
    case "$antwort" in j|ja|J|JA|Ja) echo ja; return ;; n|nein|N|NEIN|Nein) echo nein; return ;; esac
    echo "  Bitte ja oder nein eingeben." >&2
  done
}

ask_value() {  # $1=Frage $2=Default $3=geheim(1) → Antwort auf stdout
  local frage="$1" def="$2" antwort
  if ! tty_ok; then echo "$def"; return; fi
  if [ "${3:-}" = "1" ]; then
    read -r -s -p "$frage${def:+ [Vorgabe per Enter]}: " antwort || true; echo >&2
  else
    read -r -p "$frage${def:+ [Vorgabe: $def]}${def:- [leer=auslassen]}: " antwort || true
  fi
  echo "${antwort:-$def}"
}

pass_gen() {  # sicheres Zufalls-Passwort (alphanumerisch, 24 Z.)
  tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24 || true
}

NPM_COOKIES=""

npm_api_login() {  # $1=url $2=email $3=pass → Token auf stdout ("" bei Misserfolg; Cookie-Jar global)
  local url="$1" email="$2" pass="$3" tok
  NPM_COOKIES="$(mktemp)"
  chmod 600 "$NPM_COOKIES"
  tok="$(curl -sS -m 15 -c "$NPM_COOKIES" -X POST "$url/api/tokens" \
        -H 'Content-Type: application/json' \
        -d "{\"identity\":\"$email\",\"secret\":\"$pass\"}" 2>/dev/null \
        | python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
    print(d.get("token","") if isinstance(d, dict) else "")
except Exception:
    print("")' 2>/dev/null || true)"
  echo "${tok:-cookie}"
}

# Deinstallations-Modus: NACH allen nötigen Funktionsdefinitionen, VOR dem Hauptablauf
if [ "${1:-}" = "uninstall" ]; then uninstall_f; fi

# ═══ TEIL 1: RELAY-KERN (maximale Sicherheit) ════════════════════════════════

echo "[1/7] Abhängigkeiten prüfen (python3, aiohttp)"
if ! command -v python3 >/dev/null; then
  echo "python3 fehlt — installiere zuerst (apt install python3)." >&2; exit 1
fi
python3 -c 'import aiohttp' 2>/dev/null || {
  echo "aiohttp fehlt — installiere: pip3 install aiohttp" >&2; exit 1
}

echo "[2/7] System-User anlegen (Relay läuft NICHT als root): $SERVICE_USER"
if id "$SERVICE_USER" >/dev/null 2>&1; then
  echo "  ✓ System-User existiert bereits"
else
  useradd --system --user-group --home-dir "$TARGET" --no-create-home \
          --shell /usr/sbin/nologin "$SERVICE_USER"
  echo "  ✓ System-User erzeugt (nologin, keine Shell)"
fi

echo "[3/7] Zielordner anlegen: $TARGET"
mkdir -p "$TARGET/tools" "$TARGET/contrib"
chown "$SERVICE_USER:$SERVICE_USER" "$TARGET" "$TARGET/tools" "$TARGET/contrib"
chmod 750 "$TARGET" "$TARGET/tools" "$TARGET/contrib"

echo "[4/7] Backend-Dateien herunterladen"
curl -fsSL "$SRC_RELAY"  -o "$TARGET/tools/android_relay.py"
curl -fsSL "$SRC_MTLS"   -o "$TARGET/tools/mtls_enroll.py"
curl -fsSL "$SRC_DAEMON" -o "$TARGET/contrib/hermes-relay-daemon.py"
chown root:"$SERVICE_USER" "$TARGET/tools/android_relay.py" "$TARGET/tools/mtls_enroll.py" "$TARGET/contrib/hermes-relay-daemon.py"
chmod 640 "$TARGET/tools/android_relay.py" "$TARGET/tools/mtls_enroll.py" "$TARGET/contrib/hermes-relay-daemon.py"

# ── PH-4-T3: Signed-Deployment (Integrität, fail-closed bei Mismatch) ────────
# Zwei dokumentierte Prüf-Typen: (1) Release-Metadaten-Manifest (.sha256,
# je Datei, Pflicht für jeden Produktions-Deploy), (2) INSTALL_SHA256 für
# die Hauptdatei. Mismatch / fehlender Manifest-Eintrag = harter Abbruch
# mit erwartet/erhalten (nur Hash-PRÄFIXE — nie Token/Secrets).
RELAY_FILE="$TARGET/tools/android_relay.py"
MTLS_FILE="$TARGET/tools/mtls_enroll.py"
DAEMON_FILE="$TARGET/contrib/hermes-relay-daemon.py"
MANIFEST_FILE="$TARGET/.backend.sha256"

sha_fail_closed() {
  echo "INTEGRITÄT FEHLGESCHLAGEN (fail-closed) — Abbruch:" >&2
  echo "  Datei   : $1" >&2
  echo "  Erwartet: ${2:0:16}…" >&2
  echo "  Erhalten: ${3:0:16}…" >&2
  echo "Download verworfen — prüfe Quelle (FLIPSI_SHA256_ASSET_URL / INSTALL_SHA256) oder Netz." >&2
  exit 1
}

manifest_expected() {
  # erwartet: "<hex>  <name>" (sha256sum-Format, Whitespace-Toleranz);
  # Ausgabe = hex oder leer (kein Eintrag).
  awk -v name="$1" '{
    line = $0
    sub(/^[[:space:]]+/, "", line)
    hash = substr(line, 1, 64)
    rest = substr(line, 66)
    gsub(/[[:space:]]+/, "", rest)
    if (length(hash) == 64 && rest == name && hash ~ /^[0-9a-fA-F]{64}$/) {
      print tolower(hash); exit
    }
  }' "$MANIFEST_FILE"
}

echo "[4b/7] Integritätsprüfung (SHA-256)"
if curl -fsSL "$SHA256_ASSET_URL" -o "$MANIFEST_FILE" 2>/dev/null; then
  VERIFIED=1
  for entry in "$RELAY_FILE:tools/android_relay.py" \
               "$MTLS_FILE:tools/mtls_enroll.py" \
               "$DAEMON_FILE:contrib/hermes-relay-daemon.py"; do
    file_path="${entry%%:*}"
    manifest_name="${entry#*:}"
    expected_raw="$(manifest_expected "$manifest_name")"
    if [ -z "$expected_raw" ]; then
      echo "INTEGRITÄT FEHLGESCHLAGEN (fail-closed): Datei $manifest_name fehlt im Manifest — Abbruch." >&2
      rm -f "$MANIFEST_FILE"
      exit 1
    fi
    got="$(sha256sum "$file_path" | awk '{print $1}')"
    if [ "$got" != "$expected_raw" ]; then
      sha_fail_closed "$manifest_name" "$expected_raw" "$got"
    fi
    echo "  ✓ $manifest_name (Präfix ${got:0:16}…)"
  done
  rm -f "$MANIFEST_FILE"
  echo "Integrität geprüft gegen: $SHA256_ASSET_URL"
elif [ -n "${INSTALL_SHA256:-}" ]; then
  got="$(sha256sum "$RELAY_FILE" | awk '{print $1}')"
  expected="$(echo "${INSTALL_SHA256}" | tr 'A-F' 'a-f')"
  if [ "$got" != "$expected" ]; then
    sha_fail_closed "tools/android_relay.py" "$expected" "$got"
  fi
  echo "  ✓ tools/android_relay.py (Präfix ${got:0:16}…, INSTALL_SHA256)"
else
  echo "⚠ UNVERIFIZIERT: weder Manifest ($SHA256_ASSET_URL) erreichbar noch" >&2
  echo "  INSTALL_SHA256 gesetzt — Fortsetzung mit Unverifizierten Dateien." >&2
  echo "  Produktion: FLIPSI_SHA256_ASSET_URL oder INSTALL_SHA256 setzen!" >&2
fi

echo "[5/7] Gehärtete systemd-Unit installieren (Sandbox-Vollset, s. deployment/)"
curl -fsSL "$SRC_UNIT" -o /etc/systemd/system/flipsibridge-relay.service
# Zielordner in der Unit patchen (Vorlage nutzt /opt/flipsibridge als Default)
sed -i "s|/opt/flipsibridge|$TARGET|g" /etc/systemd/system/flipsibridge-relay.service
grep -q "flipsibridge-relay.service" /etc/systemd/system/flipsibridge-relay.service
grep -q "SystemCallFilter=@system-service" /etc/systemd/system/flipsibridge-relay.service

echo "[5b/7] Secrets + Sicherheits-Limits einrichten: $ENV_FILE"
ENV_DIR="$(dirname "$ENV_FILE")"
mkdir -p "$ENV_DIR"
# P23-Regel: Parent-Verzeichnis muss der Service-Gruppe BETRETBAR sein, sonst
# scheitert jedes Kind (tls-Anlage → Enrollment OFF). Group-Zugang gezielt setzen:
chgrp "$SERVICE_USER" "$ENV_DIR"
chmod 750 "$ENV_DIR"
mkdir -p "$ENV_DIR/tls"
chown root:"$SERVICE_USER" "$ENV_DIR/tls"
chmod 770 "$ENV_DIR/tls"

write_env() {
  # Token-Zeile (vorhandene Datei aktualisieren, sonst neu; Kommentare immer neu)
  if [ -f "$ENV_FILE" ]; then
    sed -i '/^ANDROID_BRIDGE_TOKEN=/d' "$ENV_FILE"
  fi
  {
    echo "ANDROID_BRIDGE_TOKEN=$1"
    echo ""
    echo "# FlipsiBridge Sicherheits-Limits (Standard = maximal, nur verschärfbar):"
    echo "ANDROID_RELAY_AUTH_MAX_ATTEMPTS=3"
    echo "ANDROID_RELAY_AUTH_BLOCK_SECONDS=600"
    echo "ANDROID_RELAY_AUTH_WINDOW_SECONDS=60"
    echo "ANDROID_RELAY_PAIRING_TTL_SECONDS=300"
    echo "ANDROID_RELAY_PAIRING_PENDING_MAX=4"
    echo "ANDROID_RELAY_REPLAY_WINDOW=64"
    echo "ANDROID_RELAY_FRAME_MAX_AGE_MS=120000"
    echo "ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS=14"
    echo ""
    echo "# TLS (Produktion PFLICHT, s. docs/SECURITY-SETUP.md §2):"
    echo "#ANDROID_RELAY_TLS=1"
    echo "#ANDROID_RELAY_CERT=/etc/flipsibridge/tls/relay.crt"
    echo "#ANDROID_RELAY_KEY=/etc/flipsibridge/tls/relay.key"
    echo "# Reverse-Proxy-Betrieb PFLICHT (sonst teilen sich alle IPs ein Rate-Limit-Bucket):"
    echo "#ANDROID_RELAY_TRUSTED_PROXY_IP=192.0.2.10"
    echo "# Relay NUR lokal lauschen lassen, wenn der Proxy terminiert:"
    echo "#ANDROID_RELAY_HOST=127.0.0.1"
    echo ""
    echo "# mTLS-Geräte-Gate (Relay-Schicht 2) — NUR mit gültigem Proxy-Zert-Zwang aktivieren (§3c):"
    echo "#ANDROID_MTLS_ENFORCE=1"
  } >> "$ENV_FILE"
  chown root:"$SERVICE_USER" "$ENV_FILE"
  chmod 640 "$ENV_FILE"
}

env_set_var() {  # $1=Name $2=Wert — Zeile aktiv in ENV_FILE setzen (Kommentar aufheben)
  sed -i "s|^#$1=.*|$1=$2|; s|^$1=.*|$1=$2|" "$ENV_FILE"
  grep -q "^$1=$2" "$ENV_FILE" || echo "$1=$2" >> "$ENV_FILE"
}

if [ -n "${ANDROID_BRIDGE_TOKEN:-}" ]; then
  write_env "$ANDROID_BRIDGE_TOKEN"
elif tty_ok; then
  CODE="$(ask_value "Kopplungscode aus der App (siehe App-Screen)" "")"
  write_env "${CODE:-__TOKEN_NACHTRAGEN__}"
  [ "${CODE:-}" = "" ] && echo "⚠ Kein Code eingegeben — Platzhalter gesetzt, später nachtragen." >&2
else
  write_env "__TOKEN_NACHTRAGEN__"
  echo "⚠ Kein Token angegeben (Platzhalter gesetzt). Nachtragen:" >&2
  echo "  sudo sed -i 's|^ANDROID_BRIDGE_TOKEN=.*|ANDROID_BRIDGE_TOKEN=<CODE>|' $ENV_FILE" >&2
fi

systemctl daemon-reload
systemctl enable flipsibridge-relay.service
systemctl restart flipsibridge-relay.service || {
  echo "⚠ systemd-Start fehlgeschlagen — prüfe Journal: journalctl -u flipsibridge-relay.service -f" >&2
  exit 1
}
state_add "relay=$TARGET"
state_add "env=$ENV_FILE"

# ═══ TEIL 2: REVERSE-PROXY (NPM) — ALLES INTERAKTIV, NIE BLIND ═══════════════

echo
echo "[6/7] Reverse-Proxy (Nginx Proxy Manager) — Einrichtung"
echo "      (Jeder Schritt wird abgefragt; ohne Terminal und ohne Env-Vorgabe wird nichts verändert)"

NPM_MODE="${FLIPSI_NPM_MODE:-ask}"

npm_template_schreiben() {  # manuelle Vorlage für die NPM-Weboberfläche bereitstellen
  local domain="$1"
  mkdir -p "$(dirname "$NPM_TEMPLATE_FILE")"
  cat > "$NPM_TEMPLATE_FILE" <<EOF
# FlipsiBridge advanced_config-Vorlage für die NPM-Weboberfläche (Host: $domain)
# Host anlegen: $domain → forward http://127.0.0.1:8766, WebSockets an, Exploit-
# Blocker an, SSL erzwingen, HSTS an. Geräte-CA-Datei zuerst ins NPM-Daten-
# verzeichnis legen (custom_nginx/, Container-Pfad /data/custom_nginx/), dann
# diesen Block unter "Advanced" einfügen:
#
#  Client-Zertifikat verlangen (optional = anfordern; Abweisung regelt die if-Kette)
ssl_client_certificate /data/custom_nginx/flipsibridge-device-ca.pem;
ssl_verify_client optional;
ssl_verify_depth 2;
#
#  Standard ZU: 421 ohne gültiges Geräte-Zertifikat — AUSNAHMEN: Einschreibung + Agenten-Kanal
if (\$ssl_client_verify != SUCCESS) { set \$fb_zert_zwang 1; }
if (\$request_uri ~ ^/mtls/enroll) { set \$fb_zert_zwang 0; }
if (\$request_uri ~ ^/agent_ws)     { set \$fb_zert_zwang 0; }
if (\$fb_zert_zwang = 1)            { return 421; }
#
# ── Geräte-CA anlegen (falls noch nicht geschehen), Vorlage zum Ausfüllen:
#   bash contrib/flipsibridge-ca.sh init          # CA einmalig erzeugen
#   bash contrib/flipsibridge-ca.sh issue hand-1 # erstes Handy-Zertifikat
#   → /root/mtls-ca/hand-1.p12 (+ .p12pass, chmod 600) in die App importieren
#   → CA-Datei in den NPM-Datenordner legen:
#     cp /root/mtls-ca/flipsibridge-ca.crt <NPM-Datenordner>/custom_nginx/flipsibridge-device-ca.pem
EOF
  chmod 640 "$NPM_TEMPLATE_FILE"
  chown root:root "$NPM_TEMPLATE_FILE"
}

npm_install_docker() {  # NPM via Docker-Compose auf DIESEM Server installieren
  echo "  → Installiere Nginx Proxy Manager per Docker-Compose nach $NPM_DIR"
  if ! command -v docker >/dev/null; then
    echo "  ✗ Docker fehlt — automatischer NPM-Install nicht möglich." >&2
    echo "    Installiere Docker (https://docs.docker.com/engine/install/) und dieses Setup" >&2
    echo "    erneut starten, ODER den Proxy manuell einrichten (docs/SECURITY-SETUP.md §2/§3)." >&2
    return 1
  fi
  if ! docker compose version >/dev/null 2>&1; then
    echo "  ✗ Docker-Compose-Plugin fehlt (docker compose version)." >&2
    return 1
  fi
  local p
  for p in 80 81 443; do
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$p\$"; then
      echo "  ✗ Port $p ist bereits belegt — NPM-Install abgebrochen (nichts wurde verändert)." >&2
      echo "    Bestehenden Proxy nutzen oder Ports in $NPM_DIR/docker-compose.yml anpassen." >&2
      return 1
    fi
  done
  mkdir -p "$NPM_DATA_DIR" "$NPM_DIR/letsencrypt"
  cat > "$NPM_DIR/docker-compose.yml" <<EOF
services:
  npm:
    image: jc21/nginx-proxy-manager:latest
    container_name: nginx-proxy-manager
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "127.0.0.1:81:81"
    volumes:
      - $NPM_DATA_DIR:/data
      - $NPM_DIR/letsencrypt:/etc/letsencrypt
EOF
  ( cd "$NPM_DIR" && docker compose up -d >/dev/null 2>&1 ) || {
    echo "  ✗ docker compose up fehlgeschlagen — Logs: docker logs nginx-proxy-manager" >&2
    return 1
  }
  echo "  → Warte auf NPM-Weboberfläche (max. 60 s)…"
  local i
  for i in $(seq 1 30); do
    if curl -fsS -m 2 "$NPM_URL/" >/dev/null 2>&1; then break; fi
    sleep 2
  done
  curl -fsS -m 3 "$NPM_URL/" >/dev/null 2>&1 || {
    echo "  ✗ NPM-Weboberfläche nicht erreichbar unter $NPM_URL." >&2
    return 1
  }
  # Initial-Admin anlegen (erster User wird Admin) — Passwort generiert, nur in 600-Datei
  local setup_email admin_pass resp
  setup_email="$(ask_value "  NPM-Admin-E-Mail (Login für die Weboberfläche)" "${FLIPSI_NPM_EMAIL:-admin@example.com}")"
  admin_pass="$(pass_gen)"
  resp="$(curl -sS -m 10 -X POST "$NPM_URL/api/users" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$setup_email\",\"name\":\"FlipsiBridge-Admin\",\"roles\":[\"admin\"],\"password\":\"$admin_pass\"}" 2>/dev/null || true)"
  if echo "$resp" | grep -q '"id"'; then
    mkdir -p "$(dirname "$NPM_CREDS_FILE")"
    printf 'NPM-Weboberfläche: %s\nLogin-E-Mail: %s\nLogin-Passwort: %s\n' \
      "$NPM_URL" "$setup_email" "$admin_pass" > "$NPM_CREDS_FILE"
    chmod 600 "$NPM_CREDS_FILE"
    echo "  ✓ NPM läuft. Admin-Zugangsdaten: $NPM_CREDS_FILE (chmod 600) — NACH dem Einrichten ändern!"
  else
    echo "  ⚠ Initial-Admin nicht anlegbar (NPM evtl. schon eingerichtet)." >&2
    echo "    Weboberfläche: $NPM_URL — Zugangsdaten selbst verwalten." >&2
  fi
  return 0
}

npm_autosetup() {  # $1=url $2=email $3=pass $4=domain $5=le_email $6=data_dir
  local npm_url="$1" email="$2" pass="$3" domain="$4" le_email="$5" data_dir="$6"
  echo "  → Melde beim NPM an ($npm_url)…"
  local tok
  tok="$(npm_api_login "$npm_url" "$email" "$pass")"
  [ -n "$tok" ] || { echo "  ✗ NPM-Login fehlgeschlagen (Adresse/E-Mail/Passwort prüfen)." >&2; return 1; }
  local AUTH="Authorization: Bearer $tok"
  # Geräte-CA: Der Installer ERZEUGT die CA (Pflicht für mTLS — echte eigene CA,
  # frisch generiert 4096-Bit, KEIN Standard-Zertifikat), aber KEINE Geräte-
  # zertifikate — die stellt Admin/Agent bewusst aus (flipsibridge-ca.sh issue).
  if [ ! -f "$CA_DATEI" ]; then
    echo "  → Keine Geräte-CA gefunden — erzeuge EIGENE CA (4096 Bit, neu generiert, kein Standard)…"
    curl -fsSL "$REPO_RAW/contrib/flipsibridge-ca.sh" -o /tmp/flipsibridge-ca.sh
    chmod +x /tmp/flipsibridge-ca.sh
    bash /tmp/flipsibridge-ca.sh init
    rm -f /tmp/flipsibridge-ca.sh
    if [ -f "$CA_DATEI" ]; then
      echo "  ✓ Geräte-CA erzeugt: $CA_DATEI (Fingerprint unten, in der App prüfbar)."
      openssl x509 -in "$CA_DATEI" -noout -fingerprint -sha256 2>/dev/null | head -1 || true
      echo "  ⚠ Geräte-Zertifikate legt der Installer NICHT an — bewusst nachholen:" >&2
      echo "      bash <Repo>/contrib/flipsibridge-ca.sh issue <gerätename>" >&2
    else
      echo "  ✗ CA-Erzeugung fehlgeschlagen — mTLS-Zwang wird NICHT gesetzt." >&2
    fi
  fi
  if [ -f "$CA_DATEI" ]; then
    mkdir -p "$data_dir/custom_nginx"
    cp "$CA_DATEI" "$data_dir/custom_nginx/flipsibridge-device-ca.pem"
    chmod 640 "$data_dir/custom_nginx/flipsibridge-device-ca.pem"
    echo "  ✓ Geräte-CA in NPM-Datenordner gelegt (custom_nginx/)"
  fi
  # Let's-Encrypt-Zertifikat (nur wenn E-Mail angegeben)
  local cert_id="" i status
  if [ -n "$le_email" ]; then
    echo "  → Fordere Let's-Encrypt-Zertifikat für $domain an (DNS muss bereits dorthin zeigen!)…"
    cert_id="$(curl -sS -m 15 -b "$NPM_COOKIES" -H "$AUTH" -H 'Content-Type: application/json' \
      -X POST "$npm_url/api/nginx/certificates" \
      -d "{\"provider\":\"letsencrypt\",\"domain_names\":[\"$domain\"],\"meta\":{\"letsencrypt_email\":\"$le_email\",\"letsencrypt_agree\":true}}" 2>/dev/null \
      | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("id",""))
except Exception:
    print("")' 2>/dev/null || true)"
    if [ -n "$cert_id" ]; then
      echo "  → Zertifikat-ID $cert_id — warte auf Ausstellung (bis 90 s)…"
      status=""
      for i in $(seq 1 18); do
        sleep 5
        status="$(curl -sS -m 10 -b "$NPM_COOKIES" -H "$AUTH" "$npm_url/api/nginx/certificates/$cert_id" 2>/dev/null \
          | python3 -c 'import json,sys
try:
    print((json.load(sys.stdin).get("meta") or {}).get("letsencrypt_certificate",""))
except Exception:
    print("")' 2>/dev/null || true)"
        [ -n "$status" ] && break
      done
      if [ -z "$status" ]; then
        echo "  ✗ Zertifikat nicht rechtzeitig ausgestellt (DNS/Port 80 prüfen)." >&2
        echo "    Host wird OHNE Zertifikat angelegt — in NPM später zuweisen (oder Zert löschen)." >&2
        cert_id=""
      else
        echo "  ✓ Zertifikat ausgestellt."
      fi
    else
      echo "  ✗ Zertifikat-Anforderung abgelehnt (Domain/DNS prüfen) — Host ohne Zert." >&2
    fi
  fi
  # Proxy-Host anlegen (mTLS-Zwang nur, wenn CA-Datei vorhanden)
  local advanced=""
  if [ -f "$data_dir/custom_nginx/flipsibridge-device-ca.pem" ]; then
    advanced='ssl_client_certificate /data/custom_nginx/flipsibridge-device-ca.pem;\nssl_verify_client optional;\nssl_verify_depth 2;\nif ($ssl_client_verify != SUCCESS) { set $fb_zert_zwang 1; }\nif ($request_uri ~ ^/mtls/enroll) { set $fb_zert_zwang 0; }\nif ($request_uri ~ ^/agent_ws) { set $fb_zert_zwang 0; }\nif ($fb_zert_zwang = 1) { return 421; }'
  fi
  local payload host_id
  payload="$(python3 - "$domain" "$cert_id" "$advanced" <<'PYEOF'
import json, sys
domain, cert_id, advanced = sys.argv[1], sys.argv[2], sys.argv[3].replace("\\n", "\n")
payload = {
  "domain_names": [domain],
  "forward_scheme": "http",
  "forward_host": "127.0.0.1",
  "forward_port": 8766,
  "block_exploits": True,
  "caching_enabled": False,
  "allow_websocket_upgrade": True,
  "http2_support": True,
  "ssl_forced": bool(cert_id),
  "hsts_enabled": True,
  "hsts_subdomains": True,
  "certificate_id": int(cert_id) if cert_id else 0,
}
if advanced:
    payload["advanced_config"] = advanced
print(json.dumps(payload))
PYEOF
)"
  host_id="$(curl -sS -m 15 -b "$NPM_COOKIES" -H "$AUTH" -H 'Content-Type: application/json' \
    -X POST "$npm_url/api/nginx/proxy-hosts" -d "$payload" 2>/dev/null \
    | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("id",""))
except Exception:
    print("")' 2>/dev/null || true)"
  if [ -z "$host_id" ]; then
    echo "  ✗ Proxy-Host konnte nicht angelegt werden (NPM-API-Fehler)." >&2
    echo "    Manuelle Vorlage: $NPM_TEMPLATE_FILE (für die NPM-Weboberfläche)" >&2
    return 1
  fi
  echo "  ✓ Proxy-Host #$host_id für $domain angelegt (forward 127.0.0.1:8766, HSTS, Exploit-Blocker)."
  # Relay: nur noch lokal lauschen + Proxy-IP vertrauen (Container-IP ermitteln, falls Docker-NPM)
  local proxy_ip
  proxy_ip="$(docker inspect nginx-proxy-manager --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' 2>/dev/null || true)"
  if [ -n "$proxy_ip" ]; then
    env_set_var ANDROID_RELAY_HOST 127.0.0.1
    env_set_var ANDROID_RELAY_TRUSTED_PROXY_IP "$proxy_ip"
    systemctl restart flipsibridge-relay
    echo "  ✓ Relay lauscht NUR lokal; Proxy-IP $proxy_ip als vertrauenswürdig eingetragen."
  else
    echo "  ⚠ Proxy-IP nicht ermittelbar (kein Docker-NPM?) — ANDROID_RELAY_TRUSTED_PROXY_IP" >&2
    echo "    selbst nachtragen (sonst teilen sich alle Clients ein Rate-Limit-Bucket)." >&2
  fi
  return 0
}

npm_teil=0
if [ "$NPM_MODE" = "none" ]; then
  echo "  → NPM-Teil laut FLIPSI_NPM_MODE=none übersprungen."
elif ! tty_ok && [ "${FLIPSI_NPM_AUTOSETUP:-}" != "1" ] && [ "${FLIPSI_NPM_INSTALL:-}" != "1" ]; then
  echo "  → Kein Terminal und keine FLIPSI_NPM_*-Vorgabe — NPM-Teil übersprungen (nie blind)." >&2
  echo "    Nachholen: interaktiv am Terminal starten oder FLIPSI_NPM_AUTOSETUP=1 setzen;" >&2
  echo "    Anleitung: docs/SECURITY-SETUP.md §2/§3." >&2
else
  hat_npm=""
  if [ "${FLIPSI_NPM_AUTOSETUP:-}" = "1" ]; then
    hat_npm="ja"          # Auto-Setup: NPM gilt als vorhanden
  elif [ "${FLIPSI_NPM_INSTALL:-}" = "1" ]; then
    hat_npm="nein"        # Install-Wunsch: NPM gilt als NICHT vorhanden → Install-Pfad
  else
    hat_npm="$(ask_yesno "  Gibt es bereits einen Nginx Proxy Manager (auf diesem oder einem anderen Server)?" "ja")"
  fi
  if [ "$hat_npm" = "nein" ]; then
    install_npm="$(ask_yesno "  Soll der Installer NPM jetzt automatisch auf DIESEM Server mitinstallieren (Docker nötig)?" "nein")"
    if [ "${FLIPSI_NPM_INSTALL:-}" = "1" ] || [ "$install_npm" = "ja" ]; then
      if npm_install_docker; then
        hat_npm="ja"
        state_add "npm=self-installed"
        state_add "npm_dir=$NPM_DIR"
        echo "  → NPM frisch installiert — jetzt Host-Einrichtung anbieten."
      else
        echo "  → Weiter ohne NPM (nichts wurde verändert)." >&2
      fi
    else
      echo "  → OK, kein NPM-Install. Relay bleibt Direkt-Server." >&2
      echo "    Alternative ohne Proxy: natives TLS (docs/SECURITY-SETUP.md §2 Weg B)." >&2
    fi
  fi
  if [ "$hat_npm" = "ja" ]; then
    auto="$(ask_yesno "  Soll der Relay-Host (Domain + Zertifikat + mTLS-Zwang) jetzt automatisch im NPM eingerichtet werden?" "ja")"
    if [ "${FLIPSI_NPM_AUTOSETUP:-}" = "1" ] || [ "$auto" = "ja" ]; then
      npm_url="$(ask_value "  NPM-Adresse (Weboberfläche/API)" "$NPM_URL")"
      npm_email="$(ask_value "  NPM-Login-E-Mail" "${FLIPSI_NPM_EMAIL:-}")"
      npm_pass="$(ask_value "  NPM-Login-Passwort" "${FLIPSI_NPM_PASSWORD:-}" 1)"
      domain="$(ask_value "  Relay-Domain (DNS muss darauf zeigen)" "${FLIPSI_RELAY_DOMAIN:-relay.example.com}")"
      le_email="$(ask_value "  E-Mail für Let's-Encrypt (leer lassen = kein Zertifikat, nur HTTP)" "${FLIPSI_LE_EMAIL:-}")"
      data_dir="$(ask_value "  NPM-Datenordner (für die Geräte-CA)" "$NPM_DATA_DIR")"
      npm_template_schreiben "$domain"
      if npm_autosetup "$npm_url" "$npm_email" "$npm_pass" "$domain" "$le_email" "$data_dir"; then
        npm_teil=1
        state_add "npm_host=$domain"
        state_add "npm_url=$npm_url"
        echo "  ✓ Automatische NPM-Einrichtung abgeschlossen."
      else
        echo "  → Automatische Einrichtung fehlgeschlagen — MANUELLE Vorlage liegt bereit:" >&2
        echo "    $NPM_TEMPLATE_FILE (für die NPM-Weboberfläche)" >&2
      fi
    else
      domain="$(ask_value "  Relay-Domain (für die manuelle Vorlage)" "${FLIPSI_RELAY_DOMAIN:-relay.example.com}")"
      npm_template_schreiben "$domain"
      echo "  → Manuelle Einrichtung gewählt. Fertige advanced_config-Vorlage:" >&2
      echo "    $NPM_TEMPLATE_FILE" >&2
      echo "    Schritt-für-Schritt: docs/SECURITY-SETUP.md §3b (NPM-Weboberfläche)." >&2
    fi
  fi
fi

echo "[7/7] Abschluss"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "FlipsiBridge-Relay installiert (maximale Sicherheitsstellung):"
echo "  Service : systemctl status flipsibridge-relay   (läuft als User: $SERVICE_USER)"
echo "  Log     : journalctl -u flipsibridge-relay -f"
echo "  Secrets : $ENV_FILE (0640 root:$SERVICE_USER)"
echo "  Limits  : 3 Fehlversuche → 10 min IP-Sperre, Kopplungsfenster 5 min"
echo "  Sandbox : ProtectSystem=strict + Syscall-Whitelist"
[ -f "$NPM_CREDS_FILE" ] && echo "  NPM-Admin: $NPM_CREDS_FILE (600 — Passwort nach Ersteinrichtung ändern!)"
[ -f "$NPM_TEMPLATE_FILE" ] && echo "  NPM-Vorlage: $NPM_TEMPLATE_FILE"
echo
echo "NÄCHSTE SCHRITTE (docs/SECURITY-SETUP.md):"
[ "$npm_teil" = "1" ] || echo "  §2/§3  TLS + mTLS-Gerätezertifikate (contrib/flipsibridge-ca.sh) einrichten"
echo "  §3d    Handy einschreiben (App: Zertifikat + Kopplungscode), danach Kopplungscode rotieren"
echo "  §4     Agenten über Einladungscode verbinden"
echo "  §6     Verifikations-Checkliste einmal komplett durchlaufen"
echo
echo "In der Handy-App eintragen:"
echo "  Server  : https://<deine-relay-domain>   (Klartext ist am Relay gesperrt)"
echo "  Code    : der Code aus der App"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
