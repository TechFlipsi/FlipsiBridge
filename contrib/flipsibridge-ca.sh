#!/usr/bin/env bash
# FlipsiBridge mTLS-CA — Client-Zertifikate für Geräte-Zugriff
#
# Erzeugt (einmalig) eine lokale CA und stellt pro Gerät ein Client-Zertifikat
# aus (EKU clientAuth), exportiert als PKCS#12 für die Android-App.
# NPM (ssl_verify_client) prüft gegen die CA — ohne Zertifikat: Abweisung.
#
# Nutzung:
#   ./flipsibridge-ca.sh init                     # CA einmalig erzeugen
#   ./flipsibridge-ca.sh issue <gerätename>       # Client-Zertifikat ausstellen
#   ./flipsibridge-ca.sh print <gerätename>       # Fingerprint + Pfade anzeigen
#
# Ablage: CA + Zertifikate unter /root/mtls-ca/ (NIE ins Repo committen!)
# P12-Passwort: wird generiert und in /root/mtls-ca/<name>.p12pass abgelegt (chmod 600).

set -euo pipefail

CA_DIR="${FLIPSIBRIDGE_CA_DIR:-/root/mtls-ca}"
CA_KEY="$CA_DIR/flipsibridge-ca.key"
CA_CRT="$CA_DIR/flipsibridge-ca.crt"
CA_DAYS=3650      # CA: 10 Jahre
CERT_DAYS=825     # Client-Zertifikate: ~2,25 Jahre (Android-Maximum für User-Certs)

mkdir -p "$CA_DIR"
chmod 700 "$CA_DIR"

cmd="${1:-help}"
case "$cmd" in
  init)
    if [[ -f "$CA_KEY" ]]; then
      echo "CA existiert bereits: $CA_CRT"
      openssl x509 -in "$CA_CRT" -noout -subject -enddate
      exit 0
    fi
    openssl genrsa -out "$CA_KEY" 4096
    openssl req -x509 -new -nodes -key "$CA_KEY" -sha256 -days "$CA_DAYS" \
      -subj "/C=AT/O=Flipsi/CN=FlipsiBridge Root CA" \
      -addext "basicConstraints=critical,CA:TRUE" \
      -out "$CA_CRT"
    echo "✔ CA erzeugt: $CA_CRT"
    openssl x509 -in "$CA_CRT" -noout -subject -fingerprint -sha256
    ;;

  issue)
    name="${2:?Usage: flipsibridge-ca.sh issue <gerätename>}"
    name="$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9-' '-')"
    key="$CA_DIR/$name-client.key"
    crt="$CA_DIR/$name-client.crt"
    p12="$CA_DIR/$name-client.p12"
    pwf="$CA_DIR/$name.p12pass"

    [[ -f "$CA_KEY" ]] || { echo "✗ Keine CA — erst: $0 init"; exit 1; }

    openssl genrsa -out "$key" 3072
    openssl req -new -key "$key" \
      -subj "/C=AT/O=FlipsiBridge/CN=flipsibridge-$name" -out "$CA_DIR/$name.csr"
    # ext Datei mit clientAuth EKU
    cat > "$CA_DIR/$name.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth
EOF
    openssl x509 -req -in "$CA_DIR/$name.csr" -CA "$CA_CRT" -CAkey "$CA_KEY" \
      -CAcreateserial -days "$CERT_DAYS" -sha256 -extfile "$CA_DIR/$name.ext" \
      -out "$crt"

    pw="$(openssl rand -base64 18 | tr -d '/+=' | head -c 20)"
    echo -n "$pw" > "$pwf"; chmod 600 "$pwf"
    openssl pkcs12 -export -inkey "$key" -in "$crt" -certfile "$CA_CRT" \
      -name "FlipsiBridge $name" -passout "pass:$pw" -out "$p12"

    echo "✔ Client-Zertifikat ausgestellt: $name"
    echo "  P12 (für Handy):  $p12"
    echo "  P12-Passwort:     $pwf"
    echo "  Fingerprint:"
    openssl x509 -in "$crt" -noout -fingerprint -sha256
    ;;

  print)
    name="${2:?Usage: flipsibridge-ca.sh print <gerätename>}"
    crt="$CA_DIR/$name-client.crt"
    openssl x509 -in "$crt" -noout -subject -enddate -fingerprint -sha256
    ;;

  *)
    echo "Usage: $0 init | issue <gerätename> | print <gerätename>"
    ;;
esac