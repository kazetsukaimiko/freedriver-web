#!/usr/bin/env bash
# Provision Mosquitto MQTTS secrets and storage on the VPS.
# Run as root/sudo. Idempotent: never overwrites existing pass or cert files.
# Do not echo passwords. GitHub Actions must not run this script.
set -euo pipefail

SECRETS=/opt/freedriver-secrets/mosquitto
STORAGE=/opt/freedriver-storage/mosquitto
IMAGE=eclipse-mosquitto:2.1.2-alpine
CN=mqtt.freedriver.io
GROUP=lonewatt-techops

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

if ! getent group "$GROUP" >/dev/null; then
  echo "Group ${GROUP} is missing; create it before re-running." >&2
  exit 1
fi

if ! command -v docker >/dev/null; then
  echo "docker is required to build the passwd file with mosquitto_passwd." >&2
  exit 1
fi

mkdir -p "$SECRETS" "$STORAGE"

write_pass() {
  local path="$1"
  if [[ -e "$path" ]]; then
    echo "Leaving existing $(basename "$path") in place."
    return
  fi
  umask 037
  openssl rand -base64 24 > "$path"
  chown "root:${GROUP}" "$path"
  chmod 640 "$path"
  echo "Wrote $(basename "$path") (contents not printed)."
}

if [[ ! -e "${SECRETS}/passwd" ]]; then
  write_pass "${SECRETS}/autonomy.pass"
  write_pass "${SECRETS}/api.pass"

  # Official image runs as uid 1883; write the passwd file as root.
  # Passwords travel via env into the short-lived container; they are not echoed.
  docker run --rm --user root \
    -v "${SECRETS}:/mosquitto/config/secrets" \
    -e AUTONOMY_PASS="$(tr -d '\n' < "${SECRETS}/autonomy.pass")" \
    -e API_PASS="$(tr -d '\n' < "${SECRETS}/api.pass")" \
    "$IMAGE" \
    sh -c 'mosquitto_passwd -c -b /mosquitto/config/secrets/passwd autonomy "$AUTONOMY_PASS" && mosquitto_passwd -b /mosquitto/config/secrets/passwd api "$API_PASS"'
  echo "Built Mosquitto passwd file."
else
  echo "passwd already exists; not generating passwords or rebuilding it."
fi

if [[ -e "${SECRETS}/server.crt" && -e "${SECRETS}/server.key" ]]; then
  echo "TLS certs already present; leaving them in place."
elif [[ -e "${SECRETS}/server.crt" || -e "${SECRETS}/server.key" ]]; then
  echo "Partial TLS material exists; refusing to overwrite. Fix by hand." >&2
  exit 1
else
  openssl req -x509 -newkey rsa:4096 -sha256 -days 365 -nodes \
    -keyout "${SECRETS}/server.key" \
    -out "${SECRETS}/server.crt" \
    -subj "/CN=${CN}" \
    -addext "subjectAltName=DNS:${CN}"
  echo "Wrote self-signed cert for ${CN} (365 days). Let's Encrypt can replace it later."
fi

chown "root:${GROUP}" "$SECRETS"
chmod 750 "$SECRETS"
find "$SECRETS" -type f -exec chown "root:${GROUP}" {} +
find "$SECRETS" -type f -exec chmod 640 {} +

# Broker process is uid 1883 and must read passwd + TLS. Host ownership stays
# root:lonewatt-techops; grant the container user via POSIX ACL when available.
if command -v setfacl >/dev/null; then
  setfacl -m u:1883:rx "$SECRETS"
  for f in passwd server.crt server.key; do
    if [[ -e "${SECRETS}/${f}" ]]; then
      setfacl -m u:1883:r "${SECRETS}/${f}"
    fi
  done
else
  chmod 755 "$SECRETS"
  for f in passwd server.crt server.key; do
    if [[ -e "${SECRETS}/${f}" ]]; then
      chmod 644 "${SECRETS}/${f}"
    fi
  done
  echo "setfacl not found; passwd/certs are other-readable so uid 1883 can start."
fi

chown 1883:1883 "$STORAGE"
chmod 750 "$STORAGE"

echo "Mosquitto secrets in ${SECRETS}; persistence in ${STORAGE}."
echo "Sysadmin still needs ${CN} A → 138.197.90.42. 8883 stays world-reachable unless Scott's home IP is stable."
