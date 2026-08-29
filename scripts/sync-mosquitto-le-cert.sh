#!/bin/sh
# Copy Caddy's issued mqtt.freedriver.io cert+key onto Mosquitto MQTTS 8883.
#
# Stock caddy:2 emits cert_obtained but has no exec handler (that is a
# plugin). Certs land under /data/caddy/certificates/<issuer>/<name>/.
# This script is the Techops renew hook: compose sidecar runs --watch
# (start + every LE renew). Manual: sudo ./scripts/sync-mosquitto-le-cert.sh
#
# Replaces /opt/freedriver-secrets/mosquitto/server.{crt,key} in place.
# No self-signed leftovers. uid 1883 can read the key; not world-readable.
# Reloads Mosquitto via SIGHUP in a shared PID namespace (compose
# pid: service:mosquitto). No Docker daemon socket, no Docker CLI.
# Does not touch passwd, ACL, instanceId, or 1883. live-commands stays false.
set -eu

DOMAIN="${DOMAIN:-mqtt.freedriver.io}"
CADDY_CERTS="${CADDY_CERTS:-/caddy-data/caddy/certificates}"
DEST_DIR="${DEST_DIR:-/mosquitto-secrets}"
DEST_CRT="${DEST_DIR}/server.crt"
DEST_KEY="${DEST_DIR}/server.key"
MQTT_UID="${MQTT_UID:-1883}"
MQTT_GID="${MQTT_GID:-1883}"
POLL_SECONDS="${POLL_SECONDS:-20}"

usage() {
  cat <<'EOF'
Usage: sync-mosquitto-le-cert.sh [--watch|--once]

  --once    Copy if Caddy has mqtt.freedriver.io material (default)
  --watch   --once, then poll until Caddy writes/renews the cert

Host paths (override when not in the sidecar):
  CADDY_CERTS=/opt/freedriver-storage/caddy/data/caddy/certificates
  DEST_DIR=/opt/freedriver-secrets/mosquitto
EOF
}

MODE=once
case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  --watch)
    MODE=watch
    ;;
  --once|"")
    MODE=once
    ;;
  *)
    echo "Unknown argument: $1" >&2
    usage >&2
    exit 1
    ;;
esac

is_pem_cert() {
  grep -qF -- "-----BEGIN CERTIFICATE-----" "$1" 2>/dev/null
}

is_pem_key() {
  # Caddy 2 default is P-256 PKCS#8 (BEGIN PRIVATE KEY), not RSA.
  grep -qE -- "-----BEGIN (RSA |EC )?PRIVATE KEY-----" "$1" 2>/dev/null
}

# Newest Caddy leaf+key for DOMAIN. Prefer Let's Encrypt issuer dir.
find_caddy_pair() {
  src_crt=""
  src_key=""
  if [ ! -d "$CADDY_CERTS" ]; then
    return 1
  fi

  le_crt="${CADDY_CERTS}/acme-v02.api.letsencrypt.org-directory/${DOMAIN}/${DOMAIN}.crt"
  le_key="${CADDY_CERTS}/acme-v02.api.letsencrypt.org-directory/${DOMAIN}/${DOMAIN}.key"
  if [ -f "$le_crt" ] && [ -f "$le_key" ]; then
    src_crt="$le_crt"
    src_key="$le_key"
    return 0
  fi

  newest=""
  newest_mtime=0
  for crt in "${CADDY_CERTS}"/*/"${DOMAIN}/${DOMAIN}.crt"; do
    [ -f "$crt" ] || continue
    key="${crt%.crt}.key"
    [ -f "$key" ] || continue
    mt=$(stat -c %Y "$crt" 2>/dev/null || stat -f %m "$crt")
    if [ "$mt" -ge "$newest_mtime" ]; then
      newest="$crt"
      newest_mtime="$mt"
    fi
  done
  if [ -n "$newest" ]; then
    src_crt="$newest"
    src_key="${newest%.crt}.key"
    return 0
  fi
  return 1
}

cert_names_ok() {
  # openssl is optional (sidecar image has it; CI fixture may too).
  if ! command -v openssl >/dev/null 2>&1; then
    return 0
  fi
  text=$(openssl x509 -in "$1" -noout -subject -ext subjectAltName 2>/dev/null || true)
  echo "$text" | grep -Fq "$DOMAIN"
}

key_matches_cert() {
  if ! command -v openssl >/dev/null 2>&1; then
    return 0
  fi
  # DER SPKI digest works for Caddy's default P-256 and for RSA.
  crt_pub=$(openssl x509 -in "$1" -noout -pubkey 2>/dev/null | openssl pkey -pubin -outform DER 2>/dev/null | openssl md5)
  key_pub=$(openssl pkey -in "$2" -pubout -outform DER 2>/dev/null | openssl md5)
  [ -n "$crt_pub" ] && [ "$crt_pub" = "$key_pub" ]
}

drop_selfsigned_leftovers() {
  # Mosquitto only loads server.crt / server.key, but do not leave a
  # renamed self-signed pair that a later bind or typo could pick up.
  rm -f \
    "${DEST_DIR}/server.crt.selfsigned" \
    "${DEST_DIR}/server.key.selfsigned" \
    "${DEST_DIR}/server.crt.bak" \
    "${DEST_DIR}/server.key.bak" \
    "${DEST_DIR}/server.crt.old" \
    "${DEST_DIR}/server.key.old"
}

# Shared PID namespace with compose `pid: service:mosquitto`.
# eclipse-mosquitto:2.1.2-alpine execs mosquitto as pid 1; if it does not,
# walk /proc for comm=mosquitto. Never kill pid 1 unless it is mosquitto
# (host --once / CI fixture must not HUP systemd or this script).
# Do not talk to the Docker daemon.
reload_mosquitto() {
  pid=""
  if [ -r /proc/1/comm ]; then
    comm1=$(tr -d '\0' < /proc/1/comm 2>/dev/null || true)
    if [ "$comm1" = "mosquitto" ]; then
      pid=1
    fi
  fi
  if [ -z "$pid" ]; then
    for commfile in /proc/[0-9]*/comm; do
      [ -r "$commfile" ] || continue
      name=$(tr -d '\0' < "$commfile" 2>/dev/null || true)
      [ "$name" = "mosquitto" ] || continue
      p=${commfile#/proc/}
      p=${p%/comm}
      [ "$p" != "$$" ] || continue
      pid=$p
      break
    done
  fi
  if [ -n "$pid" ] && kill -HUP "$pid" 2>/dev/null; then
    echo "Sent SIGHUP to mosquitto pid ${pid}."
    return 0
  fi
  echo "Copied certs; no mosquitto pid in this namespace to SIGHUP (ok for --once/CI). First install is read on mosquitto start."
}

install_pair() {
  crt="$1"
  key="$2"

  if ! is_pem_cert "$crt"; then
    echo "Refusing to install: source is not a PEM cert." >&2
    return 1
  fi
  if ! is_pem_key "$key"; then
    echo "Refusing to install: source is not a PEM key." >&2
    return 1
  fi
  if ! cert_names_ok "$crt"; then
    echo "Refusing to install: cert is not for ${DOMAIN}." >&2
    return 1
  fi
  if ! key_matches_cert "$crt" "$key"; then
    echo "Refusing to install: key does not match cert." >&2
    return 1
  fi

  if [ -f "$DEST_CRT" ] && [ -f "$DEST_KEY" ] \
    && cmp -s "$crt" "$DEST_CRT" && cmp -s "$key" "$DEST_KEY"; then
    drop_selfsigned_leftovers
    echo "Mosquitto already has the current ${DOMAIN} cert."
    return 0
  fi

  if [ ! -d "$DEST_DIR" ]; then
    echo "Secrets dir missing: ${DEST_DIR}" >&2
    return 1
  fi

  umask 077
  tmp_crt=$(mktemp "${DEST_DIR}/.server.crt.tmp.XXXXXX")
  tmp_key=$(mktemp "${DEST_DIR}/.server.key.tmp.XXXXXX")
  cleanup_tmp() { rm -f "$tmp_crt" "$tmp_key"; }

  cat "$crt" > "$tmp_crt"
  cat "$key" > "$tmp_key"
  chmod 0640 "$tmp_crt"
  chmod 0600 "$tmp_key"
  if [ "$(id -u)" -eq 0 ]; then
    chown "${MQTT_UID}:${MQTT_GID}" "$tmp_crt" "$tmp_key"
  fi

  # In-place replace of the live self-signed (or prior LE). Same names
  # Mosquitto already loads — no server.crt.selfsigned leftover.
  mv -f "$tmp_crt" "$DEST_CRT"
  mv -f "$tmp_key" "$DEST_KEY"
  cleanup_tmp
  if [ "$(id -u)" -eq 0 ]; then
    chown "${MQTT_UID}:${MQTT_GID}" "$DEST_CRT" "$DEST_KEY"
  fi
  chmod 0640 "$DEST_CRT"
  chmod 0600 "$DEST_KEY"
  drop_selfsigned_leftovers

  echo "Installed ${DOMAIN} cert over ${DEST_CRT} (replaced prior material in place)."
  reload_mosquitto
}

sync_once() {
  if ! find_caddy_pair; then
    echo "No Caddy cert for ${DOMAIN} yet under ${CADDY_CERTS}."
    return 1
  fi
  install_pair "$src_crt" "$src_key"
}

if [ "$MODE" = once ]; then
  sync_once
  exit $?
fi

echo "Watching Caddy certs for ${DOMAIN} under ${CADDY_CERTS} (poll ${POLL_SECONDS}s)."
while :; do
  sync_once || true
  sleep "$POLL_SECONDS"
done
