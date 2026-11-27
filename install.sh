#!/usr/bin/env bash
# One-liner install: hermes-android plugin for hermes-agent v0.23.0+
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/TechFlipsi/FlipsiBridge/main/install.sh | bash
#
# Installs the plugin package (58 android_* tools) into ~/.hermes/plugins.
# The server-side relay (recommended) is installed separately via
# install-relay.sh — see docs/INSTALL-GUIDE.md for the illustrated walkthrough.
set -euo pipefail

PLUGIN_DIR="$HOME/.hermes/plugins/hermes-android"
REPO="https://github.com/TechFlipsi/FlipsiBridge.git"
TMP="$(mktemp -d)"

trap 'rm -rf "$TMP"' EXIT

echo "→ FlipsiBridge-Plugin installieren (hermes-agent v0.23.0+)…"
echo "→ Klone Repo (shallow)…"
git clone --quiet --depth 1 "$REPO" "$TMP/flipsibridge"

mkdir -p "$HOME/.hermes/plugins"
rm -rf "$PLUGIN_DIR"
cp -r "$TMP/flipsibridge/hermes-android-plugin" "$PLUGIN_DIR"

# Python-Abhängigkeiten (gleiche Pins wie plugin.yaml)
if ! python3 -c "import aiohttp, requests" >/dev/null 2>&1; then
  echo "→ Installiere aiohttp + requests…"
  python3 -m pip install --user "aiohttp>=3.9,<4" "requests>=2.28" \
    || pip3 install "aiohttp>=3.9,<4" "requests>=2.28"
fi

echo "✓ Plugin installiert: $PLUGIN_DIR"
echo "  Hermes neu starten, dann /plugins prüfen — erwartet: hermes-android (58 Tools)."
echo "  Server-Relay (empfohlen): bash install-relay.sh — Anleitung: docs/INSTALL-GUIDE.md"
