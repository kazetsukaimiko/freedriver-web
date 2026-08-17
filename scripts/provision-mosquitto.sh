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

write_pass "${SECRETS}/autonomy.pass"
write_pass "${SECRETS}/api.pass"

if [[ ! -e "${SECRETS}/passwd" ]]; then
  # Official image runs as uid 1883; write the passwd file as root.
  # Read pass files inside the container so passwords are not passed via -e.
  docker run --rm --user root \
    -v "${SECRETS}:/mosquitto/config/secrets" \
    "$IMAGE" \
    sh -c 'mosquitto_passwd -c -b /mosquitto/config/secrets/passwd autonomy "$(tr -d "\n" < /mosquitto/config/secrets/autonomy.pass)" && mosquitto_passwd -b /mosquitto/config/secrets/passwd api "$(tr -d "\n" < /mosquitto/config/secrets/api.pass)"'
  echo "Built Mosquitto passwd file."
else
  echo "passwd already exists; not rebuilding it."
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

# Broker process is uid 1883 and must read passwd + TLS.
# Never chmod 644 a private key. Prefer POSIX ACL; otherwise chown 1883 + 0600.
if command -v setfacl >/dev/null; then
  setfacl -m u:1883:rx "$SECRETS"
  for f in passwd server.crt server.key; do
    if [[ -e "${SECRETS}/${f}" ]]; then
      setfacl -m u:1883:r "${SECRETS}/${f}"
    fi
  done
else
  echo "setfacl not found; chown 1883:1883 and 0600 on the key (no world-readable fallback)."
  for f in passwd server.crt server.key; do
    if [[ -e "${SECRETS}/${f}" ]]; then
      chown 1883:1883 "${SECRETS}/${f}"
    fi
  done
  [[ -e "${SECRETS}/server.key" ]] && chmod 0600 "${SECRETS}/server.key"
  [[ -e "${SECRETS}/server.crt" ]] && chmod 0640 "${SECRETS}/server.crt"
  [[ -e "${SECRETS}/passwd" ]] && chmod 0640 "${SECRETS}/passwd"
fi

chown 1883:1883 "$STORAGE"
chmod 750 "$STORAGE"

echo "Mosquitto secrets in ${SECRETS}; persistence in ${STORAGE}."
echo "Sysadmin still needs ${CN} A → 138.197.90.42. Compose binds 127.0.0.1:8883 until #29 is verified from the internet."
