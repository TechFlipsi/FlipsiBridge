#!/bin/bash
# flipsibridge-relay-setup.sh — Einzeiler-Installer fürs FlipsiBridge-Python-Relay (Server-Seite)
# Nutzung:
#   bash install-relay.sh                 → installiert in /opt/flipsibridge + systemd unit + fragt Token
#   ANDROID_BRIDGE_TOKEN=XXX bash install-relay.sh   → setzt Token nicht-interaktiv
#   FLIPSI_TARGET=/pfad bash install-relay.sh        → eigenes Install-Verzeichnis
#
# PH-4-T3 Signed-Deployment (Integrität, fail-closed bei Mismatch):
#   Weg 1 — Release-Metadaten-Manifest: FLIPSI_SHA256_ASSET_URL (Default
#     <REPO_RAW>/deployment/backend.sha256) lädt ein „<hex>  <name>"-Manifest
#     (sha256sum-Format, name = Dateiname); JEDE heruntergeladene Datei wird
#     geprüft, fehlt eine Datei im Manifest = ebenfalls Abbruch (fail-closed).
#   Weg 2 — Einzeldatei: INSTALL_SHA256=<hex> prüft die Hauptdatei
#     (tools/android_relay.py). Mismatch = Abbruch mit erwartet/erhalten
#     (nur Hash-Präfixe — nie Token/Secrets).
#   Kein Weg verfügbar → Warnung „UNVERIFIZIERT" und Weiter (Quickstart);
#     Produktions-Deploy PFLICHT: Weg 1 oder Weg 2 setzen.
#
# Voraussetzungen: python3 (≥3.11), systemd, Internet-Zugriff zum GitHub.
set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/TechFlipsi/FlipsiBridge/main"
SRC_RELAY="$REPO_RAW/tools/android_relay.py"
SRC_DAEMON="$REPO_RAW/contrib/hermes-relay-daemon.py"
SRC_UNIT="$REPO_RAW/contrib/flipsibridge-relay.service"
TARGET="${FLIPSI_TARGET:-/opt/flipsibridge}"
ENV_FILE="${FLIPSI_ENV:-/root/.secrets/flipsibridge-relay.env}"
SHA256_ASSET_URL="${FLIPSI_SHA256_ASSET_URL:-https://github.com/TechFlipsi/FlipsiBridge/releases/latest/download/backend.sha256}"

if [ "$(id -u)" -ne 0 ]; then
  echo "Bitte als root starten (sudo -i)." >&2
  exit 1
fi

echo "[1/5] Abhängigkeiten prüfen (python3, aiohttp)"
if ! command -v python3 >/dev/null; then
  echo "python3 fehlt — installiere zuerst (apt install python3)." >&2; exit 1
fi
python3 -c 'import aiohttp' 2>/dev/null || {
  echo "aiohttp fehlt — installiere: pip3 install aiohttp" >&2; exit 1
}

echo "[2/5] Zielordner anlegen: $TARGET"
mkdir -p "$TARGET/tools" "$TARGET/contrib"

echo "[3/5] Backend-Dateien herunterladen"
curl -fsSL "$SRC_RELAY"  -o "$TARGET/tools/android_relay.py"
curl -fsSL "$SRC_DAEMON" -o "$TARGET/contrib/hermes-relay-daemon.py"
chmod 644 "$TARGET/tools/android_relay.py" "$TARGET/contrib/hermes-relay-daemon.py"

# ── PH-4-T3: Signed-Deployment (Integrität, fail-closed bei Mismatch) ────────
# Zwei dokumentierte Prüf-Typen: (1) Release-Metadaten-Manifest (.sha256,
# je Datei, Pflicht für jeden Produktions-Deploy), (2) INSTALL_SHA256 für
# die Hauptdatei. Mismatch / fehlender Manifest-Eintrag = harte Abbruch
# mit erwartet/erhalten (nur Hash-PRÄFIXE — nie Token/Secrets).
RELAY_FILE="$TARGET/tools/android_relay.py"
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

echo "[3b/5] Integritätsprüfung (SHA-256)"
if curl -fsSL "$SHA256_ASSET_URL" -o "$MANIFEST_FILE" 2>/dev/null; then
  VERIFIED=1
  for entry in "$RELAY_FILE:tools/android_relay.py" \
               "$DAEMON_FILE:hermes-relay-daemon.py"; do
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

echo "[4/5] systemd-Unit installieren"
curl -fsSL "$SRC_UNIT" -o /etc/systemd/system/flipsibridge-relay.service
# Zielordner in der Unit patchen (das Original nutzt /opt/hermes-android als Default)
sed -i "s|/opt/hermes-android|$TARGET|g" /etc/systemd/system/flipsibridge-relay.service
grep -q "flipsibridge-relay.service" /etc/systemd/system/flipsibridge-relay.service

echo "[5/5] Token einrichten"
if [ -n "${ANDROID_BRIDGE_TOKEN:-}" ]; then
  mkdir -p "$(dirname "$ENV_FILE")" && chmod 700 "$(dirname "$ENV_FILE")" || true
  echo "ANDROID_BRIDGE_TOKEN=$ANDROID_BRIDGE_TOKEN" > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
else
  if [ -n "$FS_TTY" ]; then
    read -r -p "Kopplungscode aus der App (siehe App-Screen): " CODE
    mkdir -p "$(dirname "$ENV_FILE")" && chmod 700 "$(dirname "$ENV_FILE")" || true
    echo "ANDROID_BRIDGE_TOKEN=$CODE" > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
  else
    echo "⚠ Kein Token angegeben. Manuell setzen:" >&2
    echo "  sudo sed -i 's/^ANDROID_BRIDGE_TOKEN=.*/ANDROID_BRIDGE_TOKEN=<CODE>/' /root/.hermes/.env" >&2
    echo "  (oder: echo 'ANDROID_BRIDGE_TOKEN=<CODE>' > $ENV_FILE && chmod 600 $ENV_FILE)" >&2
  fi
fi

systemctl daemon-reload
systemctl enable --now flipsibridge-relay.service || {
  echo "⚠ systemd-Start fehlgeschlagen — prüfe Journal: journalctl -u flipsibridge-relay.service -f" >&2
  exit 1
}

echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "FlipsiBridge-Relay installiert und gestartet."
echo "  Service : systemctl status flipsibridge-relay"
echo "  Log     : journalctl -u flipsibridge-relay -f"
echo "  URL     : http://<SERVER-IP>:8766  (oder via Reverse-Proxy mit TLS)"
echo "  Token   : $ENV_FILE (rechte 600, root-only)"
echo
echo "⚠ TLS-PFLICHT (Produktion/Domain-Betrieb): Der Relay verweigert den Start"
echo "  auf Netz-IPs ohne TLS (fail-closed). Für Domain-Betrieb natives TLS aktivieren:"
echo "    mkdir -p /etc/flipsibridge/tls && openssl req -x509 -newkey rsa:3072 -sha256 \\"
echo "      -nodes -days 3650 -keyout /etc/flipsibridge/tls/relay.key \\"
echo "      -out /etc/flipsibridge/tls/relay.crt -subj '/CN=<DEINE-DOMAIN>' \\"
echo "      -addext 'subjectAltName=DNS:<DEINE-DOMAIN>'"
echo "    Unit ergänzen: ANDROID_RELAY_TLS=1 + ANDROID_RELAY_CERT/KEY (Pfade s. Unit-"
echo "    Kommentar), ALLOW_PLAINTEXT-Zeile entfernen, systemctl daemon-reload + restart."
echo "  Hinter Reverse-Proxy PFLICHT: ANDROID_RELAY_TRUSTED_PROXY_IP=<Proxy-IP> setzen"
echo "  (sonst landen alle Clients im selben IP-Rate-Limit-Bucket)."
echo
echo "In der Handy-App eintragen:"
echo "  Server  : http://<SERVER-IP>:8766   (LAN) oder https://deine-domain.tld   (Proxy)"
echo "  Code    : der Code aus der App"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"