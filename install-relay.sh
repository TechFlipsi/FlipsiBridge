#!/bin/bash
# flipsibridge-relay-setup.sh — Einzeiler-Installer fürs FlipsiBridge-Python-Relay (Server-Seite)
# Nutzung:
#   bash install-relay.sh                 → installiert in /opt/flipsibridge + systemd unit + fragt Token
#   ANDROID_BRIDGE_TOKEN=XXX bash install-relay.sh   → setzt Token nicht-interaktiv
#   FLIPSI_TARGET=/pfad bash install-relay.sh        → eigenes Install-Verzeichnis
#
# Voraussetzungen: python3 (≥3.11), systemd, Internet-Zugriff zum GitHub.
set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/TechFlipsi/FlipsiBridge/main"
SRC_RELAY="$REPO_RAW/tools/android_relay.py"
SRC_DAEMON="$REPO_RAW/contrib/hermes-relay-daemon.py"
SRC_UNIT="$REPO_RAW/contrib/flipsibridge-relay.service"
TARGET="${FLIPSI_TARGET:-/opt/flipsibridge}"
ENV_FILE="${FLIPSI_ENV:-/root/.secrets/flipsibridge-relay.env}"

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
echo "In der Handy-App eintragen:"
echo "  Server  : http://<SERVER-IP>:8766   (LAN) oder https://deine-domain.tld   (Proxy)"
echo "  Code    : der Code aus der App"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"