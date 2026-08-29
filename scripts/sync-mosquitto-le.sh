#!/usr/bin/env bash
# Copy Caddy's Let's Encrypt cert for mqtt.freedriver.io onto Mosquitto's
# secrets files. Restart the broker only when the leaf changed.
# Idempotent. Never prints key material. Does not flip live-commands.
# Does not open 1883. Does not change instanceId.
#
# Caddy issues the name via the mqtt.freedriver.io 404 stub (HTTP-01 on :80).
# Mosquitto still terminates MQTTS on 8883 from:
#   /opt/freedriver-secrets/mosquitto/server.crt
#   /opt/freedriver-secrets/mosquitto/server.key
#
# Exit 0: copied (and restarted unless --no-restart) or already current.
# Exit 2: Caddy has not issued the name yet (soft; deploy may skip).
# Exit 1: hard error.
set -euo pipefail

CN=mqtt.freedriver.io
CADDY_DATA="${CADDY_DATA:-/opt/freedriver-storage/caddy/data}"
SECRETS="${SECRETS:-/opt/freedriver-secrets/mosquitto}"
COMPOSE_DIR="${COMPOSE_DIR:-/opt/freedriver-web}"
COMPOSE_ENV="${COMPOSE_ENV:-/opt/freedriver-secrets/.env}"
NO_RESTART=0

usage() {
  cat <<'EOF'
Usage: sync-mosquitto-le.sh [--no-restart]

  Copy Caddy's LE cert for mqtt.freedriver.io onto
  /opt/freedriver-secrets/mosquitto/server.{crt,key} (uid 1883).
  Restart mosquitto only when the leaf fingerprint changed.

  CADDY_DATA / SECRETS / COMPOSE_DIR / COMPOSE_ENV override paths (CI).
  --no-restart   write files only (CI / dry). live-commands stays false.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-restart)
      NO_RESTART=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

leaf_fp() {
  openssl x509 -in "$1" -noout -fingerprint -sha256
}

has_san() {
  openssl x509 -in "$1" -noout -text | grep -Fq "DNS:${CN}"
}

key_matches_cert() {
  local cert_mod key_mod
  cert_mod="$(openssl x509 -in "$1" -noout -modulus | openssl sha256)"
  key_mod="$(openssl rsa -in "$2" -noout -modulus 2>/dev/null | openssl sha256)"
  [[ "$cert_mod" == "$key_mod" ]]
}

if [[ ! -d "$CADDY_DATA" ]]; then
  echo "Caddy data dir missing: ${CADDY_DATA} (exit 2; cert not issued yet)" >&2
  exit 2
fi

src_crt=""
src_mtime=0
while IFS= read -r -d '' f; do
  m="$(stat -c %Y "$f")"
  if (( m >= src_mtime )); then
    src_mtime=$m
    src_crt=$f
  fi
done < <(find "$CADDY_DATA" -type f -name "${CN}.crt" -print0)

if [[ -z "$src_crt" ]]; then
  echo "No ${CN}.crt under ${CADDY_DATA} (exit 2; Caddy has not issued yet)" >&2
  exit 2
fi

src_key="${src_crt%.crt}.key"
if [[ ! -f "$src_key" ]]; then
  echo "Found ${src_crt} but no matching .key" >&2
  exit 1
fi

if ! has_san "$src_crt"; then
  echo "Refusing ${src_crt}: SAN is not DNS:${CN}" >&2
  exit 1
fi

if ! key_matches_cert "$src_crt" "$src_key"; then
  echo "Refusing ${src_crt}: key does not match cert" >&2
  exit 1
fi

mkdir -p "$SECRETS"

if [[ -f "${SECRETS}/server.crt" ]]; then
  old_fp="$(leaf_fp "${SECRETS}/server.crt")"
  new_fp="$(leaf_fp "$src_crt")"
  if [[ "$old_fp" == "$new_fp" ]]; then
    echo "Mosquitto already has this ${CN} leaf; not restarting."
    exit 0
  fi
fi

umask 077
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp "$src_crt" "${tmp}/server.crt"
cp "$src_key" "${tmp}/server.key"
chmod 0640 "${tmp}/server.crt"
chmod 0600 "${tmp}/server.key"

cp "${tmp}/server.crt" "${SECRETS}/server.crt"
cp "${tmp}/server.key" "${SECRETS}/server.key"
chmod 0640 "${SECRETS}/server.crt"
chmod 0600 "${SECRETS}/server.key"

if [[ "$(id -u)" -eq 0 ]]; then
  if getent passwd 1883 >/dev/null 2>&1 || getent group 1883 >/dev/null 2>&1; then
    chown 1883:1883 "${SECRETS}/server.crt" "${SECRETS}/server.key" || true
  fi
fi

echo "Wrote ${SECRETS}/server.crt and server.key from Caddy ${CN} (contents not printed)."

if [[ "$NO_RESTART" -eq 1 ]]; then
  echo "Skipping mosquitto restart (--no-restart)."
  exit 0
fi

if [[ ! -f "${COMPOSE_DIR}/docker-compose.yml" ]]; then
  echo "compose file missing at ${COMPOSE_DIR}/docker-compose.yml; files written, restart by hand." >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_ENV" ]]; then
  echo "compose env-file missing at ${COMPOSE_ENV}; files written, restart by hand." >&2
  exit 1
fi

docker compose --env-file "$COMPOSE_ENV" -f "${COMPOSE_DIR}/docker-compose.yml" restart mosquitto
echo "Restarted mosquitto so 8883 loads the new cert. live-commands stays false."
