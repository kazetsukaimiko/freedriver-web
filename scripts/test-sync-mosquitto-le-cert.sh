#!/usr/bin/env bash
# Fixture test for sync-mosquitto-le-cert.sh. No live secrets, no git certs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SYNC="${ROOT}/scripts/sync-mosquitto-le-cert.sh"
WORKDIR=$(mktemp -d)
OUT="${WORKDIR}/sync.out"
trap 'rm -rf "$WORKDIR"' EXIT

CADDY_CERTS="${WORKDIR}/caddy/certificates"
DEST_DIR="${WORKDIR}/mosquitto-secrets"
LE_DIR="${CADDY_CERTS}/acme-v02.api.letsencrypt.org-directory/mqtt.freedriver.io"
mkdir -p "$LE_DIR" "$DEST_DIR"

openssl req -x509 -newkey rsa:2048 -sha256 -days 2 -nodes \
  -keyout "${WORKDIR}/old.key" \
  -out "${WORKDIR}/old.crt" \
  -subj "/CN=mqtt.freedriver.io" \
  -addext "subjectAltName=DNS:mqtt.freedriver.io" >/dev/null 2>&1
openssl req -x509 -newkey rsa:2048 -sha256 -days 2 -nodes \
  -keyout "${LE_DIR}/mqtt.freedriver.io.key" \
  -out "${LE_DIR}/mqtt.freedriver.io.crt" \
  -subj "/CN=mqtt.freedriver.io" \
  -addext "subjectAltName=DNS:mqtt.freedriver.io" >/dev/null 2>&1

# Live self-signed plus a leftover name Mosquitto must not keep.
cp "${WORKDIR}/old.crt" "${DEST_DIR}/server.crt"
cp "${WORKDIR}/old.key" "${DEST_DIR}/server.key"
cp "${WORKDIR}/old.crt" "${DEST_DIR}/server.crt.selfsigned"
chmod 0644 "${DEST_DIR}/server.key"

CADDY_CERTS="$CADDY_CERTS" DEST_DIR="$DEST_DIR" \
  "$SYNC" --once

cmp -s "${LE_DIR}/mqtt.freedriver.io.crt" "${DEST_DIR}/server.crt"
cmp -s "${LE_DIR}/mqtt.freedriver.io.key" "${DEST_DIR}/server.key"
if [[ -e "${DEST_DIR}/server.crt.selfsigned" || -e "${DEST_DIR}/server.key.selfsigned" ]]; then
  echo "must replace in place and drop self-signed leftovers" >&2
  exit 1
fi
key_mode=$(stat -c %a "${DEST_DIR}/server.key")
crt_mode=$(stat -c %a "${DEST_DIR}/server.crt")
if [[ "$key_mode" != "600" ]]; then
  echo "server.key must be 0600, got ${key_mode}" >&2
  exit 1
fi
if [[ "$crt_mode" != "640" ]]; then
  echo "server.crt must be 0640, got ${crt_mode}" >&2
  exit 1
fi

# Second run is a no-op (same bytes).
CADDY_CERTS="$CADDY_CERTS" DEST_DIR="$DEST_DIR" \
  "$SYNC" --once

# Caddy 2 default is P-256 PKCS#8. Replace again with an EC pair.
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 -days 2 -nodes \
  -keyout "${LE_DIR}/mqtt.freedriver.io.key" \
  -out "${LE_DIR}/mqtt.freedriver.io.crt" \
  -subj "/CN=mqtt.freedriver.io" \
  -addext "subjectAltName=DNS:mqtt.freedriver.io" >/dev/null 2>&1
CADDY_CERTS="$CADDY_CERTS" DEST_DIR="$DEST_DIR" \
  "$SYNC" --once
cmp -s "${LE_DIR}/mqtt.freedriver.io.crt" "${DEST_DIR}/server.crt"
cmp -s "${LE_DIR}/mqtt.freedriver.io.key" "${DEST_DIR}/server.key"
grep -qF -- "-----BEGIN PRIVATE KEY-----" "${DEST_DIR}/server.key"
[[ ! -e "${DEST_DIR}/server.crt.selfsigned" ]]

echo "sync-mosquitto-le-cert fixture ok"
